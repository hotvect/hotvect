import pytest

from hotvect.sagemaker_config import (
    DEFAULT_MAX_RUNTIME_SECONDS,
    DEFAULT_TRAINING_INPUT_MODE,
    DEFAULT_VOLUME_SIZE_IN_GB,
    build_training_job_definition,
    validate_job_prefix,
)
from hotvect.sagemaker_job_name import compute_hph
from hotvect.utils import hexigest_as_alphanumeric


def test_validate_job_prefix_rejects_invalid():
    with pytest.raises(ValueError):
        validate_job_prefix("bad prefix")
    with pytest.raises(ValueError):
        validate_job_prefix("_bad")
    with pytest.raises(ValueError):
        validate_job_prefix("bad-")


def test_build_training_job_definition_defaults_volume_and_runtime():
    template = {
        "TrainingJobName": "ignored",
        "RoleArn": "arn:aws:iam::123456789012:role/example-role",
        "AlgorithmSpecification": {"TrainingImage": "img:latest"},
        "OutputDataConfig": {"S3OutputPath": "s3://bucket/out"},
        "ResourceConfig": {"InstanceType": "ml.m5.xlarge"},
    }
    job_def = build_training_job_definition(
        template_job_def=template,
        training_job_name="jobname",
        role_arn=None,
        s3_output_base=None,
        instance_type=None,
        volume_gb=None,
        max_runtime_seconds=None,
        training_image=None,
    )
    assert job_def["TrainingJobName"] == "jobname"
    assert job_def["ResourceConfig"]["VolumeSizeInGB"] == DEFAULT_VOLUME_SIZE_IN_GB
    assert job_def["StoppingCondition"]["MaxRuntimeInSeconds"] == DEFAULT_MAX_RUNTIME_SECONDS
    assert job_def["AlgorithmSpecification"]["TrainingInputMode"] == DEFAULT_TRAINING_INPUT_MODE
    assert job_def["ResourceConfig"]["InstanceCount"] == 1


def test_compute_hph_matches_backtest_algorithm_for_empty_string():
    # Backtest uses: md5(hyperparam_string).hexdigest()[:6] then hexigest_as_alphanumeric(...)
    expected = hexigest_as_alphanumeric(__import__("hashlib").md5("".encode("utf-8")).hexdigest()[:6])  # noqa: S324
    assert compute_hph("") == expected
