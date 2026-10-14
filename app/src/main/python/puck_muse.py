"""Muse Launcher's restricted Android integration of Meta's Apache-2.0 protocol SDK.

No shell executor, filesystem tool, local socket, or content/credential logs.
Android owns the encrypted credential vault and the Bluetooth transport.
"""
import asyncio
import base64
import dataclasses
import json
import logging
import queue
import re
import threading
import time
import uuid
from urllib.parse import urlparse

logging.disable(logging.CRITICAL)
from musegadget.identity import Identity
from musegadget.pairing import PairingSession
from musegadget.ble_setup import SetupController, ProvisionFailed
from musegadget.link_client import LinkSession, DeviceDescription, Outcome, _UpgradeRejected
from musegadget.muse_api import fetch_vms_with_status, refresh_device_token
from musegadget.noise import Header
from puck_control import (ControlDecoder, normalize_invoke, valid_id,
                          MAX_CONTROL_BYTES, MAX_SIDE_STREAMS, SIDE_STREAM_TTL)

SDK_RE = re.compile(r"mgst_[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]")
MAX_TEXT = 4000
COMMANDS = {
    "puck.request": {
        "description": "Propose a version-1 Muse Launcher workspace request. Confirmation actions always require local user approval. This never executes an action directly. Request JSON uses Kotlin serialization type names dev.puck.core.WorkspaceRequest.ContentCard, Confirmation, Question, TaskStatus, with id and version=1. Confirmation.action uses dev.puck.core.DeviceAction.Camera, Dial(number optional), Message(body,number optional), Navigate(destination), Calendar(title), OpenApp(packageName,activityName). Prefer content cards when the installed app component is unknown.",
        "required": {"request": {"type": "string", "description": "JSON-encoded Muse Launcher WorkspaceRequest; max 16000 characters"}},
        "optional": {},
        "timeout_ms": 10000,
    }
}
COMMANDS["puck.update"] = {
    "description": "Push a glanceable update to Muse Launcher's home screen, even when the user has not just asked. Information only: never executes anything. Use a fresh unique id for each update. Report your own work here without claiming Muse Launcher completed external actions.",
    "required": {"id": {"type": "string", "description": "Unique request id, 1-128 letters/digits/dot/colon/dash"}, "title": {"type": "string", "description": "Short title, 1-120 characters"}, "text": {"type": "string", "description": "Update text, 1-4000 characters"}},
    "optional": {"mood": {"type": "string", "description": "Presentation only: idle, thoughtful, cheerful or concerned"}}, "timeout_ms": 10000,
}
COMMANDS["puck.command"] = {
    "description": "Propose a Muse Launcher device action for explicit local review. Never executed by this tool. Actions: camera, dial, message, navigate, calendar, open_app. Dial/message without number use Muse Launcher's contact picker. User places calls, sends messages, saves calendar changes. open_app requires a unique installed app label; otherwise ask the user to choose in Apps.",
    "required": {"id": {"type": "string", "description": "Fresh unique request id"}, "action": {"type": "string", "description": "camera, dial, message, navigate, calendar, open_app"}},
    "optional": {k: {"type": "string", "description": v} for k, v in {"number": "Telephone number for dial/message only; never USSD", "body": "Message body, required for message", "destination": "Destination, required for navigate", "title": "Event title, required for calendar", "appLabel": "Unique installed app label, required for open_app"}.items()}, "timeout_ms": 10000,
}



def api_root(value):
    value = value or "https://api.muse.ai"
    u = urlparse(value)
    if u.scheme != "https" or u.hostname != "api.muse.ai" or u.username or u.password or u.port not in (None, 443) or u.query or u.fragment:
        raise ValueError("unsupported API origin")
    return value.rstrip("/")


def noise_host(value):
    value = value or "hatch.metaaivm.com"
    if not re.fullmatch(r"[a-zA-Z0-9.-]+", value) or not (value == "hatch.metaaivm.com" or value.endswith(".metaaivm.com") or value.endswith(".muse.ai")):
        raise ValueError("unsupported Noise host")
    return value


