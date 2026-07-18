import json
import zipfile
from pathlib import Path
from types import SimpleNamespace

import pytest

import hotvect.sagemaker as sagemaker_module
from hotvect.sagemaker import SagemakerTrainingExecutor


def _write_test_jar(path: Path, *, algo_name: str, algo_def: dict) -> None:
    with zipfile.ZipFile(path, "w") as z:
        z.writestr(f"{algo_name}-algorithm-definition.json", json.dumps(algo_def).encode("utf-8"))


def test_override_guard_raises_for_legacy_semver_image_when_effective_definition_differs(tmp_path: Path) -> None:
    algo_name = "my-algo"
    jar_def = {"algorithm_name": algo_name, "train_data_prefix": "train_a"}
    effective_def = {"algorithm_name": algo_name, "train_data_prefix": "train_b"}

    jar = tmp_path / "algo.jar"
    _write_test_jar(jar, algo_name=algo_name, algo_def=jar_def)

    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor.training_job_definition = {"AlgorithmSpecification": {"TrainingImage": "repo/hotvect:10.13.15"}}
    executor.algorithm_pipeline = SimpleNamespace(
        algorithm_name=algo_name,
        algorithm_definition=effective_def,
        algorithm_jar_path=lambda: jar,
    )

    with pytest.raises(ValueError, match=r"must be >= v10\.14\.0"):
        executor._fail_if_algorithm_overrides_would_be_ignored()


def test_override_guard_allows_v10_semver_image_when_effective_definition_differs(tmp_path: Path) -> None:
    algo_name = "my-algo"
    jar_def = {"algorithm_name": algo_name, "train_data_prefix": "train_a"}
    effective_def = {"algorithm_name": algo_name, "train_data_prefix": "train_b"}

    jar = tmp_path / "algo.jar"
    _write_test_jar(jar, algo_name=algo_name, algo_def=jar_def)

    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor.training_job_definition = {"AlgorithmSpecification": {"TrainingImage": "repo/hotvect:10.14.0"}}
    executor.algorithm_pipeline = SimpleNamespace(
        algorithm_name=algo_name,
        algorithm_definition=effective_def,
        algorithm_jar_path=lambda: jar,
    )

    executor._fail_if_algorithm_overrides_would_be_ignored()


def test_override_guard_raises_for_legacy_semver_image_when_jar_cannot_be_read(tmp_path: Path) -> None:
    jar = tmp_path / "algo.jar"
    jar.write_text("not a zip", encoding="utf-8")

    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor.training_job_definition = {"AlgorithmSpecification": {"TrainingImage": "repo/hotvect:10.13.15"}}
    executor.algorithm_pipeline = SimpleNamespace(
        algorithm_name="my-algo",
        algorithm_definition={"algorithm_name": "my-algo", "train_data_prefix": "train_b"},
        algorithm_jar_path=lambda: jar,
    )

    with pytest.raises(ValueError, match=r"must be >= v10\.14\.0"):
        executor._fail_if_algorithm_overrides_would_be_ignored()


def test_override_guard_skips_jar_inspection_for_v10_image_when_jar_cannot_be_read(tmp_path: Path) -> None:
    jar = tmp_path / "algo.jar"
    jar.write_text("not a zip", encoding="utf-8")

    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor.training_job_definition = {"AlgorithmSpecification": {"TrainingImage": "repo/hotvect:10.14.0"}}
    executor.algorithm_pipeline = SimpleNamespace(
        algorithm_name="my-algo",
        algorithm_definition={"algorithm_name": "my-algo", "train_data_prefix": "train_b"},
        algorithm_jar_path=lambda: jar,
    )

    executor._fail_if_algorithm_overrides_would_be_ignored()


