"""Shared CLI helpers for executable evaluation criteria."""

from __future__ import annotations

import json
import sys
from typing import Any

from hotvect.evaluation_criteria import load_builtin_policy


def load_policy_or_exit(criteria_id: str):
    try:
        return load_builtin_policy(criteria_id)
    except KeyError as exc:
        message = exc.args[0] if exc.args else str(exc)
        raise SystemExit(message) from exc


def load_payload_from_cli(payload_file: str | None) -> dict[str, Any]:
    try:
        if payload_file:
            with open(payload_file, "r", encoding="utf-8") as fp:
                return json.load(fp)
        return json.load(sys.stdin)
    except json.JSONDecodeError as exc:
        source = payload_file or "stdin"
        raise SystemExit(f"Failed to parse criteria payload JSON from {source}: {exc}") from exc
    except OSError as exc:
        source = payload_file or "stdin"
        raise SystemExit(f"Failed to read criteria payload JSON from {source}: {exc}") from exc
