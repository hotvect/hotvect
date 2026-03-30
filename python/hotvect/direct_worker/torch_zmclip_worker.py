"""Direct worker for zmCLIP text embeddings over the Hotvect UDS protocol."""

from __future__ import annotations

import argparse
import os
import signal
import socket
import traceback
from collections import OrderedDict
from collections.abc import Iterable

import numpy as np

from hotvect.direct_worker.ipc import (
    OP_GET_WORK,
    OP_SHUTDOWN,
    OP_STARTUP_ACK,
    STARTUP_CANNOT_START,
    STARTUP_READY,
    decode_work,
    encode_request_error,
    encode_result,
    encode_startup,
    encode_worker_error,
    read_frame,
    send_startup_status,
    write_frame,
)
from hotvect.worker.torch_zmclip_backend import (
    _encode_text_batch,
    _load_zmclip,
    _resolve_checkpoint_path,
    _resolve_device,
)


def _setup_otel(worker_index: int, span_prefix: str):
    if (os.environ.get("OTEL_SDK_DISABLED") or "").strip().lower() in {"true", "1", "yes"}:
        return None, None

    endpoint = os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT")
    if not endpoint:
        return None, None

    try:
        from opentelemetry import trace
        from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
        from opentelemetry.sdk.resources import Resource
        from opentelemetry.sdk.trace import TracerProvider
        from opentelemetry.sdk.trace.export import BatchSpanProcessor
        from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator
    except Exception as e:
        raise RuntimeError(
            "OTEL_EXPORTER_OTLP_ENDPOINT is set, but required OpenTelemetry packages are not available in the python worker environment"
        ) from e

    base_service = os.environ.get("OTEL_SERVICE_NAME") or span_prefix or "hotvect"
    service_name = f"{base_service}-py-worker"
    resource = Resource.create({"service.name": service_name, "worker.index": worker_index})

    endpoint = endpoint.removeprefix("http://").removeprefix("https://")

    provider = TracerProvider(resource=resource)
    provider.add_span_processor(
        BatchSpanProcessor(
            OTLPSpanExporter(
                endpoint=endpoint,
                insecure=True,
            )
        )
    )
    trace.set_tracer_provider(provider)

    tracer = trace.get_tracer("hotvect.direct_worker.torch_zmclip_worker")
    propagator = TraceContextTextMapPropagator()
    return tracer, propagator


class _LruCache:
    def __init__(self, max_size: int):
        self.max_size = max(0, int(max_size))
        self._entries: OrderedDict[str, np.ndarray] = OrderedDict()

    def get(self, key: str) -> np.ndarray | None:
        if self.max_size <= 0:
            return None
        value = self._entries.get(key)
        if value is None:
            return None
        self._entries.move_to_end(key, last=True)
        return value

    def put(self, key: str, value: np.ndarray) -> None:
        if self.max_size <= 0:
            return
        self._entries[key] = value
        self._entries.move_to_end(key, last=True)
        while len(self._entries) > self.max_size:
            self._entries.popitem(last=False)


def _iter_chunks(values: list[str], chunk_size: int) -> Iterable[list[str]]:
    if chunk_size <= 0:
        raise ValueError(f"chunk_size must be >= 1, got {chunk_size}")
    for index in range(0, len(values), chunk_size):
        yield values[index : index + chunk_size]


