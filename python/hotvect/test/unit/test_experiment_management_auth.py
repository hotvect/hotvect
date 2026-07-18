from __future__ import annotations

import json
from types import SimpleNamespace

import pytest

from hotvect.experiment_management.auth import (
    CommandTokenProvider,
    TokenProviderAuth,
    token_provider_from_claude_settings,
)


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


def test_token_provider_from_claude_settings_reads_api_key_helper(tmp_path, monkeypatch):
    settings_path = tmp_path / "settings.json"
    settings_path.write_text(
        json.dumps(
            {
                "apiKeyHelper": "echo tok",
                "env": {"CLAUDE_CODE_API_KEY_HELPER_TTL_MS": "1000"},
            }
        ),
        encoding="utf-8",
    )

    monkeypatch.setattr(
        "hotvect.experiment_management.auth.subprocess.check_output",
        lambda args, *, text, stderr: "tok\n",
    )

    provider = token_provider_from_claude_settings(settings_path=settings_path)
    assert provider.ttl_seconds == pytest.approx(1.0)
    assert provider() == "tok"
