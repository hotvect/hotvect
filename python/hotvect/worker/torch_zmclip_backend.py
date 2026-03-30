from __future__ import annotations

import os
from pathlib import Path
from typing import Any, Dict, List

import numpy as np

from hotvect.worker.tensorflow_backend import load_schema
from hotvect.worker.v2_protocol import (
    TensorSpec,
    batch_size_from_inputs,
    build_model_metadata,
    build_output_tensor,
    parse_inputs_by_name,
    parse_request_id,
    parse_requested_output_names,
    validate_request_envelope,
)


class TorchZmClipWorkerBackend:
    def __init__(
        self,
        model_path: str,
        schema_path: str,
        *,
        model_name: str,
        use_torch_compile: bool,
        torch_num_threads: int,
        torch_num_interop_threads: int,
        warmup_iterations: int,
    ):
        self.model_name = model_name
        self._feature_specs = load_schema(schema_path)
        if set(self._feature_specs) != {"text"}:
            raise ValueError(
                "Torch zmCLIP worker expects schema with exactly one input feature named 'text'; "
                f"got {sorted(self._feature_specs)}"
            )
        feature_dtype, feature_shape = self._feature_specs["text"]
        if feature_dtype != "string" or feature_shape:
            raise ValueError(
                f"Torch zmCLIP worker expects text feature to be scalar string, got dtype={feature_dtype!r} shape={feature_shape!r}"
            )

        checkpoint_path = _resolve_checkpoint_path(model_path)
        self._model, self._tokenizer, self._torch_device = _load_zmclip(
            model_name,
            str(checkpoint_path),
            _resolve_device(),
            use_torch_compile=use_torch_compile,
            torch_num_threads=torch_num_threads,
            torch_num_interop_threads=torch_num_interop_threads,
        )
        for _ in range(max(0, int(warmup_iterations))):
            _encode_text_batch(self._model, self._tokenizer, self._torch_device, ["warmup"])

        sample = _encode_text_batch(self._model, self._tokenizer, self._torch_device, ["shape-probe"])
        if sample.ndim != 2 or sample.shape[0] != 1:
            raise ValueError(f"Expected zmCLIP encoder to return shape [1, dim], got {getattr(sample, 'shape', None)}")
        self.embedding_dim = int(sample.shape[1])
        self.input_specs = [TensorSpec(name="text", datatype="BYTES", shape=[-1])]
        self.output_specs = [TensorSpec(name="embedding", datatype="FP32", shape=[-1, self.embedding_dim])]
        self.platform = "hotvect.torch"

    def model_metadata(self, model_name: str) -> Dict[str, Any]:
        return build_model_metadata(
            name=model_name,
            platform=self.platform,
            inputs=self.input_specs,
            outputs=self.output_specs,
        )

    def infer_v2(
        self, request: Dict[str, Any], *, model_name: str, debug_include_tf_inputs: bool = False
    ) -> Dict[str, Any]:
        if debug_include_tf_inputs:
            raise ValueError("--debug-include-tf-inputs is only supported for tensorflow worker backends")

        request = dict(validate_request_envelope(request))
        request_id = parse_request_id(request)
        requested_outputs = parse_requested_output_names(request, [spec.name for spec in self.output_specs])
        inputs_by_name = parse_inputs_by_name(request, self.input_specs)
        batch_size = batch_size_from_inputs(inputs_by_name, self.input_specs)
        texts = inputs_by_name["text"]
        if batch_size == 0:
            embeddings = np.zeros((0, self.embedding_dim), dtype=np.float32)
        else:
            embeddings = _encode_text_batch(self._model, self._tokenizer, self._torch_device, texts)
        if embeddings.ndim != 2 or embeddings.shape[0] != batch_size or embeddings.shape[1] != self.embedding_dim:
            raise ValueError(
                "Torch zmCLIP backend returned unexpected embedding shape: "
                f"expected [{batch_size}, {self.embedding_dim}], got {getattr(embeddings, 'shape', None)}"
            )

        response: Dict[str, Any] = {
            "model_name": model_name,
            "outputs": [
                build_output_tensor(self.output_specs[0], embeddings.tolist())
                for _name in requested_outputs
                if _name == "embedding"
            ],
        }
        if request_id is not None:
            response["id"] = request_id
        return response


def _resolve_checkpoint_path(model_path: str) -> Path:
    root = Path(model_path)
    if root.is_file():
        return root

    candidates = sorted(path for path in root.rglob("*.pt") if path.is_file())
    if not candidates:
        raise ValueError(f"No checkpoint (.pt) found under model directory: {model_path}")

    preferred = [path for path in candidates if path.name == "zmclip_checkpoint.pt"]
    if len(preferred) == 1:
        return preferred[0]
    if len(candidates) == 1:
        return candidates[0]
    raise ValueError(
        "Ambiguous torch model directory: expected exactly one checkpoint or a unique zmclip_checkpoint.pt, "
        f"found {[path.name for path in candidates]}"
    )


def _resolve_device() -> str:
    cuda_visible_devices = os.environ.get("CUDA_VISIBLE_DEVICES")
    if cuda_visible_devices is None or cuda_visible_devices.strip() == "":
        return "cpu"
    return "cuda"


def _load_zmclip(
    model_name: str,
    checkpoint_path: str,
    device: str,
    *,
    use_torch_compile: bool,
    torch_num_threads: int,
    torch_num_interop_threads: int,
):
    import torch
    from open_clip import create_model_and_transforms, get_tokenizer

    if torch_num_threads > 0:
        torch.set_num_threads(int(torch_num_threads))
    if torch_num_interop_threads > 0:
        torch.set_num_interop_threads(int(torch_num_interop_threads))

    model, _, _ = create_model_and_transforms(model_name, pretrained=None, force_image_size=(320, 224))

    checkpoint = torch.load(checkpoint_path, map_location="cpu", weights_only=False)
    state_dict = checkpoint["state_dict"] if isinstance(checkpoint, dict) and "state_dict" in checkpoint else checkpoint
    if hasattr(state_dict, "items"):
        first_key = next(iter(state_dict.items()))[0]
        if isinstance(first_key, str) and first_key.startswith("module"):
            state_dict = {key[len("module.") :]: value for key, value in state_dict.items()}
        state_dict.pop("text.transformer.embeddings.position_ids", None)
    model.load_state_dict(state_dict)

    model.visual = None
    model.eval()

    torch_device = torch.device(device if device == "cpu" else "cuda")
    model.to(torch_device)

    if use_torch_compile:
        model = torch.compile(model, mode="default")

    tokenizer = get_tokenizer(model_name)
    return model, tokenizer, torch_device


def _encode_text_batch(model: Any, tokenizer: Any, torch_device: Any, texts: List[str]) -> np.ndarray:
    import torch

    tokens = tokenizer(texts).to(device=torch_device)
    with torch.inference_mode():
        embeddings = model.encode_text(tokens, normalize=True)
    return embeddings.detach().cpu().numpy().astype(np.float32, copy=False)
