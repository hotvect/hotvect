from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

from hotvect.evaluation_criteria import list_builtin_criteria_ids, load_builtin_policy


def _quality_metric(percent_change: float, *, date_count: int = 7) -> dict[str, object]:
    return {
        "percent_change": percent_change,
        "paired_percent_changes": [percent_change] * date_count,
    }


def _quality_metric_series(paired_percent_changes: list[float]) -> dict[str, object]:
    return {
        "percent_change": sum(paired_percent_changes) / len(paired_percent_changes),
        "paired_percent_changes": paired_percent_changes,
    }


def _system_metric(paired_percent_changes: list[float]) -> dict[str, object]:
    return {
        "percent_change": sum(paired_percent_changes) / len(paired_percent_changes),
        "paired_percent_changes": paired_percent_changes,
    }


def _python_dir() -> Path:
    return Path(__file__).resolve().parents[3]


def test_builtin_criteria_ids_are_exposed() -> None:
    assert list_builtin_criteria_ids() == ["exact", "noninferiority", "superiority"]


def test_noninferiority_multi_day_backtest_ranks_passers_and_marks_ready_for_online() -> None:
    policy = load_builtin_policy("noninferiority")

    payload = {
        "scenario": "qa",
        "stage": "multi_day_backtest",
        "context": {"online_policy_known": True},
        "treatments": [
            {
                "git_ref": "treatment-a",
                "status": {
                    "jobs_complete": True,
                    "has_semantic_shortcuts": False,
                    "performance_spec_compatible": True,
                },
                "metrics": {
                    "offline_quality": 0.82,
                    "performance": 0.61,
                    "quality": {"ndcg_at_50": _quality_metric(0.0)},
                    "system": {"p99": _system_metric([0.0] * 7)},
                },
            },
            {
                "git_ref": "treatment-b",
                "status": {
                    "jobs_complete": True,
                    "has_semantic_shortcuts": False,
                    "performance_spec_compatible": True,
                },
                "metrics": {
                    "offline_quality": 0.91,
                    "performance": 0.55,
                    "quality": {"ndcg_at_50": _quality_metric(0.1)},
                    "system": {"p99": _system_metric([0.0] * 7)},
                },
            },
            {
                "git_ref": "treatment-c",
                "status": {
                    "jobs_complete": True,
                    "has_semantic_shortcuts": False,
                    "performance_spec_compatible": True,
                },
                "metrics": {
                    "offline_quality": 0.99,
                    "performance": 0.99,
                    "quality": {"ndcg_at_50": _quality_metric(-1.0)},
                    "system": {"p99": _system_metric([0.0] * 7)},
                },
            },
        ],
    }

    result = policy.evaluate(payload)

    assert result["criteria"]["id"] == "noninferiority"
    assert result["stage_judgment"] == "pass"
    assert result["rollout_recommendation"] == "ready_for_online"
    assert result["selected_treatments"] == ["treatment-b", "treatment-a"]
    assert any(
        decision["git_ref"] == "treatment-c" and decision["judgment"] == "fail" for decision in result["arm_decisions"]
    )


def test_noninferiority_multi_day_backtest_gates_only_explicit_quality_metrics() -> None:
    policy = load_builtin_policy("noninferiority")

    result = policy.evaluate(
        {
            "scenario": "qa",
            "stage": "multi_day_backtest",
            "treatments": [
                {
                    "git_ref": "score-shifted-treatment",
                    "status": {
                        "jobs_complete": True,
                        "has_semantic_shortcuts": False,
                        "performance_spec_compatible": True,
                    },
                    "metrics": {
                        "offline_quality": 0.91,
                        "performance": 0.99,
                        "quality": {
                            "ndcg_at_50": _quality_metric(0.0),
                            "mean_score": {"percent_change": -99.0},
                            "algorithm.mean_score": {"absolute_change": -1.0},
                            "diversity@5": {"paired_percent_changes": [-99.0] * 7},
                            "algorithm.diversity@10": {"paired_percent_changes": [-50.0] * 7},
                            "new_metric": {"paired_percent_changes": [-50.0] * 7},
                        },
                        "system": {"p99": _system_metric([0.0] * 7)},
                    },
                }
            ],
        }
    )

    assert result["stage_judgment"] == "pass"
    assert result["selected_treatments"] == ["score-shifted-treatment"]
    assert result["arm_decisions"][0]["quality_statistically_noninferior"] is True
    assert "diversity@5" not in result["arm_decisions"][0]["quality_statistical_tests"]
    assert "algorithm.diversity@10" not in result["arm_decisions"][0]["quality_statistical_tests"]
    assert "new_metric" not in result["arm_decisions"][0]["quality_statistical_tests"]


