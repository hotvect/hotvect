import copy
import glob
import json
import logging
import os
import re
import secrets
import sys
import tarfile
import tempfile
import time
import uuid
from collections.abc import Callable
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any, NamedTuple
from urllib.parse import urlparse

import boto3
import psutil
from mypy_boto3_s3 import S3Client
from pebble import ProcessFuture, ProcessPool

from hotvect import utils
from hotvect.algorithm_definition_overrides import (
    apply_algorithm_definition_override,
    merge_algorithm_definition_override_fragments,
)
from hotvect.build_utils import clone_and_build_algorithm_jar
from hotvect.jvm_args import normalize_pipeline_jvm_options
from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext, DataDependency
from hotvect.sagemaker_config import (
    HOTVECT_PREFERRED_INSTANCE_TYPES_KEY,
    HOTVECT_SUBMISSION_OPTIONS_KEY,
    validate_training_job_name_length,
)
from hotvect.sagemaker_job_name import build_backtest_training_job_name, generate_runid
from hotvect.utils import (
    AlgorithmSpec,
    ConcurrencySetting,
    DirectProcessPool,
    get_boto_session_after_assuming_role,
    hexigest_as_alphanumeric,
    read_algorithm_definition_from_jar,
    read_json,
    recursive_dict_update,
    resolve_path_within_base,
    safe_extract_tar_archive,
    trydelete,
    write_data,
)

logger = logging.getLogger(__name__)


def resolve_dependency_s3_uri(
    dependency: DataDependency,
    *,
    auto_attach_data_environment: str,
    auto_attach_data_default_s3_base: str | None,
) -> str | None:
    additional_props = dependency.additional_properties or {}
    s3_uri_spec = additional_props.get("s3_uri")

    if isinstance(s3_uri_spec, str):
        return s3_uri_spec

    if isinstance(s3_uri_spec, dict):
        if dependency.data_type == "prediction":
            if auto_attach_data_environment in s3_uri_spec:
                return s3_uri_spec[auto_attach_data_environment]
            available_envs = sorted(s3_uri_spec.keys())
            raise ValueError(
                f"Environment {auto_attach_data_environment!r} not found in prediction_spec.s3_uri for "
                f"dependency '{dependency.data_prefix}'. Available environments: {available_envs}"
            )
        normalized_map = {str(k).lower(): v for k, v in s3_uri_spec.items()}
        preferred_order = [
            str(auto_attach_data_environment).lower(),
            "production",
            "prod",
            "test",
            "staging",
        ]
        for key in preferred_order:
            if key in normalized_map:
                return normalized_map[key]
        if normalized_map:
            first_value = next(iter(normalized_map.values()))
            return first_value

    if auto_attach_data_default_s3_base:
        base = auto_attach_data_default_s3_base.rstrip("/") + "/"
        return f"{base}{dependency.data_prefix}/"

    return None


def attach_input_data_config_for_dependencies(
    *,
    algorithm_pipeline: AlgorithmPipeline,
    training_job_definition: dict[str, Any],
    auto_attach_data_default_s3_base: str | None,
    auto_attach_data_environment: str,
) -> None:
    """Augment SageMaker job definition with InputDataConfig entries derived from algorithm dependencies."""
    dependencies = algorithm_pipeline.data_dependencies()
    if not dependencies:
        logger.info("No data dependencies detected; skipping InputDataConfig auto-attachment.")
        return

    input_data_config = training_job_definition.setdefault("InputDataConfig", [])
    if not isinstance(input_data_config, list):
        raise ValueError("InputDataConfig in SageMaker config must be a list when auto-attach is enabled.")

    existing_channels = {channel.get("ChannelName") for channel in input_data_config if isinstance(channel, dict)}
    added_channels = []

    training_input_mode = training_job_definition.get("AlgorithmSpecification", {}).get("TrainingInputMode", "FastFile")

    for dependency in dependencies:
        channel_name = dependency.data_prefix
        if not channel_name:
            continue
        if channel_name in existing_channels:
            continue

        s3_uri = resolve_dependency_s3_uri(
            dependency,
            auto_attach_data_environment=auto_attach_data_environment,
            auto_attach_data_default_s3_base=auto_attach_data_default_s3_base,
        )
        if not s3_uri:
            raise ValueError(
                f"Cannot resolve S3 URI for dependency '{channel_name}'. "
                f"Provide a 's3_uri' in the algorithm definition or specify a default base via "
                f"'--auto-attach-data-default-s3-base'."
            )

        channel = {
            "ChannelName": channel_name,
            "DataSource": {
                "S3DataSource": {
                    "S3DataType": "S3Prefix",
                    "S3Uri": s3_uri,
                }
            },
            "InputMode": training_input_mode,
        }
        logger.info("Auto-attach InputDataConfig channel '%s' with S3 URI '%s'", channel_name, s3_uri)
        added_channels.append(channel)
        existing_channels.add(channel_name)

    if added_channels:
        input_data_config.extend(added_channels)
        logger.info(
            "Auto-attached %d InputDataConfig channel(s): %s",
            len(added_channels),
            ", ".join(channel["ChannelName"] for channel in added_channels),
        )
    else:
        logger.info("All auto-attach InputDataConfig channels already present; nothing to add.")


class BacktestIterationResult(NamedTuple):
    parameter_version: str
    test_data_time: str
    result: dict[str, Any] | None = None
    error: str | None = None


class BacktestResult(NamedTuple):
    algo_git_reference: str
    algorithm_definition_override: dict[str, Any] | None = None
    algorithm_definition_override_metadata: dict[str, Any] | None = None
    backtest_iteration_results: list[BacktestIterationResult] | None = None
    error: str | None = None


@dataclass(frozen=True)
class EncodeCachePrewarmJob:
    representative_last_test_day: date
    encode_partition_dates_by_algorithm: dict[str, tuple[date, ...]]


def apply_sagemaker_job_overrides(job_definition: dict[str, Any], overrides: dict[str, Any] | None) -> None:
    """
    Merge algorithm-defined SageMaker job overrides into the base SageMaker job definition.

    Args:
        job_definition: SageMaker training job definition to modify
        overrides: Algorithm-supplied job definition fragment
    """
    if not overrides:
        return
    recursive_dict_update(job_definition, copy.deepcopy(overrides))


def legacy_sagemaker_params_to_overrides(params: dict[str, Any] | None) -> dict[str, Any] | None:
    """
    Convert deprecated sagemaker_execution_parameters into native SageMaker JSON overrides.

    Args:
        params: Dictionary containing instance_type, max_runtime, and/or volume_size_in_gb

    Returns:
        Nested dictionary suitable for apply_sagemaker_job_overrides or None if no params provided.
    """
    if not params:
        return None

    overrides: dict[str, Any] = {}
    resource_config: dict[str, Any] = {}

    if "instance_type" in params:
        resource_config["InstanceType"] = params["instance_type"]
    if "volume_size_in_gb" in params:
        resource_config["VolumeSizeInGB"] = params["volume_size_in_gb"]
    if resource_config:
        overrides["ResourceConfig"] = resource_config

    stopping_condition: dict[str, Any] = {}
    if "max_runtime" in params:
        stopping_condition["MaxRuntimeInSeconds"] = params["max_runtime"]
    if stopping_condition:
        overrides["StoppingCondition"] = stopping_condition

    return overrides or None


def apply_training_container(job_definition: dict[str, Any], algorithm_definition: dict[str, Any]) -> None:
    """
    Apply the legacy algorithm definition training_container to the SageMaker TrainingImage.

    Args:
        job_definition: SageMaker training job definition to modify
        algorithm_definition: Algorithm definition dict containing training_container
    """
    training_container = algorithm_definition.get("training_container")
    if not training_container:
        return

    job_definition.setdefault("AlgorithmSpecification", {})["TrainingImage"] = training_container


def apply_algorithm_sagemaker_job_configuration(
    job_definition: dict[str, Any],
    algorithm_definition: dict[str, Any],
) -> None:
    """
    Apply SageMaker job settings declared by an algorithm definition layer.

    The caller controls layer precedence. Within one layer, the deprecated
    training_container shorthand is applied before native
    sagemaker_training_job_definition fields, so native SageMaker JSON wins.
    """
    apply_training_container(job_definition, algorithm_definition)

    algorithm_job_overrides = algorithm_definition.get("sagemaker_training_job_definition")
    if algorithm_job_overrides:
        apply_sagemaker_job_overrides(job_definition, algorithm_job_overrides)

    legacy_params_override = legacy_sagemaker_params_to_overrides(
        algorithm_definition.get("sagemaker_execution_parameters")
    )
    if legacy_params_override:
        apply_sagemaker_job_overrides(job_definition, legacy_params_override)


