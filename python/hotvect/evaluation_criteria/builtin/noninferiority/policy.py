"""Stage handlers for noninferiority criteria."""

from __future__ import annotations

import math
from typing import Any

from scipy.stats import t as student_t

from hotvect.evaluation_criteria.builtin.common import require_treatments, sort_treatments_by_rank
from hotvect.evaluation_criteria.helpers import (
    aggregate_stage_judgment,
    entity_id,
    make_reason,
    metric_value,
    status_flag,
    summarize_counts,
)

_DEFAULT_QUALITY_MAX_RELATIVE_NONINFERIORITY_MARGIN_PCT = 0.5
_DEFAULT_SYSTEM_MAX_RELATIVE_NONINFERIORITY_MARGIN_PCT = 3.0
_QUALITY_NONINFERIORITY_STATISTICAL_ALPHA = 0.05
_SYSTEM_PERFORMANCE_STATISTICAL_ALPHA = 0.05
_QUALITY_SUPERIORITY_STATISTICAL_ALPHA = 0.05
_QUALITY_GATE_METRICS = frozenset(
    {
        "roc_auc",
        "pr_auc",
        "map_at_10",
        "map_at_50",
        "map_at_100",
        "map_at_all",
        "ndcg_at_10",
        "ndcg_at_50",
        "ndcg_at_100",
        "ndcg_at_all",
    }
)
_HIGHER_IS_WORSE_SYSTEM_METRICS = frozenset({"p50", "p75", "p95", "p99", "p999", "max_memory_usage"})
_LOWER_IS_WORSE_SYSTEM_METRICS = frozenset({"mean_throughput"})


def _require_parameter_source(payload: dict[str, Any], *, stage: str) -> str:
    parameter_source = payload.get("parameter_source")
    if not parameter_source:
        parameter_sources = payload.get("parameter_sources")
        if stage == "system_performance" and isinstance(parameter_sources, dict):
            if parameter_sources.get("mode") != "per_ref":
                raise ValueError(
                    f"Noninferiority criteria stage '{stage}' received unsupported parameter_sources.mode."
                )
            return "per_ref"
        raise ValueError(f"Noninferiority criteria stage '{stage}' requires a non-empty 'parameter_source'.")
    return str(parameter_source)


def _metric_group(treatment: dict[str, Any], group: str) -> dict[str, dict[str, Any]]:
    value = metric_value(treatment, group, default={})
    return value if isinstance(value, dict) else {}


def _one_sided_p_value(samples: list[float], *, null_mean: float, alternative: str) -> float:
    shifted = [value - null_mean for value in samples]
    shifted_mean = sum(shifted) / len(shifted)
    if alternative == "greater":
        if shifted_mean <= 0:
            return 1.0
    elif alternative == "less":
        if shifted_mean >= 0:
            return 1.0
    else:
        raise ValueError(f"Unsupported alternative: {alternative!r}")

    variance = sum((value - shifted_mean) ** 2 for value in shifted) / (len(shifted) - 1)
    if variance == 0:
        return 0.0

    t_statistic = shifted_mean / (math.sqrt(variance) / math.sqrt(len(shifted)))
    if alternative == "greater":
        return float(student_t.sf(t_statistic, df=len(shifted) - 1))
    return float(student_t.cdf(t_statistic, df=len(shifted) - 1))


def _paired_percent_change_values(
    payload: dict[str, Any],
    *,
    alpha: float,
    min_observations: int = 2,
    extra: dict[str, Any] | None = None,
) -> tuple[list[float] | None, dict[str, Any] | None]:
    paired_percent_changes = payload.get("paired_percent_changes")
    if not isinstance(paired_percent_changes, list):
        return None, {
            "status": "insufficient_data",
            "reason": "missing_paired_percent_changes",
            "alpha": alpha,
            **(extra or {}),
        }

    deltas = [float(value) for value in paired_percent_changes if value is not None]
    if len(deltas) < min_observations:
        return None, {
            "status": "insufficient_data",
            "reason": f"requires_at_least_{min_observations}_paired_observations",
            "observation_count": len(deltas),
            "alpha": alpha,
            **(extra or {}),
        }
    return deltas, None


