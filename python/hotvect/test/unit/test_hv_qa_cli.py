from __future__ import annotations

import json
import os
import subprocess
import sys
from datetime import date
from pathlib import Path

import pytest

from hotvect.qa import cli as qa_cli
from hotvect.qa.commands.run import _BuiltRefArtifact, _compare_backtest_outputs, _compare_complete_qa_metrics
from hotvect.qa.planning import prod_default_facts_from_slot_active_info


def _python_dir() -> Path:
    return Path(__file__).resolve().parents[3]


def _hv_qa_bin_path() -> Path:
    return _python_dir() / "bin" / "hv-qa"


def _run_hv_qa(
    *args: str, input_text: str | None = None, env_overrides: dict[str, str] | None = None
) -> subprocess.CompletedProcess[str]:
    python_dir = _python_dir()
    env = dict(
        os.environ,
        PYTHONPATH=str(python_dir),
        HOME=str(Path(os.environ.get("TMPDIR", "/tmp")) / "hotvect-empty-home-for-tests"),
    )
    if env_overrides:
        env.update(env_overrides)
    return subprocess.run(
        [sys.executable, str(_hv_qa_bin_path()), *args],
        cwd=python_dir,
        capture_output=True,
        text=True,
        env=env,
        input=input_text,
    )


def _performance_proof_algorithm_definition() -> dict:
    return {
        "hotvect_execution_parameters": {
            "performance-test": {
                "enabled": True,
                "samples": 100,
                "sample_pool_size": 100,
                "target_rps": 50.0,
                "workload_mode": "realtime",
            }
        }
    }


def _performance_proof_result(*, latency_ms: float, throughput: float, memory: float) -> dict:
    return {
        "max_memory_usage": memory,
        "mean_throughput": throughput,
        "response_time_metrics": {
            "mean_throughput": {"mean": throughput},
            **{metric: {"mean": latency_ms} for metric in ("mean", "p50", "p75", "p95", "p99", "p999")},
        },
    }


def test_hv_qa_help_mentions_run_commands_and_criteria() -> None:
    result = _run_hv_qa("--help")

    assert result.returncode == 0
    assert "start" in result.stdout
    assert "resume" in result.stdout
    assert "status" in result.stdout
    assert "criteria" in result.stdout
    assert "prod-default-of-slot-as-control" in result.stdout
    assert "QA orchestration for Hotvect algorithm releases" in result.stdout


