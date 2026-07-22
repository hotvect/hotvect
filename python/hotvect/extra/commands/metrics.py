"""Metrics subcommands for hv-ext CLI."""

from __future__ import annotations

import glob
import json
import math
import os
import re
import statistics
import sys
import textwrap
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path
from typing import Any

from scipy.stats import t as student_t

from hotvect.backtest import list_output_dirs
from hotvect.evaluation.conversion import extract_evaluation, extract_metric_value
from hotvect.evaluation.data_models import MetricEstimate

from .base import BaseCommand

QUALITY_METRICS = (
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

SYSTEM_METRICS = (
    "max_memory_usage",
    "mean_throughput",
    "mean",
    "p50",
    "p75",
    "p95",
    "p99",
    "p999",
)

DIFFERENCE_RELATIVE_METRICS = {
    "roc_auc",
    "pr_auc",
}

RELATIVE_EXCLUDED_METRICS = {
    "mean_score",
}

DEFAULT_EXPORT_METRICS = (
    "roc_auc",
    "pr_auc",
    "map_at_50",
    "ndcg_at_50",
    "p50",
    "p75",
    "p95",
    "p99",
    "p999",
)

_NON_METRIC_KEYS = {
    "algorithm_id",
    "algorithm_definition",
    "benchmark_specification",
    "cache_usage",
    "dependencies",
    "evaluation_policy",
    "missing_reward",
    "source_parameter_uri",
    "source_result_file",
    "source_result_uri",
    "source_sagemaker_job_name",
    "test_date",
    "timing_info_sec",
    "version",
    "parameter_version",
    "hyperparameter_version",
}

PLOTS_PER_PAGE = 4
PLOTS_PER_ROW = 2

_BENCHMARK_REQUEST_FIELDS = {
    "requested_samples": "samples",
    "requested_sample_pool_size": "sample_pool_size",
    "requested_target_rps": "target_rps",
    "requested_target_throughput_fraction": "target_throughput_fraction",
    "requested_workload_mode": "workload_mode",
}

_BENCHMARK_CONTRACT_FIELDS = {
    "instance_type": "instance_type",
    "max_threads": "max_threads",
    "samples": "samples",
    "sample_pool_size": "sample_pool_size",
    "target_rps": "target_rps",
    "target_throughput_fraction": "target_throughput_fraction",
    "workload_mode": "workload_mode",
}

_TIMING_STAGE_ORDER = {
    "total_pipeline": 0,
    "prepare_dependencies": 10,
    "overhead": 11,
    "dependency_overhead": 12,
    "encode_parameter": 20,
    "package_encode_params": 21,
    "encode": 30,
    "train": 40,
    "package_predict_params": 50,
    "predict": 60,
    "evaluate": 70,
    "performance_test": 80,
    "encode_test": 90,
    "audit": 100,
    "unaccounted": 2000,
}

_PRODUCTION_TRAINING_TIMING_STAGES = {
    "overhead",
    "dependency_overhead",
    "encode_parameter",
    "package_encode_params",
    "encode",
    "train",
    "package_predict_params",
    "unaccounted",
}


@dataclass(frozen=True)
class RelativeBaselineSpec:
    kind: str
    value: str

    @property
    def display_name(self) -> str:
        if self.kind == "online":
            return f"online:{self.value}"
        return self.value

    @property
    def version_selector(self) -> str:
        if self.kind == "online":
            return "online"
        return self.value


@dataclass
class PlotDataset:
    df: Any
    table_rows: list[dict[str, Any]]
    metrics: list[str]
    versions: list[str]
    baseline: RelativeBaselineSpec


@dataclass
class TimingPlotDataset:
    df: Any
    metrics: list[str]


@dataclass
class TimingBreakdownDataset:
    df: Any
    components: list[str]


@dataclass(frozen=True)
class AlgorithmSpecification:
    algorithm_id: str
    hotvect_version: str | None
    git_describe: str | None
    git_commit: str | None
    algorithm_parameters: dict[str, Any] | None


@dataclass(frozen=True)
class CommonSpecificationResult:
    specification: dict[str, Any]
    mismatch_summary: str | None


def _parse_test_date(value: Any) -> date:
    if isinstance(value, date) and not isinstance(value, datetime):
        return value
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, str):
        try:
            return date.fromisoformat(value)
        except ValueError:
            return datetime.fromisoformat(value).date()
    raise ValueError(f"Unsupported test_date value: {value!r}")


def _version_from_algorithm_id(algorithm_id: str) -> str:
    if "@" not in algorithm_id:
        return algorithm_id
    return algorithm_id.split("@", 1)[1]


def _extract_run_metadata(result_dict: dict[str, Any]) -> dict[str, str | None]:
    algorithm_definition = result_dict.get("algorithm_definition")
    hyperparameter_version: str | None = None
    if isinstance(algorithm_definition, dict):
        raw_hyperparameter_version = algorithm_definition.get("hyperparameter_version")
        if isinstance(raw_hyperparameter_version, str) and raw_hyperparameter_version:
            hyperparameter_version = raw_hyperparameter_version

    raw_parameter_version = result_dict.get("parameter_version")
    parameter_version = (
        raw_parameter_version if isinstance(raw_parameter_version, str) and raw_parameter_version else None
    )

    return {
        "parameter_version": parameter_version,
        "hyperparameter_version": hyperparameter_version,
    }


def _git_commit_from_git_describe(git_describe: str | None) -> str | None:
    if not git_describe:
        return None
    match = re.search(r"-g([0-9a-fA-F]+)(?:-dirty)?$", git_describe)
    if not match:
        return None
    return match.group(1)


def _report_hotvect_version() -> str:
    pyproject_path = Path(__file__).resolve().parents[3] / "pyproject.toml"
    if pyproject_path.is_file():
        match = re.search(r'^version = "([^"]+)"$', pyproject_path.read_text(), re.MULTILINE)
        if match:
            return match.group(1)

    try:
        import hotvect  # type: ignore

        version = getattr(hotvect, "__version__", None)
        if isinstance(version, str) and version and version != "unknown":
            return version
    except Exception:
        pass

    return "unknown"


def _json_key(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), default=str)


def _require_consistent(values: Sequence[Any], *, label: str) -> Any:
    if not values:
        return None
    unique_values = {_json_key(value): value for value in values}
    if len(unique_values) > 1:
        rendered = ", ".join(sorted(unique_values.keys()))
        raise ValueError(f"{label} differs across plotted records: {rendered}")
    return next(iter(unique_values.values()))


def _common_specification_result(specs: Sequence[dict[str, Any]], *, label: str) -> CommonSpecificationResult:
    if not specs:
        raise ValueError(f"No records available for {label} comparison")
    unique_specs = {_json_key(spec): spec for spec in specs}
    if len(unique_specs) > 1:
        return CommonSpecificationResult(specification={}, mismatch_summary=", ".join(sorted(unique_specs.keys())))
    return CommonSpecificationResult(specification=next(iter(unique_specs.values())) or {}, mismatch_summary=None)


def _copy_result_metadata(record: dict[str, Any], result_dict: dict[str, Any]) -> None:
    result_uri = result_dict.get("s3_uri_result_file")
    if isinstance(result_uri, str) and result_uri:
        record["source_result_uri"] = result_uri

    for field in ("sagemaker_job_name", "sagemaker_training_job_name", "training_job_name", "job_name"):
        value = result_dict.get(field)
        if isinstance(value, str) and value:
            record["source_sagemaker_job_name"] = value
            break

    algorithm_definition = result_dict.get("algorithm_definition")
    if isinstance(algorithm_definition, dict):
        record["algorithm_definition"] = algorithm_definition

        execution_parameters = algorithm_definition.get("hotvect_execution_parameters")
        if isinstance(execution_parameters, dict):
            parameter_uri = execution_parameters.get("with_parameter")
            if isinstance(parameter_uri, str) and parameter_uri:
                record["source_parameter_uri"] = parameter_uri

        training_job_definition = algorithm_definition.get("sagemaker_training_job_definition")
        if isinstance(training_job_definition, dict) and not record.get("source_sagemaker_job_name"):
            training_job_name = training_job_definition.get("TrainingJobName")
            if isinstance(training_job_name, str) and training_job_name:
                record["source_sagemaker_job_name"] = training_job_name

    evaluation = result_dict.get("evaluate")
    if isinstance(evaluation, dict):
        evaluation_policy = evaluation.get("evaluation_policy")
        if isinstance(evaluation_policy, dict):
            record["evaluation_policy"] = evaluation_policy

        missing_reward = evaluation.get("missing_reward")
        if isinstance(missing_reward, dict):
            record["missing_reward"] = missing_reward

    benchmark_specification = _benchmark_specification_from_result(result_dict)
    if benchmark_specification:
        record["benchmark_specification"] = benchmark_specification

    timing_info = result_dict.get("timing_info_sec")
    if isinstance(timing_info, dict):
        record["timing_info_sec"] = timing_info
        for stage in timing_info:
            stage_result = result_dict.get(stage)
            if isinstance(stage_result, dict) and stage_result.get("skipped"):
                record[str(stage)] = {"skipped": stage_result["skipped"]}

    dependencies = result_dict.get("dependencies")
    if isinstance(dependencies, dict):
        record["dependencies"] = dependencies

    cache_usage = _cache_usage_from_result(result_dict)
    if cache_usage:
        record["cache_usage"] = cache_usage


def _benchmark_specification_from_result(result_dict: dict[str, Any]) -> dict[str, Any]:
    specification: dict[str, Any] = {}

    algorithm_definition = result_dict.get("algorithm_definition")
    if isinstance(algorithm_definition, dict):
        execution_parameters = algorithm_definition.get("hotvect_execution_parameters")
        if isinstance(execution_parameters, dict):
            performance_test = execution_parameters.get("performance-test")
            if isinstance(performance_test, dict):
                specification["performance_test"] = performance_test
        performance_data_spec = algorithm_definition.get("performance_data_spec")
        if isinstance(performance_data_spec, dict):
            specification["performance_data"] = performance_data_spec

    performance_result = result_dict.get("performance_test")
    if isinstance(performance_result, dict):
        requested_parameters = {
            output_key: performance_result[input_key]
            for input_key, output_key in _BENCHMARK_REQUEST_FIELDS.items()
            if performance_result.get(input_key) is not None
        }
        if requested_parameters:
            specification["requested"] = requested_parameters
        performance_input_kind = performance_result.get("performance_input_kind")
        if isinstance(performance_input_kind, str) and performance_input_kind:
            specification["input"] = {"kind": performance_input_kind}
        skipped = performance_result.get("skipped")
        if isinstance(skipped, str) and skipped:
            specification["status"] = {"skipped": skipped}

    benchmark_contract = result_dict.get("benchmark_contract")
    if isinstance(benchmark_contract, dict):
        contract_parameters = {
            output_key: benchmark_contract[input_key]
            for input_key, output_key in _BENCHMARK_CONTRACT_FIELDS.items()
            if benchmark_contract.get(input_key) is not None
        }
        if contract_parameters:
            specification["contract"] = contract_parameters

    return specification


def _cache_configuration_from_result(result_dict: dict[str, Any]) -> dict[str, Any]:
    algorithm_definition = result_dict.get("algorithm_definition")
    if not isinstance(algorithm_definition, dict):
        return {}

    execution_parameters = algorithm_definition.get("hotvect_execution_parameters")
    if not isinstance(execution_parameters, dict):
        return {}

    configuration = {
        key: execution_parameters[key]
        for key in ("cache_base_dir", "cache_scope", "cache_refresh")
        if execution_parameters.get(key) is not None
    }
    return configuration


def _cache_sources_from_stage(stage_result: dict[str, Any]) -> list[str]:
    sources: list[str] = []
    raw_sources = stage_result.get("sources")
    if isinstance(raw_sources, list):
        sources.extend(str(source) for source in raw_sources if isinstance(source, str) and source)

    raw_source = stage_result.get("source")
    if isinstance(raw_source, str) and raw_source:
        sources.append(raw_source)

    skipped = stage_result.get("skipped")
    if isinstance(skipped, str):
        for marker in (" at: ", " at ", " in "):
            if marker in skipped:
                candidate = skipped.rsplit(marker, 1)[1].strip()
                if candidate.startswith(("s3://", "/", "file:")):
                    sources.append(candidate)
                break

    return sorted(dict.fromkeys(sources))


def _cache_hits_from_result(result_dict: dict[str, Any], prefix: str = "") -> list[dict[str, Any]]:
    hits: list[dict[str, Any]] = []
    for stage, stage_result in result_dict.items():
        if stage == "dependencies" and isinstance(stage_result, dict):
            for dependency_name in sorted(stage_result):
                dependency_result = stage_result[dependency_name]
                if not isinstance(dependency_result, dict) or dependency_result.get("skipped"):
                    continue
                dependency_prefix = _format_timing_metric(prefix, f"dependencies.{dependency_name}")
                hits.extend(_cache_hits_from_result(dependency_result, dependency_prefix))
            continue

        if not isinstance(stage_result, dict):
            continue
        skipped = stage_result.get("skipped")
        if not isinstance(skipped, str) or "cache" not in skipped.lower():
            continue
        hits.append(
            {
                "stage": _format_timing_metric(prefix, stage),
                "skipped": skipped,
                "sources": _cache_sources_from_stage(stage_result),
            }
        )
    return hits


def _cache_usage_from_result(result_dict: dict[str, Any]) -> dict[str, Any]:
    configuration = _cache_configuration_from_result(result_dict)
    hits = _cache_hits_from_result(result_dict)
    if not configuration and not hits:
        return {}
    return {
        "configuration": configuration,
        "hits": hits,
    }


def _has_cache_hits(records: Sequence[dict[str, Any]]) -> bool:
    for record in records:
        cache_usage = record.get("cache_usage")
        if not isinstance(cache_usage, dict):
            continue
        hits = cache_usage.get("hits")
        if isinstance(hits, list) and hits:
            return True
    return False


def _source_sagemaker_job_from_path(path: str) -> str | None:
    segments = [segment for segment in path.replace("\\", "/").split("/") if segment]
    if len(segments) < 3 or segments[-1] != "result.json":
        return None
    if "@" not in segments[-2]:
        return None
    candidate = segments[-3]
    if candidate == "meta" or candidate.startswith("last_test_date_"):
        return None
    return candidate


def _copy_source_metadata(record: dict[str, Any], source_result_file: Path) -> None:
    source = str(source_result_file)
    record["source_result_file"] = source
    job_name = _source_sagemaker_job_from_path(source)
    if job_name and not record.get("source_sagemaker_job_name"):
        record["source_sagemaker_job_name"] = job_name


