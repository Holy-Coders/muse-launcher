import pytest
from puck_muse import ReplyTracker, COMMANDS, api_root, noise_host, Subscription
import asyncio
from types import SimpleNamespace


def event(seq, kind, mid, parent=None, text=""):
    p = {"message_id": mid, "text": text}
    if parent is not None:
        p["reply_to_message_id"] = parent
    return {"type": "event", "seq": seq, "event_name": kind, "payload": p}


def test_reply_requires_ack_and_matching_parent():
    t = ReplyTracker()
    t.feed(event(1, "message.assistant", "unrelated", "different-user-turn", "Wrong chat"))
    t.feed(event(2, "delta.message_start", "a", "user-1"))
    t.feed(event(3, "delta.text_append", "a", None, "Correct "))
    assert t.result is None
    t.acknowledge({"message_id": "user-1"})
    t.feed(event(4, "delta.text_append", "a", None, "reply"))
    t.feed(event(5, "delta.message_done", "a"))
    assert t.result == "Correct reply"
    t.feed(event(6, "message.assistant", "a", "user-1", "Duplicate"))
    assert t.result == "Correct reply"


def test_parentless_full_message_is_not_a_reply():
    t = ReplyTracker(); t.acknowledge({"message_id": "user-1"})
    t.feed(event(1, "message.assistant", "other", None, "Unrelated"))
    t.feed(event(2, "message.assistant", "other", "user-2", "Wrong parent"))
    assert t.result is None


def test_duplicate_sequence_does_not_append_twice_and_text_is_bounded():
    t = ReplyTracker(); t.acknowledge({"message_id": "user"})
    t.feed(event(1, "delta.text_append", "a", "user", "x" * 5000))
    t.feed(event(1, "delta.text_append", "a", None, "repeat"))
    t.feed(event(2, "delta.message_done", "a"))
    assert t.result == "x" * 4000


def test_early_buffer_overflow_fails_closed():
    t = ReplyTracker()
    for i in range(64): t.feed(event(i, "message.assistant", "a", "u", "bounded"))
    with pytest.raises(ValueError): t.feed(event(65, "message.assistant", "a", "u", "overflow"))


def test_missing_ack_id_is_rejected():
    with pytest.raises(ValueError): ReplyTracker().acknowledge({"ok": True})


def test_official_result_envelope_correlates_early_reply():
    t = ReplyTracker()
    t.feed(event(1, "message.assistant", "a", "user-1", "Correlated"))
    t.acknowledge({"result": {"message_id": "user-1"}})
    assert t.result == "Correlated"


def test_only_workspace_command_registered():
    assert set(COMMANDS) == {"puck.request", "puck.update", "puck.command"}
    assert all(x not in COMMANDS for x in ("system.run", "file.read", "file.write", "device.ota"))


@pytest.mark.parametrize("value", ["http://api.muse.ai", "https://evil.example", "https://api.muse.ai@evil.example", "https://api.muse.ai:444", "https://api.muse.ai?token=x"])
def test_credentials_cannot_be_sent_to_an_untrusted_api(value):
    with pytest.raises(ValueError): api_root(value)


@pytest.mark.parametrize("value", ["evil.example", "hatch.metaaivm.com/evil", "user@hatch.metaaivm.com", "hatch.metaaivm.com:443", "muse.ai.evil.example"])
def test_noise_destination_is_bounded(value):
    with pytest.raises(ValueError): noise_host(value)


def test_subscription_split_rows_and_cancelled_turn_ignore_late_frames():
    async def check():
        sub = Subscription(asyncio.get_running_loop())
        frame = SimpleNamespace(kind="response", value=SimpleNamespace(status=200, body=b'{"type":"event",', end_body=False))
        sub.on_frame(frame)
        sub.acknowledge({"message_id": "u"})
        assert sub.ready.done() and not sub.done.done()
        sub.done.cancel()
        sub.on_frame(SimpleNamespace(kind="body", value=SimpleNamespace(data=b'"seq":1,"event_name":"message.assistant","payload":{"message_id":"a","reply_to_message_id":"u","text":"late"}}\n', end_body=False)))
        assert sub.done.cancelled() and sub.tracker.result is None
    asyncio.run(check())


