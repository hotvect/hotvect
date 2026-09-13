"""Existing-result evaluation command for hv-qa CLI."""

from __future__ import annotations

import argparse
import json
import re
import tempfile
from datetime import date, timedelta
from pathlib import Path
from typing import Any

from hotvect.evaluation_criteria import load_builtin_policy

from .base import BaseCommand
from .run import (
    _BuiltRefArtifact,
    _compare_backtest_outputs,
    _materialize_existing_backtest_results,
    _PerformanceTestSpec,
    _resolve_offline_context,
)


class EvaluateCommand(BaseCommand):
    """Evaluate existing proof-directory result files."""

    @classmethod
    def register_parser(cls, subparsers, *, command_prefix: str = "hv-qa"):
        parser = subparsers.add_parser(
            "evaluate",
            help="Evaluate an existing proof directory",
            description="Evaluate existing Hotvect result.json files without starting a QA run.",
            formatter_class=argparse.RawDescriptionHelpFormatter,
            epilog=f"""
Examples:
  {command_prefix} evaluate \\
      --criteria noninferiority \\
      --last-test-date 2000-01-08 \\
      --days 2 \\
      --proof-dir /path/to/proof
            """,
        )
        parser.add_argument("--criteria", required=True, help="Criteria id: exact, noninferiority, or superiority")
        parser.add_argument("--last-test-date", required=True, help="Offline anchor date in YYYY-MM-DD format")
        parser.add_argument("--days", required=True, help="Number of offline comparison days; must be at least 2")
        parser.add_argument("--proof-dir", required=True, help="Directory with <dt>/control and <dt>/treatment results")
        parser.add_argument("--output", default="", help="Optional path to write the evaluation JSON")
        parser.add_argument("--pretty", action="store_true", help="Pretty-print JSON output")
        return parser

    def execute(self, args):
        result = evaluate_proof_directory(
            criteria_id=args.criteria,
            last_test_date=args.last_test_date,
            days=args.days,
            proof_dir=args.proof_dir,
        )
        indent = 2 if args.pretty else None
        rendered = json.dumps(result, indent=indent, sort_keys=bool(indent))
        output_path = (args.output or "").strip()
        if output_path:
            path = Path(output_path).expanduser()
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(rendered + "\n", encoding="utf-8")
        print(rendered)
        return bool(result["ok"])


def evaluate_proof_directory(
    *,
    criteria_id: str,
    last_test_date: str,
    days: Any,
    proof_dir: str,
) -> dict[str, Any]:
    offline_context = _resolve_offline_context(last_test_date=last_test_date, backtest_days=days)
    _require_multi_day_evaluation(offline_context)
    control_results = _proof_result_paths(proof_dir=proof_dir, offline_context=offline_context, role="control")
    treatment_results = _proof_result_paths(proof_dir=proof_dir, offline_context=offline_context, role="treatment")
    return evaluate_existing_results(
        criteria_id=criteria_id,
        last_test_date=last_test_date,
        days=days,
        control_results=control_results,
        treatment_results=treatment_results,
    )


