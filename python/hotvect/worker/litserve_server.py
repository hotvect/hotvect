from __future__ import annotations

import argparse
import json
import logging
import traceback
from typing import Any, Dict, Optional

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse

from hotvect.serve import HotvectLitAPI, run_server
from hotvect.worker.tensorflow_backend import TensorFlowWorkerBackend
from hotvect.worker.torch_zmclip_backend import TorchZmClipWorkerBackend

logging.basicConfig(level=logging.INFO, format="%(asctime)s:%(levelname)s:%(name)s:%(message)s")
log = logging.getLogger(__name__)


def build_arg_parser(default_backend: Optional[str] = None) -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    if default_backend is None:
        parser.add_argument("--backend", required=True, choices=["tensorflow", "torch"])
    else:
        parser.set_defaults(backend=default_backend)
    parser.add_argument("--model-path", required=True)
    parser.add_argument("--schema-path", required=True)
    parser.add_argument("--model-name", default="model")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--debug-include-tf-inputs", action="store_true")
    parser.add_argument("--torch-model-name", default="xlm-roberta-base-ViT-B-32")
    parser.add_argument("--use-torch-compile", action="store_true")
    parser.add_argument("--torch-num-threads", type=int, default=0)
    parser.add_argument("--torch-num-interop-threads", type=int, default=0)
    parser.add_argument("--warmup-iterations", type=int, default=1)
    parser.add_argument("--accelerator", default="auto", choices=["cpu", "cuda", "mps", "auto"])
    parser.add_argument("--devices", default="auto")
    parser.add_argument("--workers-per-device", type=int, default=1)
    parser.add_argument("--request-timeout-seconds", type=float, default=30.0)
    return parser


def _parse_devices_arg(value: Any) -> int | str:
    if isinstance(value, str):
        stripped = value.strip().lower()
        if stripped == "auto":
            return "auto"
        if stripped.isdigit():
            return int(stripped)
    if isinstance(value, int):
        return value
    raise ValueError(f"--devices must be an integer or 'auto', got: {value!r}")


def build_runtime(args: argparse.Namespace):
    if args.backend == "tensorflow":
        return TensorFlowWorkerBackend(args.model_path, args.schema_path)
    if args.backend == "torch":
        return TorchZmClipWorkerBackend(
            args.model_path,
            args.schema_path,
            model_name=args.torch_model_name,
            use_torch_compile=bool(args.use_torch_compile),
            torch_num_threads=int(args.torch_num_threads),
            torch_num_interop_threads=int(args.torch_num_interop_threads),
            warmup_iterations=int(args.warmup_iterations),
        )
    raise ValueError(f"Unsupported backend: {args.backend}")


class HotvectWorkerLitAPI(HotvectLitAPI):
    def __init__(self, *, args: argparse.Namespace):
        self.args = args
        self.model_name = args.model_name
        self.debug_include_tf_inputs = bool(args.debug_include_tf_inputs)
        super().__init__(model_path=args.model_path)

    def load_model(self, device: str):
        return build_runtime(self.args)

    def decode_request(self, request) -> Dict[str, Any]:
        if not isinstance(request, dict):
            raise HTTPException(status_code=400, detail="Request body must be a JSON object")
        batch = request.get("batch")
        if not isinstance(batch, list):
            raise HTTPException(status_code=400, detail="Request body must contain a 'batch' list")
        return request

    def predict(self, request: Dict[str, Any]) -> Dict[str, Any]:
        request_id = str(request.get("request_id") or request.get("id") or "http")
        batch = request.get("batch")
        if not isinstance(batch, list):
            raise HTTPException(status_code=400, detail="Request body must contain a 'batch' list")
        try:
            assert self.model is not None
            scores, debug_json = self.model.predict_rows(
                batch,
                debug_include_tf_inputs=self.debug_include_tf_inputs,
                request_id=request_id,
            )
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        except Exception as exc:
            log.exception("LitServe worker predict failed")
            raise HTTPException(status_code=500, detail=str(exc)) from exc

        payload: Dict[str, Any] = {"scores": scores.tolist()}
        if debug_json:
            payload["debug"] = json.loads(debug_json)
        return payload

    def encode_response(self, payload):
        return payload


