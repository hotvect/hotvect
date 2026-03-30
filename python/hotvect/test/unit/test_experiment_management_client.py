from __future__ import annotations

from types import SimpleNamespace

from hotvect.experiment_management import Environment, ExperimentManagementClient, ExperimentManagementConnection


def test_experiment_management_ignore_404_returns_none(monkeypatch):
    def fake_request(*, method, url, params=None, json=None, auth=None, timeout=None):
        return SimpleNamespace(
            status_code=404,
            text="",
            json=lambda: None,
            raise_for_status=lambda: (_ for _ in ()).throw(AssertionError("should not be called")),
        )

    monkeypatch.setattr("hotvect.experiment_management.client.requests.request", fake_request)

    conn = ExperimentManagementConnection(environment=Environment.LOCAL, bearer_auth=SimpleNamespace())
    client = ExperimentManagementClient(conn)
    assert client.get_algorithm("a", "b", ignore_404=True) is None
