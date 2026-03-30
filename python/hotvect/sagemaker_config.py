import json
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Optional

from hotvect.utils import recursive_dict_update

DEFAULT_VOLUME_SIZE_IN_GB = 30
DEFAULT_MAX_RUNTIME_SECONDS = 86400
DEFAULT_TRAINING_INPUT_MODE = "FastFile"


@dataclass(frozen=True)
class SagemakerTemplateSource:
    path: Optional[Path]
    loaded_from: str  # "explicit" | "user_config" | "none"


def _home_hotvect_config_path() -> Path:
    return Path.home() / ".hotvect" / "config.json"


def load_user_config() -> Dict[str, Any]:
    cfg_path = _home_hotvect_config_path()
    if not cfg_path.exists():
        return {}
    return json.loads(cfg_path.read_text())


def resolve_template_path(explicit_template_path: Optional[str]) -> SagemakerTemplateSource:
    if explicit_template_path:
        return SagemakerTemplateSource(path=Path(os.path.expanduser(explicit_template_path)), loaded_from="explicit")

    cfg = load_user_config()
    template_path = (
        cfg.get("sagemaker", {}).get("sagemaker_config_template") if isinstance(cfg.get("sagemaker"), dict) else None
    )
    if template_path:
        return SagemakerTemplateSource(path=Path(os.path.expanduser(template_path)), loaded_from="user_config")

    return SagemakerTemplateSource(path=None, loaded_from="none")


def load_template(template_path: Path) -> Dict[str, Any]:
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
            "Shorten --sagemaker-job-prefix."
        )


def _ensure_dict(d: Dict[str, Any], key: str) -> Dict[str, Any]:
    v = d.get(key)
    if v is None:
        v = {}
        d[key] = v
    if not isinstance(v, dict):
        raise ValueError(f"Expected '{key}' to be an object/dict in SageMaker config, got {type(v)}")
    return v


def _ensure_list(d: Dict[str, Any], key: str) -> list:
    v = d.get(key)
    if v is None:
        v = []
        d[key] = v
    if not isinstance(v, list):
        raise ValueError(f"Expected '{key}' to be a list in SageMaker config, got {type(v)}")
    return v


def resolve_training_image(
    *,
    cli_training_image: Optional[str],
    algorithm_definition: Optional[Dict[str, Any]],
    template_job_def: Optional[Dict[str, Any]],
) -> Optional[str]:
    if cli_training_image:
        return cli_training_image
    if algorithm_definition and algorithm_definition.get("training_container"):
        return algorithm_definition["training_container"]
    if template_job_def:
        algo_spec = template_job_def.get("AlgorithmSpecification")
        if isinstance(algo_spec, dict) and algo_spec.get("TrainingImage"):
            return algo_spec["TrainingImage"]
    return None


def build_training_job_definition(
    *,
    template_job_def: Optional[Dict[str, Any]],
    training_job_name: str,
    role_arn: Optional[str],
    s3_output_base: Optional[str],
    instance_type: Optional[str],
    volume_gb: Optional[int],
    max_runtime_seconds: Optional[int],
    training_image: Optional[str],
    require_training_image: bool = True,
) -> Dict[str, Any]:
    job_def: Dict[str, Any] = json.loads(json.dumps(template_job_def)) if template_job_def else {}

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
            "Missing AlgorithmSpecification.TrainingImage. Provide via algo definition training_container, "
            "template, or --training-image."
        )
    if "TrainingInputMode" not in algo_spec:
        algo_spec["TrainingInputMode"] = DEFAULT_TRAINING_INPUT_MODE

    resource_cfg = _ensure_dict(job_def, "ResourceConfig")
    if instance_type:
        resource_cfg["InstanceType"] = instance_type
    if "InstanceType" not in resource_cfg:
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
    algorithm_definition_override: Optional[Dict[str, Any]],
    performance_test_samples: Optional[int],
) -> Optional[Dict[str, Any]]:
    if performance_test_samples is None:
        return algorithm_definition_override
    override = algorithm_definition_override.copy() if algorithm_definition_override else {}
    samples_update = {"hotvect_execution_parameters": {"performance-test": {"samples": int(performance_test_samples)}}}
    recursive_dict_update(override, samples_update)
    return override
