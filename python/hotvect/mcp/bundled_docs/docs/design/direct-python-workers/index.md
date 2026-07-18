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

`hotvect-python` supplies the process manager, queueing, shutdown, device assignment, and wire protocol. It does not
turn an arbitrary Python function into a Hotvect algorithm automatically. The algorithm integration still chooses a
Python module, converts its typed features into worker payloads, interprets results, and closes the worker manager.
The current repository contains a TensorFlow worker and a specialized PyTorch text-embedding worker; neither is a
generic adapter for every TensorFlow or PyTorch model. A different library or payload needs an algorithm-owned worker
and matching JVM integration.

## Configuration surface (recommended)

For an algorithm integration that supports both the current worker backends and LitServe debugging, keep backend
selection global and runtime mode scoped:

- `algorithm_parameters.backend` → backend implementation (`tensorflow` or `torch`)
- `algorithm_parameters.realtime.litserve` or `algorithm_parameters.realtime.direct_workers`
- `algorithm_parameters.batch.litserve` or `algorithm_parameters.batch.direct_workers`

Per scope, the relevant runtime block is:

- `litserve`
- `direct_workers`

Expected `direct_workers` JSON shape (see `com.hotvect.python.direct.DirectWorkersConfig#fromJson` for the authoritative schema):

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
- `request_timeout_ms` controls the per-request timeout.

## Python executable ownership

For JVM-managed direct workers, the algorithm integration constructs `PythonWorkerCommand` with an explicit Python
executable and module. `DirectWorkersConfig` validates process-count, timeout, shutdown, and IPC fields; it does not
select the interpreter or module. If the integration stores `python_executable` beside its `direct_workers` block, that
field is an algorithm-owned convention which the integration must read.

The `hv worker serve` LitServe debugger does have standard interpreter resolution. Its order is:

1. `HOTVECT_PYTHON_EXECUTABLE`
2. the selected `litserve.python_executable`
3. `sys.executable`

Use `HOTVECT_PYTHON_EXECUTABLE` for local HTTP debugging when model dependencies are installed in a specific
environment. Do not assume that this environment variable changes a JVM direct-worker integration unless that
algorithm explicitly implements the same convention.

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

## Integration checklist

Before calling a direct worker from an algorithm factory, verify all of these contracts:

1. The algorithm package includes `hotvect-python`; the Python environment contains the selected worker module and its
   model-library dependencies.
2. The factory selects the `realtime.direct_workers` or `batch.direct_workers` block from its `ExecutionContext` rather
   than assuming one scope.
3. `PythonWorkerCommand` names the exact interpreter and module; `DirectWorkersConfig.fromJson(...)` validates the
   selected configuration.
4. JVM payload encoding and Python `decode_work(...)` agree on payload count, order, tensor/array representation, and
   result shape.
5. The algorithm owns `DirectWorkerManager` and closes it from `Algorithm.close()`.
6. A bounded integration test starts the real Python environment, sends a known batch, verifies numerical output, and
   confirms shutdown leaves no worker process behind.

The product-search example uses CatBoost in the JVM and does not demonstrate this integration. Treat this page and the
`hotvect-python` tests as the current worker reference rather than inferring Python-worker behavior from that example.

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
- the selected scope should declare a `litserve` block for this HTTP debugging path

This keeps the transport consistent with direct workers without introducing a second, unrelated serving stack.

The intended contract is:

- backend selection lives at `algorithm_parameters.backend`
- local debug HTTP uses scoped `litserve`
- managed JVM-integrated workers use scoped `direct_workers`
- LitServe HTTP and local UDS workers share the same backend semantics, but not the same wire codec

## References

- Java worker manager + config: `hotvect-python/src/main/java/com/hotvect/python/direct/*`
- Python framing + codecs: `python/hotvect/direct_worker/ipc.py`

## Related documentation

- [Runtime topologies](../../architecture/runtime-topologies/index.md) compares managed workers with in-JVM and remote
  offline execution.
- [Algorithm definitions](../../reference/algorithm-definition/index.md#algorithm_parameters-for-python-workers)
  documents the `litserve` and `direct_workers` configuration fields.
- [Command-line interfaces](../../reference/cli/index.md) covers the local `hv worker serve` debugger.
