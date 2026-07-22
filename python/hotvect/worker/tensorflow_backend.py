from __future__ import annotations

import json
import os
from typing import Any

import numpy as np

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

_ALLOWED_DTYPES = {"int64", "float32", "string"}


def load_schema(schema_path: str) -> dict[str, tuple[str, list[int]]]:
    with open(schema_path, encoding="utf-8") as f:
        data = json.load(f)
    if "features" not in data or not isinstance(data["features"], list):
        raise ValueError(f"Invalid schema file (missing 'features' list): {schema_path}")

    feature_specs: dict[str, tuple[str, list[int]]] = {}
    for feat in data["features"]:
        name = feat.get("name")
        dtype = feat.get("dtype")
        shape = feat.get("shape")
        if not isinstance(name, str) or not name:
            raise ValueError(f"Invalid schema feature name: {feat}")
        if dtype not in _ALLOWED_DTYPES:
            raise ValueError(f"Invalid schema feature dtype for {name}: {dtype} (allowed={sorted(_ALLOWED_DTYPES)})")
        if not isinstance(shape, list):
            raise ValueError(f"Invalid schema feature shape for {name}: {shape!r}")
        if len(shape) not in (0, 1):
            raise ValueError(f"Only scalar and 1D sequence features are supported (feature={name}, shape={shape})")
        for dim in shape:
            if not isinstance(dim, int):
                raise ValueError(f"Invalid schema feature shape dim for {name}: {shape!r}")
            if dim <= 0:
                raise ValueError(f"Sequence length must be > 0 (feature={name}, shape={shape})")
        feature_specs[name] = (dtype, shape)

    if not feature_specs:
        raise ValueError(f"Schema has no features: {schema_path}")

    return feature_specs


def feature_defs_from_schema(feature_specs: dict[str, tuple[str, list[int]]]) -> list[tuple[str, str, list[int]]]:
    ordered_feature_names = sorted(feature_specs.keys())
    return [(name, feature_specs[name][0], feature_specs[name][1]) for name in ordered_feature_names]


def load_model(tf: Any, model_path: str):
    saved_model = tf.saved_model.load(model_path)
    if hasattr(saved_model, "signatures") and "serving_default" in saved_model.signatures:
        return saved_model, saved_model.signatures["serving_default"]
    return saved_model, saved_model


def get_tf_and_device() -> tuple[Any, str]:
    cuda_visible_devices = os.environ.get("CUDA_VISIBLE_DEVICES")
    import tensorflow as tf  # type: ignore

    gpus = tf.config.list_physical_devices("GPU")
    if not cuda_visible_devices:
        tf.config.set_visible_devices([], "GPU")
        if tf.config.list_physical_devices("GPU"):
            raise RuntimeError("Failed to hide GPUs from TensorFlow in CPU mode.")
        return tf, "/CPU:0"

    if gpus:
        return tf, "/GPU:0"

    raise RuntimeError(
        "CUDA_VISIBLE_DEVICES is set, but TensorFlow cannot see any GPU devices. "
        f"CUDA_VISIBLE_DEVICES={cuda_visible_devices!r}"
    )


def tf_dtype(tf: Any, dtype_name: str):
    if dtype_name == "int64":
        return tf.int64
    if dtype_name == "float32":
        return tf.float32
    if dtype_name == "string":
        return tf.string
    raise ValueError(f"Unsupported TensorFlow dtype name: {dtype_name!r}")


def parse_tensor(tf: Any, dtype: str, shape: list[int], tensor_proto: bytes):
    tensor = tf.io.parse_tensor(tensor_proto, out_type=tf_dtype(tf, dtype))
    if shape:
        tensor = tf.reshape(tensor, [-1, shape[0]])
    else:
        tensor = tf.reshape(tensor, [-1])
    return tensor


def numpy_value_to_python(value):
    if isinstance(value, np.generic):
        value = value.item()
    if isinstance(value, (bytes, bytearray)):
        return value.decode("utf-8", errors="replace")
    return value


def tensors_to_rows(tensors: dict[str, Any]) -> list[dict[str, Any]]:
    if not tensors:
        return []
    any_tensor = next(iter(tensors.values()))
    batch_size = int(any_tensor.shape[0])
    rows: list[dict[str, Any]] = [dict() for _ in range(batch_size)]

    for name, tensor in tensors.items():
        arr = tensor.numpy()
        if arr.ndim == 1:
            for i in range(batch_size):
                rows[i][name] = numpy_value_to_python(arr[i])
            continue
        if arr.ndim == 2:
            for i in range(batch_size):
                rows[i][name] = [numpy_value_to_python(x) for x in arr[i].tolist()]
            continue
        raise ValueError(f"Unsupported tensor rank for debug rows: {arr.ndim} for feature={name}")

    return rows


def _normalize_model_output(tf: Any, outputs: Any):
    if isinstance(outputs, dict):
        if "output_0" in outputs:
            outputs = outputs["output_0"]
        else:
            outputs = next(iter(outputs.values()))
    return tf.reshape(outputs, [-1])


