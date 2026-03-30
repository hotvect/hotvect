import pytest

from hotvect.worker.v2_protocol import TensorSpec, build_output_tensor, parse_inputs_by_name


def test_parse_inputs_by_name_accepts_nested_tensor_data():
    request = {
        "inputs": [
            {
                "name": "embedding",
                "datatype": "FP32",
                "shape": [2, 3],
                "data": [[1, 2, 3], [4.5, 5, 6]],
            }
        ]
    }

    parsed = parse_inputs_by_name(
        request,
        [TensorSpec(name="embedding", datatype="FP32", shape=[-1, 3])],
    )

    assert parsed == {"embedding": [[1.0, 2.0, 3.0], [4.5, 5.0, 6.0]]}


def test_parse_inputs_by_name_rejects_shape_mismatch():
    request = {
        "inputs": [
            {
                "name": "text",
                "datatype": "BYTES",
                "shape": [2],
                "data": ["a", "b"],
            }
        ]
    }

    with pytest.raises(ValueError, match="shape mismatch at dim 0"):
        parse_inputs_by_name(
            request,
            [TensorSpec(name="text", datatype="BYTES", shape=[3])],
        )


def test_build_output_tensor_inferrs_shape():
    tensor = build_output_tensor(
        TensorSpec(name="score", datatype="FP32", shape=[-1]),
        [0.1, 0.2, 0.3],
    )

    assert tensor == {
        "name": "score",
        "datatype": "FP32",
        "shape": [3],
        "data": [0.1, 0.2, 0.3],
    }