def _apply_algorithm_pipeline_sagemaker_job_configuration(
    job_definition: dict[str, Any],
    algorithm_pipeline: AlgorithmPipeline,
) -> None:
    job_definition.setdefault("AlgorithmSpecification", {}).setdefault("TrainingInputMode", "FastFile")

    if algorithm_pipeline.algorithm_definition_override is None:
        algorithm_definition_layers = [("algorithm definition", algorithm_pipeline.algorithm_definition)]
    else:
        assert algorithm_pipeline.committed_algorithm_definition is not None
        algorithm_definition_layers = [
            ("algorithm definition", algorithm_pipeline.committed_algorithm_definition),
            ("algorithm definition override", algorithm_pipeline.algorithm_definition_override),
        ]

    for layer_name, algorithm_definition_layer in algorithm_definition_layers:
        if algorithm_definition_layer.get("sagemaker_training_job_definition"):
            logger.info("Applying %s SageMaker job overrides", layer_name)
        if algorithm_definition_layer.get("sagemaker_execution_parameters"):
            logger.warning(
                "Algorithm %s %s uses deprecated sagemaker_execution_parameters. "
                "Please move these settings into sagemaker_training_job_definition.ResourceConfig/StoppingCondition.",
                algorithm_pipeline.algorithm_name,
                layer_name,
            )
        apply_algorithm_sagemaker_job_configuration(job_definition, algorithm_definition_layer)


def _validate_prewarm_activation(
    *,
    prewarm_encode_cache: bool,
    prewarm_instance_count: int | None,
    prewarm_instance_type: str | None,
    sagemaker_training_job_definition: dict[str, Any] | None,
) -> None:
    if prewarm_instance_count is not None:
        if prewarm_instance_count <= 0:
            raise ValueError("prewarm_instance_count must be a positive integer")
        if not prewarm_encode_cache:
            raise ValueError("prewarm_instance_count requires prewarm_encode_cache")
    if prewarm_instance_type and not prewarm_encode_cache:
        raise ValueError("prewarm_instance_type requires prewarm_encode_cache")
    if prewarm_encode_cache and not sagemaker_training_job_definition:
        raise ValueError("prewarm_encode_cache requires SageMaker execution")


def _validate_prewarm_options(
    *,
    prewarm_encode_cache: bool,
    prewarm_instance_count: int | None,
    prewarm_instance_type: str | None,
    sagemaker_training_job_definition: dict[str, Any] | None,
    cache_base_dir: str | None,
    cache_refresh: bool,
) -> None:
    _validate_prewarm_activation(
        prewarm_encode_cache=prewarm_encode_cache,
        prewarm_instance_count=prewarm_instance_count,
        prewarm_instance_type=prewarm_instance_type,
        sagemaker_training_job_definition=sagemaker_training_job_definition,
    )
    if not prewarm_encode_cache:
        return
    if not cache_base_dir:
        raise ValueError("prewarm_encode_cache requires cache_base_dir")
    if not cache_base_dir.startswith("s3://"):
        raise ValueError("prewarm_encode_cache requires an s3:// cache_base_dir")
    if cache_refresh:
        raise ValueError("prewarm_encode_cache is not supported with cache_refresh")


