from __future__ import annotations

import gzip
import json
import os
import subprocess
import zipfile
from datetime import date, datetime, timedelta, timezone
from io import BytesIO
from pathlib import Path
from types import SimpleNamespace

import pytest
from botocore.exceptions import ClientError

from hotvect.qa.commands import run as run_module


def _run_args(meta_dir: Path, **overrides) -> SimpleNamespace:
    payload = {
        "resume": "",
        "control": "v86.3.4",
        "prod_default_of_slot_as_control": "",
        "treatments": ["feature/example-cleanup-v86"],
        "slot_name": "",
        "last_test_date": "2000-04-14",
        "backtest_days": "7",
        "parameter_source": "",
        "control_parameter_source": "",
        "treatment_parameter_sources": [],
        "evaluation_criteria": "noninferiority",
        "algo_repo_url": "/tmp/fake-repo",
        "control_algo_repo_url": "",
        "treatment_algo_repo_urls": [],
        "algorithm_name": "",
        "encode_algorithm_name": "",
        "control_encode_algorithm_name": "",
        "treatment_encode_algorithm_names": [],
        "source_path": "",
        "predict_source_path": "",
        "performance_source_path": "",
        "control_performance_source_path": "",
        "treatment_performance_source_paths": [],
        "encode_source_path": "",
        "performance_runner": "",
        "performance_sagemaker_job_prefix": "",
        "performance_sagemaker_config": "",
        "performance_role_arn": "",
        "performance_assume_role_arn": "",
        "performance_s3_output_base": "",
        "performance_instance_type": "",
        "performance_volume_gb": None,
        "performance_max_runtime_seconds": None,
        "performance_training_image": "",
        "performance_samples": None,
        "performance_sample_pool_size": None,
        "performance_target_rps": None,
        "performance_target_throughput_fraction": None,
        "performance_workload_mode": "",
        "performance_max_threads": None,
        "performance_poll_seconds": None,
        "performance_trials": None,
        "data_base_dir": "",
        "scratch_dir": "",
        "algorithm_overrides": [],
        "backtest_runner": "",
        "backtest_sagemaker_job_prefix": "",
        "backtest_sagemaker_config": "",
        "backtest_role_arn": "",
        "backtest_assume_role_arn": "",
        "backtest_s3_output_base": "",
        "backtest_instance_type": "",
        "backtest_volume_gb": None,
        "backtest_max_runtime_seconds": None,
        "backtest_training_image": "",
        "backtest_auto_attach_data_default_s3_base": "",
        "backtest_auto_attach_data_environment": "",
        "backtest_poll_seconds": None,
        "backtest_no_performance_test": False,
        "backtest_performance_test_samples": None,
        "backtest_performance_test_sample_pool_size": None,
        "until": "encode_parity",
        "only": "",
        "meta_dir": str(meta_dir),
    }
    payload.update(overrides)
    return SimpleNamespace(**payload)


