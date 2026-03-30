import json
import zipfile
from pathlib import Path
from types import SimpleNamespace

import pytest

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
    executor.training_job_definition = {"AlgorithmSpecification": {"TrainingImage": "repo/hotvect:9.34.0"}}
    executor.algorithm_pipeline = SimpleNamespace(
        algorithm_name=algo_name,
        algorithm_definition=effective_def,
        algorithm_jar_path=lambda: jar,
    )

    with pytest.raises(ValueError, match=r"legacy hotvect image"):
        executor._fail_if_algorithm_overrides_would_be_ignored()


def test_override_guard_allows_v10_semver_image_when_effective_definition_differs(tmp_path: Path) -> None:
    algo_name = "my-algo"
    jar_def = {"algorithm_name": algo_name, "train_data_prefix": "train_a"}
    effective_def = {"algorithm_name": algo_name, "train_data_prefix": "train_b"}

    jar = tmp_path / "algo.jar"
    _write_test_jar(jar, algo_name=algo_name, algo_def=jar_def)

    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor.training_job_definition = {"AlgorithmSpecification": {"TrainingImage": "repo/hotvect:10.11.0"}}
    executor.algorithm_pipeline = SimpleNamespace(
        algorithm_name=algo_name,
        algorithm_definition=effective_def,
        algorithm_jar_path=lambda: jar,
    )

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