class BacktestPipeline:
    def __init__(
        self,
        algo_repo_url: str,
        algo_git_reference: str,
        data_base_dir: str,
        output_base_dir: str,
        hyperparameter_base_dir: str,
        evaluation_function: Callable[[str], dict[str, Any]],
        last_test_time: date,
        number_of_runs: int,
        additional_jars: list[Path] = None,
        algorithm_definition_override: dict[str, Any] = None,
        algorithm_definition_override_metadata: dict[str, Any] | None = None,
        auto_attach_data_default_s3_base: str | None = None,
        auto_attach_data_environment: str = "production",
        encode_test_data: bool = False,
        execute_audit: bool = False,
        prewarm_encode_cache: bool = False,
        prewarm_instance_count: int | None = None,
        prewarm_instance_type: str | None = None,
    ):
        self.algo_repo_url = algo_repo_url
        self.algo_git_reference = algo_git_reference
        self.additional_jars = additional_jars if additional_jars else []
        self.data_base_dir = Path(data_base_dir)
        self.output_data_dir = Path(output_base_dir)
        self.output_data_dir.mkdir(exist_ok=True, parents=True)
        self.hyperparameter_base_dir = Path(hyperparameter_base_dir)
        self.hyperparameter_base_dir.mkdir(exist_ok=True, parents=True)

        self.hyperparameter_dir = Path(os.path.join(self.hyperparameter_base_dir, str(uuid.uuid4())))
        self.hyperparameter_dir.mkdir(parents=True, exist_ok=True)

        self.evaluation_function = evaluation_function
        self.last_test_time = last_test_time
        self.number_of_runs = number_of_runs
        self.algorithm_definition_override = algorithm_definition_override
        self.algorithm_definition_override_metadata = copy.deepcopy(algorithm_definition_override_metadata)
        self.auto_attach_data_environment = (auto_attach_data_environment or "production").lower()
        self.auto_attach_data_default_s3_base = auto_attach_data_default_s3_base
        self.encode_test_data = encode_test_data
        self.execute_audit = execute_audit
        self.prewarm_encode_cache = prewarm_encode_cache
        self.prewarm_instance_count = prewarm_instance_count
        self.prewarm_instance_type = prewarm_instance_type

        logger.info(f"Initialized BacktestPipeline with {self.__dict__}")

    def _prepare_algorithm_jar(self, git_reference) -> AlgorithmSpec:
        """Prepare algorithm JAR by cloning repo and building."""
        result = clone_and_build_algorithm_jar(
            repo_url=self.algo_repo_url,
            git_reference=git_reference,
            work_dir=Path(self.hyperparameter_dir),
            copy_jar_to=Path(self.hyperparameter_dir),
            progress_stream=sys.stdout,
        )

        return AlgorithmSpec(
            algorithm_name=result.algorithm_name,
            algorithm_jar_path=result.algorithm_jar_path,
            git_commit_hash=result.git_commit_hash,
        )

    def _resolve_algorithm_definition(
        self,
        algorithm_spec: AlgorithmSpec,
        algorithm_definition_override: dict[str, Any],
    ) -> dict[str, Any]:
        if algorithm_definition_override is not None and (
            "algorithm_name" in algorithm_definition_override.keys()
            or "algorithm_version" in algorithm_definition_override.keys()
        ):
            raise ValueError(
                f"Since hotvect v3, you may not override algorithm name or version. Offending algorithm definition override: {algorithm_definition_override}"
            )

        committed_algorithm_definition = read_algorithm_definition_from_jar(
            algorithm_name=algorithm_spec.algorithm_name,
            algorithm_jar_path=algorithm_spec.algorithm_jar_path,
            additional_jars=self.additional_jars,
        )

        if algorithm_definition_override is not None:
            return apply_algorithm_definition_override(
                committed_algorithm_definition,
                algorithm_definition_override,
                offline=True,
            )
        return committed_algorithm_definition

    def _prepare_algorithm_definition(
        self,
        algorithm_spec: AlgorithmSpec,
        algorithm_definition_override: dict[str, Any],
    ) -> Path:
        algorithm_definition = self._resolve_algorithm_definition(
            algorithm_spec,
            algorithm_definition_override,
        )
        return write_data(
            algorithm_definition,
            os.path.join(self.hyperparameter_dir, "algorithm-definition.json"),
        )

    def _write_execution_parameters(
        self,
        last_test_time: date,
        number_of_runs: int,
        clean: bool,
        system_performance_test: bool,
        jvm_options: list[str],
    ):
        execution_parameters = {
            "last_test_time": last_test_time.isoformat(),
            "number_of_runs": number_of_runs,
            "performance_test": system_performance_test,
            "clean": clean,
        }
        if jvm_options:
            execution_parameters["jvm_options"] = jvm_options

        return write_data(
            execution_parameters,
            os.path.join(self.hyperparameter_dir, "execution-parameters.json"),
        )

    def run_all(
        self,
        clean: bool = True,
        system_performance_test: bool = True,
        jvm_options: list[str] = None,
        n_process: int = -1,
        max_threads_per_process: int = -1,
        sagemaker_training_job_definition: dict[str, Any] = None,
        sagemaker_cli_job_overrides: dict[str, Any] | None = None,
        role_arn_to_assume: str | None = None,
    ) -> BacktestResult:
        _validate_prewarm_activation(
            prewarm_encode_cache=self.prewarm_encode_cache,
            prewarm_instance_count=self.prewarm_instance_count,
            prewarm_instance_type=self.prewarm_instance_type,
            sagemaker_training_job_definition=sagemaker_training_job_definition,
        )

        laptime = time.time()
        algorithm_spec = self._prepare_algorithm_jar(self.algo_git_reference)
        logger.info(f"Prepared algorithm jar {algorithm_spec} in {(time.time() - laptime): .1f}")

        effective_algorithm_definition_override = self.algorithm_definition_override
        algorithm_definition_path = self._prepare_algorithm_definition(
            algorithm_spec, effective_algorithm_definition_override
        )
        effective_algorithm_definition = read_json(algorithm_definition_path)
        logger.info(f"Prepared algorithm definition: {effective_algorithm_definition}")

        execution_parameter_path = self._write_execution_parameters(
            self.last_test_time,
            self.number_of_runs,
            clean,
            system_performance_test,
            jvm_options,
        )
        execution_parameters = read_json(execution_parameter_path)
        logger.info(f"Wrote down execution parameters: {execution_parameters}")

        algorithm_spec = AlgorithmSpec(
            effective_algorithm_definition["algorithm_name"],
            algorithm_spec.algorithm_jar_path,
            algorithm_spec.git_commit_hash,
        )

        if not sagemaker_training_job_definition:
            logger.info("Executing in local mode")
            # Calculate concurrency and queue length
            if max_threads_per_process > 0:
                max_threads = max_threads_per_process
            else:
                max_threads = self.recommended_thread_count(n_process)
            queue_length = self.recommended_queue_length(max_threads)
            return self._execute_on_local(
                algorithm_spec=algorithm_spec,
                algorithm_definition_override=effective_algorithm_definition_override,
                algorithm_definition_override_metadata=self.algorithm_definition_override_metadata,
                last_test_time=self.last_test_time,
                number_of_runs=self.number_of_runs,
                system_performance_test=system_performance_test,
                jvm_options=jvm_options,
                n_process=n_process,
                max_thread_per_process=max_threads,
                queue_length=queue_length,
                clean=clean,
            )
        else:
            return self._execute_on_sagemaker(
                sagemaker_training_job_definition=sagemaker_training_job_definition,
                sagemaker_cli_job_overrides=sagemaker_cli_job_overrides,
                role_arn_to_assume=role_arn_to_assume,
                algorithm_spec=algorithm_spec,
                algorithm_definition_override=effective_algorithm_definition_override,
                algorithm_definition_override_metadata=self.algorithm_definition_override_metadata,
                last_test_time=self.last_test_time,
                number_of_runs=self.number_of_runs,
                system_performance_test=system_performance_test,
                jvm_options=jvm_options,
                max_threads_per_process=max_threads_per_process,
                clean=clean,
            )

    def _execute_on_sagemaker(
        self,
        algorithm_spec: AlgorithmSpec,
        algorithm_definition_override: dict[str, Any] | None,
        last_test_time: date,
        number_of_runs: int,
        system_performance_test: bool,
        jvm_options: list[str],
        clean: bool,
        max_threads_per_process: int = -1,
        algorithm_definition_override_metadata: dict[str, Any] | None = None,
        sagemaker_training_job_definition: dict[str, Any] = None,
        sagemaker_cli_job_overrides: dict[str, Any] | None = None,
        role_arn_to_assume: str | None = None,
    ) -> BacktestResult:
        last_test_days = [last_test_time - timedelta(days=i) for i in range(number_of_runs)]
        additional_jar_files = self.additional_jars
        max_threads = max_threads_per_process if max_threads_per_process > 0 else None
        queue_length = self.recommended_queue_length(max_threads)
        context = AlgorithmPipelineContext(
            algorithm_jar_path=algorithm_spec.algorithm_jar_path,
            state_source_base_path=Path(os.path.join(self.data_base_dir, "states")),
            data_base_path=Path(self.data_base_dir),
            metadata_base_path=Path(os.path.join(self.output_data_dir, "meta")),
            output_base_path=Path(os.path.join(self.output_data_dir, "out")),
            jvm_options=normalize_pipeline_jvm_options(jvm_options),
            max_threads=max_threads,
            queue_length=queue_length,
            batch_size=None,
            additional_jar_files=additional_jar_files,
        )
        try:
            from hotvect.sagemaker import SagemakerTrainingExecutor
        except ImportError as e:
            raise ImportError(
                "SageMaker support is not installed (missing optional dependency 'sagemaker_training'). "
                "Install the SageMaker extra for hotvect to use sagemaker execution."
            ) from e

        backtest_iteration_results: list[BacktestIterationResult] = []
        # Keep passing the override fragment contract into AlgorithmPipeline. The
        # pipeline materializes the effective definition internally and SageMaker
        # still needs access to the original override for upload/metadata paths.
        algorithm_definition_arg = (algorithm_spec.algorithm_name, algorithm_definition_override)

        if self.prewarm_encode_cache:
            self._prewarm_encode_partition_cache_on_sagemaker(
                algorithm_spec=algorithm_spec,
                algorithm_definition_arg=algorithm_definition_arg,
                context=context,
                last_test_days=last_test_days,
                sagemaker_training_job_definition=sagemaker_training_job_definition,
                sagemaker_cli_job_overrides=sagemaker_cli_job_overrides,
                role_arn_to_assume=role_arn_to_assume,
                sagemaker_executor_cls=SagemakerTrainingExecutor,
            )

        for last_test_day in last_test_days:
            parameter_version = f"last_test_date_{last_test_day}"
            algorithm_pipeline_kwargs = {
                "algorithm_pipeline_context": context,
                "algorithm_definition": algorithm_definition_arg,
                "last_test_time": last_test_day,
                "evaluation_func": self.evaluation_function,
                "parameter_version": parameter_version,
                "execute_performance_test": system_performance_test,
                "encode_test_data": self.encode_test_data,
                "execute_audit": self.execute_audit,
            }
            if algorithm_definition_override_metadata is not None:
                algorithm_pipeline_kwargs["algorithm_override_metadata"] = algorithm_definition_override_metadata
            algorithm_pipeline = AlgorithmPipeline(**algorithm_pipeline_kwargs)

            if sagemaker_training_job_definition is None:
                raise ValueError("SageMaker training job definition must be provided when executing on SageMaker.")
            this_iteration_sagemaker_training_job_definition = copy.deepcopy(sagemaker_training_job_definition)

            _apply_algorithm_pipeline_sagemaker_job_configuration(
                this_iteration_sagemaker_training_job_definition,
                algorithm_pipeline,
            )

            apply_sagemaker_job_overrides(this_iteration_sagemaker_training_job_definition, sagemaker_cli_job_overrides)

            self._attach_input_data_config(
                algorithm_pipeline=algorithm_pipeline,
                training_job_definition=this_iteration_sagemaker_training_job_definition,
            )

            this_iteration_sagemaker_training_job_definition["TrainingJobName"] = build_backtest_training_job_name(
                prefix=this_iteration_sagemaker_training_job_definition["TrainingJobName"],
                git_commit_hash=algorithm_spec.git_commit_hash,
                hyperparameter_version=getattr(algorithm_pipeline, "hyper_parameter_version", None) or "",
                last_test_day=last_test_day,
            )
            sagemaker_executor = SagemakerTrainingExecutor(
                algorithm_pipeline=algorithm_pipeline,
                training_job_definition=this_iteration_sagemaker_training_job_definition,
                role_arn_to_assume=role_arn_to_assume,
            )
            submission_response = sagemaker_executor.run()
            backtest_iteration_results.append(
                BacktestIterationResult(
                    parameter_version=sagemaker_executor.algorithm_pipeline.parameter_version,
                    test_data_time=sagemaker_executor.algorithm_pipeline.last_test_time.isoformat(),
                    result=sagemaker_executor.build_submission_manifest(submission_response),
                    error=None,
                )
            )

        # Don't wait for SageMaker jobs - return immediately with job submission info
        error = None
        backtest_result = BacktestResult(
            algo_git_reference=self.algo_git_reference,
            algorithm_definition_override=self.algorithm_definition_override,
            algorithm_definition_override_metadata=algorithm_definition_override_metadata,
            backtest_iteration_results=backtest_iteration_results,
            error=error,
        )
        return backtest_result

    @staticmethod
    def _validate_encode_cache_prewarm_graph(algorithm_pipeline: AlgorithmPipeline) -> None:
        def validate(pipeline: AlgorithmPipeline, cache_owner: AlgorithmPipeline | None) -> None:
            if pipeline._uses_prebuilt_parameters():
                return
            if cache_owner is not None and pipeline.should_train():
                raise ValueError(
                    f"Encode cache prewarm cannot encode {cache_owner.algorithm_name}: its trainable dependency "
                    f"{pipeline.algorithm_name} requires a trained model in the encoding-parameter archive. "
                    "Pin that dependency with hotvect_execution_parameters.with_parameter, disable partition caching "
                    f"for {cache_owner.algorithm_name}, or run without prewarming."
                )

            next_cache_owner = pipeline if pipeline._uses_encode_partition_cache() else cache_owner
            for dependency_pipeline in pipeline.dependency_pipelines.values():
                validate(dependency_pipeline, next_cache_owner)

        validate(algorithm_pipeline, None)

    def _build_encode_cache_prewarm_jobs(
        self,
        *,
        algorithm_pipeline: AlgorithmPipeline,
        last_test_days: list[date],
        maximum_contexts: int | None = None,
    ) -> list[EncodeCachePrewarmJob]:
        if not last_test_days:
            return []

        partition_dates_by_pipeline_and_last_test_day = self._encode_partition_dates_by_pipeline_and_last_test_day(
            algorithm_pipeline=algorithm_pipeline,
            last_test_days=last_test_days,
        )
        assignment_candidates: list[tuple[str, date, tuple[date, ...]]] = []
        for algorithm_name, partition_dates_by_last_test_day in sorted(
            partition_dates_by_pipeline_and_last_test_day.items()
        ):
            algorithm_partition_dates = sorted(
                {
                    partition_date
                    for partition_dates in partition_dates_by_last_test_day.values()
                    for partition_date in partition_dates
                },
                reverse=True,
            )
            for partition_date in algorithm_partition_dates:
                valid_last_test_days = tuple(
                    last_test_day
                    for last_test_day in last_test_days
                    if partition_date in partition_dates_by_last_test_day[last_test_day]
                )
                assignment_candidates.append((algorithm_name, partition_date, valid_last_test_days))

        if not assignment_candidates:
            return []

        context_order = {last_test_day: index for index, last_test_day in enumerate(last_test_days)}
        selected_contexts: set[date] = set()
        # A partition's compatible backtest contexts form an interval: each consecutive backtest day shifts the
        # training window by one day. Pick the newest context of the oldest uncovered interval; this produces the
        # minimum number of compatible parameter contexts.
        for _, _, valid_last_test_days in sorted(
            assignment_candidates,
            key=lambda candidate: (
                -context_order[candidate[2][0]],
                candidate[0],
                -candidate[1].toordinal(),
            ),
        ):
            if not selected_contexts.intersection(valid_last_test_days):
                selected_contexts.add(valid_last_test_days[0])

        if maximum_contexts is not None:
            if maximum_contexts < len(selected_contexts):
                raise ValueError(
                    f"--prewarm-instance-count={maximum_contexts} is too low: this backtest needs "
                    f"{len(selected_contexts)} one-instance prewarm jobs. They are submitted together, so set "
                    f"--prewarm-instance-count to at least {len(selected_contexts)}."
                )
            available_contexts = [
                last_test_day
                for last_test_day in last_test_days
                if last_test_day not in selected_contexts
                and any(last_test_day in valid_last_test_days for _, _, valid_last_test_days in assignment_candidates)
            ]
            while len(selected_contexts) < maximum_contexts and available_contexts:
                next_context = max(
                    available_contexts,
                    key=lambda last_test_day: (
                        sum(
                            last_test_day in valid_last_test_days
                            for _, _, valid_last_test_days in assignment_candidates
                        ),
                        -context_order[last_test_day],
                    ),
                )
                selected_contexts.add(next_context)
                available_contexts.remove(next_context)

        context_load = {last_test_day: 0 for last_test_day in selected_contexts}
        assignments_by_last_test_day: dict[date, dict[str, list[date]]] = {}
        # Place constrained partitions first, then use the least-loaded context that can encode each partition.
        assignment_candidates.sort(
            key=lambda candidate: (
                sum(last_test_day in selected_contexts for last_test_day in candidate[2]),
                candidate[0],
                -candidate[1].toordinal(),
            )
        )
        for algorithm_name, partition_date, valid_last_test_days in assignment_candidates:
            valid_selected_contexts = [
                last_test_day for last_test_day in valid_last_test_days if last_test_day in selected_contexts
            ]
            representative_last_test_day = min(
                valid_selected_contexts,
                key=lambda last_test_day: (context_load[last_test_day], context_order[last_test_day]),
            )
            assignments_by_last_test_day.setdefault(representative_last_test_day, {}).setdefault(
                algorithm_name, []
            ).append(partition_date)
            context_load[representative_last_test_day] += 1

        jobs = []
        for representative_last_test_day in last_test_days:
            assignments = assignments_by_last_test_day.get(representative_last_test_day)
            if not assignments:
                continue
            encode_partition_dates_by_algorithm = {
                algorithm_name: tuple(sorted(partition_dates, reverse=True))
                for algorithm_name, partition_dates in assignments.items()
            }
            jobs.append(
                EncodeCachePrewarmJob(
                    representative_last_test_day=representative_last_test_day,
                    encode_partition_dates_by_algorithm=encode_partition_dates_by_algorithm,
                )
            )
        return jobs

    def _encode_partition_dates_by_pipeline_and_last_test_day(
        self,
        *,
        algorithm_pipeline: AlgorithmPipeline,
        last_test_days: list[date],
    ) -> dict[str, dict[date, tuple[date, ...]]]:
        partition_dates_by_pipeline_and_last_test_day = {}

        def collect(pipeline: AlgorithmPipeline) -> None:
            if pipeline._uses_prebuilt_parameters():
                return
            if pipeline._uses_encode_partition_cache():
                partition_dates_by_last_test_day = {}
                for last_test_day in last_test_days:
                    partition_dates_by_last_test_day[last_test_day] = tuple(
                        sorted(pipeline._training_dates_for_last_test_time(last_test_day), reverse=True)
                    )
                partition_dates_by_pipeline_and_last_test_day[
                    pipeline.algorithm_name
                ] = partition_dates_by_last_test_day
            for dependency_pipeline in pipeline.dependency_pipelines.values():
                collect(dependency_pipeline)

        collect(algorithm_pipeline)
        return partition_dates_by_pipeline_and_last_test_day

    def _prewarm_job_name(
        self,
        *,
        base_prefix: str,
        algorithm_spec: AlgorithmSpec,
        algorithm_pipeline: AlgorithmPipeline,
        last_test_day: date,
    ) -> str:
        prewarm_prefix = f"{base_prefix}-prewarm"
        name = build_backtest_training_job_name(
            prefix=prewarm_prefix,
            git_commit_hash=algorithm_spec.git_commit_hash,
            hyperparameter_version=getattr(algorithm_pipeline, "hyper_parameter_version", None) or "",
            last_test_day=last_test_day,
        )
        name = f"{name}-{generate_runid()}"
        validate_training_job_name_length(name)
        return name

    def _prewarm_training_job_definition(
        self,
        *,
        base_training_job_definition: dict[str, Any],
        sagemaker_cli_job_overrides: dict[str, Any] | None,
        algorithm_pipeline: AlgorithmPipeline,
        training_job_name: str,
    ) -> dict[str, Any]:
        job_definition = copy.deepcopy(base_training_job_definition)
        job_definition["TrainingJobName"] = training_job_name
        _apply_algorithm_pipeline_sagemaker_job_configuration(job_definition, algorithm_pipeline)

        apply_sagemaker_job_overrides(job_definition, sagemaker_cli_job_overrides)
        resource_config = job_definition.setdefault("ResourceConfig", {})
        if self.prewarm_instance_type:
            resource_config["InstanceType"] = self.prewarm_instance_type
            submission_options = job_definition.get(HOTVECT_SUBMISSION_OPTIONS_KEY)
            if submission_options is not None:
                if not isinstance(submission_options, dict):
                    raise ValueError(f"{HOTVECT_SUBMISSION_OPTIONS_KEY} must be an object/dict")
                submission_options[HOTVECT_PREFERRED_INSTANCE_TYPES_KEY] = [self.prewarm_instance_type]
        resource_config["InstanceCount"] = 1
        return job_definition

    @staticmethod
    def _blocked_prewarm_partitions(result: dict[str, Any]) -> list[str]:
        blocked_partitions: list[str] = []

        def collect(value: Any, algorithm_id: str = "unknown algorithm") -> None:
            if isinstance(value, dict):
                current_algorithm_id = str(value.get("algorithm_id", algorithm_id))
                blocked = value.get("partition_cache_blocked")
                if blocked:
                    blocked_entries = [
                        str(partition.get("cache_path") or f"dt={partition.get('dt')}")
                        for partition in value.get("partitions", [])
                        if isinstance(partition, dict) and partition.get("cache") == "blocked"
                    ]
                    blocked_partitions.append(
                        f"{current_algorithm_id}: {', '.join(blocked_entries) if blocked_entries else blocked}"
                    )
                for child in value.values():
                    collect(child, current_algorithm_id)
            elif isinstance(value, list):
                for child in value:
                    collect(child, algorithm_id)

        collect(result)
        return blocked_partitions

    def _prewarm_encode_partition_cache_on_sagemaker(
        self,
        *,
        algorithm_spec: AlgorithmSpec,
        algorithm_definition_arg: tuple[str, dict[str, Any] | None],
        context: AlgorithmPipelineContext,
        last_test_days: list[date],
        sagemaker_training_job_definition: dict[str, Any],
        sagemaker_cli_job_overrides: dict[str, Any] | None,
        role_arn_to_assume: str | None,
        sagemaker_executor_cls,
    ) -> None:
        if not sagemaker_training_job_definition:
            raise ValueError("Backtest cache prewarm requires SageMaker execution.")
        if not self.prewarm_encode_cache:
            raise ValueError("prewarm_encode_cache must be enabled before prewarming")

        planning_pipeline = AlgorithmPipeline(
            algorithm_pipeline_context=context,
            algorithm_definition=algorithm_definition_arg,
            last_test_time=last_test_days[0],
            evaluation_func=self.evaluation_function,
            parameter_version=f"last_test_date_{last_test_days[0]}",
            execute_performance_test=False,
            encode_test_data=False,
            execute_audit=False,
            run_target="encode-cache",
        )
        self._validate_encode_cache_prewarm_graph(planning_pipeline)
        jobs = self._build_encode_cache_prewarm_jobs(
            algorithm_pipeline=planning_pipeline,
            last_test_days=last_test_days,
            maximum_contexts=self.prewarm_instance_count,
        )
        if not jobs:
            logger.info("No encode cache prewarm jobs were planned.")
            return
        logger.info(
            "Prewarming %d encode partition date assignment(s) for %d backtest day(s) with %d SageMaker "
            "encode-cache job(s).",
            sum(
                len(partition_dates)
                for job in jobs
                for partition_dates in job.encode_partition_dates_by_algorithm.values()
            ),
            len(last_test_days),
            len(jobs),
        )

        def build_pipeline(job: EncodeCachePrewarmJob):
            representative_last_test_day = job.representative_last_test_day
            return AlgorithmPipeline(
                algorithm_pipeline_context=context,
                algorithm_definition=algorithm_definition_arg,
                last_test_time=representative_last_test_day,
                evaluation_func=self.evaluation_function,
                parameter_version=f"last_test_date_{representative_last_test_day}",
                execute_performance_test=False,
                encode_test_data=False,
                execute_audit=False,
                run_target="encode-cache",
                encode_partition_dates_by_algorithm={
                    algorithm_name: list(partition_dates)
                    for algorithm_name, partition_dates in job.encode_partition_dates_by_algorithm.items()
                },
            )

        executors = []
        for job in jobs:
            algorithm_pipeline = build_pipeline(job)
            training_job_name = self._prewarm_job_name(
                base_prefix=sagemaker_training_job_definition["TrainingJobName"],
                algorithm_spec=algorithm_spec,
                algorithm_pipeline=algorithm_pipeline,
                last_test_day=job.representative_last_test_day,
            )
            prewarm_job_definition = self._prewarm_training_job_definition(
                base_training_job_definition=sagemaker_training_job_definition,
                sagemaker_cli_job_overrides=sagemaker_cli_job_overrides,
                algorithm_pipeline=algorithm_pipeline,
                training_job_name=training_job_name,
            )
            self._attach_input_data_config(
                algorithm_pipeline=algorithm_pipeline,
                training_job_definition=prewarm_job_definition,
            )
            executors.append(
                sagemaker_executor_cls(
                    algorithm_pipeline=algorithm_pipeline,
                    training_job_definition=prewarm_job_definition,
                    role_arn_to_assume=role_arn_to_assume,
                )
            )
        for executor in executors:
            executor.run()

        failed_jobs = []
        completed_executors = []
        for executor in executors:
            job_description = executor.wait_for_sagemaker_job_completion()
            if job_description["TrainingJobStatus"] != "Completed":
                failure_reason = job_description.get("FailureReason", job_description["TrainingJobStatus"])
                failed_jobs.append(f"{executor.training_job_name}: {failure_reason}")
            else:
                completed_executors.append(executor)
        if failed_jobs:
            raise RuntimeError(f"Encode partition cache prewarm failed: {'; '.join(failed_jobs)}")

        blocked_jobs = []
        for executor in completed_executors:
            result = executor._download_hotvect_result()
            blocked_partitions = self._blocked_prewarm_partitions(result)
            if blocked_partitions:
                blocked_jobs.append(f"{executor.training_job_name}: {'; '.join(blocked_partitions)}")
        if blocked_jobs:
            raise RuntimeError(
                f"Encode partition cache prewarm could not publish all assigned partitions: "
                f"{'; '.join(blocked_jobs)}. Remove or repair the incomplete cache prefixes before retrying."
            )

    def _attach_input_data_config(
        self,
        algorithm_pipeline: AlgorithmPipeline,
        training_job_definition: dict[str, Any],
    ) -> None:
        attach_input_data_config_for_dependencies(
            algorithm_pipeline=algorithm_pipeline,
            training_job_definition=training_job_definition,
            auto_attach_data_default_s3_base=self.auto_attach_data_default_s3_base,
            auto_attach_data_environment=self.auto_attach_data_environment,
        )

    def _execute_on_local(
        self,
        algorithm_spec: AlgorithmSpec,
        algorithm_definition_override: dict[str, Any] | None,
        last_test_time: date,
        number_of_runs: int,
        system_performance_test: bool,
        jvm_options: list[str],
        n_process: int,
        max_thread_per_process: int,
        queue_length: int,
        clean: bool = False,
        algorithm_definition_override_metadata: dict[str, Any] | None = None,
    ) -> BacktestResult:
        last_test_days = [last_test_time - timedelta(days=i) for i in range(number_of_runs)]
        additional_jars = list(self.additional_jars) if self.additional_jars else []
        context = AlgorithmPipelineContext(
            algorithm_jar_path=algorithm_spec.algorithm_jar_path,
            state_source_base_path=Path(os.path.join(self.data_base_dir, "states")),
            data_base_path=Path(self.data_base_dir),
            metadata_base_path=Path(os.path.join(self.output_data_dir, "meta")),
            output_base_path=Path(os.path.join(self.output_data_dir, "out")),
            jvm_options=normalize_pipeline_jvm_options(jvm_options),
            max_threads=max_thread_per_process,
            queue_length=queue_length,
            batch_size=None,
            additional_jar_files=additional_jars,
        )

        futures: list[tuple[BacktestIterationResult, ProcessFuture]] = []
        # Local backtests use the same AlgorithmPipeline contract as SageMaker so
        # both execution paths materialize overrides consistently.
        algorithm_definition_arg = (algorithm_spec.algorithm_name, algorithm_definition_override)

        with ProcessPool(max_workers=n_process) as pool:
            if n_process == 1:
                # We only have one process - let's use DirectProcessPool so that we can see logs
                exec_pool = DirectProcessPool()
            else:
                exec_pool = pool

            for last_test_day in last_test_days:
                parameter_version = f"last_test_date_{last_test_day}"
                logging.info(f"Scheduling parameter_version: {parameter_version}")
                future = exec_pool.schedule(
                    run_one_cycle_locally,
                    kwargs={
                        "context": context,
                        "algorithm_definition": algorithm_definition_arg,
                        "algorithm_override_metadata": algorithm_definition_override_metadata,
                        "parameter_version": parameter_version,
                        "last_test_time": last_test_day,
                        "evaluation_function": self.evaluation_function,
                        "clean": clean,
                        "system_performance_test": system_performance_test,
                        "encode_test_data": self.encode_test_data,
                        "execute_audit": self.execute_audit,
                    },
                )
                futures.append(
                    (
                        BacktestIterationResult(
                            parameter_version=parameter_version,
                            test_data_time=last_test_day.isoformat(),
                            result=None,
                            error=None,
                        ),
                        future,
                    )
                )

        ret: list[BacktestIterationResult] = []
        last_error = None
        for partial_result, future in futures:
            result = utils.get_result(future)
            if isinstance(result, BacktestIterationResult):
                ret.append(result)
            else:
                assert "error" in result.keys() and len(result.keys()) == 1
                ret.append(partial_result._replace(error=result["error"]))
                last_error = result["error"]

        return BacktestResult(
            algo_git_reference=self.algo_git_reference,
            algorithm_definition_override=self.algorithm_definition_override,
            algorithm_definition_override_metadata=algorithm_definition_override_metadata,
            backtest_iteration_results=ret,
            error=last_error,
        )

    def recommended_thread_count(self, n_process: int) -> int:
        available_physical_cores = psutil.cpu_count(logical=False)
        core_per_process = int(available_physical_cores / float(n_process))
        # Let's not go too crazy, even if we have lots of cores
        ret = min(30, max(1, core_per_process))
        logger.debug(
            f"Calculated recommended max threads for hotvect to {ret} because {available_physical_cores} physical cores were detected, and n_process={n_process} was specified"
        )
        return int(ret)

    def recommended_queue_length(self, thread_count: int | None) -> int | None:
        return None if thread_count is None else thread_count * 4


