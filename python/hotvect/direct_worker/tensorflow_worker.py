"""Direct worker for TF SavedModel inference over a Unix Domain Socket (UDS).

Long-lived python process that:
- loads the model once
- connects to a Java-owned UDS endpoint
- serves predict requests over a simple framed binary protocol
"""

from __future__ import annotations

import argparse
import os
import signal
import socket
import traceback

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
    read_frame,
    send_startup_status,
    write_frame,
)
from hotvect.worker.tensorflow_backend import TensorFlowWorkerBackend


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

    tracer = trace.get_tracer("hotvect.direct_worker.tensorflow_worker")
    propagator = TraceContextTextMapPropagator()
    return tracer, propagator


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-path", required=True)
    parser.add_argument("--schema-path", required=True)
    parser.add_argument("--connect-uds-path", required=True)
    parser.add_argument("--max-frame-bytes", type=int, default=16 * 1024 * 1024)
    parser.add_argument("--worker-index", type=int, default=0)
    parser.add_argument("--debug-include-tf-inputs", action="store_true")
    parser.add_argument("--span-prefix", default="hotvect")
    args = parser.parse_args()

    stop = False
    span_prefix = (args.span_prefix or "hotvect").strip()
    otel_tracer, otel_propagator = _setup_otel(args.worker_index, span_prefix)

    def _on_sigterm(signum, frame):
        nonlocal stop
        stop = True

    signal.signal(signal.SIGTERM, _on_sigterm)

    try:
        runtime = TensorFlowWorkerBackend(args.model_path, args.schema_path)
        cuda_visible_devices = os.environ.get("CUDA_VISIBLE_DEVICES")
        print(
            f"Direct worker starting (worker_index={args.worker_index}) "
            f"(TensorFlow device={runtime.tf_device}, CUDA_VISIBLE_DEVICES={cuda_visible_devices!r})"
        )
        print("Direct worker model loaded")
    except Exception as e:
        send_startup_status(
            args.connect_uds_path,
            args.max_frame_bytes,
            STARTUP_CANNOT_START,
            f"cannot_start(worker_index={args.worker_index}): {e}",
        )
        raise

    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.connect(args.connect_uds_path)

    write_frame(sock, encode_startup(STARTUP_READY, ""), args.max_frame_bytes)

    ack = read_frame(sock, args.max_frame_bytes)
    if len(ack) < 1 or ack[0] != OP_STARTUP_ACK:
        raise RuntimeError(f"Invalid STARTUP_ACK: {ack[:16]!r}")

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
                otel_context = None
                if otel_propagator is not None and work.traceparent:
                    try:
                        otel_context = otel_propagator.extract({"traceparent": work.traceparent})
                    except Exception:
                        otel_context = None

                if otel_tracer is None:
                    scores, debug_json = runtime.predict_serialized(
                        work.batch_size,
                        work.payloads,
                        debug_include_tf_inputs=args.debug_include_tf_inputs,
                        request_id=request_id,
                        span_prefix=span_prefix,
                    )
                else:
                    with otel_tracer.start_as_current_span(
                        f"{span_prefix}.python_worker.request", context=otel_context
                    ) as span:
                        span.set_attribute("worker.index", int(args.worker_index))
                        span.set_attribute("batch.size", int(work.batch_size))
                        scores, debug_json = runtime.predict_serialized(
                            work.batch_size,
                            work.payloads,
                            debug_include_tf_inputs=args.debug_include_tf_inputs,
                            request_id=request_id,
                            span_prefix=span_prefix,
                            otel_tracer=otel_tracer,
                        )

                write_frame(sock, encode_result(request_id, scores.tolist(), debug_json), args.max_frame_bytes)
            except Exception as e:
                write_frame(sock, encode_request_error(request_id, str(e)), args.max_frame_bytes)
    finally:
        try:
            sock.close()
        except Exception:
            pass


if __name__ == "__main__":
    try:
        main()
    except Exception:
        traceback.print_exc()
        raise