def _algorithm_specification_for_algorithm(
    records: Sequence[dict[str, Any]], algorithm_id: str
) -> AlgorithmSpecification:
    matching_definitions = [
        record.get("algorithm_definition")
        for record in records
        if str(record.get("algorithm_id")) == algorithm_id and isinstance(record.get("algorithm_definition"), dict)
    ]
    if not matching_definitions:
        return AlgorithmSpecification(
            algorithm_id=algorithm_id,
            hotvect_version=None,
            git_describe=None,
            git_commit=None,
            algorithm_parameters=None,
        )

    hotvect_version = _require_consistent(
        [
            definition.get("hotvect_version")
            for definition in matching_definitions
            if isinstance(definition.get("hotvect_version"), str) and definition.get("hotvect_version")
        ],
        label=f"{algorithm_id} hotvect version",
    )
    git_describe = _require_consistent(
        [
            definition.get("git_describe")
            for definition in matching_definitions
            if isinstance(definition.get("git_describe"), str) and definition.get("git_describe")
        ],
        label=f"{algorithm_id} git describe",
    )
    explicit_git_commit = _require_consistent(
        [
            definition.get("git_commit") or definition.get("git_hash")
            for definition in matching_definitions
            if isinstance(definition.get("git_commit") or definition.get("git_hash"), str)
            and (definition.get("git_commit") or definition.get("git_hash"))
        ],
        label=f"{algorithm_id} git commit",
    )
    algorithm_parameters = _require_consistent(
        [
            definition.get("algorithm_parameters")
            for definition in matching_definitions
            if isinstance(definition.get("algorithm_parameters"), dict)
        ],
        label=f"{algorithm_id} algorithm parameters",
    )

    return AlgorithmSpecification(
        algorithm_id=algorithm_id,
        hotvect_version=hotvect_version if isinstance(hotvect_version, str) else None,
        git_describe=git_describe if isinstance(git_describe, str) else None,
        git_commit=(
            explicit_git_commit
            if isinstance(explicit_git_commit, str)
            else _git_commit_from_git_describe(git_describe if isinstance(git_describe, str) else None)
        ),
        algorithm_parameters=algorithm_parameters if isinstance(algorithm_parameters, dict) else None,
    )


def _require_numeric(value: Any, label: str) -> float:
    if isinstance(value, (int, float)):
        return float(value)
    raise ValueError(f"Non-numeric value for {label}: {value!r}")


def _extract_central_value(value: Any) -> float | None:
    """
    Attempt to extract the central value from what may be a MetricEstimate.

    Return None if value does not look like a MetricEstimate.
    """

    if isinstance(value, dict) and "value" in value:
        central = value.get("value")
        if isinstance(central, (int, float)):
            return float(central)
    return None


def _combine_gaussian_uncertainties(per_date_uncertainties: Sequence[tuple[float, float]]) -> float:
    """Combine per-date (unc_down, unc_up) pairs into a single uncertainty on the mean.

    Use Gaussian approximation and assume different dates contribute with the same weight.
    """
    n = len(per_date_uncertainties)
    if n == 0:
        return 0.0
    sum_sq = sum(((d + u) / 2) ** 2 for d, u in per_date_uncertainties)
    combined = math.sqrt(sum_sq) / n
    return combined


def _load_result_json(path: Path) -> dict[str, Any]:
    raw = json.loads(path.read_text())
    if not isinstance(raw, dict):
        raise ValueError(f"result.json root is not an object: {path}")
    return raw


def _extract_metrics_from_nested(obj: Any, prefix: str = "") -> dict[str, MetricEstimate]:
    metrics: dict[str, MetricEstimate] = {}
    if not isinstance(obj, dict):
        return metrics

    for key, value in obj.items():
        full_key = f"{prefix}.{key}" if prefix else str(key)
        metric_estimate = extract_metric_value(obj, key)
        if metric_estimate is not None:
            metrics[full_key] = metric_estimate
        else:
            metrics.update(_extract_metrics_from_nested(value, full_key))
    return metrics


def _load_evaluation_file(file_path: str) -> dict[str, MetricEstimate]:
    """Load evaluation metrics from a result.json file."""

    path = Path(file_path)
    if not path.exists():
        raise FileNotFoundError(f"Evaluation file not found: {file_path}")

    with path.open("r") as f:
        data = json.load(f)

    if not isinstance(data, dict):
        raise ValueError(f"Evaluation JSON root is not an object: {file_path}")

    # Handle both backtest result.json format and direct evaluation metadata format
    if "evaluate" in data:
        return _extract_metrics_from_nested(data["evaluate"])
    if data.get("task") == "evaluate" and isinstance(data.get("task_metadata"), dict):
        return _extract_metrics_from_nested(data["task_metadata"])
    return _extract_metrics_from_nested(data)


def _extract_performance_block(data: Any) -> dict[str, Any]:
    if isinstance(data, dict):
        if "performance_test" in data:
            perf = data["performance_test"]
            if not isinstance(perf, dict):
                raise ValueError("performance_test must be a JSON object")
            if perf.get("skipped"):
                raise ValueError(f"Performance test was skipped: {perf.get('skipped')}")
            return perf
        if "performance-test" in data:
            perf = data["performance-test"]
            if not isinstance(perf, dict):
                raise ValueError("performance-test must be a JSON object")
            return perf
        if data.get("task") == "performance-test" and isinstance(data.get("task_metadata"), dict):
            return data["task_metadata"]
    if not isinstance(data, dict):
        raise ValueError("Performance data must be a JSON object")
    return data


def _normalize_performance(perf_data: dict[str, Any]) -> dict[str, Any]:
    response_time_metrics = perf_data.get("response_time_metrics") or {}
    if response_time_metrics and not isinstance(response_time_metrics, dict):
        raise ValueError("response_time_metrics must be a JSON object when present")

    def require_numeric(value: Any, name: str) -> float | None:
        if value is None:
            return None
        if isinstance(value, (int, float)):
            return float(value)
        raise ValueError(f"Non-numeric value for {name}: {value!r}")

    def metric_mean(metrics: dict[str, Any], key: str) -> float | None:
        if key not in metrics:
            return None
        value = metrics.get(key)
        if isinstance(value, dict):
            if "mean" not in value:
                raise ValueError(f"response_time_metrics.{key} missing mean")
            return require_numeric(value.get("mean"), f"response_time_metrics.{key}.mean")
        return require_numeric(value, f"response_time_metrics.{key}")

    mean_throughput: float | None
    if "mean_throughput" in perf_data:
        mean_throughput = require_numeric(perf_data.get("mean_throughput"), "mean_throughput")
    else:
        mean_throughput = metric_mean(response_time_metrics, "mean_throughput")

    normalized_rt: dict[str, dict[str, float]] = {}
    for metric in ["mean", "p50", "p75", "p95", "p99", "p999"]:
        value = metric_mean(response_time_metrics, metric)
        if value is None:
            key_ms = f"latency_{metric}_ms"
            key = f"latency_{metric}"
            if key_ms in perf_data:
                value = require_numeric(perf_data.get(key_ms), key_ms)
            elif key in perf_data:
                value = require_numeric(perf_data.get(key), key)
        if value is not None:
            normalized_rt[metric] = {"mean": float(value)}

    mean_throughput_rt = metric_mean(response_time_metrics, "mean_throughput")
    if mean_throughput_rt is not None:
        normalized_rt["mean_throughput"] = {"mean": float(mean_throughput_rt)}

    max_memory_usage: float | None = None
    if "max_memory_usage" in perf_data:
        max_memory_usage = require_numeric(perf_data.get("max_memory_usage"), "max_memory_usage")

    return {
        "max_memory_usage": max_memory_usage,
        "mean_throughput": mean_throughput,
        "response_time_metrics": normalized_rt,
    }


def _validate_normalized_performance(normalized: dict[str, Any], file_path: str) -> None:
    missing: list[str] = []
    if normalized.get("max_memory_usage") is None:
        missing.append("max_memory_usage")
    if normalized.get("mean_throughput") is None:
        missing.append("mean_throughput")

    rt = normalized.get("response_time_metrics") or {}
    required_rt = ["mean_throughput", "mean", "p50", "p75", "p95", "p99", "p999"]
    for metric in required_rt:
        if not isinstance(rt.get(metric), dict) or rt[metric].get("mean") is None:
            missing.append(f"response_time_metrics.{metric}.mean")

    if missing:
        missing_list = ", ".join(sorted(set(missing)))
        raise ValueError(f"Unsupported performance JSON format in {file_path}. Missing: {missing_list}")


def _load_performance_file(file_path: str) -> dict[str, Any]:
    path = Path(file_path)
    if not path.exists():
        raise FileNotFoundError(f"Performance file not found: {file_path}")

    with path.open("r") as f:
        data = json.load(f)

    perf_block = _extract_performance_block(data)
    normalized = _normalize_performance(perf_block)
    _validate_normalized_performance(normalized, file_path)
    return normalized


def _records_from_output_base_dir(
    output_base_dir: str,
    algorithm_name_pattern: str,
    algorithm_version_pattern: str,
    from_date: date | None,
    to_date: date | None,
) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for output_dir in list_output_dirs(
        output_base_dir=output_base_dir,
        algorithm_name_pattern=algorithm_name_pattern,
        algorithm_version_pattern=algorithm_version_pattern,
        from_including_test_date=from_date,
        to_including_test_date=to_date,
    ):
        result_json_path = output_dir / "result.json"
        if not result_json_path.is_file():
            continue
        raw = _load_result_json(result_json_path)
        extracted = extract_evaluation(raw)
        if extracted is None:
            continue
        if "algorithm_id" not in extracted or "test_date" not in extracted:
            raise ValueError("Missing algorithm_id/test_date in extracted record")
        normalized = dict(extracted)
        normalized["test_date"] = _parse_test_date(normalized["test_date"])
        normalized.update(_extract_run_metadata(raw))
        _copy_result_metadata(normalized, raw)
        _copy_source_metadata(normalized, result_json_path)
        _enrich_with_performance_metrics(normalized, result_json_path)
        records.append(normalized)
    return records


def _enrich_with_performance_metrics(record: dict[str, Any], result_json_path: Path) -> None:
    try:
        perf = _load_performance_file(str(result_json_path))
    except Exception:
        return

    if record.get("max_memory_usage") is None:
        record["max_memory_usage"] = perf.get("max_memory_usage")
    if record.get("mean_throughput") is None:
        record["mean_throughput"] = perf.get("mean_throughput")

    rt = perf.get("response_time_metrics") or {}
    for key in ["mean", "p50", "p75", "p95", "p99", "p999"]:
        if record.get(key) is None:
            metric = rt.get(key)
            if isinstance(metric, dict):
                record[key] = metric.get("mean")


def _records_from_result_files(result_files: Sequence[str]) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for file_path in result_files:
        path = Path(file_path)
        if not path.exists():
            raise FileNotFoundError(f"Result file not found: {file_path}")
        raw = _load_result_json(path)
        extracted = extract_evaluation(raw)
        if extracted is None:
            raise ValueError(f"No evaluation/performance metrics found in {file_path}")
        if "algorithm_id" not in extracted or "test_date" not in extracted:
            raise ValueError(f"Missing algorithm_id/test_date in {file_path}")
        extracted["test_date"] = _parse_test_date(extracted["test_date"])
        extracted.update(_extract_run_metadata(raw))
        _copy_result_metadata(extracted, raw)
        _copy_source_metadata(extracted, path)

        # Enrich/normalize system metrics using the same logic as hv-ext perf compare.
        # `extract_evaluation` does not always map throughput into `mean_throughput`.
        _enrich_with_performance_metrics(extracted, path)

        records.append(extracted)
    return records


def _is_metric_value(value: Any) -> bool:
    """Check if a value can be a MetricEstimate."""
    if isinstance(value, dict) and "value" in value:
        central = value.get("value")
        return isinstance(central, (int, float))
    return False


def _discover_metrics(records: Sequence[dict[str, Any]]) -> list[str]:
    candidates: set[str] = set()
    for record in records:
        for key, value in record.items():
            if key in _NON_METRIC_KEYS:
                continue
            if key.startswith("algorithm."):
                # `extract_evaluation` often includes duplicates like `algorithm.roc_auc`.
                continue
            if value is None:
                continue
            if isinstance(value, (int, float)) or _is_metric_value(value):
                candidates.add(key)

    if not candidates:
        return []

    discovered: list[str] = []
    for metric in sorted(candidates):
        ok = True
        for record in records:
            value = record.get(metric)
            if value is None or (not isinstance(value, (int, float)) and not _is_metric_value(value)):
                ok = False
                break
        if ok:
            discovered.append(metric)

    preferred_order = list(QUALITY_METRICS) + list(SYSTEM_METRICS)
    ordered: list[str] = [m for m in preferred_order if m in discovered]
    ordered.extend([m for m in discovered if m not in ordered])
    return ordered


def _select_algorithm_id(records: Sequence[dict[str, Any]], selector: str) -> str:
    algorithm_ids = sorted({r["algorithm_id"] for r in records})
    if "@" in selector:
        if selector not in algorithm_ids:
            raise ValueError(f"Requested id not found: {selector}. Available: {algorithm_ids}")
        return selector
    matches = [algo_id for algo_id in algorithm_ids if algo_id.endswith(f"@{selector}")]
    if not matches:
        raise ValueError(f"No algorithm_id matches version '{selector}'. Available: {algorithm_ids}")
    if len(matches) > 1:
        raise ValueError(f"Version '{selector}' is ambiguous. Matches: {matches}")
    return matches[0]


def _common_dates_by_version(records: Sequence[dict[str, Any]]) -> list[date]:
    by_version: dict[str, set[date]] = {}
    for record in records:
        version = _version_from_algorithm_id(record["algorithm_id"])
        by_version.setdefault(version, set()).add(record["test_date"])
    if not by_version:
        return []
    common = set.intersection(*by_version.values())
    return sorted(common)


def _filter_to_common_dates(records: Sequence[dict[str, Any]]) -> list[dict[str, Any]]:
    common_dates = _common_dates_by_version(records)
    if not common_dates:
        raise ValueError("No common test dates across versions.")
    allowed = set(common_dates)
    return [r for r in records if r["test_date"] in allowed]


def _aggregate_by_date(
    records: Sequence[dict[str, Any]],
    metric_names: Sequence[str],
) -> dict[date, dict[str, float]]:
    """
    Aggregate metrics by date.

    Metrics could be instances of MetricEstimate or scalar values. The former are used with quality
    metrics, the latter with system ones. In case of MetricEstimate, only the central values are
    used for the aggregation.
    """
    by_date: dict[date, dict[str, list[float]]] = {}
    for record in records:
        day = record["test_date"]
        by_date.setdefault(day, {})
        for metric in metric_names:
            if metric not in record:
                continue
            value = record[metric]
            if value is None:
                continue
            if isinstance(value, (int, float)):
                numeric_value = float(value)
            else:
                numeric_value = _extract_central_value(value)
            if numeric_value is not None:
                by_date[day].setdefault(metric, []).append(numeric_value)

    aggregated: dict[date, dict[str, float]] = {}
    for day, metrics in by_date.items():
        aggregated[day] = {m: statistics.fmean(vals) for m, vals in metrics.items() if vals}
    return aggregated