def _attach_v2_routes(
    app: FastAPI,
    *,
    runtime_getter,
    model_name: str,
    debug_include_tf_inputs: bool,
) -> None:
    def _runtime():
        runtime = runtime_getter()
        if runtime is None:
            raise HTTPException(status_code=503, detail="Worker backend is not ready")
        return runtime

    @app.get("/v2")
    def _v2_root():
        return {"name": "hotvect-worker-server", "version": "2.0", "extensions": []}

    @app.get("/v2/health/live")
    def _v2_live():
        return {"live": True}

    @app.get("/v2/health/ready")
    def _v2_ready():
        _runtime()
        return {"ready": True}

    @app.get("/v2/models/{requested_model_name}")
    def _v2_model_metadata(requested_model_name: str):
        if requested_model_name != model_name:
            return JSONResponse(
                status_code=404,
                content={"error": {"message": f"Unknown model: {requested_model_name}"}},
            )
        return _runtime().model_metadata(model_name)

    @app.get("/v2/models/{requested_model_name}/ready")
    def _v2_model_ready(requested_model_name: str):
        if requested_model_name != model_name:
            return JSONResponse(
                status_code=404,
                content={"error": {"message": f"Unknown model: {requested_model_name}"}},
            )
        _runtime()
        return {"name": model_name, "ready": True}

    @app.post("/v2/models/{requested_model_name}/infer")
    def _v2_infer(requested_model_name: str, request: Dict[str, Any]):
        if requested_model_name != model_name:
            return JSONResponse(
                status_code=404,
                content={"error": {"message": f"Unknown model: {requested_model_name}"}},
            )
        try:
            return _runtime().infer_v2(
                request,
                model_name=model_name,
                debug_include_tf_inputs=debug_include_tf_inputs,
            )
        except ValueError as exc:
            return JSONResponse(status_code=400, content={"error": {"message": str(exc)}})
        except HTTPException:
            raise
        except Exception as exc:
            log.exception("Error processing V2 inference request")
            return JSONResponse(
                status_code=500,
                content={"error": {"message": str(exc), "stacktrace": traceback.format_exc()}},
            )


def main(argv: Optional[list[str]] = None, *, default_backend: Optional[str] = None) -> None:
    parser = build_arg_parser(default_backend=default_backend)
    args = parser.parse_args(argv)

    if args.backend != "tensorflow" and args.debug_include_tf_inputs:
        raise ValueError("--debug-include-tf-inputs is only supported for tensorflow worker backends")

    api = HotvectWorkerLitAPI(args=args)
    log.info("LitServe worker backend loading (backend=%s, model=%s)", args.backend, args.model_name)
    print(f"Worker server listening on http://{args.host}:{args.port}/predict")
    print("Compatibility endpoints:")
    print("  GET  /health")
    print("  GET  /v2")
    print("  GET  /v2/health/live")
    print("  GET  /v2/health/ready")
    print("  GET  /v2/models/<model>")
    print("  GET  /v2/models/<model>/ready")
    print("  POST /v2/models/<model>/infer")
    run_server(
        api,
        host=args.host,
        port=args.port,
        workers=int(args.workers_per_device),
        device=args.accelerator,
        devices=_parse_devices_arg(args.devices),
        timeout=float(args.request_timeout_seconds),
        log_level="info",
        configure_app=lambda app: _attach_v2_routes(
            app,
            runtime_getter=lambda: api.model,
            model_name=args.model_name,
            debug_include_tf_inputs=bool(args.debug_include_tf_inputs),
        ),
    )


if __name__ == "__main__":
    main()
