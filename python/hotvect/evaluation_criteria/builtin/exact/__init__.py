"""Built-in QA exact-equivalence criteria."""

from collections.abc import Callable
from typing import Any

from hotvect.evaluation_criteria.base import CriteriaManifest, CriteriaPolicy
from hotvect.evaluation_criteria.builtin.common import require_treatments
from hotvect.evaluation_criteria.builtin.noninferiority.policy import get_stage_handlers
from hotvect.evaluation_criteria.helpers import entity_id, make_reason, status_flag, summarize_counts


def _fixed_parameter_source(payload: dict[str, Any]) -> str | None:
    parameter_source = payload.get("parameter_source")
    if parameter_source:
        return str(parameter_source)
    context = payload.get("context")
    if isinstance(context, dict) and context.get("parameter_source"):
        return str(context["parameter_source"])
    return None


def _require_shared_parameter_source(payload: dict[str, Any], *, stage: str) -> None:
    if payload.get("parameter_sources"):
        raise ValueError(
            f"QA exact criteria stage '{stage}' requires a shared 'parameter_source'; "
            "per-ref parameter sources cannot prove fixed-model parity."
        )
    if not _fixed_parameter_source(payload):
        raise ValueError(
            f"QA exact criteria stage '{stage}' requires a shared 'parameter_source' to prove fixed-model parity."
        )


def _with_shared_parameter_source(
    stage: str,
    handler: Callable[[dict[str, Any]], dict[str, Any]],
) -> Callable[[dict[str, Any]], dict[str, Any]]:
    def wrapped(payload: dict[str, Any]) -> dict[str, Any]:
        _require_shared_parameter_source(payload, stage=stage)
        return handler(payload)

    return wrapped


def _strict_control_completed_result(payload: dict[str, Any], *, stage: str) -> dict[str, Any] | None:
    control = payload.get("control")
    control_jobs_complete = status_flag(control, "jobs_complete") if isinstance(control, dict) else None
    if control_jobs_complete is True:
        return None

    judgment = "inconclusive" if control_jobs_complete is None else "fail"
    reason_code = "missing_control_jobs_complete" if control_jobs_complete is None else "control_jobs_complete"
    reason_message = (
        "Missing status flag 'jobs_complete' for control."
        if control_jobs_complete is None
        else f"Control did not complete successfully during {stage}; exact parity requires outputs."
    )
    treatments = require_treatments(payload)
    decisions = [
        {
            "git_ref": entity_id(treatment, default=f"treatment-{index}"),
            "judgment": judgment,
            "recommendation": "stop",
            "reasons": [make_reason(reason_code, reason_message)],
        }
        for index, treatment in enumerate(treatments, start=1)
    ]
    return {
        "stage_judgment": judgment,
        "rollout_recommendation": "stop",
        "selected_treatments": [],
        "summary": summarize_counts("treatments", passed=0, total=len(decisions)),
        "reasons": [
            make_reason(
                "no_treatment_passed",
                f"No treatment cleared the {stage} gate because the control output was unavailable.",
            )
        ],
        "arm_decisions": decisions,
    }


def _with_strict_control_success(
    stage: str,
    handler: Callable[[dict[str, Any]], dict[str, Any]],
) -> Callable[[dict[str, Any]], dict[str, Any]]:
    def wrapped(payload: dict[str, Any]) -> dict[str, Any]:
        _require_shared_parameter_source(payload, stage=stage)
        strict_result = _strict_control_completed_result(payload, stage=stage)
        if strict_result is not None:
            return strict_result
        return handler(payload)

    return wrapped


def _stage_handlers() -> dict[str, Callable[[dict[str, Any]], dict[str, Any]]]:
    handlers = dict(get_stage_handlers())
    handlers["audit_parity"] = _with_strict_control_success("audit_parity", handlers["audit_parity"])
    handlers["encode_parity"] = _with_strict_control_success("encode_parity", handlers["encode_parity"])
    handlers["predict_parity"] = _with_strict_control_success("predict_parity", handlers["predict_parity"])
    handlers["system_performance"] = _with_shared_parameter_source("system_performance", handlers["system_performance"])
    return handlers


MANIFEST = CriteriaManifest(
    name="exact",
    scenario="qa",
    supported_stages=(
        "audit_parity",
        "encode_parity",
        "system_performance",
        "predict_parity",
        "realistic_single_day",
        "multi_day_backtest",
    ),
    description=(
        "QA exact-equivalence validation for changes where model semantics are not meant to change: "
        "require fixed-parameter audit/encode/predict parity before quality and system gates."
    ),
)

POLICY = CriteriaPolicy(MANIFEST, _stage_handlers())

__all__ = ["MANIFEST", "POLICY"]
