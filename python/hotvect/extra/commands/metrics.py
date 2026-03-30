"""Metrics subcommands for hv-ext CLI."""

from __future__ import annotations

import glob
import json
import os
import statistics
import sys
import textwrap
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence

from hotvect.backtest import extract_evaluation_result
from hotvect.mlutils import extract_evaluation

from .base import BaseCommand

QUALITY_METRICS = (
    "roc_auc",
    "pr_auc",
    "mean_score",
    "map_at_10",
    "map_at_50",
    "map_at_all",
    "ndcg_at_10",
    "ndcg_at_50",
    "ndcg_at_all",
    "diversity@5",
    "diversity@10",
    "diversity@30",
)

SYSTEM_METRICS = (
    "max_memory_usage",
    "mean_throughput",
    "p50",
    "p75",
    "p95",
    "p99",
    "p999",
)

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
    "test_date",
    "version",
}

PLOTS_PER_PAGE = 4
PLOTS_PER_ROW = 2


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
    table_rows: List[Dict[str, Any]]
    metrics: List[str]
    versions: List[str]
    baseline: RelativeBaselineSpec


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


def _require_numeric(value: Any, label: str) -> float:
    if isinstance(value, (int, float)):
        return float(value)
    raise ValueError(f"Non-numeric value for {label}: {value!r}")


def _load_result_json(path: Path) -> Dict[str, Any]:
    raw = json.loads(path.read_text())
    if not isinstance(raw, dict):
        raise ValueError(f"result.json root is not an object: {path}")
    return raw


def _extract_metrics_from_nested(obj: Any, prefix: str = "") -> Dict[str, float]:
    metrics: Dict[str, float] = {}
    if not isinstance(obj, dict):
        return metrics

    for key, value in obj.items():
        full_key = f"{prefix}.{key}" if prefix else str(key)
        if isinstance(value, (int, float)):
            metrics[full_key] = float(value)
        elif isinstance(value, dict):
            mean_value = value.get("mean")
            if isinstance(mean_value, (int, float)):
                # Extract .mean values (like roc_auc.mean)
                metrics[full_key] = float(mean_value)
            else:
                metrics.update(_extract_metrics_from_nested(value, full_key))
    return metrics


def _load_evaluation_file(file_path: str) -> Dict[str, float]:
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
    return _extract_metrics_from_nested(data)


def _extract_performance_block(data: Any) -> Dict[str, Any]:
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
    if not isinstance(data, dict):
        raise ValueError("Performance data must be a JSON object")
    return data


def _normalize_performance(perf_data: Dict[str, Any]) -> Dict[str, Any]:
    response_time_metrics = perf_data.get("response_time_metrics") or {}
    if response_time_metrics and not isinstance(response_time_metrics, dict):
        raise ValueError("response_time_metrics must be a JSON object when present")

    def require_numeric(value: Any, name: str) -> Optional[float]:
        if value is None:
            return None
        if isinstance(value, (int, float)):
            return float(value)
        raise ValueError(f"Non-numeric value for {name}: {value!r}")

    def metric_mean(metrics: Dict[str, Any], key: str) -> Optional[float]:
        if key not in metrics:
            return None
        value = metrics.get(key)
        if isinstance(value, dict):
            if "mean" not in value:
                raise ValueError(f"response_time_metrics.{key} missing mean")
            return require_numeric(value.get("mean"), f"response_time_metrics.{key}.mean")
        return require_numeric(value, f"response_time_metrics.{key}")

    mean_throughput: Optional[float]
    if "mean_throughput" in perf_data:
        mean_throughput = require_numeric(perf_data.get("mean_throughput"), "mean_throughput")
    else:
        mean_throughput = metric_mean(response_time_metrics, "mean_throughput")

    normalized_rt: Dict[str, Dict[str, float]] = {}
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

    max_memory_usage: Optional[float] = None
    if "max_memory_usage" in perf_data:
        max_memory_usage = require_numeric(perf_data.get("max_memory_usage"), "max_memory_usage")

    return {
        "max_memory_usage": max_memory_usage,
        "mean_throughput": mean_throughput,
        "response_time_metrics": normalized_rt,
    }


