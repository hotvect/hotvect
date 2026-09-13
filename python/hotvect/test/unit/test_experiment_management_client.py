from __future__ import annotations

from types import SimpleNamespace

import requests

from hotvect.experiment_management import Algorithm, AlgorithmParameter, ExperimentManagementClient


def test_client_posts_generated_request_model(monkeypatch):
    recorded = {}

    def fake_request(*, method, url, params=None, json=None, auth=None, timeout=None):
        recorded.update(method=method, url=url, params=params, json=json, auth=auth, timeout=timeout)
        return SimpleNamespace(
            status_code=200,
            text="payload",
            json=lambda: json,
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
            absolute_s3_jar_path="s3://bucket/algo.jar",
            algorithm_training_image_name="algo:1.2.3",
            parameter_mode="REQUIRED",
        ),
        evaluation_results="",
        absolute_s3_path="s3://bucket/key",
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
