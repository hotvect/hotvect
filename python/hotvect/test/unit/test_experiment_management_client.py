from __future__ import annotations

from types import SimpleNamespace

import pytest
import requests
from pydantic import ValidationError

from hotvect.experiment_management import Algorithm, AlgorithmParameter, ExperimentManagementClient


@pytest.mark.parametrize("last_test_time", [None, "2000-09-24T10:00:00Z"])
def test_client_posts_parameter_and_accepts_additive_ems_response_fields(monkeypatch, last_test_time):
    recorded = {}

    def fake_request(*, method, url, params=None, json=None, auth=None, timeout=None):
        recorded.update(method=method, url=url, params=params, json=json, auth=auth, timeout=timeout)
        return SimpleNamespace(
            status_code=200,
            text="payload",
            json=lambda: json
            | {
                "last_test_time": last_test_time,
                "future_parameter_field": {"value": 1},
                "algorithm": json["algorithm"] | {"future_algorithm_field": True},
            },
            raise_for_status=lambda: None,
        )

    monkeypatch.setattr("hotvect.experiment_management.client.requests.request", fake_request)

    auth = SimpleNamespace()
    client = ExperimentManagementClient(base_url="http://localhost:8080/", auth=auth)
    parameter = AlgorithmParameter(
        algorithm_parameter_id="p1",
        algorithm=Algorithm(
            algorithm_name="algo",
            algorithm_version="1.2.3",
            absolute_s3_jar_path="s3://example-bucket/algo.jar",
            algorithm_training_image_name="algo:1.2.3",
            parameter_mode="REQUIRED",
        ),
        evaluation_results="",
        absolute_s3_path="s3://example-bucket/key",
    )

    assert client.create_algorithm_parameter(parameter) == parameter
    assert recorded == {
        "method": "POST",
        "url": "http://localhost:8080/algorithm-parameters",
        "params": None,
        "json": parameter.model_dump(by_alias=True, exclude_none=True),
        "auth": auth,
        "timeout": (5.0, 15.0),
    }


@pytest.mark.parametrize("last_test_time", [None, "2000-09-24T10:00:00Z"])
def test_client_reads_parameters_with_additive_ems_response_fields(monkeypatch, last_test_time):
    payload = {
        "algorithm_parameter_id": "p1",
        "algorithm": {
            "algorithm_name": "algo",
            "algorithm_version": "1.2.3",
            "absolute_s3_jar_path": "s3://example-bucket/algo.jar",
            "algorithm_training_image_name": "algo:1.2.3",
            "parameter_mode": "REQUIRED",
            "future_algorithm_field": True,
        },
        "absolute_s3_path": "s3://example-bucket/p1.zip",
        "last_test_time": last_test_time,
        "future_parameter_field": {"value": 1},
    }

    def fake_request(**kwargs):
        assert kwargs["method"] == "GET"
        return SimpleNamespace(
            status_code=200,
            text="payload",
            json=lambda: [payload] if kwargs["url"].endswith("/algorithm-parameters") else payload,
            raise_for_status=lambda: None,
        )

    monkeypatch.setattr("hotvect.experiment_management.client.requests.request", fake_request)
    client = ExperimentManagementClient(base_url="http://localhost:8080", auth=SimpleNamespace())

    parameter = client.get_latest_algorithm_parameter("algo", "1.2.3")

    assert parameter.algorithm_parameter_id == "p1"
    assert parameter.algorithm.algorithm_name == "algo"
    assert client.get_algorithm_parameters() == [parameter]
    assert "last_test_time" not in parameter.model_dump()
    assert "future_algorithm_field" not in parameter.algorithm.model_dump()

    payload["absolute_s3_path"] = {"invalid": "type"}
    with pytest.raises(ValidationError, match="absolute_s3_path"):
        client.get_latest_algorithm_parameter("algo", "1.2.3")
    del payload["absolute_s3_path"]
    with pytest.raises(ValidationError, match="absolute_s3_path"):
        client.get_latest_algorithm_parameter("algo", "1.2.3")


def test_client_propagates_http_errors(monkeypatch):
    def fake_request(*, method, url, params=None, json=None, auth=None, timeout=None):
        error = requests.HTTPError("404 Client Error")
        return SimpleNamespace(
            status_code=404,
            text="",
            json=lambda: None,
            raise_for_status=lambda: (_ for _ in ()).throw(error),
        )

    monkeypatch.setattr("hotvect.experiment_management.client.requests.request", fake_request)

    client = ExperimentManagementClient(base_url="http://localhost:8080", auth=SimpleNamespace())

    try:
        client.get_algorithm("a", "b")
    except requests.HTTPError as error:
        assert "404" in str(error)
    else:
        raise AssertionError("expected HTTPError")


def test_client_accepts_no_content_for_parameterless_latest_parameter(monkeypatch):
    def fake_request(**kwargs):
        assert kwargs["method"] == "GET"
        assert kwargs["url"] == "https://ems.example/algorithms/policy/v1/latest-algorithm-parameter"
        response = requests.Response()
        response.status_code = 204
        response._content = b""
        return response

    monkeypatch.setattr("hotvect.experiment_management.client.requests.request", fake_request)
    client = ExperimentManagementClient(base_url="https://ems.example", auth=SimpleNamespace())

    assert client.get_latest_algorithm_parameter("policy", "v1") is None


def test_client_unwraps_algorithms_with_active_variants(monkeypatch):
    def fake_request(*, method, url, params=None, json=None, auth=None, timeout=None):
        assert method == "GET"
        assert url == "http://localhost:8080/algorithms/with-active-variants"
        return SimpleNamespace(
            status_code=200,
            text="payload",
            json=lambda: {
                "algorithms": [
                    {
                        "algorithmName": "ranking",
                        "algorithmVersion": "4.2.0",
                        "variants": [{"variantId": 17, "slotName": "example-slot"}],
                    }
                ]
            },
            raise_for_status=lambda: None,
        )

    monkeypatch.setattr("hotvect.experiment_management.client.requests.request", fake_request)
    client = ExperimentManagementClient(base_url="http://localhost:8080", auth=SimpleNamespace())

    algorithms = client.get_algorithms_with_active_variants()

    assert [(algorithm.algorithm_name, algorithm.algorithm_version) for algorithm in algorithms] == [
        ("ranking", "4.2.0")
    ]
