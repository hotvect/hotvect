from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from hotvect.algorithm_definition_overrides import (
    apply_algorithm_definition_override,
    load_effective_algorithm_definition,
)
from hotvect.s3_utils import join_s3_uri
from hotvect.text_data_files import is_text_data_file, list_part_text_data_files, list_text_data_files

ALGO_DEF_S3_URI_HYPERPARAMETER = "s3_uri_algorithm_definition"
PREDICT_PARAMETERS_ZIP_HYPERPARAMETER = "s3_uri_predict_parameters_zip"
S3_URI_ALGORITHM_JAR_HYPERPARAMETER = "s3_uri_algorithm_jar"
S3_URI_PARAMETER_ZIP_HYPERPARAMETER = "s3_uri_parameter_zip"
S3_URI_METADATA_HYPERPARAMETER = "s3_uri_metadata"
S3_URI_RESULT_FILE_HYPERPARAMETER = "s3_uri_result_file"

HOTVECT_TASK_HYPERPARAMETER = "hotvect_task"
HOTVECT_SOURCE_CHANNEL_HYPERPARAMETER = "hotvect_source_channel"
HOTVECT_SOURCE_S3_URI_HYPERPARAMETER = "hotvect_source_s3_uri"
HOTVECT_TASK_OUTPUT_HYPERPARAMETER = "hotvect_task_output"
HOTVECT_PARALLEL_EXECUTION_HYPERPARAMETER = "hotvect_parallel_execution"
HOTVECT_ORDERED_HYPERPARAMETER = "hotvect_ordered"
HOTVECT_UNORDERED_HYPERPARAMETER = "hotvect_unordered"
HOTVECT_WRITER_NUM_SHARDS_HYPERPARAMETER = "hotvect_writer_num_shards"
HOTVECT_INCLUDE_FEATURE_STORE_RESPONSES_HYPERPARAMETER = "hotvect_include_feature_store_responses"
HOTVECT_MAX_THREADS_HYPERPARAMETER = "hotvect_max_threads"
HOTVECT_LOG_FEATURES_HYPERPARAMETER = "hotvect_log_features"
HOTVECT_SAMPLES_HYPERPARAMETER = "hotvect_samples"
HOTVECT_SAMPLE_POOL_SIZE_HYPERPARAMETER = "hotvect_sample_pool_size"
HOTVECT_TARGET_RPS_HYPERPARAMETER = "hotvect_target_rps"
HOTVECT_TARGET_THROUGHPUT_FRACTION_HYPERPARAMETER = "hotvect_target_throughput_fraction"
HOTVECT_WORKLOAD_MODE_HYPERPARAMETER = "hotvect_workload_mode"
HOTVECT_INSTANCE_TYPE_HYPERPARAMETER = "hotvect_instance_type"
HOTVECT_TRAINING_IMAGE_HYPERPARAMETER = "hotvect_training_image"
HOTVECT_SAGEMAKER_OUTPUT_S3_URI_HYPERPARAMETER = "hotvect_sagemaker_output_s3_uri"

PARALLEL_TEXT_PART_INDEX_WIDTH = 5


def _parse_dict_hyperparameter_value(value: Any, key: str) -> dict[str, Any]:
    if value in (None, ""):
        return {}
    if isinstance(value, dict):
        return value
    if isinstance(value, str):
        parsed = json.loads(value)
        if isinstance(parsed, dict):
            return parsed
    raise ValueError(f"{key} must be a JSON object, got {value!r}")


def _parse_optional_bool(value: Any) -> bool:
    return str(value).lower() in {"1", "true", "yes"} if value not in (None, "") else False


def _parse_optional_int(value: Any, key: str) -> int | None:
    if value in (None, ""):
        return None
    try:
        return int(value)
    except Exception as exc:
        raise ValueError(f"{key} must be an int, got {value!r}") from exc


def _parse_optional_float(value: Any, key: str) -> float | None:
    if value in (None, ""):
        return None
    try:
        return float(value)
    except Exception as exc:
        raise ValueError(f"{key} must be a float, got {value!r}") from exc


