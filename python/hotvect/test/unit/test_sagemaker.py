import json
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest
from botocore.exceptions import ClientError

from hotvect.sagemaker import (
    ALGO_DEF_S3_URI_HYPERPARAMETER,
    ALGORITHM_DEFINITION_S3_URI_HYPERPARAMETER,
    HOTVECT_PREFERRED_INSTANCE_TYPES_KEY,
    HOTVECT_SUBMISSION_OPTIONS_KEY,
    SAGEMAKER_TAR_INCLUDE_METADATA_ENV_VARIABLE,
    SAGEMAKER_TAR_INCLUDE_OUTPUT_ENV_VARIABLE,
    SagemakerAlgorithmPipelineRebuilder,
    SagemakerTrainingExecutor,
)


def test_algorithm_definition_s3_uri_hyperparameter_backcompat_alias():
    assert ALGORITHM_DEFINITION_S3_URI_HYPERPARAMETER == ALGO_DEF_S3_URI_HYPERPARAMETER


def test_sagemaker_training_executor_retries_with_fallback_instance_types(monkeypatch):
    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor.algorithm_pipeline = SimpleNamespace()
    executor.training_job_definition = {
        "TrainingJobName": "job-name",
        "ResourceConfig": {"InstanceType": "ml.m5.12xlarge"},
    }
    executor._instance_type_fallbacks = ["ml.r7i.8xlarge", "ml.c7i.8xlarge"]
    monkeypatch.setattr(executor, "_upload_effective_training_job_definition", MagicMock())

    attempted_instance_types = []

    def _create_training_job(**kwargs):
        attempted_instance_types.append(kwargs["ResourceConfig"]["InstanceType"])
        if len(attempted_instance_types) == 1:
            raise ClientError(
                {"Error": {"Code": "ResourceLimitExceeded", "Message": "quota full"}}, "CreateTrainingJob"
            )
        return {"TrainingJobArn": "arn:aws:sagemaker:eu-central-1:123:training-job/job-name"}

    create_training_job = MagicMock(side_effect=_create_training_job)
    executor._sagemaker_client = SimpleNamespace(create_training_job=create_training_job)

    result = executor._create_training_job_with_instance_fallbacks()

    assert result["TrainingJobArn"].endswith("/job-name")
    assert attempted_instance_types == ["ml.m5.12xlarge", "ml.r7i.8xlarge"]
    assert executor.training_job_definition["ResourceConfig"]["InstanceType"] == "ml.r7i.8xlarge"
    executor._upload_effective_training_job_definition.assert_called_once_with()


def test_sagemaker_training_executor_does_not_fallback_on_other_client_errors(monkeypatch):
    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor.algorithm_pipeline = SimpleNamespace()
    executor.training_job_definition = {
        "TrainingJobName": "job-name",
        "ResourceConfig": {"InstanceType": "ml.m5.12xlarge"},
    }
    executor._instance_type_fallbacks = ["ml.r7i.8xlarge"]
    monkeypatch.setattr(executor, "_upload_effective_training_job_definition", MagicMock())

    create_training_job = MagicMock(
        side_effect=ClientError(
            {"Error": {"Code": "ValidationException", "Message": "bad request"}}, "CreateTrainingJob"
        )
    )
    executor._sagemaker_client = SimpleNamespace(create_training_job=create_training_job)

    with pytest.raises(ClientError, match="ValidationException"):
        executor._create_training_job_with_instance_fallbacks()

    assert create_training_job.call_count == 1
    executor._upload_effective_training_job_definition.assert_not_called()


def test_sagemaker_training_executor_materializes_primary_from_preferred_instance_types():
    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor.training_job_definition = {
        HOTVECT_SUBMISSION_OPTIONS_KEY: {
            HOTVECT_PREFERRED_INSTANCE_TYPES_KEY: ["ml.m5.12xlarge", "ml.r7i.8xlarge", "ml.c7i.8xlarge"]
        },
    }

    fallbacks = executor._normalize_instance_type_preferences()

    assert HOTVECT_SUBMISSION_OPTIONS_KEY not in executor.training_job_definition
    assert executor.training_job_definition["ResourceConfig"]["InstanceType"] == "ml.m5.12xlarge"
    assert fallbacks == ["ml.r7i.8xlarge", "ml.c7i.8xlarge"]