def _metrics_present_for_all_dates(
    control_by_date: dict[date, dict[str, float]],
    treatment_by_date: dict[date, dict[str, float]],
    dates: Sequence[date],
) -> list[str]:
    metrics: list[str] = []
    for metric in sorted(set().union(*[control_by_date[d].keys() for d in dates])):
        if all(metric in control_by_date[d] and metric in treatment_by_date[d] for d in dates):
            metrics.append(metric)
    return metrics


def _compare_metrics_across_dates(
    control_by_date: dict[date, dict[str, float]],
    treatment_by_date: dict[date, dict[str, float]],
    dates: Sequence[date],
) -> dict[str, dict[str, float | None]]:
    metrics = _metrics_present_for_all_dates(control_by_date, treatment_by_date, dates)
    if not metrics:
        raise ValueError("No common metrics across control/treatment for the paired dates.")

    result: dict[str, dict[str, float | None]] = {}
    for metric in metrics:
        control_vals = [control_by_date[d][metric] for d in dates]
        treatment_vals = [treatment_by_date[d][metric] for d in dates]
        control_mean = statistics.fmean(control_vals)
        treatment_mean = statistics.fmean(treatment_vals)
        absolute_change = treatment_mean - control_mean
        percent_change = None if control_mean == 0 else (absolute_change / control_mean) * 100
        result[metric] = {
            "control_mean": control_mean,
            "treatment_mean": treatment_mean,
            "absolute_change": absolute_change,
            "percent_change": percent_change,
        }
    return result


def _write_json_output(payload: dict[str, Any], output_path: str | None) -> None:
    rendered = json.dumps(payload, indent=2, sort_keys=True)
    if output_path:
        Path(output_path).write_text(rendered)
    else:
        print(rendered)


def _chunked_metrics(metrics: Sequence[str], chunk_size: int = PLOTS_PER_PAGE) -> list[list[str]]:
    if chunk_size <= 0:
        raise ValueError(f"chunk_size must be > 0, got {chunk_size}")
    return [list(metrics[i : i + chunk_size]) for i in range(0, len(metrics), chunk_size)]


def _parse_relative_baseline(selector: str) -> RelativeBaselineSpec:
    if not selector:
        raise ValueError("--relative-baseline is required.")
    if selector.startswith("online:"):
        dimension = selector.split(":", 1)[1].strip()
        if not dimension:
            raise ValueError("Online relative baseline must be in the form online:<dimension>.")
        return RelativeBaselineSpec(kind="online", value=dimension)
    return RelativeBaselineSpec(kind="version", value=selector.split("@", 1)[-1])


def _parse_treatment_descriptions(values: Sequence[str] | None) -> dict[str, str]:
    descriptions: dict[str, str] = {}
    for value in values or []:
        if "=" not in value:
            raise ValueError("--treatment-description must use VERSION=TEXT")
        version, description = value.split("=", 1)
        version = version.strip()
        description = description.strip()
        if not version:
            raise ValueError("--treatment-description must include a non-empty VERSION")
        if not description:
            raise ValueError("--treatment-description must include non-empty TEXT")
        if version in descriptions:
            raise ValueError(f"Duplicate --treatment-description for version: {version}")
        descriptions[version] = description
    return descriptions


def _validate_plot_descriptions(
    *,
    baseline_description: str | None,
    treatment_descriptions: dict[str, str],
    versions: Sequence[str],
    baseline: RelativeBaselineSpec,
) -> str | None:
    normalized_baseline_description = baseline_description.strip() if baseline_description else None
    if normalized_baseline_description == "":
        raise ValueError("--baseline-description must not be empty")

    allowed_versions = set(versions)
    unknown_versions = sorted(version for version in treatment_descriptions if version not in allowed_versions)
    if unknown_versions:
        raise ValueError(
            "--treatment-description refers to version(s) that are not plotted: " + ", ".join(unknown_versions)
        )

    baseline_selector = baseline.version_selector
    if baseline.kind == "version" and baseline_selector in treatment_descriptions:
        raise ValueError(
            f"--treatment-description {baseline_selector}=... describes the baseline; "
            "use --baseline-description instead"
        )

    if baseline.kind == "online" and "online" in treatment_descriptions:
        raise ValueError(
            "--treatment-description online=... describes the baseline; use --baseline-description instead"
        )

    return normalized_baseline_description


def _online_metric_column(dimension: str, metric: str) -> str:
    return f"__online__{dimension}.{metric}"


def _build_plot_rows(
    records: Sequence[dict[str, Any]],
    metrics: Sequence[str],
    baseline: RelativeBaselineSpec,
) -> list[dict[str, Any]]:
    raw_rows: list[dict[str, Any]] = []
    for record in records:
        row = {
            "algorithm_id": record["algorithm_id"],
            "test_date": _parse_test_date(record["test_date"]).isoformat(),
            "version": _version_from_algorithm_id(record["algorithm_id"]),
        }
        for metric in metrics:
            row[metric] = record.get(metric)
            if baseline.kind == "online":
                row[_online_metric_column(baseline.value, metric)] = record.get(f"{baseline.value}.{metric}")
        raw_rows.append(row)
    return raw_rows


def _resolve_online_metrics(
    raw_rows: Sequence[dict[str, Any]],
    metrics: Sequence[str],
    baseline: RelativeBaselineSpec,
) -> list[str]:
    test_dates = sorted({row["test_date"] for row in raw_rows})
    supported_metrics: list[str] = []
    for metric in metrics:
        online_values_by_date = _online_metric_values_by_date(raw_rows, metric, baseline)
        if len(online_values_by_date) == len(test_dates):
            supported_metrics.append(metric)
    return supported_metrics


def _online_metric_values_by_date(
    raw_rows: Sequence[dict[str, Any]],
    metric: str,
    baseline: RelativeBaselineSpec,
) -> dict[str, MetricEstimate]:
    """Extract the online baseline value of a metric for each test date from raw plot rows.

    Each raw row may carry an online metric value under the column
    `_online_metric_column(baseline.value, metric)` (e.g. `algorithm.roc_auc`). The value is either
    a plain float (legacy files) or a MetricEstimate dict with `value`, and optionally a confidence
    interval.

    Returns a mapping from ISO-date string to MetricEstimate.  The confidence interval is only
    filled when the source value carried them.

    The result is consumed by `_synthesize_online_control_rows`, which stores it as-is so that
    `_table_rows_for_metrics` can extract central values and confidence interval uniformly alongside
    the offline rows.
    """
    values: dict[str, list[MetricEstimate]] = {}
    online_col = _online_metric_column(baseline.value, metric)
    for row in raw_rows:
        value = row.get(online_col)
        if value is None:
            continue
        if _is_metric_value(value):
            central = float(value["value"])
            if "ci95_lower" in value or "ci95_upper" in value:
                estimate: MetricEstimate = {
                    "value": central,
                    "ci95_lower": float(value["ci95_lower"]),
                    "ci95_upper": float(value["ci95_upper"]),
                }
            else:
                estimate = {"value": central}
        else:
            estimate = {"value": _require_numeric(value, online_col)}
        values.setdefault(row["test_date"], []).append(estimate)

    by_date: dict[str, MetricEstimate] = {}
    for test_date, date_estimates in values.items():
        # Within a single test date, all rows must agree on the central value (the online baseline is the same for every
        # offline algorithm run on that date). In multiple rows exist, the first one wins.
        first_central = date_estimates[0]["value"]
        inconsistent = [
            e["value"]
            for e in date_estimates[1:]
            if not math.isclose(e["value"], first_central, rel_tol=1e-12, abs_tol=1e-12)
        ]
        if inconsistent:
            rendered = ", ".join(f"{v:.12g}" for v in [first_central] + inconsistent)
            raise ValueError(
                f"Online baseline {baseline.display_name} metric {metric} has inconsistent "
                f"values for {test_date}: {rendered}"
            )
        by_date[test_date] = date_estimates[0]
    return by_date


def _synthesize_online_control_rows(
    raw_rows: Sequence[dict[str, Any]],
    metrics: Sequence[str],
    baseline: RelativeBaselineSpec,
) -> list[dict[str, Any]]:
    online_rows: list[dict[str, Any]] = []
    online_values = {metric: _online_metric_values_by_date(raw_rows, metric, baseline) for metric in metrics}
    for test_date in sorted({row["test_date"] for row in raw_rows}):
        online_row = {
            "algorithm_id": f"online:{baseline.value}",
            "test_date": test_date,
            "version": baseline.version_selector,
        }
        for metric in metrics:
            online_row[metric] = online_values[metric].get(test_date)
        online_rows.append(online_row)
    return online_rows


def _table_rows_for_metrics(rows: Sequence[dict[str, Any]], metrics: Sequence[str]) -> list[dict[str, Any]]:
    table_rows: list[dict[str, Any]] = []
    for row in rows:
        output_row = {
            "algorithm_id": row["algorithm_id"],
            "test_date": row["test_date"],
            "version": row["version"],
        }
        for metric in metrics:
            est = row.get(metric)
            if _is_metric_value(est):
                output_row[metric] = _extract_central_value(est)
                if isinstance(est, dict) and ("ci95_lower" in est or "ci95_upper" in est):
                    output_row[f"{metric}__unc_down"] = float(est["value"] - est["ci95_lower"])
                    output_row[f"{metric}__unc_up"] = float(est["ci95_upper"] - est["value"])
                else:
                    output_row[f"{metric}__unc_down"] = 0.0
                    output_row[f"{metric}__unc_up"] = 0.0
            elif isinstance(est, (int, float)):
                # Branch to process system metrics
                output_row[metric] = float(est)
        table_rows.append(output_row)
    return table_rows


def _drop_metric_columns(rows: Sequence[dict[str, Any]], metrics: Sequence[str]) -> list[dict[str, Any]]:
    metric_columns = set(metrics)
    for metric in metrics:
        metric_columns.add(f"{metric}__unc_down")
        metric_columns.add(f"{metric}__unc_up")
    return [{key: value for key, value in row.items() if key not in metric_columns} for row in rows]


def _resolve_plot_versions(
    table_rows: Sequence[dict[str, Any]],
    explicit_versions: Sequence[str] | None,
    baseline: RelativeBaselineSpec,
) -> list[str]:
    versions = list(dict.fromkeys(row["version"] for row in table_rows))
    if explicit_versions:
        versions = [v.split("@", 1)[-1] for v in explicit_versions]
    versions = [
        baseline.version_selector,
        *[version for version in versions if version != baseline.version_selector],
    ]
    return list(dict.fromkeys(versions))


def _build_plot_dataset(
    records: Sequence[dict[str, Any]],
    metrics: Sequence[str],
    explicit_versions: Sequence[str] | None,
    relative_baseline: str,
) -> PlotDataset:
    import pandas as pd  # type: ignore

    baseline = _parse_relative_baseline(relative_baseline)
    raw_rows = _build_plot_rows(records, metrics, baseline)

    final_metrics = list(metrics)
    online_rows: list[dict[str, Any]] = []
    if baseline.kind == "online":
        final_metrics = _resolve_online_metrics(raw_rows, final_metrics, baseline)
        if not final_metrics:
            raise ValueError(f"No metrics available for online baseline: {baseline.display_name}")
        online_rows = _synthesize_online_control_rows(raw_rows, final_metrics, baseline)

    table_rows = _table_rows_for_metrics(raw_rows + online_rows, final_metrics)

    df = pd.DataFrame(table_rows)
    if df.empty:
        raise ValueError("No data available for plotting after filtering.")

    versions = _resolve_plot_versions(table_rows, explicit_versions, baseline)
    df["version"] = pd.Categorical(df["version"], categories=versions, ordered=True)
    if baseline.kind == "version" and baseline.version_selector not in df["version"].cat.categories:
        raise ValueError(f"Relative baseline version not present in data: {baseline.value}")

    return PlotDataset(
        df=df,
        table_rows=table_rows,
        metrics=final_metrics,
        versions=versions,
        baseline=baseline,
    )


def _format_timing_metric(prefix: str, stage: str) -> str:
    if stage == "total_time":
        stage = "total_pipeline"
    return f"{prefix}.{stage}" if prefix else stage


def _is_timing_stage_skipped(result_dict: dict[str, Any], stage: str) -> bool:
    stage_result = result_dict.get(stage)
    return isinstance(stage_result, dict) and bool(stage_result.get("skipped"))


def _extract_timing_metrics(result_dict: dict[str, Any], prefix: str = "") -> dict[str, float]:
    metrics: dict[str, float] = {}
    timing_info = result_dict.get("timing_info_sec")
    stage_values: dict[str, float] = {}
    if isinstance(timing_info, dict):
        for stage, value in timing_info.items():
            if isinstance(value, (int, float)):
                stage_values[str(stage)] = float(value)

    for stage, value in stage_values.items():
        if stage == "prepare_dependencies":
            continue
        if _is_timing_stage_skipped(result_dict, stage):
            continue
        metrics[_format_timing_metric(prefix, stage)] = value

    dependencies = result_dict.get("dependencies")
    if isinstance(dependencies, dict):
        for dependency_name in sorted(dependencies):
            dependency_result = dependencies[dependency_name]
            if not isinstance(dependency_result, dict):
                continue
            if dependency_result.get("skipped"):
                continue
            dependency_prefix = _format_timing_metric(prefix, f"dependencies.{dependency_name}")
            metrics.update(_extract_timing_metrics(dependency_result, dependency_prefix))

    return metrics


def _timing_stage_values(result_dict: dict[str, Any]) -> dict[str, float]:
    timing_info = result_dict.get("timing_info_sec")
    if not isinstance(timing_info, dict):
        return {}
    return {
        str(stage): float(value)
        for stage, value in timing_info.items()
        if isinstance(value, (int, float)) and float(value) >= 0
    }


def _dependency_results(result_dict: dict[str, Any]) -> dict[str, dict[str, Any]]:
    dependencies = result_dict.get("dependencies")
    if not isinstance(dependencies, dict):
        return {}
    return {
        str(name): dependency_result
        for name, dependency_result in dependencies.items()
        if isinstance(dependency_result, dict) and not dependency_result.get("skipped")
    }


def _extract_timing_breakdown(result_dict: dict[str, Any], prefix: str = "") -> dict[str, float]:
    stage_values = _timing_stage_values(result_dict)
    dependencies = _dependency_results(result_dict)
    breakdown: dict[str, float] = {}

    dependency_total = 0.0
    omitted_total = 0.0
    for dependency_name in sorted(dependencies):
        dependency_result = dependencies[dependency_name]
        dependency_prefix = _format_timing_metric(prefix, f"dependencies.{dependency_name}")
        dependency_breakdown = _extract_timing_breakdown(dependency_result, dependency_prefix)
        breakdown.update(dependency_breakdown)

        dependency_total += sum(dependency_breakdown.values())

    for stage, value in stage_values.items():
        if stage == "total_time":
            continue
        if stage == "prepare_dependencies" and dependencies:
            overhead = max(0.0, value - dependency_total)
            if overhead > 0:
                breakdown[_format_timing_metric(prefix, "dependency_overhead")] = overhead
            continue
        if stage == "prepare_dependencies":
            omitted_total += value
            continue
        if _is_timing_stage_skipped(result_dict, stage):
            omitted_total += value
            continue
        breakdown[_format_timing_metric(prefix, stage)] = value

    total_time = stage_values.get("total_time")
    if total_time is not None:
        total_time = max(0.0, total_time - omitted_total)
        accounted = sum(breakdown.values())
        unaccounted = total_time - accounted
        if unaccounted > max(1e-9, total_time * 1e-9):
            breakdown[_format_timing_metric(prefix, "unaccounted")] = unaccounted

    return breakdown


