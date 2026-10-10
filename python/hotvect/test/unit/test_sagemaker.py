import copy
import json
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest
from botocore.exceptions import ClientError

from hotvect.offline_source_manifest import OFFLINE_SOURCE_MANIFEST_HYPERPARAMETER
from hotvect.sagemaker import (
    ALGO_DEF_S3_URI_HYPERPARAMETER,
    ALGORITHM_DEFINITION_S3_URI_HYPERPARAMETER,
    HOTVECT_PREFERRED_INSTANCE_TYPES_KEY,
    HOTVECT_SUBMISSION_OPTIONS_KEY,
    SAGEMAKER_TAR_INCLUDE_METADATA_ENV_VARIABLE,
    SAGEMAKER_TAR_INCLUDE_OUTPUT_ENV_VARIABLE,
    OneShotSagemakerExecutor,
    SagemakerAlgorithmPipelineRebuilder,
    SagemakerTrainingExecutor,
)
from hotvect.sagemaker_contracts import HOTVECT_INSTANCE_TYPE_HYPERPARAMETER, OneShotSagemakerHyperparameters


def test_algorithm_definition_s3_uri_hyperparameter_backcompat_alias():
    assert ALGORITHM_DEFINITION_S3_URI_HYPERPARAMETER == ALGO_DEF_S3_URI_HYPERPARAMETER


def _one_shot_executor(create_training_job, *, instance_type_fallbacks, include_benchmark_provenance=True):
    executor = OneShotSagemakerExecutor.__new__(OneShotSagemakerExecutor)
    hyperparameters = {
        "hotvect_task": "performance-test",
        "hotvect_task_output": json.dumps({"s3_uri": "s3://bucket/task-output", "compression": "none"}),
        "s3_uri_metadata": "s3://bucket/job/performance-test/metadata",
        "s3_uri_result_file": "s3://bucket/job/performance-test/result.json",
        OFFLINE_SOURCE_MANIFEST_HYPERPARAMETER: "s3://bucket/job/performance-test/offline-source/manifest.json",
    }
    if include_benchmark_provenance:
        hyperparameters[HOTVECT_INSTANCE_TYPE_HYPERPARAMETER] = "ml.m5.12xlarge"
    executor.training_job_definition = {
        "TrainingJobName": "job-name",
        "ResourceConfig": {"InstanceType": "ml.m5.12xlarge"},
        "HyperParameters": hyperparameters,
    }
    executor._instance_type_fallbacks = instance_type_fallbacks
    executor._s3_client = SimpleNamespace()
    executor._sagemaker_client = SimpleNamespace(create_training_job=create_training_job)
    return executor


def _capture_effective_training_job_definition_uploads(monkeypatch, events=None):
    uploads = []

    def _capture(payload, s3_target_uri, s3_client, **kwargs):
        uploads.append((copy.deepcopy(payload), s3_target_uri, s3_client, kwargs))
        if events is not None:
            events.append(("upload", payload["ResourceConfig"]["InstanceType"]))

    monkeypatch.setattr("hotvect.sagemaker._upload_json_to_s3", _capture)
    return uploads