def test_override_guard_raises_for_pre_10_14_image_even_without_algorithm_override(tmp_path: Path) -> None:
    algo_name = "my-algo"
    jar_def = {"algorithm_name": algo_name, "train_data_prefix": "train_a"}

    jar = tmp_path / "algo.jar"
    _write_test_jar(jar, algo_name=algo_name, algo_def=jar_def)

    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor.training_job_definition = {"AlgorithmSpecification": {"TrainingImage": "repo/hotvect:10.13.15"}}
    executor.algorithm_pipeline = SimpleNamespace(
        algorithm_name=algo_name,
        algorithm_definition=jar_def,
        algorithm_jar_path=lambda: jar,
    )

    with pytest.raises(ValueError, match=r"must be >= v10\.14\.0"):
        executor._fail_if_algorithm_overrides_would_be_ignored()


def test_override_guard_allows_script_mode_on_legacy_image_when_effective_definition_differs(tmp_path: Path) -> None:
    algo_name = "my-algo"
    jar_def = {"algorithm_name": algo_name, "train_data_prefix": "train_a"}
    effective_def = {"algorithm_name": algo_name, "train_data_prefix": "train_b"}

    jar = tmp_path / "algo.jar"
    _write_test_jar(jar, algo_name=algo_name, algo_def=jar_def)

    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor.training_job_definition = {
        "AlgorithmSpecification": {"TrainingImage": "repo/hotvect:9.34.0"},
        "HyperParameters": {"s3_uri_custom_jar": "s3://bucket/payload.zip"},
    }
    executor.algorithm_pipeline = SimpleNamespace(
        algorithm_name=algo_name,
        algorithm_definition=effective_def,
        algorithm_jar_path=lambda: jar,
    )

    executor._fail_if_algorithm_overrides_would_be_ignored()


def test_prepare_algorithm_definition_uploads_effective_definition_for_remote_rebuild_and_metadata(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    uploads = {}

    def _fake_upload_json_to_s3(payload, s3_uri, _s3_client, fail_fast=False, default=None):
        del default
        uploads[s3_uri] = {
            "fail_fast": fail_fast,
            "payload": payload,
        }

    monkeypatch.setattr(sagemaker_module, "_upload_json_to_s3", _fake_upload_json_to_s3)

    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor._s3_client = object()
    executor.training_job_definition = {
        "TrainingJobName": "train-job",
        "OutputDataConfig": {"S3OutputPath": "s3://bucket/output"},
        "HyperParameters": {"s3_uri_metadata": "s3://bucket/output/train-job/hp-slug/metadata"},
    }
    executor.algorithm_pipeline = SimpleNamespace(
        algorithm_name="demo-algo",
        algorithm_version="1.2.3",
        algorithm_definition_override={
            "hyperparameter_version": "hp-v1",
            "dependencies": {"child-model": {"number_of_training_days": 1}},
        },
        algorithm_definition={
            "algorithm_name": "demo-algo",
            "algorithm_version": "1.2.3",
            "hyperparameter_version": "hp-v1",
            "dependencies": ["child-model"],
            "training_lag_days": 7,
        },
        hyperparameter_slug=lambda: "hp-slug",
    )

    executor._prepare_algorithm_definition_for_sagemaker_execution()

    assert executor.hyperparameters["s3_uri_algorithm_definition"] == (
        "s3://bucket/output/train-job/hp-slug-algorithm_definition.json"
    )
    assert uploads["s3://bucket/output/train-job/hp-slug-algorithm_definition.json"] == {
        "fail_fast": True,
        "payload": {
            "algorithm_name": "demo-algo",
            "algorithm_version": "1.2.3",
            "hyperparameter_version": "hp-v1",
            "dependencies": ["child-model"],
            "training_lag_days": 7,
        },
    }
    assert uploads["s3://bucket/output/train-job/hp-slug/metadata/effective_algorithm_definition.json"] == {
        "fail_fast": False,
        "payload": {
            "algorithm_name": "demo-algo",
            "algorithm_version": "1.2.3",
            "hyperparameter_version": "hp-v1",
            "dependencies": ["child-model"],
            "training_lag_days": 7,
        },
    }