def test_noninferiority_multi_day_backtest_requires_system_metrics() -> None:
    policy = load_builtin_policy("noninferiority")

    result = policy.evaluate(
        {
            "scenario": "qa",
            "stage": "multi_day_backtest",
            "treatments": [
                {
                    "git_ref": "quality-only-treatment",
                    "status": {
                        "jobs_complete": True,
                        "has_semantic_shortcuts": False,
                        "performance_spec_compatible": True,
                    },
                    "metrics": {
                        "offline_quality": 0.91,
                        "quality": {"ndcg_at_50": _quality_metric(0.0)},
                    },
                }
            ],
        }
    )

    assert result["stage_judgment"] == "inconclusive"
    assert result["selected_treatments"] == []


def test_noninferiority_multi_day_backtest_requires_paired_quality_statistical_test() -> None:
    policy = load_builtin_policy("noninferiority")

    result = policy.evaluate(
        {
            "scenario": "qa",
            "stage": "multi_day_backtest",
            "treatments": [
                {
                    "git_ref": "one-date-treatment",
                    "status": {
                        "jobs_complete": True,
                        "has_semantic_shortcuts": False,
                        "performance_spec_compatible": True,
                    },
                    "metrics": {
                        "offline_quality": 0.91,
                        "quality": {"ndcg_at_50": _quality_metric(0.0, date_count=1)},
                    },
                }
            ],
        }
    )

    assert result["stage_judgment"] == "inconclusive"
    assert result["arm_decisions"][0]["quality_statistically_noninferior"] is None
    assert result["arm_decisions"][0]["quality_statistical_tests"]["ndcg_at_50"]["reason"] == (
        "requires_at_least_two_paired_dates"
    )
    assert "performance_statistically_noninferior" in result["arm_decisions"][0]
    assert "performance_within_contract" not in result["arm_decisions"][0]


def test_superiority_multi_day_backtest_requires_quality_gain() -> None:
    policy = load_builtin_policy("superiority")

    result = policy.evaluate(
        {
            "scenario": "qa",
            "stage": "multi_day_backtest",
            "context": {"online_policy_known": True},
            "treatments": [
                {
                    "git_ref": "flat-treatment",
                    "status": {
                        "jobs_complete": True,
                        "has_semantic_shortcuts": False,
                        "performance_spec_compatible": True,
                    },
                    "metrics": {
                        "offline_quality": 0.82,
                        "performance": 0.61,
                        "quality": {"ndcg_at_50": _quality_metric(0.0)},
                        "system": {"p99": _system_metric([0.0] * 7)},
                    },
                },
                {
                    "git_ref": "noisy-positive-treatment",
                    "status": {
                        "jobs_complete": True,
                        "has_semantic_shortcuts": False,
                        "performance_spec_compatible": True,
                    },
                    "metrics": {
                        "offline_quality": 0.87,
                        "performance": 0.59,
                        "quality": {"ndcg_at_50": _quality_metric_series([0.4, -0.35, 0.3, -0.25, 0.2, -0.15, 0.1])},
                        "system": {"p99": _system_metric([0.0] * 7)},
                    },
                },
                {
                    "git_ref": "better-treatment",
                    "status": {
                        "jobs_complete": True,
                        "has_semantic_shortcuts": False,
                        "performance_spec_compatible": True,
                    },
                    "metrics": {
                        "offline_quality": 0.91,
                        "performance": 0.55,
                        "quality": {"ndcg_at_50": _quality_metric(0.1)},
                        "system": {"p99": _system_metric([0.0] * 7)},
                    },
                },
            ],
        }
    )

    assert result["criteria"]["id"] == "superiority"
    assert result["stage_judgment"] == "pass"
    assert result["rollout_recommendation"] == "ready_for_online"
    assert result["selected_treatments"] == ["better-treatment"]
    assert [
        (decision["git_ref"], decision["judgment"], decision["quality_superior"])
        for decision in result["arm_decisions"]
    ] == [
        ("flat-treatment", "fail", False),
        ("noisy-positive-treatment", "fail", False),
        ("better-treatment", "pass", True),
    ]


