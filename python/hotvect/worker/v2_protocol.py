from __future__ import annotations

from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class TensorSpec:
    name: str
    datatype: str
    shape: list[int]

    def metadata(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "datatype": self.datatype,
            "shape": list(self.shape),
        }


def parse_request_id(request: Mapping[str, Any]) -> str | None:
    request_id = request.get("id")
    if request_id is None:
        return None
    if not isinstance(request_id, str):
        raise ValueError("request.id must be a string when provided")
    return request_id


def validate_request_envelope(request: Any) -> Mapping[str, Any]:
    if not isinstance(request, Mapping):
        raise ValueError("Request body must be a JSON object")
    return request


def parse_requested_output_names(request: Mapping[str, Any], allowed_names: Iterable[str]) -> list[str]:
    allowed = list(allowed_names)
    allowed_set = set(allowed)

    raw_outputs = request.get("outputs")
    if raw_outputs is None:
        return allowed
    if not isinstance(raw_outputs, list):
        raise ValueError("request.outputs must be a list when provided")
    if not raw_outputs:
        return allowed

    requested: list[str] = []
    seen = set()
    for index, item in enumerate(raw_outputs):
        if not isinstance(item, Mapping):
            raise ValueError(f"request.outputs[{index}] must be an object")
        name = item.get("name")
        if not isinstance(name, str) or not name:
            raise ValueError(f"request.outputs[{index}].name must be a non-empty string")
        if name not in allowed_set:
            raise ValueError(f"Unknown requested output: {name!r}. Allowed outputs: {sorted(allowed_set)}")
        if name in seen:
            raise ValueError(f"Duplicate requested output: {name!r}")
        seen.add(name)
        requested.append(name)
    return requested


def parse_inputs_by_name(request: Mapping[str, Any], expected_specs: Sequence[TensorSpec]) -> dict[str, Any]:
    raw_inputs = request.get("inputs")
    if not isinstance(raw_inputs, list):
        raise ValueError("request.inputs must be a list")

    expected_by_name = {spec.name: spec for spec in expected_specs}
    inputs_by_name: dict[str, Any] = {}
    seen = set()

    for index, raw_input in enumerate(raw_inputs):
        if not isinstance(raw_input, Mapping):
            raise ValueError(f"request.inputs[{index}] must be an object")

        name = raw_input.get("name")
        if not isinstance(name, str) or not name:
            raise ValueError(f"request.inputs[{index}].name must be a non-empty string")
        if name in seen:
            raise ValueError(f"Duplicate input tensor: {name!r}")
        seen.add(name)

        spec = expected_by_name.get(name)
        if spec is None:
            raise ValueError(f"Unknown input tensor: {name!r}. Expected: {sorted(expected_by_name)}")

        datatype = raw_input.get("datatype")
        if datatype != spec.datatype:
            raise ValueError(
                f"request.inputs[{index}].datatype for {name!r} must be {spec.datatype!r}, got {datatype!r}"
            )

        shape = raw_input.get("shape")
        if not isinstance(shape, list) or not all(isinstance(dim, int) for dim in shape):
            raise ValueError(f"request.inputs[{index}].shape for {name!r} must be a list of integers")
        _validate_shape_against_spec(name, shape, spec)

        if "data" not in raw_input:
            raise ValueError(f"request.inputs[{index}].data for {name!r} is required")
        inputs_by_name[name] = _coerce_tensor_data(
            raw_input["data"],
            shape=shape,
            datatype=spec.datatype,
            path=f"request.inputs[{index}].data",
        )

    missing = [spec.name for spec in expected_specs if spec.name not in inputs_by_name]
    if missing:
        raise ValueError(f"Missing input tensors: {missing}")

    return inputs_by_name


def batch_size_from_inputs(inputs_by_name: Mapping[str, Any], expected_specs: Sequence[TensorSpec]) -> int:
    if not expected_specs:
        return 0
    first_spec = expected_specs[0]
    first_value = inputs_by_name[first_spec.name]
    if not isinstance(first_value, list):
        raise ValueError(f"Input tensor {first_spec.name!r} must be batched")
    batch_size = len(first_value)
    for spec in expected_specs[1:]:
        value = inputs_by_name[spec.name]
        if not isinstance(value, list):
            raise ValueError(f"Input tensor {spec.name!r} must be batched")
        if len(value) != batch_size:
            raise ValueError(
                f"All input tensors must share batch size; {spec.name!r} has {len(value)} but expected {batch_size}"
            )
    return batch_size