@dataclass(frozen=True)
class TaskOutputConfig:
    s3_uri: str
    compression: str = "none"

    def __post_init__(self) -> None:
        if not isinstance(self.s3_uri, str) or not self.s3_uri:
            raise ValueError("task output s3_uri must be a non-empty string")
        if self.compression not in {"none", "gzip"}:
            raise ValueError(f"Invalid compression: {self.compression!r}. Must be 'none' or 'gzip'")

    def to_hyperparameter_value(self) -> str:
        return json.dumps({"s3_uri": self.s3_uri, "compression": self.compression}, sort_keys=True)

    @classmethod
    def from_hyperparameter_value(cls, value: Any) -> TaskOutputConfig:
        cfg = _parse_dict_hyperparameter_value(value, HOTVECT_TASK_OUTPUT_HYPERPARAMETER)
        s3_uri = cfg.get("s3_uri")
        if not isinstance(s3_uri, str) or not s3_uri:
            raise ValueError(f"{HOTVECT_TASK_OUTPUT_HYPERPARAMETER}.s3_uri must be a non-empty string")
        compression = str(cfg.get("compression", "none"))
        return cls(s3_uri=s3_uri, compression=compression)


@dataclass(frozen=True)
class ParallelExecutionConfig:
    worker_count: int
    worker_index: int

    def __post_init__(self) -> None:
        if self.worker_count <= 1:
            raise ValueError(f"worker_count must be > 1, got {self.worker_count}")
        if self.worker_index < 0 or self.worker_index >= self.worker_count:
            raise ValueError(
                f"worker_index must be in [0, worker_count), got worker_index={self.worker_index}, "
                f"worker_count={self.worker_count}"
            )

    def to_hyperparameter_value(self) -> str:
        return json.dumps({"worker_count": self.worker_count, "worker_index": self.worker_index}, sort_keys=True)

    @classmethod
    def from_hyperparameter_value(cls, value: Any) -> ParallelExecutionConfig | None:
        cfg = _parse_dict_hyperparameter_value(value, HOTVECT_PARALLEL_EXECUTION_HYPERPARAMETER)
        if not cfg:
            return None
        worker_count = _parse_optional_int(
            cfg.get("worker_count"), f"{HOTVECT_PARALLEL_EXECUTION_HYPERPARAMETER}.worker_count"
        )
        worker_index = _parse_optional_int(
            cfg.get("worker_index"), f"{HOTVECT_PARALLEL_EXECUTION_HYPERPARAMETER}.worker_index"
        )
        if worker_count is None or worker_index is None:
            raise ValueError(f"{HOTVECT_PARALLEL_EXECUTION_HYPERPARAMETER} must include worker_count and worker_index")
        return cls(worker_count=worker_count, worker_index=worker_index)