def _validate_normalized_performance(normalized: Dict[str, Any], file_path: str) -> None:
    missing: List[str] = []
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


def _load_performance_file(file_path: str) -> Dict[str, Any]:
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
    from_date: Optional[date],
    to_date: Optional[date],
) -> List[Dict[str, Any]]:
    records: List[Dict[str, Any]] = []
    for record in extract_evaluation_result(
        output_base_dir=output_base_dir,
        algorithm_name_pattern=algorithm_name_pattern,
        algorithm_version_pattern=algorithm_version_pattern,
        from_including_test_date=from_date,
        to_including_test_date=to_date,
    ):
        if "algorithm_id" not in record or "test_date" not in record:
            raise ValueError("Missing algorithm_id/test_date in extracted record")
        normalized = dict(record)
        normalized["test_date"] = _parse_test_date(normalized["test_date"])
        records.append(normalized)
    return records


def _records_from_result_files(result_files: Sequence[str]) -> List[Dict[str, Any]]:
    records: List[Dict[str, Any]] = []
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

        # Enrich/normalize system metrics using the same logic as hv-ext perf compare.
        # `extract_evaluation` does not always map throughput into `mean_throughput`.
        try:
            perf = _load_performance_file(file_path)
        except Exception:
            perf = None
        if perf:
            if extracted.get("max_memory_usage") is None:
                extracted["max_memory_usage"] = perf.get("max_memory_usage")
            if extracted.get("mean_throughput") is None:
                extracted["mean_throughput"] = perf.get("mean_throughput")

            rt = perf.get("response_time_metrics") or {}
            for key in ["p50", "p75", "p95", "p99", "p999"]:
                if extracted.get(key) is None:
                    metric = rt.get(key)
                    if isinstance(metric, dict):
                        extracted[key] = metric.get("mean")

        records.append(extracted)
    return records


def _discover_metrics(records: Sequence[Dict[str, Any]]) -> List[str]:
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
            if isinstance(value, (int, float)):
                candidates.add(key)

    if not candidates:
        return []

    discovered: List[str] = []
    for metric in sorted(candidates):
        ok = True
        for record in records:
            value = record.get(metric)
            if value is None or not isinstance(value, (int, float)):
                ok = False
                break
        if ok:
            discovered.append(metric)

    preferred_order = list(QUALITY_METRICS) + list(SYSTEM_METRICS)
    ordered: List[str] = [m for m in preferred_order if m in discovered]
    ordered.extend([m for m in discovered if m not in ordered])
    return ordered


def _select_algorithm_id(records: Sequence[Dict[str, Any]], selector: str) -> str:
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


def _common_dates_by_version(records: Sequence[Dict[str, Any]]) -> List[date]:
    by_version: Dict[str, set[date]] = {}
    for record in records:
        version = _version_from_algorithm_id(record["algorithm_id"])
        by_version.setdefault(version, set()).add(record["test_date"])
    if not by_version:
        return []
    common = set.intersection(*by_version.values())
    return sorted(common)


def _filter_to_common_dates(records: Sequence[Dict[str, Any]]) -> List[Dict[str, Any]]:
    common_dates = _common_dates_by_version(records)
    if not common_dates:
        raise ValueError("No common test dates across versions.")
    allowed = set(common_dates)
    return [r for r in records if r["test_date"] in allowed]


def _aggregate_by_date(
    records: Sequence[Dict[str, Any]],
    metric_names: Sequence[str],
) -> Dict[date, Dict[str, float]]:
    by_date: Dict[date, Dict[str, List[float]]] = {}
    for record in records:
        day = record["test_date"]
        by_date.setdefault(day, {})
        for metric in metric_names:
            if metric not in record:
                continue
            value = record[metric]
            if value is None:
                continue
            by_date[day].setdefault(metric, []).append(_require_numeric(value, metric))

    aggregated: Dict[date, Dict[str, float]] = {}
    for day, metrics in by_date.items():
        aggregated[day] = {m: statistics.fmean(vals) for m, vals in metrics.items() if vals}
    return aggregated