def run_one_cycle_locally(
    context: AlgorithmPipelineContext,
    algorithm_definition: str | dict[str, Any] | tuple[str, dict[str, Any]],
    parameter_version: str,
    last_test_time: date,
    evaluation_function: Callable[[str], dict[str, Any]],
    clean: bool,
    system_performance_test: bool,
    encode_test_data: bool = False,
    execute_audit: bool = False,
    algorithm_override_metadata: dict[str, Any] | None = None,
) -> BacktestIterationResult:
    algorithm_pipeline_kwargs = {
        "algorithm_pipeline_context": context,
        "algorithm_definition": algorithm_definition,
        "last_test_time": last_test_time,
        "parameter_version": parameter_version,
        "evaluation_func": evaluation_function,
        "execute_performance_test": system_performance_test,
        "encode_test_data": encode_test_data,
        "execute_audit": execute_audit,
    }
    if algorithm_override_metadata is not None:
        algorithm_pipeline_kwargs["algorithm_override_metadata"] = algorithm_override_metadata
    pipeline = AlgorithmPipeline(**algorithm_pipeline_kwargs)
    result = pipeline.run_all(
        clean=clean,
    )
    return BacktestIterationResult(
        parameter_version=parameter_version,
        test_data_time=last_test_time.isoformat(),
        result=result,
        error=None,
    )