@dataclass(frozen=True)
class OneShotSagemakerHyperparameters:
    task: str
    task_output: TaskOutputConfig
    source_channel: str = "source"
    source_s3_uri: str | None = None
    algorithm_jar_s3_uri: str | None = None
    algorithm_definition_s3_uri: str | None = None
    metadata_s3_uri: str | None = None
    result_file_s3_uri: str | None = None
    parameter_zip_s3_uri: str | None = None
    instance_type: str | None = None
    training_image: str | None = None
    sagemaker_output_s3_uri: str | None = None
    ordered: bool = False
    unordered: bool = False
    writer_num_shards: int | None = None
    include_feature_store_responses: bool = False
    max_threads: int | None = None
    log_features: bool = False
    samples: int | None = None
    sample_pool_size: int | None = None
    target_rps: float | None = None
    target_throughput_fraction: float | None = None
    workload_mode: str | None = None
    parallel_execution: ParallelExecutionConfig | None = None

    @classmethod
    def required_hyperparameter_keys(cls, *, require_runtime_artifacts: bool) -> list[str]:
        required = [HOTVECT_TASK_HYPERPARAMETER, HOTVECT_TASK_OUTPUT_HYPERPARAMETER]
        if require_runtime_artifacts:
            required.extend(
                [
                    S3_URI_ALGORITHM_JAR_HYPERPARAMETER,
                    ALGO_DEF_S3_URI_HYPERPARAMETER,
                    S3_URI_METADATA_HYPERPARAMETER,
                    S3_URI_RESULT_FILE_HYPERPARAMETER,
                ]
            )
        return required

    @classmethod
    def missing_hyperparameters(cls, hp: dict[str, Any], *, require_runtime_artifacts: bool) -> list[str]:
        return [
            key
            for key in cls.required_hyperparameter_keys(require_runtime_artifacts=require_runtime_artifacts)
            if hp.get(key) in (None, "")
        ]

    def to_hyperparameters(self) -> dict[str, str]:
        hp = {
            HOTVECT_TASK_HYPERPARAMETER: self.task,
            HOTVECT_SOURCE_CHANNEL_HYPERPARAMETER: self.source_channel,
            HOTVECT_TASK_OUTPUT_HYPERPARAMETER: self.task_output.to_hyperparameter_value(),
        }
        if self.source_s3_uri:
            hp[HOTVECT_SOURCE_S3_URI_HYPERPARAMETER] = self.source_s3_uri
        if self.algorithm_jar_s3_uri:
            hp[S3_URI_ALGORITHM_JAR_HYPERPARAMETER] = self.algorithm_jar_s3_uri
        if self.algorithm_definition_s3_uri:
            hp[ALGO_DEF_S3_URI_HYPERPARAMETER] = self.algorithm_definition_s3_uri
        if self.metadata_s3_uri:
            hp[S3_URI_METADATA_HYPERPARAMETER] = self.metadata_s3_uri
        if self.result_file_s3_uri:
            hp[S3_URI_RESULT_FILE_HYPERPARAMETER] = self.result_file_s3_uri
        if self.parameter_zip_s3_uri:
            hp[S3_URI_PARAMETER_ZIP_HYPERPARAMETER] = self.parameter_zip_s3_uri
        if self.instance_type:
            hp[HOTVECT_INSTANCE_TYPE_HYPERPARAMETER] = self.instance_type
        if self.training_image:
            hp[HOTVECT_TRAINING_IMAGE_HYPERPARAMETER] = self.training_image
        if self.sagemaker_output_s3_uri:
            hp[HOTVECT_SAGEMAKER_OUTPUT_S3_URI_HYPERPARAMETER] = self.sagemaker_output_s3_uri
        if self.ordered:
            hp[HOTVECT_ORDERED_HYPERPARAMETER] = "true"
        if self.unordered:
            hp[HOTVECT_UNORDERED_HYPERPARAMETER] = "true"
        if self.writer_num_shards is not None:
            hp[HOTVECT_WRITER_NUM_SHARDS_HYPERPARAMETER] = str(self.writer_num_shards)
        if self.include_feature_store_responses:
            hp[HOTVECT_INCLUDE_FEATURE_STORE_RESPONSES_HYPERPARAMETER] = "true"
        if self.max_threads is not None:
            hp[HOTVECT_MAX_THREADS_HYPERPARAMETER] = str(self.max_threads)
        if self.log_features:
            hp[HOTVECT_LOG_FEATURES_HYPERPARAMETER] = "true"
        if self.samples is not None:
            hp[HOTVECT_SAMPLES_HYPERPARAMETER] = str(self.samples)
        if self.sample_pool_size is not None:
            hp[HOTVECT_SAMPLE_POOL_SIZE_HYPERPARAMETER] = str(self.sample_pool_size)
        if self.target_rps is not None:
            hp[HOTVECT_TARGET_RPS_HYPERPARAMETER] = str(self.target_rps)
        if self.target_throughput_fraction is not None:
            hp[HOTVECT_TARGET_THROUGHPUT_FRACTION_HYPERPARAMETER] = str(self.target_throughput_fraction)
        if self.workload_mode is not None:
            hp[HOTVECT_WORKLOAD_MODE_HYPERPARAMETER] = str(self.workload_mode)
        if self.parallel_execution is not None:
            hp[HOTVECT_PARALLEL_EXECUTION_HYPERPARAMETER] = self.parallel_execution.to_hyperparameter_value()
        return hp

    @classmethod
    def from_hyperparameters(
        cls, hp: dict[str, Any], *, require_runtime_artifacts: bool = True
    ) -> OneShotSagemakerHyperparameters:
        missing = cls.missing_hyperparameters(hp, require_runtime_artifacts=require_runtime_artifacts)
        if missing:
            raise ValueError(
                "run_one_shot_from_sagemaker_env must be executed inside a SageMaker training container with the "
                f"required hyperparameters set. Missing: {', '.join(missing)}"
            )
        return cls(
            task=str(hp[HOTVECT_TASK_HYPERPARAMETER]),
            task_output=TaskOutputConfig.from_hyperparameter_value(hp[HOTVECT_TASK_OUTPUT_HYPERPARAMETER]),
            source_channel=str(hp.get(HOTVECT_SOURCE_CHANNEL_HYPERPARAMETER, "source")),
            source_s3_uri=hp.get(HOTVECT_SOURCE_S3_URI_HYPERPARAMETER),
            algorithm_jar_s3_uri=hp.get(S3_URI_ALGORITHM_JAR_HYPERPARAMETER),
            algorithm_definition_s3_uri=hp.get(ALGO_DEF_S3_URI_HYPERPARAMETER),
            metadata_s3_uri=hp.get(S3_URI_METADATA_HYPERPARAMETER),
            result_file_s3_uri=hp.get(S3_URI_RESULT_FILE_HYPERPARAMETER),
            parameter_zip_s3_uri=hp.get(S3_URI_PARAMETER_ZIP_HYPERPARAMETER),
            instance_type=hp.get(HOTVECT_INSTANCE_TYPE_HYPERPARAMETER),
            training_image=hp.get(HOTVECT_TRAINING_IMAGE_HYPERPARAMETER),
            sagemaker_output_s3_uri=hp.get(HOTVECT_SAGEMAKER_OUTPUT_S3_URI_HYPERPARAMETER),
            ordered=_parse_optional_bool(hp.get(HOTVECT_ORDERED_HYPERPARAMETER)),
            unordered=_parse_optional_bool(hp.get(HOTVECT_UNORDERED_HYPERPARAMETER)),
            writer_num_shards=_parse_optional_int(
                hp.get(HOTVECT_WRITER_NUM_SHARDS_HYPERPARAMETER),
                HOTVECT_WRITER_NUM_SHARDS_HYPERPARAMETER,
            ),
            include_feature_store_responses=_parse_optional_bool(
                hp.get(HOTVECT_INCLUDE_FEATURE_STORE_RESPONSES_HYPERPARAMETER)
            ),
            max_threads=_parse_optional_int(
                hp.get(HOTVECT_MAX_THREADS_HYPERPARAMETER),
                HOTVECT_MAX_THREADS_HYPERPARAMETER,
            ),
            log_features=_parse_optional_bool(hp.get(HOTVECT_LOG_FEATURES_HYPERPARAMETER)),
            samples=_parse_optional_int(hp.get(HOTVECT_SAMPLES_HYPERPARAMETER), HOTVECT_SAMPLES_HYPERPARAMETER),
            sample_pool_size=_parse_optional_int(
                hp.get(HOTVECT_SAMPLE_POOL_SIZE_HYPERPARAMETER),
                HOTVECT_SAMPLE_POOL_SIZE_HYPERPARAMETER,
            ),
            target_rps=_parse_optional_float(
                hp.get(HOTVECT_TARGET_RPS_HYPERPARAMETER), HOTVECT_TARGET_RPS_HYPERPARAMETER
            ),
            target_throughput_fraction=_parse_optional_float(
                hp.get(HOTVECT_TARGET_THROUGHPUT_FRACTION_HYPERPARAMETER),
                HOTVECT_TARGET_THROUGHPUT_FRACTION_HYPERPARAMETER,
            ),
            workload_mode=(
                str(hp[HOTVECT_WORKLOAD_MODE_HYPERPARAMETER])
                if hp.get(HOTVECT_WORKLOAD_MODE_HYPERPARAMETER) not in (None, "")
                else None
            ),
            parallel_execution=ParallelExecutionConfig.from_hyperparameter_value(
                hp.get(HOTVECT_PARALLEL_EXECUTION_HYPERPARAMETER)
            ),
        )