def test_superiority_multi_day_backtest_gates_only_explicit_quality_metrics() -> None:
    policy = load_builtin_policy("superiority")

    result = policy.evaluate(
        {
            "scenario": "qa",
            "stage": "multi_day_backtest",
            "treatments": [
                {
                    "git_ref": "better-treatment",
                    "status": {
                        "jobs_complete": True,
                        "has_semantic_shortcuts": False,
                        "performance_spec_compatible": True,
                    },
                    "metrics": {
                        "offline_quality": 0.91,
                        "performance": 0.55,
                        "quality": {
                            "ndcg_at_50": _quality_metric(0.1),
                            "mean_score": {"paired_percent_changes": [-99.0] * 7},
                            "diversity@30": {"paired_percent_changes": [-50.0] * 7},
                            "algorithm.diversity@5": {"paired_percent_changes": [-25.0] * 7},
                            "new_metric": {"paired_percent_changes": [-25.0] * 7},
                        },
                        "system": {"p99": _system_metric([0.0] * 7)},
                    },
                }
            ],
        }
    )

    assert result["stage_judgment"] == "pass"
    assert result["selected_treatments"] == ["better-treatment"]
    assert result["arm_decisions"][0]["quality_superior"] is True
    assert "mean_score" not in result["arm_decisions"][0]["quality_superiority_tests"]
    assert "diversity@30" not in result["arm_decisions"][0]["quality_superiority_tests"]
    assert "algorithm.diversity@5" not in result["arm_decisions"][0]["quality_superiority_tests"]
    assert "new_metric" not in result["arm_decisions"][0]["quality_superiority_tests"]


def test_superiority_corrects_across_metrics_and_treatments() -> None:
    policy = load_builtin_policy("superiority")
    marginal_gain = _quality_metric_series([0.5, 0.5, 0.5, 0.5, 0.5, -0.2, -0.2])

    result = policy.evaluate(
        {
            "scenario": "qa",
            "stage": "multi_day_backtest",
            "treatments": [
                {
                    "git_ref": treatment_id,
                    "status": {
                        "jobs_complete": True,
                        "has_semantic_shortcuts": False,
                        "performance_spec_compatible": True,
                    },
                    "metrics": {
                        "quality": {
                            "map_at_50": marginal_gain,
                            "ndcg_at_50": marginal_gain,
                        },
                        "system": {"p99": _system_metric([0.0] * 7)},
                    },
                }
                for treatment_id in ("treatment-a", "treatment-b")
            ],
        }
    )

    assert result["stage_judgment"] == "fail"
    assert result["selected_treatments"] == []
    for decision in result["arm_decisions"]:
        for test in decision["quality_superiority_tests"].values():
            assert 0.025 < test["p_value"] < 0.05
            assert test["nominal_alpha"] == 0.05
            assert test["alpha"] == 0.0125
            assert test["multiplicity_adjustment"] == "bonferroni"
            assert test["family_size"] == 4
            assert test["passed"] is False


