---
title: Direct Python Workers (UDS IPC)
description: Design notes for running long-lived Python inference workers over a framed Unix Domain Socket protocol (no HTTP)
tags: [design, python, direct-workers, ipc, uds, inference]
difficulty: advanced
estimated_time: 10 minutes
related_docs:
  - ../../reference/cli/index.md
  - ../../guides/patterns/override-files/index.md
---

# Direct Python Workers (UDS IPC)

## Why this exists

Some algorithms need Python at runtime (e.g. PyTorch / transformers). A common first attempt is “start an HTTP server in Python and call it from Java”, but that adds avoidable complexity:

- startup ordering issues and random port allocation
- request routing and retry semantics
- additional serialization overhead (JSON/HTTP)
- difficult lifecycle management (zombie processes, timeouts, shutdowns)

Hotvect supports a lower-overhead alternative: **long-lived Python worker processes** connected to the JVM via a **Unix Domain Socket (UDS)** and a **framed binary protocol**.

## Configuration surface (recommended)

Keep backend selection global and runtime mode scoped:

- `algorithm_parameters.backend` → backend implementation (`tensorflow`, later `torch`)
- `algorithm_parameters.realtime.litserve` or `algorithm_parameters.realtime.direct_workers`
- `algorithm_parameters.batch.litserve` or `algorithm_parameters.batch.direct_workers`

Migration note: if you still have older configs or notes that use `algorithm_parameters.workers.backend`, rename that field to `algorithm_parameters.backend`. The worker runtime now treats the top-level key as the canonical backend contract.

Per scope, the relevant runtime block is:

- `litserve`
- `direct_workers`

Expected runtime JSON shape (see `com.hotvect.python.direct.DirectWorkersConfig#fromJson` for the authoritative schema):

```json
{
  "accelerator": "auto",
  "devices": "auto",
  "workers_per_device": 1,
  "startup_timeout_ms": 60000,
  "request_timeout_ms": 30000,
  "shutdown": {
    "sigterm_timeout_ms": 5000,
    "sigkill_timeout_ms": 2000,
    "descendants_timeout_ms": 10000
  },
  "ipc": {
    "work_queue_size_per_worker": 20,
    "queue_full_policy": "reject",
    "retry": {
      "max_attempts": 1,
      "backoff_ms": 0
    },
    "max_frame_bytes": 16777216,
    "uds_base_dir": "/tmp"
  }
}
```

Key behaviors:

- `accelerator="auto"` selects CPU unless CUDA devices are available.
- If the parent process has `CUDA_VISIBLE_DEVICES` set, it acts as a **hard allowlist** for what workers may use.
- `request_timeout_ms` is preferred; legacy `predict_timeout_ms` may exist in older configs (only one may be set).

## Python executable resolution

Set the interpreter in the selected runtime block:

- `algorithm_parameters.realtime.litserve.python_executable`
- `algorithm_parameters.batch.direct_workers.python_executable`
- or the analogous scoped variant you are actually using

Hotvect also supports an env override:

- `HOTVECT_PYTHON_EXECUTABLE=/path/to/python`

For `hv worker serve` local LitServe startup, interpreter resolution order is:

1. `HOTVECT_PYTHON_EXECUTABLE`
2. the selected `litserve.python_executable`
3. `sys.executable`

Use this for local development when your Python dependencies (e.g. torch) are installed in a specific environment.

## Protocol overview

All messages are sent as **frames**:

- 4-byte big-endian length prefix (`uint32`)
- followed by the frame payload bytes

The payload starts with a one-byte **opcode**. Important opcodes:

- `STARTUP` → worker announces PID + readiness status
- `STARTUP_ACK` → JVM acks readiness
- `GET_WORK` → worker polls for the next request
- `WORK` → JVM sends a request
- `RESULT` → worker returns the result
- `REQUEST_ERROR` / `WORKER_ERROR` → worker reports failures
- `SHUTDOWN` → JVM asks the worker to exit

### WORK frame layout (compatibility-critical)

If you implement a worker, do not guess the wire format. Use the helper codec:

- Python: `hotvect.direct_worker.ipc.decode_work(...)`

The current `WORK` payload layout is:

1. `op` (byte) – must be `OP_WORK`
2. `request_id` (u16 length + UTF-8)
3. `batch_size` (int32)
4. `payload_count` (int32)
5. `payloads`:
   - repeated `payload_count` times:
     - `payload_len` (int32)
     - `payload_bytes`
6. optional `traceparent` (u16 length + UTF-8)

If your Python worker decodes the wrong fields (for example, reading only one int32 and treating it as payload length), the JVM will typically surface confusing errors like timeouts, truncated frames, or “identical outputs” due to misaligned request parsing.

## Debugging tips

- Prefer fail-fast startup:
  - on worker init failure, send `STARTUP(status=CANNOT_START, message=...)` and exit
- Keep `max_frame_bytes` conservative; large embeddings/log payloads can exceed defaults.
- When debugging locally, set `HOTVECT_PYTHON_EXECUTABLE` explicitly and log the resolved executable.

## TensorFlow worker payloads (implemented)

The built-in TensorFlow direct worker (`python/hotvect/direct_worker/tensorflow_worker.py`) expects the `WORK.payloads`
to be a **columnar** list of serialized tensors:

- `payload_count` must equal the number of features in the materialized schema.
- Each payload is a `tf.io.serialize_tensor(...)`-compatible byte string and is decoded with `tf.io.parse_tensor`.
- Feature order is derived deterministically from the schema (current worker uses sorted feature names).

This design avoids `tf.io.parse_example(...)` on every request and shifts decoding into TensorFlow C++ ops.

## Local HTTP model debugging

Hotvect now distinguishes between two local debugging surfaces:

- `hv serve` → serves the **full algorithm** over HTTP using the Java runtime
- `hv serve --ui` → enables the browser debugger on the same algorithm server
- `hv worker serve` → serves the **worker runtime only** over HTTP using LitServe

`hv worker serve` is intentionally narrower than `hv serve`:

- it bypasses JVM request decoding and feature extraction
- it expects **worker-ready feature rows** matching `encoded-schema-description`
- it resolves the backend from `algorithm_parameters.backend` in the algorithm definition
- it starts a local LitServe process and exposes LitServe directly
- if the selected scope has no `litserve` block, it falls back to sibling `direct_workers` for overlapping runtime knobs

If an algorithm definition still uses the older `algorithm_parameters.workers.backend` path, migrate it before using `hv worker serve`; the worker-only HTTP path no longer reads the nested key.

This keeps the transport consistent with direct workers without introducing a second, unrelated serving stack.

The intended contract is:

- backend selection lives at `algorithm_parameters.backend`
- local debug HTTP uses scoped `litserve`
- managed JVM-integrated workers use scoped `direct_workers`
- LitServe HTTP and local UDS workers share the same backend semantics, but not the same wire codec

## References

- Java worker manager + config: `hotvect-python/src/main/java/com/hotvect/python/direct/*`
- Python framing + codecs: `python/hotvect/direct_worker/ipc.py`
