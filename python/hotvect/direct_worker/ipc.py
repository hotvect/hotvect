from __future__ import annotations

import os
import socket
import struct
import sys
import traceback
from dataclasses import dataclass
from typing import List, Tuple

OP_STARTUP = 1
OP_STARTUP_ACK = 2
OP_WORK = 3
OP_RESULT = 4
OP_GET_WORK = 5
OP_SHUTDOWN = 6
OP_REQUEST_ERROR = 7
OP_WORKER_ERROR = 8

STARTUP_READY = 0
STARTUP_CANNOT_START = 1


def read_exact(sock: socket.socket, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise EOFError("Socket closed")
        buf.extend(chunk)
    return bytes(buf)


def read_frame(sock: socket.socket, max_frame_bytes: int) -> bytes:
    (length,) = struct.unpack(">I", read_exact(sock, 4))
    if length < 0 or length > max_frame_bytes:
        raise ValueError(f"Invalid frame length: {length} (max={max_frame_bytes})")
    return read_exact(sock, length)


def write_frame(sock: socket.socket, payload: bytes, max_frame_bytes: int | None = None) -> None:
    if max_frame_bytes is not None and len(payload) > max_frame_bytes:
        raise ValueError(f"IPC payload exceeds max_frame_bytes: {len(payload)} > {max_frame_bytes}")
    sock.sendall(struct.pack(">I", len(payload)) + payload)


def _read_u16(buf: memoryview, offset: int) -> Tuple[int, int]:
    (v,) = struct.unpack_from(">H", buf, offset)
    return int(v), offset + 2


def _read_i32(buf: memoryview, offset: int) -> Tuple[int, int]:
    (v,) = struct.unpack_from(">i", buf, offset)
    return int(v), offset + 4


def _read_i64(buf: memoryview, offset: int) -> Tuple[int, int]:
    (v,) = struct.unpack_from(">q", buf, offset)
    return int(v), offset + 8


def _read_utf8_u16(buf: memoryview, offset: int) -> Tuple[str, int]:
    n, offset = _read_u16(buf, offset)
    if n == 0:
        return "", offset
    s = bytes(buf[offset : offset + n]).decode("utf-8")
    return s, offset + n


def _encode_utf8_u16(s: str) -> bytes:
    b = (s or "").encode("utf-8")
    if len(b) > 65535:
        raise ValueError(f"String too long for u16: {len(b)} bytes")
    return struct.pack(">H", len(b)) + b


def encode_startup(status: int, message: str) -> bytes:
    out = bytearray()
    out.append(OP_STARTUP)
    out.extend(struct.pack(">q", os.getpid()))
    out.append(int(status) & 0xFF)
    out.extend(_encode_utf8_u16(message or ""))
    return bytes(out)


def encode_request_error(request_id: str | None, message: str) -> bytes:
    out = bytearray()
    out.append(OP_REQUEST_ERROR)
    out.extend(_encode_utf8_u16(request_id if request_id is not None else ""))
    msg_bytes = (message or "").encode("utf-8")
    if len(msg_bytes) > 65535:
        msg_bytes = msg_bytes[:65535]
    out.extend(struct.pack(">H", len(msg_bytes)))
    out.extend(msg_bytes)
    return bytes(out)


def encode_worker_error(message: str) -> bytes:
    out = bytearray()
    out.append(OP_WORKER_ERROR)
    msg_bytes = (message or "").encode("utf-8")
    if len(msg_bytes) > 65535:
        msg_bytes = msg_bytes[:65535]
    out.extend(struct.pack(">H", len(msg_bytes)))
    out.extend(msg_bytes)
    return bytes(out)


def encode_result(request_id: str, output: List[float], debug_json: str | None) -> bytes:
    out = bytearray()
    out.append(OP_RESULT)
    out.extend(_encode_utf8_u16(request_id))
    out.extend(struct.pack(">i", int(len(output))))
    for v in output:
        out.extend(struct.pack(">f", float(v)))

    # Always include debug_len for compatibility with the Java decoder (it accepts either).
    if not debug_json:
        out.extend(struct.pack(">i", 0))
        return bytes(out)

    dbg = debug_json.encode("utf-8")
    out.extend(struct.pack(">i", len(dbg)))
    out.extend(dbg)
    return bytes(out)


@dataclass(frozen=True)
class DecodedWork:
    request_id: str
    batch_size: int
    payloads: List[bytes]
    traceparent: str


def decode_work(payload: bytes) -> DecodedWork:
    buf = memoryview(payload)
    if len(buf) < 1 + 2 + 4 + 4:
        raise ValueError("WORK frame too small")

    op = int(buf[0])
    if op != OP_WORK:
        raise ValueError(f"Expected WORK, got op={op}")

    offset = 1
    request_id, offset = _read_utf8_u16(buf, offset)

    batch_size, offset = _read_i32(buf, offset)
    if batch_size < 0:
        raise ValueError(f"Invalid batch_size={batch_size}")

    payload_count, offset = _read_i32(buf, offset)
    if payload_count < 0:
        raise ValueError(f"Invalid payload_count={payload_count}")

    payloads: List[bytes] = []
    for _ in range(payload_count):
        n, offset = _read_i32(buf, offset)
        if n < 0:
            raise ValueError(f"Invalid payload_len={n}")
        if offset + n > len(buf):
            raise ValueError("Truncated payload")
        payloads.append(bytes(buf[offset : offset + n]))
        offset += n

    traceparent = ""
    if offset < len(buf):
        traceparent, offset = _read_utf8_u16(buf, offset)

    if offset != len(buf):
        raise ValueError(f"Trailing bytes after WORK frame: {len(buf) - offset}")

    return DecodedWork(
        request_id=request_id,
        batch_size=batch_size,
        payloads=payloads,
        traceparent=traceparent,
    )


def send_startup_status(connect_uds_path: str, max_frame_bytes: int, status: int, message: str) -> None:
    """Best-effort: connect and send STARTUP(status, message). For READY, also validate STARTUP_ACK."""
    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
            sock.settimeout(5.0)
            sock.connect(connect_uds_path)
            sanitized = (message or "")[:2000]
            write_frame(sock, encode_startup(status, sanitized), max_frame_bytes)

            if status == STARTUP_READY:
                ack = read_frame(sock, max_frame_bytes)
                if len(ack) < 1 or ack[0] != OP_STARTUP_ACK:
                    return
    except Exception as e:
        try:
            print(
                f"Direct worker failed to send STARTUP status={status} to {connect_uds_path!r}: {e}",
                file=sys.stderr,
            )
            traceback.print_exc()
        except Exception:
            pass