class ReplyTracker:
    """Strictly associate subscription events with one acknowledged user turn."""
    def __init__(self, chat_id=None, isolated=False, reply_marker=None):
        self.ids = None
        self.early = []
        self.correlated = set()
        self.texts = {}
        self.result = None
        self.seq = -1
        self.chat_id = chat_id
        self.isolated = isolated
        self.current_user = False
        self.reply_marker = reply_marker
        self.stats = dict(events=0, same_chat=0, matching_parent=0, marker=0, current_user=0)

    def acknowledge(self, ack):
        if not isinstance(ack, dict):
            raise ValueError("missing chat acknowledgment")
        p = ack.get("result", ack.get("payload", ack))
        message = p.get("message_id") if isinstance(p, dict) else None
        if not isinstance(message, str) or not message or len(message) > 128:
            raise ValueError("uncorrelated chat acknowledgment")
        self.ids = {message}
        parent = p.get("reply_to_message_id")
        if isinstance(parent, str) and parent:
            self.ids.add(parent)
        early, self.early = self.early, []
        for row in early:
            self._accept(row)

    def feed(self, row):
        if not isinstance(row, dict) or row.get("type") != "event" or self.result is not None:
            return
        seq = row.get("seq")
        if isinstance(seq, int):
            if seq <= self.seq:
                return
            self.seq = seq
        if self.ids is None:
            if len(self.early) >= 64:
                raise ValueError("subscription pre-ack overflow")
            self.early.append(row)
        else:
            self._accept(row)

    def _accept(self, row):
        # The reference protocol accepts fields at the event root as well as
        # in payload. Payload fields take precedence in the nested form.
        p = {**row, **(row.get("payload") if isinstance(row.get("payload"), dict) else {})}
        event = row.get("event_name", row.get("event"))
        mid = p.get("message_id", p.get("id"))
        same_chat = self.chat_id is None or p.get("session_id") == self.chat_id
        self.stats["events"] += 1
        self.stats["same_chat"] += int(same_chat)
        if event == "message.user":
            if same_chat:
                self.current_user = mid in self.ids
                self.stats["current_user"] += int(self.current_user)
            return
        if event not in ("delta.message_start", "delta.text_append", "delta.message_done", "message.assistant"):
            return
        if not isinstance(mid, str) or not mid or len(mid) > 128:
            return
        text = next((p[k] for k in ("display_text", "content", "text") if isinstance(p.get(k), str)), "")
        self.stats["text_events"] = self.stats.get("text_events",0) + int(bool(text))
        self.stats["recognized_assistant"] = self.stats.get("recognized_assistant",0) + 1
        parent = p.get("reply_to_message_id", p.get("parent_message_id"))
        prefilled = False
        if parent:
            if parent not in self.ids:
                return
            if len(self.correlated) >= 16 and mid not in self.correlated:
                raise ValueError("too many assistant messages")
            self.stats["matching_parent"] += 1
            self.correlated.add(mid)
        elif mid not in self.correlated:
            if self.reply_marker:
                if not same_chat:
                    return
                # Prefixes may arrive one token at a time. Buffer only a matching
                # prefix, bounded to 16 messages; unrelated history is discarded.
                token = self.reply_marker.rstrip()
                current = self.texts.get(mid, "")
                candidate = (current + text)[:MAX_TEXT] if event == "delta.text_append" else (text or current)
                marked = candidate.startswith(token) and (len(candidate)==len(token) or candidate[len(token)].isspace())
                if not marked:
                    if token.startswith(candidate) and len(self.texts)<16:
                        self.texts[mid] = candidate
                    else:
                        self.texts.pop(mid,None)
                    return
                self.stats["marker"] += 1
                self.texts[mid] = candidate
                prefilled = True
            elif not (same_chat and (self.current_user or self.isolated)):
                return
            if len(self.correlated) >= 16:
                raise ValueError("too many assistant messages")
            self.correlated.add(mid)
        current = self.texts.get(mid, "")
        if not prefilled:
            if event == "delta.text_append":
                current = (current + text)[:MAX_TEXT]
            elif text:
                current = text[:MAX_TEXT]
        self.texts[mid] = current
        if event in ("delta.message_done", "message.assistant") and p.get("display_text_ready", True) and current.strip():
            result = current.removeprefix(self.reply_marker.rstrip()).lstrip() if self.reply_marker else current
            if result.strip():
                self.result = result


