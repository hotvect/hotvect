import json
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from hotvect.algorithm_definition_overrides import merge_algorithm_definition_override_fragments

DEFAULT_VOLUME_SIZE_IN_GB = 30
DEFAULT_MAX_RUNTIME_SECONDS = 86400
DEFAULT_TRAINING_INPUT_MODE = "FastFile"
HOTVECT_SUBMISSION_OPTIONS_KEY = "HotvectSubmissionOptions"
HOTVECT_PREFERRED_INSTANCE_TYPES_KEY = "PreferredInstanceTypes"


@dataclass(frozen=True)
class SagemakerTemplateSource:
    path: Path | None
    loaded_from: str  # "explicit" | "user_config" | "none"


def _home_hotvect_config_path() -> Path:
    return Path.home() / ".hotvect" / "config.json"


def load_user_config() -> dict[str, Any]:
    cfg_path = _home_hotvect_config_path()
    if not cfg_path.exists():
        return {}
    return json.loads(cfg_path.read_text())


def resolve_template_path(explicit_template_path: str | None) -> SagemakerTemplateSource:
    if explicit_template_path:
        return SagemakerTemplateSource(path=Path(os.path.expanduser(explicit_template_path)), loaded_from="explicit")

    cfg = load_user_config()
    template_path = (
        cfg.get("sagemaker", {}).get("sagemaker_config_template") if isinstance(cfg.get("sagemaker"), dict) else None
    )
    if template_path:
        return SagemakerTemplateSource(path=Path(os.path.expanduser(template_path)), loaded_from="user_config")

    return SagemakerTemplateSource(path=None, loaded_from="none")


def load_template(template_path: Path) -> dict[str, Any]:
    return json.loads(template_path.read_text())


def validate_job_prefix(prefix: str) -> None:
    import re

    if not prefix:
        raise ValueError("--sagemaker-job-prefix is required")
    pat = re.compile(r"^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?$")
    if not pat.fullmatch(prefix):
        raise ValueError(
            "Invalid --sagemaker-job-prefix. Must match "
            "'^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?$' (letters/digits/hyphen; start/end alnum)."
        )


def validate_training_job_name_length(name: str) -> None:
    if len(name) > 63:
        raise ValueError(
            f"SageMaker TrainingJobName must be <= 63 characters, got {len(name)}: {name!r}. "
            "Shorten the effective SageMaker job prefix (for example --sagemaker-job-prefix "
            "or the template's TrainingJobName)."
        )


def _ensure_dict(d: dict[str, Any], key: str) -> dict[str, Any]:
    v = d.get(key)
    if v is None:
        v = {}
        d[key] = v
    if not isinstance(v, dict):
        raise ValueError(f"Expected '{key}' to be an object/dict in SageMaker config, got {type(v)}")
    return v


def _ensure_list(d: dict[str, Any], key: str) -> list:
    v = d.get(key)
    if v is None:
        v = []
        d[key] = v
    if not isinstance(v, list):
        raise ValueError(f"Expected '{key}' to be a list in SageMaker config, got {type(v)}")
    return v


def _recursive_dict_update(base: dict[str, Any], update: dict[str, Any]) -> None:
    for key, value in update.items():
        if isinstance(base.get(key), dict) and isinstance(value, dict):
            _recursive_dict_update(base[key], value)
        else:
            base[key] = value


def build_cli_sagemaker_job_overrides(
    *,
    role_arn: str | None,
    s3_output_base: str | None,
    instance_type: str | None,
    volume_gb: int | None,
    max_runtime_seconds: int | None,
    training_image: str | None,
) -> dict[str, Any]:
    overrides: dict[str, Any] = {}
    if role_arn:
        overrides["RoleArn"] = role_arn
    if s3_output_base:
        overrides.setdefault("OutputDataConfig", {})["S3OutputPath"] = s3_output_base
    if training_image:
        overrides.setdefault("AlgorithmSpecification", {})["TrainingImage"] = training_image
    if instance_type:
        overrides.setdefault("ResourceConfig", {})["InstanceType"] = instance_type
    if volume_gb is not None:
        overrides.setdefault("ResourceConfig", {})["VolumeSizeInGB"] = int(volume_gb)
    if max_runtime_seconds is not None:
        overrides.setdefault("StoppingCondition", {})["MaxRuntimeInSeconds"] = int(max_runtime_seconds)
    return overrides


def apply_cli_sagemaker_job_overrides(
    job_definition: dict[str, Any],
    *,
    role_arn: str | None,
    s3_output_base: str | None,
    instance_type: str | None,
    volume_gb: int | None,
    max_runtime_seconds: int | None,
    training_image: str | None,
) -> None:
    """
    Reapply explicit CLI SageMaker fields after algorithm/template merging.

    Precedence is template < algorithm definition/override < explicit CLI.
    This helper only includes values that were explicitly passed to the CLI.
    """
    _recursive_dict_update(
        job_definition,
        build_cli_sagemaker_job_overrides(
            role_arn=role_arn,
            s3_output_base=s3_output_base,
            instance_type=instance_type,
            volume_gb=volume_gb,
            max_runtime_seconds=max_runtime_seconds,
            training_image=training_image,
        ),
    )