def run_backtest_on_git_reference(
    algo_repo_url: str,
    algo_git_reference: str,
    data_base_dir: str,
    output_base_dir: str,
    hyperparameter_base_dir: str,
    evaluation_function: Callable[[str], dict[str, Any]],
    last_test_time: date,
    number_of_runs: int,
    algorithm_definition_override: dict[str, Any] = None,
    algorithm_definition_override_metadata: dict[str, Any] | None = None,
    jvm_options: list[str] = None,
    additional_jars: list[Path] = None,
    n_process: int = 1,
    max_threads_per_process: int = -1,
    clean: bool = False,
    system_performance_test=True,
    sagemaker_training_job_definition: dict[str, Any] = None,
    sagemaker_cli_job_overrides: dict[str, Any] | None = None,
    role_arn_to_assume: str | None = None,
    online_offline_analysis_on_variant_id: str | None = None,
    auto_attach_data_default_s3_base: str | None = None,
    auto_attach_data_environment: str = "production",
    encode_test_data: bool = False,
    execute_audit: bool = False,
    cache_base_dir: str | None = None,
    cache_scope: str = "hyperparam",
    cache_refresh: bool = False,
    prewarm_encode_cache: bool = False,
    prewarm_instance_count: int | None = None,
    prewarm_instance_type: str | None = None,
) -> BacktestResult:
    _validate_prewarm_options(
        prewarm_encode_cache=prewarm_encode_cache,
        prewarm_instance_count=prewarm_instance_count,
        prewarm_instance_type=prewarm_instance_type,
        sagemaker_training_job_definition=sagemaker_training_job_definition,
        cache_base_dir=cache_base_dir,
        cache_refresh=cache_refresh,
    )

    def updated_sagemaker_training_job_definition():
        if not sagemaker_training_job_definition:
            return None
        copy_of_sagemaker_training_job_definition = copy.deepcopy(sagemaker_training_job_definition)

        # Note: The git hash and hyperparameter identifiers will be added in _execute_on_sagemaker where we have access to the algorithm spec
        return copy_of_sagemaker_training_job_definition

    this_gitref_sagemaker_training_job_definition = updated_sagemaker_training_job_definition()

    effective_algorithm_definition_override = algorithm_definition_override
    effective_algorithm_definition_override_metadata = (
        copy.deepcopy(algorithm_definition_override_metadata) if algorithm_definition_override_metadata else {}
    )
    cache_overrides = {"hotvect_execution_parameters": {}}
    if cache_base_dir:
        cache_overrides["hotvect_execution_parameters"]["cache_base_dir"] = cache_base_dir
        cache_overrides["hotvect_execution_parameters"]["cache_scope"] = cache_scope
    if prewarm_encode_cache:
        cache_overrides["hotvect_execution_parameters"]["encode"] = {"cache": "partition"}
    if cache_refresh:
        cache_overrides["hotvect_execution_parameters"]["cache_refresh"] = True
    if cache_overrides["hotvect_execution_parameters"]:
        effective_algorithm_definition_override = merge_algorithm_definition_override_fragments(
            effective_algorithm_definition_override,
            cache_overrides,
            offline=True,
        )
        effective_algorithm_definition_override_metadata.setdefault("reasons", []).append(
            "CLI cache options generated a Hotvect execution-parameter override."
        )
        effective_algorithm_definition_override_metadata.setdefault("fields", []).extend(
            [
                "hotvect_execution_parameters.cache_base_dir",
                "hotvect_execution_parameters.cache_scope",
                *(["hotvect_execution_parameters.cache_refresh"] if cache_refresh else []),
            ]
        )

    pipeline = BacktestPipeline(
        algo_repo_url=algo_repo_url,
        algo_git_reference=algo_git_reference,
        data_base_dir=data_base_dir,
        output_base_dir=output_base_dir,
        hyperparameter_base_dir=hyperparameter_base_dir,
        evaluation_function=evaluation_function,
        last_test_time=last_test_time,
        number_of_runs=number_of_runs,
        algorithm_definition_override=effective_algorithm_definition_override,
        algorithm_definition_override_metadata=effective_algorithm_definition_override_metadata or None,
        additional_jars=additional_jars,
        auto_attach_data_default_s3_base=auto_attach_data_default_s3_base,
        auto_attach_data_environment=auto_attach_data_environment,
        encode_test_data=encode_test_data,
        execute_audit=execute_audit,
        prewarm_encode_cache=prewarm_encode_cache,
        prewarm_instance_count=prewarm_instance_count,
        prewarm_instance_type=prewarm_instance_type,
    )
    return pipeline.run_all(
        clean=clean,
        system_performance_test=system_performance_test,
        role_arn_to_assume=role_arn_to_assume,
        sagemaker_training_job_definition=this_gitref_sagemaker_training_job_definition,
        sagemaker_cli_job_overrides=sagemaker_cli_job_overrides,
        jvm_options=jvm_options,
        n_process=n_process,
        max_threads_per_process=max_threads_per_process,
    )