def test_noninferiority_realistic_single_day_stops_when_nothing_passes() -> None:
    policy = load_builtin_policy("noninferiority")

    payload = {
        "scenario": "qa",
        "stage": "realistic_single_day",
        "treatments": [
            {
                "git_ref": "broken-treatment",
                "status": {
                    "jobs_complete": True,
                    "packaging_ok": False,
                    "data_shape_ok": True,
                    "runtime_ok": True,
                },
            }
        ],
    }

    result = policy.evaluate(payload)

    assert result["stage_judgment"] == "fail"
    assert result["rollout_recommendation"] == "stop"
    assert result["selected_treatments"] == []


def test_noninferiority_system_performance_requires_parameter_source() -> None:
    policy = load_builtin_policy("noninferiority")

    with pytest.raises(ValueError, match="parameter_source"):
        policy.evaluate(
            {
                "scenario": "qa",
                "stage": "system_performance",
                "treatments": [
                    {
                        "git_ref": "runtime-cleanup",
                        "status": {"jobs_complete": True},
                    }
                ],
            }
        )


def test_noninferiority_system_performance_fails_on_statistically_supported_regression_beyond_margin() -> None:
    policy = load_builtin_policy("noninferiority")

    result = policy.evaluate(
        {
            "scenario": "qa",
            "stage": "system_performance",
            "parameter_source": "s3://example-bucket/path/params.zip",
            "treatments": [
                {
                    "git_ref": "runtime-regression",
                    "status": {"jobs_complete": True, "performance_spec_compatible": True},
                    "metrics": {
                        "p99_ms": 106.0,
                        "throughput_rps": 1000.0,
                        "peak_rss_gib": 8.0,
                        "system": {"p99": _system_metric([4.0] * 7)},
                    },
                }
            ],
        }
    )

    assert result["stage_judgment"] == "fail"
    assert result["rollout_recommendation"] == "stop"
    assert result["arm_decisions"][0]["performance_statistically_noninferior"] is False
    assert result["arm_decisions"][0]["performance_statistical_tests"]["p99"]["margin_percent"] == 3.0
    assert "performance_within_contract" not in result["arm_decisions"][0]


def test_noninferiority_system_performance_requires_spec_compatibility_result() -> None:
    policy = load_builtin_policy("noninferiority")

    result = policy.evaluate(
        {
            "scenario": "qa",
            "stage": "system_performance",
            "parameter_source": "s3://example-bucket/path/params.zip",
            "treatments": [
                {
                    "git_ref": "runtime-unspecified-contract",
                    "status": {"jobs_complete": True},
                    "metrics": {"system": {"p99": _system_metric([2.0] * 7)}},
                }
            ],
        }
    )

    assert result["stage_judgment"] == "inconclusive"
    assert result["rollout_recommendation"] == "stop"
    assert result["arm_decisions"][0]["performance_spec_compatible"] is None


def test_noninferiority_system_performance_requires_paired_trials_for_statistical_gate() -> None:
    policy = load_builtin_policy("noninferiority")

    result = policy.evaluate(
        {
            "scenario": "qa",
            "stage": "system_performance",
            "parameter_source": "s3://example-bucket/path/params.zip",
            "treatments": [
                {
                    "git_ref": "runtime-single-trial",
                    "status": {"jobs_complete": True, "performance_spec_compatible": True},
                    "metrics": {
                        "p99_ms": 101.0,
                        "throughput_rps": 1000.0,
                        "peak_rss_gib": 8.0,
                        "system": {"p99": _system_metric([1.0])},
                    },
                }
            ],
        }
    )

    assert result["stage_judgment"] == "inconclusive"
    assert result["rollout_recommendation"] == "stop"
    assert result["arm_decisions"][0]["performance_statistically_noninferior"] is None
    assert result["arm_decisions"][0]["performance_statistical_tests"]["p99"]["reason"] == (
        "requires_at_least_two_paired_trials"
    )