def _timing_metric_sort_key(metric: str) -> tuple:
    parts = metric.split(".")
    if len(parts) >= 3 and parts[0] == "dependencies":
        dependency_path = ".".join(parts[:-1])
        stage = parts[-1]
        return (1, dependency_path, _TIMING_STAGE_ORDER.get(stage, 1000), stage)
    stage = parts[-1]
    return (0, "", _TIMING_STAGE_ORDER.get(stage, 1000), stage)


def _is_production_training_timing_component(component: str) -> bool:
    return component.split(".")[-1] in _PRODUCTION_TRAINING_TIMING_STAGES


def _production_training_timing_components(components: Sequence[str]) -> list[str]:
    return [component for component in components if _is_production_training_timing_component(component)]


def _has_production_training_breakdown_signal(components: Sequence[str]) -> bool:
    return any(component != "package_predict_params" for component in components)


def _build_timing_plot_dataset(records: Sequence[dict[str, Any]], versions: Sequence[str]) -> TimingPlotDataset | None:
    import pandas as pd  # type: ignore

    rows: list[dict[str, Any]] = []
    all_metrics: set[str] = set()
    for record in records:
        timing_metrics = _extract_timing_metrics(record)
        if not timing_metrics:
            continue
        row: dict[str, Any] = {
            "algorithm_id": record["algorithm_id"],
            "test_date": _parse_test_date(record["test_date"]).isoformat(),
            "version": _version_from_algorithm_id(record["algorithm_id"]),
        }
        row.update(timing_metrics)
        rows.append(row)
        all_metrics.update(timing_metrics)

    if not rows or not all_metrics:
        return None

    df = pd.DataFrame(rows)
    df["version"] = pd.Categorical(df["version"], categories=list(versions), ordered=True)
    metrics: list[str] = []
    for metric in sorted(all_metrics, key=_timing_metric_sort_key):
        values = df[metric].dropna().astype(float)
        if values.empty or not (values != 0).any():
            continue
        metrics.append(metric)
    if not metrics:
        return None

    return TimingPlotDataset(df=df, metrics=metrics)


def _build_timing_breakdown_dataset(
    records: Sequence[dict[str, Any]], versions: Sequence[str]
) -> TimingBreakdownDataset | None:
    import pandas as pd  # type: ignore

    rows: list[dict[str, Any]] = []
    all_components: set[str] = set()
    for record in records:
        breakdown = _extract_timing_breakdown(record)
        if not breakdown:
            continue
        row: dict[str, Any] = {
            "algorithm_id": record["algorithm_id"],
            "test_date": _parse_test_date(record["test_date"]).isoformat(),
            "version": _version_from_algorithm_id(record["algorithm_id"]),
        }
        row.update(breakdown)
        rows.append(row)
        all_components.update(breakdown)

    if not rows or not all_components:
        return None

    df = pd.DataFrame(rows)
    df["version"] = pd.Categorical(df["version"], categories=list(versions), ordered=True)
    components = [
        component
        for component in sorted(all_components, key=_timing_metric_sort_key)
        if component in df.columns and not df[component].dropna().empty
    ]
    if not components:
        return None

    return TimingBreakdownDataset(df=df, components=components)


def _format_seconds(value: float) -> str:
    if value >= 3600:
        return f"{value / 3600:.2f}h"
    if value >= 60:
        return f"{value / 60:.2f}m"
    if value >= 1:
        return f"{value:.2f}s"
    if value >= 0.001:
        return f"{value * 1000:.2f}ms"
    return f"{value * 1_000_000:.2f}us"


def _mean_ci_95(values: Sequence[float]) -> tuple[float, tuple[float, float] | None] | None:
    clean_values = [float(value) for value in values if not math.isnan(float(value))]
    if not clean_values:
        return None

    mean = statistics.fmean(clean_values)
    if len(clean_values) < 2:
        return mean, None

    degrees_of_freedom = len(clean_values) - 1
    t_critical = float(student_t.ppf(0.975, degrees_of_freedom))
    margin = t_critical * statistics.stdev(clean_values) / math.sqrt(len(clean_values))
    return mean, (max(0.0, mean - margin), mean + margin)


def _format_timing_estimate(values: Sequence[float]) -> tuple[float, str] | None:
    estimate = _mean_ci_95(values)
    if estimate is None:
        return None
    mean, ci = estimate
    rendered = _format_seconds(mean)
    if ci is not None:
        low, high = ci
        rendered = f"{rendered} (95% CI {_format_seconds(low)} .. {_format_seconds(high)})"
    return mean, rendered


def _build_timing_summary_lines(
    timing_dataset: TimingPlotDataset,
    versions: Sequence[str],
    baseline_version: str,
) -> list[str]:
    lines: list[str] = [
        "Pipeline Performance Summary",
        "Pipeline stage timings from result.json timing_info_sec. Seconds; lower is better.",
        "Point plots and summary intervals use 95% confidence intervals across test dates.",
        "When present, total_pipeline is timing_info_sec.total_time.",
        "Stages recorded as zero for every run are omitted.",
        (
            "When shown, the stacked breakdown chart focuses on the production training path and excludes offline-only "
            "predict/evaluate/performance-test stages."
        ),
        (
            "The opaque prepare_dependencies aggregate is expanded into dependency-internal timing components "
            "when nested dependency results are available."
        ),
    ]
    df = timing_dataset.df

    for metric in timing_dataset.metrics:
        metric_values = df[["version", metric]].dropna(subset=[metric])
        if metric_values.empty:
            continue
        baseline_rows = metric_values[metric_values["version"] == baseline_version][metric].astype(float).tolist()
        baseline_estimate = _format_timing_estimate(baseline_rows)
        baseline_mean = baseline_estimate[0] if baseline_estimate is not None else None

        lines.append("")
        lines.extend(_wrap_labeled_text("Metric:", metric, label_width=12))
        for version in versions:
            version_rows = metric_values[metric_values["version"] == version][metric].astype(float).tolist()
            version_estimate = _format_timing_estimate(version_rows)
            if version_estimate is None:
                continue
            mean, rendered = version_estimate
            if version != baseline_version and baseline_mean is not None and baseline_mean != 0:
                rendered = f"{rendered}; mean {mean / baseline_mean:.3g}x baseline"
            lines.extend(_wrap_labeled_text("Version:", f"{version}: {rendered}", label_width=12))

    return lines


def _cache_source_pattern(path: str, root: str | None) -> str:
    pattern = _relative_to_root(path, root)
    pattern = re.sub(r"\d{4}-\d{2}-\d{2}", "<date>", pattern)
    pattern = re.sub(r"(?<=-)[0-9a-fA-F]{8,12}(?=[.-])", "<hash>", pattern)
    return pattern


def _build_cache_usage_lines(records: Sequence[dict[str, Any]]) -> list[str]:
    lines: list[str] = ["", "Cache Usage"]
    cache_usages = [record["cache_usage"] for record in records if isinstance(record.get("cache_usage"), dict)]

    if not cache_usages:
        lines.extend(
            _wrap_labeled_text(
                "Cache Used:",
                "not recorded in result metadata",
                label_width=16,
            )
        )
        return lines

    configurations = [
        usage.get("configuration")
        for usage in cache_usages
        if isinstance(usage.get("configuration"), dict) and usage.get("configuration")
    ]
    unique_configurations = {
        _json_key(configuration): configuration for configuration in configurations if isinstance(configuration, dict)
    }
    if not unique_configurations:
        lines.extend(_wrap_labeled_text("Cache Config:", "(none recorded)", label_width=16))
    elif len(unique_configurations) == 1:
        configuration = next(iter(unique_configurations.values()))
        lines.extend(_wrap_labeled_text("Cache Config:", _format_mapping(configuration), label_width=16))
    else:
        lines.extend(
            _wrap_labeled_text("Cache Config:", f"mixed ({len(unique_configurations)} configurations)", label_width=16)
        )

    hits_by_stage: dict[str, dict[str, Any]] = {}
    for usage in cache_usages:
        hits = usage.get("hits")
        if not isinstance(hits, list):
            continue
        stages_seen_for_record: set[str] = set()
        for hit in hits:
            if not isinstance(hit, dict):
                continue
            stage = hit.get("stage")
            if not isinstance(stage, str) or not stage:
                continue
            entry = hits_by_stage.setdefault(stage, {"count": 0, "sources": []})
            if stage not in stages_seen_for_record:
                entry["count"] += 1
                stages_seen_for_record.add(stage)
            sources = hit.get("sources")
            if isinstance(sources, list):
                entry["sources"].extend(str(source) for source in sources if isinstance(source, str) and source)

    if not hits_by_stage:
        lines.extend(
            _wrap_labeled_text(
                "Cache Used:",
                "no cache-hit stages recorded",
                label_width=16,
            )
        )
        return lines

    total_runs = len(records)
    stage_summaries = [
        f"{stage} ({entry['count']}/{total_runs} runs)"
        for stage, entry in sorted(hits_by_stage.items(), key=lambda item: _timing_metric_sort_key(item[0]))
    ]
    lines.extend(_wrap_labeled_text("Cache Used:", "yes", label_width=16))
    lines.extend(_wrap_labeled_values("Hit Stages:", stage_summaries, max_items=12, label_width=16))

    source_paths = sorted(
        dict.fromkeys(
            source
            for entry in hits_by_stage.values()
            for source in entry["sources"]
            if isinstance(source, str) and source
        )
    )
    source_root = _common_path_root(source_paths)
    source_patterns = sorted(dict.fromkeys(_cache_source_pattern(source, source_root) for source in source_paths))
    if source_root:
        lines.extend(_wrap_labeled_text("Cache Root:", source_root, label_width=16))
    if source_patterns:
        lines.extend(_wrap_labeled_values("Cache Pattern:", source_patterns, max_items=6, label_width=16))

    return lines


def _wrap_selection_values(label: str, values: Sequence[str], max_items: int = 4) -> list[str]:
    if not values:
        return []
    unique_values = list(dict.fromkeys(str(v) for v in values if v))
    if not unique_values:
        return []
    display_values = unique_values
    if len(unique_values) > max_items:
        display_values = [*unique_values[:max_items], f"... (+{len(unique_values) - max_items} more)"]
    prefix = f"  {label:<11} "
    return textwrap.fill(
        ", ".join(display_values),
        width=110,
        initial_indent=prefix,
        subsequent_indent=" " * len(prefix),
    ).splitlines()


def _wrap_labeled_text(label: str, text: str, *, label_width: int = 18) -> list[str]:
    prefix = f"  {label:<{label_width}}  "
    return textwrap.fill(
        text,
        width=110,
        initial_indent=prefix,
        subsequent_indent=" " * len(prefix),
    ).splitlines()


def _wrap_labeled_values(
    label: str,
    values: Sequence[str],
    *,
    max_items: int | None = None,
    label_width: int = 18,
) -> list[str]:
    unique_values = list(dict.fromkeys(str(v) for v in values if v))
    if not unique_values:
        return []

    display_values = unique_values
    if max_items is not None and len(unique_values) > max_items:
        display_values = [*unique_values[:max_items], f"... (+{len(unique_values) - max_items} more)"]

    return _wrap_labeled_text(label, ", ".join(display_values), label_width=label_width)


def _format_spec_value(value: Any) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    if value is None:
        return "null"
    if isinstance(value, (int, float, str)):
        return str(value)
    return json.dumps(value, sort_keys=True, separators=(",", ":"), default=str)


def _flatten_mapping(mapping: dict[str, Any], prefix: str = "") -> list[tuple[str, str]]:
    flattened: list[tuple[str, str]] = []
    for key in sorted(mapping):
        value = mapping[key]
        next_prefix = f"{prefix}.{key}" if prefix else str(key)
        if isinstance(value, dict):
            flattened.extend(_flatten_mapping(value, next_prefix))
        else:
            flattened.append((next_prefix, _format_spec_value(value)))
    return flattened


def _format_mapping(mapping: dict[str, Any] | None) -> str:
    if not mapping:
        return "(none)"
    return ", ".join(f"{key}={value}" for key, value in _flatten_mapping(mapping))


def _evaluation_specification_from_record(record: dict[str, Any]) -> dict[str, Any]:
    algorithm_definition = record.get("algorithm_definition")
    if not isinstance(algorithm_definition, dict):
        algorithm_definition = {}

    execution_parameters = algorithm_definition.get("hotvect_execution_parameters")
    if not isinstance(execution_parameters, dict):
        execution_parameters = {}

    evaluation_function = execution_parameters.get("evaluation_function")
    if isinstance(evaluation_function, str):
        evaluation_function = {"name": evaluation_function}
    elif not isinstance(evaluation_function, dict):
        evaluation_function = {}

    evaluation_policy = record.get("evaluation_policy")
    if not isinstance(evaluation_policy, dict):
        evaluation_policy = {}

    test_decoder_parameters = algorithm_definition.get("test_decoder_parameters")
    if not isinstance(test_decoder_parameters, dict):
        test_decoder_parameters = {}

    return {
        "evaluation_function": evaluation_function,
        "evaluation_policy": evaluation_policy,
        "test_decoder_parameters": test_decoder_parameters,
    }


def _benchmark_specification_from_record(record: dict[str, Any]) -> dict[str, Any]:
    benchmark_specification = record.get("benchmark_specification")
    if isinstance(benchmark_specification, dict):
        return benchmark_specification

    result_like = {"algorithm_definition": record.get("algorithm_definition")}
    return _benchmark_specification_from_result(result_like)


def _common_evaluation_specification(records: Sequence[dict[str, Any]]) -> dict[str, Any]:
    specs = [_evaluation_specification_from_record(record) for record in records]
    return _require_consistent(specs, label="evaluation specification") or {}


def _common_benchmark_specification(records: Sequence[dict[str, Any]]) -> dict[str, Any]:
    specs = [_benchmark_specification_from_record(record) for record in records]
    return _require_consistent(specs, label="benchmark specification") or {}


def _common_benchmark_specification_result(records: Sequence[dict[str, Any]]) -> CommonSpecificationResult:
    specs = [_benchmark_specification_from_record(record) for record in records]
    return _common_specification_result(specs, label="benchmark specification")


def _path_segments(path: str) -> list[str]:
    return path.replace("\\", "/").split("/")


def _join_path_segments(segments: Sequence[str]) -> str:
    return "/".join(segments)