def _paired_quality_noninferiority_test(payload: dict[str, Any]) -> dict[str, Any]:
    margin = -_DEFAULT_QUALITY_MAX_RELATIVE_NONINFERIORITY_MARGIN_PCT
    deltas, failure = _paired_percent_change_values(
        payload,
        alpha=_QUALITY_NONINFERIORITY_STATISTICAL_ALPHA,
        extra={"margin_percent": margin},
    )
    if deltas is None:
        if failure and failure.get("reason") == "requires_at_least_2_paired_observations":
            failure = dict(failure)
            failure["reason"] = "requires_at_least_two_paired_dates"
            failure["date_count"] = failure.pop("observation_count")
        return failure

    p_value = _one_sided_p_value(deltas, null_mean=margin, alternative="greater")

    return {
        "status": "tested",
        "date_count": len(deltas),
        "mean_percent_change": sum(deltas) / len(deltas),
        "margin_percent": margin,
        "p_value": p_value,
        "alpha": _QUALITY_NONINFERIORITY_STATISTICAL_ALPHA,
        "passed": p_value <= _QUALITY_NONINFERIORITY_STATISTICAL_ALPHA,
    }


def _paired_quality_superiority_test(
    payload: dict[str, Any],
    *,
    adjusted_alpha: float,
    family_size: int,
) -> dict[str, Any]:
    deltas, failure = _paired_percent_change_values(
        payload,
        alpha=adjusted_alpha,
        extra={
            "margin_percent": 0.0,
            "nominal_alpha": _QUALITY_SUPERIORITY_STATISTICAL_ALPHA,
            "multiplicity_adjustment": "bonferroni",
            "family_size": family_size,
        },
    )
    if deltas is None:
        if failure and failure.get("reason") == "requires_at_least_2_paired_observations":
            failure = dict(failure)
            failure["reason"] = "requires_at_least_two_paired_dates"
            failure["date_count"] = failure.pop("observation_count")
        return failure

    p_value = _one_sided_p_value(deltas, null_mean=0.0, alternative="greater")
    return {
        "status": "tested",
        "date_count": len(deltas),
        "mean_percent_change": sum(deltas) / len(deltas),
        "margin_percent": 0.0,
        "p_value": p_value,
        "alpha": adjusted_alpha,
        "nominal_alpha": _QUALITY_SUPERIORITY_STATISTICAL_ALPHA,
        "multiplicity_adjustment": "bonferroni",
        "family_size": family_size,
        "passed": p_value <= adjusted_alpha,
    }


def _is_quality_gate_metric(metric: str) -> bool:
    return metric.rsplit(".", maxsplit=1)[-1] in _QUALITY_GATE_METRICS


def _quality_superiority_hypothesis_count(metrics: dict[str, Any]) -> int:
    return sum(_is_quality_gate_metric(metric) for metric in metrics)


def _quality_statistically_noninferior(metrics: dict[str, dict[str, Any]]) -> tuple[bool | None, dict[str, Any]]:
    if not metrics:
        return None, {}

    tests: dict[str, Any] = {}
    saw_quality_metric = False
    missing_tests: list[str] = []
    failed_tests: list[str] = []
    for metric, payload in metrics.items():
        if not _is_quality_gate_metric(metric):
            continue
        saw_quality_metric = True
        if not isinstance(payload, dict):
            missing_tests.append(metric)
            tests[metric] = {
                "status": "insufficient_data",
                "reason": "metric_payload_not_object",
                "margin_percent": -_DEFAULT_QUALITY_MAX_RELATIVE_NONINFERIORITY_MARGIN_PCT,
                "alpha": _QUALITY_NONINFERIORITY_STATISTICAL_ALPHA,
            }
            continue
        test = _paired_quality_noninferiority_test(payload)
        tests[metric] = test
        if test.get("status") != "tested":
            missing_tests.append(metric)
        elif test.get("passed") is not True:
            failed_tests.append(metric)

    if not saw_quality_metric:
        return None, tests
    if missing_tests:
        return None, tests
    if failed_tests:
        return False, tests
    return True, tests