class Subscription:
    def __init__(self, loop, chat_id=None, isolated=False, reply_marker=None, progress=None):
        self.progress=progress
        self.reported_reply=False
        self.done = loop.create_future()
        self.ready = loop.create_future()
        self.tracker = ReplyTracker(chat_id, isolated, reply_marker)
        self.buf = bytearray()

    def acknowledge(self, ack):
        self.tracker.acknowledge(ack)
        self.finish_if_ready()

    def finish_if_ready(self):
        if self.tracker.result is not None and not self.done.done():
            self.done.set_result(self.tracker.result)

    def on_frame(self, frame):
        if self.done.done():
            return
        if frame.kind == "reset":
            self.done.set_exception(ConnectionError("subscription reset"))
            return
        if frame.kind == "response":
            if frame.value.status >= 400:
                error = ConnectionError("subscription refused")
                if not self.ready.done():
                    self.ready.set_exception(error)
                self.done.set_exception(error)
                return
            if not self.ready.done():
                self.ready.set_result(True)
            data, ended = frame.value.body, frame.value.end_body
        else:
            data, ended = frame.value.data, frame.value.end_body
        # A transport frame can contain many history rows. Bound each NDJSON
        # row and the incomplete tail, not the total size of a valid batch.
        for part in data.splitlines(keepends=True):
            self.buf += part
            if len(self.buf) > 262144:
                self.done.set_exception(ValueError("subscription row exceeded"))
                return
            if not self.buf.endswith(b"\n"):
                continue
            line = bytes(self.buf)
            self.buf.clear()
            if line.strip():
                try:
                    row = json.loads(line)
                    self.tracker.feed(row)
                    if not self.reported_reply and self.tracker.correlated and any(self.tracker.texts.get(mid,"") for mid in self.tracker.correlated):
                        self.reported_reply=True
                        if self.progress:
                            try:self.progress("replying")
                            except Exception:pass
                    self.finish_if_ready()
                except (ValueError, TypeError):
                    self.done.set_exception(ValueError("invalid subscription event"))
                    return
        if ended and not self.done.done():
            self.done.set_exception(ConnectionError("subscription ended before reply"))


class AndroidDescription(DeviceDescription):
    def register_params(self):
        # Muse SDK requires the linux/homehub protocol profile. Android is the
        # host, not a replacement server-routing profile; never use family link.
        return {**super().register_params(), "display_name": "Muse Launcher Jelly Star"}