def build_parameter_pin_override(
    algorithm_definition: dict[str, Any],
    *,
    task: str,
    parameter_s3_uri: str | None,
) -> dict[str, Any] | None:
    if task not in {"predict", "audit", "performance-test"} or not parameter_s3_uri:
        return None

    existing = algorithm_definition.get("hotvect_execution_parameters", {}).get("with_parameter")
    if existing and existing != parameter_s3_uri:
        raise ValueError(
            "Conflicting with_parameter pin for one-shot task: "
            f"algorithm definition has {existing!r}, but --parameter-s3-uri is {parameter_s3_uri!r}"
        )
    return {"hotvect_execution_parameters": {"with_parameter": parameter_s3_uri}}


def build_one_shot_effective_algorithm_definition(
    *,
    algorithm_jar: str,
    algorithm_name: str,
    algorithm_override: str | None,
    task: str,
    parameter_s3_uri: str | None,
) -> dict[str, Any]:
    effective_definition = load_effective_algorithm_definition(algorithm_jar, algorithm_name, algorithm_override)
    parameter_pin_override = build_parameter_pin_override(
        effective_definition,
        task=task,
        parameter_s3_uri=parameter_s3_uri,
    )
    if not parameter_pin_override:
        return effective_definition
    return apply_algorithm_definition_override(effective_definition, parameter_pin_override)