def test_noninferiority_system_performance_fails_when_noninferiority_is_not_proven() -> None:
    policy = load_builtin_policy("noninferiority")

    result = policy.evaluate(
        {
            "scenario": "qa",
            "stage": "system_performance",
            "parameter_source": "s3://example-bucket/path/params.zip",
            "treatments": [
                {
                    "git_ref": "runtime-noisy",
                    "status": {"jobs_complete": True, "performance_spec_compatible": True},
                    "metrics": {
                        "p99_ms": 101.0,
                        "throughput_rps": 1000.0,
                        "peak_rss_gib": 8.0,
                        "system": {"p99": _system_metric([10.0, -10.0, 8.0, -8.0, 6.0, -6.0, 4.0])},
                    },
                }
            ],
        }
    )

    assert result["stage_judgment"] == "fail"
    assert result["rollout_recommendation"] == "stop"
    assert result["arm_decisions"][0]["performance_statistically_noninferior"] is False
    assert result["selected_treatments"] == []


def test_noninferiority_system_performance_passes_when_regression_is_within_margin() -> None:
    policy = load_builtin_policy("noninferiority")

    result = policy.evaluate(
        {
            "scenario": "qa",
            "stage": "system_performance",
            "parameter_source": "s3://example-bucket/path/params.zip",
            "treatments": [
                {
                    "git_ref": "runtime-within-margin",
                    "status": {"jobs_complete": True, "performance_spec_compatible": True},
                    "metrics": {
                        "p99_ms": 103.0,
                        "throughput_rps": 1000.0,
                        "peak_rss_gib": 8.0,
                        "system": {"p99": _system_metric([2.0] * 7)},
                    },
                }
            ],
        }
    )

    assert result["stage_judgment"] == "pass"
    assert result["rollout_recommendation"] == "advance"
    assert result["arm_decisions"][0]["performance_statistically_noninferior"] is True
    assert result["arm_decisions"][0]["performance_statistical_tests"]["p99"]["margin_percent"] == 3.0
    assert result["selected_treatments"] == ["runtime-within-margin"]


def test_noninferiority_multi_day_backtest_uses_statistical_system_safety_when_present() -> None:
    policy = load_builtin_policy("noninferiority")

    result = policy.evaluate(
        {
            "scenario": "qa",
            "stage": "multi_day_backtest",
            "treatments": [
                {
                    "git_ref": "perf-regressed-treatment",
                    "status": {
                        "jobs_complete": True,
                        "has_semantic_shortcuts": False,
                        "performance_spec_compatible": True,
                    },
                    "metrics": {
                        "offline_quality": 0.91,
                        "performance": 0.55,
                        "quality": {"ndcg_at_50": _quality_metric(0.0)},
                        "system": {"p99": _system_metric([4.0] * 7)},
                    },
                }
            ],
        }
    )

    assert result["stage_judgment"] == "fail"
    assert result["selected_treatments"] == []
    assert result["arm_decisions"][0]["performance_statistically_noninferior"] is False


def test_noninferiority_predict_parity_passes_with_parameter_source() -> None:
    policy = load_builtin_policy("noninferiority")

    result = policy.evaluate(
        {
            "scenario": "qa",
            "stage": "predict_parity",
            "parameter_source": "s3://example-bucket/path/params.zip",
            "control": {
                "git_ref": "baseline",
                "status": {
                    "jobs_complete": True,
                },
            },
            "treatments": [
                {
                    "git_ref": "runtime-cleanup",
                    "status": {
                        "jobs_complete": True,
                        "output_equivalent": True,
                    },
                }
            ],
        }
    )

    assert result["stage_judgment"] == "pass"
    assert result["rollout_recommendation"] == "advance"
    assert result["selected_treatments"] == ["runtime-cleanup"]


def test_noninferiority_predict_parity_can_pass_when_treatment_matches_control_failure() -> None:
    policy = load_builtin_policy("noninferiority")

    result = policy.evaluate(
        {
            "scenario": "qa",
            "stage": "predict_parity",
            "parameter_source": "s3://example-bucket/path/params.zip",
            "control": {
                "git_ref": "baseline",
                "status": {
                    "jobs_complete": False,
                },
            },
            "treatments": [
                {
                    "git_ref": "runtime-cleanup",
                    "status": {
                        "jobs_complete": False,
                        "matched_control_failure": True,
                    },
                }
            ],
        }
    )

    assert result["stage_judgment"] == "pass"
    assert result["rollout_recommendation"] == "advance"
    assert result["selected_treatments"] == ["runtime-cleanup"]