def _quality_statistically_superior(
    metrics: dict[str, dict[str, Any]],
    *,
    family_size: int | None = None,
) -> tuple[bool | None, dict[str, Any]]:
    if not metrics:
        return None, {}

    hypothesis_count = _quality_superiority_hypothesis_count(metrics)
    if hypothesis_count == 0:
        return None, {}
    resolved_family_size = hypothesis_count if family_size is None else family_size
    if resolved_family_size < hypothesis_count:
        raise ValueError(
            f"Superiority family size {resolved_family_size} is smaller than the {hypothesis_count} tested metrics."
        )
    adjusted_alpha = _QUALITY_SUPERIORITY_STATISTICAL_ALPHA / resolved_family_size

    tests: dict[str, Any] = {}
    saw_quality_metric = False
    missing_tests: list[str] = []
    passed_tests: list[str] = []
    for metric, payload in metrics.items():
        if not _is_quality_gate_metric(metric):
            continue
        saw_quality_metric = True
        if not isinstance(payload, dict):
            missing_tests.append(metric)
            tests[metric] = {
                "status": "insufficient_data",
                "reason": "metric_payload_not_object",
                "margin_percent": 0.0,
                "alpha": adjusted_alpha,
                "nominal_alpha": _QUALITY_SUPERIORITY_STATISTICAL_ALPHA,
                "multiplicity_adjustment": "bonferroni",
                "family_size": resolved_family_size,
            }
            continue
        test = _paired_quality_superiority_test(
            payload,
            adjusted_alpha=adjusted_alpha,
            family_size=resolved_family_size,
        )
        tests[metric] = test
        if test.get("status") != "tested":
            missing_tests.append(metric)
        elif test.get("passed") is True:
            passed_tests.append(metric)

    if not saw_quality_metric:
        return None, tests
    if missing_tests:
        return None, tests
    if passed_tests:
        return True, tests
    return False, tests


def _paired_system_regression_test(metric: str, payload: dict[str, Any]) -> dict[str, Any]:
    if metric not in _HIGHER_IS_WORSE_SYSTEM_METRICS and metric not in _LOWER_IS_WORSE_SYSTEM_METRICS:
        return {
            "status": "insufficient_data",
            "reason": "unsupported_system_metric",
            "metric": metric,
            "margin_percent": _DEFAULT_SYSTEM_MAX_RELATIVE_NONINFERIORITY_MARGIN_PCT,
            "alpha": _SYSTEM_PERFORMANCE_STATISTICAL_ALPHA,
        }

    deltas, failure = _paired_percent_change_values(
        payload,
        alpha=_SYSTEM_PERFORMANCE_STATISTICAL_ALPHA,
        min_observations=2,
        extra={"metric": metric},
    )
    if deltas is None:
        if failure and failure.get("reason") == "requires_at_least_2_paired_observations":
            failure = dict(failure)
            failure["reason"] = "requires_at_least_two_paired_trials"
            failure["trial_count"] = failure.pop("observation_count")
        return failure

    regression_direction = "increase" if metric in _HIGHER_IS_WORSE_SYSTEM_METRICS else "decrease"
    regression_deltas = deltas if regression_direction == "increase" else [-value for value in deltas]
    margin = _DEFAULT_SYSTEM_MAX_RELATIVE_NONINFERIORITY_MARGIN_PCT
    p_value = _one_sided_p_value(regression_deltas, null_mean=margin, alternative="less")
    statistically_noninferior = p_value <= _SYSTEM_PERFORMANCE_STATISTICAL_ALPHA
    return {
        "status": "tested",
        "trial_count": len(deltas),
        "metric": metric,
        "regression_direction": regression_direction,
        "mean_percent_change": sum(deltas) / len(deltas),
        "margin_percent": margin,
        "p_value": p_value,
        "alpha": _SYSTEM_PERFORMANCE_STATISTICAL_ALPHA,
        "statistically_noninferior": statistically_noninferior,
        "passed": statistically_noninferior,
    }