def _common_path_root(paths: Sequence[str]) -> str | None:
    normalized_paths = [path for path in paths if path]
    if not normalized_paths:
        return None

    split_paths = [_path_segments(path) for path in normalized_paths]
    common_length = 0
    for grouped_segments in zip(*split_paths):
        if len(set(grouped_segments)) != 1:
            break
        common_length += 1

    common_segments = split_paths[0][:common_length]
    if not common_segments:
        return None

    if len(normalized_paths) == 1:
        common_segments = common_segments[:-1]
    else:
        last_segment = common_segments[-1]
        if last_segment.endswith((".json", ".json.gz", ".zip")):
            common_segments = common_segments[:-1]

    if not common_segments:
        return None
    root = _join_path_segments(common_segments)
    return root if root == "/" else root.rstrip("/")


def _relative_to_root(path: str, root: str | None) -> str:
    if not root:
        return path
    normalized_root = root.rstrip("/")
    if path == normalized_root:
        return "."
    prefix = f"{normalized_root}/"
    if path.startswith(prefix):
        return path[len(prefix) :]
    return path


def _source_pattern(record: dict[str, Any], path: str, root: str | None) -> str:
    pattern = _relative_to_root(path, root)
    replacements = [
        (str(record.get("source_sagemaker_job_name") or ""), "<sagemaker_job>"),
        (str(record.get("algorithm_id") or ""), "<algorithm_id>"),
        (str(record.get("parameter_version") or ""), "<parameter_version>"),
        (_parse_test_date(record["test_date"]).isoformat(), "<test_date>"),
    ]
    for value, placeholder in replacements:
        if value:
            pattern = pattern.replace(value, placeholder)
    pattern = re.sub(r"(?<=-)[0-9a-fA-F]{8,12}(?=[.-])", "<hash>", pattern)
    return pattern


def _record_source_value(record: dict[str, Any], field: str, fallback_field: str | None = None) -> str | None:
    value = record.get(field)
    if isinstance(value, str) and value:
        return value
    if fallback_field is None:
        return None
    fallback_value = record.get(fallback_field)
    if isinstance(fallback_value, str) and fallback_value:
        return fallback_value
    return None


def _source_patterns(
    records: Sequence[dict[str, Any]], field: str, root: str | None, *, fallback_field: str | None = None
) -> list[str]:
    patterns = [
        _source_pattern(record, value, root)
        for record in records
        if (value := _record_source_value(record, field, fallback_field)) is not None
    ]
    return sorted(dict.fromkeys(patterns))


def _build_provenance_lines(records: Sequence[dict[str, Any]]) -> list[str]:
    lines: list[str] = []
    records_sorted = sorted(
        records,
        key=lambda record: (
            _parse_test_date(record["test_date"]),
            str(record.get("algorithm_id") or ""),
            str(record.get("source_result_file") or ""),
        ),
    )

    algorithm_ids = sorted({str(record["algorithm_id"]) for record in records_sorted if record.get("algorithm_id")})
    test_dates = sorted({_parse_test_date(record["test_date"]).isoformat() for record in records_sorted})
    result_paths = [
        value
        for record in records_sorted
        if (value := _record_source_value(record, "source_result_uri", "source_result_file")) is not None
    ]
    parameter_uris = [
        str(record["source_parameter_uri"])
        for record in records_sorted
        if isinstance(record.get("source_parameter_uri"), str) and record.get("source_parameter_uri")
    ]
    result_root = _common_path_root(result_paths)
    parameter_root = _common_path_root(parameter_uris)
    result_patterns = _source_patterns(
        records_sorted,
        "source_result_uri",
        result_root,
        fallback_field="source_result_file",
    )
    parameter_patterns = _source_patterns(records_sorted, "source_parameter_uri", parameter_root)
    job_names = sorted(
        {
            str(record["source_sagemaker_job_name"])
            for record in records_sorted
            if record.get("source_sagemaker_job_name")
        }
    )

    lines.append("Source Summary")
    lines.extend(_wrap_labeled_text("Runs:", str(len(records_sorted)), label_width=16))
    lines.extend(_wrap_labeled_values("Algorithms:", algorithm_ids, label_width=16))
    lines.extend(_wrap_labeled_values("Dates:", test_dates, label_width=16))
    if result_root:
        lines.extend(_wrap_labeled_text("Result Root:", result_root, label_width=16))
    if result_patterns:
        lines.extend(_wrap_labeled_values("Result Pattern:", result_patterns, max_items=6, label_width=16))
    if parameter_root:
        lines.extend(_wrap_labeled_text("Parameter Root:", parameter_root, label_width=16))
    if parameter_patterns:
        lines.extend(_wrap_labeled_values("Param Pattern:", parameter_patterns, max_items=6, label_width=16))
    if job_names:
        lines.extend(_wrap_labeled_values("SageMaker Jobs:", job_names, max_items=40, label_width=16))
    else:
        lines.extend(
            _wrap_labeled_text(
                "SageMaker Jobs:",
                "(not available in result source metadata)",
                label_width=16,
            )
        )

    return lines


def _relative_frame(
    df: Any,
    metric: str,
    versions: Sequence[str],
    baseline_version: str,
    *,
    include_baseline: bool,
) -> Any:
    unc_down_col = f"{metric}__unc_down"
    unc_up_col = f"{metric}__unc_up"
    has_unc = unc_down_col in df.columns and unc_up_col in df.columns
    pivot_values = [metric] + ([unc_down_col, unc_up_col] if has_unc else [])

    pivoted = df.pivot_table(
        values=pivot_values,
        index=["version", "test_date"],
        aggfunc="mean",
        observed=False,
    ).reset_index()
    if metric not in pivoted.columns:
        return df.iloc[0:0].copy()

    baseline = pivoted[pivoted["version"] == baseline_version].set_index("test_date")[metric].to_dict()
    if not baseline:
        return df.iloc[0:0].copy()

    relative_rows: list[dict[str, Any]] = []
    for _, row in pivoted.iterrows():
        version = row["version"]
        if not include_baseline and version == baseline_version:
            continue
        base = baseline.get(row["test_date"])
        val = row[metric]
        if base is None or val is None:
            continue
        if metric in DIFFERENCE_RELATIVE_METRICS:
            rel = val - base
            rel_unc_down = float(row.get(unc_down_col, 0.0) or 0.0)
            rel_unc_up = float(row.get(unc_up_col, 0.0) or 0.0)
        else:
            rel = None if base == 0 else val / base
            if rel is None:
                continue
            scale = abs(base)
            rel_unc_down = float(row.get(unc_down_col, 0.0) or 0.0) / scale if scale else 0.0
            rel_unc_up = float(row.get(unc_up_col, 0.0) or 0.0) / scale if scale else 0.0
        if version == baseline_version:
            expected = _relative_axis_origin(metric)
            if not math.isclose(float(rel), expected, rel_tol=1e-12, abs_tol=1e-12):
                raise ValueError(
                    f"Relative baseline for metric {metric} on {row['test_date']} should be {expected}, got {rel}"
                )
            rel = expected
        relative_rows.append(
            {
                "version": version,
                "test_date": row["test_date"],
                metric: rel,
                unc_down_col: rel_unc_down,
                unc_up_col: rel_unc_up,
            }
        )

    if not relative_rows:
        return df.iloc[0:0].copy()

    import pandas as pd  # type: ignore

    rel_df = pd.DataFrame(relative_rows)
    categories = [version for version in versions if include_baseline or version != baseline_version]
    rel_df["version"] = pd.Categorical(rel_df["version"], categories=categories, ordered=True)
    return rel_df


def _collect_relative_metric_frames(
    df: Any,
    metrics: Sequence[str],
    versions: Sequence[str],
    baseline_version: str,
) -> tuple[list[str], list[str], dict[str, Any], dict[str, Any]]:
    relative_point_metrics: list[str] = []
    relative_time_metrics: list[str] = []
    relative_point_frames: dict[str, Any] = {}
    relative_time_frames: dict[str, Any] = {}

    for metric in metrics:
        if metric in RELATIVE_EXCLUDED_METRICS:
            continue
        if metric not in df.columns or df[metric].dropna().empty:
            continue
        rel_point_df = _relative_frame(
            df,
            metric,
            versions,
            baseline_version,
            include_baseline=True,
        )
        if not rel_point_df.empty:
            relative_point_metrics.append(metric)
            relative_point_frames[metric] = rel_point_df
        rel_time_df = _relative_frame(
            df,
            metric,
            versions,
            baseline_version,
            include_baseline=False,
        )
        if not rel_time_df.empty:
            relative_time_metrics.append(metric)
            relative_time_frames[metric] = rel_time_df

    return relative_point_metrics, relative_time_metrics, relative_point_frames, relative_time_frames


def _relative_axis_origin(metric: str) -> float:
    return 0.0 if metric in DIFFERENCE_RELATIVE_METRICS else 1.0


def _relative_metric_label(metric: str) -> str:
    return f"{metric} - baseline" if metric in DIFFERENCE_RELATIVE_METRICS else f"{metric} / baseline"


def _compact_metric_label(metric: str, *, max_length: int = 52) -> str:
    if len(metric) <= max_length:
        return metric

    parts = metric.split(".")
    for tail_count in (3, 2, 1):
        if len(parts) <= tail_count:
            continue
        candidate = "..." + ".".join(parts[-tail_count:])
        if len(candidate) <= max_length:
            return candidate

    return "..." + metric[-(max_length - 3) :]


def _plot_metric_label(metric: str, *, relative: bool = False) -> str:
    compact = _compact_metric_label(metric)
    if relative:
        compact = f"{compact} - baseline" if metric in DIFFERENCE_RELATIVE_METRICS else f"{compact} / baseline"
    return textwrap.fill(compact, width=46, break_long_words=False, break_on_hyphens=False)


def _truncate_metric_list(items: list[str], *, limit: int = 40) -> list[str]:
    if len(items) <= limit:
        return items
    remaining = len(items) - limit
    return [*items[:limit], f"… (+{remaining} more)"]


def _treatment_description_lines(
    treatment_algorithm_ids: Sequence[str],
    treatment_descriptions: dict[str, str],
) -> list[str]:
    lines: list[str] = []
    for algorithm_id in treatment_algorithm_ids:
        version = _version_from_algorithm_id(algorithm_id)
        description = treatment_descriptions.get(version)
        if description:
            lines.extend(_wrap_labeled_text(f"{version}:", description, label_width=20))
    return lines


def _build_header_lines(
    *,
    source_root: str,
    source_files: int,
    treatment_algorithm_ids: Sequence[str],
    baseline_label: str,
    baseline_description: str | None,
    treatment_descriptions: dict[str, str],
    from_last_test_date: str,
    to_last_test_date: str,
    n_dates: int,
    date_min: str,
    date_max: str,
    metrics: Sequence[str],
) -> list[str]:
    metrics_display = _truncate_metric_list(list(metrics))
    root_lines = _wrap_labeled_text("Root:", source_root)
    files_line = f"{source_files} result.json" if source_files else "(from output_base_dir scan)"
    date_range = f"{from_last_test_date} .. {to_last_test_date}"

    header_lines: list[str] = []
    header_lines.append("Subject of Evaluation")
    header_lines.extend(
        _wrap_labeled_values(
            "Treatment:" if len(treatment_algorithm_ids) == 1 else "Treatments:",
            treatment_algorithm_ids,
            label_width=20,
        )
    )
    header_lines.extend(_wrap_labeled_text("Baseline:", baseline_label, label_width=20))
    if baseline_description:
        header_lines.extend(_wrap_labeled_text("Baseline Desc.:", baseline_description, label_width=20))
    treatment_description_lines = _treatment_description_lines(treatment_algorithm_ids, treatment_descriptions)
    if treatment_description_lines:
        header_lines.append("  Treatment Desc.:")
        header_lines.extend(treatment_description_lines)
    header_lines.append("")
    header_lines.append("Execution Parameters")
    header_lines.extend(_wrap_labeled_text("Test Date Range:", date_range, label_width=20))
    header_lines.extend(_wrap_labeled_text("Common Days:", f"{n_dates} ({date_min} .. {date_max})", label_width=20))
    header_lines.append("")
    header_lines.append("Source Data")
    header_lines.extend(root_lines)
    header_lines.extend(_wrap_labeled_text("Files:", files_line))
    header_lines.extend(_wrap_labeled_values("Metrics:", metrics_display, label_width=20))
    return header_lines


def _build_specification_lines(
    *,
    generated_at: str,
    generated_with: str,
    evaluation_specification: dict[str, Any],
    benchmark_specification: dict[str, Any],
    treatment_specs: Sequence[AlgorithmSpecification],
) -> list[str]:
    lines: list[str] = []
    lines.append("Report Generation")
    lines.extend(_wrap_labeled_text("Generated At:", generated_at, label_width=20))
    lines.extend(_wrap_labeled_text("Generated With:", generated_with, label_width=20))

    evaluation_function = evaluation_specification.get("evaluation_function")
    if not isinstance(evaluation_function, dict):
        evaluation_function = {}
    evaluation_arguments = evaluation_function.get("arguments")
    if not isinstance(evaluation_arguments, dict):
        evaluation_arguments = {}
    evaluation_policy = evaluation_specification.get("evaluation_policy")
    if not isinstance(evaluation_policy, dict):
        evaluation_policy = {}
    test_decoder_parameters = evaluation_specification.get("test_decoder_parameters")
    if not isinstance(test_decoder_parameters, dict):
        test_decoder_parameters = {}

    lines.append("")
    lines.append("Evaluation Specification")
    lines.extend(
        _wrap_labeled_text(
            "Function:",
            str(evaluation_function.get("name") or "(default)"),
            label_width=20,
        )
    )
    lines.extend(_wrap_labeled_text("Arguments:", _format_mapping(evaluation_arguments), label_width=20))
    lines.extend(_wrap_labeled_text("Policy:", _format_mapping(evaluation_policy), label_width=20))
    lines.extend(_wrap_labeled_text("Test Decoder:", _format_mapping(test_decoder_parameters), label_width=20))

    performance_test = benchmark_specification.get("performance_test")
    if not isinstance(performance_test, dict):
        performance_test = {}
    performance_data = benchmark_specification.get("performance_data")
    if not isinstance(performance_data, dict):
        performance_data = {}
    requested_parameters = benchmark_specification.get("requested")
    if not isinstance(requested_parameters, dict):
        requested_parameters = {}
    performance_input = benchmark_specification.get("input")
    if not isinstance(performance_input, dict):
        performance_input = {}
    benchmark_contract = benchmark_specification.get("contract")
    if not isinstance(benchmark_contract, dict):
        benchmark_contract = {}
    benchmark_status = benchmark_specification.get("status")
    if not isinstance(benchmark_status, dict):
        benchmark_status = {}

    lines.append("")
    lines.append("Benchmark Specification")
    lines.extend(_wrap_labeled_text("Performance Test:", _format_mapping(performance_test), label_width=20))
    lines.extend(_wrap_labeled_text("Performance Data:", _format_mapping(performance_data), label_width=20))
    lines.extend(_wrap_labeled_text("Requested:", _format_mapping(requested_parameters), label_width=20))
    if performance_input:
        lines.extend(_wrap_labeled_text("Input:", _format_mapping(performance_input), label_width=20))
    if benchmark_contract:
        lines.extend(_wrap_labeled_text("Contract:", _format_mapping(benchmark_contract), label_width=20))
    if benchmark_status:
        lines.extend(_wrap_labeled_text("Status:", _format_mapping(benchmark_status), label_width=20))

    for spec in treatment_specs:
        title = (
            "Treatment Specification" if len(treatment_specs) == 1 else f"Treatment Specification ({spec.algorithm_id})"
        )
        lines.append("")
        lines.append(title)
        lines.extend(_wrap_labeled_text("Algorithm ID:", spec.algorithm_id, label_width=20))
        lines.extend(
            _wrap_labeled_text("Algorithm Params:", _format_mapping(spec.algorithm_parameters), label_width=20)
        )
        if spec.hotvect_version:
            lines.extend(_wrap_labeled_text("Hotvect Version:", spec.hotvect_version, label_width=20))
        if spec.git_describe:
            lines.extend(_wrap_labeled_text("Git Describe:", spec.git_describe, label_width=20))
        if spec.git_commit:
            lines.extend(_wrap_labeled_text("Git Commit:", spec.git_commit, label_width=20))

    return lines