def _metrics_present_for_all_dates(
    control_by_date: Dict[date, Dict[str, float]],
    treatment_by_date: Dict[date, Dict[str, float]],
    dates: Sequence[date],
) -> List[str]:
    metrics: List[str] = []
    for metric in sorted(set().union(*[control_by_date[d].keys() for d in dates])):
        if all(metric in control_by_date[d] and metric in treatment_by_date[d] for d in dates):
            metrics.append(metric)
    return metrics


def _compare_metrics_across_dates(
    control_by_date: Dict[date, Dict[str, float]],
    treatment_by_date: Dict[date, Dict[str, float]],
    dates: Sequence[date],
) -> Dict[str, Dict[str, Optional[float]]]:
    metrics = _metrics_present_for_all_dates(control_by_date, treatment_by_date, dates)
    if not metrics:
        raise ValueError("No common metrics across control/treatment for the paired dates.")

    result: Dict[str, Dict[str, Optional[float]]] = {}
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


def _write_json_output(payload: Dict[str, Any], output_path: Optional[str]) -> None:
    rendered = json.dumps(payload, indent=2, sort_keys=True)
    if output_path:
        Path(output_path).write_text(rendered)
    else:
        print(rendered)


def _chunked_metrics(metrics: Sequence[str], chunk_size: int = PLOTS_PER_PAGE) -> List[List[str]]:
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


def _online_metric_column(dimension: str, metric: str) -> str:
    return f"__online__{dimension}.{metric}"


def _build_plot_rows(
    records: Sequence[Dict[str, Any]],
    metrics: Sequence[str],
    baseline: RelativeBaselineSpec,
) -> List[Dict[str, Any]]:
    raw_rows: List[Dict[str, Any]] = []
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
    raw_rows: Sequence[Dict[str, Any]],
    metrics: Sequence[str],
    baseline: RelativeBaselineSpec,
) -> List[str]:
    test_dates = sorted({row["test_date"] for row in raw_rows})
    supported_metrics: List[str] = []
    for metric in metrics:
        online_values_by_date = {}
        online_col = _online_metric_column(baseline.value, metric)
        for row in raw_rows:
            value = row.get(online_col)
            if value is not None:
                online_values_by_date.setdefault(row["test_date"], value)
        if len(online_values_by_date) == len(test_dates):
            supported_metrics.append(metric)
    return supported_metrics


def _synthesize_online_control_rows(
    raw_rows: Sequence[Dict[str, Any]],
    metrics: Sequence[str],
    baseline: RelativeBaselineSpec,
) -> List[Dict[str, Any]]:
    online_rows: List[Dict[str, Any]] = []
    for test_date in sorted({row["test_date"] for row in raw_rows}):
        source = next(row for row in raw_rows if row["test_date"] == test_date)
        online_row = {
            "algorithm_id": f"online:{baseline.value}",
            "test_date": test_date,
            "version": baseline.version_selector,
        }
        for metric in metrics:
            online_row[metric] = source.get(_online_metric_column(baseline.value, metric))
        online_rows.append(online_row)
    return online_rows


def _table_rows_for_metrics(rows: Sequence[Dict[str, Any]], metrics: Sequence[str]) -> List[Dict[str, Any]]:
    table_rows: List[Dict[str, Any]] = []
    for row in rows:
        output_row = {
            "algorithm_id": row["algorithm_id"],
            "test_date": row["test_date"],
            "version": row["version"],
        }
        for metric in metrics:
            output_row[metric] = row.get(metric)
        table_rows.append(output_row)
    return table_rows


def _resolve_plot_versions(
    table_rows: Sequence[Dict[str, Any]],
    explicit_versions: Optional[Sequence[str]],
    baseline: RelativeBaselineSpec,
) -> List[str]:
    versions = list(dict.fromkeys(row["version"] for row in table_rows))
    if explicit_versions:
        versions = [v.split("@", 1)[-1] for v in explicit_versions]
    if baseline.kind == "online":
        versions = [
            baseline.version_selector,
            *[version for version in versions if version != baseline.version_selector],
        ]
    return list(dict.fromkeys(versions))