def test_android_adapter_real_noise_round_trip_and_correlated_subscription():
    from puck_muse import AndroidSession, AndroidDescription, Runtime
    from reference.test_link_client import Pipe, FakeVm
    from musegadget.noise import ServiceFrame, ApplicationResponse, BodyChunk
    from musegadget.link_client import Outcome
    import json

    async def scenario():
        to_device, to_vm = asyncio.Queue(), asyncio.Queue()
        device_ws, vm_ws = Pipe(to_device, to_vm), Pipe(to_vm, to_device)
        connected = asyncio.Event()
        proposals = []
        class Callbacks:
            def onState(self, state, message):
                if state == "connected": connected.set()
            def onWorkspace(self, request):
                proposals.append(request); return True
            def onUpdate(self, request):
                proposals.append(("update", json.loads(request))); return True
            def onCommand(self, request):
                proposals.append(("command", json.loads(request))); return True
        callbacks = Callbacks()
        runtime = Runtime(callbacks, "02:00:00:ab:cd:ef")
        session = AndroidSession(callbacks=callbacks, noise_host="hatch.metaaivm.com", vm_id="test", vm_auth_token="fixture-only", device=AndroidDescription(node_id="homelink-abcdef", display_name="Puck", version="test", commands=COMMANDS), run_command=runtime.command)
        async def open_pipe(): return device_ws
        session._open = open_pipe
        vm = FakeVm(vm_ws)
        stop = asyncio.Event()
        running = asyncio.create_task(session.run(stop))
        await vm.handshake(); await vm.accept_control_stream()
        register = await vm.next_message()
        assert set(register["params"]["commands_v2"]) == {"puck.request", "puck.update", "puck.command"}
        assert register["params"]["platform"] == "linux"
        assert register["params"]["model_id"] == "linux"
        assert register["params"]["device_family"] == "homehub"
        await vm.send_message({"type": "res", "id": register["id"], "ok": True, "result": {"status": "registered"}})
        await asyncio.wait_for(connected.wait(), 2)
        assert await vm.next_message() == {"method": "link.heartbeat"}
        turn = asyncio.create_task(session.turn("test only", "puck-isolated-chat"))
        chat = await vm.next_frame()
        assert chat.value.path == "/chat/stream"
        assert json.loads(chat.value.body)["session_id"] == "puck-isolated-chat"
        await vm.send_frame(ServiceFrame.response(chat.stream_id, ApplicationResponse(status=200, body=b'{"result":{"message_id":"user-1"}}', end_body=True)))
        subscription = await vm.next_frame()
        assert subscription.value.path == "/chat/subscribe"
        assert json.loads(subscription.value.body)["session_id"] == "puck-isolated-chat"
        await vm.send_frame(ServiceFrame.response(subscription.stream_id, ApplicationResponse(status=200, end_body=False)))
        rows = [event(1, "message.assistant", "wrong", "other-turn", "WRONG CHAT"), event(2, "message.assistant", "reply-1", "user-1", "Encrypted reply verified")]
        data = b"".join(json.dumps(r).encode() + b"\n" for r in rows)
        await vm.send_frame(ServiceFrame.body_chunk(subscription.stream_id, BodyChunk(data=data)))
        assert await asyncio.wait_for(turn, 2) == "Encrypted reply verified"
        ended = await vm.next_frame()
        assert ended.stream_id == subscription.stream_id and ended.value.end_body
        await vm.send_message({"method": "link.invoke", "id": "bad", "command": "system.run", "params": {"command": "fixture"}})
        rejected = await vm.next_message()
        assert rejected["ok"] is False and not proposals
        # An unsolicited push over the encrypted control link reaches the bridge
        # even without a new local chat request. No action is executed by receipt.
        await vm.send_message({"method": "link.invoke", "id": "push", "command": "puck.update", "params": {"id": "new-update", "title": "Update", "text": "Visible", "mood": "cheerful"}})
        pushed = await vm.next_message()
        assert pushed["ok"] and not pushed["payload"]["executed"]
        assert proposals == [("update", {"id": "new-update", "title": "Update", "text": "Visible", "mood": "cheerful"})]
        await vm.send_message({"method": "link.invoke", "id": "proposal", "command": "puck.command", "params": {"id": "review-camera", "action": "camera"}})
        proposed = await vm.next_message()
        assert proposed["ok"] and proposed["payload"]["approval_required"] and not proposed["payload"]["executed"]
        stop.set()
        assert await asyncio.wait_for(running, 2) == Outcome.STOPPED
    asyncio.run(scenario())