def _materialize_embeddings(
    *,
    texts: list[str],
    cache: _LruCache,
    model,
    tokenizer,
    torch_device,
    max_batch_size: int,
    otel_tracer,
    span_prefix: str,
) -> list[np.ndarray]:
    unique_missing: list[str] = []
    seen_missing: set[str] = set()
    for text in texts:
        if cache.get(text) is None and text not in seen_missing:
            seen_missing.add(text)
            unique_missing.append(text)

    for chunk in _iter_chunks(unique_missing, max(1, int(max_batch_size))):
        if otel_tracer is None:
            encoded = _encode_text_batch(model, tokenizer, torch_device, chunk)
        else:
            with otel_tracer.start_as_current_span(f"{span_prefix}.python_worker.model_call") as span:
                span.set_attribute("batch.size", int(len(chunk)))
                encoded = _encode_text_batch(model, tokenizer, torch_device, chunk)
        if encoded.ndim != 2:
            raise ValueError(f"Expected 2D embeddings, got shape={getattr(encoded, 'shape', None)}")
        for text, row in zip(chunk, encoded):
            cache.put(text, np.asarray(row, dtype=np.float32).reshape((-1,)))

    outputs: list[np.ndarray] = []
    for text in texts:
        embedding = cache.get(text)
        if embedding is None:
            raise RuntimeError(f"Embedding cache fill failed for text={text!r}")
        outputs.append(embedding)
    return outputs


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint-path", required=True)
    parser.add_argument("--model-name", default="xlm-roberta-base-ViT-B-32")
    parser.add_argument("--use-torch-compile", action="store_true")
    parser.add_argument("--torch-num-threads", type=int, default=0)
    parser.add_argument("--torch-num-interop-threads", type=int, default=0)
    parser.add_argument("--warmup-iterations", type=int, default=1)
    parser.add_argument("--cache-size", type=int, default=10_000)
    parser.add_argument("--max-batch-size", type=int, default=16)
    parser.add_argument("--connect-uds-path", required=True)
    parser.add_argument("--max-frame-bytes", type=int, default=16 * 1024 * 1024)
    parser.add_argument("--worker-index", type=int, default=0)
    parser.add_argument("--span-prefix", default="hotvect")
    parser.add_argument(
        "--debug-include-tf-inputs", action="store_true"
    )  # accepted for parity with worker CLI/debug flows
    args = parser.parse_args()

    stop = False
    span_prefix = (args.span_prefix or "hotvect").strip()
    otel_tracer, otel_propagator = _setup_otel(args.worker_index, span_prefix)

    def _on_sigterm(signum, frame):
        nonlocal stop
        stop = True

    signal.signal(signal.SIGTERM, _on_sigterm)

    try:
        checkpoint_path = _resolve_checkpoint_path(args.checkpoint_path)
        model, tokenizer, torch_device = _load_zmclip(
            args.model_name,
            str(checkpoint_path),
            _resolve_device(),
            use_torch_compile=bool(args.use_torch_compile),
            torch_num_threads=int(args.torch_num_threads),
            torch_num_interop_threads=int(args.torch_num_interop_threads),
        )
        for _ in range(max(0, int(args.warmup_iterations))):
            _encode_text_batch(model, tokenizer, torch_device, ["warmup"])
        print(
            f"Direct worker starting (worker_index={args.worker_index}) "
            f"(device={torch_device}, checkpoint={checkpoint_path})"
        )
        print("Direct worker model loaded")
    except Exception as e:
        send_startup_status(
            args.connect_uds_path,
            args.max_frame_bytes,
            STARTUP_CANNOT_START,
            f"cannot_start(worker_index={args.worker_index}): {type(e).__name__}: {e}",
        )
        raise

    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.connect(args.connect_uds_path)

    write_frame(sock, encode_startup(STARTUP_READY, ""), args.max_frame_bytes)

    ack = read_frame(sock, args.max_frame_bytes)
    if len(ack) < 1 or ack[0] != OP_STARTUP_ACK:
        raise RuntimeError(f"Invalid STARTUP_ACK: {ack[:16]!r}")

    cache = _LruCache(args.cache_size)

    try:
        while not stop:
            write_frame(sock, bytes([OP_GET_WORK]), args.max_frame_bytes)

            payload = read_frame(sock, args.max_frame_bytes)
            if not payload:
                raise EOFError("empty payload")

            op = payload[0]
            if op == OP_SHUTDOWN:
                print(f"Direct worker received SHUTDOWN (worker_index={args.worker_index})")
                break

            work = decode_work(payload)
            request_id = work.request_id

            try:
                if work.batch_size != len(work.payloads):
                    raise ValueError(
                        f"WORK batch_size={work.batch_size} does not match payload_count={len(work.payloads)}"
                    )
                texts = [payload.decode("utf-8") for payload in work.payloads]
                if not texts:
                    raise ValueError("Empty batch payloads")

                otel_context = None
                if otel_propagator is not None and work.traceparent:
                    try:
                        otel_context = otel_propagator.extract({"traceparent": work.traceparent})
                    except Exception:
                        otel_context = None

                if otel_tracer is None:
                    embeddings = _materialize_embeddings(
                        texts=texts,
                        cache=cache,
                        model=model,
                        tokenizer=tokenizer,
                        torch_device=torch_device,
                        max_batch_size=args.max_batch_size,
                        otel_tracer=None,
                        span_prefix=span_prefix,
                    )
                else:
                    with otel_tracer.start_as_current_span(
                        f"{span_prefix}.python_worker.request", context=otel_context
                    ) as span:
                        span.set_attribute("worker.index", int(args.worker_index))
                        span.set_attribute("batch.size", int(work.batch_size))
                        embeddings = _materialize_embeddings(
                            texts=texts,
                            cache=cache,
                            model=model,
                            tokenizer=tokenizer,
                            torch_device=torch_device,
                            max_batch_size=args.max_batch_size,
                            otel_tracer=otel_tracer,
                            span_prefix=span_prefix,
                        )

                if len(embeddings) != 1:
                    raise ValueError(
                        f"Expected exactly one text payload per request; got {len(embeddings)} (caller batching is unsupported)"
                    )
                write_frame(
                    sock,
                    encode_result(request_id, embeddings[0].tolist(), None),
                    args.max_frame_bytes,
                )
            except Exception as e:
                write_frame(
                    sock,
                    encode_request_error(request_id, f"{type(e).__name__}: {e}"),
                    args.max_frame_bytes,
                )
    except Exception as e:
        try:
            write_frame(sock, encode_worker_error(f"{type(e).__name__}: {e}"), args.max_frame_bytes)
        except Exception:
            pass
        raise
    finally:
        try:
            sock.close()
        except Exception:
            pass


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
    except Exception:
        traceback.print_exc()
        raise