def evaluate_existing_results(
    *,
    criteria_id: str,
    last_test_date: str,
    days: Any,
    control_results: list[str],
    treatment_results: list[str],
) -> dict[str, Any]:
    offline_context = _resolve_offline_context(last_test_date=last_test_date, backtest_days=days)
    _require_multi_day_evaluation(offline_context)
    policy = load_builtin_policy(criteria_id)
    if policy.manifest.scenario != "qa":
        raise ValueError(
            f"Criteria '{policy.manifest.identifier}' is for scenario '{policy.manifest.scenario}', not 'qa'."
        )

    with tempfile.TemporaryDirectory(prefix="hv-qa-evaluate-") as temp_dir_name:
        temp_dir = Path(temp_dir_name)
        control_output_base_dir = temp_dir / "control"
        treatment_output_base_dir = temp_dir / "treatment"
        control_artifacts = _materialize_existing_backtest_results(
            result_sources=control_results,
            output_base_dir=control_output_base_dir,
            offline_context=offline_context,
            role="control",
        )
        treatment_artifacts = _materialize_existing_backtest_results(
            result_sources=treatment_results,
            output_base_dir=treatment_output_base_dir,
            offline_context=offline_context,
            role="treatment",
        )
        control_artifact = _artifact_from_materialized_results(control_artifacts, role="control")
        treatment_artifact = _artifact_from_materialized_results(treatment_artifacts, role="treatment")
        comparison = _compare_backtest_outputs(
            control_output_base_dir=control_output_base_dir,
            treatment_output_base_dir=treatment_output_base_dir,
            control_artifact=control_artifact,
            treatment_artifact=treatment_artifact,
            offline_context=offline_context,
        )
        performance_spec_compatible = _existing_performance_specs_compatible(
            control_artifacts=control_artifacts,
            treatment_artifacts=treatment_artifacts,
        )

    stage = "multi_day_backtest"
    payload = _results_evaluation_payload(
        stage=stage,
        offline_context=offline_context,
        control_artifact=control_artifact,
        treatment_artifact=treatment_artifact,
        comparison=comparison,
        performance_spec_compatible=performance_spec_compatible,
    )
    judgment = policy.evaluate(payload)
    return {
        "ok": judgment["stage_judgment"] == "pass",
        "criteria": policy.manifest.identifier,
        "judgment": judgment["stage_judgment"],
        "rollout_recommendation": judgment["rollout_recommendation"],
        "stage": stage,
        "summary": judgment.get("summary"),
        "reasons": judgment.get("reasons", []),
        "dates_used": comparison["dates_used"],
        "control_id": comparison["control_id"],
        "treatment_id": comparison["treatment_id"],
        "control_algorithm_ids": comparison["control_algorithm_ids"],
        "treatment_algorithm_ids": comparison["treatment_algorithm_ids"],
        "quality_metrics": comparison["quality_metrics"],
        "quality_statistical_basis": comparison["quality_statistical_basis"],
        "system_metrics": comparison["system_metrics"],
        "criteria_judgment": judgment,
    }


def _require_multi_day_evaluation(offline_context: dict[str, Any]) -> None:
    if int(offline_context["backtest_days"]) < 2:
        raise ValueError(
            "QA evaluation requires at least two paired dates. "
            "The realistic_single_day stage is an execution smoke check, not a quality judgment."
        )


def _proof_result_paths(*, proof_dir: str, offline_context: dict[str, Any], role: str) -> list[str]:
    root = Path(proof_dir).expanduser()
    if not root.is_dir():
        raise FileNotFoundError(f"Proof directory not found: {root}")
    ret: list[str] = []
    for day in _offline_context_dates(offline_context):
        path = root / day / role / "result.json"
        if not path.exists():
            raise FileNotFoundError(f"Missing {role} result for {day}: {path}")
        ret.append(str(path))
    return ret


def _offline_context_dates(offline_context: dict[str, Any]) -> list[str]:
    window = dict(offline_context.get("backtest_date_window") or {})
    start_date = str(window["start_date"])
    end_date = str(window["end_date"])

    cursor = date.fromisoformat(start_date)
    last = date.fromisoformat(end_date)
    ret: list[str] = []
    while cursor <= last:
        ret.append(cursor.isoformat())
        cursor += timedelta(days=1)
    return ret


def _artifact_from_materialized_results(materialized: dict[str, Any], *, role: str) -> _BuiltRefArtifact:
    result_ids = [
        str(item.get("algorithm_id") or "").strip()
        for item in materialized.get("results", [])
        if str(item.get("algorithm_id") or "").strip()
    ]
    if not result_ids:
        raise ValueError(f"No {role} algorithm ids found in saved results.")
    algorithm_name, artifact_version = _infer_algorithm_name_and_version(result_ids, role=role)
    return _BuiltRefArtifact(
        git_ref=role,
        resolved_git_ref=role,
        artifact_name=algorithm_name,
        artifact_version=artifact_version,
        git_commit="",
        jar_path=Path(""),
        build_dir=Path(""),
    )