def test_android_setup_offers_current_connection_as_open_not_password_protected():
    # Regression for the real Muse app requesting a password when 'secure' was absent.
    from puck_muse import Runtime
    class Callbacks:
        def isOnline(self): return True
        def getMtu(self): return 185
        def sendPacket(self, value): return True
        def disconnectPeer(self): pass
    runtime = Runtime(Callbacks(), "02:00:00:ab:cd:ef")
    try:
        runtime.start_pairing("mgst_" + "A" * 43)  # synthetic canonical fixture, never a service token
        scan = runtime.controller._network.current_connection_entry()
        assert scan == {"ssid": "Current phone connection", "rssi": -40, "secure": False}
    finally:
        runtime.stop_pairing()


def test_reply_root_fields_match_official_protocol():
    t = ReplyTracker(); t.acknowledge({"message_id": "u"})
    t.feed({"type": "event", "seq": 1, "event": "message.assistant", "message_id": "a", "reply_to_message_id": "u", "display_text": "Root reply"})
    assert t.result == "Root reply"


def test_large_history_batch_is_parsed_per_row_without_accepting_history():
    import json
    async def check():
        sub = Subscription(asyncio.get_running_loop())
        sub.acknowledge({"message_id": "current"})
        history = {"type": "snapshot", "content": "x" * 80000}
        data = json.dumps(history).encode() + b"\n" + json.dumps(event(1, "message.assistant", "a", "current", "Current reply")).encode() + b"\n"
        sub.on_frame(SimpleNamespace(kind="response", value=SimpleNamespace(status=200, body=data, end_body=False)))
        assert await sub.done == "Current reply" and not sub.buf
    asyncio.run(check())


def test_oversized_incomplete_subscription_row_fails_closed():
    async def check():
        sub = Subscription(asyncio.get_running_loop())
        sub.on_frame(SimpleNamespace(kind="response", value=SimpleNamespace(status=200, body=b"x" * 262145, end_body=False)))
        with pytest.raises(ValueError, match="subscription row exceeded"): await sub.done
    asyncio.run(check())


def test_parentless_reply_requires_matching_ack_user_in_same_dedicated_chat():
    t = ReplyTracker("puck-chat")
    user = event(1, "message.user", "u"); user["payload"]["session_id"] = "puck-chat"
    reply = event(2, "message.assistant", "a", text="Scoped reply"); reply["payload"]["session_id"] = "puck-chat"
    t.feed(user); t.feed(reply)
    assert t.result is None
    t.acknowledge({"message_id": "u"})
    assert t.result == "Scoped reply"


@pytest.mark.parametrize("user_id,reply_chat,next_user", [("wrong-user", "puck-chat", None), ("u", "another-chat", None), ("u", "puck-chat", "later-user")])
def test_scoped_parentless_reply_rejects_wrong_turn_chat_or_intervening_user(user_id, reply_chat, next_user):
    t = ReplyTracker("puck-chat"); t.acknowledge({"message_id": "u"})
    user = event(1, "message.user", user_id); user["payload"]["session_id"] = "puck-chat"; t.feed(user)
    if next_user:
        intervening = event(2, "message.user", next_user); intervening["payload"]["session_id"] = "puck-chat"; t.feed(intervening)
    reply = event(3, "message.assistant", "a", text="Must ignore"); reply["payload"]["session_id"] = reply_chat; t.feed(reply)
    assert t.result is None


def test_isolated_turn_chat_accepts_parentless_reply_after_ack_only():
    t = ReplyTracker("new-turn-chat", isolated=True)
    wrong = event(1, "message.assistant", "wrong", text="Wrong chat")
    wrong["payload"]["session_id"] = "other-turn-chat"
    right = event(2, "message.assistant", "right", text="Own turn")
    right["payload"]["session_id"] = "new-turn-chat"
    t.feed(wrong); t.feed(right)
    assert t.result is None
    t.acknowledge({"message_id": "u"})
    assert t.result == "Own turn"


def test_isolated_chat_still_rejects_explicit_wrong_parent():
    t = ReplyTracker("new-turn-chat", isolated=True); t.acknowledge({"message_id": "u"})
    row = event(1, "message.assistant", "a", "other-user", "Ignore")
    row["payload"]["session_id"] = "new-turn-chat"
    t.feed(row)
    assert t.result is None