def build_predict_fn(tf: Any, model: Any, feature_defs: list[tuple[str, str, list[int]]]):
    ordered_names = [name for (name, _dtype, _shape) in feature_defs]
    ordered_dtypes = [tf_dtype(tf, dtype_name) for (_name, dtype_name, _shape) in feature_defs]
    ordered_shapes = [shape for (_name, _dtype, shape) in feature_defs]

    @tf.function
    def _predict_fast(serialized_tensor_protos, batch_size):
        inputs = {}
        checked_batch_size = False
        for i in range(len(ordered_names)):
            tensor = tf.io.parse_tensor(serialized_tensor_protos[i], out_type=ordered_dtypes[i])
            if not ordered_shapes[i]:
                tensor = tf.ensure_shape(tensor, [None])
            else:
                tensor = tf.ensure_shape(tensor, [None, int(ordered_shapes[i][0])])
            if not checked_batch_size:
                tf.debugging.assert_equal(tf.shape(tensor)[0], batch_size, message="batch_size mismatch")
                checked_batch_size = True
            inputs[ordered_names[i]] = tensor
        outputs = model(**inputs) if callable(model) else model(inputs)
        return _normalize_model_output(tf, outputs)

    def _predict_debug(batch_size: int, tensor_protos: list[bytes]):
        if len(tensor_protos) != len(feature_defs):
            raise ValueError(f"feature tensor count mismatch: expected={len(feature_defs)} actual={len(tensor_protos)}")

        tensors: dict[str, Any] = {}
        for i, (name, dtype, shape) in enumerate(feature_defs):
            tensors[name] = parse_tensor(tf, dtype, shape, tensor_protos[i])

        outputs = model(**tensors) if callable(model) else model(tensors)
        scores = _normalize_model_output(tf, outputs).numpy().astype(np.float32, copy=False)
        if scores.shape[0] != batch_size:
            raise ValueError(f"score count mismatch: expected={batch_size} actual={scores.shape[0]}")
        return scores, tensors

    return _predict_fast, _predict_debug


def _coerce_scalar(name: str, dtype: str, value: Any):
    if dtype == "string":
        if isinstance(value, (bytes, bytearray)):
            return value.decode("utf-8", errors="replace")
        if isinstance(value, str):
            return value
        raise ValueError(f"feature={name} expects string, got {type(value).__name__}")
    if dtype == "int64":
        if isinstance(value, bool):
            return int(value)
        if isinstance(value, int) and not isinstance(value, bool):
            return value
        raise ValueError(f"feature={name} expects int64, got {type(value).__name__}")
    if dtype == "float32":
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            return float(value)
        raise ValueError(f"feature={name} expects float32, got {type(value).__name__}")
    raise ValueError(f"Unsupported dtype for feature={name}: {dtype}")


def _coerce_sequence(name: str, dtype: str, shape: list[int], value: Any):
    if not isinstance(value, (list, tuple)):
        raise ValueError(f"feature={name} expects a sequence of length {shape[0]}, got {type(value).__name__}")
    if len(value) != shape[0]:
        raise ValueError(f"feature={name} expects length {shape[0]}, got {len(value)}")
    return [_coerce_scalar(name, dtype, element) for element in value]


def rows_to_tensors(
    tf: Any, feature_defs: list[tuple[str, str, list[int]]], batch_rows: list[dict[str, Any]]
) -> dict[str, Any]:
    if not isinstance(batch_rows, list):
        raise ValueError(f"batch must be a list, got {type(batch_rows).__name__}")
    for i, row in enumerate(batch_rows):
        if not isinstance(row, dict):
            raise ValueError(f"batch[{i}] must be an object, got {type(row).__name__}")

    tensors: dict[str, Any] = {}
    batch_size = len(batch_rows)
    for name, dtype, shape in feature_defs:
        if batch_size == 0:
            tensor = tf.constant([], dtype=tf_dtype(tf, dtype))
            tensors[name] = tf.reshape(tensor, [0, shape[0]] if shape else [0])
            continue

        values = []
        for row_idx, row in enumerate(batch_rows):
            if name not in row:
                raise ValueError(f"Missing feature '{name}' in batch[{row_idx}]")
            raw_value = row[name]
            if shape:
                values.append(_coerce_sequence(name, dtype, shape, raw_value))
            else:
                values.append(_coerce_scalar(name, dtype, raw_value))

        tensor = tf.constant(values, dtype=tf_dtype(tf, dtype))
        if shape:
            tensor = tf.reshape(tensor, [batch_size, shape[0]])
        else:
            tensor = tf.reshape(tensor, [batch_size])
        tensors[name] = tensor

    return tensors


