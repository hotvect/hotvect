import socket
import struct

import pytest

from hotvect.direct_worker.ipc import OP_WORK, decode_work, read_frame, write_frame


def _u16(s: str) -> bytes:
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def test_decode_work_without_traceparent() -> None:
    payload = bytearray()
    payload.append(OP_WORK)
    payload += _u16("req#1")
    payload += struct.pack(">i", 3)  # batch_size
    payload += struct.pack(">i", 2)  # payload_count
    payload += struct.pack(">i", 3) + b"abc"
    payload += struct.pack(">i", 2) + b"zz"

    decoded = decode_work(bytes(payload))
    assert decoded.request_id == "req#1"
    assert decoded.batch_size == 3
    assert decoded.payloads == [b"abc", b"zz"]
    assert decoded.traceparent == ""


def test_decode_work_with_traceparent() -> None:
    traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
    payload = bytearray()
    payload.append(OP_WORK)
    payload += _u16("req#2")
    payload += struct.pack(">i", 1)  # batch_size
    payload += struct.pack(">i", 0)  # payload_count
    payload += _u16(traceparent)

    decoded = decode_work(bytes(payload))
    assert decoded.request_id == "req#2"
    assert decoded.batch_size == 1
    assert decoded.payloads == []
    assert decoded.traceparent == traceparent


def test_write_frame_rejects_payload_larger_than_negotiated_max_frame_size() -> None:
    left, right = socket.socketpair()
    try:
        with pytest.raises(ValueError, match=r"IPC payload exceeds max_frame_bytes: 5 > 4"):
            write_frame(left, b"12345", 4)
    finally:
        left.close()
        right.close()


def test_write_frame_allows_payload_at_negotiated_max_frame_size() -> None:
    left, right = socket.socketpair()
    try:
        payload = b"1234"
        write_frame(left, payload, 4)
        assert read_frame(right, 4) == payload
    finally:
        left.close()
        right.close()


def test_write_frame_without_max_frame_bytes_remains_backward_compatible() -> None:
    left, right = socket.socketpair()
    try:
        payload = b"12345"
        write_frame(left, payload)
        assert read_frame(right, 5) == payload
    finally:
        left.close()
        right.close()