def test_sagemaker_training_executor_requires_matching_primary_for_preferred_instance_types():
    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor.training_job_definition = {
        "ResourceConfig": {"InstanceType": "ml.c7i.8xlarge"},
        HOTVECT_SUBMISSION_OPTIONS_KEY: {HOTVECT_PREFERRED_INSTANCE_TYPES_KEY: ["ml.m5.12xlarge", "ml.r7i.8xlarge"]},
    }

    with pytest.raises(ValueError, match="ResourceConfig.InstanceType must match"):
        executor._normalize_instance_type_preferences()


@pytest.mark.parametrize(
    "submission_options",
    [
        {"InstanceTypeFallbacks": ["ml.c7i.8xlarge"]},
        {
            HOTVECT_PREFERRED_INSTANCE_TYPES_KEY: ["ml.m5.12xlarge", "ml.r7i.8xlarge"],
            "InstanceTypeFallbacks": ["ml.c7i.8xlarge"],
        },
    ],
)
def test_sagemaker_training_executor_rejects_unexpected_submission_options_keys(submission_options):
    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor.training_job_definition = {
        "ResourceConfig": {"InstanceType": "ml.m5.12xlarge"},
        HOTVECT_SUBMISSION_OPTIONS_KEY: submission_options,
    }

    with pytest.raises(ValueError, match="only supports 'PreferredInstanceTypes'"):
        executor._normalize_instance_type_preferences()


@pytest.mark.parametrize(
    ("env_variable", "method_name"),
    [
        (SAGEMAKER_TAR_INCLUDE_METADATA_ENV_VARIABLE, "_get_metadata_dir"),
        (SAGEMAKER_TAR_INCLUDE_OUTPUT_ENV_VARIABLE, "_get_output_dir"),
    ],
)
def test_rebuilder_uses_new_tar_include_env_vars(monkeypatch, env_variable, method_name):
    rebuilder = SagemakerAlgorithmPipelineRebuilder.__new__(SagemakerAlgorithmPipelineRebuilder)
    rebuilder.sagemaker_env = SimpleNamespace(output_data_dir="/tmp/sm-output")
    monkeypatch.setenv(env_variable, "true")

    assert getattr(rebuilder, method_name)() == "/tmp/sm-output"


@pytest.mark.parametrize(
    ("legacy_env_variable", "method_name", "expected"),
    [
        ("WRITE_METADATA_TO_S3", "_get_metadata_dir", "/tmp/"),
        ("WRITE_OUTPUT_TO_S3", "_get_output_dir", "/tmp/output"),
    ],
)
def test_rebuilder_ignores_legacy_tar_include_env_vars(monkeypatch, legacy_env_variable, method_name, expected):
    rebuilder = SagemakerAlgorithmPipelineRebuilder.__new__(SagemakerAlgorithmPipelineRebuilder)
    rebuilder.sagemaker_env = SimpleNamespace(output_data_dir="/tmp/sm-output")
    monkeypatch.setenv(legacy_env_variable, "true")

    assert getattr(rebuilder, method_name)() == expected


def test_rebuilder_adds_sagemaker_metadata_to_result_json(tmp_path):
    metadata_dir = tmp_path / "meta"
    metadata_dir.mkdir()
    result_path = metadata_dir / "result.json"
    result_path.write_text(json.dumps({"evaluate": {"roc_auc": 0.8}}))

    rebuilder = SagemakerAlgorithmPipelineRebuilder.__new__(SagemakerAlgorithmPipelineRebuilder)
    rebuilder.algorithm_pipeline = SimpleNamespace(metadata_path=lambda: str(metadata_dir))
    rebuilder.sagemaker_env = SimpleNamespace(
        job_name="bt-demo-job",
        hyperparameters={"s3_uri_result_file": "s3://bucket/jobs/bt-demo-job/result.json"},
    )

    rebuilder._add_sagemaker_metadata_to_result_file()

    result = json.loads(result_path.read_text())
    assert result["evaluate"] == {"roc_auc": 0.8}
    assert result["sagemaker_training_job_name"] == "bt-demo-job"
    assert result["s3_uri_result_file"] == "s3://bucket/jobs/bt-demo-job/result.json"