def _infer_algorithm_name_and_version(algorithm_ids: list[str], *, role: str) -> tuple[str, str]:
    parsed = [_split_algorithm_id(algorithm_id, role=role) for algorithm_id in algorithm_ids]
    names = {name for name, _ in parsed}
    if len(names) != 1:
        raise ValueError(f"Saved {role} results contain multiple algorithm names: {sorted(names)}")
    versions = [version for _, version in parsed]
    version_set = set(versions)
    if len(version_set) == 1:
        return next(iter(names)), versions[0]
    semver_prefixes = {_semver_prefix(version) for version in versions}
    semver_prefixes.discard(None)
    if len(semver_prefixes) == 1:
        return next(iter(names)), next(iter(semver_prefixes))
    raise ValueError(f"Saved {role} results contain multiple algorithm versions: {sorted(version_set)}")


def _split_algorithm_id(algorithm_id: str, *, role: str) -> tuple[str, str]:
    if "@" not in algorithm_id:
        raise ValueError(f"Saved {role} result algorithm_id '{algorithm_id}' is missing '@'.")
    name, version = algorithm_id.split("@", 1)
    if not name or not version:
        raise ValueError(f"Saved {role} result algorithm_id '{algorithm_id}' must be '<name>@<version>'.")
    return name, version


def _semver_prefix(version: str) -> str | None:
    match = re.match(r"^(\d+\.\d+\.\d+)(?:[-_].*)?$", version)
    return match.group(1) if match else None


def _results_evaluation_payload(
    *,
    stage: str,
    offline_context: dict[str, Any],
    control_artifact: _BuiltRefArtifact,
    treatment_artifact: _BuiltRefArtifact,
    comparison: dict[str, Any],
    performance_spec_compatible: bool | None,
) -> dict[str, Any]:
    status_flags = {
        "jobs_complete": True,
        "packaging_ok": True,
        "data_shape_ok": True,
        "runtime_ok": True,
        "has_semantic_shortcuts": False,
    }
    treatment_status = (
        {
            "jobs_complete": status_flags["jobs_complete"],
            "packaging_ok": status_flags["packaging_ok"],
            "data_shape_ok": status_flags["data_shape_ok"],
            "runtime_ok": status_flags["runtime_ok"],
        }
        if stage == "realistic_single_day"
        else {
            "jobs_complete": status_flags["jobs_complete"],
            "has_semantic_shortcuts": status_flags["has_semantic_shortcuts"],
            "performance_spec_compatible": performance_spec_compatible,
        }
    )
    return {
        "scenario": "qa",
        "stage": stage,
        "context": {
            "online_policy_known": False,
            "target_dates": comparison["dates_used"],
            "backtest_output_mode": "existing_results",
            "offline_context": offline_context,
            "quality_statistical_basis": comparison["quality_statistical_basis"],
        },
        "control": {
            "git_ref": "control",
            "algorithm_name": control_artifact.artifact_name,
            "algorithm_version": control_artifact.artifact_version,
        },
        "treatments": [
            {
                "git_ref": comparison["treatment_id"],
                "algorithm_name": treatment_artifact.artifact_name,
                "algorithm_version": treatment_artifact.artifact_version,
                "status": treatment_status,
                "metrics": {
                    "offline_quality": comparison["offline_quality"],
                    "performance": comparison["performance"],
                    "quality": comparison["quality_metrics"],
                    "system": comparison["system_metrics"],
                },
            }
        ],
    }


def _existing_performance_specs_compatible(
    *, control_artifacts: dict[str, Any], treatment_artifacts: dict[str, Any]
) -> bool | None:
    control_specs = _existing_performance_specs(control_artifacts, role="control")
    treatment_specs = _existing_performance_specs(treatment_artifacts, role="treatment")
    if control_specs is None or treatment_specs is None:
        return None
    return all(spec == control_specs[0] for spec in [*control_specs, *treatment_specs])


def _existing_performance_specs(materialized: dict[str, Any], *, role: str) -> list[_PerformanceTestSpec] | None:
    specs: list[_PerformanceTestSpec] = []
    for item in materialized.get("results", []):
        local_path = item.get("local_path")
        if not isinstance(local_path, str) or not local_path:
            return None
        payload = json.loads(Path(local_path).read_text(encoding="utf-8"))
        algorithm_definition = payload.get("algorithm_definition")
        if not isinstance(algorithm_definition, dict):
            return None
        try:
            specs.append(_PerformanceTestSpec.from_algorithm_definition(algorithm_definition, git_ref=role))
        except ValueError:
            return None
    return specs or None