def _performance_statistically_noninferior(metrics: dict[str, dict[str, Any]]) -> tuple[bool | None, dict[str, Any]]:
    if not metrics:
        return None, {}

    tests: dict[str, Any] = {}
    saw_system_metric = False
    missing_tests: list[str] = []
    failed_tests: list[str] = []
    for metric, payload in metrics.items():
        if metric not in _HIGHER_IS_WORSE_SYSTEM_METRICS and metric not in _LOWER_IS_WORSE_SYSTEM_METRICS:
            continue
        saw_system_metric = True
        if not isinstance(payload, dict):
            missing_tests.append(metric)
            tests[metric] = {
                "status": "insufficient_data",
                "reason": "metric_payload_not_object",
                "metric": metric,
                "margin_percent": _DEFAULT_SYSTEM_MAX_RELATIVE_NONINFERIORITY_MARGIN_PCT,
                "alpha": _SYSTEM_PERFORMANCE_STATISTICAL_ALPHA,
            }
            continue
        test = _paired_system_regression_test(metric, payload)
        tests[metric] = test
        if test.get("status") != "tested":
            missing_tests.append(metric)
        elif test.get("passed") is not True:
            failed_tests.append(metric)
    if not saw_system_metric:
        return None, tests
    if missing_tests:
        return None, tests
    if failed_tests:
        return False, tests
    return True, tests


def _quality_statistical_gate_flag(treatment: dict[str, Any]) -> tuple[bool | None, dict[str, Any]]:
    return _quality_statistically_noninferior(_metric_group(treatment, "quality"))


def _performance_statistical_gate_flag(treatment: dict[str, Any]) -> tuple[bool | None, dict[str, Any]]:
    return _performance_statistically_noninferior(_metric_group(treatment, "system"))


def _evaluate_flag_gate_treatment(
    treatment: dict[str, Any],
    *,
    index: int,
    required_true: dict[str, str],
    required_false: dict[str, str] | None = None,
    success_code: str,
    success_message: str,
) -> dict[str, Any]:
    treatment_id = entity_id(treatment, default=f"treatment-{index}")
    reasons: list[dict[str, str]] = []

    for flag, message in required_true.items():
        value = status_flag(treatment, flag)
        if value is None:
            reasons.append(make_reason(f"missing_{flag}", f"Missing status flag '{flag}' for {treatment_id}."))
        elif value is not True:
            reasons.append(make_reason(flag, f"{treatment_id}: {message}."))

    for flag, message in (required_false or {}).items():
        value = status_flag(treatment, flag)
        if value is None:
            reasons.append(make_reason(f"missing_{flag}", f"Missing status flag '{flag}' for {treatment_id}."))
        elif value is not False:
            reasons.append(make_reason(flag, f"{treatment_id}: {message}."))

    if any(reason["code"].startswith("missing_") for reason in reasons):
        judgment = "inconclusive"
        recommendation = "stop"
    elif reasons:
        judgment = "fail"
        recommendation = "stop"
    else:
        judgment = "pass"
        recommendation = "advance"
        reasons.append(make_reason(success_code, f"{treatment_id} {success_message}"))

    return {
        "git_ref": treatment_id,
        "judgment": judgment,
        "recommendation": recommendation,
        "reasons": reasons,
    }


def _build_stage_result(
    *,
    decisions: list[dict[str, Any]],
    success_summary: str,
    failure_summary: str,
) -> dict[str, Any]:
    selected = [decision["git_ref"] for decision in decisions if decision["judgment"] == "pass"]
    return {
        "stage_judgment": aggregate_stage_judgment(decision["judgment"] for decision in decisions),
        "rollout_recommendation": "advance" if selected else "stop",
        "selected_treatments": selected,
        "summary": summarize_counts("treatments", passed=len(selected), total=len(decisions)),
        "reasons": [
            make_reason(
                "treatment_advance" if selected else "no_treatment_passed",
                success_summary if selected else failure_summary,
            )
        ],
        "arm_decisions": decisions,
    }


def _evaluate_encode_parity_treatment(treatment: dict[str, Any], *, index: int) -> dict[str, Any]:
    return _evaluate_flag_gate_treatment(
        treatment,
        index=index,
        required_true={
            "jobs_complete": "encode jobs did not complete successfully",
            "output_equivalent": "encode output equivalence failed",
        },
        success_code="encode_parity_ok",
        success_message="passed the encode parity gate.",
    )