def test_hv_qa_evaluate_returns_nonzero_for_rejected_evidence(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(qa_cli.EvaluateCommand, "execute", lambda self, args: False)

    return_code = qa_cli.main(
        [
            "evaluate",
            "--criteria",
            "noninferiority",
            "--last-test-date",
            "2000-05-25",
            "--days",
            "2",
            "--proof-dir",
            "/tmp/proof",
        ]
    )

    assert return_code == 1


def test_hv_qa_start_help_mentions_prod_default_control_flag() -> None:
    result = _run_hv_qa("start", "--help")

    assert result.returncode == 0
    assert "--prod-default-of-slot-as-control" in result.stdout
    assert "--control" in result.stdout
    assert "--control-repo" in result.stdout
    assert "--treatment-repo" in result.stdout
    assert "--control-parameter-source" in result.stdout
    assert "--treatment-parameter-source" in result.stdout
    assert "--control-performance-source-path" in result.stdout
    assert "--treatment-performance-source-path" in result.stdout


def test_hv_qa_start_rejects_control_and_prod_default_slot_together() -> None:
    result = _run_hv_qa(
        "start",
        "--control",
        "v86.3.4",
        "--prod-default-of-slot-as-control",
        "example-slot",
        "--treatment",
        "feature/example-cleanup-v86",
        "--last-test-date",
        "2000-05-25",
        "--days",
        "7",
    )

    assert result.returncode == 2
    assert "not allowed with argument --control" in result.stderr


def test_validation_plan_can_identify_prod_default_from_hv_exp_slot_payload() -> None:
    facts = prod_default_facts_from_slot_active_info(
        slot_name="example-slot",
        active_info={
            "default_variant": {
                "variant_id": 2115,
                "created_at": "2000-05-18T08:16:16Z",
                "algorithm": {
                    "algorithm_name": "example-ranking-algorithm",
                    "algorithm_version": "18.5.11",
                },
            }
        },
    )

    assert facts == {
        "algorithm": "example-ranking-algorithm",
        "version": "18.5.11",
        "variant": "2115",
        "created_at": "2000-05-18T08:16:16Z",
        "slot_name": "example-slot",
    }


def test_hv_qa_criteria_list_and_evaluate() -> None:
    listed = _run_hv_qa("criteria", "list")
    assert listed.returncode == 0
    payload = json.loads(listed.stdout)
    assert payload["criteria"] == ["exact", "noninferiority", "superiority"]

    evaluated = _run_hv_qa(
        "criteria",
        "evaluate",
        "noninferiority",
        "--pretty",
        input_text=json.dumps(
            {
                "scenario": "qa",
                "stage": "realistic_single_day",
                "treatments": [
                    {
                        "git_ref": "demo",
                        "status": {
                            "jobs_complete": True,
                            "packaging_ok": True,
                            "data_shape_ok": True,
                            "runtime_ok": True,
                        },
                    }
                ],
            }
        ),
    )
    assert evaluated.returncode == 0
    judgment = json.loads(evaluated.stdout)
    assert judgment["criteria"]["id"] == "noninferiority"
    assert judgment["stage_judgment"] == "pass"


def test_hv_qa_start_creates_durable_state(tmp_path: Path) -> None:
    meta_dir = tmp_path / "meta"
    result = _run_hv_qa(
        "start",
        "--control",
        "v86.1.4",
        "--treatment",
        "v86.1.13",
        "--last-test-date",
        "2000-04-14",
        "--days",
        "7",
        "--work-dir",
        str(meta_dir),
    )

    assert result.returncode == 2
    payload = json.loads(result.stdout)
    assert payload["ok"] is False
    assert payload["action"] == "created"
    assert payload["target_stage"] == "multi_day_backtest"
    assert payload["criteria"]["id"] == "noninferiority"
    assert payload["state"] == "failed"
    assert payload["stage_status"]["realistic_single_day"] == "failed"
    assert "Stage 'realistic_single_day' is missing required execution context" in result.stderr

    run_dir = Path(payload["run_dir"])
    assert run_dir.exists()
    assert (run_dir / "plan.json").exists()
    assert (run_dir / "status.json").exists()
    assert (run_dir / "decisions.jsonl").exists()
    assert (run_dir / "evidence.json").exists()
    assert not (run_dir / "report.md").exists()
    assert (run_dir / "criteria").is_dir()
    assert (run_dir / "tracks" / "quality").is_dir()

    scenario = json.loads((run_dir / "plan.json").read_text(encoding="utf-8"))
    status = json.loads((run_dir / "status.json").read_text(encoding="utf-8"))
    assert scenario["refs"]["control"]["git_ref"] == "v86.1.4"
    assert "version_hint" not in scenario["refs"]["control"]
    assert [item["git_ref"] for item in scenario["refs"]["treatments"]] == ["v86.1.13"]
    assert "version_hint" not in scenario["refs"]["treatments"][0]
    assert scenario["criteria"]["id"] == "noninferiority"
    assert scenario["offline_context"] == {
        "last_test_date": "2000-04-14",
        "backtest_days": 7,
        "backtest_date_window": {
            "start_date": "2000-04-08",
            "end_date": "2000-04-14",
        },
    }
    assert scenario["parameter_source"] is None
    assert payload["offline_context"] == scenario["offline_context"]
    assert payload["stage_sequence"] == [
        "realistic_single_day",
        "multi_day_backtest",
    ]
    assert status["requested_stages"] == [
        "realistic_single_day",
        "multi_day_backtest",
    ]


def test_hv_qa_does_not_expose_backtest_stage_options(tmp_path: Path) -> None:
    result = _run_hv_qa(
        "start",
        "--control",
        "v86.1.4",
        "--treatment",
        "v86.1.13",
        "--last-test-date",
        "2000-04-14",
        "--days",
        "7",
        "--algorithm-override",
        str(tmp_path / "override.json"),
    )

    assert result.returncode == 2
    assert "unrecognized arguments: --algorithm-override" in result.stderr
    assert result.stdout == ""


def test_hv_qa_does_not_expose_backtest_quality_metric_prefix(tmp_path: Path) -> None:
    result = _run_hv_qa(
        "start",
        "--control",
        "v86.1.4",
        "--treatment",
        "v86.1.13",
        "--last-test-date",
        "2000-04-14",
        "--days",
        "7",
        "--backtest-quality-metric-prefix",
        "online:algorithm",
        "--work-dir",
        str(tmp_path / "meta"),
    )

    assert result.returncode == 2
    assert "unrecognized arguments: --backtest-quality-metric-prefix" in result.stderr
    assert result.stdout == ""


def test_hv_qa_start_only_allows_fresh_single_stage_execution(tmp_path: Path) -> None:
    meta_dir = tmp_path / "meta"
    result = _run_hv_qa(
        "start",
        "--control",
        "v86.1.4",
        "--treatment",
        "v86.1.13",
        "--last-test-date",
        "2000-04-14",
        "--days",
        "7",
        "--stage",
        "realistic_single_day",
        "--work-dir",
        str(meta_dir),
    )

    assert result.returncode == 2
    payload = json.loads(result.stdout)
    assert payload["ok"] is False
    assert payload["action"] == "created"
    assert payload["target_stage"] == "realistic_single_day"
    assert payload["requested_stages"] == ["realistic_single_day"]
    assert payload["stage_status"]["realistic_single_day"] == "failed"
    assert payload["stage_status"]["multi_day_backtest"] == "not_requested"
    assert "requires an existing run context" not in result.stderr
    assert "Stage 'realistic_single_day' is missing required execution context" in result.stderr


def test_hv_qa_status_and_resume(tmp_path: Path) -> None:
    meta_dir = tmp_path / "meta"
    created = _run_hv_qa(
        "start",
        "--control",
        "v86.1.4",
        "--treatment",
        "feature/runtime-swap",
        "--last-test-date",
        "2000-04-14",
        "--days",
        "7",
        "--criteria",
        "noninferiority",
        "--work-dir",
        str(meta_dir),
    )
    assert created.returncode == 2
    created_payload = json.loads(created.stdout)
    assert created_payload["ok"] is False
    run_id = created_payload["run_id"]

    status = _run_hv_qa("status", run_id, "--work-dir", str(meta_dir))
    assert status.returncode == 0
    status_payload = json.loads(status.stdout)
    assert status_payload["ok"] is False
    assert status_payload["status"]["state"] == "failed"
    assert status_payload["status"]["target_stage"] == "multi_day_backtest"
    assert status_payload["criteria"]["id"] == "noninferiority"
    assert status_payload["offline_context"]["last_test_date"] == "2000-04-14"
    assert "report" not in status_payload["files"]

    resumed = _run_hv_qa(
        "resume",
        run_id,
        "--until",
        "realistic_single_day",
        "--work-dir",
        str(meta_dir),
    )
    assert resumed.returncode == 2
    resumed_payload = json.loads(resumed.stdout)
    assert resumed_payload["ok"] is False
    assert resumed_payload["action"] == "resumed"
    assert resumed_payload["target_stage"] == "realistic_single_day"
    assert resumed_payload["requested_stages"] == ["realistic_single_day"]


def test_hv_qa_refuses_same_algorithm_version_by_default(tmp_path: Path) -> None:
    result = _run_hv_qa(
        "start",
        "--control",
        "v86.1.4",
        "--treatment",
        "86.1.4",
        "--last-test-date",
        "2000-04-14",
        "--days",
        "7",
        "--work-dir",
        str(tmp_path / "meta"),
    )

    assert result.returncode != 0
    assert "require a new algorithm version" in result.stderr


def test_hv_qa_allows_branch_name_that_only_happens_to_contain_control_version(tmp_path: Path) -> None:
    result = _run_hv_qa(
        "start",
        "--control",
        "v86.3.4",
        "--treatment",
        "feature/example-cache-v86.3.4",
        "--last-test-date",
        "2000-04-14",
        "--days",
        "7",
        "--work-dir",
        str(tmp_path / "meta"),
    )

    assert result.returncode == 2
    assert "require a new algorithm version" not in result.stderr
    payload = json.loads(result.stdout)
    assert payload["ok"] is False
    assert payload["action"] == "created"
    assert payload["stage_status"]["realistic_single_day"] == "failed"


def test_hv_qa_compares_same_built_version_from_isolated_backtest_outputs(tmp_path: Path) -> None:
    control_output = tmp_path / "control-output"
    treatment_output = tmp_path / "treatment-output"
    control_result = (
        control_output / "meta" / "example-ranking-algorithm@18.6.0" / "last_test_date_2000-04-02" / "result.json"
    )
    treatment_result = (
        treatment_output / "meta" / "example-ranking-algorithm@18.6.0" / "last_test_date_2000-04-02" / "result.json"
    )
    control_child_result = (
        control_output
        / "meta"
        / "example-ranking-model@18.6.0"
        / "last_test_date_2000-04-02"
        / "result.json"
    )
    treatment_child_result = (
        treatment_output
        / "meta"
        / "example-treatment-model@18.6.0"
        / "last_test_date_2000-04-02"
        / "result.json"
    )
    control_result.parent.mkdir(parents=True)
    treatment_result.parent.mkdir(parents=True)
    control_child_result.parent.mkdir(parents=True)
    treatment_child_result.parent.mkdir(parents=True)
    base_payload = {
        "algorithm_id": "example-ranking-algorithm@18.6.0",
        "test_data_time": "2000-04-02",
        "evaluate": {
            "ndcg_at_50": 0.50,
            "map_at_50": 0.40,
            "roc_auc": {"mean": 0.70},
            "online": {
                "algorithm": {
                    "ndcg_at_50": 0.60,
                    "map_at_50": 0.50,
                    "roc_auc": {"mean": 0.80},
                }
            },
        },
        "performance_test": {
            "max_memory_usage": 1.0,
            "mean_throughput": 100.0,
            "response_time_metrics": {"p99": {"mean": 10.0}},
        },
    }
    control_result.write_text(json.dumps(base_payload), encoding="utf-8")
    treatment_payload = json.loads(json.dumps(base_payload))
    treatment_payload["evaluate"]["ndcg_at_50"] = 0.51
    treatment_payload["evaluate"]["online"]["algorithm"]["ndcg_at_50"] = 0.62
    treatment_payload["performance_test"]["response_time_metrics"]["p99"]["mean"] = 11.0
    treatment_result.write_text(json.dumps(treatment_payload), encoding="utf-8")
    control_child_payload = json.loads(json.dumps(base_payload))
    control_child_payload["algorithm_id"] = "example-ranking-model@18.6.0"
    control_child_result.write_text(json.dumps(control_child_payload), encoding="utf-8")
    treatment_child_payload = json.loads(json.dumps(base_payload))
    treatment_child_payload["algorithm_id"] = "example-treatment-model@18.6.0"
    treatment_child_result.write_text(json.dumps(treatment_child_payload), encoding="utf-8")

    artifact = _BuiltRefArtifact(
        git_ref="control",
        resolved_git_ref="control",
        artifact_name="example-ranking-algorithm",
        artifact_version="18.6.0",
        git_commit="0" * 40,
        jar_path=tmp_path / "missing.jar",
        build_dir=tmp_path,
    )

    comparison = _compare_backtest_outputs(
        control_output_base_dir=control_output,
        treatment_output_base_dir=treatment_output,
        control_artifact=artifact,
        treatment_artifact=artifact,
        offline_context={
            "backtest_date_window": {
                "start_date": "2000-04-02",
                "end_date": "2000-04-02",
            }
        },
    )

    assert comparison["control_id"] == "example-ranking-algorithm@18.6.0"
    assert comparison["treatment_id"] == "example-ranking-algorithm@18.6.0"
    assert comparison["dates_used"] == ["2000-04-02"]
    assert comparison["quality_metrics"]["ndcg_at_50"]["absolute_change"] == 0.010000000000000009
    assert comparison["quality_metrics"]["ndcg_at_50"]["date_count"] == 1
    assert comparison["quality_metrics"]["ndcg_at_50"]["paired_percent_changes"] == [2.0000000000000018]
    assert comparison["system_metrics"]["p99"]["percent_change"] == 10.0
    assert "algorithm.ndcg_at_50" not in comparison["quality_metrics"]
    assert "quality_metric_prefix" not in comparison


def test_hv_qa_compares_date_aligned_control_aliases_as_one_logical_algorithm(tmp_path: Path) -> None:
    control_output = tmp_path / "control-output"
    treatment_output = tmp_path / "treatment-output"
    control_ids = [
        "example-ranking-algorithm@9.3.4-example-control-date-aligned-2000-05-21",
        "example-ranking-algorithm@9.3.4-example-control-date-aligned-2000-05-22",
    ]
    treatment_id = "example-treatment-algorithm@1.0.0"

    for day, control_id, control_ndcg, treatment_ndcg in [
        ("2000-05-21", control_ids[0], 0.50, 0.51),
        ("2000-05-22", control_ids[1], 0.60, 0.63),
    ]:
        control_result = control_output / "meta" / control_id / f"last_test_date_{day}" / "result.json"
        treatment_result = treatment_output / "meta" / treatment_id / f"last_test_date_{day}" / "result.json"
        control_result.parent.mkdir(parents=True)
        treatment_result.parent.mkdir(parents=True)
        control_result.write_text(
            json.dumps(
                {
                    "algorithm_id": control_id,
                    "test_data_time": day,
                    "evaluate": {
                        "ndcg_at_50": control_ndcg,
                        "map_at_50": 0.40,
                    },
                }
            ),
            encoding="utf-8",
        )
        treatment_result.write_text(
            json.dumps(
                {
                    "algorithm_id": treatment_id,
                    "test_data_time": day,
                    "evaluate": {
                        "ndcg_at_50": treatment_ndcg,
                        "map_at_50": 0.42,
                    },
                }
            ),
            encoding="utf-8",
        )

    control_artifact = _BuiltRefArtifact(
        git_ref="9.3.4",
        resolved_git_ref="9.3.4",
        artifact_name="example-ranking-algorithm",
        artifact_version="9.3.4",
        git_commit="0" * 40,
        jar_path=tmp_path / "control.jar",
        build_dir=tmp_path,
    )
    treatment_artifact = _BuiltRefArtifact(
        git_ref="treatment",
        resolved_git_ref="treatment",
        artifact_name="example-treatment-algorithm",
        artifact_version="1.0.0",
        git_commit="1" * 40,
        jar_path=tmp_path / "treatment.jar",
        build_dir=tmp_path,
    )

    comparison = _compare_backtest_outputs(
        control_output_base_dir=control_output,
        treatment_output_base_dir=treatment_output,
        algorithm_name="example-ranking-algorithm",
        control_artifact=control_artifact,
        treatment_artifact=treatment_artifact,
        offline_context={
            "backtest_date_window": {
                "start_date": "2000-05-21",
                "end_date": "2000-05-22",
            }
        },
    )

    assert comparison["control_id"] == "example-ranking-algorithm@9.3.4"
    assert comparison["control_algorithm_ids"] == control_ids
    assert comparison["treatment_id"] == treatment_id
    assert comparison["treatment_algorithm_ids"] == [treatment_id]
    assert comparison["dates_used"] == ["2000-05-21", "2000-05-22"]
    assert comparison["quality_metrics"]["ndcg_at_50"]["date_count"] == 2


def test_hv_qa_rejects_partial_metric_coverage() -> None:
    first_day = date(2000, 5, 21)
    second_day = date(2000, 5, 22)
    control = {
        first_day: {"ndcg_at_50": 0.5, "roc_auc": 0.7},
        second_day: {"ndcg_at_50": 0.6, "roc_auc": 0.8},
    }
    treatment = {
        first_day: {"ndcg_at_50": 0.51, "roc_auc": 0.0},
        second_day: {"ndcg_at_50": 0.61},
    }

    with pytest.raises(ValueError, match=r"missing treatment metrics=\['roc_auc'\]"):
        _compare_complete_qa_metrics(
            control_by_date=control,
            treatment_by_date=treatment,
            expected_dates=[first_day, second_day],
            evidence_name="quality",
        )


def test_hv_qa_rejects_missing_requested_date() -> None:
    first_day = date(2000, 5, 21)
    second_day = date(2000, 5, 22)

    with pytest.raises(ValueError, match=r"missing treatment dates=\['2000-05-22'\]"):
        _compare_complete_qa_metrics(
            control_by_date={first_day: {"ndcg_at_50": 0.5}, second_day: {"ndcg_at_50": 0.6}},
            treatment_by_date={first_day: {"ndcg_at_50": 0.51}},
            expected_dates=[first_day, second_day],
            evidence_name="quality",
        )


def test_hv_qa_evaluate_judges_existing_proof_directory(tmp_path: Path) -> None:
    proof_dir = tmp_path / "proof"
    for day, control_ndcg, treatment_ndcg in [
        ("2000-05-24", 0.50, 0.51),
        ("2000-05-25", 0.60, 0.612),
    ]:
        control_result = proof_dir / day / "control" / "result.json"
        treatment_result = proof_dir / day / "treatment" / "result.json"
        control_result.parent.mkdir(parents=True)
        treatment_result.parent.mkdir(parents=True)
        control_result.write_text(
            json.dumps(
                {
                    "algorithm_id": "example-control-algorithm@86.3.4",
                    "test_data_time": day,
                    "evaluate": {
                        "ndcg_at_50": control_ndcg,
                        "map_at_50": 0.40,
                    },
                    "algorithm_definition": _performance_proof_algorithm_definition(),
                    "performance_test": _performance_proof_result(
                        latency_ms=10.0,
                        throughput=100.0,
                        memory=1.0,
                    ),
                }
            ),
            encoding="utf-8",
        )
        treatment_result.write_text(
            json.dumps(
                {
                    "algorithm_id": "example-control-algorithm@86.3.5",
                    "test_data_time": day,
                    "evaluate": {
                        "ndcg_at_50": treatment_ndcg,
                        "map_at_50": 0.42,
                    },
                    "algorithm_definition": _performance_proof_algorithm_definition(),
                    "performance_test": _performance_proof_result(
                        latency_ms=9.0,
                        throughput=101.0,
                        memory=0.9,
                    ),
                }
            ),
            encoding="utf-8",
        )

    output_path = tmp_path / "judgment.json"
    args = [
        "evaluate",
        "--criteria",
        "noninferiority",
        "--last-test-date",
        "2000-05-25",
        "--days",
        "2",
        "--proof-dir",
        str(proof_dir),
        "--output",
        str(output_path),
        "--pretty",
    ]

    result = _run_hv_qa(*args)

    assert result.returncode == 0
    payload = json.loads(result.stdout)
    assert payload["ok"] is True
    assert payload["criteria"] == "noninferiority"
    assert payload["judgment"] == "pass"
    assert payload["stage"] == "multi_day_backtest"
    assert payload["dates_used"] == ["2000-05-24", "2000-05-25"]
    assert payload["control_id"] == "example-control-algorithm@86.3.4"
    assert payload["treatment_id"] == "example-control-algorithm@86.3.5"
    assert payload["criteria_judgment"]["arm_decisions"][0]["performance_spec_compatible"] is True
    assert output_path.exists()
    assert json.loads(output_path.read_text(encoding="utf-8")) == payload


def test_hv_qa_evaluate_rejects_single_day_quality_judgment(tmp_path: Path) -> None:
    result = _run_hv_qa(
        "evaluate",
        "--criteria",
        "noninferiority",
        "--last-test-date",
        "2000-05-25",
        "--days",
        "1",
        "--proof-dir",
        str(tmp_path / "proof"),
    )

    assert result.returncode != 0
    assert "requires at least two paired dates" in result.stderr
    assert "execution smoke check, not a quality judgment" in result.stderr


def test_hv_qa_evaluate_uses_central_values_from_metric_estimates(tmp_path: Path) -> None:
    proof_dir = tmp_path / "proof"
    for day, control_ndcg, treatment_ndcg in [
        ("2000-05-24", 0.50, 0.51),
        ("2000-05-25", 0.60, 0.612),
    ]:
        control_result = proof_dir / day / "control" / "result.json"
        treatment_result = proof_dir / day / "treatment" / "result.json"
        control_result.parent.mkdir(parents=True)
        treatment_result.parent.mkdir(parents=True)
        control_result.write_text(
            json.dumps(
                {
                    "algorithm_id": "example-control-algorithm@86.3.4",
                    "test_data_time": day,
                    "evaluate": {
                        "ndcg_at_50": {
                            "value": control_ndcg,
                            "ci95_lower": control_ndcg - 0.01,
                            "ci95_upper": control_ndcg + 0.01,
                        },
                        "map_at_50": {"value": 0.40, "ci95_lower": 0.39, "ci95_upper": 0.41},
                    },
                    "algorithm_definition": _performance_proof_algorithm_definition(),
                    "performance_test": _performance_proof_result(
                        latency_ms=10.0,
                        throughput=100.0,
                        memory=1.0,
                    ),
                }
            ),
            encoding="utf-8",
        )
        treatment_result.write_text(
            json.dumps(
                {
                    "algorithm_id": "example-control-algorithm@86.3.5",
                    "test_data_time": day,
                    "evaluate": {
                        "ndcg_at_50": {
                            "value": treatment_ndcg,
                            "ci95_lower": treatment_ndcg - 0.01,
                            "ci95_upper": treatment_ndcg + 0.01,
                        },
                        "map_at_50": {"value": 0.42, "ci95_lower": 0.41, "ci95_upper": 0.43},
                    },
                    "algorithm_definition": _performance_proof_algorithm_definition(),
                    "performance_test": _performance_proof_result(
                        latency_ms=9.0,
                        throughput=101.0,
                        memory=0.9,
                    ),
                }
            ),
            encoding="utf-8",
        )

    result = _run_hv_qa(
        "evaluate",
        "--criteria",
        "noninferiority",
        "--last-test-date",
        "2000-05-25",
        "--days",
        "2",
        "--proof-dir",
        str(proof_dir),
    )

    assert result.returncode == 0
    payload = json.loads(result.stdout)
    assert payload["judgment"] == "pass"
    assert payload["quality_statistical_basis"] == {
        "pairing_unit": "test_date",
        "metric_estimate_field": "value",
        "source_confidence_intervals": "not_used_without_paired_covariance",
    }
    assert payload["quality_metrics"]["ndcg_at_50"]["control_values"] == [0.50, 0.60]
    assert payload["quality_metrics"]["ndcg_at_50"]["treatment_values"] == [0.51, 0.612]


def test_hv_qa_parameter_stage_request_enables_fixed_parameter_stages(tmp_path: Path) -> None:
    result = _run_hv_qa(
        "start",
        "--control",
        "v86.3.4",
        "--treatment",
        "feature/example-cache-v86.3.5",
        "--last-test-date",
        "2000-04-14",
        "--days",
        "7",
        "--until",
        "predict_parity",
        "--work-dir",
        str(tmp_path / "meta"),
    )

    assert result.returncode == 2
    payload = json.loads(result.stdout)
    assert payload["ok"] is False
    assert payload["parameter_source"] is None
    assert payload["stage_sequence"] == [
        "encode_parity",
        "system_performance",
        "predict_parity",
        "realistic_single_day",
        "multi_day_backtest",
    ]
    assert payload["requested_stages"] == [
        "encode_parity",
        "system_performance",
        "predict_parity",
    ]


def test_hv_qa_exact_forces_parity_before_quality(tmp_path: Path) -> None:
    result = _run_hv_qa(
        "start",
        "--control",
        "v86.3.4",
        "--treatment",
        "feature/runtime-upgrade-v86",
        "--last-test-date",
        "2000-04-14",
        "--days",
        "7",
        "--criteria",
        "exact",
        "--until",
        "multi_day_backtest",
        "--work-dir",
        str(tmp_path / "meta"),
    )

    assert result.returncode == 2
    payload = json.loads(result.stdout)
    assert payload["criteria"]["id"] == "exact"
    assert payload["stage_sequence"] == [
        "audit_parity",
        "encode_parity",
        "predict_parity",
        "system_performance",
        "realistic_single_day",
        "multi_day_backtest",
    ]
    assert payload["requested_stages"] == [
        "audit_parity",
        "encode_parity",
        "predict_parity",
        "system_performance",
        "realistic_single_day",
        "multi_day_backtest",
    ]


def test_hv_qa_exact_criteria_refuses_manual_parameter_source(tmp_path: Path) -> None:
    result = _run_hv_qa(
        "start",
        "--control",
        "v86.3.4",
        "--treatment",
        "feature/runtime-upgrade-v86",
        "--last-test-date",
        "2000-04-14",
        "--days",
        "7",
        "--criteria",
        "exact",
        "--parameter-source",
        "s3://example-bucket/path/params.zip",
        "--work-dir",
        str(tmp_path / "meta"),
    )

    assert result.returncode == 2
    assert result.stdout == ""
    assert "does not accept manual parameter source flags" in result.stderr


def test_hv_qa_parameter_backed_stage_requires_derivable_context(tmp_path: Path) -> None:
    result = _run_hv_qa(
        "start",
        "--control",
        "v86.3.4",
        "--treatment",
        "feature/example-cache-v86.3.5",
        "--last-test-date",
        "2000-04-14",
        "--days",
        "7",
        "--until",
        "predict_parity",
        "--work-dir",
        str(tmp_path / "meta"),
    )

    assert result.returncode != 0
    payload = json.loads(result.stdout)
    assert payload["requested_stages"] == [
        "encode_parity",
        "system_performance",
        "predict_parity",
    ]
    assert "fixed-parameter QA setup" in result.stderr


def test_hv_qa_rejects_configured_last_test_date(tmp_path: Path) -> None:
    home_dir = tmp_path / "home"
    config_path = home_dir / ".hotvect" / "config.json"
    config_path.parent.mkdir(parents=True, exist_ok=True)
    config_path.write_text(
        json.dumps({"qa": {"run": {"defaults": {"last_test_date": "2000-04-14"}}}}),
        encoding="utf-8",
    )
    result = _run_hv_qa(
        "start",
        "--control",
        "v86.3.4",
        "--treatment",
        "feature/example-cache-v86.3.5",
        "--last-test-date",
        "2000-04-14",
        "--work-dir",
        str(tmp_path / "meta"),
        env_overrides={"HOME": str(home_dir)},
    )

    assert result.returncode != 0
    assert "qa.run.defaults.last_test_date" in result.stderr


def test_hv_qa_can_read_namespaced_defaults_from_config(tmp_path: Path) -> None:
    home_dir = tmp_path / "home"
    config_path = home_dir / ".hotvect" / "config.json"
    config_path.parent.mkdir(parents=True, exist_ok=True)
    config_path.write_text(
        json.dumps(
            {
                "qa": {
                    "run": {
                        "defaults": {
                            "backtest_days": 3,
                            "evaluation_criteria": "noninferiority",
                        }
                    }
                }
            }
        ),
        encoding="utf-8",
    )

    result = _run_hv_qa(
        "start",
        "--control",
        "v86.1.4",
        "--treatment",
        "v86.1.13",
        "--last-test-date",
        "2000-04-14",
        "--work-dir",
        str(tmp_path / "meta"),
        env_overrides={"HOME": str(home_dir)},
    )

    assert result.returncode == 2
    payload = json.loads(result.stdout)
    assert payload["ok"] is False
    assert payload["criteria"]["id"] == "noninferiority"
    assert payload["offline_context"] == {
        "last_test_date": "2000-04-14",
        "backtest_days": 3,
        "backtest_date_window": {
            "start_date": "2000-04-12",
            "end_date": "2000-04-14",
        },
    }