def test_push_update_and_command_are_queued_but_never_claim_execution():
    from puck_muse import Runtime
    import json
    received = []
    class Callbacks:
        def onUpdate(self, value): received.append(("update", json.loads(value))); return True
        def onCommand(self, value): received.append(("command", json.loads(value))); return True
        def onWorkspace(self, value): return False
    runtime = Runtime(Callbacks(), "02:00:00:ab:cd:ef")
    update = runtime.command("puck.update", {"id": "update-1", "title": "News", "text": "Ready", "mood": "cheerful"}, None)
    action = runtime.command("puck.command", {"id": "action-1", "action": "camera"}, None)
    assert update["ok"] and not update["payload"]["executed"] and not update["payload"]["approval_required"]
    assert action["ok"] and not action["payload"]["executed"] and action["payload"]["approval_required"]
    assert received[1][1] == {"id": "action-1", "action": "camera"}
    assert not runtime.command("system.run", {"command": "bad"}, None)["ok"]
    assert not runtime.command("puck.update", {"text": "x" * 17000}, None)["ok"]

@pytest.mark.parametrize("instruction", ["", "Only return one JSON information update", "Return exactly one JSON object, no markdown or extra prose."])
def test_reply_mode_reuses_persistent_chat_with_unique_reply_markers(monkeypatch, instruction):
    import concurrent.futures
    import json
    from puck_muse import Runtime
    captured = []
    class Callbacks:
        def onState(self, *args): pass
    class Session:
        registered_at = 1
        def turn(self, text, chat, marker, attachments,progress=None):
            captured.append((text, chat, marker, attachments))
            async def reply(): return "Ready"
            return reply()
    def submit(coro, loop):
        coro.close()
        future = concurrent.futures.Future()
        future.set_result("Ready")
        return future
    monkeypatch.setattr(asyncio, "run_coroutine_threadsafe", submit)
    runtime = Runtime(Callbacks(), "02:00:00:ab:cd:ef")
    runtime.loop = object()
    runtime.session = Session()
    for request in ["local-one", "local-two"]:
        assert json.loads(runtime.send(request, "Hello", "personal-chat-fixture", instruction))["text"] == "Ready"
    assert captured[0][1] == captured[1][1] == "personal-chat-fixture"
    assert captured[0][2] != captured[1][2]
    assert all(row[2] in row[0] for row in captured)
    assert not runtime.pending
    if instruction:
        expected=instruction.replace("Return exactly one JSON object, no markdown or extra prose.","After the mandatory reply prefix line, return exactly one JSON object, no markdown or extra prose.")
        assert expected in captured[0][0]
        assert "use this gadget's puck.update" not in captured[0][0]
    else:
        assert "puck.update" in captured[0][0]


def test_local_reports_reuse_the_same_side_chat(monkeypatch):
    import concurrent.futures
    from puck_muse import Runtime
    captured = []
    class Callbacks:
        def onRequestFailed(self, *args): pytest.fail("Report should be accepted")
    class Session:
        registered_at = 1
        turn_lock = asyncio.Lock()
        async def send_chat(self, text, chat):
            captured.append(chat)
            return {"ok": True}
    def submit(coro, loop):
        future = concurrent.futures.Future()
        future.set_result(asyncio.run(coro))
        return future
    monkeypatch.setattr(asyncio, "run_coroutine_threadsafe", submit)
    runtime = Runtime(Callbacks(), "02:00:00:ab:cd:ef")
    runtime.loop = object()
    runtime.session = Session()
    for _ in range(2):
        assert runtime.notify('{"status":"handed_off"}', "personal-chat-fixture")
    assert captured == ["personal-chat-fixture", "personal-chat-fixture"]


def test_persistent_chat_rejects_stale_parentless_reply_even_after_current_user():
    t = ReplyTracker("puck-chat", reply_marker="PUCK_REPLY:fresh\n")
    t.acknowledge({"message_id": "u"})
    user = event(1, "message.user", "u"); user["payload"]["session_id"] = "puck-chat"; t.feed(user)
    for seq, text in [(2, "Old answer"), (3, "PUCK_REPLY:old\nLate answer")]:
        row = event(seq, "message.assistant", "old-" + str(seq), text=text)
        row["payload"]["session_id"] = "puck-chat"; t.feed(row)
    assert t.result is None
    reply = event(4, "message.assistant", "new", text="PUCK_REPLY:fresh\n{\"text\":\"Ready\"}")
    reply["payload"]["session_id"] = "puck-chat"; t.feed(reply)
    assert t.result == '{"text":"Ready"}'