def _build_plot_dataset(
    records: Sequence[Dict[str, Any]],
    metrics: Sequence[str],
    explicit_versions: Optional[Sequence[str]],
    relative_baseline: str,
) -> PlotDataset:
    import pandas as pd  # type: ignore

    baseline = _parse_relative_baseline(relative_baseline)
    raw_rows = _build_plot_rows(records, metrics, baseline)

    final_metrics = list(metrics)
    online_rows: List[Dict[str, Any]] = []
    if baseline.kind == "online":
        final_metrics = _resolve_online_metrics(raw_rows, final_metrics, baseline)
        if not final_metrics:
            raise ValueError(f"No metrics available for online baseline: {baseline.display_name}")
        online_rows = _synthesize_online_control_rows(raw_rows, final_metrics, baseline)

    table_rows = _table_rows_for_metrics(raw_rows, final_metrics)
    table_rows.extend(online_rows)

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


def _relative_frame(
    df: "Any",
    metric: str,
    versions: Sequence[str],
    baseline_version: str,
    *,
    include_baseline: bool,
) -> "Any":
    pivoted = df.pivot_table(
        values=metric,
        index=["version", "test_date"],
        aggfunc="mean",
        observed=False,
    ).reset_index()
    if metric not in pivoted.columns:
        return df.iloc[0:0].copy()

    baseline = pivoted[pivoted["version"] == baseline_version].set_index("test_date")[metric].to_dict()
    if not baseline:
        return df.iloc[0:0].copy()

    relative_rows: List[Dict[str, Any]] = []
    for _, row in pivoted.iterrows():
        version = row["version"]
        if not include_baseline and version == baseline_version:
            continue
        base = baseline.get(row["test_date"])
        val = row[metric]
        if base is None or val is None:
            continue
        if metric in ("roc_auc", "pr_auc"):
            rel = val - base
        else:
            rel = None if base == 0 else val / base
        if rel is None:
            continue
        relative_rows.append({"version": version, "test_date": row["test_date"], metric: rel})

    if not relative_rows:
        return df.iloc[0:0].copy()

    rel_df = df.iloc[0:0][["version", "test_date"]].copy()
    rel_df = rel_df.reindex(range(len(relative_rows)))
    for column in list(rel_df.columns):
        if column not in {"version", "test_date"}:
            del rel_df[column]
    import pandas as pd  # type: ignore

    rel_df = pd.DataFrame(relative_rows)
    categories = [version for version in versions if include_baseline or version != baseline_version]
    rel_df["version"] = pd.Categorical(rel_df["version"], categories=categories, ordered=True)
    return rel_df


def _collect_relative_metric_frames(
    df: "Any",
    metrics: Sequence[str],
    versions: Sequence[str],
    baseline_version: str,
) -> tuple[List[str], List[str], Dict[str, "Any"], Dict[str, "Any"]]:
    relative_point_metrics: List[str] = []
    relative_time_metrics: List[str] = []
    relative_point_frames: Dict[str, "Any"] = {}
    relative_time_frames: Dict[str, "Any"] = {}

    for metric in metrics:
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


def _truncate_metric_list(items: List[str], *, limit: int = 40) -> List[str]:
    if len(items) <= limit:
        return items
    remaining = len(items) - limit
    return [*items[:limit], f"… (+{remaining} more)"]


def _wrap_metric_list(prefix: str, items: List[str]) -> List[str]:
    if not items:
        return []
    content = ", ".join(items)
    filled = textwrap.fill(
        content,
        width=110,
        initial_indent=prefix,
        subsequent_indent=" " * len(prefix),
    )
    return filled.splitlines()


