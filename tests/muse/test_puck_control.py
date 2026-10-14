import asyncio
import json
import struct
from types import SimpleNamespace

import pytest

from puck_control import ControlDecoder, MAX_CONTROL_BYTES, normalize_invoke
from puck_muse import AndroidDescription, AndroidSession, COMMANDS, Runtime
from musegadget.link_client import Outcome, encode_message
from musegadget.noise import ApplicationResponse, BodyChunk, ServiceFrame
from reference.test_link_client import Pipe, FakeVm


@pytest.mark.parametrize("wire", ["prefixed", "json", "ndjson"])
def test_split_frames_unicode_and_multiple_messages(wire):
    values = [{"method": "link.invoke", "id": "u", "text": "שלום 🌙"}, {"id": 2}]
    encoded = [json.dumps(x, ensure_ascii=False).encode() for x in values]
    data = b"".join(encode_message(x) for x in values) if wire == "prefixed" else b"\n".join(encoded) + b"\n"
    decoder = ControlDecoder()
    messages = []
    for byte in data:
        messages += decoder.feed(bytes([byte]))
    messages += decoder.feed(b"", ended=True)
    assert messages == values


def test_length_prefix_whose_low_byte_looks_like_json():
    raw = b'{"padding":"' + b'x' * 109 + b'"}'
    assert len(raw) == 123  # '{' as the first byte of a valid u32 prefix
    decoder = ControlDecoder()
    assert decoder.feed(struct.pack('<I', len(raw)) + raw) == [json.loads(raw)]
    assert decoder.formats == {"prefixed": 1, "json": 0}


@pytest.mark.parametrize("data", [b'{"id":', b'[]', b'bad!', struct.pack('<I', MAX_CONTROL_BYTES), b'{}\nnot-json'])
def test_invalid_or_incomplete_control_fails_closed(data):
    with pytest.raises(ValueError):
        ControlDecoder().feed(data, ended=True)


def test_buffer_and_batch_limits():
    with pytest.raises(ValueError): ControlDecoder().feed(b'x' * (MAX_CONTROL_BYTES + 1))
    with pytest.raises(ValueError): ControlDecoder().feed(b'{}\n' * 65)


@pytest.mark.parametrize("bad", [True, [], {}, "", "x" * 129, 2**60, 1.5, None])
def test_invalid_ids_are_not_dispatchable(bad):
    with pytest.raises(ValueError): normalize_invoke({"method": "link.invoke", "id": bad, "command": "puck.update"}, COMMANDS)


def test_direct_methods_are_allowlisted_and_invalid_params_rejected():
    assert normalize_invoke({"method": "system.run", "id": 1}, COMMANDS) is None
    for params in ([], None, "json"):
        with pytest.raises(ValueError): normalize_invoke({"method": "puck.update", "id": 1, "params": params}, COMMANDS)
    for timeout in (True, -1, "30", 600001):
        with pytest.raises(ValueError): normalize_invoke({"method": "puck.update", "id": 1, "timeout_ms": timeout}, COMMANDS)


async def paired(callbacks, command):
    to_device, to_vm = asyncio.Queue(), asyncio.Queue()
    device_ws, vm_ws = Pipe(to_device, to_vm), Pipe(to_vm, to_device)
    session = AndroidSession(callbacks=callbacks, noise_host="hatch.metaaivm.com", vm_id="fixture",
        vm_auth_token="fixture-only", device=AndroidDescription(node_id="homelink-abcdef", display_name="Puck", version="test", commands=COMMANDS), run_command=command)
    async def connect(): return device_ws
    session._open = connect
    vm, stop = FakeVm(vm_ws), asyncio.Event()
    running = asyncio.create_task(session.run(stop))
    await vm.handshake()
    # ESP32 accepts body chunks even after a finished opening response.
    opening = await vm.next_frame()
    vm.stream_id = opening.stream_id
    await vm.send_frame(ServiceFrame.response(vm.stream_id, ApplicationResponse(status=200, end_body=True)))
    register = await vm.next_message()
    await vm.send_message({"type": "res", "id": register["id"], "result": {"status": "registered"}})
    assert await asyncio.wait_for(vm.next_message(), 2) == {"method": "link.heartbeat"}
    assert session.registered_at is not None
    return session, vm, stop, running