class MetricsCommand(BaseCommand):
    """Top-level metrics command wrapper."""

    @classmethod
    def register_parser(cls, subparsers):
        parser = subparsers.add_parser(
            "metrics",
            help="Metrics utilities (quality + system)",
        )
        metrics_subparsers = parser.add_subparsers(dest="metrics_command", metavar="<metrics-command>")

        MetricsCompareQualityCommand.register_parser(metrics_subparsers)
        MetricsCompareSystemCommand.register_parser(metrics_subparsers)
        MetricsExportCommand.register_parser(metrics_subparsers)
        MetricsPlotCommand.register_parser(metrics_subparsers)

        return parser

    def execute(self, args):
        if args.metrics_command == "compare-quality":
            MetricsCompareQualityCommand().execute(args)
        elif args.metrics_command == "compare-system":
            MetricsCompareSystemCommand().execute(args)
        elif args.metrics_command == "export":
            MetricsExportCommand().execute(args)
        elif args.metrics_command == "plot":
            MetricsPlotCommand().execute(args)
        else:
            raise SystemExit("Missing metrics subcommand. Use `hv-ext metrics -h`.")


class MetricsCompareQualityCommand(BaseCommand):
    """Compare quality metrics between control and treatment."""

    @classmethod
    def register_parser(cls, subparsers):
        parser = subparsers.add_parser(
            "compare-quality",
            help="Compare recommender quality metrics (single-day or multi-day)",
        )
        parser.add_argument("control_file", nargs="?", help="Control evaluation/result JSON file")
        parser.add_argument("treatment_file", nargs="?", help="Treatment evaluation/result JSON file")
        parser.add_argument("-o", "--output", help="Output file path for JSON (default: stdout)")

        parser.add_argument("--output-base-dir", help="Backtest meta/output directory for multi-day comparison")
        parser.add_argument("--control", help="Control algorithm id or version")
        parser.add_argument("--treatment", help="Treatment algorithm id or version")
        parser.add_argument("--from-test-date", help="Start date (YYYY-MM-DD)")
        parser.add_argument("--to-test-date", help="End date (YYYY-MM-DD)")
        parser.add_argument("--algorithm-name-pattern", default=".*", help="Regex for algorithm name filtering")
        parser.add_argument("--algorithm-version-pattern", default=".*", help="Regex for algorithm version filtering")
        return parser

    def execute(self, args):
        try:
            if args.output_base_dir:
                if args.control_file or args.treatment_file:
                    raise ValueError("Provide either control/treatment files or --output-base-dir, not both.")
                if not args.control or not args.treatment:
                    raise ValueError("--control and --treatment are required with --output-base-dir.")
                from_date = date.fromisoformat(args.from_test_date) if args.from_test_date else None
                to_date = date.fromisoformat(args.to_test_date) if args.to_test_date else None
                records = _records_from_output_base_dir(
                    output_base_dir=args.output_base_dir,
                    algorithm_name_pattern=args.algorithm_name_pattern,
                    algorithm_version_pattern=args.algorithm_version_pattern,
                    from_date=from_date,
                    to_date=to_date,
                )
                if not records:
                    raise ValueError("No evaluation records found in output-base-dir.")

                control_id = _select_algorithm_id(records, args.control)
                treatment_id = _select_algorithm_id(records, args.treatment)
                control_records = [r for r in records if r["algorithm_id"] == control_id]
                treatment_records = [r for r in records if r["algorithm_id"] == treatment_id]
                if not control_records or not treatment_records:
                    raise ValueError("Missing control or treatment records for the selected ids.")

                control_by_date = _aggregate_by_date(control_records, QUALITY_METRICS)
                treatment_by_date = _aggregate_by_date(treatment_records, QUALITY_METRICS)
                common_dates = sorted(set(control_by_date.keys()) & set(treatment_by_date.keys()))
                if not common_dates:
                    raise ValueError("No common test dates between control and treatment.")

                metrics = _compare_metrics_across_dates(control_by_date, treatment_by_date, common_dates)
                payload = {
                    "control": control_id,
                    "treatment": treatment_id,
                    "dates_used": [d.isoformat() for d in common_dates],
                    "n_dates": len(common_dates),
                    "metrics": metrics,
                }
                _write_json_output(payload, args.output)
                return

            if not args.control_file or not args.treatment_file:
                raise ValueError("Provide control/treatment files or --output-base-dir.")

            control_metrics = _load_evaluation_file(args.control_file)
            treatment_metrics = _load_evaluation_file(args.treatment_file)

            control_filtered = {k: v for k, v in control_metrics.items() if k in QUALITY_METRICS}
            treatment_filtered = {k: v for k, v in treatment_metrics.items() if k in QUALITY_METRICS}
            common_metrics = sorted(set(control_filtered.keys()) & set(treatment_filtered.keys()))
            if not common_metrics:
                raise ValueError("No common quality metrics between control and treatment.")

            per_metric: dict[str, dict[str, float | None]] = {}
            for metric in common_metrics:
                control_mean = _require_numeric(_extract_central_value(control_filtered[metric]), metric)
                treatment_mean = _require_numeric(_extract_central_value(treatment_filtered[metric]), metric)
                absolute_change = treatment_mean - control_mean
                percent_change = None if control_mean == 0 else (absolute_change / control_mean) * 100
                per_metric[metric] = {
                    "control_mean": control_mean,
                    "treatment_mean": treatment_mean,
                    "absolute_change": absolute_change,
                    "percent_change": percent_change,
                }

            raw_control = _load_result_json(Path(args.control_file))
            raw_treatment = _load_result_json(Path(args.treatment_file))
            control_date = raw_control.get("test_data_time")
            treatment_date = raw_treatment.get("test_data_time")
            dates_used: list[str] = []
            if isinstance(control_date, str) and isinstance(treatment_date, str):
                if control_date != treatment_date:
                    raise ValueError("Control/treatment test_data_time mismatch for single-day compare.")
                dates_used = [control_date]

            payload = {
                "control": args.control_file,
                "treatment": args.treatment_file,
                "dates_used": dates_used,
                "n_dates": 1 if dates_used else None,
                "metrics": per_metric,
            }
            _write_json_output(payload, args.output)
        except FileNotFoundError as e:
            print(f"Error: File not found - {e}", file=sys.stderr)
            sys.exit(1)
        except json.JSONDecodeError as e:
            print(f"Error: Invalid JSON file - {e}", file=sys.stderr)
            sys.exit(1)
        except Exception as e:
            print(f"Error: {e}", file=sys.stderr)
            sys.exit(1)


class MetricsCompareSystemCommand(BaseCommand):
    """Compare system performance metrics between control and treatment."""

    @classmethod
    def register_parser(cls, subparsers):
        parser = subparsers.add_parser(
            "compare-system",
            help="Compare system performance metrics (single-day or multi-day)",
        )
        parser.add_argument("control_file", nargs="?", help="Control performance/result JSON file")
        parser.add_argument("treatment_file", nargs="?", help="Treatment performance/result JSON file")
        parser.add_argument("-o", "--output", help="Output file path for JSON (default: stdout)")

        parser.add_argument("--output-base-dir", help="Backtest meta/output directory for multi-day comparison")
        parser.add_argument("--control", help="Control algorithm id or version")
        parser.add_argument("--treatment", help="Treatment algorithm id or version")
        parser.add_argument("--from-test-date", help="Start date (YYYY-MM-DD)")
        parser.add_argument("--to-test-date", help="End date (YYYY-MM-DD)")
        parser.add_argument("--algorithm-name-pattern", default=".*", help="Regex for algorithm name filtering")
        parser.add_argument("--algorithm-version-pattern", default=".*", help="Regex for algorithm version filtering")
        return parser

    def execute(self, args):
        try:
            if args.output_base_dir:
                if args.control_file or args.treatment_file:
                    raise ValueError("Provide either control/treatment files or --output-base-dir, not both.")
                if not args.control or not args.treatment:
                    raise ValueError("--control and --treatment are required with --output-base-dir.")
                from_date = date.fromisoformat(args.from_test_date) if args.from_test_date else None
                to_date = date.fromisoformat(args.to_test_date) if args.to_test_date else None
                records = _records_from_output_base_dir(
                    output_base_dir=args.output_base_dir,
                    algorithm_name_pattern=args.algorithm_name_pattern,
                    algorithm_version_pattern=args.algorithm_version_pattern,
                    from_date=from_date,
                    to_date=to_date,
                )
                if not records:
                    raise ValueError("No performance records found in output-base-dir.")

                control_id = _select_algorithm_id(records, args.control)
                treatment_id = _select_algorithm_id(records, args.treatment)
                control_records = [r for r in records if r["algorithm_id"] == control_id]
                treatment_records = [r for r in records if r["algorithm_id"] == treatment_id]
                if not control_records or not treatment_records:
                    raise ValueError("Missing control or treatment records for the selected ids.")

                control_by_date = _aggregate_by_date(control_records, SYSTEM_METRICS)
                treatment_by_date = _aggregate_by_date(treatment_records, SYSTEM_METRICS)
                common_dates = sorted(set(control_by_date.keys()) & set(treatment_by_date.keys()))
                if not common_dates:
                    raise ValueError("No common test dates between control and treatment.")

                metrics = _compare_metrics_across_dates(control_by_date, treatment_by_date, common_dates)
                payload = {
                    "control": control_id,
                    "treatment": treatment_id,
                    "dates_used": [d.isoformat() for d in common_dates],
                    "n_dates": len(common_dates),
                    "metrics": metrics,
                }
                _write_json_output(payload, args.output)
                return

            if not args.control_file or not args.treatment_file:
                raise ValueError("Provide control/treatment files or --output-base-dir.")

            control_perf = _load_performance_file(args.control_file)
            treatment_perf = _load_performance_file(args.treatment_file)

            control_metrics = {
                "max_memory_usage": control_perf["max_memory_usage"],
                "mean_throughput": control_perf["mean_throughput"],
            }
            treatment_metrics = {
                "max_memory_usage": treatment_perf["max_memory_usage"],
                "mean_throughput": treatment_perf["mean_throughput"],
            }
            control_rt = control_perf.get("response_time_metrics") or {}
            treatment_rt = treatment_perf.get("response_time_metrics") or {}
            for key in ["p50", "p75", "p95", "p99", "p999"]:
                control_metrics[key] = control_rt.get(key, {}).get("mean")
                treatment_metrics[key] = treatment_rt.get(key, {}).get("mean")

            common_metrics = sorted(
                m for m in SYSTEM_METRICS if control_metrics.get(m) is not None and treatment_metrics.get(m) is not None
            )
            if not common_metrics:
                raise ValueError("No common system metrics between control and treatment.")

            per_metric: dict[str, dict[str, float | None]] = {}
            for metric in common_metrics:
                control_mean = _require_numeric(control_metrics[metric], metric)
                treatment_mean = _require_numeric(treatment_metrics[metric], metric)
                absolute_change = treatment_mean - control_mean
                percent_change = None if control_mean == 0 else (absolute_change / control_mean) * 100
                per_metric[metric] = {
                    "control_mean": control_mean,
                    "treatment_mean": treatment_mean,
                    "absolute_change": absolute_change,
                    "percent_change": percent_change,
                }

            control_date: str | None = None
            treatment_date: str | None = None
            try:
                control_date = _load_result_json(Path(args.control_file)).get("test_data_time")
                treatment_date = _load_result_json(Path(args.treatment_file)).get("test_data_time")
            except Exception:
                pass

            dates_used: list[str] = []
            if isinstance(control_date, str) and isinstance(treatment_date, str):
                if control_date != treatment_date:
                    raise ValueError("Control/treatment test_data_time mismatch for single-day compare.")
                dates_used = [control_date]

            payload = {
                "control": args.control_file,
                "treatment": args.treatment_file,
                "dates_used": dates_used,
                "n_dates": 1 if dates_used else None,
                "metrics": per_metric,
            }
            _write_json_output(payload, args.output)
        except FileNotFoundError as e:
            print(f"Error: File not found - {e}", file=sys.stderr)
            sys.exit(1)
        except json.JSONDecodeError as e:
            print(f"Error: Invalid JSON file - {e}", file=sys.stderr)
            sys.exit(1)
        except Exception as e:
            print(f"Error: {e}", file=sys.stderr)
            sys.exit(1)


