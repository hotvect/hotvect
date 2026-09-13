"""Shared helpers for built-in criteria policies."""

from __future__ import annotations

from typing import Any, Mapping

from hotvect.evaluation_criteria.helpers import entity_id, metric_value


def require_treatments(payload: Mapping[str, Any]) -> list[dict[str, Any]]:
    treatments = payload.get("treatments")
    if not isinstance(treatments, list) or not treatments:
        raise ValueError("Criteria payload must include a non-empty 'treatments' list.")
    for treatment in treatments:
        if not isinstance(treatment, dict):
            raise ValueError("Every treatment entry must be a dict.")
    return treatments


def require_refs(payload: Mapping[str, Any]) -> list[dict[str, Any]]:
    refs = payload.get("refs")
    if not isinstance(refs, list) or not refs:
        raise ValueError("Criteria payload must include a non-empty 'refs' list.")
    for ref in refs:
        if not isinstance(ref, dict):
            raise ValueError("Every ref entry must be a dict.")
    return refs


def require_targets(payload: Mapping[str, Any]) -> list[dict[str, Any]]:
    targets = payload.get("targets")
    if not isinstance(targets, list) or not targets:
        raise ValueError("Criteria payload must include a non-empty 'targets' list.")
    for target in targets:
        if not isinstance(target, dict):
            raise ValueError("Every target entry must be a dict.")
    return targets


def _float_metric_or_default(value: Any, default: float) -> float:
    return default if value is None else float(value)


def sort_treatments_by_rank(treatments: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return sorted(
        treatments,
        key=lambda treatment: (
            -_float_metric_or_default(metric_value(treatment, "offline_quality", default=None), float("-inf")),
            -_float_metric_or_default(metric_value(treatment, "performance", default=None), float("-inf")),
            entity_id(treatment, default=""),
        ),
    )


def sort_refs_by_perf_rank(refs: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return sorted(
        refs,
        key=lambda ref: (
            _float_metric_or_default(metric_value(ref, "p99_ms", default=None), float("inf")),
            -_float_metric_or_default(metric_value(ref, "throughput_rps", default=None), float("-inf")),
            _float_metric_or_default(metric_value(ref, "peak_rss_gib", default=None), float("inf")),
            entity_id(ref, default=""),
        ),
    )