def resolve_training_image(
    *,
    cli_training_image: str | None,
    algorithm_definition: dict[str, Any] | None,
    template_job_def: dict[str, Any] | None,
) -> str | None:
    if cli_training_image:
        return cli_training_image
    if algorithm_definition:
        algorithm_job_def = algorithm_definition.get("sagemaker_training_job_definition")
        if isinstance(algorithm_job_def, dict):
            algo_spec = algorithm_job_def.get("AlgorithmSpecification")
            if isinstance(algo_spec, dict) and algo_spec.get("TrainingImage"):
                return algo_spec["TrainingImage"]
        if algorithm_definition.get("training_container"):
            return algorithm_definition["training_container"]
    if template_job_def:
        algo_spec = template_job_def.get("AlgorithmSpecification")
        if isinstance(algo_spec, dict) and algo_spec.get("TrainingImage"):
            return algo_spec["TrainingImage"]
    return None


def build_training_job_definition(
    *,
    template_job_def: dict[str, Any] | None,
    training_job_name: str,
    role_arn: str | None,
    s3_output_base: str | None,
    instance_type: str | None,
    volume_gb: int | None,
    max_runtime_seconds: int | None,
    training_image: str | None,
    require_training_image: bool = True,
) -> dict[str, Any]:
    job_def: dict[str, Any] = json.loads(json.dumps(template_job_def)) if template_job_def else {}

    job_def["TrainingJobName"] = training_job_name

    if role_arn:
        job_def["RoleArn"] = role_arn
    if "RoleArn" not in job_def:
        raise ValueError("Missing RoleArn for SageMaker job. Provide via template or --role-arn.")

    output_cfg = _ensure_dict(job_def, "OutputDataConfig")
    if s3_output_base:
        output_cfg["S3OutputPath"] = s3_output_base
    if "S3OutputPath" not in output_cfg:
        raise ValueError("Missing OutputDataConfig.S3OutputPath. Provide via template or --s3-output-base.")

    algo_spec = _ensure_dict(job_def, "AlgorithmSpecification")
    if training_image:
        algo_spec["TrainingImage"] = training_image
    if require_training_image and "TrainingImage" not in algo_spec:
        raise ValueError(
            "Missing AlgorithmSpecification.TrainingImage. Provide via "
            "sagemaker_training_job_definition.AlgorithmSpecification.TrainingImage, --training-image, "
            "or legacy training_container."
        )
    if "TrainingInputMode" not in algo_spec:
        algo_spec["TrainingInputMode"] = DEFAULT_TRAINING_INPUT_MODE

    resource_cfg = _ensure_dict(job_def, "ResourceConfig")
    if instance_type:
        resource_cfg["InstanceType"] = instance_type
    submission_options = job_def.get(HOTVECT_SUBMISSION_OPTIONS_KEY)
    has_preferred_instance_types = isinstance(submission_options, dict) and bool(
        submission_options.get(HOTVECT_PREFERRED_INSTANCE_TYPES_KEY)
    )
    if "InstanceType" not in resource_cfg and not has_preferred_instance_types:
        raise ValueError("Missing ResourceConfig.InstanceType. Provide via template or --instance-type.")
    if "InstanceCount" not in resource_cfg:
        resource_cfg["InstanceCount"] = 1
    if volume_gb is not None:
        resource_cfg["VolumeSizeInGB"] = int(volume_gb)
    elif "VolumeSizeInGB" not in resource_cfg:
        resource_cfg["VolumeSizeInGB"] = DEFAULT_VOLUME_SIZE_IN_GB

    stopping = _ensure_dict(job_def, "StoppingCondition")
    if max_runtime_seconds is not None:
        stopping["MaxRuntimeInSeconds"] = int(max_runtime_seconds)
    elif "MaxRuntimeInSeconds" not in stopping:
        stopping["MaxRuntimeInSeconds"] = DEFAULT_MAX_RUNTIME_SECONDS

    _ensure_dict(job_def, "HyperParameters")
    _ensure_list(job_def, "InputDataConfig")

    return job_def


def apply_performance_test_samples_override(
    algorithm_definition_override: dict[str, Any] | None,
    performance_test_samples: int | None,
) -> dict[str, Any] | None:
    if performance_test_samples is None:
        return algorithm_definition_override
    samples_update = {"hotvect_execution_parameters": {"performance-test": {"samples": int(performance_test_samples)}}}
    return merge_algorithm_definition_override_fragments(algorithm_definition_override, samples_update)


def apply_performance_test_sample_pool_size_override(
    algorithm_definition_override: dict[str, Any] | None,
    performance_test_sample_pool_size: int | None,
) -> dict[str, Any] | None:
    if performance_test_sample_pool_size is None:
        return algorithm_definition_override
    sample_pool_size_update = {
        "hotvect_execution_parameters": {
            "performance-test": {"sample_pool_size": int(performance_test_sample_pool_size)}
        }
    }
    return merge_algorithm_definition_override_fragments(algorithm_definition_override, sample_pool_size_update)
