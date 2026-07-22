import datetime
from collections import defaultdict
from typing import Any

from hotvect.evaluation.data_models import MetricEstimate, MetricEvaluationResult

_EVALUATION_METRIC_NAMES = (
    "roc_auc",
    "pr_auc",
    "mean_score",
    "map_at_10",
    "map_at_50",
    "map_at_100",
    "map_at_all",
    "ndcg_at_10",
    "ndcg_at_50",
    "ndcg_at_100",
    "ndcg_at_all",
    "diversity@5",
    "diversity@10",
    "diversity@30",
)


def _split_algorithm_id(algorithm_id: str, *, context: str) -> tuple[str, str]:
    if "@" not in algorithm_id:
        raise ValueError(f"{context} algorithm_id '{algorithm_id}' is missing '@'.")
    algorithm_name, algorithm_version = algorithm_id.split("@", 1)
    if not algorithm_name or not algorithm_version:
        raise ValueError(f"{context} algorithm_id '{algorithm_id}' must be '<name>@<version>'.")
    return algorithm_name, algorithm_version


def _validate_algorithm_definition_identity(result_dict: dict[str, Any], *, claimed_algorithm_id: str) -> None:
    algorithm_definition = result_dict.get("algorithm_definition")
    if algorithm_definition is None:
        return
    if not isinstance(algorithm_definition, dict):
        raise ValueError("result.json has non-object algorithm_definition.")

    algorithm_name = algorithm_definition.get("algorithm_name")
    algorithm_version = algorithm_definition.get("algorithm_version")
    if algorithm_name is None and algorithm_version is None:
        return

    if not isinstance(algorithm_name, str) or not algorithm_name.strip():
        raise ValueError(f"result.json has invalid algorithm_definition.algorithm_name: {algorithm_name!r}")
    if not isinstance(algorithm_version, str) or not algorithm_version.strip():
        raise ValueError(f"result.json has invalid algorithm_definition.algorithm_version: {algorithm_version!r}")

    definition_algorithm_id = f"{algorithm_name.strip()}@{algorithm_version.strip()}"
    if definition_algorithm_id != claimed_algorithm_id:
        raise ValueError(
            f"result.json claims algorithm_id '{claimed_algorithm_id}' but "
            f"algorithm_definition implies '{definition_algorithm_id}'."
        )


def _extract_algorithm_id(result_dict: dict[str, Any]) -> str:
    algorithm_id = str(result_dict.get("algorithm_id") or "").strip()
    if not algorithm_id:
        raise ValueError("result.json is missing algorithm_id.")
    _split_algorithm_id(algorithm_id, context="result.json")
    _validate_algorithm_definition_identity(result_dict, claimed_algorithm_id=algorithm_id)
    return algorithm_id


def extract_evaluation(result_dict: dict[str, Any]) -> dict[str, Any] | None:
    performance_test = result_dict.get("performance_test", {})
    response_time_metrics = performance_test.get("response_time_metrics", {})
    evaluation = result_dict.get("evaluate", {})
    algorithm_id = _extract_algorithm_id(result_dict)

    response_time_metrics = {
        key: _get_nested_value(response_time_metrics, key + ".mean") for key in ["p50", "p75", "p95", "p99", "p999"]
    }

    evaluation_metrics = {}
    for key in _EVALUATION_METRIC_NAMES:
        value = extract_metric_value(evaluation, key)
        if value is not None:
            evaluation_metrics[key] = value

    online_evaluation_metrics = {}
    online_evaluation = evaluation.get("online", {})
    for dimension in online_evaluation.keys():
        for key in _EVALUATION_METRIC_NAMES:
            value = extract_metric_value(online_evaluation[dimension], key)
            if value is not None:
                online_evaluation_metrics[f"{dimension}.{key}"] = value

    if not any([response_time_metrics, evaluation_metrics, online_evaluation_metrics]):
        return None

    result = {
        "algorithm_id": algorithm_id,
        "test_date": datetime.datetime.fromisoformat(result_dict["test_data_time"]),
        "max_memory_usage": _get_nested_value(performance_test, "max_memory_usage"),
        "mean_throughput": _get_nested_value(performance_test, "mean_throughput"),
        **response_time_metrics,
        **evaluation_metrics,
        **online_evaluation_metrics,
    }

    return result


def repack_metrics(metrics: list[MetricEvaluationResult]) -> dict[str, Any]:
    """Repack metrics into a nested dictionary expected downstream."""

    online_metrics = defaultdict(dict)
    offline_metrics = {}
    for metric_result in metrics:
        if metric_result["is_online"]:
            online_metrics[metric_result.get("dimension")][metric_result["name"]] = metric_result["estimate"]
        else:
            offline_metrics[metric_result["name"]] = metric_result["estimate"]

    repacked_metrics = offline_metrics.copy()
    if online_metrics:
        repacked_metrics["online"] = dict(online_metrics)
    return repacked_metrics


def _get_nested_value(d: dict[str, Any], keys: str, default: Any = None) -> Any:
    """Look up from a nested dictionary using dot-separated keys."""

    for key in keys.split("."):
        if isinstance(d, dict):
            d = d.get(key, {})
        else:
            return default
    return d if d != {} else default


def extract_metric_value(d: dict[str, Any], key: str) -> MetricEstimate | None:
    """
    Extract a metric value from supported result shapes.

    Supports:
    - Structured estimate: {"roc_auc": {"value": 0.8, "ci95_lower": ..., "ci95_upper": ...}} → as is
    - Legacy flat numeric: {"roc_auc": 0.8} → {"value": 0.8}
    - Legacy mean wrapper: {"roc_auc": {"mean": 0.8}} → {"value": 0.8}
    """

    value = _get_nested_value(d, key)
    if isinstance(value, (int, float)):
        return MetricEstimate(value=float(value))

    if isinstance(value, dict):
        estimate_value = value.get("value")
        if isinstance(estimate_value, (int, float)):
            # Already in MetricEstimate format, return as-is with all fields
            result: MetricEstimate = {"value": float(estimate_value)}
            if "ci95_lower" in value or "ci95_upper" in value:
                result["ci95_lower"] = float(value["ci95_lower"])
                result["ci95_upper"] = float(value["ci95_upper"])
            return result

        # Try legacy mean wrapper
        mean_value = value.get("mean")
        if isinstance(mean_value, (int, float)):
            return MetricEstimate(value=float(mean_value))

    return None