def parallel_text_output_filename(worker_index: int, local_shard_index: int, compression: str) -> str:
    filename = (
        f"part-{worker_index:0{PARALLEL_TEXT_PART_INDEX_WIDTH}d}-"
        f"{local_shard_index:0{PARALLEL_TEXT_PART_INDEX_WIDTH}d}.jsonl"
    )
    if compression == "gzip":
        filename += ".gz"
    return filename


def parallel_text_output_uri(dest_path: str, worker_index: int, local_shard_index: int, compression: str) -> str:
    return join_s3_uri(dest_path, parallel_text_output_filename(worker_index, local_shard_index, compression))


def expected_output_descriptor(task: str, dest_path: str, shard_index: int, compression: str) -> dict[str, Any]:
    if task in {"predict", "audit"}:
        suffix = ".jsonl.gz" if compression == "gzip" else ".jsonl"
        return {
            "type": "part-files",
            "uri_prefix": dest_path,
            "filename_glob": f"part-{shard_index:0{PARALLEL_TEXT_PART_INDEX_WIDTH}d}-*{suffix}",
        }
    if task == "encode":
        shard_base = join_s3_uri(dest_path, f"part-{shard_index:0{PARALLEL_TEXT_PART_INDEX_WIDTH}d}")
        return {
            "type": "encode",
            "schema_uri": join_s3_uri(shard_base, "encoded-schema-description"),
            "encoded_prefix_uri": join_s3_uri(shard_base, "encoded") + "/",
        }
    raise ValueError(f"Unsupported task for expected outputs: {task}")


def find_text_outputs(base_path: Path) -> list[Path]:
    if base_path.is_file():
        return [base_path]
    if base_path.is_dir():
        return list_text_data_files(base_path)
    return []


def require_text_outputs(*candidate_paths: Path) -> list[Path]:
    for candidate in candidate_paths:
        outputs = find_text_outputs(candidate)
        if outputs:
            return outputs
    raise FileNotFoundError(
        "Expected text output under one of: " + ", ".join(str(candidate) for candidate in candidate_paths)
    )


def task_text_output_paths(task: str, output_dir: Path) -> list[Path]:
    if task == "predict":
        return require_text_outputs(output_dir / "prediction")
    if task == "audit":
        return require_text_outputs(output_dir / "audit", output_dir / "audit.jsonl")
    raise ValueError(f"Task {task} does not use text output part files")


def resolve_prediction_source_candidate(candidate: Path) -> Path | None:
    if is_text_data_file(candidate):
        return candidate
    if not candidate.is_dir():
        return None

    prediction_part_files = list_part_text_data_files(candidate)
    if prediction_part_files:
        return candidate

    text_files = list_text_data_files(candidate)
    if len(text_files) == 1:
        return text_files[0]
    return None


def resolve_evaluate_source_path(source_dir: Path) -> Path:
    candidates = [
        source_dir / "out" / "prediction",
        source_dir / "prediction",
        source_dir,
    ]
    for candidate in candidates:
        resolved = resolve_prediction_source_candidate(candidate)
        if resolved is not None:
            return resolved
    raise FileNotFoundError(
        "Could not locate prediction input under SageMaker source channel "
        f"{source_dir}. Expected out/prediction/, prediction/, root-level part files, or a single prediction file."
    )