def _evaluate_audit_parity_treatment(treatment: dict[str, Any], *, index: int) -> dict[str, Any]:
    return _evaluate_flag_gate_treatment(
        treatment,
        index=index,
        required_true={
            "jobs_complete": "audit jobs did not complete successfully",
            "output_equivalent": "audit output equivalence failed",
        },
        success_code="audit_parity_ok",
        success_message="passed the audit parity gate.",
    )


def _parity_control_jobs_complete(payload: dict[str, Any]) -> bool | None:
    control = payload.get("control")
    if not isinstance(control, dict):
        return None
    return status_flag(control, "jobs_complete")


def _evaluate_control_failure_parity_treatment(
    treatment: dict[str, Any],
    *,
    index: int,
    stage_label: str,
) -> dict[str, Any]:
    return _evaluate_flag_gate_treatment(
        treatment,
        index=index,
        required_true={
            "matched_control_failure": f"did not match the control failure behavior during {stage_label}",
        },
        required_false={
            "jobs_complete": f"completed successfully while the control failed during {stage_label}",
        },
        success_code="matched_control_failure",
        success_message="matched the control failure behavior.",
    )


def evaluate_encode_parity(payload: dict[str, Any]) -> dict[str, Any]:
    control_jobs_complete = _parity_control_jobs_complete(payload)
    treatments = require_treatments(payload)
    if control_jobs_complete is None:
        decisions = [
            {
                "git_ref": entity_id(treatment, default=f"treatment-{index}"),
                "judgment": "inconclusive",
                "recommendation": "stop",
                "reasons": [
                    make_reason(
                        "missing_control_jobs_complete",
                        "Missing status flag 'jobs_complete' for control.",
                    )
                ],
            }
            for index, treatment in enumerate(treatments, start=1)
        ]
    elif control_jobs_complete is False:
        decisions = [
            _evaluate_control_failure_parity_treatment(treatment, index=index, stage_label="encode_parity")
            for index, treatment in enumerate(treatments, start=1)
        ]
    else:
        decisions = [
            _evaluate_encode_parity_treatment(treatment, index=index)
            for index, treatment in enumerate(treatments, start=1)
        ]
    return _build_stage_result(
        decisions=decisions,
        success_summary=summarize_counts(
            "treatments", passed=sum(d["judgment"] == "pass" for d in decisions), total=len(decisions)
        ),
        failure_summary="No treatment cleared the encode parity gate.",
    )


def evaluate_audit_parity(payload: dict[str, Any]) -> dict[str, Any]:
    _require_parameter_source(payload, stage="audit_parity")
    control_jobs_complete = _parity_control_jobs_complete(payload)
    treatments = require_treatments(payload)
    if control_jobs_complete is None:
        decisions = [
            {
                "git_ref": entity_id(treatment, default=f"treatment-{index}"),
                "judgment": "inconclusive",
                "recommendation": "stop",
                "reasons": [
                    make_reason(
                        "missing_control_jobs_complete",
                        "Missing status flag 'jobs_complete' for control.",
                    )
                ],
            }
            for index, treatment in enumerate(treatments, start=1)
        ]
    elif control_jobs_complete is False:
        decisions = [
            _evaluate_control_failure_parity_treatment(treatment, index=index, stage_label="audit_parity")
            for index, treatment in enumerate(treatments, start=1)
        ]
    else:
        decisions = [
            _evaluate_audit_parity_treatment(treatment, index=index)
            for index, treatment in enumerate(treatments, start=1)
        ]
    return _build_stage_result(
        decisions=decisions,
        success_summary=summarize_counts(
            "treatments", passed=sum(d["judgment"] == "pass" for d in decisions), total=len(decisions)
        ),
        failure_summary="No treatment cleared the audit parity gate.",
    )


