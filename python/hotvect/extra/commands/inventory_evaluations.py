"""List available result runs under a conventional backtest meta directory.

This command is intended as a discovery tool: it enumerates what run directories
exist locally and reports which result artifacts are present and parseable.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from datetime import date, timedelta
from typing import Any

from hotvect.extra.config import resolve_meta_dir

from .base import BaseCommand


def _iter_days(from_date: date, to_date: date) -> list[date]:
    lo = min(from_date, to_date)
    hi = max(from_date, to_date)
    out: list[date] = []
    cur = lo
    while cur <= hi:
        out.append(cur)
        cur += timedelta(days=1)
    return out


def _parse_last_test_date_dirname(dirname: str) -> str | None:
    prefix = "last_test_date_"
    if not dirname.startswith(prefix):
        return None
    day_s = dirname[len(prefix) :]
    try:
        date.fromisoformat(day_s)
    except ValueError:
        return None
    return day_s


@dataclass(frozen=True)
class _RunSummary:
    algorithm_id: str
    algorithm_name: str
    algorithm_version: str
    test_date: str
    run_dir: str
    result_json_path: str
    result_json_exists: bool
    result_json_json_ok: bool | None
    hyperparameter_version: str | None
    parameter_version: str | None
    test_data_time: str | None
    contents: dict[str, Any]
    errors: list[str]


def _extract_identity_from_algorithm_id(
    algorithm_id: str | None,
    *,
    hyperparameter_version: str | None,
) -> dict[str, Any]:
    """Extract {algorithm_id, algorithm_name, algorithm_version} from algorithm_id.

    If hyperparameter_version is known and algorithm_id ends with "-<hyperparameter_version>",
    strip that suffix to recover the base algorithm_version.
    """
    if not algorithm_id or "@" not in algorithm_id:
        return {"algorithm_id": algorithm_id, "algorithm_name": None, "algorithm_version": None}

    algorithm_name, rest = algorithm_id.split("@", 1)

    if hyperparameter_version:
        suffix = f"-{hyperparameter_version}"
        if rest.endswith(suffix):
            rest = rest[: -len(suffix)]

    return {"algorithm_id": algorithm_id, "algorithm_name": algorithm_name, "algorithm_version": rest}


def _content_status(obj: Any) -> tuple[str, str | None]:
    """Return (status, error) for a result.json section.

    Status is one of: missing, present, skipped, invalid.
    """
    if obj is None:
        return "missing", None
    if isinstance(obj, dict) and "skipped" in obj:
        return "skipped", None
    if isinstance(obj, dict):
        return "present", None
    return "invalid", f"Unexpected type: {type(obj).__name__}"


def _extract_metrics_from_nested(obj: Any, prefix: str = "") -> dict[str, float]:
    metrics: dict[str, float] = {}
    if not isinstance(obj, dict):
        return metrics

    for key, value in obj.items():
        full_key = f"{prefix}.{key}" if prefix else str(key)
        if isinstance(value, (int, float)):
            metrics[full_key] = float(value)
        elif isinstance(value, dict):
            mean_value = value.get("mean")
            if isinstance(mean_value, (int, float)):
                metrics[full_key] = float(mean_value)
            else:
                metrics.update(_extract_metrics_from_nested(value, full_key))
    return metrics


class ListAvailableResultsCommand(BaseCommand):
    """List locally available runs and which result contents are present."""

    @classmethod
    def register_parser(cls, subparsers):
        parser = subparsers.add_parser(
            "list-available-results",
            help="List locally available result runs under a backtest meta dir (JSON output only)",
        )
        parser.add_argument("--meta-dir", default="", help="Path to the backtest meta directory (default: from config)")
        parser.add_argument("--from-date", help="Only include runs on/after this date (YYYY-MM-DD, inclusive)")
        parser.add_argument("--to-date", help="Only include runs on/before this date (YYYY-MM-DD, inclusive)")
        parser.add_argument(
            "--algorithm-name",
            default="",
            help="Only include algorithms with this name (directory name split at '@') (optional)",
        )
        parser.add_argument(
            "--algorithm-name-regex",
            default="",
            help="Regex to filter algorithm name (matches dir name split at '@') (optional)",
        )
        parser.add_argument(
            "--algorithm-version-regex",
            default="",
            help="Regex to filter algorithm version (matches dir name split at '@') (optional)",
        )
        return parser

    def execute(self, args):
        meta_dir = resolve_meta_dir(meta_dir=(args.meta_dir or None))
        algorithm_name_filter = (args.algorithm_name or "").strip()
        algorithm_name_regex = (args.algorithm_name_regex or "").strip()
        algorithm_version_regex = (args.algorithm_version_regex or "").strip()
        algorithm_name_re = re.compile(algorithm_name_regex) if algorithm_name_regex else None
        algorithm_version_re = re.compile(algorithm_version_regex) if algorithm_version_regex else None

        from_date = date.fromisoformat(args.from_date) if args.from_date else None
        to_date = date.fromisoformat(args.to_date) if args.to_date else None
        if from_date and to_date and from_date > to_date:
            raise ValueError("--from-date must be <= --to-date")

        # We don't precompute the full day set unless both sides are provided. Otherwise, we do one-sided checks.
        day_set: set[str] | None = None
        if from_date and to_date:
            day_set = {d.isoformat() for d in _iter_days(from_date, to_date)}

        algorithms_seen: set[str] = set()
        runs: list[_RunSummary] = []

        if not meta_dir.exists():
            raise FileNotFoundError(f"--meta-dir does not exist: {meta_dir}")
        if not meta_dir.is_dir():
            raise NotADirectoryError(f"--meta-dir is not a directory: {meta_dir}")

        for algo_dir in sorted([p for p in meta_dir.iterdir() if p.is_dir()]):
            algorithm_id = algo_dir.name
            algo_name = algorithm_id.split("@", 1)[0]
            algo_version = algorithm_id.split("@", 1)[1] if "@" in algorithm_id else ""
            if algorithm_name_filter and algo_name != algorithm_name_filter:
                continue
            if algorithm_name_re and not algorithm_name_re.match(algo_name):
                continue
            if algorithm_version_re and not algorithm_version_re.match(algo_version):
                continue
            algorithms_seen.add(algorithm_id)
            for run_dir in sorted([p for p in algo_dir.iterdir() if p.is_dir()]):
                test_date_s = _parse_last_test_date_dirname(run_dir.name)
                if test_date_s is None:
                    continue
                if day_set is not None:
                    if test_date_s not in day_set:
                        continue
                else:
                    if from_date and test_date_s < from_date.isoformat():
                        continue
                    if to_date and test_date_s > to_date.isoformat():
                        continue

                result_path = run_dir / "result.json"
                contents: dict[str, Any] = {
                    "quality": {"status": "missing"},
                    "system_performance": {"status": "missing"},
                }
                errors: list[str] = []
                result_json_exists = result_path.exists()
                result_json_json_ok: bool | None = None
                hyper_v: str | None = None
                parameter_v: str | None = None
                test_data_time: str | None = None

                if not result_json_exists:
                    runs.append(
                        _RunSummary(
                            algorithm_id=algorithm_id,
                            algorithm_name=algo_name,
                            algorithm_version=algo_version,
                            test_date=test_date_s,
                            run_dir=str(run_dir),
                            result_json_path=str(result_path),
                            result_json_exists=False,
                            result_json_json_ok=None,
                            hyperparameter_version=None,
                            parameter_version=None,
                            test_data_time=None,
                            contents=contents,
                            errors=[],
                        )
                    )
                    continue

                try:
                    raw = json.loads(result_path.read_text())
                    result_json_json_ok = True
                except Exception as e:
                    result_json_json_ok = False
                    contents["quality"] = {"status": "invalid", "error": "Cannot parse result.json"}
                    contents["system_performance"] = {"status": "invalid", "error": "Cannot parse result.json"}
                    errors.append(f"Cannot parse result.json: {e}")
                    runs.append(
                        _RunSummary(
                            algorithm_id=algorithm_id,
                            algorithm_name=algo_name,
                            algorithm_version=algo_version,
                            test_date=test_date_s,
                            run_dir=str(run_dir),
                            result_json_path=str(result_path),
                            result_json_exists=True,
                            result_json_json_ok=False,
                            hyperparameter_version=None,
                            parameter_version=None,
                            test_data_time=None,
                            contents=contents,
                            errors=errors,
                        )
                    )
                    continue

                if not isinstance(raw, dict):
                    errors.append("result.json root is not an object")
                    contents["quality"] = {"status": "invalid", "error": "result.json root is not an object"}
                    contents["system_performance"] = {"status": "invalid", "error": "result.json root is not an object"}
                    runs.append(
                        _RunSummary(
                            algorithm_id=algorithm_id,
                            algorithm_name=algo_name,
                            algorithm_version=algo_version,
                            test_date=test_date_s,
                            run_dir=str(run_dir),
                            result_json_path=str(result_path),
                            result_json_exists=True,
                            result_json_json_ok=True,
                            hyperparameter_version=None,
                            parameter_version=None,
                            test_data_time=None,
                            contents=contents,
                            errors=errors,
                        )
                    )
                    continue

                algo_def = raw.get("algorithm_definition")
                if isinstance(algo_def, dict):
                    hv = algo_def.get("hyperparameter_version")
                    if isinstance(hv, str) and hv:
                        hyper_v = hv

                json_algo_id = raw.get("algorithm_id")
                json_test_data_time = raw.get("test_data_time")
                json_parameter_version = raw.get("parameter_version")

                parameter_v = json_parameter_version if isinstance(json_parameter_version, str) else None
                test_data_time = json_test_data_time if isinstance(json_test_data_time, str) else None

                if isinstance(json_algo_id, str):
                    if json_algo_id != algorithm_id:
                        errors.append(f"algorithm_id mismatch: path='{algorithm_id}' json='{json_algo_id}'")
                else:
                    errors.append("Missing or invalid algorithm_id in result.json")

                if isinstance(json_test_data_time, str):
                    if json_test_data_time != test_date_s:
                        errors.append(f"test_data_time mismatch: path='{test_date_s}' json='{json_test_data_time}'")
                else:
                    errors.append("Missing or invalid test_data_time in result.json")

                evaluate_section = raw.get("evaluate")
                perf_section = raw.get("performance_test")

                # Basic shape validation: if evaluate exists but isn't dict, treat as invalid.
                q_status, q_err = _content_status(evaluate_section)
                p_status, p_err = _content_status(perf_section)
                contents["quality"] = {"status": q_status, "error": q_err}
                contents["system_performance"] = {"status": p_status, "error": p_err}

                # If evaluate section is present but not skipped, we can also validate that we can extract metrics.
                if q_status == "present":
                    try:
                        _ = _extract_metrics_from_nested(evaluate_section)
                    except Exception as e:
                        contents["quality"] = {"status": "invalid", "error": str(e)}

                runs.append(
                    _RunSummary(
                        algorithm_id=algorithm_id,
                        algorithm_name=algo_name,
                        algorithm_version=algo_version,
                        test_date=test_date_s,
                        run_dir=str(run_dir),
                        result_json_path=str(result_path),
                        result_json_exists=True,
                        result_json_json_ok=result_json_json_ok,
                        hyperparameter_version=hyper_v,
                        parameter_version=parameter_v,
                        test_data_time=test_data_time,
                        contents=contents,
                        errors=errors,
                    )
                )

        algorithms = sorted(algorithms_seen)
        dates = sorted({r.test_date for r in runs})
        result = {
            "meta_dir": str(meta_dir),
            "filters": {
                "from_date": from_date.isoformat() if from_date else None,
                "to_date": to_date.isoformat() if to_date else None,
                "algorithm_name": algorithm_name_filter or None,
            },
            "algorithms": algorithms,
            "dates": dates,
            "runs": [r.__dict__ for r in runs],
        }

        print(json.dumps(result, indent=2))