class AndroidSession(LinkSession):
    def __init__(self, callbacks, **kwargs):
        super().__init__(**kwargs)
        self.callbacks = callbacks
        self.turn_lock = asyncio.Lock()
        self.connected_reported = False
        self.control_stats = {key: 0 for key in (
            "messages", "invokes", "accepted", "noise_frames", "control_frames",
            "request_frames", "unknown_frames", "unassembled_frames", "text_frames",
            "unknown_responses", "unknown_body_chunks", "unknown_resets", "unknown_bytes",
            "request_invoke_candidates", "request_scan_errors",
            "decode_errors", "side_stream_drops", "prefixed_messages", "json_messages",
            "link_invokes", "device_invokes", "direct_invokes", "other_methods",
            "invalid_invokes", "results_sent", "result_send_errors", "callback_errors")}
        self._pending_invokes = 0
        self.reply_stats = {}

    async def _read_loop(self):
        decoder = ControlDecoder()
        side = {}
        request_scans = {}
        while True:
            try:
                raw = await self._ws.recv()
            except Exception:
                return Outcome.CLOSED
            if isinstance(raw, str):
                self.control_stats["text_frames"] += 1
                continue
            self.control_stats["noise_frames"] += 1
            frame = self._transport.decrypt_frame(bytes(raw))
            if frame is None:
                self.control_stats["unassembled_frames"] += 1
                continue
            control = frame.stream_id == self._stream_id
            category = "control_frames" if control else "request_frames" if frame.stream_id in self._requests else "unknown_frames"
            self.control_stats[category] += 1
            if category == "unknown_frames":
                if frame.kind == "response":
                    self.control_stats["unknown_responses"] += 1
                    self.control_stats["unknown_bytes"] += len(frame.value.body)
                elif frame.kind == "body_chunk":
                    self.control_stats["unknown_body_chunks"] += 1
                    self.control_stats["unknown_bytes"] += len(frame.value.data)
                elif frame.kind == "reset":
                    self.control_stats["unknown_resets"] += 1
            if not control and frame.stream_id in self._requests:
                # Known HTTP responses (including shared/side chat text) can
                # never grant permission to execute a control command.
                self._requests[frame.stream_id].on_frame(frame)
                # Passively count top-level invoke envelopes on those streams
                # to expose unexpected routing without acting on chat content.
                for stream in list(request_scans):
                    if stream not in self._requests:
                        request_scans.pop(stream)
                if frame.kind in ("response", "body_chunk"):
                    data = frame.value.body if frame.kind == "response" else frame.value.data
                    if frame.stream_id in request_scans or len(request_scans) < MAX_SIDE_STREAMS:
                        scan = request_scans.setdefault(frame.stream_id, ControlDecoder())
                        try:
                            for message in scan.feed(data, ended=frame.value.end_body):
                                try:
                                    candidate = normalize_invoke(message, COMMANDS) is not None
                                except ValueError:
                                    candidate = True
                                self.control_stats["request_invoke_candidates"] += int(candidate)
                        except (ValueError, TypeError):
                            self.control_stats["request_scan_errors"] += 1
                            request_scans.pop(frame.stream_id, None)
                        if frame.value.end_body:
                            request_scans.pop(frame.stream_id, None)
                elif frame.kind == "reset":
                    request_scans.pop(frame.stream_id, None)
                continue
            if frame.kind == "reset":
                side.pop(frame.stream_id, None)
                if control:
                    return Outcome.CLOSED
                continue
            if frame.kind == "response":
                if frame.value.status >= 400:
                    if control:
                        return Outcome.FORBIDDEN if frame.value.status == 403 else Outcome.CLOSED
                    continue
                data, ended = frame.value.body, frame.value.end_body
            elif frame.kind == "body_chunk":
                data, ended = frame.value.data, frame.value.end_body
            else:
                continue
            selected = decoder
            if not control:
                now = time.monotonic()
                for stream, (_, touched) in list(side.items()):
                    if now - touched > SIDE_STREAM_TTL:
                        side.pop(stream)
                        self.control_stats["side_stream_drops"] += 1
                if frame.stream_id not in side and len(side) >= MAX_SIDE_STREAMS:
                    self.control_stats["side_stream_drops"] += 1
                    continue
                selected = side.get(frame.stream_id, (ControlDecoder(), now))[0]
                side[frame.stream_id] = (selected, now)
            before = dict(selected.formats)
            try:
                messages = selected.feed(data, ended=ended)
            except (ValueError, TypeError):
                self.control_stats["decode_errors"] += 1
                if control:
                    return Outcome.CLOSED
                side.pop(frame.stream_id, None)
                continue
            for fmt in ("prefixed", "json"):
                self.control_stats[fmt + "_messages"] += selected.formats[fmt] - before[fmt]
            for message in messages:
                if control:
                    outcome = self._handle(message)
                else:
                    # Unclaimed streams accept invocation envelopes only.
                    # A stray register/unpair object cannot mutate session state.
                    outcome = self._dispatch_compatible(message)
                if outcome is not None:
                    return outcome
            if ended and not control:
                side.pop(frame.stream_id, None)
            # A completed opening HTTP response is not proof that the long-
            # lived control channel has ended; the ESP32 keeps receiving it.

    def _dispatch_compatible(self, message):
        method = message.get("method")
        category = "link_invokes" if method == "link.invoke" else "device_invokes" if method in ("device.invoke", "invoke") else "direct_invokes" if isinstance(method, str) and method in COMMANDS else "other_methods"
        self.control_stats[category] += 1
        try:
            invoke = normalize_invoke(message, COMMANDS)
        except ValueError:
            self.control_stats["invalid_invokes"] += 1
            if valid_id(message.get("id")):
                invoke = {"id": message["id"], "invalid": True,
                          "type": message.get("type")}
            else:
                return None
        if invoke is None:
            return None
        self.control_stats["invokes"] += 1
        if self._pending_invokes >= 16:
            self.control_stats["invalid_invokes"] += 1
            return None
        self._pending_invokes += 1
        task = asyncio.ensure_future(self._invoke(invoke))
        self._tasks.add(task)
        def finished(done):
            self._tasks.discard(done)
            self._pending_invokes -= 1
            if not done.cancelled():
                done.exception()  # never log exception text or payloads
        task.add_done_callback(finished)
        return None

    async def _invoke(self, message):
        async with self._invokes:
            if message.get("invalid"):
                result = {"ok": False, "error": "Invalid Muse Launcher invocation"}
            elif message["command"] not in COMMANDS:
                result = {"ok": False, "error": "Unsupported Muse Launcher command"}
            else:
                try:
                    result = await asyncio.get_running_loop().run_in_executor(
                        None, self._run_command, message["command"], message["params"], message["timeout_ms"])
                    if not isinstance(result, dict) or type(result.get("ok")) is not bool:
                        raise ValueError("invalid result")
                    if len(json.dumps(result).encode()) > MAX_CONTROL_BYTES:
                        raise ValueError("result exceeded")
                except Exception:
                    self.control_stats["callback_errors"] += 1
                    result = {"ok": False, "error": "Muse Launcher command failed"}
            reply = {**result, "method": "link.result", "id": message["id"]}
            if message.get("type") == "req":
                reply["type"] = "res"
            try:
                await self.send(reply)
                self.control_stats["results_sent"] += 1
            except Exception:
                self.control_stats["result_send_errors"] += 1
                raise

    async def _open(self):
        from websockets.asyncio.client import connect
        from websockets.exceptions import InvalidStatus
        try:
            return await connect(self._url, additional_headers={"Authorization": f"Bearer {self._token}"},
                                 open_timeout=20, ping_interval=20, ping_timeout=20,
                                 max_size=4 * 1024 * 1024)
        except InvalidStatus as exc:
            if exc.response.status_code in (401, 403):
                raise _UpgradeRejected(exc.response.status_code) from None
            raise

    def _handle(self, message):
        self.control_stats["messages"] += 1
        if message.get("id") == self._register_id:
            self.control_stats["registration_ok"] = message.get("ok") is True
            self.control_stats["registration_error"] = bool(message.get("error"))
            result = message.get("result")
            self.control_stats["registration_status"] = "registered" if isinstance(result, dict) and result.get("status") == "registered" else "other"
            self.control_stats["registration_type"] = message.get("type") if message.get("type") in ("ack", "res", "response") else "other"
        # Let the SDK handle registration/unpair events, but normalize invokes
        # here so its executor never receives an unvalidated envelope.
        outcome = None if message.get("method") == "link.invoke" else super()._handle(message)
        if outcome is None:
            self._dispatch_compatible(message)
        if message.get("id") == self._register_id and message.get("error"):
            self.callbacks.onState("blocked", "Muse rejected Muse Launcher registration. Check the device in Muse Settings.")
        if self.registered_at is not None and not self.connected_reported:
            self.connected_reported = True
            self.callbacks.onState("connected", "Muse connected")
            # The official gadget firmware announces presence once after a
            # registered response (then every 24h); websocket ping is separate.
            if isinstance(message.get("result"), dict) and message["result"].get("status") == "registered":
                async def announce():
                    try:
                        await self.send({"method": "link.heartbeat"})
                    except Exception:
                        self.callbacks.onRequestFailed("Muse presence announcement failed")
                task = asyncio.ensure_future(announce())
                self._tasks.add(task)
                task.add_done_callback(self._tasks.discard)
        return outcome

    async def turn(self, text, chat_id, reply_marker=None, attachments=None, progress=None):
        async with self.turn_lock:
            started_at = time.monotonic()
            timing = {}
            if self.registered_at is None:
                raise ConnectionError("Muse is not registered")
            sub = Subscription(asyncio.get_running_loop(), chat_id, reply_marker=reply_marker,progress=progress)
            if progress:progress("uploading")
            # A newly-created side chat cannot be subscribed until the chat
            # request is acknowledged. The default subscription is main-chat only.
            ack = await self.send_chat(text, chat_id, attachments=attachments)
            timing["upload_ack_ms"] = round((time.monotonic() - started_at) * 1000)
            if ack.get("ok") and progress:progress("thinking")
            response = ack.get("response")
            self.reply_stats = {"chat_status": ack.get("status",0), "ack_object": isinstance(response,dict),
                "ack_has_message": isinstance(response,dict) and isinstance(response.get("result",response.get("payload",response)),dict) and "message_id" in response.get("result",response.get("payload",response))}
            if not ack.get("ok"):
                self.callbacks.onRequestFailed("Muse chat request refused (HTTP %d)" % ack.get("status", 0))
                raise ConnectionError("chat request refused")
            sub.acknowledge(ack.get("response"))
            encrypted = self._transport.encrypt_http_request("POST", "/chat/subscribe", json.dumps({"session_id": chat_id} if chat_id else {}).encode(), headers=[Header("Content-Type", "application/json")])
            self._requests[encrypted.stream_id] = sub
            try:
                await self._send_frames(encrypted.frames)
                await asyncio.wait_for(asyncio.shield(sub.ready), 15)
                timing["subscription_ready_ms"] = round((time.monotonic() - started_at) * 1000)
                try:
                    return await asyncio.wait_for(asyncio.shield(sub.done), 90)
                except asyncio.TimeoutError:
                    self.callbacks.onRequestFailed("Muse reply timed out. No matching reply was received.")
                    raise
            finally:
                self.reply_stats = {**sub.tracker.stats, "completed": sub.tracker.result is not None,
                    **timing, "total_ms": round((time.monotonic() - started_at) * 1000)}
                self._requests.pop(encrypted.stream_id, None)
                for future in (sub.ready, sub.done):
                    if not future.done():
                        future.cancel()
                    elif not future.cancelled():
                        future.exception()  # consume errors; never log contents
                # Close this request stream so cancelled/finished turns don't leak subscriptions.
                try:
                    await self._send_frames(self._transport.encrypt_body_chunk(encrypted.stream_id, b"", end_body=True))
                except Exception:
                    pass


