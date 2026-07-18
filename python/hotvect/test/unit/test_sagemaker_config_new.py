import datetime

import pytest

from hotvect.sagemaker_config import (
    DEFAULT_MAX_RUNTIME_SECONDS,
    DEFAULT_TRAINING_INPUT_MODE,
    DEFAULT_VOLUME_SIZE_IN_GB,
    apply_cli_sagemaker_job_overrides,
    build_training_job_definition,
    resolve_training_image,
    validate_job_prefix,
)
from hotvect.sagemaker_job_name import build_backtest_training_job_name, compute_hph
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
        "RoleArn": "arn:aws:iam::123:role/x",
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


def test_build_training_job_definition_allows_preferred_instance_types_without_scalar_instance_type():
    template = {
        "TrainingJobName": "ignored",
        "RoleArn": "arn:aws:iam::123:role/x",
        "AlgorithmSpecification": {"TrainingImage": "img:latest"},
        "OutputDataConfig": {"S3OutputPath": "s3://bucket/out"},
        "ResourceConfig": {},
        "HotvectSubmissionOptions": {"PreferredInstanceTypes": ["ml.m5.large", "ml.m5.xlarge"]},
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
    assert "InstanceType" not in job_def["ResourceConfig"]
    assert job_def["HotvectSubmissionOptions"]["PreferredInstanceTypes"] == ["ml.m5.large", "ml.m5.xlarge"]
    assert job_def["ResourceConfig"]["InstanceCount"] == 1


def test_build_training_job_definition_still_requires_instance_type_without_preferred_instance_types():
    template = {
        "TrainingJobName": "ignored",
        "RoleArn": "arn:aws:iam::123:role/x",
        "AlgorithmSpecification": {"TrainingImage": "img:latest"},
        "OutputDataConfig": {"S3OutputPath": "s3://bucket/out"},
        "ResourceConfig": {},
    }
    with pytest.raises(ValueError, match="Missing ResourceConfig.InstanceType"):
        build_training_job_definition(
            template_job_def=template,
            training_job_name="jobname",
            role_arn=None,
            s3_output_base=None,
            instance_type=None,
            volume_gb=None,
            max_runtime_seconds=None,
            training_image=None,
        )


def test_apply_cli_sagemaker_job_overrides_only_reapplies_explicit_cli_fields():
    job_def = {
        "RoleArn": "arn:aws:iam::123:role/template",
        "OutputDataConfig": {"S3OutputPath": "s3://template/out"},
        "AlgorithmSpecification": {"TrainingImage": "algo-image", "TrainingInputMode": "FastFile"},
        "ResourceConfig": {"InstanceType": "ml.m6i.4xlarge", "VolumeSizeInGB": 64},
        "StoppingCondition": {"MaxRuntimeInSeconds": 7200, "MaxWaitTimeInSeconds": 172800},
    }

    apply_cli_sagemaker_job_overrides(
        job_def,
        role_arn=None,
        s3_output_base="s3://cli/out",
        instance_type="ml.c7i.2xlarge",
        volume_gb=None,
        max_runtime_seconds=3600,
        training_image="cli-image",
    )

    assert job_def["RoleArn"] == "arn:aws:iam::123:role/template"
    assert job_def["OutputDataConfig"]["S3OutputPath"] == "s3://cli/out"
    assert job_def["AlgorithmSpecification"]["TrainingImage"] == "cli-image"
    assert job_def["ResourceConfig"]["InstanceType"] == "ml.c7i.2xlarge"
    assert job_def["ResourceConfig"]["VolumeSizeInGB"] == 64
    assert job_def["StoppingCondition"]["MaxRuntimeInSeconds"] == 3600
    assert job_def["StoppingCondition"]["MaxWaitTimeInSeconds"] == 172800


def test_resolve_training_image_prefers_native_algorithm_job_definition_over_legacy_training_container():
    image = resolve_training_image(
        cli_training_image=None,
        algorithm_definition={
            "training_container": "legacy-training-container",
            "sagemaker_training_job_definition": {"AlgorithmSpecification": {"TrainingImage": "native-training-image"}},
        },
        template_job_def={"AlgorithmSpecification": {"TrainingImage": "template-training-image"}},
    )

    assert image == "native-training-image"


def test_resolve_training_image_keeps_legacy_training_container_as_fallback():
    image = resolve_training_image(
        cli_training_image=None,
        algorithm_definition={"training_container": "legacy-training-container"},
        template_job_def={"AlgorithmSpecification": {"TrainingImage": "template-training-image"}},
    )

    assert image == "legacy-training-container"


def test_resolve_training_image_cli_wins_over_algorithm_native_image():
    image = resolve_training_image(
        cli_training_image="cli-training-image",
        algorithm_definition={
            "sagemaker_training_job_definition": {"AlgorithmSpecification": {"TrainingImage": "native-training-image"}}
        },
        template_job_def=None,
    )

    assert image == "cli-training-image"


def test_compute_hph_matches_backtest_algorithm_for_empty_string():
    # Backtest uses: md5(hyperparam_string).hexdigest()[:6] then hexigest_as_alphanumeric(...)
    expected = hexigest_as_alphanumeric(__import__("hashlib").md5(b"").hexdigest()[:6])  # noqa: S324
    assert compute_hph("") == expected


def test_build_backtest_training_job_name_matches_backtest_format():
    name = build_backtest_training_job_name(
        prefix="backtest-a1b2c3d4",
        git_commit_hash="abcdef1234567890",
        hyperparameter_version="hp-v1",
        last_test_day=datetime.date(2026, 3, 26),
    )
    assert name == f"backtest-a1b2c3d4-abcdef-{compute_hph('hp-v1')}-2026-03-26"


def test_build_backtest_training_job_name_rejects_overlong_name():
    with pytest.raises(ValueError, match="SageMaker TrainingJobName must be <= 63 characters"):
        build_backtest_training_job_name(
            prefix="a" * 50,
            git_commit_hash="abcdef1234567890",
            hyperparameter_version="hp-v1",
            last_test_day=datetime.date(2026, 3, 26),
        )