def run_backtest_on_git_references(
    algo_repo_url: str,
    algo_git_references: list[
        str | tuple[str, dict[str, Any] | None] | tuple[str, dict[str, Any] | None, dict[str, Any] | None]
    ],
    data_base_dir: str,
    output_base_dir: str,
    hyperparameter_base_dir: str,
    evaluation_function: Callable[[str], dict[str, Any]],
    last_test_time: date,
    number_of_runs: int,
    available_physical_cores: int = psutil.cpu_count(logical=False),
    clean: bool = False,
    system_performance_test: bool = True,
    jvm_options: list[str] = None,
    additional_jars: list[Path] = None,
    sagemaker_training_job_definition: dict[str, Any] = None,
    sagemaker_cli_job_overrides: dict[str, Any] | None = None,
    role_arn_to_assume: str | None = None,
    training_image_override: str | None = None,
    concurrency_setting: ConcurrencySetting = None,
    online_offline_analysis_on_variant_id: str | None = None,
    auto_attach_data_default_s3_base: str | None = None,
    auto_attach_data_environment: str = "production",
    encode_test_data: bool = False,
    execute_audit: bool = False,
    cache_base_dir: str | None = None,
    cache_scope: str = "hyperparam",
    cache_refresh: bool = False,
    prewarm_encode_cache: bool = False,
    prewarm_instance_count: int | None = None,
    prewarm_instance_type: str | None = None,
) -> list[BacktestResult]:
    def _unpack_reference(entry: Any) -> tuple[str, dict[str, Any] | None, dict[str, Any] | None]:
        if isinstance(entry, str):
            return entry, None, None
        if len(entry) == 2:
            git_ref, override = entry
            return git_ref, override, None
        if len(entry) == 3:
            git_ref, override, metadata = entry
            return git_ref, override, metadata
        raise ValueError(f"Unexpected git reference entry: {entry!r}")

    _validate_prewarm_options(
        prewarm_encode_cache=prewarm_encode_cache,
        prewarm_instance_count=prewarm_instance_count,
        prewarm_instance_type=prewarm_instance_type,
        sagemaker_training_job_definition=sagemaker_training_job_definition,
        cache_base_dir=cache_base_dir,
        cache_refresh=cache_refresh,
    )

    if not concurrency_setting:
        concurrency_setting = utils.recommend_concurrency(
            num_git_ref=len(algo_git_references),
            num_runs=number_of_runs,
            available_physical_cores=available_physical_cores,
            remote_execution=sagemaker_training_job_definition is not None,
        )

    if len(algo_git_references) == 0:
        raise ValueError("List of algorithms to test is empty")

    if isinstance(algo_git_references[0], str):
        # No algorithm definition override defined
        algo_git_references = [(e, None, None) for e in algo_git_references]
        logger.info(f"Starting backtest on git references: {[x[0] for x in algo_git_references]}")
    else:
        logger.info(f"Starting backtest on git references with algorithm definition overrides: {algo_git_references}")

    if sagemaker_training_job_definition is not None:
        if additional_jars:
            raise ValueError(
                "Currently you cannot use the additional_jars option with Sagemaker. Please use local execution mode"
            )

        this_sagemaker_training_job_definition = copy.deepcopy(sagemaker_training_job_definition)
        if training_image_override:
            algo_spec = this_sagemaker_training_job_definition.setdefault("AlgorithmSpecification", {})
            algo_spec["TrainingImage"] = training_image_override
        job_id_prefix = this_sagemaker_training_job_definition.get(
            "TrainingJobName", "backtest" if online_offline_analysis_on_variant_id is None else "online-offline"
        )
        this_sagemaker_training_job_definition["TrainingJobName"] = (
            job_id_prefix + f"-{hexigest_as_alphanumeric(secrets.token_hex(4))}"
        )
        logger.info(
            f"Running in sagemaker mode: with job prefix: {this_sagemaker_training_job_definition['TrainingJobName']}"
        )
    else:
        this_sagemaker_training_job_definition = None
        logger.info(
            f"Running in local mode, using concurrency setting {concurrency_setting} "
            f"for available cores: {available_physical_cores}"
        )

    futures: list[tuple[BacktestResult, ProcessFuture]] = []

    with ProcessPool(max_workers=concurrency_setting.total_backtest_pipelines) as pool:
        if concurrency_setting.total_backtest_pipelines == 1:
            # We only have one process - let's use DirectProcessPool so that we can see logs
            exec_pool = DirectProcessPool()
        else:
            exec_pool = pool

        for entry in algo_git_references:
            (
                algo_git_reference,
                algorithm_definition_override,
                algorithm_definition_override_metadata,
            ) = _unpack_reference(entry)
            if algorithm_definition_override is not None:
                logger.info(f"Starting backtest on {algo_git_reference} with override: {algorithm_definition_override}")
            else:
                logger.info(f"Starting backtest on {algo_git_reference}")

            future = exec_pool.schedule(
                run_backtest_on_git_reference,
                kwargs={
                    "algo_repo_url": algo_repo_url,
                    "algo_git_reference": algo_git_reference,
                    "data_base_dir": data_base_dir,
                    "output_base_dir": output_base_dir,
                    "hyperparameter_base_dir": hyperparameter_base_dir,
                    "evaluation_function": evaluation_function,
                    "last_test_time": last_test_time,
                    "number_of_runs": number_of_runs,
                    "algorithm_definition_override": algorithm_definition_override,
                    "algorithm_definition_override_metadata": algorithm_definition_override_metadata,
                    "jvm_options": jvm_options,
                    "n_process": concurrency_setting.nproc_per_backtest_pipeline,
                    "max_threads_per_process": concurrency_setting.threads_per_backtest_process,
                    "clean": clean,
                    "system_performance_test": system_performance_test,
                    "sagemaker_training_job_definition": this_sagemaker_training_job_definition,
                    "sagemaker_cli_job_overrides": sagemaker_cli_job_overrides,
                    "role_arn_to_assume": role_arn_to_assume,
                    "online_offline_analysis_on_variant_id": online_offline_analysis_on_variant_id,
                    "auto_attach_data_default_s3_base": auto_attach_data_default_s3_base,
                    "auto_attach_data_environment": auto_attach_data_environment,
                    "encode_test_data": encode_test_data,
                    "execute_audit": execute_audit,
                    "cache_base_dir": cache_base_dir,
                    "cache_scope": cache_scope,
                    "cache_refresh": cache_refresh,
                    "prewarm_encode_cache": prewarm_encode_cache,
                    "prewarm_instance_count": prewarm_instance_count,
                    "prewarm_instance_type": prewarm_instance_type,
                },
            )
            futures.append(
                (
                    BacktestResult(
                        algo_git_reference=algo_git_reference,
                        algorithm_definition_override=algorithm_definition_override,
                        algorithm_definition_override_metadata=algorithm_definition_override_metadata,
                    ),
                    future,
                )
            )

    ret = []
    for partial_result, future in futures:
        result = utils.get_result(future)
        if type(result) is BacktestResult:
            ret.append(result)
        else:
            assert "error" in result.keys() and len(result.keys()) == 1
            ret.append(partial_result._replace(error=result["error"]))
    return ret