class TensorFlowWorkerBackend:
    def __init__(self, model_path: str, schema_path: str):
        self.feature_specs = load_schema(schema_path)
        self.feature_defs = feature_defs_from_schema(self.feature_specs)
        self.tf, self.tf_device = get_tf_and_device()
        with self.tf.device(self.tf_device):
            self.saved_model, self.model = load_model(self.tf, model_path)
        self.predict_fn = build_predict_fn(self.tf, self.model, self.feature_defs)
        self.input_specs = [
            TensorSpec(
                name=name,
                datatype=_schema_dtype_to_v2(dtype_name),
                shape=([-1, int(shape[0])] if shape else [-1]),
            )
            for (name, dtype_name, shape) in self.feature_defs
        ]
        self.output_specs = [TensorSpec(name="score", datatype="FP32", shape=[-1])]
        self.platform = "hotvect.tensorflow"

    def predict_serialized(
        self,
        batch_size: int,
        tensor_protos: list[bytes],
        *,
        debug_include_tf_inputs: bool,
        request_id: str,
        span_prefix: str,
        otel_tracer=None,
    ):
        predict_fast_fn, predict_debug_fn = self.predict_fn

        debug_json = None
        if debug_include_tf_inputs:
            if otel_tracer is None:
                scores, tensors = predict_debug_fn(batch_size, tensor_protos)
            else:
                with otel_tracer.start_as_current_span(f"{span_prefix}.python_worker.model_call") as span:
                    span.set_attribute("batch.size", int(batch_size))
                    scores, tensors = predict_debug_fn(batch_size, tensor_protos)
            debug_payload = {"request_id": request_id, "batch": tensors_to_rows(tensors)}
            debug_json = json.dumps(debug_payload, ensure_ascii=False)
            return scores, debug_json

        if batch_size == 0:
            return np.zeros((0,), dtype=np.float32), None

        serialized = self.tf.constant(tensor_protos, dtype=self.tf.string)
        batch_size_t = self.tf.constant(int(batch_size), dtype=self.tf.int32)
        if otel_tracer is None:
            out = predict_fast_fn(serialized, batch_size_t)
        else:
            with otel_tracer.start_as_current_span(f"{span_prefix}.python_worker.model_call") as span:
                span.set_attribute("batch.size", int(batch_size))
                out = predict_fast_fn(serialized, batch_size_t)

        scores = out.numpy().reshape((-1,)).astype(np.float32, copy=False)
        if scores.shape[0] != batch_size:
            raise ValueError(f"score count mismatch: expected={batch_size} actual={scores.shape[0]}")
        return scores, None

    def predict_rows(self, batch_rows: list[dict[str, Any]], *, debug_include_tf_inputs: bool, request_id: str):
        tensors = rows_to_tensors(self.tf, self.feature_defs, batch_rows)
        outputs = self.model(**tensors) if callable(self.model) else self.model(tensors)
        scores = _normalize_model_output(self.tf, outputs).numpy().astype(np.float32, copy=False)
        expected = len(batch_rows)
        if scores.shape[0] != expected:
            raise ValueError(f"score count mismatch: expected={expected} actual={scores.shape[0]}")

        debug_json = None
        if debug_include_tf_inputs:
            debug_payload = {"request_id": request_id, "batch": tensors_to_rows(tensors)}
            debug_json = json.dumps(debug_payload, ensure_ascii=False)
        return scores, debug_json

    def model_metadata(self, model_name: str) -> dict[str, Any]:
        return build_model_metadata(
            name=model_name,
            platform=self.platform,
            inputs=self.input_specs,
            outputs=self.output_specs,
        )

    def infer_v2(
        self, request: dict[str, Any], *, model_name: str, debug_include_tf_inputs: bool = False
    ) -> dict[str, Any]:
        request = dict(validate_request_envelope(request))
        request_id = parse_request_id(request)
        requested_outputs = parse_requested_output_names(request, [spec.name for spec in self.output_specs])
        inputs_by_name = parse_inputs_by_name(request, self.input_specs)
        batch_size = batch_size_from_inputs(inputs_by_name, self.input_specs)

        batch_rows = []
        for batch_index in range(batch_size):
            row: dict[str, Any] = {}
            for spec in self.input_specs:
                value = inputs_by_name[spec.name][batch_index]
                row[spec.name] = value
            batch_rows.append(row)

        scores, debug_json = self.predict_rows(
            batch_rows,
            debug_include_tf_inputs=debug_include_tf_inputs,
            request_id=request_id or "http",
        )
        response: dict[str, Any] = {
            "model_name": model_name,
            "outputs": [
                build_output_tensor(self.output_specs[0], scores.tolist())
                for output_name in requested_outputs
                if output_name == "score"
            ],
        }
        if request_id is not None:
            response["id"] = request_id
        if debug_json:
            response["debug"] = json.loads(debug_json)
        return response


def _schema_dtype_to_v2(dtype_name: str) -> str:
    if dtype_name == "int64":
        return "INT64"
    if dtype_name == "float32":
        return "FP32"
    if dtype_name == "string":
        return "BYTES"
    raise ValueError(f"Unsupported schema dtype for V2 mapping: {dtype_name!r}")
