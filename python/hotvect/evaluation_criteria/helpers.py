"""Small helpers for built-in criteria policies."""

from __future__ import annotations

from typing import Any, Iterable, Mapping


def get_nested(data: Mapping[str, Any], *path: str, default: Any = None) -> Any:
    current: Any = data
    for key in path:
        if not isinstance(current, Mapping) or key not in current:
            return default
        current = current[key]
    return current


def entity_id(entity: Mapping[str, Any], *, default: str) -> str:
    for key in ("git_ref", "id", "algorithm_version", "name"):
        value = entity.get(key)
        if isinstance(value, str) and value:
            return value
    return default


def status_flag(entity: Mapping[str, Any], name: str) -> Any:
    return get_nested(entity, "status", name)


def metric_value(entity: Mapping[str, Any], name: str, *, default: Any = None) -> Any:
    return get_nested(entity, "metrics", name, default=default)


def make_reason(code: str, message: str) -> dict[str, str]:
    return {"code": code, "message": message}


def aggregate_stage_judgment(judgments: Iterable[str]) -> str:
    ordered = list(judgments)
    if any(j == "pass" for j in ordered):
        return "pass"
    if any(j == "fail" for j in ordered):
        return "fail"
    if any(j == "confounded" for j in ordered):
        return "confounded"
    return "inconclusive"


def summarize_counts(label: str, *, passed: int, total: int) -> str:
    return f"{passed}/{total} {label} passed the gate."
