from __future__ import annotations

import json
from pathlib import Path

import pytest
import requests
from pydantic import ValidationError

from hotvect.experiment_management import (
    Algorithm,
    AlgorithmName,
    AlgorithmParameterSpec,
    AlgorithmSpec,
    AlgorithmWithLatestParameter,
    ExperimentManagementClient,
    ExperimentManagementConnection,
    SlotActiveInfoAlgorithmResponse,
)

FIXTURES = Path(__file__).parent / "fixtures" / "ems_registration"


def _fixture(name):
    return json.loads((FIXTURES / f"{name}.json").read_text())


@pytest.fixture
def ems_http(monkeypatch):
    responses = []
    requests_sent = []

    def fake_send(self, request, **kwargs):
        requests_sent.append(request)
        status, payload = responses.pop(0)
        response = requests.Response()
        response.status_code = status
        response._content = json.dumps(payload).encode() if payload is not None else b""
        response.request = request
        return response

    monkeypatch.setattr(requests.Session, "send", fake_send)
    client = ExperimentManagementClient(
        ExperimentManagementConnection(
            environment="https://ems.example",
            bearer_auth=requests.auth.HTTPBasicAuth("test", "test"),
        )
    )
    return client, responses, requests_sent


@pytest.mark.parametrize("parameter_state", ["without-parameter", "with-parameter"])
def test_ems_get_algorithm_preserves_mode_and_nullable_summary(ems_http, parameter_state):
    client, responses, requests_sent = ems_http
    payload = _fixture(f"algorithm-get-{parameter_state}")
    responses.append((200, payload))

    algorithm = client.get_algorithm("jar_name", "v2")

    assert algorithm.model_dump(mode="json") == payload
    assert [(request.method, request.url) for request in requests_sent] == [
        ("GET", "https://ems.example/algorithms/jar_name/v2")
    ]


def test_ems_parameterless_algorithm_has_no_latest_parameter(ems_http):
    client, responses, _ = ems_http
    payload = _fixture("algorithm-get-without-parameter") | {"parameter_mode": "NONE"}
    responses.extend([(200, payload), (204, None)])

    algorithm = client.get_algorithm("jar_name", "v2")

    assert algorithm.parameter_mode == "NONE"
    assert algorithm.latest_algorithm_parameter is None
    assert client.get_latest_algorithm_parameter("jar_name", "v2") is None


@pytest.mark.parametrize("parameter_mode", ["REQUIRED", "NONE"])
def test_ems_create_algorithm_posts_explicit_mode(ems_http, parameter_mode):
    client, responses, requests_sent = ems_http
    responses.append((201, _fixture("algorithm-get-without-parameter") | {"parameter_mode": parameter_mode}))
    payload = {
        "algorithm_name": "jar_name",
        "algorithm_version": "v2",
        "absolute_s3_jar_path": "s3://bucket/dir/jar_name-v2.jar",
        "algorithm_training_image_name": "jar_name:v2",
        "parameter_mode": parameter_mode,
    }

    client.create_algorithm(AlgorithmSpec.model_validate(payload))

    assert requests_sent[0].url == "https://ems.example/algorithms"
    assert json.loads(requests_sent[0].body) == payload


def test_ems_create_algorithm_omits_legacy_default_mode(ems_http):
    client, responses, requests_sent = ems_http
    responses.append((201, _fixture("algorithm-get-without-parameter")))
    payload = {
        "algorithm_name": "jar_name",
        "algorithm_version": "v2",
        "absolute_s3_jar_path": "s3://bucket/dir/jar_name-v2.jar",
        "algorithm_training_image_name": "jar_name:v2",
    }

    client.create_algorithm(AlgorithmSpec.model_validate(payload))

    assert json.loads(requests_sent[0].body) == payload


@pytest.mark.parametrize("model", [Algorithm, AlgorithmWithLatestParameter])
@pytest.mark.parametrize("mode", [None, "OPTIONAL"])
def test_algorithm_models_require_a_valid_explicit_mode(model, mode):
    payload = _fixture("algorithm-get-without-parameter")
    if mode is None:
        del payload["parameter_mode"]
    else:
        payload["parameter_mode"] = mode

    with pytest.raises(ValidationError, match="parameter_mode"):
        model.model_validate(payload)


def test_algorithm_spec_allows_legacy_default_mode_omission():
    payload = _fixture("algorithm-get-without-parameter")
    del payload["parameter_mode"]

    algorithm = AlgorithmSpec.model_validate(payload)

    assert algorithm.parameter_mode is None


def test_parameterless_slot_algorithm_accepts_omitted_parameter_fields():
    algorithm = SlotActiveInfoAlgorithmResponse.model_validate(
        {
            "algorithm_name": "parameterless",
            "algorithm_version": "1.0.0",
            "absolute_s3_algorithm_jar_path": "s3://bucket/parameterless.jar",
        }
    )

    assert algorithm.latest_algorithm_parameter is None
    assert algorithm.absolute_s3_algorithm_parameter_path is None


def test_ems_active_algorithms_with_variants_uses_released_wrapper(ems_http):
    client, responses, _ = ems_http
    responses.append(
        (
            200,
            {
                "algorithms": [
                    {
                        "algorithm_name": "parameterless",
                        "algorithm_version": "1.0.0",
                        "state": "ACTIVE",
                        "absolute_s3_jar_path": "s3://bucket/parameterless.jar",
                        "created_at": "2000-04-11T10:15:30Z",
                        "algorithm_training_image_name": "parameterless:1.0.0",
                        "parameter_mode": "NONE",
                        "latest_algorithm_parameter": None,
                        "variants": [{"variant_id": 7, "slot_name": "catalog"}],
                    }
                ]
            },
        )
    )

    algorithms = client.get_algorithms_with_active_variants()

    assert algorithms is not None
    assert algorithms[0].algorithm_name == "parameterless"
    assert algorithms[0].parameter_mode == "NONE"
    assert algorithms[0].variants[0].variant_id == 7
    assert algorithms[0].variants[0].slot_name == "catalog"


def test_ems_active_algorithms_with_variants_requires_the_response_envelope(ems_http):
    client, responses, _ = ems_http
    responses.append((200, {}))

    with pytest.raises(ValidationError, match="algorithms"):
        client.get_algorithms_with_active_variants()


def test_ems_create_algorithm_parameter_returns_saved_record(ems_http):
    client, responses, requests_sent = ems_http
    saved_parameter = _fixture("algorithm-parameters-create")
    responses.append((201, saved_parameter))
    parameter = AlgorithmParameterSpec(
        algorithm_parameter_id="trained-parameter",
        algorithm=AlgorithmName(algorithm_name="jar_name", algorithm_version="v2"),
        evaluation_results="",
        absolute_s3_path="s3://bucket/trained-parameter.parameters.zip",
    )

    result = client.create_algorithm_parameter(parameter)

    assert result.model_dump(mode="json") == saved_parameter
    assert [(request.method, request.url) for request in requests_sent] == [
        ("POST", "https://ems.example/algorithm-parameters")
    ]
    assert json.loads(requests_sent[0].body) == parameter.model_dump(mode="json")


def test_ems_registration_rejects_an_empty_saved_response(ems_http):
    client, responses, _ = ems_http
    responses.append((201, None))
    parameter = AlgorithmParameterSpec(
        algorithm_parameter_id="trained-parameter",
        algorithm=AlgorithmName(algorithm_name="jar_name", algorithm_version="v2"),
        evaluation_results="",
        absolute_s3_path="s3://bucket/trained-parameter.parameters.zip",
    )

    with pytest.raises(ValidationError):
        client.create_algorithm_parameter(parameter)