def _evaluate_system_performance_treatment(treatment: dict[str, Any], *, index: int) -> dict[str, Any]:
    treatment_id = entity_id(treatment, default=f"treatment-{index}")
    reasons: list[dict[str, str]] = []
    jobs_complete = status_flag(treatment, "jobs_complete")
    performance_spec_compatible = status_flag(treatment, "performance_spec_compatible")
    performance_statistically_noninferior, performance_statistical_tests = _performance_statistical_gate_flag(treatment)

    if jobs_complete is None:
        reasons.append(make_reason("missing_jobs_complete", f"Missing status flag 'jobs_complete' for {treatment_id}."))
    elif jobs_complete is not True:
        reasons.append(
            make_reason("jobs_complete", f"{treatment_id}: system performance jobs did not complete successfully.")
        )

    if performance_spec_compatible is None:
        reasons.append(
            make_reason(
                "missing_performance_spec_compatible",
                f"Missing committed performance-spec compatibility result for {treatment_id}.",
            )
        )
    elif performance_spec_compatible is not True:
        reasons.append(
            make_reason(
                "performance_spec_compatible",
                f"{treatment_id}: committed performance-test specs are incompatible.",
            )
        )

    if performance_statistically_noninferior is None:
        reasons.append(
            make_reason(
                "missing_performance_statistical_noninferiority",
                f"Missing paired system metrics for statistical performance evaluation for {treatment_id}.",
            )
        )
    elif performance_statistically_noninferior is not True:
        reasons.append(
            make_reason(
                "performance_statistical_noninferiority",
                f"{treatment_id}: system performance showed a statistically supported regression.",
            )
        )

    if any(reason["code"].startswith("missing_") for reason in reasons):
        judgment = "inconclusive"
        recommendation = "stop"
    elif reasons:
        judgment = "fail"
        recommendation = "stop"
    else:
        judgment = "pass"
        recommendation = "advance"
        reasons.append(make_reason("system_performance_ok", f"{treatment_id} passed the system performance gate."))

    decision = {
        "git_ref": treatment_id,
        "judgment": judgment,
        "recommendation": recommendation,
        "reasons": reasons,
        "performance_spec_compatible": performance_spec_compatible,
        "performance_statistically_noninferior": performance_statistically_noninferior,
        "performance_statistical_tests": performance_statistical_tests,
    }
    decision.update(
        {
            "p99_ms": metric_value(treatment, "p99_ms", default=None),
            "throughput_rps": metric_value(treatment, "throughput_rps", default=None),
            "peak_rss_gib": metric_value(treatment, "peak_rss_gib", default=None),
        }
    )
    return decision


def evaluate_system_performance(payload: dict[str, Any]) -> dict[str, Any]:
    _require_parameter_source(payload, stage="system_performance")
    treatments = require_treatments(payload)
    decisions = [
        _evaluate_system_performance_treatment(treatment, index=index)
        for index, treatment in enumerate(treatments, start=1)
    ]
    return _build_stage_result(
        decisions=decisions,
        success_summary=summarize_counts(
            "treatments", passed=sum(d["judgment"] == "pass" for d in decisions), total=len(decisions)
        ),
        failure_summary="No treatment cleared the system performance gate.",
    )


def _evaluate_predict_parity_treatment(treatment: dict[str, Any], *, index: int) -> dict[str, Any]:
    return _evaluate_flag_gate_treatment(
        treatment,
        index=index,
        required_true={
            "jobs_complete": "predict jobs did not complete successfully",
            "output_equivalent": "predict output equivalence failed for the pinned parameter source",
        },
        success_code="predict_parity_ok",
        success_message="passed the predict parity gate.",
    )


def evaluate_predict_parity(payload: dict[str, Any]) -> dict[str, Any]:
    _require_parameter_source(payload, stage="predict_parity")
    control_jobs_complete = _parity_control_jobs_complete(payload)
    treatments = require_treatments(payload)
    if control_jobs_complete is None:
        decisions = [
            {
                "git_ref": entity_id(treatment, default=f"treatment-{index}"),
                "judgment": "inconclusive",
                "recommendation": "stop",
                "reasons": [
                    make_reason(
                        "missing_control_jobs_complete",
                        "Missing status flag 'jobs_complete' for control.",
                    )
                ],
            }
            for index, treatment in enumerate(treatments, start=1)
        ]
    elif control_jobs_complete is False:
        decisions = [
            _evaluate_control_failure_parity_treatment(treatment, index=index, stage_label="predict_parity")
            for index, treatment in enumerate(treatments, start=1)
        ]
    else:
        decisions = [
            _evaluate_predict_parity_treatment(treatment, index=index)
            for index, treatment in enumerate(treatments, start=1)
        ]
    return _build_stage_result(
        decisions=decisions,
        success_summary=summarize_counts(
            "treatments", passed=sum(d["judgment"] == "pass" for d in decisions), total=len(decisions)
        ),
        failure_summary="No treatment cleared the predict parity gate.",
    )