def list_output_dirs(
    output_base_dir: str,
    algorithm_name_pattern: str = ".*",
    algorithm_version_pattern: str = ".*",
    from_including_test_date: date | None = None,
    to_including_test_date: date | None = None,
) -> list[Path]:
    full_glob = os.path.join(
        output_base_dir,
        "*@*",
        "*last_test_date_*",
    )

    def include(path):
        if not is_valid(path):
            return False
        return all(
            [
                from_including_test_date is None or to_test_date(path) >= from_including_test_date,
                to_including_test_date is None or to_test_date(path) <= to_including_test_date,
                re.match(algorithm_name_pattern, to_algorithm_name(path)),
                re.match(algorithm_version_pattern, to_algorithm_version(path)),
            ]
        )

    return [Path(x) for x in glob.glob(full_glob) if include(x)]


PATTERN = r"/([\w-]+)@([\.\w-]+)/(?:last_train_date_[\w-]+-)?last_test_date_(\d{4}-\d{2}-\d{2})"


def is_valid(path):
    return re.search(PATTERN, path)


def to_algorithm_version(path):
    if match := re.search(PATTERN, path):
        return match.group(2)
    else:
        raise ValueError(path)


def to_algorithm_name(path):
    if match := re.search(PATTERN, path):
        return match.group(1)
    else:
        raise ValueError(path)


def to_test_date(path):
    if match := re.search(PATTERN, path):
        return date.fromisoformat(match.group(3))
    else:
        raise ValueError(path)


def extract_evaluation_result(
    output_base_dir: str,
    algorithm_name_pattern: str = ".*",
    algorithm_version_pattern: str = ".*",
    from_including_test_date: date | None = None,
    to_including_test_date: date | None = None,
):
    from hotvect.evaluation.conversion import extract_evaluation

    output_dirs = list_output_dirs(
        output_base_dir=output_base_dir,
        algorithm_name_pattern=algorithm_name_pattern,
        algorithm_version_pattern=algorithm_version_pattern,
        from_including_test_date=from_including_test_date,
        to_including_test_date=to_including_test_date,
    )

    for output_dir in output_dirs:
        result_json_path = os.path.join(output_dir, "result.json")
        if not os.path.isfile(result_json_path):
            # Skip missing data
            continue
        with open(os.path.join(output_dir, "result.json")) as f:
            evaluation_result = extract_evaluation(json.load(f))
            # Skip if result is empty (this happens when test was skipped
            if evaluation_result is None:
                continue

            yield evaluation_result


@dataclass(frozen=True)
class _SuccessfulSageMakerResultsComponents:
    backtest_test_date: str
    algorithm_name: str
    algorithm_version: str
    training_job: str
    hyperparameter: str
    execution_date: datetime
    key: str


_RESULT_PATH_COMPONENT_PATTERN = re.compile(r"^[A-Za-z0-9._-]+$")


def _require_safe_result_path_component(value: str | None, field_name: str) -> str | None:
    if value is None:
        return None
    if not _RESULT_PATH_COMPONENT_PATTERN.fullmatch(value):
        raise ValueError(f"Unsafe {field_name!r} value in result key: {value!r}. " "Only [A-Za-z0-9._-] are allowed.")
    return value