def _patch_config_defaults(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(run_module.hv_config, "load_qa_run_defaults", lambda: {})
    monkeypatch.setattr(run_module.hv_config, "load_qa_run_execution_context", lambda: {})
    monkeypatch.setattr(run_module.hv_config, "load_qa_run_system_performance", lambda: {"trials": 3})
    monkeypatch.setattr(run_module.hv_config, "load_qa_run_backtest", lambda: {})
    monkeypatch.setattr(run_module.hv_config, "load_directory_defaults", lambda: {})
    monkeypatch.setattr(run_module.hv_config, "load_sagemaker_defaults", lambda: {})


def _catalog_parameter(parameter_id: str, created_at: datetime) -> SimpleNamespace:
    return SimpleNamespace(
        algorithm_parameter_id=parameter_id,
        algorithm=SimpleNamespace(algorithm_name="catalog", algorithm_version="1.0.0"),
        created_at=created_at,
        absolute_s3_path=f"s3://example-bucket/parameters/{parameter_id}.zip",
    )


def _patch_builds(monkeypatch: pytest.MonkeyPatch) -> None:
    def fake_build_ref_artifact(*, run_dir: Path, algo_repo_url: str, git_ref: str):
        version = "86.3.4" if git_ref in {"v86.3.4", "86.3.4"} else "86.3.5"
        jar_path = run_dir / "fake-artifacts" / f"{run_module.sanitize_path_component(git_ref)}.jar"
        jar_path.parent.mkdir(parents=True, exist_ok=True)
        jar_path.write_text(git_ref, encoding="utf-8")
        return run_module._BuiltRefArtifact(
            git_ref=git_ref,
            resolved_git_ref=git_ref,
            artifact_name="example-control-algorithm",
            artifact_version=version,
            git_commit=f"{run_module.sanitize_path_component(git_ref)}-commit",
            jar_path=jar_path,
            build_dir=jar_path.parent,
        )

    monkeypatch.setattr(run_module, "_build_ref_artifact", fake_build_ref_artifact)


def _patch_algorithm_definition_derivation(
    monkeypatch: pytest.MonkeyPatch,
    *,
    control_performance_spec: dict | None = None,
    treatment_performance_spec: dict | None = None,
    missing_control_performance_spec: bool = False,
    missing_treatment_performance_spec: bool = False,
) -> None:
    default_performance_spec = {
        "samples": 722,
        "sample_pool_size": 64,
        "target_rps": 10.0,
        "workload_mode": "realtime",
    }
    control_performance_spec = (
        default_performance_spec if control_performance_spec is None else control_performance_spec
    )
    treatment_performance_spec = (
        default_performance_spec if treatment_performance_spec is None else treatment_performance_spec
    )
    definitions = {
        "example-control-algorithm": {
            "algorithm_name": "example-control-algorithm",
            "dependencies": ["example-control-model"],
            "test_data_spec": {
                "s3_uri": {
                    "production": (
                        "s3://example-bucket/example-data/"
                        "example-training-data/"
                    )
                }
            },
        },
        "example-control-model": {
            "algorithm_name": "example-control-model",
            "training_command": "catboost_train --foo",
            "test_data_spec": {
                "s3_uri": {
                    "production": (
                        "s3://example-bucket/example-data/"
                        "example-training-data/"
                    )
                }
            },
        },
        "example-treatment-model": {
            "algorithm_name": "example-treatment-model",
            "training_command": "catboost_train --foo",
            "test_data_spec": {
                "s3_uri": {
                    "production": (
                        "s3://example-bucket/example-data/"
                        "example-training-data/"
                    )
                }
            },
        },
    }

    def fake_read_algorithm_definition_from_jar(*, algorithm_name: str, algorithm_jar_path: Path, additional_jars=None):
        del additional_jars
        definition = json.loads(json.dumps(definitions[algorithm_name]))
        if algorithm_name == "example-control-algorithm":
            performance_spec = (
                control_performance_spec if "v86.3.4" in algorithm_jar_path.name else treatment_performance_spec
            )
            if "v86.3.4" in algorithm_jar_path.name and missing_control_performance_spec:
                performance_spec = None
            if "v86.3.4" not in algorithm_jar_path.name and missing_treatment_performance_spec:
                performance_spec = None
            if performance_spec is not None:
                definition["hotvect_execution_parameters"] = {"performance-test": performance_spec}
        return definition

    def fake_as_locally_available_content(cache_path: str | None, local_cache_path: str) -> str | None:
        if cache_path is None:
            return None
        if cache_path.startswith("s3://"):
            local_dir = Path(local_cache_path) / "downloaded"
            local_dir.mkdir(parents=True, exist_ok=True)
            (local_dir / "part-000.jsonl").write_text('{"example_id":"ex-1"}\n', encoding="utf-8")
            return str(local_dir)
        path = Path(cache_path)
        return str(path) if path.exists() else None

    monkeypatch.setattr(run_module, "read_algorithm_definition_from_jar", fake_read_algorithm_definition_from_jar)
    monkeypatch.setattr(run_module, "as_locally_available_content", fake_as_locally_available_content)
    monkeypatch.setattr(
        run_module,
        "_localize_sampled_input_content",
        lambda source, cache_dir, sample_count: fake_as_locally_available_content(source, str(cache_dir)),
    )


def _write_fake_parameter_zip(
    path: Path,
    *,
    root_version: str = "86.3.4",
    child_version: str = "86.3.4",
) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as zip_file:
        zip_file.writestr(
            "example-control-algorithm/algorithm-parameters.json",
            json.dumps(
                {
                    "algorithm_name": "example-control-algorithm",
                    "algorithm_version": root_version,
                    "parameter_id": "last_test_date_2000-04-14",
                }
            ),
        )
        zip_file.writestr(
            "example-control-model/algorithm-parameters.json",
            json.dumps(
                {
                    "algorithm_name": "example-control-model",
                    "algorithm_version": child_version,
                    "parameter_id": "last_test_date_2000-04-14",
                }
            ),
        )
    return path


def _patch_hv_runner(monkeypatch: pytest.MonkeyPatch) -> None:
    def fake_run_hv(*, args: list[str], log_path: Path, cwd: Path | None = None):
        if args[0] == "encode":
            assert "--unordered" in args
        log_path.parent.mkdir(parents=True, exist_ok=True)
        log_path.write_text("ok\n", encoding="utf-8")
        command = args[0]

        if command == "encode":
            dest_path = Path(args[args.index("--dest-path") + 1])
            dest_path.parent.mkdir(parents=True, exist_ok=True)
            dest_path.write_text("1\tfeature_a\tfeature_b\n", encoding="utf-8")
            schema_path = Path(args[args.index("--dest-schema-path") + 1])
            schema_path.parent.mkdir(parents=True, exist_ok=True)
            schema_path.write_text(
                json.dumps({"columns": ["label", "feature_a", "feature_b"]}) + "\n", encoding="utf-8"
            )
            metadata_dir = Path(args[args.index("--metadata-path") + 1])
            metadata_dir.mkdir(parents=True, exist_ok=True)
            (metadata_dir / "metadata.json").write_text(json.dumps({"ok": True}) + "\n", encoding="utf-8")
            return {"command": args, "log_path": str(log_path), "tail": []}

        if command == "audit":
            dest_path = Path(args[args.index("--dest-path") + 1])
            dest_path.parent.mkdir(parents=True, exist_ok=True)
            dest_path.write_text(
                json.dumps(
                    {
                        "example_id": "ex-1",
                        "features": {"feature_a": 1.0, "feature_b": 2.0},
                    },
                    sort_keys=True,
                )
                + "\n",
                encoding="utf-8",
            )
            metadata_dir = Path(args[args.index("--metadata-path") + 1])
            metadata_dir.mkdir(parents=True, exist_ok=True)
            (metadata_dir / "metadata.json").write_text(json.dumps({"ok": True}) + "\n", encoding="utf-8")
            return {"command": args, "log_path": str(log_path), "tail": []}

        if command == "predict":
            dest_dir = Path(args[args.index("--dest-path") + 1])
            dest_dir.mkdir(parents=True, exist_ok=True)
            (dest_dir / "shard_0.jsonl").write_text(
                json.dumps(
                    {
                        "example_id": "ex-1",
                        "result": [
                            {"action_id": "a1", "rank": 1, "score": 0.25},
                            {"action_id": "a2", "rank": 2, "score": 0.11},
                        ],
                    }
                )
                + "\n",
                encoding="utf-8",
            )
            metadata_dir = Path(args[args.index("--metadata-path") + 1])
            metadata_dir.mkdir(parents=True, exist_ok=True)
            (metadata_dir / "metadata.json").write_text(json.dumps({"ok": True}) + "\n", encoding="utf-8")
            return {"command": args, "log_path": str(log_path), "tail": []}

        if command == "performance-test":
            metadata_dir = Path(args[args.index("--metadata-path") + 1])
            metadata_dir.mkdir(parents=True, exist_ok=True)
            is_control = "v86.3.4" in str(metadata_dir)
            payload = {
                "max_memory_usage": 1000.0 if is_control else 980.0,
                "mean_throughput": 500.0 if is_control else 510.0,
                "response_time_metrics": {
                    "mean_throughput": {"mean": 480.0 if is_control else 490.0},
                    "mean": {"mean": 10.0 if is_control else 9.8},
                    "p50": {"mean": 8.0 if is_control else 7.9},
                    "p75": {"mean": 9.0 if is_control else 8.8},
                    "p95": {"mean": 11.0 if is_control else 10.7},
                    "p99": {"mean": 12.0 if is_control else 11.5},
                    "p999": {"mean": 15.0 if is_control else 14.2},
                },
            }
            (metadata_dir / "metadata.json").write_text(json.dumps(payload) + "\n", encoding="utf-8")
            return {"command": args, "log_path": str(log_path), "tail": []}

        raise AssertionError(f"Unexpected hv command in test: {args}")

    monkeypatch.setattr(run_module, "_run_hv", fake_run_hv)


def test_qa_run_executes_encode_parity_with_execution_context(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _patch_config_defaults(monkeypatch)
    _patch_builds(monkeypatch)
    _patch_algorithm_definition_derivation(monkeypatch)
    _patch_hv_runner(monkeypatch)
    parameter_path = _write_fake_parameter_zip(tmp_path / "predict.parameters.zip")

    args = _run_args(
        tmp_path / "meta",
        parameter_source=str(parameter_path),
        until="encode_parity",
    )
    run_module.StartCommand().execute(args)

    payload = json.loads(capsys.readouterr().out)
    assert payload["action"] == "created"
    assert payload["state"] == "completed"
    assert payload["stage_status"]["encode_parity"] == "passed"
    assert payload["stage_results"]["encode_parity"]["stage_judgment"] == "pass"
    assert payload["execution_context"]["algorithm_name"] == "example-control-algorithm"
    assert (
        payload["execution_context"]["encode_algorithm_name"] == "example-control-model"
    )
    expected_source = (
        "s3://example-bucket/example-data/"
        "example-training-data/dt=2000-04-14/"
    )
    assert payload["execution_context"]["predict_source_path"] == expected_source
    assert payload["execution_context"]["performance_source_path"] == expected_source
    assert payload["execution_context"]["encode_source_path"] == expected_source
    assert payload["execution_context"]["source_path"] == expected_source
    assert payload["refs"]["control"]["resolved_algorithm_version"] == "86.3.4"
    assert payload["refs"]["treatments"][0]["resolved_algorithm_version"] == "86.3.5"


def test_qa_run_discovers_the_latest_complete_test_data_window(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _patch_config_defaults(monkeypatch)
    _patch_builds(monkeypatch)
    _patch_algorithm_definition_derivation(monkeypatch)
    _patch_hv_runner(monkeypatch)
    parameter_path = _write_fake_parameter_zip(tmp_path / "predict.parameters.zip")
    discovery_calls: list[tuple[str, int]] = []

    def fake_discovery(*, data_base_uri: str, days: int) -> str:
        discovery_calls.append((data_base_uri, days))
        return "2000-04-20"

    monkeypatch.setattr(run_module, "_discover_latest_contiguous_test_data_date", fake_discovery)

    args = _run_args(
        tmp_path / "meta",
        last_test_date="",
        backtest_days="",
        parameter_source=str(parameter_path),
        until="encode_parity",
    )
    run_module.StartCommand().execute(args)

    payload = json.loads(capsys.readouterr().out)
    assert discovery_calls == [
        (
            "s3://example-bucket/example-data/"
            "example-training-data/",
            7,
        )
    ]
    assert payload["offline_context"] == {
        "last_test_date": "2000-04-20",
        "backtest_days": 7,
        "backtest_date_window": {
            "start_date": "2000-04-14",
            "end_date": "2000-04-20",
        },
    }
    assert payload["execution_context"]["source_path"].endswith("/dt=2000-04-20/")


def test_exact_qa_run_starts_with_audit_parity(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _patch_config_defaults(monkeypatch)
    _patch_builds(monkeypatch)
    _patch_algorithm_definition_derivation(monkeypatch)
    hv_commands: list[str] = []

    def fake_run_hv(*, args: list[str], log_path: Path, cwd: Path | None = None):
        del cwd
        hv_commands.append(args[0])
        log_path.parent.mkdir(parents=True, exist_ok=True)
        log_path.write_text("ok\n", encoding="utf-8")
        metadata_dir = Path(args[args.index("--metadata-path") + 1])
        metadata_dir.mkdir(parents=True, exist_ok=True)
        (metadata_dir / "metadata.json").write_text(json.dumps({"ok": True}) + "\n", encoding="utf-8")

        if args[0] == "audit":
            dest_path = Path(args[args.index("--dest-path") + 1])
            dest_path.parent.mkdir(parents=True, exist_ok=True)
            dest_path.write_text(
                json.dumps({"example_id": "ex-1", "features": {"feature_a": 1.0}}, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            return {"command": args, "log_path": str(log_path), "tail": []}

        if args[0] == "encode":
            dest_path = Path(args[args.index("--dest-path") + 1])
            dest_path.parent.mkdir(parents=True, exist_ok=True)
            dest_path.write_text("1\tfeature_a\n", encoding="utf-8")
            schema_path = Path(args[args.index("--dest-schema-path") + 1])
            schema_path.parent.mkdir(parents=True, exist_ok=True)
            schema_path.write_text(json.dumps({"columns": ["label", "feature_a"]}) + "\n", encoding="utf-8")
            return {"command": args, "log_path": str(log_path), "tail": []}

        if args[0] == "predict":
            dest_dir = Path(args[args.index("--dest-path") + 1])
            dest_dir.mkdir(parents=True, exist_ok=True)
            (dest_dir / "part-00000.jsonl").write_text(
                json.dumps(
                    {"example_id": "ex-1", "result": [{"action_id": "a1", "rank": 1, "score": 0.5}]},
                    sort_keys=True,
                )
                + "\n",
                encoding="utf-8",
            )
            return {"command": args, "log_path": str(log_path), "tail": []}

        raise AssertionError(f"Unexpected hv command in test: {args}")

    monkeypatch.setattr(run_module, "_run_hv", fake_run_hv)
    parameter_path = _write_fake_parameter_zip(tmp_path / "predict.parameters.zip")

    class FakeExperimentManagementClient:
        def get_latest_algorithm_parameter(self, algorithm_name: str, algorithm_version: str):
            assert algorithm_name == "example-control-algorithm"
            assert algorithm_version == "86.3.4"
            return SimpleNamespace(
                algorithm_parameter_id="latest-example-parameter",
                absolute_s3_path=str(parameter_path),
            )

    monkeypatch.setattr(
        run_module,
        "create_client_from_hotvect_config",
        lambda: FakeExperimentManagementClient(),
    )
    args = _run_args(
        tmp_path / "meta",
        evaluation_criteria="exact",
        until="predict_parity",
    )
    run_module.StartCommand().execute(args)

    payload = json.loads(capsys.readouterr().out)
    assert payload["stage_sequence"][:4] == [
        "audit_parity",
        "encode_parity",
        "predict_parity",
        "system_performance",
    ]
    assert payload["stage_status"]["audit_parity"] == "passed"
    assert payload["stage_status"]["encode_parity"] == "passed"
    assert payload["stage_status"]["predict_parity"] == "passed"
    assert payload["stage_results"]["audit_parity"]["stage_judgment"] == "pass"
    assert payload["parameter_source"] == str(parameter_path)
    assert payload["parameter_source_resolution"]["source"] == "experiment_management"
    assert hv_commands == ["audit", "audit", "encode", "encode", "predict", "predict"]


def test_qa_run_can_resolve_control_from_prod_default_slot(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _patch_config_defaults(monkeypatch)
    monkeypatch.setattr(run_module, "_prepare_qa_run_execution", lambda **kwargs: {})
    monkeypatch.setattr(run_module, "_should_execute_requested_stages", lambda status: False)

    class FakeExperimentManagementClient:
        def get_default_variant_and_active_experiments(self, slot_name: str):
            assert slot_name == "example-slot"
            return {
                "default_variant": {
                    "variant_id": 2115,
                    "created_at": "2000-05-18T08:16:16Z",
                    "algorithm": {
                        "algorithm_name": "example-ranking-algorithm",
                        "algorithm_version": "18.5.11",
                    },
                }
            }

        def get_algorithm_parameters(self):
            return [
                SimpleNamespace(
                    algorithm_parameter_id="example-parameter-1",
                    algorithm=SimpleNamespace(
                        algorithm_name="example-ranking-algorithm",
                        algorithm_version="18.5.11",
                    ),
                    created_at=datetime(2000, 5, 17, 23, tzinfo=timezone.utc),
                    absolute_s3_path="s3://parameters/example-parameter-1.zip",
                )
            ]

    monkeypatch.setattr(
        run_module,
        "create_client_from_hotvect_config",
        lambda: FakeExperimentManagementClient(),
    )
    args = _run_args(
        tmp_path / "meta",
        control="",
        prod_default_of_slot_as_control="example-slot",
        treatments=["feature/example-cleanup-v18"],
        last_test_date="2000-05-20",
        backtest_days="3",
        until="",
    )

    run_module.StartCommand().execute(args)

    payload = json.loads(capsys.readouterr().out)
    assert payload["state"] == "prepared"
    assert payload["slot_name"] == "example-slot"
    assert payload["refs"]["control"]["git_ref"] == "18.5.11"
    assert payload["control_resolution"] == {
        "mode": "prod_default_of_slot",
        "source": "experiment_management",
        "slot_name": "example-slot",
        "algorithm": "example-ranking-algorithm",
        "algorithm_version": "18.5.11",
        "variant": "2115",
        "default_created_at": "2000-05-18T08:16:16Z",
        "control_git_ref": "18.5.11",
    }
    assert payload["execution_context"]["algorithm_name"] == "example-ranking-algorithm"
    assert payload["offline_context"]["backtest_days"] == 3
    assert payload["offline_context"]["backtest_date_window"] == {
        "start_date": "2000-05-18",
        "end_date": "2000-05-20",
    }
    resolution = payload["offline_context"]["prod_default_parameter_resolution"]
    assert resolution["parameter_policy"] == "last_registered_on_date_else_latest_earlier"
    assert list(resolution["dates"]) == ["2000-05-18", "2000-05-19", "2000-05-20"]
    assert {dt: record["algorithm_parameter_id"] for dt, record in resolution["dates"].items()} == {
        "2000-05-18": "example-parameter-1",
        "2000-05-19": "example-parameter-1",
        "2000-05-20": "example-parameter-1",
    }
    assert payload["prod_default_parameters_by_date"] == {
        "2000-05-18": {
            "algorithm_name": "example-ranking-algorithm",
            "algorithm_version": "18.5.11",
            "algorithm_parameter_id": "example-parameter-1",
            "source": "s3://parameters/example-parameter-1.zip",
            "created_at": "2000-05-17T23:00:00Z",
        },
        "2000-05-19": {
            "algorithm_name": "example-ranking-algorithm",
            "algorithm_version": "18.5.11",
            "algorithm_parameter_id": "example-parameter-1",
            "source": "s3://parameters/example-parameter-1.zip",
            "created_at": "2000-05-17T23:00:00Z",
        },
        "2000-05-20": {
            "algorithm_name": "example-ranking-algorithm",
            "algorithm_version": "18.5.11",
            "algorithm_parameter_id": "example-parameter-1",
            "source": "s3://parameters/example-parameter-1.zip",
            "created_at": "2000-05-17T23:00:00Z",
        },
    }


def test_prod_default_parameter_resolution_preserves_window_across_mid_day_rotation(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class FakeExperimentManagementClient:
        def get_algorithm_parameters(self):
            return [
                _catalog_parameter("example-before-window", datetime(2000, 7, 19, 23, tzinfo=timezone.utc)),
                _catalog_parameter("example-rotated-mid-window", datetime(2000, 7, 22, 3, tzinfo=timezone.utc)),
            ]

    monkeypatch.setattr(
        run_module,
        "create_client_from_hotvect_config",
        lambda: FakeExperimentManagementClient(),
    )
    offline_context = {
        "last_test_date": "2000-07-26",
        "backtest_days": 7,
        "backtest_date_window": {"start_date": "2000-07-20", "end_date": "2000-07-26"},
    }

    resolved_context, parameters_by_date = run_module._resolve_prod_default_date_matched_parameters(
        offline_context=offline_context,
        control_resolution={
            "slot_name": "example-slot",
            "algorithm": "catalog",
            "algorithm_version": "1.0.0",
        },
    )

    assert resolved_context["last_test_date"] == "2000-07-26"
    assert resolved_context["backtest_days"] == 7
    assert resolved_context["backtest_date_window"] == {
        "start_date": "2000-07-20",
        "end_date": "2000-07-26",
    }
    assert list(parameters_by_date) == [
        "2000-07-20",
        "2000-07-21",
        "2000-07-22",
        "2000-07-23",
        "2000-07-24",
        "2000-07-25",
        "2000-07-26",
    ]
    assert [parameter["algorithm_parameter_id"] for parameter in parameters_by_date.values()] == [
        "example-before-window",
        "example-before-window",
        "example-rotated-mid-window",
        "example-rotated-mid-window",
        "example-rotated-mid-window",
        "example-rotated-mid-window",
        "example-rotated-mid-window",
    ]
    july_22_resolution = resolved_context["prod_default_parameter_resolution"]["dates"]["2000-07-22"]
    assert july_22_resolution["algorithm_parameter_id"] == "example-rotated-mid-window"


def test_prod_default_parameter_resolution_uses_each_daily_mid_day_rotation(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class FakeExperimentManagementClient:
        def get_algorithm_parameters(self):
            return [
                _catalog_parameter(
                    f"example-{dt.isoformat()}",
                    datetime.combine(dt, datetime.min.time(), tzinfo=timezone.utc) + timedelta(hours=5),
                )
                for dt in (date(2000, 7, 20), date(2000, 7, 21), date(2000, 7, 22))
            ]

    monkeypatch.setattr(
        run_module,
        "create_client_from_hotvect_config",
        lambda: FakeExperimentManagementClient(),
    )

    resolved_context, parameters_by_date = run_module._resolve_prod_default_date_matched_parameters(
        offline_context={
            "last_test_date": "2000-07-22",
            "backtest_days": 3,
            "backtest_date_window": {"start_date": "2000-07-20", "end_date": "2000-07-22"},
        },
        control_resolution={
            "slot_name": "example-slot",
            "algorithm": "catalog",
            "algorithm_version": "1.0.0",
        },
    )

    assert [parameter["algorithm_parameter_id"] for parameter in parameters_by_date.values()] == [
        "example-2000-07-20",
        "example-2000-07-21",
        "example-2000-07-22",
    ]
    assert list(resolved_context["prod_default_parameter_resolution"]["dates"]) == [
        "2000-07-20",
        "2000-07-21",
        "2000-07-22",
    ]


def test_prod_default_parameter_resolution_fails_whole_window_on_unresolved_date(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class FakeExperimentManagementClient:
        def get_algorithm_parameters(self):
            return [_catalog_parameter("example-2000-07-21", datetime(2000, 7, 21, 5, tzinfo=timezone.utc))]

    monkeypatch.setattr(
        run_module,
        "create_client_from_hotvect_config",
        lambda: FakeExperimentManagementClient(),
    )

    with pytest.raises(ValueError, match="example-slot.*2000-07-20"):
        run_module._resolve_prod_default_date_matched_parameters(
            offline_context={
                "last_test_date": "2000-07-22",
                "backtest_days": 3,
                "backtest_date_window": {"start_date": "2000-07-20", "end_date": "2000-07-22"},
            },
            control_resolution={
                "slot_name": "example-slot",
                "algorithm": "catalog",
                "algorithm_version": "1.0.0",
            },
        )


def test_prod_default_parameter_resolution_uses_the_last_sub_daily_registration(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class FakeExperimentManagementClient:
        def get_algorithm_parameters(self):
            return [
                _catalog_parameter("example-morning", datetime(2000, 7, 20, 5, tzinfo=timezone.utc)),
                _catalog_parameter("example-evening", datetime(2000, 7, 20, 17, tzinfo=timezone.utc)),
            ]

    monkeypatch.setattr(
        run_module,
        "create_client_from_hotvect_config",
        lambda: FakeExperimentManagementClient(),
    )

    resolved_context, parameters_by_date = run_module._resolve_prod_default_date_matched_parameters(
        offline_context={
            "last_test_date": "2000-07-20",
            "backtest_days": 1,
            "backtest_date_window": {"start_date": "2000-07-20", "end_date": "2000-07-20"},
        },
        control_resolution={
            "slot_name": "example-slot",
            "algorithm": "catalog",
            "algorithm_version": "1.0.0",
        },
    )

    assert parameters_by_date["2000-07-20"]["algorithm_parameter_id"] == "example-evening"
    resolution = resolved_context["prod_default_parameter_resolution"]["dates"]["2000-07-20"]
    assert resolution["algorithm_parameter_id"] == "example-evening"


@pytest.mark.parametrize("evaluation_criteria", ["noninferiority", "superiority", "exact"])
def test_prod_default_control_backtest_uses_the_matching_production_parameter_for_each_date(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
    evaluation_criteria: str,
) -> None:
    _patch_config_defaults(monkeypatch)
    _patch_builds(monkeypatch)
    _patch_algorithm_definition_derivation(monkeypatch)
    commands: list[list[str]] = []

    def fake_run_hv(*, args: list[str], log_path: Path, cwd: Path | None = None):
        del cwd
        assert args[0] == "backtest"
        commands.append(list(args))
        log_path.parent.mkdir(parents=True, exist_ok=True)
        log_path.write_text("ok\n", encoding="utf-8")
        git_ref = args[args.index("--git-reference") + 1]
        output_base_dir = Path(args[args.index("--output-base-dir") + 1])
        last_test_date = date.fromisoformat(args[args.index("--last-test-time") + 1])
        number_of_runs = int(args[args.index("--number-of-runs") + 1])
        version = "86.3.4" if git_ref == "86.3.4" else "86.3.5"
        for offset in range(number_of_runs):
            dt = (last_test_date - timedelta(days=offset)).isoformat()
            result_path = (
                output_base_dir
                / "meta"
                / f"example-control-algorithm@{version}"
                / f"last_test_date_{dt}"
                / "result.json"
            )
            result_path.parent.mkdir(parents=True, exist_ok=True)
            result_path.write_text(
                json.dumps(
                    {
                        "algorithm_id": f"example-control-algorithm@{version}",
                        "test_data_time": dt,
                        "evaluate": {
                            "ndcg_at_50": 0.50 if version == "86.3.4" else 0.51,
                            "map_at_50": 0.40,
                            "roc_auc": {"mean": 0.70},
                        },
                        "performance_test": {
                            "max_memory_usage": 1.0,
                            "mean_throughput": 100.0,
                            "response_time_metrics": {"p99": {"mean": 10.0 if version == "86.3.4" else 9.0}},
                        },
                    }
                ),
                encoding="utf-8",
            )
        return {"command": args, "log_path": str(log_path), "tail": []}

    class FakeExperimentManagementClient:
        def get_default_variant_and_active_experiments(self, slot_name: str):
            assert slot_name == "example-slot"
            return {
                "default_variant": {
                    "variant_id": 2115,
                    "created_at": "2000-04-12T00:00:00Z",
                    "algorithm": {
                        "algorithm_name": "example-control-algorithm",
                        "algorithm_version": "86.3.4",
                    },
                }
            }

        def get_algorithm_parameters(self):
            return [
                SimpleNamespace(
                    algorithm_parameter_id="prod-2000-04-13",
                    algorithm=SimpleNamespace(
                        algorithm_name="example-control-algorithm",
                        algorithm_version="86.3.4",
                    ),
                    created_at=datetime(2000, 4, 13, tzinfo=timezone.utc),
                    absolute_s3_path="s3://parameters/prod-2000-04-13.zip",
                ),
                SimpleNamespace(
                    algorithm_parameter_id="prod-2000-04-14",
                    algorithm=SimpleNamespace(
                        algorithm_name="example-control-algorithm",
                        algorithm_version="86.3.4",
                    ),
                    created_at=datetime(2000, 4, 14, tzinfo=timezone.utc),
                    absolute_s3_path="s3://parameters/prod-2000-04-14.zip",
                ),
            ]

    monkeypatch.setattr(run_module, "_run_hv", fake_run_hv)
    monkeypatch.setattr(
        run_module,
        "create_client_from_hotvect_config",
        lambda: FakeExperimentManagementClient(),
    )
    args = _run_args(
        tmp_path / "meta",
        control="",
        prod_default_of_slot_as_control="example-slot",
        treatments=["feature/example-cleanup-v86"],
        last_test_date="2000-04-14",
        backtest_days="2",
        data_base_dir=str(tmp_path / "data"),
        evaluation_criteria=evaluation_criteria,
        until="",
        only="multi_day_backtest",
    )

    run_module.StartCommand().execute(args)

    payload = json.loads(capsys.readouterr().out)
    assert payload["state"] == "completed"
    assert payload["stage_status"]["multi_day_backtest"] == "passed"
    assert [
        command[command.index("--last-test-time") + 1]
        for command in commands
        if command[command.index("--git-reference") + 1] == "86.3.4"
    ] == ["2000-04-13", "2000-04-14"]
    control_commands = [command for command in commands if command[command.index("--git-reference") + 1] == "86.3.4"]
    assert [
        json.loads(Path(command[command.index("--algorithm-override") + 1]).read_text(encoding="utf-8"))[
            "hotvect_execution_parameters"
        ]["with_parameter"]
        for command in control_commands
    ] == [
        "s3://parameters/prod-2000-04-13.zip",
        "s3://parameters/prod-2000-04-14.zip",
    ]
    assert len([command for command in commands if "feature/example-cleanup-v86" in command]) == 1
    stage_payload = json.loads(
        Path(payload["stage_results"]["multi_day_backtest"]["payload_path"]).read_text(encoding="utf-8")
    )
    assert stage_payload["context"]["control_parameter_policy"] == "prod_parameters_by_date"
    assert stage_payload["control"]["prod_parameters_by_date"]["2000-04-13"]["algorithm_parameter_id"] == (
        "prod-2000-04-13"
    )
    assert stage_payload["treatments"][0]["status"]["performance_spec_compatible"] is True


def test_dated_test_data_s3_uri_supports_test_data_prefix(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        run_module.hv_config,
        "load_sagemaker_defaults",
        lambda: {"default_s3_data_base_dir": "s3://example-bucket/tables"},
    )

    uri = run_module._dated_test_data_s3_uri_from_algorithm_definition(
        {"test_data_prefix": "example-training-data"},
        last_test_date="2000-04-27",
    )

    assert uri == "s3://example-bucket/tables/example-training-data/dt=2000-04-27/"


def test_discover_latest_contiguous_test_data_date_requires_complete_window(monkeypatch: pytest.MonkeyPatch) -> None:
    class FakePaginator:
        def paginate(self, **kwargs):
            assert kwargs == {
                "Bucket": "example-bucket",
                "Prefix": "datasets/testing/",
                "Delimiter": "/",
            }
            return [
                {
                    "CommonPrefixes": [
                        {"Prefix": "datasets/testing/dt=2000-04-11/"},
                        {"Prefix": "datasets/testing/dt=2000-04-12/"},
                        {"Prefix": "datasets/testing/dt=2000-04-13/"},
                        {"Prefix": "datasets/testing/dt=2000-04-14/"},
                        {"Prefix": "datasets/testing/dt=2000-04-15/"},
                        {"Prefix": "datasets/testing/dt=2000-04-16/"},
                        {"Prefix": "datasets/testing/dt=2000-04-17/"},
                        {"Prefix": "datasets/testing/dt=2000-04-18/"},
                        {"Prefix": "datasets/testing/dt=2000-04-20/"},
                    ]
                }
            ]

    class FakeS3Client:
        def get_paginator(self, name: str):
            assert name == "list_objects_v2"
            return FakePaginator()

    monkeypatch.setattr(run_module.boto3, "client", lambda service_name: FakeS3Client())

    with pytest.raises(ValueError, match="newer test data exists .2000-04-20.*Missing dates: 2000-04-19"):
        run_module._discover_latest_contiguous_test_data_date(
            data_base_uri="s3://example-bucket/datasets/testing/", days=7
        )
    with pytest.raises(ValueError, match="no contiguous 9-day"):
        run_module._discover_latest_contiguous_test_data_date(
            data_base_uri="s3://example-bucket/datasets/testing/", days=9
        )


def test_discover_latest_contiguous_test_data_date_returns_newest_window_without_gaps(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class FakePaginator:
        def paginate(self, **kwargs):
            return [
                {
                    "CommonPrefixes": [
                        {"Prefix": f"datasets/testing/dt=2000-04-{day:02d}/"}
                        for day in range(11, 19)  # 2000-04-11 .. 2000-04-18, no gaps
                    ]
                }
            ]

    class FakeS3Client:
        def get_paginator(self, name: str):
            return FakePaginator()

    monkeypatch.setattr(run_module.boto3, "client", lambda service_name: FakeS3Client())

    assert (
        run_module._discover_latest_contiguous_test_data_date(
            data_base_uri="s3://example-bucket/datasets/testing/", days=7
        )
        == "2000-04-18"
    )


def test_resolve_checkout_git_ref_allows_unique_remote_tracking_branch(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        run_module,
        "_git_ref_exists",
        lambda repo_path, git_ref: git_ref == "origin/feature/example-cache-v86.3.4",
    )
    monkeypatch.setattr(
        run_module,
        "_list_known_git_refs",
        lambda repo_path: [
            "origin/main",
            "origin/feature/example-cache-v86.3.4",
        ],
    )

    resolved = run_module._resolve_checkout_git_ref(Path("/tmp/fake-repo"), "feature/example-cache-v86.3.4")

    assert resolved == "origin/feature/example-cache-v86.3.4"


def test_resolve_checkout_git_ref_allows_semver_alternate_tag(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        run_module,
        "_git_ref_exists",
        lambda repo_path, git_ref: git_ref == "v86.3.4",
    )

    resolved = run_module._resolve_checkout_git_ref(Path("/tmp/fake-repo"), "86.3.4")

    assert resolved == "v86.3.4"


def test_resolve_checkout_git_ref_requires_explicit_ref(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(run_module, "_git_ref_exists", lambda repo_path, git_ref: False)
    monkeypatch.setattr(
        run_module,
        "_list_known_git_refs",
        lambda repo_path: [
            "origin/main",
            "origin/feature/example-cache-v86.3.4",
        ],
    )

    with pytest.raises(ValueError, match="Git ref 'v86.3.4' was not found"):
        run_module._resolve_checkout_git_ref(Path("/tmp/fake-repo"), "v86.3.4")


def test_qa_run_derives_parameter_source_for_encode_parity(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _patch_config_defaults(monkeypatch)
    _patch_builds(monkeypatch)
    _patch_algorithm_definition_derivation(monkeypatch)
    _patch_hv_runner(monkeypatch)
    parameter_path = _write_fake_parameter_zip(tmp_path / "predict.parameters.zip")
    source_path = tmp_path / "source.jsonl"
    source_path.write_text('{"example_id":"ex-1"}\n', encoding="utf-8")

    class FakeExperimentManagementClient:
        def get_latest_algorithm_parameter(self, algorithm_name: str, algorithm_version: str):
            assert algorithm_name == "example-control-algorithm"
            assert algorithm_version == "86.3.4"
            return SimpleNamespace(
                algorithm_parameter_id="latest-example-parameter",
                absolute_s3_path=str(parameter_path),
            )

    monkeypatch.setattr(
        run_module,
        "create_client_from_hotvect_config",
        lambda: FakeExperimentManagementClient(),
    )
    args = _run_args(
        tmp_path / "meta",
        until="encode_parity",
        source_path=str(source_path),
    )

    run_module.StartCommand().execute(args)

    payload = json.loads(capsys.readouterr().out)
    assert payload["parameter_source"] == str(parameter_path)
    assert payload["stage_status"]["encode_parity"] == "passed"
    assert payload["stage_results"]["encode_parity"]["stage_judgment"] == "pass"


def test_qa_run_allows_encode_specific_execution_target(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _patch_config_defaults(monkeypatch)
    _patch_builds(monkeypatch)
    _patch_algorithm_definition_derivation(monkeypatch)
    _patch_hv_runner(monkeypatch)
    parameter_path = _write_fake_parameter_zip(tmp_path / "predict.parameters.zip")

    source_path = tmp_path / "source.jsonl"
    source_path.write_text('{"example_id":"ex-1"}\n', encoding="utf-8")

    args = _run_args(
        tmp_path / "meta",
        parameter_source=str(parameter_path),
        algorithm_name="example-control-algorithm",
        encode_algorithm_name="example-control-model",
        source_path=str(source_path),
        until="encode_parity",
    )
    run_module.StartCommand().execute(args)

    payload = json.loads(capsys.readouterr().out)
    assert payload["execution_context"]["algorithm_name"] == "example-control-algorithm"
    assert (
        payload["execution_context"]["encode_algorithm_name"] == "example-control-model"
    )
    assert payload["stage_status"]["encode_parity"] == "passed"


def test_qa_run_allows_per_ref_encode_algorithm_names(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _patch_config_defaults(monkeypatch)
    _patch_builds(monkeypatch)
    _patch_algorithm_definition_derivation(monkeypatch)
    parameter_path = _write_fake_parameter_zip(tmp_path / "predict.parameters.zip")
    source_path = tmp_path / "source.jsonl"
    source_path.write_text('{"example_id":"ex-1"}\n', encoding="utf-8")
    commands: list[list[str]] = []

    def fake_run_hv(*, args: list[str], log_path: Path, cwd: Path | None = None):
        del cwd
        commands.append(list(args))
        log_path.parent.mkdir(parents=True, exist_ok=True)
        log_path.write_text("ok\n", encoding="utf-8")
        assert args[0] == "encode"
        dest_path = Path(args[args.index("--dest-path") + 1])
        dest_path.parent.mkdir(parents=True, exist_ok=True)
        dest_path.write_text("1\tfeature_a\tfeature_b\n", encoding="utf-8")
        schema_path = Path(args[args.index("--dest-schema-path") + 1])
        schema_path.parent.mkdir(parents=True, exist_ok=True)
        schema_path.write_text(json.dumps({"columns": ["label", "feature_a", "feature_b"]}) + "\n", encoding="utf-8")
        metadata_dir = Path(args[args.index("--metadata-path") + 1])
        metadata_dir.mkdir(parents=True, exist_ok=True)
        (metadata_dir / "metadata.json").write_text(json.dumps({"ok": True}) + "\n", encoding="utf-8")
        return {"command": args, "log_path": str(log_path), "tail": []}

    monkeypatch.setattr(run_module, "_run_hv", fake_run_hv)

    args = _run_args(
        tmp_path / "meta",
        parameter_source=str(parameter_path),
        source_path=str(source_path),
        control_encode_algorithm_name="example-control-model",
        treatment_encode_algorithm_names=["example-treatment-model"],
        until="encode_parity",
    )
    run_module.StartCommand().execute(args)

    payload = json.loads(capsys.readouterr().out)
    assert payload["stage_status"]["encode_parity"] == "passed"
    assert payload["encode_algorithm_names"] == {
        "mode": "per_ref",
        "control": {
            "git_ref": "v86.3.4",
            "algorithm_name": "example-control-model",
        },
        "treatments": [
            {
                "git_ref": "feature/example-cleanup-v86",
                "algorithm_name": "example-treatment-model",
            }
        ],
    }
    assert [command[command.index("--algorithm-name") + 1] for command in commands] == [
        "example-control-model",
        "example-treatment-model",
    ]
    assert all("--unordered" in command for command in commands)
    treatment = payload["stage_results"]["encode_parity"]["treatments"][0]
    assert treatment["encode_algorithm_name"] == "example-treatment-model"


@pytest.mark.parametrize(
    ("definition", "expected"),
    [
        ({}, "unordered"),
        ({"train_decoder_parameters": {"ordering": "unordered"}}, "unordered"),
        ({"train_decoder_parameters": {"ordering": "ordered"}}, "ordered"),
    ],
)
def test_encode_default_output_ordering_matches_algorithm_definition(definition: dict, expected: str) -> None:
    assert run_module._encode_default_output_ordering(definition) == expected


def test_unordered_text_artifact_comparison_accepts_reordered_shards(tmp_path: Path) -> None:
    control_dir = tmp_path / "control"
    treatment_dir = tmp_path / "treatment"
    control_dir.mkdir()
    treatment_dir.mkdir()
    (control_dir / "part-00000").write_text("a\t1\nb\t2\n", encoding="utf-8")
    (control_dir / "part-00001").write_text("c\t3\n", encoding="utf-8")
    (treatment_dir / "part-00000").write_text("c\t3\na\t1\n", encoding="utf-8")
    (treatment_dir / "part-00001").write_text("b\t2\n", encoding="utf-8")

    comparison = run_module._compare_text_artifact_lines_unordered(control_dir, treatment_dir)

    assert comparison["identical"] is True
    assert comparison["control_line_count"] == 3
    assert comparison["treatment_line_count"] == 3
    assert comparison["mode"] == "line_multiset"


def test_compare_audit_jsonl_artifacts_accepts_sharded_directories(tmp_path: Path) -> None:
    control_dir = tmp_path / "control"
    treatment_dir = tmp_path / "treatment"
    control_dir.mkdir()
    treatment_dir.mkdir()
    (control_dir / "part-00000.jsonl").write_text(
        json.dumps({"example_id": "ex-1", "features": {"a": 1, "b": 2}}, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    (control_dir / "part-00001.jsonl").write_text(
        json.dumps({"example_id": "ex-2", "features": {"a": 3, "b": 4}}, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    (treatment_dir / "part-00000.jsonl").write_text(
        "\n".join(
            [
                json.dumps({"features": {"b": 4, "a": 3}, "example_id": "ex-2"}),
                json.dumps({"features": {"b": 2, "a": 1}, "example_id": "ex-1"}),
                "",
            ]
        ),
        encoding="utf-8",
    )

    comparison = run_module._compare_audit_jsonl_artifacts(control_dir, treatment_dir, tmp_path / "out")

    assert comparison["identical"] is True
    assert comparison["mode"] == "jsonl_record_multiset"
    assert comparison["control_record_count"] == 2
    assert comparison["treatment_record_count"] == 2


def test_encoded_tsv_comparison_recovers_legacy_joined_record_boundaries(tmp_path: Path) -> None:
    schema_path = tmp_path / "schema.txt"
    schema_path.write_text("0\tLabel\n1\tText\tname\n2\tNum\tvalue\n", encoding="utf-8")
    control_dir = tmp_path / "control"
    treatment_dir = tmp_path / "treatment"
    control_dir.mkdir()
    treatment_dir.mkdir()
    (control_dir / "part-00000").write_bytes(b"0\ta\t1001\tb\t20\n")
    (treatment_dir / "part-00000").write_bytes(b"1\tb\t20\n0\ta\t100\n")

    comparison = run_module._compare_encoded_tsv_artifacts_unordered(
        control_dir,
        treatment_dir,
        schema_path,
    )

    assert comparison["identical"] is True
    assert comparison["mode"] == "encoded_tsv_record_multiset"
    assert comparison["control_record_count"] == 2
    assert comparison["treatment_record_count"] == 2
    assert comparison["control_repaired_joined_record_boundaries"] == 1


def test_qa_run_backtest_stage_waits_for_remote_sagemaker_results(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _patch_config_defaults(monkeypatch)
    _patch_builds(monkeypatch)
    _patch_algorithm_definition_derivation(monkeypatch)

    hv_commands: list[list[str]] = []

    def fake_run_hv(*, args: list[str], log_path: Path, cwd: Path | None = None):
        del cwd
        hv_commands.append(list(args))
        log_path.parent.mkdir(parents=True, exist_ok=True)
        log_path.write_text("submitted\n", encoding="utf-8")
        assert args[0] == "backtest"
        output_dir = Path(args[args.index("--output-base-dir") + 1])
        git_refs = [args[index + 1] for index, value in enumerate(args) if value == "--git-reference"]
        run_dir = output_dir / "meta" / "_backtest_submissions" / "20000511T000000.000000Z"
        run_dir.mkdir(parents=True, exist_ok=True)
        jobs = []
        for git_ref in git_refs:
            job_name = "bt-control" if git_ref == "v86.3.4" else "bt-treatment"
            jobs.append(
                {
                    "algo_git_reference": git_ref,
                    "parameter_version": "last_test_date_2000-04-14",
                    "test_data_time": "2000-04-14",
                    "training_job_name": job_name,
                    "submission_status": "submitted",
                    "s3_uri_result_file": f"s3://fake-bucket/results/{job_name}/result.json",
                    "s3_uri_metadata": f"s3://fake-bucket/results/{job_name}/metadata",
                }
            )
        (run_dir / "backtest_submission_manifest.json").write_text(
            json.dumps({"generated_at": "2000-05-11T00:00:00+00:00", "job_count": len(jobs), "jobs": jobs}),
            encoding="utf-8",
        )
        return {"command": args, "log_path": str(log_path), "tail": ["SageMaker jobs submitted successfully!"]}

    class FakeS3Client:
        def download_file(self, Bucket: str, Key: str, Filename: str):
            del Bucket
            target = Path(Filename)
            target.parent.mkdir(parents=True, exist_ok=True)
            is_control = "bt-control" in Key
            version = "86.3.4" if is_control else "86.3.5"
            payload = {
                "algorithm_id": f"example-control-algorithm@{version}",
                "test_data_time": "2000-04-14",
                "evaluate": {
                    "ndcg_at_50": 0.50 if is_control else 0.51,
                    "map_at_50": 0.40,
                    "roc_auc": {"mean": 0.70},
                },
                "performance_test": {
                    "max_memory_usage": 1.0,
                    "mean_throughput": 100.0,
                    "response_time_metrics": {"p99": {"mean": 10.0 if is_control else 10.5}},
                },
            }
            target.write_text(json.dumps(payload), encoding="utf-8")

    class FakeSageMakerClient:
        def describe_training_job(self, TrainingJobName: str):
            return {
                "TrainingJobStatus": "Completed",
                "SecondaryStatus": "Completed",
                "HyperParameters": {
                    "s3_uri_result_file": f"s3://fake-bucket/results/{TrainingJobName}/result.json",
                    "s3_uri_metadata": f"s3://fake-bucket/results/{TrainingJobName}/metadata",
                },
            }

    class FakeSession:
        def client(self, service_name: str):
            if service_name == "s3":
                return FakeS3Client()
            if service_name == "sagemaker":
                return FakeSageMakerClient()
            raise AssertionError(f"Unexpected boto client request: {service_name}")

    monkeypatch.setattr(run_module, "_run_hv", fake_run_hv)
    monkeypatch.setattr(run_module, "_qa_run_boto_session", lambda assume_role_arn: FakeSession())

    sagemaker_config = tmp_path / "sagemaker.json"
    sagemaker_config.write_text(
        json.dumps({"OutputDataConfig": {"S3OutputPath": "s3://example-bucket/test-output"}}),
        encoding="utf-8",
    )

    args = _run_args(
        tmp_path / "meta",
        data_base_dir="",
        only="realistic_single_day",
        until="",
        backtest_runner="sagemaker",
        backtest_sagemaker_job_prefix="ml-exp",
        backtest_sagemaker_config=str(sagemaker_config),
        backtest_auto_attach_data_default_s3_base="s3://example-bucket/tables",
        backtest_poll_seconds=1,
    )
    run_module.StartCommand().execute(args)

    payload = json.loads(capsys.readouterr().out)
    assert payload["state"] == "completed"
    assert payload["stage_status"]["realistic_single_day"] == "passed"
    stage_payload = json.loads(
        Path(payload["stage_results"]["realistic_single_day"]["payload_path"]).read_text(encoding="utf-8")
    )
    assert stage_payload["context"]["backtest_runner"] == "sagemaker"
    assert stage_payload["control"]["remote_backtest_jobs"][0]["training_job_name"] == "bt-control"
    assert stage_payload["treatments"][0]["artifacts"]["remote_backtest_jobs"][0]["training_job_name"] == "bt-treatment"
    assert len(hv_commands) == 1
    command = hv_commands[0]
    assert "--sagemaker" in command
    assert command[command.index("--sagemaker-job-prefix") + 1] == "ml-exp"
    assert command[command.index("--sagemaker-config") + 1] == str(sagemaker_config)
    assert command[command.index("--auto-attach-data-default-s3-base") + 1] == "s3://example-bucket/tables"
    assert "--data-base-dir" not in command


def test_qa_run_uses_each_ref_repository_for_same_named_cross_repository_backtests(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _patch_config_defaults(monkeypatch)
    build_calls: list[tuple[str, str]] = []
    backtest_commands: list[list[str]] = []
    control_repo = str((tmp_path / "example-ranking-algorithm").resolve())
    treatment_repo = str((tmp_path / "example-treatment-algorithm").resolve())

    def fake_build_ref_artifact(*, run_dir: Path, algo_repo_url: str, git_ref: str):
        build_calls.append((algo_repo_url, git_ref))
        is_control = algo_repo_url == control_repo
        artifact_name = "example-ranking-algorithm" if is_control else "example-treatment-algorithm"
        artifact_version = "18.6.10" if is_control else "2.1.0"
        jar_path = run_dir / "fake-artifacts" / f"{artifact_name}-{artifact_version}.jar"
        jar_path.parent.mkdir(parents=True, exist_ok=True)
        jar_path.write_text(f"{artifact_name}:{artifact_version}", encoding="utf-8")
        return run_module._BuiltRefArtifact(
            git_ref=git_ref,
            resolved_git_ref=git_ref,
            artifact_name=artifact_name,
            artifact_version=artifact_version,
            git_commit=f"{artifact_name}-commit",
            jar_path=jar_path,
            build_dir=jar_path.parent,
            algo_repo_url=algo_repo_url,
        )

    def fake_read_algorithm_definition_from_jar(*, algorithm_name: str, algorithm_jar_path: Path, additional_jars=None):
        del algorithm_jar_path, additional_jars
        return {
            "algorithm_name": algorithm_name,
            "hotvect_execution_parameters": {
                "performance-test": {
                    "samples": 722,
                    "sample_pool_size": 64,
                    "target_rps": 10.0,
                    "workload_mode": "realtime",
                }
            },
        }

    def fake_run_backtest_hv_command(*, args: list[str], **_kwargs):
        backtest_commands.append(args)
        return {"runner": "local"}

    monkeypatch.setattr(run_module, "_build_ref_artifact", fake_build_ref_artifact)
    monkeypatch.setattr(run_module, "read_algorithm_definition_from_jar", fake_read_algorithm_definition_from_jar)
    monkeypatch.setattr(run_module, "_run_backtest_hv_command", fake_run_backtest_hv_command)
    monkeypatch.setattr(
        run_module,
        "_compare_backtest_outputs",
        lambda **_kwargs: {
            "dates_used": ["2000-04-14"],
            "quality_metrics": {},
            "quality_statistical_basis": {
                "pairing_unit": "test_date",
                "metric_estimate_field": "value",
                "source_confidence_intervals": "not_used_without_paired_covariance",
            },
            "system_metrics": {},
            "offline_quality": None,
            "performance": None,
        },
    )

    args = _run_args(
        tmp_path / "meta",
        control="main",
        treatments=["main"],
        algo_repo_url="",
        control_algo_repo_url=control_repo,
        treatment_algo_repo_urls=[treatment_repo],
        data_base_dir="/tmp/data",
        until="",
        only="realistic_single_day",
    )
    run_module.StartCommand().execute(args)

    payload = json.loads(capsys.readouterr().out)
    assert payload["state"] == "completed"
    assert payload["refs"]["control"]["algo_repo_url"] == control_repo
    assert payload["refs"]["treatments"][0]["algo_repo_url"] == treatment_repo
    assert build_calls == [
        (control_repo, "main"),
        (treatment_repo, "main"),
    ]
    assert len(backtest_commands) == 2
    assert {
        (
            command[command.index("--algo-repo-url") + 1],
            command[command.index("--git-reference") + 1],
        )
        for command in backtest_commands
    } == {
        (control_repo, "main"),
        (treatment_repo, "main"),
    }
    output_dirs = {command[command.index("--output-base-dir") + 1] for command in backtest_commands}
    assert len(output_dirs) == 2
    assert any("control-main" in output_dir for output_dir in output_dirs)
    assert any("treatment-main" in output_dir for output_dir in output_dirs)


def test_remote_backtest_resume_reuses_existing_submission_manifest(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    def fail_run_hv(*, args: list[str], log_path: Path, cwd: Path | None = None):
        raise AssertionError("resume must not submit a new hv backtest command")

    class FakeS3Client:
        def download_file(self, Bucket: str, Key: str, Filename: str):
            del Bucket
            target = Path(Filename)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(
                json.dumps(
                    {
                        "algorithm_id": "example-control-algorithm@86.3.5",
                        "test_data_time": "2000-04-14",
                        "evaluate": {"ndcg_at_50": 0.51},
                    }
                ),
                encoding="utf-8",
            )

    class FakeSageMakerClient:
        def describe_training_job(self, TrainingJobName: str):
            return {
                "TrainingJobStatus": "Completed",
                "SecondaryStatus": "Completed",
                "HyperParameters": {
                    "s3_uri_result_file": f"s3://fake-bucket/results/{TrainingJobName}/result.json",
                    "s3_uri_metadata": f"s3://fake-bucket/results/{TrainingJobName}/metadata",
                },
            }

    class FakeSession:
        def client(self, service_name: str):
            if service_name == "s3":
                return FakeS3Client()
            if service_name == "sagemaker":
                return FakeSageMakerClient()
            raise AssertionError(f"Unexpected boto client request: {service_name}")

    monkeypatch.setattr(run_module, "_run_hv", fail_run_hv)
    monkeypatch.setattr(run_module, "_qa_run_boto_session", lambda assume_role_arn: FakeSession())

    output_dir = tmp_path / "output"
    submission_dir = output_dir / "meta" / "_backtest_submissions" / "20000511T000000.000000Z"
    submission_dir.mkdir(parents=True)
    (submission_dir / "backtest_submission_manifest.json").write_text(
        json.dumps(
            {
                "generated_at": "2000-05-11T00:00:00+00:00",
                "job_count": 1,
                "jobs": [
                    {
                        "algo_git_reference": "feature/example-cleanup-v86",
                        "parameter_version": "last_test_date_2000-04-14",
                        "test_data_time": "2000-04-14",
                        "training_job_name": "bt-treatment",
                        "submission_status": "submitted",
                        "s3_uri_result_file": "s3://fake-bucket/results/bt-treatment/result.json",
                        "s3_uri_metadata": "s3://fake-bucket/results/bt-treatment/metadata",
                    }
                ],
            }
        ),
        encoding="utf-8",
    )

    result = run_module._run_backtest_hv_command(
        args=["backtest"],
        log_path=tmp_path / "command.log",
        output_base_dir=output_dir,
        options=run_module._BacktestOptions(runner="sagemaker", sagemaker_job_prefix="ml-exp", poll_seconds=1),
        remote=True,
    )

    assert result["reused_existing_submission"] is True
    assert result["jobs"][0]["training_job_name"] == "bt-treatment"
    assert "Reusing existing SageMaker backtest submission manifest" in (tmp_path / "command.log").read_text(
        encoding="utf-8"
    )


def test_backtest_comparison_supports_renamed_algorithm_repo(tmp_path: Path) -> None:
    control_id = "example-ranking-algorithm@9.8.1-example-slot-v98"
    treatment_id = "example-treatment-algorithm@1.0.0-example-treatment-algorithm-v1-scf"
    for base_dir, algorithm_id, ndcg in [
        (tmp_path / "control", control_id, 0.50),
        (tmp_path / "treatment", treatment_id, 0.51),
    ]:
        result_path = base_dir / "meta" / algorithm_id / "last_test_date_2000-05-10" / "result.json"
        result_path.parent.mkdir(parents=True, exist_ok=True)
        result_path.write_text(
            json.dumps(
                {
                    "algorithm_id": algorithm_id,
                    "test_data_time": "2000-05-10",
                    "evaluate": {
                        "ndcg_at_50": ndcg,
                        "map_at_50": 0.40,
                        "roc_auc": {"mean": 0.70},
                    },
                }
            ),
            encoding="utf-8",
        )

    control_artifact = run_module._BuiltRefArtifact(
        git_ref="v9.8.1",
        resolved_git_ref="control",
        artifact_name="example-ranking-algorithm",
        artifact_version="9.8.1",
        git_commit="control",
        jar_path=tmp_path / "control.jar",
        build_dir=tmp_path,
    )
    treatment_artifact = run_module._BuiltRefArtifact(
        git_ref="v1",
        resolved_git_ref="treatment",
        artifact_name="example-treatment-algorithm",
        artifact_version="1.0.0",
        git_commit="treatment",
        jar_path=tmp_path / "treatment.jar",
        build_dir=tmp_path,
    )

    comparison = run_module._compare_backtest_outputs(
        control_output_base_dir=tmp_path / "control",
        treatment_output_base_dir=tmp_path / "treatment",
        algorithm_name="example-ranking-algorithm",
        control_artifact=control_artifact,
        treatment_artifact=treatment_artifact,
        offline_context={
            "backtest_date_window": {
                "start_date": "2000-05-10",
                "end_date": "2000-05-10",
            }
        },
    )

    assert comparison["control_id"] == control_id
    assert comparison["treatment_id"] == treatment_id
    assert comparison["dates_used"] == ["2000-05-10"]
    assert comparison["system_metrics"] == {}


def test_qa_run_rejects_same_resolved_commit(tmp_path: Path) -> None:
    control_jar = tmp_path / "control.jar"
    treatment_jar = tmp_path / "treatment.jar"
    control_jar.write_text("control", encoding="utf-8")
    treatment_jar.write_text("treatment", encoding="utf-8")
    artifacts = [
        run_module._BuiltRefArtifact(
            git_ref="main",
            resolved_git_ref="main",
            artifact_name="ranker",
            artifact_version="1.0.0",
            git_commit="same-commit",
            jar_path=control_jar,
            build_dir=tmp_path,
        ),
        run_module._BuiltRefArtifact(
            git_ref="candidate",
            resolved_git_ref="candidate",
            artifact_name="ranker",
            artifact_version="1.0.1",
            git_commit="same-commit",
            jar_path=treatment_jar,
            build_dir=tmp_path,
        ),
    ]

    with pytest.raises(ValueError, match="both resolve to same-commit"):
        run_module._validate_distinct_built_artifacts(artifacts)


def test_qa_run_rejects_identical_artifacts(tmp_path: Path) -> None:
    control_jar = tmp_path / "control.jar"
    treatment_jar = tmp_path / "treatment.jar"
    control_jar.write_text("identical", encoding="utf-8")
    treatment_jar.write_text("identical", encoding="utf-8")
    artifacts = [
        run_module._BuiltRefArtifact(
            git_ref="v1",
            resolved_git_ref="v1",
            artifact_name="ranker",
            artifact_version="1.0.0",
            git_commit="control-commit",
            jar_path=control_jar,
            build_dir=tmp_path,
        ),
        run_module._BuiltRefArtifact(
            git_ref="v2",
            resolved_git_ref="v2",
            artifact_name="ranker",
            artifact_version="1.0.1",
            git_commit="treatment-commit",
            jar_path=treatment_jar,
            build_dir=tmp_path,
        ),
    ]

    with pytest.raises(ValueError, match="have JAR SHA-256"):
        run_module._validate_distinct_built_artifacts(artifacts)


def test_qa_run_resume_reuses_parameter_source_for_later_parameter_backed_stages(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _patch_config_defaults(monkeypatch)
    _patch_builds(monkeypatch)
    _patch_algorithm_definition_derivation(monkeypatch)
    _patch_hv_runner(monkeypatch)
    local_source = tmp_path / "predict-source.jsonl"
    local_source.write_text('{"example_id":"ex-1"}\n', encoding="utf-8")
    monkeypatch.chdir(tmp_path)
    parameter_path = _write_fake_parameter_zip(tmp_path / "predict.parameters.zip")

    create_args = _run_args(
        tmp_path / "meta",
        parameter_source=str(parameter_path),
        source_path=str(local_source),
        until="encode_parity",
    )
    run_module.StartCommand().execute(create_args)
    created_payload = json.loads(capsys.readouterr().out)
    run_id = created_payload["run_id"]

    resume_args = _run_args(
        tmp_path / "meta",
        resume=run_id,
        control="",
        treatments=[],
        last_test_date="",
        backtest_days="",
        evaluation_criteria="",
        until="predict_parity",
    )
    run_module.StartCommand().execute(resume_args)

    resumed_payload = json.loads(capsys.readouterr().out)
    assert resumed_payload["action"] == "resumed"
    assert resumed_payload["state"] == "completed"
    assert resumed_payload["parameter_source"] == str(parameter_path)
    assert resumed_payload["stage_status"]["encode_parity"] == "passed"
    assert resumed_payload["stage_status"]["system_performance"] == "passed"
    assert resumed_payload["stage_status"]["predict_parity"] == "passed"
    assert resumed_payload["stage_results"]["predict_parity"]["stage_judgment"] == "pass"


def test_qa_run_resume_does_not_reload_configuration(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _patch_config_defaults(monkeypatch)
    _patch_builds(monkeypatch)
    _patch_algorithm_definition_derivation(monkeypatch)
    _patch_hv_runner(monkeypatch)
    parameter_path = _write_fake_parameter_zip(tmp_path / "predict.parameters.zip")

    run_module.StartCommand().execute(
        _run_args(
            tmp_path / "meta",
            parameter_source=str(parameter_path),
            until="encode_parity",
        )
    )
    created_payload = json.loads(capsys.readouterr().out)
    plan_path = Path(created_payload["files"]["plan"])
    created_plan = json.loads(plan_path.read_text(encoding="utf-8"))

    def configuration_must_not_be_read():
        raise AssertionError("resume reloaded mutable configuration")

    monkeypatch.setattr(run_module.hv_config, "load_qa_run_execution_context", configuration_must_not_be_read)
    monkeypatch.setattr(run_module.hv_config, "load_qa_run_system_performance", configuration_must_not_be_read)
    monkeypatch.setattr(run_module.hv_config, "load_qa_run_backtest", configuration_must_not_be_read)
    monkeypatch.setattr(run_module.hv_config, "load_directory_defaults", configuration_must_not_be_read)
    monkeypatch.setattr(run_module.hv_config, "load_sagemaker_defaults", configuration_must_not_be_read)

    run_module.StartCommand().execute(
        _run_args(
            tmp_path / "meta",
            resume=created_payload["run_id"],
            control="",
            treatments=[],
            last_test_date="",
            backtest_days="",
            evaluation_criteria="",
            until="encode_parity",
        )
    )
    capsys.readouterr()
    resumed_plan = json.loads(plan_path.read_text(encoding="utf-8"))

    assert resumed_plan["execution_context"] == created_plan["execution_context"]
    assert resumed_plan["stage_options"] == created_plan["stage_options"]


def test_qa_run_resume_keeps_completed_state_when_no_stages_are_pending(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _patch_config_defaults(monkeypatch)
    _patch_builds(monkeypatch)
    _patch_algorithm_definition_derivation(monkeypatch)
    _patch_hv_runner(monkeypatch)
    parameter_path = _write_fake_parameter_zip(tmp_path / "predict.parameters.zip")

    create_args = _run_args(
        tmp_path / "meta",
        parameter_source=str(parameter_path),
        until="encode_parity",
    )
    run_module.StartCommand().execute(create_args)
    created_payload = json.loads(capsys.readouterr().out)

    resume_args = _run_args(
        tmp_path / "meta",
        resume=created_payload["run_id"],
        control="",
        treatments=[],
        last_test_date="",
        backtest_days="",
        evaluation_criteria="",
        until="encode_parity",
    )
    run_module.StartCommand().execute(resume_args)

    resumed_payload = json.loads(capsys.readouterr().out)
    persisted_status = json.loads(Path(resumed_payload["files"]["status"]).read_text(encoding="utf-8"))
    assert resumed_payload["state"] == "completed"
    assert resumed_payload["stage_status"]["encode_parity"] == "passed"
    assert persisted_status["state"] == "completed"


def test_qa_run_system_performance_runs_paired_trials(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _patch_config_defaults(monkeypatch)
    _patch_builds(monkeypatch)
    _patch_algorithm_definition_derivation(monkeypatch)
    _patch_hv_runner(monkeypatch)
    parameter_path = _write_fake_parameter_zip(tmp_path / "predict.parameters.zip")

    args = _run_args(
        tmp_path / "meta",
        parameter_source=str(parameter_path),
        performance_runner="local",
        performance_trials=3,
        until="",
        only="system_performance",
    )
    run_module.StartCommand().execute(args)

    payload = json.loads(capsys.readouterr().out)
    result = payload["stage_results"]["system_performance"]
    stage_payload = json.loads(Path(result["payload_path"]).read_text(encoding="utf-8"))
    treatment = stage_payload["treatments"][0]
    comparison = json.loads(Path(treatment["artifacts"]["comparison_path"]).read_text(encoding="utf-8"))

    assert stage_payload["context"]["trial_count"] == 3
    assert len(stage_payload["control"]["artifacts"]["metadata_paths"]) == 3
    assert len(treatment["artifacts"]["metadata_paths"]) == 3
    assert comparison["p99"]["trial_count"] == 3
    assert comparison["p99"]["paired_permutation_p_value"] is not None


def test_qa_run_system_performance_requires_committed_spec_for_both_refs(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _patch_config_defaults(monkeypatch)
    _patch_builds(monkeypatch)
    _patch_algorithm_definition_derivation(monkeypatch, missing_control_performance_spec=True)
    _patch_hv_runner(monkeypatch)
    parameter_path = _write_fake_parameter_zip(tmp_path / "predict.parameters.zip")

    with pytest.raises(ValueError, match="Missing committed performance-test spec for v86.3.4"):
        run_module.StartCommand().execute(
            _run_args(
                tmp_path / "meta",
                parameter_source=str(parameter_path),
                performance_runner="local",
                until="",
                only="system_performance",
            )
        )


def test_qa_run_system_performance_refuses_mismatched_committed_specs(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _patch_config_defaults(monkeypatch)
    _patch_builds(monkeypatch)
    _patch_algorithm_definition_derivation(
        monkeypatch,
        treatment_performance_spec={
            "samples": 722,
            "sample_pool_size": 64,
            "target_rps": 11.0,
            "workload_mode": "realtime",
        },
    )
    _patch_hv_runner(monkeypatch)
    parameter_path = _write_fake_parameter_zip(tmp_path / "predict.parameters.zip")

    with pytest.raises(ValueError, match="requires equal committed performance-test specs"):
        run_module.StartCommand().execute(
            _run_args(
                tmp_path / "meta",
                parameter_source=str(parameter_path),
                performance_runner="local",
                until="",
                only="system_performance",
            )
        )


def test_qa_run_system_performance_refuses_conflicting_cli_spec_override(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _patch_config_defaults(monkeypatch)
    _patch_builds(monkeypatch)
    _patch_algorithm_definition_derivation(monkeypatch)
    _patch_hv_runner(monkeypatch)
    parameter_path = _write_fake_parameter_zip(tmp_path / "predict.parameters.zip")

    with pytest.raises(ValueError, match="conflicts with the committed performance-test spec"):
        run_module.StartCommand().execute(
            _run_args(
                tmp_path / "meta",
                parameter_source=str(parameter_path),
                performance_runner="local",
                performance_target_rps=11.0,
                until="",
                only="system_performance",
            )
        )


def test_qa_run_resume_retries_failed_only_stage(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _patch_config_defaults(monkeypatch)
    _patch_builds(monkeypatch)
    _patch_algorithm_definition_derivation(monkeypatch)

    performance_calls = 0

    def flaky_run_hv(*, args: list[str], log_path: Path, cwd: Path | None = None):
        del cwd
        nonlocal performance_calls
        log_path.parent.mkdir(parents=True, exist_ok=True)
        command = args[0]
        if command != "performance-test":
            raise AssertionError(f"Unexpected hv command in test: {args}")

        performance_calls += 1
        metadata_dir = Path(args[args.index("--metadata-path") + 1])
        if performance_calls == 1:
            log_path.write_text("synthetic performance failure\n", encoding="utf-8")
            raise run_module._HvCommandFailure(
                "hv performance-test failed: synthetic performance failure",
                command_name="performance-test",
                hv_args=list(args),
                wrapper_cmd=["python", "hv", *args],
                log_path=str(log_path),
                metadata_dir=str(metadata_dir),
                log_paths={"command": str(log_path)},
                log_tail={"command": ["synthetic performance failure"]},
                root_cause="java.lang.IllegalStateException: synthetic performance failure",
                throwable_stacktrace="java.lang.IllegalStateException: synthetic performance failure",
                return_code=1,
                cwd=None,
            )

        metadata_dir.mkdir(parents=True, exist_ok=True)
        is_control = "v86.3.4" in str(metadata_dir)
        payload = {
            "max_memory_usage": 1000.0 if is_control else 980.0,
            "mean_throughput": 500.0 if is_control else 510.0,
            "response_time_metrics": {
                "mean_throughput": {"mean": 480.0 if is_control else 490.0},
                "mean": {"mean": 10.0 if is_control else 9.8},
                "p50": {"mean": 8.0 if is_control else 7.9},
                "p75": {"mean": 9.0 if is_control else 8.8},
                "p95": {"mean": 11.0 if is_control else 10.7},
                "p99": {"mean": 12.0 if is_control else 11.5},
                "p999": {"mean": 15.0 if is_control else 14.2},
            },
        }
        (metadata_dir / "metadata.json").write_text(json.dumps(payload) + "\n", encoding="utf-8")
        log_path.write_text("ok\n", encoding="utf-8")
        return {"command": args, "log_path": str(log_path), "tail": []}

    monkeypatch.setattr(run_module, "_run_hv", flaky_run_hv)

    parameter_path = _write_fake_parameter_zip(tmp_path / "predict.parameters.zip")
    source_path = tmp_path / "predict-source.jsonl"
    source_path.write_text('{"example_id":"ex-1"}\n', encoding="utf-8")

    create_args = _run_args(
        tmp_path / "meta",
        parameter_source=str(parameter_path),
        source_path=str(source_path),
        performance_runner="local",
        until="",
        only="system_performance",
    )

    with pytest.raises(ValueError, match="Stage 'system_performance' failed"):
        run_module.StartCommand().execute(create_args)

    created_payload = json.loads(capsys.readouterr().out)
    run_id = created_payload["run_id"]
    assert created_payload["state"] == "failed"
    assert created_payload["stage_status"]["system_performance"] == "failed"
    assert performance_calls == 1

    resume_args = _run_args(
        tmp_path / "meta",
        resume=run_id,
        control="",
        treatments=[],
        last_test_date="",
        backtest_days="",
        evaluation_criteria="",
        until="",
        only="system_performance",
    )
    run_module.StartCommand().execute(resume_args)

    resumed_payload = json.loads(capsys.readouterr().out)
    assert resumed_payload["action"] == "resumed"
    assert resumed_payload["state"] == "completed"
    assert resumed_payload["stage_status"]["system_performance"] == "passed"
    assert resumed_payload["stage_results"]["system_performance"]["stage_judgment"] == "pass"
    assert performance_calls == 7


def test_qa_run_system_performance_passes_sample_pool_size_to_local_hv(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _patch_config_defaults(monkeypatch)
    _patch_builds(monkeypatch)
    _patch_algorithm_definition_derivation(monkeypatch)

    performance_calls: list[list[str]] = []

    def fake_run_hv(*, args: list[str], log_path: Path, cwd: Path | None = None):
        del cwd
        if args[0] != "performance-test":
            raise AssertionError(f"Unexpected hv command in test: {args}")
        performance_calls.append(list(args))
        log_path.parent.mkdir(parents=True, exist_ok=True)
        log_path.write_text("ok\n", encoding="utf-8")
        metadata_dir = Path(args[args.index("--metadata-path") + 1])
        metadata_dir.mkdir(parents=True, exist_ok=True)
        is_control = "v86.3.4" in str(metadata_dir)
        payload = {
            "max_memory_usage": 1000.0 if is_control else 980.0,
            "mean_throughput": 500.0 if is_control else 510.0,
            "response_time_metrics": {
                "mean_throughput": {"mean": 480.0 if is_control else 490.0},
                "mean": {"mean": 10.0 if is_control else 9.8},
                "p50": {"mean": 8.0 if is_control else 7.9},
                "p75": {"mean": 9.0 if is_control else 8.8},
                "p95": {"mean": 11.0 if is_control else 10.7},
                "p99": {"mean": 12.0 if is_control else 11.5},
                "p999": {"mean": 15.0 if is_control else 14.2},
            },
        }
        (metadata_dir / "metadata.json").write_text(json.dumps(payload) + "\n", encoding="utf-8")
        return {"command": args, "log_path": str(log_path), "tail": []}

    monkeypatch.setattr(run_module, "_run_hv", fake_run_hv)
    parameter_path = _write_fake_parameter_zip(tmp_path / "predict.parameters.zip")
    source_path = tmp_path / "example-input.jsonl"
    source_path.write_text('{"example_id":"ex-1"}\n', encoding="utf-8")

    args = _run_args(
        tmp_path / "meta",
        parameter_source=str(parameter_path),
        source_path=str(source_path),
        performance_runner="local",
        performance_samples=722,
        performance_sample_pool_size=64,
        performance_target_rps=10.0,
        performance_workload_mode="realtime",
        performance_max_threads=2,
        until="",
        only="system_performance",
    )
    run_module.StartCommand().execute(args)

    payload = json.loads(capsys.readouterr().out)
    assert payload["stage_status"]["system_performance"] == "passed"
    assert payload["stage_options"]["system_performance"]["samples"] == 722
    assert payload["stage_options"]["system_performance"]["sample_pool_size"] == 64
    assert len(performance_calls) == 6
    for call in performance_calls:
        assert call[call.index("--samples") + 1] == "722"
        assert call[call.index("--sample-pool-size") + 1] == "64"
        assert call[call.index("--target-rps") + 1] == "10.0"
        assert call[call.index("--workload-mode") + 1] == "realtime"
        assert call[call.index("--max-threads") + 1] == "2"


def test_qa_run_can_execute_remote_system_performance_with_staged_parameter_zip(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _patch_config_defaults(monkeypatch)
    _patch_builds(monkeypatch)
    _patch_algorithm_definition_derivation(monkeypatch)

    uploaded: list[tuple[str, str]] = []
    hv_calls: list[list[str]] = []

    def fake_run_hv(*, args: list[str], log_path: Path, cwd: Path | None = None):
        hv_calls.append(list(args))
        log_path.parent.mkdir(parents=True, exist_ok=True)
        command = args[0]
        if command == "encode":
            dest_path = Path(args[args.index("--dest-path") + 1])
            dest_path.parent.mkdir(parents=True, exist_ok=True)
            dest_path.write_text("1\tfeature_a\tfeature_b\n", encoding="utf-8")
            schema_path = Path(args[args.index("--dest-schema-path") + 1])
            schema_path.parent.mkdir(parents=True, exist_ok=True)
            schema_path.write_text(
                json.dumps({"columns": ["label", "feature_a", "feature_b"]}) + "\n", encoding="utf-8"
            )
            metadata_dir = Path(args[args.index("--metadata-path") + 1])
            metadata_dir.mkdir(parents=True, exist_ok=True)
            (metadata_dir / "metadata.json").write_text(json.dumps({"ok": True}) + "\n", encoding="utf-8")
            return {"command": args, "log_path": str(log_path), "tail": []}

        if command == "performance-test":
            is_control = "v86.3.4" in str(log_path)
            job_name = "perf-control" if is_control else "perf-treatment"
            log_path.write_text(f"SageMaker job submitted: {job_name}\n", encoding="utf-8")
            return {
                "command": args,
                "log_path": str(log_path),
                "tail": [f"SageMaker job submitted: {job_name}"],
            }

        raise AssertionError(f"Unexpected hv command in test: {args}")

    class FakeS3Client:
        def upload_file(self, Filename: str, Bucket: str, Key: str):
            uploaded.append((Filename, f"s3://{Bucket}/{Key}"))

        def download_file(self, Bucket: str, Key: str, Filename: str):
            target = Path(Filename)
            target.parent.mkdir(parents=True, exist_ok=True)
            is_control = "perf-control" in Key
            payload = {
                "max_memory_usage": 1000.0 if is_control else 980.0,
                "mean_throughput": 500.0 if is_control else 510.0,
                "response_time_metrics": {
                    "mean_throughput": {"mean": 480.0 if is_control else 490.0},
                    "mean": {"mean": 10.0 if is_control else 9.8},
                    "p50": {"mean": 8.0 if is_control else 7.9},
                    "p75": {"mean": 9.0 if is_control else 8.8},
                    "p95": {"mean": 11.0 if is_control else 10.7},
                    "p99": {"mean": 12.0 if is_control else 11.5},
                    "p999": {"mean": 15.0 if is_control else 14.2},
                },
            }
            target.write_text(json.dumps(payload) + "\n", encoding="utf-8")

    class FakeSageMakerClient:
        def describe_training_job(self, TrainingJobName: str):
            return {
                "TrainingJobStatus": "Completed",
                "HyperParameters": {
                    "s3_uri_metadata": f"s3://fake-bucket/{TrainingJobName}/metadata",
                    "s3_uri_result_file": f"s3://fake-bucket/{TrainingJobName}/result.json",
                    "s3_uri_task_output": f"s3://fake-bucket/{TrainingJobName}/perf",
                },
            }

    class FakeSession:
        def client(self, service_name: str):
            if service_name == "s3":
                return FakeS3Client()
            if service_name == "sagemaker":
                return FakeSageMakerClient()
            raise AssertionError(f"Unexpected boto client request: {service_name}")

    monkeypatch.setattr(run_module, "_run_hv", fake_run_hv)
    monkeypatch.setattr(run_module, "_qa_run_boto_session", lambda assume_role_arn: FakeSession())

    parameter_path = _write_fake_parameter_zip(tmp_path / "predict.parameters.zip")
    sagemaker_config = tmp_path / "sagemaker.json"
    sagemaker_config.write_text(
        json.dumps({"OutputDataConfig": {"S3OutputPath": "s3://example-bucket/test-output"}}),
        encoding="utf-8",
    )

    args = _run_args(
        tmp_path / "meta",
        parameter_source=str(parameter_path),
        performance_sagemaker_config=str(sagemaker_config),
        performance_sagemaker_job_prefix="qa",
        performance_sample_pool_size=64,
        until="system_performance",
    )
    run_module.StartCommand().execute(args)

    payload = json.loads(capsys.readouterr().out)
    assert payload["action"] == "created"
    assert payload["state"] == "completed"
    assert payload["stage_status"]["encode_parity"] == "passed"
    assert payload["stage_status"]["system_performance"] == "passed"
    assert payload["stage_results"]["system_performance"]["stage_judgment"] == "pass"
    expected_source = (
        "s3://example-bucket/example-data/"
        "example-training-data/dt=2000-04-14/"
    )
    assert payload["execution_context"]["predict_source_path"] == expected_source
    assert payload["execution_context"]["performance_source_path"] == expected_source
    assert payload["stage_options"]["system_performance"]["runner"] == "auto"
    assert payload["stage_options"]["system_performance"]["sagemaker_job_prefix"] == "qa"
    assert payload["stage_options"]["system_performance"]["sample_pool_size"] == 64
    performance_calls = [call for call in hv_calls if call[0] == "performance-test"]
    assert len(performance_calls) == 6
    for call in performance_calls:
        assert "--sample-pool-size" in call
        assert call[call.index("--sample-pool-size") + 1] == "64"
    assert len(uploaded) == 1
    assert uploaded[0][0] == str(parameter_path)
    assert "_hv_qa/qa/" in uploaded[0][1]


def test_qa_run_remote_system_performance_submits_all_treatments_before_collecting_results(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _patch_config_defaults(monkeypatch)
    _patch_builds(monkeypatch)
    _patch_algorithm_definition_derivation(monkeypatch)

    submitted_refs: list[str] = []
    expected_submission_count = 9

    class FakeFuture:
        def __init__(self, kwargs: dict):
            self.kwargs = kwargs

        def result(self) -> dict[str, str]:
            assert len(submitted_refs) == expected_submission_count
            stage_dir = self.kwargs["stage_dir"]
            metadata_path = stage_dir / "metadata" / "metadata.json"
            metadata_path.parent.mkdir(parents=True, exist_ok=True)
            is_control = self.kwargs["git_ref"] == "v86.3.4"
            metadata_path.write_text(
                json.dumps(
                    {
                        "max_memory_usage": 1000.0 if is_control else 980.0,
                        "mean_throughput": 500.0 if is_control else 510.0,
                        "response_time_metrics": {
                            "mean_throughput": {"mean": 480.0 if is_control else 490.0},
                            "mean": {"mean": 10.0 if is_control else 9.8},
                            "p50": {"mean": 8.0 if is_control else 7.9},
                            "p75": {"mean": 9.0 if is_control else 8.8},
                            "p95": {"mean": 11.0 if is_control else 10.7},
                            "p99": {"mean": 12.0 if is_control else 11.5},
                            "p999": {"mean": 15.0 if is_control else 14.2},
                        },
                    }
                )
                + "\n",
                encoding="utf-8",
            )
            return {
                "metadata_path": str(metadata_path),
                "submission_path": str(stage_dir / "submission.json"),
                "training_job_name": f"perf-{self.kwargs['git_ref']}",
            }

    class FakeExecutor:
        def __init__(self, *, max_workers: int):
            assert max_workers == expected_submission_count

        def submit(self, _fn, **kwargs):
            submitted_refs.append(kwargs["git_ref"])
            return FakeFuture(kwargs)

        def shutdown(self) -> None:
            return None

    monkeypatch.setattr(run_module, "ThreadPoolExecutor", FakeExecutor)
    monkeypatch.setattr(
        run_module,
        "_ensure_remote_parameter_source",
        lambda **_kwargs: "s3://fake-bucket/parameter.zip",
    )

    parameter_path = _write_fake_parameter_zip(tmp_path / "predict.parameters.zip")
    args = _run_args(
        tmp_path / "meta",
        parameter_source=str(parameter_path),
        treatments=["candidate-one", "candidate-two"],
        performance_sagemaker_job_prefix="qa",
        until="",
        only="system_performance",
    )
    run_module.StartCommand().execute(args)

    payload = json.loads(capsys.readouterr().out)
    assert payload["state"] == "completed"
    assert submitted_refs.count("v86.3.4") == 3
    assert submitted_refs.count("candidate-one") == 3
    assert submitted_refs.count("candidate-two") == 3


def test_remote_system_performance_resume_reuses_submitted_job(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    jar_path = tmp_path / "algorithm.jar"
    jar_path.write_text("jar", encoding="utf-8")
    artifact = run_module._BuiltRefArtifact(
        git_ref="candidate",
        resolved_git_ref="candidate",
        artifact_name="ranker",
        artifact_version="1.0.0",
        git_commit="candidate-commit",
        jar_path=jar_path,
        build_dir=tmp_path,
    )
    submissions = 0

    def fake_run_hv(*, args: list[str], log_path: Path, cwd: Path | None = None):
        del args, cwd
        nonlocal submissions
        submissions += 1
        log_path.parent.mkdir(parents=True, exist_ok=True)
        log_path.write_text("SageMaker job submitted: perf-candidate\n", encoding="utf-8")
        return {"tail": ["SageMaker job submitted: perf-candidate"]}

    def fake_download(*, metadata_prefix: str, stage_dir: Path, s3_client):
        del metadata_prefix, s3_client
        metadata_path = stage_dir / "metadata" / "metadata.json"
        metadata_path.parent.mkdir(parents=True, exist_ok=True)
        metadata_path.write_text("{}\n", encoding="utf-8")
        return metadata_path

    class FakeSession:
        def client(self, service_name: str):
            return object()

    monkeypatch.setattr(run_module, "_run_hv", fake_run_hv)
    monkeypatch.setattr(run_module, "_qa_run_boto_session", lambda assume_role_arn: FakeSession())
    monkeypatch.setattr(
        run_module,
        "_wait_for_sagemaker_training_job",
        lambda **kwargs: {
            "TrainingJobStatus": "Completed",
            "HyperParameters": {"s3_uri_metadata": "s3://bucket/perf-candidate/metadata"},
        },
    )
    monkeypatch.setattr(run_module, "_download_system_performance_metadata", fake_download)

    arguments = {
        "git_ref": "candidate",
        "artifact": artifact,
        "algorithm_name": "ranker",
        "source_s3_uri": "s3://bucket/source.jsonl",
        "parameter_s3_uri": "s3://bucket/parameter.zip",
        "stage_dir": tmp_path / "stage",
        "system_performance_options": {"sagemaker_job_prefix": "qa"},
    }
    first = run_module._run_remote_system_performance_job(**arguments)
    Path(first["metadata_path"]).unlink()
    second = run_module._run_remote_system_performance_job(**arguments)

    assert submissions == 1
    assert first["training_job_name"] == second["training_job_name"] == "perf-candidate"


def test_qa_run_can_execute_remote_system_performance_with_per_ref_parameter_zips(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _patch_config_defaults(monkeypatch)
    _patch_builds(monkeypatch)
    _patch_algorithm_definition_derivation(monkeypatch)

    uploaded: list[tuple[str, str]] = []
    hv_calls: list[list[str]] = []

    def fake_run_hv(*, args: list[str], log_path: Path, cwd: Path | None = None):
        del cwd
        hv_calls.append(list(args))
        log_path.parent.mkdir(parents=True, exist_ok=True)
        if args[0] != "performance-test":
            raise AssertionError(f"Unexpected hv command in per-ref perf test: {args}")
        is_control = "v86.3.4" in str(log_path)
        job_name = "perf-control" if is_control else "perf-treatment"
        log_path.write_text(f"SageMaker job submitted: {job_name}\n", encoding="utf-8")
        return {
            "command": args,
            "log_path": str(log_path),
            "tail": [f"SageMaker job submitted: {job_name}"],
        }

    class FakeS3Client:
        def upload_file(self, Filename: str, Bucket: str, Key: str):
            uploaded.append((Filename, f"s3://{Bucket}/{Key}"))

        def download_file(self, Bucket: str, Key: str, Filename: str):
            target = Path(Filename)
            target.parent.mkdir(parents=True, exist_ok=True)
            is_control = "perf-control" in Key
            payload = {
                "max_memory_usage": 1000.0 if is_control else 900.0,
                "mean_throughput": 500.0 if is_control else 520.0,
                "response_time_metrics": {
                    "mean": {"mean": 10.0 if is_control else 9.0},
                    "p50": {"mean": 8.0 if is_control else 7.0},
                    "p75": {"mean": 9.0 if is_control else 8.0},
                    "p95": {"mean": 11.0 if is_control else 9.5},
                    "p99": {"mean": 12.0 if is_control else 10.0},
                    "p999": {"mean": 15.0 if is_control else 13.0},
                    "mean_throughput": {"mean": 480.0 if is_control else 500.0},
                },
            }
            target.write_text(json.dumps(payload) + "\n", encoding="utf-8")

    class FakeSageMakerClient:
        def describe_training_job(self, TrainingJobName: str):
            return {
                "TrainingJobStatus": "Completed",
                "HyperParameters": {
                    "s3_uri_metadata": f"s3://fake-bucket/{TrainingJobName}/metadata",
                },
            }

    class FakeSession:
        def client(self, service_name: str):
            if service_name == "s3":
                return FakeS3Client()
            if service_name == "sagemaker":
                return FakeSageMakerClient()
            raise AssertionError(f"Unexpected boto client request: {service_name}")

    monkeypatch.setattr(run_module, "_run_hv", fake_run_hv)
    monkeypatch.setattr(run_module, "_qa_run_boto_session", lambda assume_role_arn: FakeSession())

    control_parameter = _write_fake_parameter_zip(
        tmp_path / "control.parameters.zip",
        root_version="86.3.4",
        child_version="86.3.4",
    )
    treatment_parameter = _write_fake_parameter_zip(
        tmp_path / "treatment.parameters.zip",
        root_version="86.3.5",
        child_version="86.3.5",
    )
    sagemaker_config = tmp_path / "sagemaker.json"
    sagemaker_config.write_text(
        json.dumps({"OutputDataConfig": {"S3OutputPath": "s3://example-bucket/test-output"}}),
        encoding="utf-8",
    )

    args = _run_args(
        tmp_path / "meta",
        parameter_source="",
        control_parameter_source=str(control_parameter),
        treatment_parameter_sources=[str(treatment_parameter)],
        performance_sagemaker_config=str(sagemaker_config),
        performance_sagemaker_job_prefix="qa",
        performance_sample_pool_size=64,
        only="system_performance",
        until="",
    )
    run_module.StartCommand().execute(args)

    payload = json.loads(capsys.readouterr().out)
    assert payload["state"] == "completed"
    assert payload["stage_sequence"] == ["system_performance"]
    assert payload["stage_status"]["system_performance"] == "passed"
    assert payload["parameter_source"] is None
    assert payload["parameter_sources"]["control"]["source"] == str(control_parameter)
    assert payload["parameter_sources"]["treatments"][0]["source"] == str(treatment_parameter)
    performance_calls = [call for call in hv_calls if call[0] == "performance-test"]
    assert len(performance_calls) == 6
    parameter_uris = [call[call.index("--parameter-s3-uri") + 1] for call in performance_calls]
    assert len(set(parameter_uris)) == 2
    assert any("v86.3.4" in uri for uri in parameter_uris)
    assert any("feature_example-cleanup-v86" in uri for uri in parameter_uris)
    assert len(uploaded) == 2


def test_ensure_remote_parameter_source_reuses_matching_staged_object(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    parameter_path = _write_fake_parameter_zip(tmp_path / "predict.parameters.zip")
    uploads: list[tuple[str, str, str]] = []
    head_requests: list[tuple[str, str]] = []

    class FakeS3Client:
        def head_object(self, Bucket: str, Key: str):
            head_requests.append((Bucket, Key))
            return {"ContentLength": parameter_path.stat().st_size}

        def upload_file(self, Filename: str, Bucket: str, Key: str):
            uploads.append((Filename, Bucket, Key))

    class FakeSession:
        def client(self, service_name: str):
            if service_name != "s3":
                raise AssertionError(f"Unexpected boto client request: {service_name}")
            return FakeS3Client()

    monkeypatch.setattr(run_module, "_qa_run_boto_session", lambda assume_role_arn: FakeSession())

    staged_s3_uri = run_module._ensure_remote_parameter_source(
        parameter_source=str(parameter_path),
        run_id="qa-run",
        stage_dir=tmp_path / "stage",
        system_performance_options={"s3_output_base": "s3://example-bucket/test-output"},
    )

    assert staged_s3_uri.endswith("/_hv_qa/qa/qa-run/parameter_artifacts/predict.parameters.zip")
    assert len(head_requests) == 1
    assert uploads == []


def test_qa_run_fails_fast_on_parameter_source_version_mismatch(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _patch_config_defaults(monkeypatch)
    _patch_builds(monkeypatch)
    _patch_algorithm_definition_derivation(monkeypatch)

    parameter_path = _write_fake_parameter_zip(
        tmp_path / "predict.parameters.zip",
        root_version="86.1.13",
        child_version="86.1.13",
    )

    def fail_if_hv_runs(*, args: list[str], log_path: Path, cwd: Path | None = None):
        raise AssertionError(f"hv should not run when parameter validation fails: {args}")

    def fake_as_locally_available_content(cache_path: str | None, local_cache_path: str) -> str | None:
        del local_cache_path
        if cache_path is None:
            return None
        if cache_path == str(parameter_path):
            return str(parameter_path)
        if cache_path.startswith("s3://"):
            raise AssertionError("source localized before parameter validation")
        path = Path(cache_path)
        return str(path) if path.exists() else None

    monkeypatch.setattr(run_module, "_run_hv", fail_if_hv_runs)
    monkeypatch.setattr(run_module, "as_locally_available_content", fake_as_locally_available_content)

    args = _run_args(
        tmp_path / "meta",
        parameter_source=str(parameter_path),
        until="encode_parity",
    )
    with pytest.raises(ValueError):
        run_module.StartCommand().execute(args)

    payload = json.loads(capsys.readouterr().out)
    assert payload["ok"] is False
    assert payload["state"] == "failed"
    assert payload["stage_status"]["encode_parity"] == "failed"
    assert "packages example-control-model version 86.1.13" in (
        payload["stage_results"]["encode_parity"]["error"]
    )
    assert "source localized before parameter validation" not in payload["stage_results"]["encode_parity"]["error"]


def test_qa_run_predict_parity_compares_all_unordered_prediction_shards(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _patch_config_defaults(monkeypatch)
    _patch_builds(monkeypatch)
    _patch_algorithm_definition_derivation(monkeypatch)
    parameter_path = _write_fake_parameter_zip(tmp_path / "predict.parameters.zip")
    local_source = tmp_path / "predict-source.jsonl"
    local_source.write_text('{"example_id":"ex-1"}\n', encoding="utf-8")

    def fake_run_hv(*, args: list[str], log_path: Path, cwd: Path | None = None):
        del cwd
        log_path.parent.mkdir(parents=True, exist_ok=True)
        log_path.write_text("ok\n", encoding="utf-8")
        side_slug = log_path.parent.name
        is_control = side_slug == "v86.3.4"
        command = args[0]
        metadata_dir = Path(args[args.index("--metadata-path") + 1])
        metadata_dir.mkdir(parents=True, exist_ok=True)

        if command == "encode":
            dest_path = Path(args[args.index("--dest-path") + 1])
            dest_path.parent.mkdir(parents=True, exist_ok=True)
            dest_path.write_text("1\tfeature_a\tfeature_b\n", encoding="utf-8")
            schema_path = Path(args[args.index("--dest-schema-path") + 1])
            schema_path.parent.mkdir(parents=True, exist_ok=True)
            schema_path.write_text(
                json.dumps({"columns": ["label", "feature_a", "feature_b"]}) + "\n",
                encoding="utf-8",
            )
            (metadata_dir / "metadata.json").write_text(json.dumps({"ok": True}) + "\n", encoding="utf-8")
            return {"command": args, "log_path": str(log_path), "tail": []}

        if command == "performance-test":
            payload = {
                "max_memory_usage": 1000.0 if is_control else 980.0,
                "mean_throughput": 500.0 if is_control else 510.0,
                "response_time_metrics": {
                    "mean_throughput": {"mean": 480.0 if is_control else 490.0},
                    "mean": {"mean": 10.0 if is_control else 9.8},
                    "p50": {"mean": 8.0 if is_control else 7.9},
                    "p75": {"mean": 9.0 if is_control else 8.8},
                    "p95": {"mean": 11.0 if is_control else 10.7},
                    "p99": {"mean": 12.0 if is_control else 11.5},
                    "p999": {"mean": 15.0 if is_control else 14.2},
                },
            }
            (metadata_dir / "metadata.json").write_text(json.dumps(payload) + "\n", encoding="utf-8")
            return {"command": args, "log_path": str(log_path), "tail": []}

        assert command == "predict"
        dest_dir = Path(args[args.index("--dest-path") + 1])
        dest_dir.mkdir(parents=True, exist_ok=True)
        (metadata_dir / "metadata.json").write_text(json.dumps({"ok": True}) + "\n", encoding="utf-8")

        shard_payloads = (
            {
                "shard_0.jsonl": [
                    {
                        "example_id": "ex-2",
                        "result": [
                            {"action_id": "a1", "rank": 1, "score": 0.6},
                            {"action_id": "a2", "rank": 2, "score": 0.2},
                        ],
                    }
                ],
                "shard_1.jsonl": [
                    {
                        "example_id": "ex-1",
                        "result": [
                            {"action_id": "a1", "rank": 1, "score": 0.8},
                            {"action_id": "a2", "rank": 2, "score": 0.1},
                        ],
                    }
                ],
            }
            if is_control
            else {
                "shard_0.jsonl": [
                    {
                        "example_id": "ex-1",
                        "result": [
                            {"action_id": "a1", "rank": 1, "score": 0.8},
                            {"action_id": "a2", "rank": 2, "score": 0.1},
                        ],
                    }
                ],
                "shard_1.jsonl": [
                    {
                        "example_id": "ex-2",
                        "result": [
                            {"action_id": "a1", "rank": 1, "score": 0.6},
                            {"action_id": "a2", "rank": 2, "score": 0.2},
                        ],
                    }
                ],
            }
        )
        for filename, records in shard_payloads.items():
            (dest_dir / filename).write_text("".join(json.dumps(record) + "\n" for record in records), encoding="utf-8")
        return {"command": args, "log_path": str(log_path), "tail": []}

    monkeypatch.setattr(run_module, "_run_hv", fake_run_hv)

    args = _run_args(
        tmp_path / "meta",
        parameter_source=str(parameter_path),
        source_path=str(local_source),
        until="predict_parity",
        only="",
    )
    run_module.StartCommand().execute(args)

    payload = json.loads(capsys.readouterr().out)
    assert payload["action"] == "created"
    assert payload["state"] == "completed"
    assert payload["stage_status"]["predict_parity"] == "passed"
    stage_result = payload["stage_results"]["predict_parity"]
    treatment = stage_result["treatments"][0]
    assert treatment["status"]["output_equivalent"] is True
    comparison = json.loads(Path(treatment["artifacts"]["comparison_path"]).read_text(encoding="utf-8"))
    assert comparison["status"] == "passed"
    assert comparison["processed_lines"] == 2
    assert len(comparison["normalization"]["control"]["input_files"]) == 2
    assert len(comparison["normalization"]["treatment"]["input_files"]) == 2


def test_qa_run_encode_parity_passes_when_treatment_matches_control_failure(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _patch_config_defaults(monkeypatch)
    _patch_builds(monkeypatch)
    _patch_algorithm_definition_derivation(monkeypatch)
    parameter_path = _write_fake_parameter_zip(tmp_path / "predict.parameters.zip")

    def failing_run_hv(*, args: list[str], log_path: Path, cwd: Path | None = None):
        del cwd
        log_path.parent.mkdir(parents=True, exist_ok=True)
        metadata_dir = Path(args[args.index("--metadata-path") + 1])
        metadata_dir.mkdir(parents=True, exist_ok=True)
        offline_log = metadata_dir / "hotvect-offline-utils.log"
        throwable = (
            "Caused by: java.lang.IllegalStateException: shared synthetic encode failure\n"
            "\tat org.example.Encode.run(Encode.java:42)\n"
            "\t... 4 common frames omitted"
        )
        offline_log.write_text(throwable + "\n", encoding="utf-8")
        raise run_module._HvCommandFailure(
            "hv encode failed: java.lang.IllegalStateException: shared synthetic encode failure",
            command_name=args[0],
            hv_args=args,
            wrapper_cmd=["python", "hv", *args],
            log_path=str(log_path),
            metadata_dir=str(metadata_dir),
            log_paths={"hotvect_offline_utils": str(offline_log), "command": str(log_path)},
            log_tail={"hotvect_offline_utils": throwable.splitlines()},
            root_cause="java.lang.IllegalStateException: shared synthetic encode failure",
            throwable_stacktrace=throwable,
            return_code=1,
            cwd=None,
        )

    monkeypatch.setattr(run_module, "_run_hv", failing_run_hv)

    args = _run_args(
        tmp_path / "meta",
        parameter_source=str(parameter_path),
        until="encode_parity",
        only="",
    )
    run_module.StartCommand().execute(args)

    payload = json.loads(capsys.readouterr().out)
    assert payload["action"] == "created"
    assert payload["state"] == "completed"
    assert payload["stage_status"]["encode_parity"] == "passed"
    stage_result = payload["stage_results"]["encode_parity"]
    assert stage_result["control"]["status"]["jobs_complete"] is False
    treatment = stage_result["treatments"][0]
    assert treatment["status"]["jobs_complete"] is False
    assert treatment["status"]["matched_control_failure"] is True
    failure_comparison = json.loads(Path(treatment["artifacts"]["failure_comparison_path"]).read_text(encoding="utf-8"))
    assert failure_comparison["equivalent"] is True
    assert failure_comparison["matched_on"] == "throwable_stacktrace"
    assert failure_comparison["notes"] == []


def test_qa_run_encode_parity_accepts_matching_root_cause_with_stacktrace_drift(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _patch_config_defaults(monkeypatch)
    _patch_builds(monkeypatch)
    _patch_algorithm_definition_derivation(monkeypatch)
    parameter_path = _write_fake_parameter_zip(tmp_path / "predict.parameters.zip")

    def failing_run_hv(*, args: list[str], log_path: Path, cwd: Path | None = None):
        del cwd
        log_path.parent.mkdir(parents=True, exist_ok=True)
        metadata_dir = Path(args[args.index("--metadata-path") + 1])
        metadata_dir.mkdir(parents=True, exist_ok=True)
        offline_log = metadata_dir / "hotvect-offline-utils.log"
        shared_root_cause = "java.lang.IllegalStateException: shared synthetic encode failure"
        algorithm_jar = args[args.index("--algorithm-jar") + 1]
        if algorithm_jar.endswith("feature_example-cleanup-v86.jar"):
            throwable = (
                "Caused by: java.lang.IllegalStateException: shared synthetic encode failure\n"
                "\tat org.example.ConfigFeatureState.fetch(ConfigFeatureState.java:235)\n"
                "\t... 4 common frames omitted"
            )
        else:
            throwable = (
                "Caused by: java.lang.IllegalStateException: shared synthetic encode failure\n"
                "\tat org.example.InMemoryConfigFeatureState.fetch(InMemoryConfigFeatureState.java:247)\n"
                "\t... 4 common frames omitted"
            )
        offline_log.write_text(throwable + "\n", encoding="utf-8")
        raise run_module._HvCommandFailure(
            f"hv encode failed: {shared_root_cause}",
            command_name=args[0],
            hv_args=args,
            wrapper_cmd=["python", "hv", *args],
            log_path=str(log_path),
            metadata_dir=str(metadata_dir),
            log_paths={"hotvect_offline_utils": str(offline_log), "command": str(log_path)},
            log_tail={"hotvect_offline_utils": throwable.splitlines()},
            root_cause=shared_root_cause,
            throwable_stacktrace=throwable,
            return_code=1,
            cwd=None,
        )

    monkeypatch.setattr(run_module, "_run_hv", failing_run_hv)

    args = _run_args(
        tmp_path / "meta",
        parameter_source=str(parameter_path),
        until="encode_parity",
        only="",
    )
    run_module.StartCommand().execute(args)

    payload = json.loads(capsys.readouterr().out)
    assert payload["action"] == "created"
    assert payload["state"] == "completed"
    assert payload["stage_status"]["encode_parity"] == "passed"
    treatment = payload["stage_results"]["encode_parity"]["treatments"][0]
    assert treatment["status"]["matched_control_failure"] is True
    failure_comparison = json.loads(Path(treatment["artifacts"]["failure_comparison_path"]).read_text(encoding="utf-8"))
    assert failure_comparison["equivalent"] is True
    assert failure_comparison["matched_on"] == "root_cause"
    assert failure_comparison["mismatches"] == []
    assert any("throwable_stacktrace differed" in note for note in failure_comparison["notes"])


def test_missing_git_ref_message_includes_similar_refs(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    repo_path = tmp_path / "repo"
    repo_path.mkdir()
    monkeypatch.setattr(
        run_module,
        "_list_known_git_refs",
        lambda path: ["feature/example-cache-v86.3.4", "feature/example-compare-v86"] if path == repo_path else [],
    )

    message = run_module._missing_git_ref_message(repo_path=repo_path, git_ref="feature/example-cache-v86.3.5")

    assert "Git ref 'feature/example-cache-v86.3.5' was not found" in message
    assert "feature/example-cache-v86.3.4" in message
    assert "feature/example-compare-v86" in message


def test_localize_optional_content_refreshes_expired_aws_credentials(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    cache_dir = tmp_path / "cache"
    localized_path = tmp_path / "localized.jsonl"
    localized_path.write_text('{"example_id":"ex-1"}\n', encoding="utf-8")

    attempts = {"count": 0}

    def fake_as_locally_available_content(cache_path: str | None, local_cache_path: str) -> str | None:
        attempts["count"] += 1
        assert cache_path == "s3://bucket/path"
        assert local_cache_path == str(cache_dir)
        if attempts["count"] == 1:
            raise ClientError(
                {"Error": {"Code": "ExpiredToken", "Message": "The provided token has expired."}},
                "ListObjects",
            )
        return str(localized_path)

    helper_calls: list[list[str]] = []

    def fake_run(cmd, capture_output, text, encoding, errors, check):
        helper_calls.append(list(cmd))
        return subprocess.CompletedProcess(cmd, 0, stdout="", stderr="")

    monkeypatch.setattr(run_module, "as_locally_available_content", fake_as_locally_available_content)
    monkeypatch.setattr(
        run_module.hv_config,
        "try_load_config",
        lambda: {"aws": {"credential_helper": "example-aws-cli login example-account ExampleRole"}},
    )
    monkeypatch.setattr(run_module.subprocess, "run", fake_run)

    resolved = run_module._localize_optional_content("s3://bucket/path", cache_dir=cache_dir)

    assert resolved == str(localized_path.resolve())
    assert attempts["count"] == 2
    assert helper_calls == [["example-aws-cli", "login", "example-account", "ExampleRole"]]


def test_retry_after_refreshing_expired_aws_credentials_retries_transient_aws_timeout(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    attempts = {"count": 0}
    monkeypatch.setattr(run_module.time, "sleep", lambda _seconds: None)

    def operation() -> str:
        attempts["count"] += 1
        if attempts["count"] == 1:
            raise run_module.ReadTimeoutError(
                endpoint_url="https://api.sagemaker.eu-central-1.amazonaws.com/",
                error=TimeoutError("handshake timed out"),
            )
        return "ok"

    assert (
        run_module._retry_after_refreshing_expired_aws_credentials(
            reason="polling SageMaker training job",
            operation=operation,
        )
        == "ok"
    )
    assert attempts["count"] == 2


def test_localize_sampled_input_samples_s3_prefix_without_full_download(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    records = [json.dumps({"example_id": f"ex-{index}"}) + "\n" for index in range(5)]
    objects = {
        "prefix/part-00000.json.gz": gzip.compress("".join(records).encode("utf-8")),
        "prefix/part-00001.json.gz": gzip.compress(b'{"example_id":"unused"}\n'),
    }

    class FakePaginator:
        def paginate(self, *, Bucket: str, Prefix: str):
            assert Bucket == "bucket"
            assert Prefix == "prefix/"
            return [
                {
                    "Contents": [
                        {"Key": "prefix/_SUCCESS", "Size": 1},
                        {"Key": "prefix/part-00001.json.gz", "Size": len(objects["prefix/part-00001.json.gz"])},
                        {"Key": "prefix/part-00000.json.gz", "Size": len(objects["prefix/part-00000.json.gz"])},
                    ]
                }
            ]

    class FakeS3Client:
        def __init__(self):
            self.read_keys: list[str] = []

        def get_paginator(self, name: str):
            assert name == "list_objects_v2"
            return FakePaginator()

        def get_object(self, *, Bucket: str, Key: str):
            assert Bucket == "bucket"
            self.read_keys.append(Key)
            return {"Body": BytesIO(objects[Key])}

    fake_client = FakeS3Client()

    def fake_boto3_client(service_name: str):
        assert service_name == "s3"
        return fake_client

    monkeypatch.setattr(run_module.boto3, "client", fake_boto3_client)

    resolved = run_module._localize_sampled_input_content(
        "s3://bucket/prefix/",
        cache_dir=tmp_path / "cache",
        sample_count=3,
    )

    sample_path = Path(resolved) / "part-00000.json.gz"
    with gzip.open(sample_path, "rt", encoding="utf-8") as sample_file:
        sampled_records = [json.loads(line) for line in sample_file]
    metadata_path = Path(resolved).parent / f"{Path(resolved).name}.metadata.json"
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))

    assert [record["example_id"] for record in sampled_records] == ["ex-0", "ex-1", "ex-2"]
    assert fake_client.read_keys == ["prefix/part-00000.json.gz"]
    assert metadata["listed_object_count"] == 2
    assert metadata["read_object_count"] == 1
    assert [path.name for path in Path(resolved).iterdir()] == ["part-00000.json.gz"]


def test_qa_run_failure_output_contains_debug_handoff(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _patch_config_defaults(monkeypatch)
    _patch_builds(monkeypatch)
    _patch_algorithm_definition_derivation(monkeypatch)
    parameter_path = _write_fake_parameter_zip(tmp_path / "predict.parameters.zip")

    source_path = tmp_path / "source.jsonl"
    source_path.write_text('{"example_id":"ex-1"}\n', encoding="utf-8")

    def failing_run_hv(*, args: list[str], log_path: Path, cwd: Path | None = None):
        del cwd
        log_path.parent.mkdir(parents=True, exist_ok=True)
        metadata_dir = Path(args[args.index("--metadata-path") + 1])
        metadata_dir.mkdir(parents=True, exist_ok=True)
        if log_path.parent.name != "feature_example-cleanup-v86":
            dest_path = Path(args[args.index("--dest-path") + 1])
            dest_path.parent.mkdir(parents=True, exist_ok=True)
            dest_path.write_text("1\tfeature_a\tfeature_b\n", encoding="utf-8")
            schema_path = Path(args[args.index("--dest-schema-path") + 1])
            schema_path.parent.mkdir(parents=True, exist_ok=True)
            schema_path.write_text(
                json.dumps({"columns": ["label", "feature_a", "feature_b"]}) + "\n",
                encoding="utf-8",
            )
            (metadata_dir / "metadata.json").write_text(json.dumps({"ok": True}) + "\n", encoding="utf-8")
            return {"command": args, "log_path": str(log_path), "tail": []}

        offline_log = metadata_dir / "hotvect-offline-utils.log"
        offline_log.write_text(
            "\n".join(
                [
                    "Caused by: java.lang.IllegalStateException: synthetic encode failure",
                    "\tat org.example.Encode.run(Encode.java:42)",
                    "\t... 4 common frames omitted",
                ]
            )
            + "\n",
            encoding="utf-8",
        )
        raise run_module._HvCommandFailure(
            "hv encode failed: java.lang.IllegalStateException: synthetic encode failure",
            command_name=args[0],
            hv_args=args,
            wrapper_cmd=["python", "hv", *args],
            log_path=str(log_path),
            metadata_dir=str(metadata_dir),
            log_paths={"hotvect_offline_utils": str(offline_log), "command": str(log_path)},
            log_tail={
                "hotvect_offline_utils": [
                    "Caused by: java.lang.IllegalStateException: synthetic encode failure",
                    "\tat org.example.Encode.run(Encode.java:42)",
                ]
            },
            root_cause="java.lang.IllegalStateException: synthetic encode failure",
            throwable_stacktrace=(
                "Caused by: java.lang.IllegalStateException: synthetic encode failure\n"
                "\tat org.example.Encode.run(Encode.java:42)\n"
                "\t... 4 common frames omitted"
            ),
            return_code=1,
            cwd=None,
        )

    monkeypatch.setattr(run_module, "_run_hv", failing_run_hv)

    args = _run_args(
        tmp_path / "meta",
        parameter_source=str(parameter_path),
        source_path=str(source_path),
        until="encode_parity",
    )
    with pytest.raises(ValueError):
        run_module.StartCommand().execute(args)

    payload = json.loads(capsys.readouterr().out)
    assert payload["ok"] is False
    assert payload["state"] == "failed"
    assert payload["stage_status"]["encode_parity"] == "failed"
    assert payload["debug_handoff"]["failed_stage"] == "encode_parity"
    assert "synthetic encode failure" in payload["debug_handoff"]["failure_summary"]
    assert payload["debug_handoff"]["criteria"]["id"] == "noninferiority"
    assert payload["debug_handoff"]["refs"]["control"]["git_ref"] == "v86.3.4"
    assert payload["debug_handoff"]["requested_stages"] == ["encode_parity"]
    assert payload["debug_handoff"]["stage_debug"]["kind"] == "hv_command_failure"
    assert payload["debug_handoff"]["run_files"]["status"].endswith("status.json")
    assert any(path.endswith("plan.json") for path in payload["debug_handoff"]["suggested_files"])
    stage_debug = payload["stage_results"]["encode_parity"]["debug"]
    assert stage_debug["kind"] == "hv_command_failure"
    assert stage_debug["command"]["command_name"] == "encode"
    assert stage_debug["command"]["hv_args"][0] == "encode"
    assert "synthetic encode failure" in stage_debug["root_cause"]
    assert "org.example.Encode.run" in stage_debug["throwable_stacktrace"]
    assert os.path.isabs(stage_debug["log_paths"]["command"])
    assert os.path.isabs(stage_debug["metadata_dir"])


def test_run_hv_reports_logged_root_cause_from_metadata_log(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    metadata_dir = tmp_path / "metadata"
    metadata_dir.mkdir(parents=True, exist_ok=True)
    (metadata_dir / "hotvect-offline-utils.log").write_text(
        "\n".join(
            [
                "236 main INFO - Running PredictTask",
                "527 main ERROR - Error while running:com.hotvect.offlineutils.commandline.PredictTask@6995bf68",
                "Caused by: java.lang.IllegalStateException: Config feature state is missing. "
                "Expected either key ending with config_feature_state.jsonl or ordered shards matching "
                "config_feature_state-00000.jsonl, available keys: [in_memory_config_feature_state-00000.jsonl]",
            ]
        )
        + "\n",
        encoding="utf-8",
    )

    def fake_run_logged_command(
        cmd: list[str],
        *,
        log_path: Path,
        cwd: Path | None = None,
        env: dict[str, str] | None = None,
    ) -> dict[str, object]:
        del cwd, env
        log_path.parent.mkdir(parents=True, exist_ok=True)
        log_path.write_text("", encoding="utf-8")
        raise subprocess.CalledProcessError(returncode=1, cmd=cmd, output="")

    monkeypatch.setattr(run_module, "_run_logged_command", fake_run_logged_command)

    with pytest.raises(RuntimeError) as exc_info:
        run_module._run_hv(
            args=[
                "predict",
                "--algorithm-name",
                "example-control-algorithm",
                "--metadata-path",
                str(metadata_dir),
            ],
            log_path=tmp_path / "command.log",
        )

    message = str(exc_info.value)
    assert message.startswith("hv predict failed:")
    assert "Config feature state is missing." in message
    assert "config_feature_state.jsonl" in message
    assert "Available keys omitted." in message
    assert isinstance(exc_info.value, run_module._HvCommandFailure)
    assert exc_info.value.throwable_stacktrace is not None
    assert "Caused by: java.lang.IllegalStateException: Config feature state is missing." in (
        exc_info.value.throwable_stacktrace
    )
    assert "config_feature_state-00000.jsonl" in exc_info.value.throwable_stacktrace


def test_extract_logged_throwable_stacktrace_returns_last_java_cause_block(tmp_path: Path) -> None:
    log_path = tmp_path / "hotvect-offline-utils.log"
    log_path.write_text(
        "\n".join(
            [
                "12 main ERROR - Error while running:com.hotvect.task@1",
                "java.lang.RuntimeException: wrapper failure",
                "\tat com.hotvect.Runner.run(Runner.java:10)",
                "Caused by: java.lang.IllegalStateException: first cause",
                "\tat com.hotvect.Step.one(Step.java:20)",
                "13 main ERROR - Exception encountered",
                "java.util.concurrent.ExecutionException: wrapper failure",
                "\tat com.hotvect.Task.call(Task.java:30)",
                "Caused by: java.lang.IllegalStateException: final root cause",
                "\tat org.example.Encode.run(Encode.java:42)",
                "\t... 4 common frames omitted",
                "14 main INFO - done",
            ]
        )
        + "\n",
        encoding="utf-8",
    )

    throwable_stacktrace = run_module._extract_logged_throwable_stacktrace(log_path)

    assert throwable_stacktrace == (
        "Caused by: java.lang.IllegalStateException: final root cause\n"
        "\tat org.example.Encode.run(Encode.java:42)\n"
        "\t... 4 common frames omitted"
    )