def _evaluate_realistic_single_day_treatment(treatment: dict[str, Any], *, index: int) -> dict[str, Any]:
    treatment_id = entity_id(treatment, default=f"treatment-{index}")
    reasons: list[dict[str, str]] = []
    required_true = {
        "jobs_complete": "jobs did not complete successfully",
        "packaging_ok": "packaging did not succeed cleanly",
        "data_shape_ok": "data shape validation failed",
        "runtime_ok": "runtime validation failed",
    }
    for flag, message in required_true.items():
        value = status_flag(treatment, flag)
        if value is None:
            reasons.append(make_reason(f"missing_{flag}", f"Missing status flag '{flag}' for {treatment_id}."))
        elif value is not True:
            reasons.append(make_reason(flag, f"{treatment_id}: {message}."))

    if any(reason["code"].startswith("missing_") for reason in reasons):
        judgment = "inconclusive"
        recommendation = "stop"
    elif reasons:
        judgment = "fail"
        recommendation = "stop"
    else:
        judgment = "pass"
        recommendation = "advance"
        reasons.append(make_reason("realistic_single_day_ok", f"{treatment_id} passed the realistic single-day gate."))

    return {
        "git_ref": treatment_id,
        "judgment": judgment,
        "recommendation": recommendation,
        "reasons": reasons,
    }


def evaluate_realistic_single_day(payload: dict[str, Any]) -> dict[str, Any]:
    treatments = require_treatments(payload)
    decisions = [
        _evaluate_realistic_single_day_treatment(treatment, index=index)
        for index, treatment in enumerate(treatments, start=1)
    ]
    return _build_stage_result(
        decisions=decisions,
        success_summary=summarize_counts(
            "treatments", passed=sum(d["judgment"] == "pass" for d in decisions), total=len(decisions)
        ),
        failure_summary="No treatment cleared the realistic single-day gate.",
    )


