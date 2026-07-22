from __future__ import annotations

from types import SimpleNamespace

from hotvect.experiment_management.hotvect_config import (
    create_client_from_hotvect_config,
    load_experiment_management_hotvect_config,
)


def test_create_client_from_hotvect_config_uses_config_url_and_token_provider(monkeypatch):
    recorded = {}

    config = {
        "experiment_management": {
            "url": "http://localhost:9999",
            "token_provider_command": "echo tok",
            "token_provider_ttl_ms": 1000,
            "connect_timeout_seconds": 7.5,
            "read_timeout_seconds": 22.0,
        }
    }

    monkeypatch.setattr(
        "hotvect.experiment_management.auth.subprocess.check_output",
        lambda args, *, text, stderr: "tok\n",
    )

    def fake_request(*, method, url, params=None, json=None, auth=None, timeout=None):
        recorded["method"] = method
        recorded["url"] = url
        recorded["timeout"] = timeout

        req = SimpleNamespace(headers={})
        auth(req)
        recorded["auth_header"] = req.headers.get("Authorization")
        return SimpleNamespace(status_code=200, text="", json=lambda: None, raise_for_status=lambda: None)

    monkeypatch.setattr("hotvect.experiment_management.client.requests.request", fake_request)

    client = create_client_from_hotvect_config(config=config)
    client.get_algorithms()

    assert recorded["method"] == "GET"
    assert recorded["url"] == "http://localhost:9999/algorithms"
    assert recorded["auth_header"] == "Bearer tok"
    assert recorded["timeout"] == (7.5, 22.0)


def test_create_client_from_hotvect_config_allows_url_override(monkeypatch):
    recorded = {}

    config = {
        "experiment_management": {
            "url": "http://localhost:1111",
            "token_provider_command": "echo tok",
            "token_provider_ttl_ms": 1000,
        }
    }

    monkeypatch.setattr(
        "hotvect.experiment_management.auth.subprocess.check_output",
        lambda args, *, text, stderr: "tok\n",
    )

    def fake_request(*, method, url, params=None, json=None, auth=None, timeout=None):
        recorded["method"] = method
        recorded["url"] = url
        recorded["timeout"] = timeout
        return SimpleNamespace(status_code=200, text="", json=lambda: None, raise_for_status=lambda: None)

    monkeypatch.setattr("hotvect.experiment_management.client.requests.request", fake_request)

    client = create_client_from_hotvect_config(config=config, url_override="http://localhost:2222")
    client.get_algorithms()

    assert recorded["method"] == "GET"
    assert recorded["url"] == "http://localhost:2222/algorithms"
    assert recorded["timeout"] == (5.0, 15.0)


def test_create_client_from_hotvect_config_rejects_non_positive_timeouts():
    config = {
        "experiment_management": {
            "url": "http://localhost:1111",
            "token_provider_command": "echo tok",
            "token_provider_ttl_ms": 1000,
            "connect_timeout_seconds": 0,
        }
    }

    try:
        create_client_from_hotvect_config(config=config)
    except ValueError as exc:
        assert "connect_timeout_seconds" in str(exc)
    else:
        raise AssertionError("expected ValueError")


def test_load_experiment_management_hotvect_config_reads_online_results_slot_mapping():
    config = {
        "experiment_management": {
            "url": "http://localhost:1111",
            "token_provider_command": "echo tok",
            "online_results": {
                "slots": {
                    "slot-a": {"s3_base_prefix": "s3://bucket/slot-a/"},
                    "slot-b": {"s3_base_prefix": "s3://bucket/slot-b/"},
                }
            },
        }
    }

    loaded = load_experiment_management_hotvect_config(config=config)

    assert loaded.online_results_s3_base_prefixes_by_slot == {
        "slot-a": "s3://bucket/slot-a/",
        "slot-b": "s3://bucket/slot-b/",
    }