class SageMakerBacktestResultsDownloader:
    """Download evaluation results of a backtest run using SageMaker.

    If more than one training job result exist for a (algorithm_name, algorithm_version, test_date) combination,
    the training job with the latest last modified date in the S3 store is used. In the unlikely event the dates are
    identical, the choice is undefined. If you want to include/exclude certain training jobs, you can use the
    training_job_id_pattern parameter.

    Args:
        s3_base_prefix: Source s3 folder to download the results from
        dest_base_dir: Local folder to download the results to
        training_job_id_pattern: Regex pattern to filter the training job id to take the results from
        include_metadata: Whether to download the "meta" data (like JSONs, text)
        include_output_data: Whether to download the "output" data (like model parameter files)
        algorithm_name_pattern: Regex pattern to filter the algorithm name to take results for
        algorithm_version_pattern: Regex pattern to filter the algorithm version to take results for
        skip_data_if_already_present: Whether to skip data for which there already exists a local result.json
        from_including_test_date: Start of the test date range to get results for
        to_including_test_date: End of the test date range to get results for
        role_arn_to_assume: A role that would be tried to be assumed to execute the download
    """

    OUTPUT_FILE = "output/model.tar.gz"
    METADATA_FILE = "output/output.tar.gz"

    def __init__(
        self,
        *,
        s3_base_prefix: str,
        dest_base_dir: str,
        training_job_id_pattern: str = ".+?",
        include_metadata: bool = True,
        include_output_data: bool = False,
        algorithm_name_pattern: str = ".+?",
        algorithm_version_pattern: str = ".+?",
        skip_data_if_already_present: bool = True,
        from_including_test_date: date | None = None,
        to_including_test_date: date | None = None,
        role_arn_to_assume: str | None = None,
    ):
        self._s3_base_prefix: str = s3_base_prefix
        self._training_job_id_pattern: str = training_job_id_pattern
        self._algorithm_name_pattern: str = algorithm_name_pattern
        self._algorithm_version_pattern: str = algorithm_version_pattern
        self._skip_data_if_already_present: bool = skip_data_if_already_present
        self._from_including_test_date: date | None = from_including_test_date
        self._to_including_test_date: date | None = to_including_test_date

        self._dest_base_dir: Path = Path(dest_base_dir)
        self._dest_base_dir.mkdir(parents=True, exist_ok=True)

        self._include_metadata: bool = include_metadata
        self._include_output_data: bool = include_output_data

        s3_uri_parsed = urlparse(self._s3_base_prefix)
        self._s3_source_bucket: str = s3_uri_parsed.netloc
        self._s3_source_prefix: str = s3_uri_parsed.path.strip("/")

        session = get_boto_session_after_assuming_role(role_arn_to_assume) if role_arn_to_assume else boto3.Session()
        self._s3_client: S3Client = session.client("s3")

        self._training_job_id_filter = re.compile(self._training_job_id_pattern)
        self._algorithm_name_filter = re.compile(self._algorithm_name_pattern)
        self._algorithm_version_filter = re.compile(self._algorithm_version_pattern)
        escaped_prefix = re.escape(self._s3_source_prefix)
        prefix_pattern = f"{escaped_prefix}/" if escaped_prefix else ""
        self._match_regex = rf"^{prefix_pattern}(.+?)-(\d\d\d\d-\d\d-\d\d)/" r"(.+?)@(.+?)(-([^/]+))?/result\.json$"
        logger.debug(f"Regex used to parse successful SageMaker runs {self._match_regex}")

    def download(self):
        relevant_executions = self._find_relevant_executions()
        self._download_results(relevant_executions)

    def _find_relevant_executions(self):
        paginator = self._s3_client.get_paginator("list_objects_v2")
        response_iterator = paginator.paginate(
            Bucket=self._s3_source_bucket,
            Prefix=self._s3_source_prefix,
            PaginationConfig={"PageSize": 1000},
        )
        relevant_executions = {}
        for page in response_iterator:
            for key in page.get("Contents", []):
                self._add_or_replace_key_if_relevant(key, relevant_executions)
        return relevant_executions

    def _add_or_replace_key_if_relevant(self, key, relevant_executions):
        match = re.match(self._match_regex, key["Key"])
        if not match:
            return
        training_job, backtest_test_date, algorithm_name = match.group(1, 2, 3)
        algorithm_version, hyperparameter = match.group(4, 6)
        training_job = _require_safe_result_path_component(training_job, "training_job")
        algorithm_name = _require_safe_result_path_component(algorithm_name, "algorithm_name")
        try:
            algorithm_version = _require_safe_result_path_component(algorithm_version, "algorithm_version")
            hyperparameter = _require_safe_result_path_component(hyperparameter, "hyperparameter")
        except ValueError:
            # Ignore non-canonical nested result.json keys that can exist under metadata/deps.
            return
        if not self._training_job_id_filter.search(training_job):
            return
        if not self._algorithm_name_filter.search(algorithm_name):
            return
        if not self._algorithm_version_filter.search(algorithm_version):
            return
        if not self._is_date_in_range(backtest_test_date):
            return
        backtest_key = (backtest_test_date, algorithm_name, algorithm_version, hyperparameter)
        if (
            backtest_key not in relevant_executions
            or relevant_executions[backtest_key].execution_date < key["LastModified"]
        ):
            relevant_executions[backtest_key] = _SuccessfulSageMakerResultsComponents(
                backtest_test_date=backtest_test_date,
                algorithm_name=algorithm_name,
                algorithm_version=algorithm_version,
                training_job=training_job,
                hyperparameter=hyperparameter,
                execution_date=key["LastModified"],
                key=key["Key"],
            )

    def _is_date_in_range(self, backtest_test_date: str):
        if self._from_including_test_date and self._from_including_test_date > date.fromisoformat(backtest_test_date):
            return False
        if self._to_including_test_date and date.fromisoformat(backtest_test_date) > self._to_including_test_date:
            return False
        return True

    def _download_results(
        self, relevant_executions: dict[tuple[str, str, str, str | None], _SuccessfulSageMakerResultsComponents]
    ):
        for result_components in relevant_executions.values():
            if self._skip_data_if_already_present and self._result_json_exists(result_components):
                logger.info(
                    f"Skipping downloading of output for {result_components} because skip existing is specified and result.json for that result exists"
                )
                continue
            if self._include_output_data:
                logger.info(f"Downloading output data for result: {result_components}")
                self._download_and_extract_result_from_s3(result_components, self.OUTPUT_FILE, "output")
            if self._include_metadata:
                logger.info(f"Downloading metadata for result: {result_components}")
                self._download_and_extract_result_from_s3(result_components, self.METADATA_FILE)
            if (not self._include_metadata) and (not self._include_output_data):
                logger.info(f"Downloading the result.json for {result_components}")
                hyperparam = "" if result_components.hyperparameter is None else f"-{result_components.hyperparameter}"
                algo_id = f"{result_components.algorithm_name}@{result_components.algorithm_version}{hyperparam}"
                remote_result_json_path = f"{algo_id}/result.json"
                local_result_json_path = f"{algo_id}/last_test_date_{result_components.backtest_test_date}/result.json"
                self._download_result_from_s3(
                    result_components, remote_result_json_path, os.path.join("meta", local_result_json_path)
                )

    def _download_result_from_s3(
        self,
        result_components: _SuccessfulSageMakerResultsComponents,
        remote_result_file: str,
        dest_file: str,
    ):
        destination = resolve_path_within_base(self._dest_base_dir, dest_file)
        os.makedirs(destination.parent, exist_ok=True)
        s3_prefix_results = self._get_base_prefix_to_download(result_components)
        self._s3_client.download_file(
            self._s3_source_bucket,
            f"{s3_prefix_results}/{remote_result_file}",
            str(destination),
        )

    def _download_and_extract_result_from_s3(
        self, result_components: _SuccessfulSageMakerResultsComponents, remote_result_file: str, local_sub_dir: str = ""
    ):
        tmp_download_location = None
        try:
            s3_prefix_results = self._get_base_prefix_to_download(result_components)
            with tempfile.NamedTemporaryFile(delete=False) as temp_file:
                self._s3_client.download_fileobj(
                    self._s3_source_bucket,
                    f"{s3_prefix_results}/{remote_result_file}",
                    temp_file,
                )
                tmp_download_location = temp_file.name
            with tarfile.open(tmp_download_location) as file:
                safe_extract_tar_archive(file, self._dest_base_dir.joinpath(local_sub_dir))
        finally:
            if tmp_download_location:
                trydelete(tmp_download_location)

    def _get_base_prefix_to_download(self, result_components: _SuccessfulSageMakerResultsComponents):
        s3_base_prefix_to_download = (
            f"{self._s3_source_prefix}/{result_components.training_job}" f"-{result_components.backtest_test_date}"
        )
        return s3_base_prefix_to_download

    def _result_json_exists(self, result_components):
        hyperparam = "" if result_components.hyperparameter is None else f"-{result_components.hyperparameter}"
        return (
            len(
                glob.glob(
                    str(
                        self._dest_base_dir.joinpath(
                            f"meta/{result_components.algorithm_name}@{result_components.algorithm_version}{hyperparam}/*last_test_date_{result_components.backtest_test_date}/result.json"
                        )
                    )
                )
            )
            >= 1
        )