def _build_header_lines(
    *,
    source_root: str,
    source_files: int,
    versions: Sequence[str],
    n_dates: int,
    date_min: str,
    date_max: str,
    metrics: Sequence[str],
) -> List[str]:
    versions_display = ", ".join(str(v) for v in versions)
    if len(versions) == 2:
        versions_display = f"{versions[0]}  vs  {versions[1]}"

    metrics_quality = _truncate_metric_list([m for m in metrics if m in QUALITY_METRICS])
    metrics_system = _truncate_metric_list([m for m in metrics if m in SYSTEM_METRICS])
    metrics_other = _truncate_metric_list([m for m in metrics if m not in QUALITY_METRICS and m not in SYSTEM_METRICS])

    root_lines = textwrap.fill(
        source_root,
        width=110,
        initial_indent="  root:    ",
        subsequent_indent=" " * 10,
    ).splitlines()
    files_line = f"  files:   {source_files} result.json" if source_files else "  files:   (from output_base_dir scan)"

    header_lines: List[str] = []
    header_lines.append("Input")
    header_lines.extend(root_lines)
    header_lines.append(files_line)
    header_lines.append("")
    header_lines.append("Selection")
    header_lines.append(f"  versions:   {versions_display}")
    header_lines.append(f"  dates:      {n_dates} common days ({date_min} .. {date_max})")
    header_lines.append("")
    header_lines.append(f"Metrics ({len(metrics)})")
    header_lines.extend(_wrap_metric_list("  quality:  ", metrics_quality))
    header_lines.extend(_wrap_metric_list("  system:   ", metrics_system))
    header_lines.extend(_wrap_metric_list("  other:    ", metrics_other))
    return header_lines


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

            per_metric: Dict[str, Dict[str, Optional[float]]] = {}
            for metric in common_metrics:
                control_mean = _require_numeric(control_filtered[metric], metric)
                treatment_mean = _require_numeric(treatment_filtered[metric], metric)
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
            dates_used: List[str] = []
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

            per_metric: Dict[str, Dict[str, Optional[float]]] = {}
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

            control_date: Optional[str] = None
            treatment_date: Optional[str] = None
            try:
                control_date = _load_result_json(Path(args.control_file)).get("test_data_time")
                treatment_date = _load_result_json(Path(args.treatment_file)).get("test_data_time")
            except Exception:
                pass

            dates_used: List[str] = []
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
        result_files: List[str] = []
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
            filtered: List[Dict[str, Any]] = []
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

        metrics: Optional[List[str]] = args.metrics
        if metrics == []:
            metrics = None
        if metrics is None:
            metrics = _discover_metrics(records) or list(DEFAULT_EXPORT_METRICS)
        output_rows: List[Dict[str, Any]] = []
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
        parser.add_argument("--out", default="metrics-plots.pdf", help="Output PDF file path")
        parser.add_argument("--table-out", help="Optional path to write extracted table JSON")
        parser.add_argument("--result-files", nargs="*", help="Optional list of result.json files")
        parser.add_argument("--result-glob", help="Optional glob pattern for result.json files")
        return parser

    def execute(self, args):
        try:
            import matplotlib.pyplot as plt  # type: ignore
            import pandas as pd  # type: ignore
            import seaborn as sns  # type: ignore
            from matplotlib.backends.backend_pdf import PdfPages  # type: ignore
        except Exception as e:  # pragma: no cover
            raise RuntimeError(
                "metrics plot requires matplotlib and seaborn. Install with `pip install 'hotvect[ext-viz]'`."
            ) from e

        result_files: List[str] = []
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
            filtered: List[Dict[str, Any]] = []
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

        metrics: Optional[List[str]] = args.metrics
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

        if args.table_out:
            rows_sorted = sorted(rows, key=lambda r: (r["algorithm_id"], r["test_date"]))
            Path(args.table_out).write_text(json.dumps(rows_sorted, indent=2, sort_keys=True))

        out_path = Path(args.out)
        if out_path.suffix.lower() != ".pdf":
            raise ValueError("--out must be a .pdf path")
        out_path.parent.mkdir(parents=True, exist_ok=True)

        def header_page(pdf: "PdfPages", title: str, lines: Sequence[str]) -> None:
            fig = plt.figure(figsize=(15, 8))
            fig.patch.set_facecolor("white")
            ax = fig.add_subplot(111)
            ax.axis("off")
            ax.text(0.5, 0.72, title, ha="center", va="center", fontsize=30, fontweight="bold")
            # Left-aligned metadata block. Callers should pre-wrap to preserve indentation.
            y = 0.60
            for line in lines:
                ax.text(0.08, y, line, ha="left", va="top", fontsize=13, family="monospace")
                y -= 0.042
            fig.tight_layout()
            pdf.savefig(fig)
            plt.close(fig)

        def point_plot(ax: "Any", data: "pd.DataFrame", metric: str, title: str) -> None:
            sns.pointplot(x="version", y=metric, data=data, ax=ax, errorbar=("ci", 95))
            ax.set_title(title)
            ax.yaxis.grid(True)
            for label in ax.get_xticklabels():
                label.set_rotation(45)
                label.set_horizontalalignment("right")

        def time_series_plot(
            ax: "Any",
            data: "pd.DataFrame",
            metric: str,
            title: str,
            *,
            show_legend: bool,
        ) -> bool:
            if "test_date" not in data.columns:
                return False
            plot_data = data.copy()
            plot_data["test_date"] = pd.to_datetime(plot_data["test_date"], errors="coerce")
            plot_data = plot_data.dropna(subset=["test_date", metric])
            if plot_data.empty:
                return False

            sns.lineplot(
                x="test_date",
                y=metric,
                hue="version",
                style="version",
                markers=True,
                dashes=False,
                data=plot_data.sort_values(["test_date", "version"]),
                ax=ax,
            )
            ax.set_title(title)
            ax.yaxis.grid(True)
            ax.set_xlabel("test_date")
            unique_dates = sorted({d.normalize() for d in plot_data["test_date"].dt.tz_localize(None)})
            ax.set_xticks(unique_dates)
            ax.set_xticklabels([d.strftime("%Y-%m-%d") for d in unique_dates], rotation=45, ha="right")
            legend = ax.get_legend()
            if legend is not None and not show_legend:
                legend.remove()
            return True

        def metric_grid_page(
            pdf: "PdfPages",
            data: "pd.DataFrame",
            metric_group: Sequence[str],
            title: str,
            plot_kind: str,
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
                    point_plot(ax, data, metric, metric)
                elif plot_kind == "time":
                    plotted = time_series_plot(
                        ax,
                        data,
                        metric,
                        metric,
                        show_legend=index == 0,
                    )
                    if not plotted:
                        ax.axis("off")
                        ax.set_title(f"{metric} (no data)")
                else:
                    raise ValueError(f"Unsupported plot_kind: {plot_kind}")

            for ax in axes_list[len(metric_group) :]:
                ax.axis("off")

            fig.tight_layout(rect=(0, 0, 1, 0.97))
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

        header_lines_common = _build_header_lines(
            source_root=source_root,
            source_files=source_files,
            versions=[str(v) for v in df["version"].cat.categories],
            n_dates=n_dates,
            date_min=date_min,
            date_max=date_max,
            metrics=final_metrics,
        )

        with PdfPages(out_path) as pdf:
            header_lines_relative = list(header_lines_common)
            relative_baseline_display = baseline.display_name
            versions_display = ", ".join(str(v) for v in df["version"].cat.categories)
            if len(df["version"].cat.categories) == 2:
                versions_display = f"{df['version'].cat.categories[0]}  vs  {df['version'].cat.categories[1]}"
            try:
                idx = header_lines_relative.index(f"  versions:   {versions_display}")
                header_lines_relative.insert(idx + 1, f"  baseline:   {relative_baseline_display}")
            except ValueError:
                header_lines_relative.append(f"  baseline:   {relative_baseline_display}")

            header_page(pdf, "RELATIVE METRICS", header_lines_relative)
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

            if not relative_point_metrics and not relative_time_metrics:
                raise ValueError(f"No metrics available for relative baseline: {relative_baseline_display}")

            for page_num, metric_group in enumerate(_chunked_metrics(relative_point_metrics), start=1):
                metric_grid_page(
                    pdf,
                    pd.concat([relative_point_frames[m] for m in metric_group], ignore_index=True),
                    metric_group,
                    f"Relative point plots (page {page_num})",
                    "point",
                )

            for page_num, metric_group in enumerate(_chunked_metrics(relative_time_metrics), start=1):
                metric_grid_page(
                    pdf,
                    pd.concat([relative_time_frames[m] for m in metric_group], ignore_index=True),
                    metric_group,
                    f"Relative time series (non-baseline variants; page {page_num})",
                    "time",
                )

            header_page(pdf, "ABSOLUTE METRICS", header_lines_common)
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
