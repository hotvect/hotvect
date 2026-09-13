from __future__ import annotations

from types import SimpleNamespace

from hotvect.experiment_management.auth import CommandTokenProvider, TokenProviderAuth


def test_token_provider_auth_sets_bearer_header():
    auth = TokenProviderAuth(lambda: "abc123")
    request = SimpleNamespace(headers={})
    auth(request)
    assert request.headers["Authorization"] == "Bearer abc123"


def test_token_provider_auth_keeps_existing_bearer_prefix():
    auth = TokenProviderAuth(lambda: "Bearer abc123")
    request = SimpleNamespace(headers={})
    auth(request)
    assert request.headers["Authorization"] == "Bearer abc123"


def test_command_token_provider_caches(monkeypatch):
    calls: list[list[str]] = []

    def fake_check_output(args, *, text, stderr):
        calls.append(list(args))
        return "tok\n"

    monkeypatch.setattr("hotvect.experiment_management.auth.subprocess.check_output", fake_check_output)

    provider = CommandTokenProvider(command="echo tok", ttl_seconds=60)
    assert provider() == "tok"
    assert provider() == "tok"
    assert len(calls) == 1