def test_persistent_chat_accepts_explicit_ack_parent_without_marker():
    t = ReplyTracker("puck-chat", reply_marker="PUCK_REPLY:fresh\n")
    t.acknowledge({"message_id": "u"})
    t.feed(event(1, "message.assistant", "new", "u", "Ready"))
    assert t.result == "Ready"


def test_main_chat_marker_correlates_without_session_id():
    t=ReplyTracker(None,reply_marker="PUCK_REPLY:new\n")
    t.acknowledge({"message_id":"current"})
    t.feed(event(1,"message.assistant","old",text="PUCK_REPLY:old\nWrong"))
    assert t.result is None
    t.feed(event(2,"message.assistant","new",text="PUCK_REPLY:new\nReady"))
    assert t.result == "Ready"


def test_main_chat_streamed_marker_split_across_tokens_and_crlf():
    t=ReplyTracker(None,reply_marker="PUCK_REPLY:new\n")
    t.acknowledge({"message_id":"current"})
    t.feed(event(1,"delta.message_start","a"))
    t.feed(event(2,"delta.text_append","a",text="PUCK_"))
    t.feed(event(3,"delta.text_append","a",text="REPLY:new\r\nReady"))
    t.feed(event(4,"delta.message_done","a"))
    assert t.result == "Ready"


def test_progress_waits_for_correlated_reply_and_cannot_break_delivery():
    import json
    async def check():
        phases=[]
        s=Subscription(asyncio.get_running_loop(),"puck",reply_marker="PUCK_REPLY:new\n",progress=phases.append)
        s.acknowledge({"message_id":"current"})
        def frame(row):
            return SimpleNamespace(kind="body_chunk",value=SimpleNamespace(data=json.dumps(row).encode()+b"\n",end_body=False))
        wrong=event(1,"message.assistant","old","other",text="Old private reply")
        wrong["payload"]["session_id"]="puck";s.on_frame(frame(wrong))
        assert phases==[] and not s.done.done()
        right=event(2,"delta.text_append","new",text="PUCK_REPLY:new\nReady")
        right["payload"]["session_id"]="puck";s.on_frame(frame(right))
        assert phases==["replying"] and not s.done.done()
        done=event(3,"delta.message_done","new");done["payload"]["session_id"]="puck"
        s.on_frame(frame(done));assert await s.done=="Ready"
        def broken(_):raise RuntimeError("UI gone")
        other=Subscription(asyncio.get_running_loop(),progress=broken);other.acknowledge({"message_id":"current"})
        other.on_frame(frame(event(1,"message.assistant","answer","current",text="Still delivered")))
        assert await other.done=="Still delivered"
    asyncio.run(check())

@pytest.mark.parametrize("kind", ["valid", "bad_base64", "jpeg", "truncated", "large", "with_photo"])
def test_video_attachment_validates_before_transport(monkeypatch, kind):
    import base64, concurrent.futures, json
    from puck_muse import Runtime
    captured = []
    class Callbacks:
        def onState(self, *args): pass
    class Session:
        registered_at = 1
        async def turn(self, text, chat, marker, attachments, progress=None):
            captured.extend(attachments)
            return "fixture reply"
    def submit(coro, loop):
        future = concurrent.futures.Future()
        future.set_result(asyncio.run(coro))
        return future
    monkeypatch.setattr(asyncio, "run_coroutine_threadsafe", submit)
    runtime = Runtime(Callbacks(), "02:00:00:ab:cd:ef")
    runtime.loop = object(); runtime.session = Session()
    payload = (24).to_bytes(4, "big") + b"ftypisom" + bytes(20)
    if kind == "jpeg": payload = b"\xff\xd8" + bytes(30)
    if kind == "truncated": payload = payload[:10]
    if kind == "large": payload += bytes(8_000_000)
    encoded = "!!" if kind == "bad_base64" else base64.b64encode(payload).decode()
    photo = base64.b64encode(b"\xff\xd8fixture").decode() if kind == "with_photo" else ""
    result = json.loads(runtime.send("fixture", "Inspect video", "same-chat", "", photo, "", encoded))
    assert result["ok"] == (kind == "valid")
    if kind == "valid":
        assert captured == [{"type": "file", "mime_type": "video/mp4", "filename": "muse-launcher-video.mp4", "data_base64": encoded}]
    else: assert not captured
