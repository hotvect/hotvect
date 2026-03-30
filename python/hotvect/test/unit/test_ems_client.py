from __future__ import annotations

from types import SimpleNamespace

from hotvect.experiment_management import (
    AlgorithmName,
    AlgorithmParameterSpec,
    Environment,
    ExperimentManagementClient,
    ExperimentManagementConnection,
)


def test_ems_create_algorithm_parameter_posts(monkeypatch):
    recorded = {}

    def fake_request(*, method, url, params=None, json=None, auth=None, timeout=None):
        recorded["method"] = method
        recorded["url"] = url
        recorded["params"] = params
        recorded["json"] = json
        recorded["auth"] = auth
        recorded["timeout"] = timeout
        return SimpleNamespace(
            status_code=200,
            text="",
            json=lambda: None,
            raise_for_status=lambda: None,
        )

    monkeypatch.setattr("hotvect.experiment_management.client.requests.request", fake_request)

    conn = ExperimentManagementConnection(environment=Environment.LOCAL, bearer_auth=SimpleNamespace())
    client = ExperimentManagementClient(conn)
    client.create_algorithm_parameter(
        AlgorithmParameterSpec(
            algorithm_parameter_id="p1",
            algorithm=AlgorithmName(algorithm_name="algo", algorithm_version="1.2.3"),
            evaluation_results="",
            absolute_s3_path="s3://bucket/key",
        )
    )

    assert recorded["method"] == "POST"
    assert recorded["url"].endswith("/algorithm-parameters")