def _evaluate_multi_day_treatment(treatment: dict[str, Any], *, index: int) -> dict[str, Any]:
    treatment_id = entity_id(treatment, default=f"treatment-{index}")
    reasons: list[dict[str, str]] = []

    jobs_complete = status_flag(treatment, "jobs_complete")
    quality_statistically_noninferior, quality_statistical_tests = _quality_statistical_gate_flag(treatment)
    performance_statistically_noninferior, performance_statistical_tests = _performance_statistical_gate_flag(treatment)
    performance_spec_compatible = status_flag(treatment, "performance_spec_compatible")
    has_semantic_shortcuts = status_flag(treatment, "has_semantic_shortcuts")

    if jobs_complete is None:
        reasons.append(make_reason("missing_jobs_complete", f"Missing status flag 'jobs_complete' for {treatment_id}."))
    elif jobs_complete is not True:
        reasons.append(make_reason("jobs_complete", f"{treatment_id}: jobs did not complete successfully."))

    if quality_statistically_noninferior is None:
        reasons.append(
            make_reason(
                "missing_quality_statistical_noninferiority",
                f"Missing paired quality metrics for statistical noninferiority evaluation for {treatment_id}.",
            )
        )
    elif quality_statistically_noninferior is not True:
        reasons.append(
            make_reason(
                "quality_statistical_noninferiority",
                f"{treatment_id}: offline quality did not pass the paired noninferiority test.",
            )
        )

    if performance_statistically_noninferior is None:
        reasons.append(
            make_reason(
                "missing_performance_statistical_noninferiority",
                f"Missing paired system metrics for statistical performance evaluation for {treatment_id}.",
            )
        )
    elif performance_statistically_noninferior is not None and performance_statistically_noninferior is not True:
        reasons.append(
            make_reason(
                "performance_statistical_noninferiority",
                f"{treatment_id}: performance showed a statistically supported regression.",
            )
        )

    if performance_spec_compatible is None:
        reasons.append(
            make_reason(
                "missing_performance_spec_compatible",
                f"Missing committed performance-spec compatibility result for {treatment_id}.",
            )
        )
    elif performance_spec_compatible is not True:
        reasons.append(
            make_reason(
                "performance_spec_compatible",
                f"{treatment_id}: committed performance-test specs are incompatible.",
            )
        )

    if has_semantic_shortcuts is None:
        reasons.append(
            make_reason(
                "missing_has_semantic_shortcuts",
                f"Missing status flag 'has_semantic_shortcuts' for {treatment_id}.",
            )
        )
    elif has_semantic_shortcuts is not False:
        reasons.append(
            make_reason("has_semantic_shortcuts", f"{treatment_id}: smoke-only semantic shortcuts were present.")
        )

    if any(reason["code"].startswith("missing_") for reason in reasons):
        judgment = "inconclusive"
        recommendation = "stop"
    elif reasons:
        judgment = "fail"
        recommendation = "stop"
    else:
        judgment = "pass"
        recommendation = "advance"
        reasons.append(make_reason("offline_gate_ok", f"{treatment_id} cleared the authoritative offline gate."))

    return {
        "git_ref": treatment_id,
        "judgment": judgment,
        "recommendation": recommendation,
        "reasons": reasons,
        "quality_statistically_noninferior": quality_statistically_noninferior,
        "quality_statistical_tests": quality_statistical_tests,
        "performance_statistically_noninferior": performance_statistically_noninferior,
        "performance_statistical_tests": performance_statistical_tests,
        "performance_spec_compatible": performance_spec_compatible,
        "offline_quality": metric_value(treatment, "offline_quality", default=None),
        "performance": metric_value(treatment, "performance", default=None),
    }


def evaluate_multi_day_backtest(payload: dict[str, Any]) -> dict[str, Any]:
    treatments = require_treatments(payload)
    raw_decisions = [
        _evaluate_multi_day_treatment(treatment, index=index) for index, treatment in enumerate(treatments, start=1)
    ]
    passers = [decision for decision in raw_decisions if decision["judgment"] == "pass"]
    ranked_passers = sort_treatments_by_rank(
        [treatment for treatment in treatments if entity_id(treatment, default="") in {d["git_ref"] for d in passers}]
    )
    selected = [entity_id(treatment, default="") for treatment in ranked_passers]
    stage_judgment = aggregate_stage_judgment(decision["judgment"] for decision in raw_decisions)

    online_policy_known = bool(payload.get("context", {}).get("online_policy_known"))
    if selected and online_policy_known:
        recommendation = "ready_for_online"
        summary = f"{len(selected)}/{len(raw_decisions)} treatments passed; best treatment is {selected[0]}."
        reasons = [
            make_reason("ready_for_online", "At least one treatment passed and an online policy is available."),
            make_reason("ranking", f"Treatments were ranked by offline_quality then performance: {selected}."),
        ]
    elif selected:
        recommendation = "advance"
        summary = f"{len(selected)}/{len(raw_decisions)} treatments passed the offline gate."
        reasons = [
            make_reason("offline_gate_passed", "At least one treatment passed the authoritative offline gate."),
            make_reason(
                "online_policy_missing", "No online policy was resolved, so progression stops at offline validation."
            ),
        ]
    else:
        recommendation = "stop"
        summary = "No treatment passed the authoritative offline gate."
        reasons = [make_reason("no_treatment_passed", summary)]

    return {
        "stage_judgment": stage_judgment,
        "rollout_recommendation": recommendation,
        "selected_treatments": selected,
        "summary": summary,
        "reasons": reasons,
        "arm_decisions": raw_decisions,
    }


def get_stage_handlers():
    return {
        "audit_parity": evaluate_audit_parity,
        "encode_parity": evaluate_encode_parity,
        "system_performance": evaluate_system_performance,
        "predict_parity": evaluate_predict_parity,
        "realistic_single_day": evaluate_realistic_single_day,
        "multi_day_backtest": evaluate_multi_day_backtest,
    }