class MetricsExportCommand(BaseCommand):
    """Export a tidy evaluation table from backtest results."""

    @classmethod
    def register_parser(cls, subparsers):
        parser = subparsers.add_parser(
            "export",
            help="Export a tidy evaluation table from backtest results",
        )
        parser.add_argument("--output-base-dir", help="Backtest meta/output directory")
        parser.add_argument("--out", required=True, help="Output JSON file path")
        parser.add_argument("--algorithm-name-pattern", default=".*", help="Regex for algorithm name filtering")
        parser.add_argument("--algorithm-version-pattern", default=".*", help="Regex for algorithm version filtering")
        parser.add_argument("--from-test-date", help="Start date (YYYY-MM-DD)")
        parser.add_argument("--to-test-date", help="End date (YYYY-MM-DD)")
        parser.add_argument("--versions", nargs="*", help="Optional ordered version list")
        parser.add_argument("--metrics", nargs="*", help="Optional metric list override")
        parser.add_argument("--result-files", nargs="*", help="Optional list of result.json files")
        parser.add_argument("--result-glob", help="Optional glob pattern for result.json files")
        return parser

    def execute(self, args):
        result_files: list[str] = []
        if args.result_files:
            result_files.extend(args.result_files)
        if args.result_glob:
            result_files.extend(glob.glob(args.result_glob, recursive=True))

        if result_files:
            records = _records_from_result_files(result_files)
        else:
            if not args.output_base_dir:
                raise ValueError("--output-base-dir is required when no result files are provided.")
            from_date = date.fromisoformat(args.from_test_date) if args.from_test_date else None
            to_date = date.fromisoformat(args.to_test_date) if args.to_test_date else None
            records = _records_from_output_base_dir(
                output_base_dir=args.output_base_dir,
                algorithm_name_pattern=args.algorithm_name_pattern,
                algorithm_version_pattern=args.algorithm_version_pattern,
                from_date=from_date,
                to_date=to_date,
            )

        if not records:
            raise ValueError("No evaluation records found.")

        if args.versions:
            allowed_versions = list(args.versions)
            filtered: list[dict[str, Any]] = []
            for record in records:
                version = _version_from_algorithm_id(record["algorithm_id"])
                if any(
                    (
                        v == record["algorithm_id"],
                        v == version,
                        ("@" in v and v == record["algorithm_id"]),
                    )
                    for v in allowed_versions
                ):
                    filtered.append(record)
            records = filtered
            if not records:
                raise ValueError("No records matched --versions filter.")

        records = _filter_to_common_dates(records)

        metrics: list[str] | None = args.metrics
        if metrics == []:
            metrics = None
        if metrics is None:
            metrics = _discover_metrics(records) or list(DEFAULT_EXPORT_METRICS)
        output_rows: list[dict[str, Any]] = []
        for record in records:
            row = {
                "algorithm_id": record["algorithm_id"],
                "test_date": _parse_test_date(record["test_date"]).isoformat(),
            }
            for metric in metrics:
                row[metric] = record.get(metric)
            output_rows.append(row)

        output_rows.sort(key=lambda r: (r["algorithm_id"], r["test_date"]))
        Path(args.out).write_text(json.dumps(output_rows, indent=2, sort_keys=True))