@pytest.mark.parametrize("wire", ["prefixed", "json", "ndjson"])
@pytest.mark.parametrize("shape", ["link", "device", "direct"])
@pytest.mark.parametrize("stream", ["control", "unclaimed", "unclaimed-response"])
def test_encrypted_compatibility_keeps_update_and_action_review_contract(wire, shape, stream):
    async def scenario():
        received = []
        def record(kind, payload): received.append((kind, json.loads(payload))); return True
        callbacks = SimpleNamespace(onState=lambda *_: None, onUpdate=lambda p: record("update", p), onCommand=lambda p: record("command", p))
        runtime = Runtime(callbacks, "02:00:00:ab:cd:ef")
        session, vm, stop, running = await paired(callbacks, runtime.command)
        runtime.session = session
        try:
            for index, command in enumerate(("puck.update", "puck.command")):
                params = {"id": "fixture", "text": "hello"} if index == 0 else {"id": "fixture", "action": "camera"}
                message = {"type": "req", "id": index, "method": "link.invoke", "command": command, "params": params}
                if shape == "device": message.update(method="device.invoke", command=None, params={"name": command, "arguments": params})
                if shape == "direct": message = {"type": "req", "id": index, "method": command, "params": params}
                data = encode_message(message) if wire == "prefixed" else json.dumps(message).encode() + (b'\n' if wire == "ndjson" else b'')
                stream_id = vm.stream_id if stream == "control" else 999
                if stream == "unclaimed-response":
                    await vm.send_frame(ServiceFrame.response(stream_id, ApplicationResponse(status=200, body=data, end_body=True)))
                else:
                    await vm.send_frame(ServiceFrame.body_chunk(stream_id, BodyChunk(data=data[:3])))
                    await vm.send_frame(ServiceFrame.body_chunk(stream_id, BodyChunk(data=data[3:])))
                reply = await asyncio.wait_for(vm.next_message(), 2)
                assert reply["id"] == index and reply["type"] == "res" and reply["ok"]
                assert not reply["payload"]["executed"]
                assert reply["payload"]["approval_required"] == (index == 1)
            assert [kind for kind, _ in received] == ["update", "command"]
            assert session.control_stats["invokes"] == session.control_stats["results_sent"] == 2
            assert "hello" not in json.dumps(session.control_stats)
        finally:
            stop.set()
            assert await asyncio.wait_for(running, 2) == Outcome.STOPPED
    asyncio.run(scenario())


def test_known_http_response_is_not_a_command_and_side_events_cannot_unpair():
    async def scenario():
        calls = []
        callbacks = SimpleNamespace(onState=lambda *_: None)
        session, vm, stop, running = await paired(callbacks, lambda *args: calls.append(args))
        try:
            seen = []
            session._requests[888] = SimpleNamespace(on_frame=lambda f: seen.append(f), done=asyncio.get_running_loop().create_future())
            payload = {"method": "puck.command", "id": "wrong-origin", "params": {"action": "camera"}}
            await vm.send_frame(ServiceFrame.body_chunk(888, BodyChunk(data=json.dumps(payload).encode())))
            await vm.send_frame(ServiceFrame.body_chunk(999, BodyChunk(data=b'{"event":"link.unpaired"}')))
            await vm.send_message({"method": "device.invoke", "id": "reject", "params": {"command": "system.run", "params": {"command": "private-fixture"}}})
            reply = await asyncio.wait_for(vm.next_message(), 2)
            assert reply["ok"] is False and not calls and len(seen) == 1
            assert session.control_stats["request_frames"] == 1
            assert session.control_stats["request_invoke_candidates"] == 1
            assert session.control_stats["request_scan_errors"] == 0
            assert not running.done()
            assert "private-fixture" not in json.dumps(session.control_stats)
            session._requests.pop(888)
        finally:
            stop.set()
            await asyncio.wait_for(running, 2)
    asyncio.run(scenario())


def test_unclaimed_streams_are_bounded_and_invalid_invoke_does_not_block_next():
    async def scenario():
        calls = []
        def execute(*args): calls.append(args); return {"ok": True, "payload": {"executed": False}}
        session, vm, stop, running = await paired(SimpleNamespace(onState=lambda *_: None), execute)
        try:
            for stream in range(100, 109):
                await vm.send_frame(ServiceFrame.body_chunk(stream, BodyChunk(data=b'{"m')))
            await vm.send_message({"method": "device.invoke", "id": "malformed", "command": "puck.update", "params": []})
            bad = await asyncio.wait_for(vm.next_message(), 2)
            assert bad["id"] == "malformed" and bad["ok"] is False and not calls
            assert session.control_stats["side_stream_drops"] == 1
            assert session.control_stats["invalid_invokes"] == 1
            await vm.send_message({"method": "puck.update", "id": "next", "params": {}})
            assert (await asyncio.wait_for(vm.next_message(), 2))["ok"]
            assert len(calls) == 1
        finally:
            stop.set()
            await asyncio.wait_for(running, 2)
    asyncio.run(scenario())


@pytest.mark.parametrize("failure", ["raise", "invalid-result", "encoding"])
def test_callback_failures_return_correlated_error_and_next_invoke_works(failure):
    async def scenario():
        calls = []
        def execute(*args):
            calls.append(args)
            if len(calls) == 1:
                if failure == "raise": raise RuntimeError("private-fixture")
                if failure == "encoding": return {"ok": True, "payload": object()}
                return None
            return {"ok": True, "payload": {"executed": False}}
        session, vm, stop, running = await paired(SimpleNamespace(onState=lambda *_: None), execute)
        try:
            for index in (1, 2):
                await vm.send_message({"method": "link.invoke", "id": index, "command": "puck.update", "params": {}})
                reply = await asyncio.wait_for(vm.next_message(), 2)
                assert reply["id"] == index and reply["ok"] == (index == 2)
                assert "private-fixture" not in json.dumps(reply)
            assert session.control_stats["callback_errors"] == 1
        finally:
            stop.set()
            await asyncio.wait_for(running, 2)
    asyncio.run(scenario())
