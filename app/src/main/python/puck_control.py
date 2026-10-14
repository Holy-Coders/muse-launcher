"""Bounded Puck control compatibility, independent of the vendored SDK.

Only authenticated, unclaimed streams reach this decoder. Chat subscription
frames must never be interpreted as commands. No payloads are logged.
"""
import json
import struct

MAX_CONTROL_BYTES = 262144
MAX_SIDE_STREAMS = 8
SIDE_STREAM_TTL = 30


class ControlDecoder:
    def __init__(self):
        self.buf = bytearray()
        self.mode = None
        self.formats = {"prefixed": 0, "json": 0}

    def feed(self, data, ended=False):
        if not self.buf:
            self.mode = None
        if len(self.buf) + len(data) > MAX_CONTROL_BYTES:
            raise ValueError("control buffer exceeded")
        self.buf.extend(data)
        messages = []
        while self.buf:
            if self.mode is None:
                # Prefer a valid length prefix, even when its first byte is
                # '{' or whitespace. Raw JSON cannot have a plausible u32
                # prefix once four bytes are present.
                if len(self.buf) >= 4:
                    length = struct.unpack_from("<I", self.buf)[0]
                    if length <= MAX_CONTROL_BYTES - 4:
                        self.mode = "prefixed"
                    elif self.buf.lstrip().startswith(b"{"):
                        self.mode = "json"
                    else:
                        raise ValueError("invalid control framing")
                elif ended:
                    self.mode = "json"
                else:
                    break
            if self.mode == "prefixed":
                if len(self.buf) < 4:
                    break
                length = struct.unpack_from("<I", self.buf)[0]
                if length > MAX_CONTROL_BYTES - 4:
                    raise ValueError("control message exceeded")
                if len(self.buf) < 4 + length:
                    break
                raw = bytes(self.buf[4:4 + length])
                del self.buf[:4 + length]
                if not raw:
                    continue
                obj = json.loads(raw)
            else:
                raw = bytes(self.buf).lstrip()
                if not raw:
                    self.buf.clear()
                    break
                try:
                    decoded = raw.decode("utf-8")
                    obj, consumed = json.JSONDecoder().raw_decode(decoded)
                except (UnicodeDecodeError, json.JSONDecodeError):
                    if ended:
                        raise ValueError("invalid control JSON") from None
                    break
                self.buf = bytearray(raw[len(decoded[:consumed].encode("utf-8")):])
            if not isinstance(obj, dict):
                raise ValueError("control object required")
            self.formats[self.mode] += 1
            messages.append(obj)
            if len(messages) > 64:
                raise ValueError("control batch exceeded")
        if ended and self.buf:
            raise ValueError("incomplete control message")
        return messages


def valid_id(value):
    return (type(value) is int and abs(value) <= 2**53 - 1) or (
        isinstance(value, str) and 0 < len(value) <= 128)


def normalize_invoke(message, commands):
    """Return an invocation, None for other messages; malformed invokes fail.

    Direct-method compatibility is limited to Puck's registered tools. Unknown
    dotted methods are never inferred to be executable commands.
    """
    method = message.get("method")
    wrapped = method in ("link.invoke", "device.invoke", "invoke")
    direct = isinstance(method, str) and method in commands
    if not wrapped and not direct:
        return None
    if not valid_id(message.get("id")):
        raise ValueError("invalid invocation id")
    command = method if direct else message.get("command")
    params = message.get("params", {})
    if wrapped and command is None and isinstance(params, dict):
        command = params.get("command", params.get("name", params.get("method")))
        params = params.get("params", params.get("args", params.get("arguments", {})))
    timeout = message.get("timeout_ms", message.get("timeout"))
    if (not isinstance(command, str) or not 0 < len(command) <= 128 or
            not isinstance(params, dict) or
            (timeout is not None and (type(timeout) is not int or not 0 < timeout <= 600000))):
        raise ValueError("invalid invocation shape")
    return {"method": "link.invoke", "id": message["id"], "command": command,
            "params": params, "timeout_ms": timeout,
            **({"type": "req"} if message.get("type") == "req" else {})}