def test_one_shot_sagemaker_executor_retries_with_effective_definition_and_benchmark_provenance(monkeypatch):
    events = []
    uploads = _capture_effective_training_job_definition_uploads(monkeypatch, events)
    submissions = []

    def _create_training_job(**job_definition):
        submissions.append(copy.deepcopy(job_definition))
        events.append(("submit", job_definition["ResourceConfig"]["InstanceType"]))
        if len(submissions) == 1:
            raise ClientError(
                {"Error": {"Code": "ResourceLimitExceeded", "Message": "quota full"}}, "CreateTrainingJob"
            )
        return {"TrainingJobArn": "arn:aws:sagemaker:eu-central-1:123:training-job/job-name"}

    executor = _one_shot_executor(_create_training_job, instance_type_fallbacks=["ml.r7i.8xlarge"])

    result = executor.run()

    assert result["TrainingJobArn"].endswith("/job-name")
    assert [submission["ResourceConfig"]["InstanceType"] for submission in submissions] == [
        "ml.m5.12xlarge",
        "ml.r7i.8xlarge",
    ]
    assert [submission["HyperParameters"][HOTVECT_INSTANCE_TYPE_HYPERPARAMETER] for submission in submissions] == [
        "ml.m5.12xlarge",
        "ml.r7i.8xlarge",
    ]
    assert [upload[1] for upload in uploads] == [
        "s3://bucket/job/performance-test/metadata/effective_training_job_definition.json",
        "s3://bucket/job/performance-test/metadata/effective_training_job_definition.json",
    ]
    assert [upload[0]["ResourceConfig"]["InstanceType"] for upload in uploads] == [
        "ml.m5.12xlarge",
        "ml.r7i.8xlarge",
    ]
    assert events == [
        ("upload", "ml.m5.12xlarge"),
        ("submit", "ml.m5.12xlarge"),
        ("upload", "ml.r7i.8xlarge"),
        ("submit", "ml.r7i.8xlarge"),
    ]
    assert all(upload[2] is executor._s3_client for upload in uploads)
    assert all(upload[3] == {"fail_fast": True, "default": str} for upload in uploads)
    assert executor.training_job_definition["ResourceConfig"]["InstanceType"] == "ml.r7i.8xlarge"
    assert executor.hyperparameters[HOTVECT_INSTANCE_TYPE_HYPERPARAMETER] == "ml.r7i.8xlarge"

    from hotvect.sagemaker_tasks import _build_one_shot_benchmark_contract

    request_hp = OneShotSagemakerHyperparameters.from_hyperparameters(executor.hyperparameters)
    benchmark_contract = _build_one_shot_benchmark_contract(
        request_hp=request_hp,
        task_metadata={},
        s3_uri_metadata=executor.hyperparameters["s3_uri_metadata"],
        s3_uri_result_file=executor.hyperparameters["s3_uri_result_file"],
        task_output_s3_uri="s3://bucket/task-output",
    )
    assert benchmark_contract["instance_type"] == "ml.r7i.8xlarge"


def test_one_shot_sagemaker_executor_preserves_final_capacity_error(monkeypatch):
    uploads = _capture_effective_training_job_definition_uploads(monkeypatch)
    submissions = []
    errors = [
        ClientError({"Error": {"Code": "ResourceLimitExceeded", "Message": instance_type}}, "CreateTrainingJob")
        for instance_type in ["ml.m5.12xlarge", "ml.r7i.8xlarge", "ml.c7i.8xlarge"]
    ]

    def _create_training_job(**job_definition):
        submissions.append(copy.deepcopy(job_definition))
        raise errors[len(submissions) - 1]

    executor = _one_shot_executor(
        _create_training_job,
        instance_type_fallbacks=["ml.r7i.8xlarge", "ml.c7i.8xlarge"],
    )

    with pytest.raises(ClientError) as raised:
        executor.run()

    assert raised.value is errors[-1]
    assert [submission["ResourceConfig"]["InstanceType"] for submission in submissions] == [
        "ml.m5.12xlarge",
        "ml.r7i.8xlarge",
        "ml.c7i.8xlarge",
    ]
    assert len(uploads) == 3


def test_one_shot_sagemaker_executor_does_not_retry_non_capacity_errors(monkeypatch):
    uploads = _capture_effective_training_job_definition_uploads(monkeypatch)
    submissions = []
    validation_error = ClientError(
        {"Error": {"Code": "ValidationException", "Message": "bad request"}}, "CreateTrainingJob"
    )

    def _create_training_job(**job_definition):
        submissions.append(copy.deepcopy(job_definition))
        raise validation_error

    executor = _one_shot_executor(
        _create_training_job,
        instance_type_fallbacks=["ml.r7i.8xlarge"],
        include_benchmark_provenance=False,
    )

    with pytest.raises(ClientError) as raised:
        executor.run()

    assert raised.value is validation_error
    assert [submission["ResourceConfig"]["InstanceType"] for submission in submissions] == ["ml.m5.12xlarge"]
    assert HOTVECT_INSTANCE_TYPE_HYPERPARAMETER not in executor.hyperparameters
    assert len(uploads) == 1


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
