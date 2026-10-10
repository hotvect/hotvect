from __future__ import annotations

import shlex
import subprocess
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass

import boto3
from requests.auth import AuthBase

TokenProvider = Callable[[], str]


class SecretsManagerAuth(AuthBase):
    def __init__(self, secret_id: str, *, region_name: str = "eu-central-1"):
        self._secret_id = secret_id
        self._region_name = region_name

    def __call__(self, request):
        client = boto3.client("secretsmanager", region_name=self._region_name)
        token = client.get_secret_value(SecretId=self._secret_id)["SecretString"]
        request.headers["Authorization"] = _as_bearer_header_value(token)
        return request


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


def _as_bearer_header_value(token_or_header: str) -> str:
    value = token_or_header.strip()
    if not value:
        raise RuntimeError("Bearer token is empty")
    if value.lower().startswith("bearer "):
        return value
    return "Bearer " + value
