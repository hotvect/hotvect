from __future__ import annotations

import json
import os
import shlex
import subprocess
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from pathlib import Path

import boto3
from requests.auth import AuthBase

TokenProvider = Callable[[], str]


class SecretsManagerAuth(AuthBase):
    def __init__(self, secret_id: str, *, region_name: str = "eu-central-1"):
        self._secret_id = secret_id
        self._region_name = region_name

    def __call__(self, request):
        request.headers["Authorization"] = _as_bearer_header_value(self._get_token())
        return request

    def _get_token(self) -> str:
        client = boto3.client("secretsmanager", region_name=self._region_name)
        return client.get_secret_value(SecretId=self._secret_id)["SecretString"]


class TokenProviderAuth(AuthBase):
    def __init__(self, token_provider: TokenProvider):
        self._token_provider = token_provider

    def __call__(self, request):
        request.headers["Authorization"] = _as_bearer_header_value(self._token_provider())
        return request


@dataclass(frozen=True)
class CommandTokenProvider:
    """
    Token provider that executes a command (e.g. `ztoken token -n ems`) and caches the result.

    This mirrors Claude Code's `apiKeyHelper` behavior: shell out to a helper to obtain a token
    and reuse it for a configured TTL.
    """

    command: str | Sequence[str]
    ttl_seconds: float = 3600.0

    def __post_init__(self) -> None:
        if self.ttl_seconds <= 0:
            raise ValueError("ttl_seconds must be > 0")

        object.__setattr__(self, "_cached_token", None)
        object.__setattr__(self, "_cached_at", None)

    def __call__(self) -> str:
        cached_token = getattr(self, "_cached_token")
        cached_at = getattr(self, "_cached_at")
        now = time.monotonic()
        if cached_token is not None and cached_at is not None and (now - cached_at) < self.ttl_seconds:
            return cached_token

        token = self._run_command().strip()
        if not token:
            raise RuntimeError("Token provider command returned an empty token")

        object.__setattr__(self, "_cached_token", token)
        object.__setattr__(self, "_cached_at", now)
        return token

    def _run_command(self) -> str:
        if isinstance(self.command, str):
            args = shlex.split(self.command)
        else:
            args = list(self.command)
        return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)


class BearerAuth(AuthBase):
    """
    Optional auth that uses the internal `tokens` library (STUPS tokens).

    This matches the legacy STUPS-token behavior without forcing the dependency in Hotvect's base install.
    """

    def __init__(self, token_name: str = "ems"):
        self._token_name = token_name
        self._configure_token()

    def __call__(self, request):
        request.headers["Authorization"] = _as_bearer_header_value(self._get_token())
        return request

    def _configure_token(self) -> None:
        try:
            import tokens  # type: ignore
        except Exception as e:  # pragma: no cover
            raise RuntimeError(
                "BearerAuth requires the `tokens` package to be installed (internal STUPS tokens client)."
            ) from e

        tokens.configure(from_file_only=False)
        tokens.manage(self._token_name)

    def _get_token(self) -> str:
        import tokens  # type: ignore

        return tokens.get(self._token_name)


def token_provider_from_claude_settings(
    *,
    settings_path: str | Path = "~/.claude/settings.json",
    ttl_ms_env_var: str = "CLAUDE_CODE_API_KEY_HELPER_TTL_MS",
    default_ttl_ms: int = 3600_000,
) -> CommandTokenProvider:
    """
    Build a command-based token provider from Claude Code's settings.json.

    Claude Code uses `apiKeyHelper` to retrieve a token on-demand. The TTL is often configured
    via an env var (e.g. `CLAUDE_CODE_API_KEY_HELPER_TTL_MS`) which may also appear in the
    settings.json `env` field.
    """

    settings_file = Path(settings_path).expanduser()
    settings = json.loads(settings_file.read_text(encoding="utf-8"))
    helper_command = settings.get("apiKeyHelper")
    if not helper_command:
        raise KeyError(f"Missing `apiKeyHelper` in {settings_file}")

    ttl_ms = _parse_int(os.environ.get(ttl_ms_env_var), default=None)
    if ttl_ms is None:
        ttl_ms = _parse_int((settings.get("env") or {}).get(ttl_ms_env_var), default=default_ttl_ms)

    return CommandTokenProvider(command=helper_command, ttl_seconds=ttl_ms / 1000.0)


def _parse_int(value: str | None, *, default: int | None) -> int | None:
    if value is None:
        return default
    try:
        return int(value)
    except ValueError:
        return default


def _as_bearer_header_value(token_or_header: str) -> str:
    value = token_or_header.strip()
    if not value:
        raise RuntimeError("Bearer token is empty")
    if value.lower().startswith("bearer "):
        return value
    return "Bearer " + value