class MetricsPlotCommand(BaseCommand):
    """Plot evaluation metrics from backtest results."""

    @classmethod
    def register_parser(cls, subparsers):
        parser = subparsers.add_parser(
            "plot",
            help="Plot evaluation metrics from backtest results",
        )
        parser.add_argument("--output-base-dir", help="Backtest meta/output directory")
        parser.add_argument("--algorithm-name-pattern", default=".*", help="Regex for algorithm name filtering")
        parser.add_argument("--algorithm-version-pattern", default=".*", help="Regex for algorithm version filtering")
        parser.add_argument("--from-test-date", help="Start date (YYYY-MM-DD)")
        parser.add_argument("--to-test-date", help="End date (YYYY-MM-DD)")
        parser.add_argument("--versions", nargs="*", help="Optional ordered version list")
        parser.add_argument("--metrics", nargs="*", help="Optional metric list override")
        parser.add_argument(
            "--relative-baseline",
            required=True,
            help="Relative baseline: either a plotted version or online:<dimension> (e.g. online:algorithm)",
        )
        parser.add_argument(
            "--baseline-description",
            help="Short human-readable description for the relative baseline shown in the PDF summary",
        )
        parser.add_argument(
            "--treatment-description",
            action="append",
            metavar="VERSION=TEXT",
            help=(
                "Short human-readable description for a plotted treatment version shown in the PDF summary. "
                "May be repeated."
            ),
        )
        parser.add_argument("--out", default="metrics-plots.pdf", help="Output PDF file path")
        parser.add_argument("--table-out", help="Optional path to write extracted table JSON")
        parser.add_argument("--result-files", nargs="*", help="Optional list of result.json files")
        parser.add_argument("--result-glob", help="Optional glob pattern for result.json files")
        return parser

    def execute(self, args):
        try:
            import matplotlib.pyplot as plt  # type: ignore
            import pandas as pd  # type: ignore
            from matplotlib.backends.backend_pdf import PdfPages  # type: ignore
        except Exception as e:  # pragma: no cover
            raise RuntimeError(
                "metrics plot requires plotting libraries. Install with `pip install 'hotvect[ext-viz]'`."
            ) from e

        result_files: list[str] = []
        if args.result_files:
            result_files.extend(args.result_files)
        if args.result_glob:
            result_files.extend(glob.glob(args.result_glob, recursive=True))

        if result_files:
            records = _records_from_result_files(result_files)
        else:
            if not args.output_base_dir:
                raise ValueError("--output-base-dir is required when no result files are provided.")
            from_date = date.fromisoformat(args.from_test_date) if args.from_test_date else None
            to_date = date.fromisoformat(args.to_test_date) if args.to_test_date else None
            records = _records_from_output_base_dir(
                output_base_dir=args.output_base_dir,
                algorithm_name_pattern=args.algorithm_name_pattern,
                algorithm_version_pattern=args.algorithm_version_pattern,
                from_date=from_date,
                to_date=to_date,
            )

        if not records:
            raise ValueError("No evaluation records found.")

        if args.versions:
            allowed_versions = list(args.versions)
            filtered: list[dict[str, Any]] = []
            for record in records:
                version = _version_from_algorithm_id(record["algorithm_id"])
                if any(
                    (
                        v == record["algorithm_id"],
                        v == version,
                        ("@" in v and v == record["algorithm_id"]),
                    )
                    for v in allowed_versions
                ):
                    filtered.append(record)
            records = filtered
            if not records:
                raise ValueError("No records matched --versions filter.")

        records = _filter_to_common_dates(records)

        metrics: list[str] | None = args.metrics
        if metrics == []:
            metrics = None
        if metrics is None:
            metrics = _discover_metrics(records) or list(DEFAULT_EXPORT_METRICS)
        plot_dataset = _build_plot_dataset(
            records=records,
            metrics=metrics,
            explicit_versions=args.versions,
            relative_baseline=args.relative_baseline,
        )
        df = plot_dataset.df
        rows = plot_dataset.table_rows
        final_metrics = plot_dataset.metrics
        versions = plot_dataset.versions
        baseline = plot_dataset.baseline
        treatment_descriptions = _parse_treatment_descriptions(args.treatment_description)
        baseline_description = _validate_plot_descriptions(
            baseline_description=args.baseline_description,
            treatment_descriptions=treatment_descriptions,
            versions=versions,
            baseline=baseline,
        )

        evaluation_specification = _common_evaluation_specification(records)
        benchmark_specification_result = _common_benchmark_specification_result(records)
        benchmark_specification = benchmark_specification_result.specification
        performance_test_warning = None
        omitted_system_metrics: list[str] = []
        if benchmark_specification_result.mismatch_summary is not None:
            omitted_system_metrics = [metric for metric in final_metrics if metric in SYSTEM_METRICS]
            if omitted_system_metrics:
                final_metrics = [metric for metric in final_metrics if metric not in SYSTEM_METRICS]
                rows = _drop_metric_columns(rows, omitted_system_metrics)
                drop_columns = [
                    column
                    for metric in omitted_system_metrics
                    for column in (metric, f"{metric}__unc_down", f"{metric}__unc_up")
                ]
                df = df.drop(columns=[column for column in drop_columns if column in df.columns])
            performance_test_warning = (
                "Performance-test latency/throughput comparisons are disabled because benchmark specifications "
                "differ across plotted records. Pipeline timing from timing_info_sec is still shown."
            )
            print(f"Warning: {performance_test_warning}", file=sys.stderr)

        if args.table_out:
            rows_sorted = sorted(rows, key=lambda r: (r["algorithm_id"], r["test_date"]))
            Path(args.table_out).write_text(json.dumps(rows_sorted, indent=2, sort_keys=True))

        out_path = Path(args.out)
        if out_path.suffix.lower() != ".pdf":
            raise ValueError("--out must be a .pdf path")
        out_path.parent.mkdir(parents=True, exist_ok=True)

        def header_page(pdf: PdfPages, title: str, lines: Sequence[str]) -> None:
            fig = plt.figure(figsize=(15, 8))
            fig.patch.set_facecolor("white")
            ax = fig.add_subplot(111)
            ax.axis("off")
            ax.text(0.5, 0.72, title, ha="center", va="center", fontsize=30, fontweight="bold")
            # Left-aligned metadata block. Callers should pre-wrap to preserve indentation.
            line_step = min(0.042, 0.56 / max(len(lines), 1))
            font_size = 13 if line_step >= 0.038 else 11 if line_step >= 0.030 else 9
            y = 0.60
            for line in lines:
                ax.text(0.08, y, line, ha="left", va="top", fontsize=font_size, family="monospace")
                y -= line_step
            fig.tight_layout()
            pdf.savefig(fig)
            plt.close(fig)

        def text_pages(
            pdf: PdfPages,
            title: str,
            lines: Sequence[str],
            *,
            lines_per_page: int = 24,
        ) -> None:
            page_lines = list(lines) or ["(none)"]
            blocks: list[list[str]] = []
            current_block: list[str] = []
            for line in page_lines:
                current_block.append(line)
                if line == "":
                    blocks.append(current_block)
                    current_block = []
            if current_block:
                blocks.append(current_block)

            pages: list[list[str]] = []
            current_page: list[str] = []
            for block in blocks:
                if len(block) > lines_per_page:
                    if current_page:
                        pages.append(current_page)
                        current_page = []
                    pages.extend(
                        block[index : index + lines_per_page] for index in range(0, len(block), lines_per_page)
                    )
                    continue
                if current_page and len(current_page) + len(block) > lines_per_page:
                    pages.append(current_page)
                    current_page = []
                current_page.extend(block)
            if current_page:
                pages.append(current_page)

            for page_index, lines_for_page in enumerate(pages, start=1):
                page_title = title if len(pages) == 1 else f"{title} ({page_index}/{len(pages)})"
                fig = plt.figure(figsize=(15, 8))
                fig.patch.set_facecolor("white")
                ax = fig.add_subplot(111)
                ax.axis("off")
                ax.text(0.5, 0.93, page_title, ha="center", va="center", fontsize=24, fontweight="bold")
                y = 0.86
                for line in lines_for_page:
                    ax.text(0.06, y, line, ha="left", va="top", fontsize=9, family="monospace")
                    y -= 0.033
                fig.tight_layout()
                pdf.savefig(fig)
                plt.close(fig)

        def format_plain_numeric_axis(ax: Any) -> None:
            from matplotlib.ticker import FuncFormatter  # type: ignore

            ax.yaxis.set_major_formatter(FuncFormatter(lambda value, _pos: f"{value:.8g}"))
            ax.yaxis.get_offset_text().set_visible(False)

        def format_relative_axis(ax: Any, metric: str) -> None:
            origin = _relative_axis_origin(metric)
            ax.axhline(origin, color="#666666", linewidth=0.8, linestyle="--", alpha=0.7)

        _VERSION_COLORS = plt.get_cmap("tab10").colors

        def _version_color(version_index: int) -> Any:
            return _VERSION_COLORS[version_index % len(_VERSION_COLORS)]

        def point_plot(
            ax: Any,
            data: pd.DataFrame,
            metric: str,
            title: str,
            *,
            relative: bool = False,
            plain_numeric_axis: bool = False,
            baseline_version: str | None = None,
        ) -> None:
            unc_down_col = f"{metric}__unc_down"
            unc_up_col = f"{metric}__unc_up"
            has_unc = unc_down_col in data.columns and unc_up_col in data.columns
            origin = _relative_axis_origin(metric) if relative else 0.0
            all_versions = [v for v in versions if v in data["version"].cat.categories]
            draw_baseline_as_band = relative and baseline_version is not None
            # When drawing the baseline as a band, exclude it from the x-axis tick list
            tick_versions = [v for v in all_versions if not (draw_baseline_as_band and v == baseline_version)]
            x_pos = {v: i for i, v in enumerate(tick_versions)}

            for version in all_versions:
                vdata = data[data["version"] == version].dropna(subset=[metric])
                if vdata.empty:
                    continue
                central = float(vdata[metric].mean())
                if has_unc:
                    per_date_uncertainties = list(
                        zip(
                            vdata[unc_down_col].fillna(0.0).tolist(),
                            vdata[unc_up_col].fillna(0.0).tolist(),
                        )
                    )
                    unc = _combine_gaussian_uncertainties(per_date_uncertainties)
                    unc_down, unc_up = unc, unc
                else:
                    unc_down, unc_up = 0.0, 0.0
                if draw_baseline_as_band and version == baseline_version:
                    ax.axhspan(origin - unc_down, origin + unc_up, color="gray", alpha=0.2, zorder=0)
                else:
                    i = x_pos[version]
                    color = _version_color(i)
                    ax.errorbar(
                        i,
                        central,
                        yerr=[[unc_down], [unc_up]],
                        fmt="o",
                        color=color,
                        capsize=4,
                        capthick=1.2,
                        elinewidth=1.2,
                        markersize=6,
                        zorder=3,
                    )

            display_title = _plot_metric_label(metric, relative=relative)
            ax.set_title(display_title, fontsize=10)
            ax.set_ylabel(display_title, fontsize=9)
            ax.set_xticks(list(range(len(tick_versions))))
            ax.set_xticklabels([str(v) for v in tick_versions])
            ax.yaxis.grid(True)
            if relative:
                format_relative_axis(ax, metric)
            elif plain_numeric_axis:
                format_plain_numeric_axis(ax)
            for label in ax.get_xticklabels():
                label.set_rotation(45)
                label.set_horizontalalignment("right")

        def time_series_plot(
            ax: Any,
            data: pd.DataFrame,
            metric: str,
            title: str,
            *,
            show_legend: bool,
            relative: bool = False,
            plain_numeric_axis: bool = False,
            baseline_version: str | None = None,
            baseline_point_data: pd.DataFrame | None = None,
        ) -> bool:
            if "test_date" not in data.columns:
                return False
            plot_data = data.copy()
            plot_data["test_date"] = pd.to_datetime(plot_data["test_date"], errors="coerce")
            plot_data = plot_data.dropna(subset=["test_date", metric])
            if plot_data.empty:
                return False

            unc_down_col = f"{metric}__unc_down"
            unc_up_col = f"{metric}__unc_up"
            has_unc = unc_down_col in plot_data.columns and unc_up_col in plot_data.columns
            origin = _relative_axis_origin(metric) if relative else 0.0
            ordered_versions = [v for v in versions if v in plot_data["version"].cat.categories]

            # For relative time series, draw a date-varying gray band for the baseline.
            # fill_between with a single point has zero x-width, so we use axhspan per date
            # extended to the half-interval between adjacent ticks.
            if relative and baseline_version is not None and baseline_point_data is not None:
                band_data = baseline_point_data.copy()
                band_data["test_date"] = pd.to_datetime(band_data["test_date"], errors="coerce")
                band_data = band_data.dropna(subset=["test_date"]).sort_values("test_date")
                band_has_unc = (
                    not band_data.empty and unc_down_col in band_data.columns and unc_up_col in band_data.columns
                )
                if not band_data.empty:
                    import matplotlib.dates as mdates  # type: ignore
                    from matplotlib.patches import Patch, Rectangle  # type: ignore

                    bd = band_data[unc_down_col].fillna(0.0).values if band_has_unc else [0.0] * len(band_data)
                    bu = band_data[unc_up_col].fillna(0.0).values if band_has_unc else [0.0] * len(band_data)
                    dates_ts = list(band_data["test_date"])
                    n = len(dates_ts)
                    for j, (dt, down, up) in enumerate(zip(dates_ts, bd, bu)):
                        dt_num = float(mdates.date2num(dt))
                        # extend each band to the midpoint between adjacent date ticks
                        if n == 1:
                            half = 0.5  # half a day on each side
                        else:
                            prev_num = float(mdates.date2num(dates_ts[j - 1])) if j > 0 else dt_num
                            next_num = float(mdates.date2num(dates_ts[j + 1])) if j < n - 1 else dt_num
                            half = max(
                                (dt_num - prev_num) / 2 if j > 0 else (next_num - dt_num) / 2,
                                (next_num - dt_num) / 2 if j < n - 1 else (dt_num - prev_num) / 2,
                            )
                        rect = Rectangle(
                            (dt_num - half, origin - down),
                            2 * half,
                            down + up,
                            linewidth=0,
                            facecolor="gray",
                            alpha=0.2,
                            zorder=0,
                            transform=ax.transData,
                        )
                        ax.add_patch(rect)
                    ax.add_artist(
                        ax.legend(
                            handles=[Patch(facecolor="gray", alpha=0.4, label=f"{baseline_version} (band)")],
                            loc="upper left",
                            fontsize=8,
                        )
                    )

            for i, version in enumerate(ordered_versions):
                vdata = plot_data[plot_data["version"] == version].sort_values("test_date")
                if vdata.empty:
                    continue
                color = _version_color(i)
                dates = vdata["test_date"].values
                values_arr = vdata[metric].values
                if has_unc:
                    unc_down = vdata[unc_down_col].fillna(0.0).values
                    unc_up = vdata[unc_up_col].fillna(0.0).values
                else:
                    unc_down = [0.0] * len(vdata)
                    unc_up = [0.0] * len(vdata)
                ax.errorbar(
                    dates,
                    values_arr,
                    yerr=[unc_down, unc_up],
                    fmt="-o",
                    color=color,
                    alpha=0.72,
                    capsize=3,
                    capthick=1.0,
                    elinewidth=1.0,
                    markersize=5,
                    label=str(version),
                    zorder=3,
                )

            display_title = _plot_metric_label(metric, relative=relative)
            ax.set_title(display_title, fontsize=10)
            ax.set_ylabel(display_title, fontsize=9)
            ax.yaxis.grid(True)
            if relative:
                format_relative_axis(ax, metric)
            elif plain_numeric_axis:
                format_plain_numeric_axis(ax)
            ax.set_xlabel("test_date")
            all_dates = plot_data["test_date"].dt.normalize().dt.tz_localize(None)
            unique_dates = sorted(set(all_dates))
            ax.set_xticks(unique_dates)
            ax.set_xticklabels([d.strftime("%Y-%m-%d") for d in unique_dates], rotation=45, ha="right")
            if show_legend:
                ax.legend(fontsize=8)
            else:
                legend = ax.get_legend()
                if legend is not None:
                    legend.remove()
            return True

        def metric_grid_page(
            pdf: PdfPages,
            data: pd.DataFrame,
            metric_group: Sequence[str],
            title: str,
            plot_kind: str,
            *,
            relative: bool = False,
            plain_numeric_axis: bool = False,
            baseline_version: str | None = None,
            relative_point_frames: dict[str, Any] | None = None,
        ) -> None:
            fig, axes = plt.subplots(
                nrows=max(1, (len(metric_group) + PLOTS_PER_ROW - 1) // PLOTS_PER_ROW),
                ncols=PLOTS_PER_ROW,
                figsize=(16, 12),
            )
            axes_list = list(getattr(axes, "flat", [axes]))
            fig.patch.set_facecolor("white")
            fig.suptitle(title, fontsize=18, fontweight="bold")

            for index, metric in enumerate(metric_group):
                ax = axes_list[index]
                if plot_kind == "point":
                    point_plot(
                        ax,
                        data,
                        metric,
                        metric,
                        relative=relative,
                        plain_numeric_axis=plain_numeric_axis,
                        baseline_version=baseline_version,
                    )
                elif plot_kind == "time":
                    # For relative time series: supply the baseline slice from the point frame
                    # so the date-varying band can be drawn.
                    baseline_point_data: pd.DataFrame | None = None
                    if relative and baseline_version is not None and relative_point_frames is not None:
                        point_frame = relative_point_frames.get(metric)
                        if point_frame is not None:
                            baseline_slice = point_frame[point_frame["version"] == baseline_version]
                            if not baseline_slice.empty:
                                baseline_point_data = baseline_slice
                    plotted = time_series_plot(
                        ax,
                        data,
                        metric,
                        metric,
                        show_legend=index == 0,
                        relative=relative,
                        plain_numeric_axis=plain_numeric_axis,
                        baseline_version=baseline_version,
                        baseline_point_data=baseline_point_data,
                    )
                    if not plotted:
                        ax.axis("off")
                        ax.set_title(f"{_plot_metric_label(metric)} (no data)", fontsize=10)
                else:
                    raise ValueError(f"Unsupported plot_kind: {plot_kind}")

            for ax in axes_list[len(metric_group) :]:
                ax.axis("off")

            fig.tight_layout(rect=(0, 0, 1, 0.97))
            pdf.savefig(fig)
            plt.close(fig)

        def timing_breakdown_page(
            pdf: PdfPages,
            breakdown_dataset: TimingBreakdownDataset,
            version_order: Sequence[str],
        ) -> None:
            components = _production_training_timing_components(breakdown_dataset.components)
            if not components:
                return

            component_values = breakdown_dataset.df[["version", *components]].copy()
            component_values[components] = component_values[components].fillna(0.0)
            grouped = component_values.groupby("version", observed=False)[components].mean()
            grouped = grouped.reindex(version_order).fillna(0.0)
            grouped = grouped.loc[:, (grouped != 0).any(axis=0)]
            if grouped.empty:
                return
            if not _has_production_training_breakdown_signal(list(grouped.columns)):
                return

            fig, ax = plt.subplots(figsize=(16, 9))
            fig.patch.set_facecolor("white")
            fig.suptitle(
                "Production training pipeline breakdown (mean seconds; 95% CI; lower is better)",
                fontsize=18,
                fontweight="bold",
            )
            fig.text(
                0.5,
                0.91,
                (
                    "Shows dependency-internal stages when available, plus generate-state/encode-parameter, encode, "
                    "train, and parameter packaging. Excludes predict/evaluate/performance-test because those are "
                    "offline backtest stages."
                ),
                ha="center",
                va="center",
                fontsize=10,
            )

            x_positions = list(range(len(grouped.index)))
            bottoms = [0.0] * len(grouped.index)
            colors = plt.get_cmap("tab20").colors
            for component_index, component in enumerate(grouped.columns):
                values = [float(value) for value in grouped[component].tolist()]
                ax.bar(
                    x_positions,
                    values,
                    bottom=bottoms,
                    label=_compact_metric_label(component, max_length=70),
                    color=colors[component_index % len(colors)],
                )
                bottoms = [bottom + value for bottom, value in zip(bottoms, values)]

            total_estimates: dict[str, tuple[float, tuple[float, float] | None]] = {}
            component_values["_total"] = component_values[list(grouped.columns)].sum(axis=1)
            for version in grouped.index:
                version_totals = (
                    component_values[component_values["version"] == version]["_total"].astype(float).tolist()
                )
                estimate = _mean_ci_95(version_totals)
                if estimate is not None:
                    total_estimates[str(version)] = estimate

            for x_position, version, total in zip(x_positions, grouped.index, bottoms):
                mean, ci = total_estimates.get(str(version), (total, None))
                if ci is not None:
                    low, high = ci
                    ax.errorbar(
                        [x_position],
                        [mean],
                        yerr=[[mean - low], [high - mean]],
                        fmt="none",
                        ecolor="black",
                        elinewidth=1.2,
                        capsize=5,
                    )
                ax.text(
                    x_position,
                    mean,
                    _format_seconds(mean),
                    ha="center",
                    va="bottom",
                    fontsize=9,
                )

            ax.set_xticks(x_positions)
            ax.set_xticklabels([str(version) for version in grouped.index], rotation=35, ha="right")
            ax.set_ylabel("production training path seconds")
            ax.yaxis.grid(True)
            format_plain_numeric_axis(ax)
            ax.legend(loc="center left", bbox_to_anchor=(1.02, 0.5), fontsize=8)
            fig.tight_layout(rect=(0, 0, 0.82, 0.88))
            pdf.savefig(fig)
            plt.close(fig)

        n_dates = int(df["test_date"].nunique())
        date_min = str(df["test_date"].min())
        date_max = str(df["test_date"].max())

        result_file_paths = [Path(p) for p in result_files]
        if result_file_paths:
            try:
                common_root = Path(os.path.commonpath([str(p) for p in result_file_paths]))
                if common_root.suffix:
                    common_root = common_root.parent
            except Exception:
                common_root = result_file_paths[0].parent
            source_root = str(common_root)
            source_files = len(result_file_paths)
        else:
            source_root = str(args.output_base_dir)
            source_files = 0

        non_online_algorithm_ids = list(dict.fromkeys(str(record["algorithm_id"]) for record in records))
        if baseline.kind == "online":
            baseline_label = baseline.display_name
            treatment_algorithm_ids = non_online_algorithm_ids
        else:
            baseline_label = _select_algorithm_id(records, baseline.value)
            treatment_algorithm_ids = [algo_id for algo_id in non_online_algorithm_ids if algo_id != baseline_label]
            if not treatment_algorithm_ids:
                treatment_algorithm_ids = [baseline_label]
        header_lines_common = _build_header_lines(
            source_root=source_root,
            source_files=source_files,
            treatment_algorithm_ids=treatment_algorithm_ids,
            baseline_label=baseline_label,
            baseline_description=baseline_description,
            treatment_descriptions=treatment_descriptions,
            from_last_test_date=date_min,
            to_last_test_date=date_max,
            n_dates=n_dates,
            date_min=date_min,
            date_max=date_max,
            metrics=final_metrics,
        )
        generated_at = datetime.now().astimezone().strftime("%Y-%m-%d %H:%M %Z").strip()
        generated_with = f"hotvect {_report_hotvect_version()}"
        treatment_specs = [
            _algorithm_specification_for_algorithm(records, algorithm_id) for algorithm_id in treatment_algorithm_ids
        ]
        specification_lines = _build_specification_lines(
            generated_at=generated_at,
            generated_with=generated_with,
            evaluation_specification=evaluation_specification,
            benchmark_specification=benchmark_specification,
            treatment_specs=treatment_specs,
        )
        timing_dataset = _build_timing_plot_dataset(records, versions)
        timing_breakdown_dataset = _build_timing_breakdown_dataset(records, versions)

        with PdfPages(out_path) as pdf:
            relative_baseline_display = baseline.display_name
            header_page(pdf, "Metrics Report", header_lines_common)
            # Benchmark reports can compare many treatments; paginate these details instead of
            # squeezing them into the short title-page layout.
            text_pages(pdf, "Specification", specification_lines, lines_per_page=22)
            text_pages(pdf, "Result Provenance", _build_provenance_lines(records))
            (
                relative_point_metrics,
                relative_time_metrics,
                relative_point_frames,
                relative_time_frames,
            ) = _collect_relative_metric_frames(
                df=df,
                metrics=final_metrics,
                versions=versions,
                baseline_version=baseline.version_selector,
            )

            if not relative_point_metrics and not relative_time_metrics and performance_test_warning is None:
                raise ValueError(f"No metrics available for relative baseline: {relative_baseline_display}")

            for page_num, metric_group in enumerate(_chunked_metrics(relative_point_metrics), start=1):
                metric_grid_page(
                    pdf,
                    pd.concat([relative_point_frames[m] for m in metric_group], ignore_index=True),
                    metric_group,
                    f"Relative point plots (baseline={relative_baseline_display}; page {page_num})",
                    "point",
                    relative=True,
                    baseline_version=baseline.version_selector,
                )

            for page_num, metric_group in enumerate(_chunked_metrics(relative_time_metrics), start=1):
                metric_grid_page(
                    pdf,
                    pd.concat([relative_time_frames[m] for m in metric_group], ignore_index=True),
                    metric_group,
                    f"Relative time series (baseline={relative_baseline_display}; non-baseline variants; page {page_num})",
                    "time",
                    relative=True,
                    baseline_version=baseline.version_selector,
                    relative_point_frames=relative_point_frames,
                )

            absolute_metrics = [
                metric for metric in final_metrics if metric in df.columns and not df[metric].dropna().empty
            ]
            for page_num, metric_group in enumerate(_chunked_metrics(absolute_metrics), start=1):
                metric_grid_page(
                    pdf,
                    df,
                    metric_group,
                    f"Absolute point plots (page {page_num})",
                    "point",
                )
            for page_num, metric_group in enumerate(_chunked_metrics(absolute_metrics), start=1):
                metric_grid_page(
                    pdf,
                    df,
                    metric_group,
                    f"Absolute time series (page {page_num})",
                    "time",
                )

            if performance_test_warning is not None:
                text_pages(pdf, "Performance Test", ["Warning", performance_test_warning])

            if timing_dataset is not None:
                timing_summary_lines = _build_timing_summary_lines(
                    timing_dataset,
                    versions,
                    baseline.version_selector,
                )
                has_cache_hits = _has_cache_hits(records)
                if has_cache_hits:
                    timing_summary_lines.extend(
                        [
                            "",
                            "Production Training Breakdown",
                            (
                                "Omitted because cache-hit stages were recorded in the plotted result files. "
                                "Cached backtest timings are not representative of production training."
                            ),
                        ]
                    )
                timing_summary_lines.extend(_build_cache_usage_lines(records))
                text_pages(pdf, "Pipeline Performance", timing_summary_lines)
                if timing_breakdown_dataset is not None and not has_cache_hits:
                    timing_breakdown_page(pdf, timing_breakdown_dataset, versions)
                (
                    timing_relative_point_metrics,
                    _timing_relative_time_metrics,
                    timing_relative_point_frames,
                    _timing_relative_time_frames,
                ) = _collect_relative_metric_frames(
                    df=timing_dataset.df,
                    metrics=timing_dataset.metrics,
                    versions=versions,
                    baseline_version=baseline.version_selector,
                )

                for page_num, metric_group in enumerate(_chunked_metrics(timing_relative_point_metrics), start=1):
                    metric_grid_page(
                        pdf,
                        pd.concat([timing_relative_point_frames[m] for m in metric_group], ignore_index=True),
                        metric_group,
                        (
                            "Pipeline performance relative point plots "
                            f"(seconds; lower is better; baseline={relative_baseline_display}; page {page_num})"
                        ),
                        "point",
                        relative=True,
                    )

                for page_num, metric_group in enumerate(_chunked_metrics(timing_dataset.metrics), start=1):
                    metric_grid_page(
                        pdf,
                        timing_dataset.df,
                        metric_group,
                        f"Pipeline performance absolute point plots (seconds; lower is better; page {page_num})",
                        "point",
                        plain_numeric_axis=True,
                    )