def build_output_tensor(spec: TensorSpec, data: Any) -> dict[str, Any]:
    plain_data = _to_plain_python(data)
    shape = _infer_shape(plain_data)
    _validate_shape_against_spec(spec.name, shape, spec)
    return {
        "name": spec.name,
        "datatype": spec.datatype,
        "shape": shape,
        "data": plain_data,
    }


def build_model_metadata(
    *, name: str, platform: str, inputs: Sequence[TensorSpec], outputs: Sequence[TensorSpec]
) -> dict[str, Any]:
    return {
        "name": name,
        "versions": ["1"],
        "platform": platform,
        "inputs": [spec.metadata() for spec in inputs],
        "outputs": [spec.metadata() for spec in outputs],
    }


def _validate_shape_against_spec(name: str, shape: Sequence[int], spec: TensorSpec) -> None:
    if len(shape) != len(spec.shape):
        raise ValueError(
            f"Tensor {name!r} has rank {len(shape)}, expected rank {len(spec.shape)} from shape {spec.shape}"
        )
    for index, (actual_dim, expected_dim) in enumerate(zip(shape, spec.shape)):
        if actual_dim < 0:
            raise ValueError(f"Tensor {name!r} shape dim {index} must be >= 0, got {actual_dim}")
        if expected_dim != -1 and actual_dim != expected_dim:
            raise ValueError(
                f"Tensor {name!r} shape mismatch at dim {index}: expected {expected_dim}, got {actual_dim}"
            )


def _coerce_tensor_data(data: Any, *, shape: Sequence[int], datatype: str, path: str) -> Any:
    if not shape:
        return _coerce_scalar(data, datatype=datatype, path=path)

    if not isinstance(data, list):
        raise ValueError(f"{path} must be a list for tensor shape {list(shape)}")
    expected_len = int(shape[0])
    if len(data) != expected_len:
        raise ValueError(f"{path} length must be {expected_len}, got {len(data)}")
    if expected_len == 0:
        return []

    child_shape = shape[1:]
    return [
        _coerce_tensor_data(item, shape=child_shape, datatype=datatype, path=f"{path}[{index}]")
        for index, item in enumerate(data)
    ]


def _coerce_scalar(value: Any, *, datatype: str, path: str) -> Any:
    if datatype == "BYTES":
        if not isinstance(value, str):
            raise ValueError(f"{path} must be a string for BYTES tensors, got {type(value).__name__}")
        return value
    if datatype == "FP32":
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ValueError(f"{path} must be a number for FP32 tensors, got {type(value).__name__}")
        return float(value)
    if datatype == "INT64":
        if isinstance(value, bool) or not isinstance(value, int):
            raise ValueError(f"{path} must be an integer for INT64 tensors, got {type(value).__name__}")
        return int(value)
    raise ValueError(f"Unsupported tensor datatype: {datatype!r}")


def _to_plain_python(value: Any) -> Any:
    if hasattr(value, "tolist"):
        return _to_plain_python(value.tolist())
    if isinstance(value, list):
        return [_to_plain_python(item) for item in value]
    if isinstance(value, tuple):
        return [_to_plain_python(item) for item in value]
    if isinstance(value, (bytes, bytearray)):
        return value.decode("utf-8", errors="replace")
    if isinstance(value, bool):
        return value
    if isinstance(value, int):
        return int(value)
    if isinstance(value, float):
        return float(value)
    return value


def _infer_shape(data: Any) -> list[int]:
    if isinstance(data, list):
        if not data:
            return [0]
        first_shape = _infer_shape(data[0])
        for item in data[1:]:
            item_shape = _infer_shape(item)
            if item_shape != first_shape:
                raise ValueError(f"Tensor output data must be rectangular; found shapes {first_shape} and {item_shape}")
        return [len(data), *first_shape]
    return []