class Runtime:
    def __init__(self, callbacks, mac):
        self.callbacks = callbacks
        self.identity = Identity(mac)
        self.controller = None
        self.loop = None
        self.session = None
        self.last_control_stats = {}
        self.stop_event = None
        self.thread = None
        self.pending = {}
        self.closed = False
        self.notifications = set()

    def link_stats(self):
        """Counts only; never includes identifiers, content or credentials."""
        return json.dumps({**(self.session.control_stats if self.session else self.last_control_stats),
                           "session_active": self.session is not None,
                           "reply": self.session.reply_stats if self.session else {}})

    def validate_sdk(self, value):
        return SDK_RE.fullmatch(value.strip()) is not None

    def start_pairing(self, sdk):
        if not self.validate_sdk(sdk):
            raise ValueError("invalid SDK token")
        self.stop_pairing()
        pairing = PairingSession(node_id=self.identity.node_id, device_id=self.identity.device_id, mac=self.identity.mac, firmware_version="puck-0.3", sdk_token=sdk)
        runtime = self
        class Transport:
            def send_packets(self, packets):
                for packet in packets:
                    if not runtime.callbacks.sendPacket(base64.b64encode(packet).decode()):
                        raise ConnectionError("Bluetooth notification failed")
            def mtu(self):
                return int(runtime.callbacks.getMtu())
            def disconnect(self, delay):
                runtime.callbacks.disconnectPeer()
        class Network:
            def is_online(self):
                return bool(runtime.callbacks.isOnline())
            def current_connection_entry(self):
                return {"ssid": "Current phone connection", "rssi": -40, "secure": False}
        def provision(credentials, commit):
            try:
                root = api_root(credentials.api_url_v2)
                noise_host(credentials.noise_host)
            except (ValueError, TypeError):
                raise ProvisionFailed("auth_failed") from None
            vms, status = fetch_vms_with_status(credentials.access_token, root)
            if status != 200 or not vms:
                raise ProvisionFailed("auth_failed")
            saved = {**dataclasses.asdict(credentials), "sdk_token": sdk, "saved_at": time.time()}
            if not commit(lambda: bool(runtime.callbacks.saveCredentials(json.dumps(saved)))):
                raise ProvisionFailed("auth_failed")
        self.controller = SetupController(pairing=pairing, identity=self.identity, version="puck-0.3", transport=Transport(), network=Network(), provision=provision, on_complete=lambda: runtime.callbacks.onPaired())
        self.controller._inbox = queue.Queue(maxsize=128)
        self.controller.start()

    def packet(self, encoded):
        raw = base64.b64decode(encoded, validate=True)
        if len(raw) > 512:
            raise ValueError("oversize Bluetooth write")
        if self.controller:
            self.controller.on_write(raw)

    def peer_disconnected(self):
        if self.controller:
            self.controller.on_disconnect()

    def stop_pairing(self):
        if self.controller:
            self.controller.on_disconnect()
            self.controller.stop()
            self.controller = None

    def connect(self, credentials_json):
        if self.thread and self.thread.is_alive():
            if not self.closed:
                return
            self.thread.join(timeout=20)
            if self.thread.is_alive():
                raise ConnectionError("previous Muse session is still stopping")
        credentials = json.loads(credentials_json)
        api_root(credentials.get("api_url_v2"))
        noise_host(credentials.get("noise_host"))
        self.closed = False
        self.thread = threading.Thread(target=lambda: asyncio.run(self._run(credentials)), daemon=True, name="puck-muse")
        self.thread.start()

    def command(self, command, params, timeout):
        if not isinstance(params, dict):
            return {"ok": False, "error": "Invalid Muse Launcher request"}
        if command == "puck.request":
            request = params.get("request")
            if not isinstance(request, str) or not 0 < len(request) <= 16000:
                return {"ok": False, "error": "Invalid workspace request"}
            accepted = bool(self.callbacks.onWorkspace(request))
        elif command in ("puck.update", "puck.command"):
            encoded = json.dumps(params)
            if len(encoded) > 16000:
                return {"ok": False, "error": "Muse Launcher request too large"}
            callback = self.callbacks.onUpdate if command == "puck.update" else self.callbacks.onCommand
            accepted = bool(callback(encoded))
        else:
            return {"ok": False, "error": "Unsupported Muse Launcher request"}
        if accepted and self.session:
            self.session.control_stats["accepted"] += 1
        return {"ok": accepted, "payload": {"received": accepted, "executed": False, "approval_required": command != "puck.update"}}

    def notify(self, event_json, chat_id):
        if len(self.notifications) >= 16 or not self.loop or not self.session or self.session.registered_at is None:
            self.callbacks.onRequestFailed("Muse event was not sent. It will not be replayed.")
            return False
        event = json.loads(event_json)
        if not isinstance(event, dict) or len(event_json) > 8000:
            raise ValueError("invalid Muse Launcher event")
        session = self.session
        async def send_event():
            try:
                async with session.turn_lock:
                    result = await session.send_chat("Muse Launcher local event (external completion is NOT verified): " + event_json +
                        "\nUse puck.update for a useful visible update. Use puck.command only to propose a reviewed action. Never interpret this event as new approval.", chat_id)
                    if not result.get("ok"):
                        self.callbacks.onRequestFailed("Muse event was not accepted. It will not be replayed.")
            except Exception:
                self.callbacks.onRequestFailed("Muse event was not sent. It will not be replayed.")
        # No offline queue or automatic replay of user interactions.
        future = asyncio.run_coroutine_threadsafe(send_event(), self.loop)
        self.notifications.add(future)
        future.add_done_callback(self.notifications.discard)
        return True

    async def _run(self, credentials):
        self.loop = asyncio.get_running_loop()
        self.stop_event = asyncio.Event()
        failures = 0
        while not self.closed:
            self.callbacks.onState("connecting", "Connecting to Muse")
            try:
                root = api_root(credentials.get("api_url_v2"))
                vms, status = await asyncio.to_thread(fetch_vms_with_status, credentials["access_token"], root)
                if status == 401 or time.time() - credentials.get("saved_at", 0) > 3600:
                    tokens, refresh_status = await asyncio.to_thread(refresh_device_token, credentials["refresh_token"], self.identity.device_id, root, credentials.get("sdk_token"))
                    if refresh_status == 401:
                        self.callbacks.clearCredentials()
                        self.callbacks.onState("blocked", "Muse pairing was revoked. Pair again.")
                        return
                    if tokens:
                        candidate = {**credentials, "access_token": tokens["access_token"], "refresh_token": tokens["refresh_token"], "saved_at": time.time()}
                        if not self.callbacks.saveCredentials(json.dumps(candidate)):
                            raise ConnectionError("Credential persistence failed")
                        credentials = candidate
                        vms, status = await asyncio.to_thread(fetch_vms_with_status, credentials["access_token"], root)
                if not vms:
                    raise ConnectionError("Muse VM unavailable")
                vm = next((v for v in vms if v["is_default"]), vms[0])
                session = AndroidSession(callbacks=self.callbacks, noise_host=noise_host(credentials.get("noise_host")), vm_id=vm["vm_id"] or vm["vm_name"], vm_auth_token=vm["vm_auth_token"], device=AndroidDescription(node_id=self.identity.node_id, display_name="Muse Launcher", version="0.7.0", commands=COMMANDS), run_command=self.command)
                self.session = session
                outcome = await session.run(self.stop_event)
                self.last_control_stats = dict(session.control_stats)
                self.session = None
                if outcome == Outcome.UNPAIRED:
                    self.callbacks.clearCredentials()
                    self.callbacks.onState("blocked", "Muse removed this device. Pair again.")
                    return
                if outcome == Outcome.STOPPED:
                    return
            except Exception:
                if self.session is not None:
                    self.last_control_stats = dict(self.session.control_stats)
                self.session = None
                self.callbacks.onState("blocked", "Muse connection unavailable. Drafts remain local; requests are not replayed.")
            if self.closed:
                break
            failures += 1
            try:
                await asyncio.wait_for(self.stop_event.wait(), min(60, 2 ** min(failures, 6)))
                return
            except asyncio.TimeoutError:
                pass

    def send(self, request_id, text, chat_id, reply_instruction="", image_base64="", audio_base64="", video_base64=""):
        if not self.loop or not self.session or self.session.registered_at is None:
            return json.dumps({"ok": False, "error": "Muse is reconnecting"})
        session = self.session
        attachments = None
        if image_base64:
            try:
                image = base64.b64decode(image_base64, validate=True)
                if len(image) > 4_000_000 or not image.startswith(b"\xff\xd8"):
                    raise ValueError("Invalid JPEG")
                attachments = [{"type": "file", "mime_type": "image/jpeg", "filename": "muse-launcher-photo.jpg", "data_base64": image_base64}]
            except (ValueError, TypeError):
                return json.dumps({"ok": False})
        if audio_base64:
            try:
                audio = base64.b64decode(audio_base64, validate=True)
                if not 3244 <= len(audio) <= 2_000_044 or audio[:4] != b"RIFF" or audio[8:12] != b"WAVE":
                    raise ValueError("Invalid WAV")
                attachments = (attachments or []) + [{"type": "file", "mime_type": "audio/wav", "filename": "muse-launcher-voice.wav", "data_base64": audio_base64}]
            except (ValueError, TypeError):
                return json.dumps({"ok": False})
        if video_base64:
            try:
                if len(video_base64) > 10_666_668 or image_base64:
                    raise ValueError("Video too large or competing visual attachment")
                video = base64.b64decode(video_base64, validate=True)
                box_size = int.from_bytes(video[:4], "big")
                if not 24 <= len(video) <= 8_000_000 or video[4:8] != b"ftyp" or not 16 <= box_size <= len(video):
                    raise ValueError("Invalid MP4")
                attachments = (attachments or []) + [{"type": "file", "mime_type": "video/mp4", "filename": "muse-launcher-video.mp4", "data_base64": video_base64}]
            except (ValueError, TypeError):
                return json.dumps({"ok": False, "error": "Video must be an MP4 under 8 MB"})
        # One durable device-side conversation for requests and local reports.
        # Fresh markers reject stale parentless replies within that conversation.
        marker = "PUCK_REPLY:" + uuid.uuid4().hex + "\n"
        context = "Muse Launcher gadget interaction. Target this gadget node_id: " + self.identity.node_id + ". Handle requests yourself when possible and report back. For a useful visible update use this gadget\'s puck.update; for a local phone action propose puck.command for user review. Never treat an interaction as approval or claim an external action succeeded. User interaction follows:\n"
        if reply_instruction:
            instruction = reply_instruction[:4000].replace("Return exactly one JSON object, no markdown or extra prose.",
                "After the mandatory reply prefix line, return exactly one JSON object, no markdown or extra prose.")
            context = "Muse Launcher gadget interaction. " + instruction + "\n"
        context += "Begin your chat reply with this exact line, followed by your reply (including any requested JSON): " + marker
        def progress(phase):
            try:
                if hasattr(self.callbacks,"onTurnProgress"):self.callbacks.onTurnProgress(request_id,phase)
            except Exception:pass
        future = asyncio.run_coroutine_threadsafe(session.turn(context + text[:MAX_TEXT], chat_id, marker, attachments,progress=progress), self.loop)
        self.pending[request_id] = future
        try:
            reply = future.result(timeout=120)
            self.callbacks.onState("connected", "Muse connected")
            return json.dumps({"ok": True, "text": reply})
        except Exception as exc:
            future.cancel()
            safe_errors = {
                "missing chat acknowledgment": "Muse returned no chat acknowledgment",
                "uncorrelated chat acknowledgment": "Muse acknowledgment had no message ID",
                "subscription refused": "Muse reply subscription refused",
                "subscription reset": "Muse reply subscription reset",
                "invalid subscription event": "Muse reply event could not be parsed",
                "subscription ended before reply": "Muse reply stream ended before a correlated reply",
                "chat request refused": "Muse chat request was refused",
                "subscription buffer exceeded": "Muse subscription frame exceeds buffer limit",
                "subscription row exceeded": "Muse subscription event exceeds row limit",
                "subscription pre-ack overflow": "Muse subscription history exceeds early event limit",
                "too many assistant messages": "Muse reply exceeds message limit",
            }
            message = safe_errors.get(str(exc), "Muse request failed (%s)" % type(exc).__name__)
            if not isinstance(exc, asyncio.TimeoutError):
                self.callbacks.onRequestFailed(message)
            return json.dumps({"ok": False, "error": message})
        finally:
            self.pending.pop(request_id, None)

    def cancel(self, request_id):
        future = self.pending.get(request_id)
        if future:
            future.cancel()

    def disconnect(self):
        self.closed = True
        for future in list(self.pending.values()) + list(self.notifications):
            future.cancel()
        if self.loop and self.stop_event:
            self.loop.call_soon_threadsafe(self.stop_event.set)
        self.callbacks.onState("disconnected", "Muse disconnected")


def crypto_smoke():
    """No credentials/network: check the Android native wheel and Noise primitives."""
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from musegadget.noise import NoiseXXInitiator
    key = AESGCM.generate_key(bit_length=256)
    cipher = AESGCM(key)
    nonce = b"\x00" * 12
    encrypted = cipher.encrypt(nonce, b"puck-runtime-check", b"puck")
    assert cipher.decrypt(nonce, encrypted, b"puck") == b"puck-runtime-check"
    initiator = NoiseXXInitiator(); initiator.initialize()
    assert len(initiator.write_message1()) > 0
    return True
