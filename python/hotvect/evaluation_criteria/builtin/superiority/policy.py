"""Stage handlers for superiority criteria."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

from hotvect.evaluation_criteria.builtin.common import require_treatments, sort_treatments_by_rank
from hotvect.evaluation_criteria.builtin.noninferiority.policy import (
    _quality_statistically_superior,
    _quality_superiority_hypothesis_count,
)
from hotvect.evaluation_criteria.builtin.noninferiority.policy import get_stage_handlers as get_noninferiority_handlers
from hotvect.evaluation_criteria.helpers import aggregate_stage_judgment, entity_id, make_reason, summarize_counts


def _quality_superiority_flag(
    treatment: dict[str, Any],
    *,
    family_size: int,
) -> tuple[bool | None, dict[str, Any]]:
    quality_metrics = treatment.get("metrics", {}).get("quality")
    if not isinstance(quality_metrics, dict):
        return None, {}
    return _quality_statistically_superior(quality_metrics, family_size=family_size)


def _apply_quality_superiority(
    payload: dict[str, Any],
    result: dict[str, Any],
    *,
    stage: str,
) -> dict[str, Any]:
    treatments = require_treatments(payload)
    treatments_by_id = {
        entity_id(treatment, default=f"treatment-{index}"): treatment
        for index, treatment in enumerate(treatments, start=1)
    }
    family_size = sum(
        _quality_superiority_hypothesis_count(quality_metrics)
        for treatment in treatments
        if isinstance(quality_metrics := treatment.get("metrics", {}).get("quality"), dict)
    )
    decisions: list[dict[str, Any]] = []

    for decision in result["arm_decisions"]:
        treatment_id = str(decision["git_ref"])
        treatment = treatments_by_id[treatment_id]
        quality_superior, quality_superiority_tests = _quality_superiority_flag(
            treatment,
            family_size=family_size,
        )
        updated = dict(decision)
        updated["quality_superior"] = quality_superior
        updated["quality_superiority_tests"] = quality_superiority_tests
        updated["reasons"] = list(decision.get("reasons") or [])
        if decision["judgment"] == "pass":
            if quality_superior is True:
                updated["reasons"].append(
                    make_reason(
                        "quality_superiority_ok",
                        f"{treatment_id} showed a statistically supported offline quality gain.",
                    )
                )
            elif quality_superior is False:
                updated["judgment"] = "fail"
                updated["recommendation"] = "stop"
                updated["reasons"].append(
                    make_reason(
                        "quality_superiority",
                        f"{treatment_id}: offline quality did not show a statistically supported gain.",
                    )
                )
            else:
                updated["judgment"] = "inconclusive"
                updated["recommendation"] = "stop"
                updated["reasons"].append(
                    make_reason(
                        "missing_quality_superiority",
                        f"{treatment_id}: missing paired quality metrics for statistical superiority evaluation.",
                    )
                )
        decisions.append(updated)

    pass_ids = {str(decision["git_ref"]) for decision in decisions if decision["judgment"] == "pass"}
    ranked_passers = sort_treatments_by_rank(
        [treatment for treatment_id, treatment in treatments_by_id.items() if treatment_id in pass_ids]
    )
    selected = [entity_id(treatment, default="") for treatment in ranked_passers]
    stage_judgment = aggregate_stage_judgment(decision["judgment"] for decision in decisions)

    if selected:
        online_policy_known = bool(payload.get("context", {}).get("online_policy_known"))
        rollout_recommendation = (
            "ready_for_online" if stage == "multi_day_backtest" and online_policy_known else "advance"
        )
        summary = summarize_counts("treatments", passed=len(selected), total=len(decisions))
        reasons = [
            make_reason(
                "quality_superiority",
                "At least one treatment passed safety gates and showed a statistically supported offline quality gain.",
            )
        ]
    else:
        rollout_recommendation = "stop"
        summary = "No treatment cleared the superiority gate."
        reasons = [make_reason("no_superior_treatment", summary)]

    updated_result = dict(result)
    updated_result.update(
        {
            "stage_judgment": stage_judgment,
            "rollout_recommendation": rollout_recommendation,
            "selected_treatments": selected,
            "summary": summary,
            "reasons": reasons,
            "arm_decisions": decisions,
        }
    )
    return updated_result


def _with_quality_superiority(
    stage: str,
    handler: Callable[[dict[str, Any]], dict[str, Any]],
) -> Callable[[dict[str, Any]], dict[str, Any]]:
    def wrapped(payload: dict[str, Any]) -> dict[str, Any]:
        return _apply_quality_superiority(payload, handler(payload), stage=stage)

    return wrapped


def get_stage_handlers():
    handlers = dict(get_noninferiority_handlers())
    handlers["multi_day_backtest"] = _with_quality_superiority("multi_day_backtest", handlers["multi_day_backtest"])
    return handlers