def test_exact_requires_shared_parameter_source_for_encode_parity() -> None:
    policy = load_builtin_policy("exact")
    payload = {
        "scenario": "qa",
        "stage": "encode_parity",
        "control": {"git_ref": "baseline", "status": {"jobs_complete": True}},
        "treatments": [
            {
                "git_ref": "runtime-upgrade",
                "status": {"jobs_complete": True, "output_equivalent": True},
            }
        ],
    }

    with pytest.raises(ValueError, match="shared 'parameter_source'"):
        policy.evaluate(payload)

    payload["parameter_source"] = "s3://example-bucket/path/params.zip"
    result = policy.evaluate(payload)

    assert result["criteria"]["id"] == "exact"
    assert result["stage_judgment"] == "pass"
    assert result["selected_treatments"] == ["runtime-upgrade"]


def test_exact_accepts_audit_parity_with_shared_parameter_source() -> None:
    policy = load_builtin_policy("exact")
    payload = {
        "scenario": "qa",
        "stage": "audit_parity",
        "control": {"git_ref": "baseline", "status": {"jobs_complete": True}},
        "treatments": [
            {
                "git_ref": "runtime-upgrade",
                "status": {"jobs_complete": True, "output_equivalent": True},
            }
        ],
    }

    with pytest.raises(ValueError, match="shared 'parameter_source'"):
        policy.evaluate(payload)

    payload["parameter_source"] = "s3://example-bucket/path/params.zip"
    result = policy.evaluate(payload)

    assert result["criteria"]["id"] == "exact"
    assert result["stage_judgment"] == "pass"
    assert result["selected_treatments"] == ["runtime-upgrade"]


def test_exact_rejects_matched_control_failure_for_encode_parity() -> None:
    policy = load_builtin_policy("exact")

    result = policy.evaluate(
        {
            "scenario": "qa",
            "stage": "encode_parity",
            "parameter_source": "s3://example-bucket/path/params.zip",
            "control": {
                "git_ref": "baseline",
                "status": {
                    "jobs_complete": False,
                },
            },
            "treatments": [
                {
                    "git_ref": "runtime-upgrade",
                    "status": {
                        "jobs_complete": False,
                        "matched_control_failure": True,
                    },
                }
            ],
        }
    )

    assert result["stage_judgment"] == "fail"
    assert result["rollout_recommendation"] == "stop"
    assert result["selected_treatments"] == []


def test_workflow_specific_criteria_are_not_public_builtins() -> None:
    for criteria_id in [
        "compare-exact-v1",
        "compare-noninferiority-v1",
        "parameter-train-acceptance-v1",
        "performance-noninferiority-v1",
    ]:
        with pytest.raises(KeyError, match=criteria_id):
            load_builtin_policy(criteria_id)


def test_module_cli_can_list_and_describe_builtins() -> None:
    python_dir = _python_dir()
    env = dict(os.environ, PYTHONPATH=str(python_dir))

    list_result = subprocess.run(
        [sys.executable, "-m", "hotvect.evaluation_criteria", "list"],
        cwd=python_dir,
        capture_output=True,
        text=True,
        env=env,
    )
    assert list_result.returncode == 0
    listed = json.loads(list_result.stdout)
    assert "noninferiority" in listed["criteria"]

    describe_result = subprocess.run(
        [sys.executable, "-m", "hotvect.evaluation_criteria", "describe", "noninferiority"],
        cwd=python_dir,
        capture_output=True,
        text=True,
        env=env,
    )
    assert describe_result.returncode == 0
    manifest = json.loads(describe_result.stdout)
    assert manifest["scenario"] == "qa"
    assert manifest["id"] == "noninferiority"
    assert "version" not in manifest
