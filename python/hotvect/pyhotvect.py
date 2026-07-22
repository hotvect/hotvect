import copy
import getpass
import glob
import json
import logging
import os
import re
import shutil
import socket
import sys
import time
import zipfile
from collections.abc import Callable
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, tzinfo
from functools import reduce
from pathlib import Path
from typing import Any, NamedTuple

import boto3
import psutil
from botocore.exceptions import ClientError
from jinja2 import Template

import hotvect.hotvectjar
from hotvect.algorithm_definition_overrides import (
    apply_algorithm_definition_override,
    merge_algorithm_definition_override_fragments,
)
from hotvect.benchmark_contract import BENCHMARK_CONTRACT_KEY, build_benchmark_contract
from hotvect.jvm_args import normalize_runtime_jvm_args
from hotvect.s3_utils import download_s3_file, require_s3_uri
from hotvect.utils import (
    as_locally_available_content,
    assert_s3_directory_uri_empty,
    clean_dir,
    copy_or_link,
    read_algorithm_definition_from_jar,
    read_json,
    resolve_path_within_base,
    store_file,
    stream_output,
    to_local_paths,
    to_zip_archive,
    trydelete,
    verify_algorithm_hyperparameter_version,
    verify_algorithm_name,
    verify_algorithm_version,
)

logger = logging.getLogger(__name__)

_HV_LOG_FILENAME = "hv.log"
_HV_ALL_LOG_FILENAME = "hv.all.log"
_HV_LOG_FORMAT = "%(asctime)s:%(levelname)s:%(name)s:%(funcName)s:%(message)s"
_HV_LOG_HANDLER_KIND_ATTR = "_hotvect_log_kind"
_HV_LOG_HANDLER_KIND_PIPELINE = "pipeline"
_HV_LOG_HANDLER_KIND_COMBINED = "combined"


def _standard_evaluation(*args, **kwargs):
    from hotvect.evaluation import evaluation

    return evaluation.standard_evaluation(*args, **kwargs)


def _real_numbers_reward_evaluation(*args, **kwargs):
    from hotvect.evaluation import evaluation

    return evaluation.real_numbers_reward_evaluation(*args, **kwargs)


EVALUATION_FUNCTIONS = {
    "standard_evaluation": _standard_evaluation,
    "real_numbers_reward_evaluation": _real_numbers_reward_evaluation,
}

VALID_RUN_TARGETS = ("parameters", "predict", "evaluate")


def _parse_major_version(version: Any) -> int | None:
    if not isinstance(version, str):
        return None
    match = re.match(r"^\s*(\d+)(?:[.\-_]|$)", version)
    if not match:
        return None
    return int(match.group(1))


def _training_container_hotvect_major(training_container: Any) -> int | None:
    if not isinstance(training_container, str) or ":" not in training_container:
        return None
    return _parse_major_version(training_container.rsplit(":", 1)[1])


def _algorithm_hotvect_major(algorithm_definition: dict[str, Any]) -> int | None:
    hotvect_major = _parse_major_version(algorithm_definition.get("hotvect_version"))
    if hotvect_major is not None:
        return hotvect_major
    return _training_container_hotvect_major(algorithm_definition.get("training_container"))


def _uses_legacy_model_parameter_path(algorithm_definition: dict[str, Any]) -> bool:
    major = _algorithm_hotvect_major(algorithm_definition)
    return major is not None and major <= 9


def _normalize_run_target(target: str) -> str:
    if target not in VALID_RUN_TARGETS:
        raise ValueError(f"Invalid run target: {target!r}. Expected one of {VALID_RUN_TARGETS}.")
    return target


# Courtesy of https://stackoverflow.com/a/23705687/234901
# Under CC BY-SA 3.0
class SimpleUTC(tzinfo):
    def tzname(self, **kwargs):
        return "UTC"

    def utcoffset(self, dt):
        return timedelta(0)


PARTITION_CACHE_CHANNEL_NAME = "hotvect_partition_cache"
ENCODE_PARTITION_STARTED_MARKER = "_STARTED"
ENCODE_PARTITION_SUCCESS_MARKER = "_SUCCESS"
ENCODE_PARTITION_SUCCESS_MARKER_VERSION = 1
ENCODE_PARTITION_SUCCESS_MARKER_TYPE = "hotvect_encode_partition_cache"
ENCODE_PARTITION_STARTED_MARKER_TYPE = "hotvect_encode_partition_cache_write"


class AlgorithmPipelineContext(NamedTuple):
    # Algorithm jar
    algorithm_jar_path: Path

    # Output locations
    data_base_path: Path
    metadata_base_path: Path
    output_base_path: Path

    # Parameter sources
    # Optional for backward compatibility with older `hv` entrypoints; when omitted, state sources are read from `data_base_path`.
    state_source_base_path: Path | None = None

    # Execution parameters
    jvm_options: list[str] | None = None
    max_threads: int | None = None
    queue_length: int | None = None
    read_queue_length: int | None = None
    write_queue_length: int | None = None
    batch_size: int | None = None
    additional_jar_files: list[Path] | None = None
    benchmark_contract: dict[str, Any] | None = None
    partition_cache_base_paths: dict[str, Path] | None = None
    sagemaker_training_job_name: str | None = None


@dataclass(frozen=True)
class DataDependency:
    algorithm_name: str
    algorithm_version: str
    data_prefix: str
    data_dates: set[date]
    data_type: str  # e.g., 'train', 'test', or 'state'
    additional_properties: dict[str, Any] = field(default_factory=dict)


class AlgorithmPipeline:
    def __init__(
        self,
        algorithm_pipeline_context: AlgorithmPipelineContext,
        algorithm_definition: str | dict[str, Any] | tuple[str, dict[str, Any]],
        last_test_time: date,
        evaluation_func: Callable[[str], dict[str, Any]] | None,
        hyperparameter_version: str | None = None,
        parameter_version: str | None = None,
        clean_output_after_run: bool = False,
        encode_test_data: bool = False,
        execute_audit: bool = False,
        execute_performance_test: bool = True,
        run_target: str = "evaluate",
        data_environment: str = "production",
        ran_at: str | None = None,
    ):
        self.clean_output_after_run = clean_output_after_run
        self.encode_test_data = encode_test_data
        self.execute_audit = execute_audit
        self.execute_performance_test = execute_performance_test
        self.run_target = _normalize_run_target(run_target)
        self.data_environment = str(data_environment or "production")

        # Context
        self.algorithm_pipeline_context = algorithm_pipeline_context

        # Algorithm definition
        self.algorithm_definition_override: dict[str, Any] | None = None
        self.committed_algorithm_definition: dict[str, Any] | None = None
        if isinstance(algorithm_definition, str):
            self.algorithm_name = verify_algorithm_name(algorithm_definition)
            self.algorithm_definition: dict[str, Any] = read_algorithm_definition_from_jar(
                algorithm_name=self.algorithm_name,
                algorithm_jar_path=self.algorithm_jar_path(),
                additional_jars=(
                    self.algorithm_pipeline_context.additional_jar_files
                    if self.algorithm_pipeline_context.additional_jar_files
                    else []
                ),
            )
            if self.algorithm_definition["algorithm_name"] != self.algorithm_name:
                raise ValueError(
                    f"Algorithm name {self.algorithm_name} does not match the name in the algorithm definition {algorithm_definition}"
                )
            self.algorithm_version = verify_algorithm_version(self.algorithm_definition["algorithm_version"])
            self.committed_algorithm_definition = copy.deepcopy(self.algorithm_definition)
            self.hyper_parameter_version = (
                "" if not hyperparameter_version else verify_algorithm_hyperparameter_version(hyperparameter_version)
            )
        elif isinstance(algorithm_definition, dict):
            self.algorithm_name = verify_algorithm_name(algorithm_definition["algorithm_name"])
            self.algorithm_definition = copy.deepcopy(algorithm_definition)
            self.committed_algorithm_definition = copy.deepcopy(self.algorithm_definition)
            self.algorithm_version = verify_algorithm_version(self.algorithm_definition["algorithm_version"])
            if hyperparameter_version and "hyperparameter_version" in self.algorithm_definition.keys():
                self.hyper_parameter_version = (
                    f"{self.algorithm_definition['hyperparameter_version']}-{hyperparameter_version}"
                )
            else:
                self.hyper_parameter_version = self.algorithm_definition.get(
                    "hyperparameter_version", hyperparameter_version
                )
        else:
            # Algorithm has algorithm definition override
            algorithm_name, algorithm_definition_override = algorithm_definition
            self.algorithm_definition_override = (
                copy.deepcopy(algorithm_definition_override) if algorithm_definition_override is not None else None
            )
            self.algorithm_name = verify_algorithm_name(algorithm_name)
            algo_def = read_algorithm_definition_from_jar(
                algorithm_name=self.algorithm_name,
                algorithm_jar_path=self.algorithm_jar_path(),
                additional_jars=(
                    self.algorithm_pipeline_context.additional_jar_files
                    if self.algorithm_pipeline_context.additional_jar_files
                    else []
                ),
            )
            self.algorithm_definition = algo_def
            if self.algorithm_definition["algorithm_name"] != self.algorithm_name:
                raise ValueError(
                    f"Algorithm name {self.algorithm_name} does not match the name in the algorithm definition {algorithm_definition}"
                )
            self.algorithm_version = verify_algorithm_version(self.algorithm_definition["algorithm_version"])
            self.committed_algorithm_definition = copy.deepcopy(algo_def)
            self.algorithm_definition = apply_algorithm_definition_override(algo_def, algorithm_definition_override)
            if hyperparameter_version and "hyperparameter_version" in self.algorithm_definition.keys():
                self.hyper_parameter_version = (
                    f"{self.algorithm_definition['hyperparameter_version']}-{hyperparameter_version}"
                )
            else:
                self.hyper_parameter_version = self.algorithm_definition.get(
                    "hyperparameter_version", hyperparameter_version
                )

        self.algorithm_is_state = self.algorithm_definition.get("generator_factory_classname", False)
        if "algorithm_factory_classname" not in self.algorithm_definition:
            raise ValueError(
                f"Algorithm {self.algorithm_name} does not have an algorithm_factory_classname defined. All algorithms must have one."
            )
        if self.algorithm_is_state:
            # If an algorithm is a state, it cannot have any of these
            prohibited = {
                "vectorizer_factory_classname",
                "transformer_factory_classname",
                "reward_function_factory_classname",
                "encoder_factory_classname",
                "train_data_prefix",
                "train_data_spec",
                "test_data_prefix",
                "test_data_spec",
                "number_of_training_days",
                "training_lag_days",
                "training_command",
            }
            if any(x in self.algorithm_definition for x in prohibited):
                raise ValueError(
                    f'Algorithm {self.algorithm_name} has a "generator_factory_classname" defined, and is thus a simple state. It cannot have cannot have {prohibited} in the algorithm definition because they have no effect.'
                )

        # Execution parameters
        self.last_test_time = last_test_time
        self.training_lag_days = self.algorithm_definition.get("training_lag_days", 1)
        if not isinstance(self.training_lag_days, int):
            raise ValueError("training_lag_days must be an integer")
        self.training_lag = timedelta(days=self.training_lag_days)

        # Parameter version and runtime
        nowtime = datetime.utcnow().replace(tzinfo=SimpleUTC())
        self.ran_at: str = ran_at or nowtime.isoformat()
        # If no parameter version was specified, we use ran_at
        if parameter_version:
            self.parameter_version = parameter_version
        else:
            self.parameter_version = f"last_test_date_{self.last_test_time}"

        # Retrieve the evaluation function, if none found -> use default standard_evaluation
        # Supports both:
        # - "evaluation_function": "standard_evaluation"
        # - "evaluation_function": {"name": "standard_evaluation", "arguments": {...}}
        maybe_evaluation_function = _recursive_get(
            self.algorithm_definition, ["hotvect_execution_parameters", "evaluation_function"]
        )
        if isinstance(maybe_evaluation_function, dict):
            algo_def_evaluation_function = maybe_evaluation_function.get("name")
            if not algo_def_evaluation_function:
                raise ValueError(
                    "Algorithm definition evaluation_function is a dict but has no 'name'. "
                    "Expected {'name': <string>, 'arguments': <dict>}."
                )
        elif maybe_evaluation_function is None:
            algo_def_evaluation_function = None
        elif isinstance(maybe_evaluation_function, str):
            algo_def_evaluation_function = maybe_evaluation_function
        else:
            raise ValueError(
                "Algorithm definition evaluation_function must be a string, a dict, or omitted. "
                f"Got: {type(maybe_evaluation_function)}"
            )
        if algo_def_evaluation_function:
            # Fetch the callable from the dictionary based on the string key
            if algo_def_evaluation_function in EVALUATION_FUNCTIONS:
                self.evaluation_function = EVALUATION_FUNCTIONS[algo_def_evaluation_function]
            else:
                raise ValueError(
                    f"Evaluation function '{algo_def_evaluation_function}' is not defined in the EVALUATION_FUNCTIONS dictionary. Available keys are {EVALUATION_FUNCTIONS.keys()}"
                )
        else:
            # Fallback to the provided evaluation function
            self.evaluation_function = evaluation_func

        # Prepare dependency pipelines
        cache_inherited_parameters: dict[str, Any] = {}
        cache_base_dir = _recursive_get(self.algorithm_definition, ["hotvect_execution_parameters", "cache_base_dir"])
        if cache_base_dir:
            cache_inherited_parameters["cache_base_dir"] = cache_base_dir
        cache_override = _recursive_get(self.algorithm_definition, ["hotvect_execution_parameters", "cache"])
        if cache_override is not None:
            cache_inherited_parameters["cache"] = cache_override
        cache_scope = _recursive_get(self.algorithm_definition, ["hotvect_execution_parameters", "cache_scope"])
        if cache_scope is not None:
            cache_inherited_parameters["cache_scope"] = cache_scope
        cache_refresh = _recursive_get(self.algorithm_definition, ["hotvect_execution_parameters", "cache_refresh"])
        if cache_refresh is not None:
            cache_inherited_parameters["cache_refresh"] = cache_refresh
        execution_parameters = self.algorithm_definition.get("hotvect_execution_parameters", {})
        encode_parameters = execution_parameters.get("encode") if isinstance(execution_parameters, dict) else None
        if isinstance(encode_parameters, dict) and "cache" in encode_parameters:
            cache_inherited_parameters.setdefault("encode", {})["cache"] = encode_parameters["cache"]

        self.dependency_pipelines: dict[str, AlgorithmPipeline] = {}
        if self.algorithm_definition.get("dependencies"):
            dependencies = self.algorithm_definition["dependencies"]

            if isinstance(dependencies, list):
                # Dependencies are specified as names, so no algorithm definition overrides
                for algorithm_name in dependencies:
                    assert isinstance(algorithm_name, str)
                    if algorithm_name == self.algorithm_name:
                        raise ValueError(
                            f"Invalid algorithm definition: '{self.algorithm_name}' cannot list itself in 'dependencies'."
                        )
                    algo_def_override = {}
                    if cache_inherited_parameters:
                        inherited_def = {"hotvect_execution_parameters": cache_inherited_parameters}
                        algo_def_override = merge_algorithm_definition_override_fragments(
                            inherited_def, algo_def_override
                        )
                    pipeline = AlgorithmPipeline(
                        algorithm_pipeline_context=self.algorithm_pipeline_context,
                        algorithm_definition=(algorithm_name, algo_def_override),
                        last_test_time=self.last_test_time,
                        hyperparameter_version=self.hyper_parameter_version,
                        parameter_version=self.parameter_version,
                        evaluation_func=self.evaluation_function,
                        encode_test_data=encode_test_data,
                        execute_audit=execute_audit,
                        run_target="parameters",
                        data_environment=self.data_environment,
                    )
                    self.dependency_pipelines[algorithm_name] = pipeline
            elif isinstance(dependencies, dict):
                if self.algorithm_name in dependencies:
                    raise ValueError(
                        f"Invalid algorithm definition/override: 'dependencies' contains '{self.algorithm_name}'. "
                        "Self-dependency overrides are not supported. "
                        "Move those fields to the top-level override, or apply the override file to the parent "
                        "algorithm (where this algorithm is a true dependency)."
                    )
                # Dependencies are specified as dict, so there are algorithm definition overrides
                for algorithm_name, algo_def_override in dependencies.items():
                    assert isinstance(algorithm_name, str)
                    verify_algorithm_name(algorithm_name)
                    assert isinstance(algo_def_override, dict)
                    if cache_inherited_parameters:
                        inherited_def = {"hotvect_execution_parameters": cache_inherited_parameters}
                        algo_def_override = merge_algorithm_definition_override_fragments(
                            inherited_def, algo_def_override
                        )
                    pipeline = AlgorithmPipeline(
                        algorithm_pipeline_context=self.algorithm_pipeline_context,
                        algorithm_definition=(algorithm_name, algo_def_override),
                        last_test_time=self.last_test_time,
                        hyperparameter_version=self.hyper_parameter_version,
                        parameter_version=self.parameter_version,
                        evaluation_func=self.evaluation_function,
                        encode_test_data=encode_test_data,
                        execute_audit=execute_audit,
                        run_target="parameters",
                        data_environment=self.data_environment,
                    )
                    self.dependency_pipelines[algorithm_name] = pipeline
            else:
                raise ValueError(f"Dependency object has unexpected type: {dependencies}")
        logger.info(f"Initialized: {self.__dict__}")
        self.available_parameter_cache_path = None

    def hyperparameter_slug(self) -> str:
        ret = self.algorithm_id_slug()
        if self.hyper_parameter_version:
            return f"{ret}-{self.hyper_parameter_version}"
        else:
            return ret

    def _cache_refresh_enabled(self) -> bool:
        value = _recursive_get(self.algorithm_definition, ["hotvect_execution_parameters", "cache_refresh"], False)
        if not isinstance(value, bool):
            raise ValueError("Invalid cache_refresh value. Use true or false.")
        if not value:
            return False
        if not _recursive_get(self.algorithm_definition, ["hotvect_execution_parameters", "cache_base_dir"]):
            raise ValueError("cache_refresh requires effective cache_base_dir.")
        if self._root_cache_override() != "run" or "partition" in self._encode_cache_modes():
            raise ValueError(
                "cache_refresh requires effective cache mode 'run'. Partition cache refresh is not supported."
            )
        return True

    def _cache_scope(self) -> str:
        value = _recursive_get(self.algorithm_definition, ["hotvect_execution_parameters", "cache_scope"], "hyperparam")
        if value is None:
            return "hyperparam"
        value = str(value)
        allowed = {"major", "minor", "patch", "hyperparam"}
        if value not in allowed:
            raise ValueError(f"Invalid cache_scope: {value}. Allowed: {sorted(allowed)}")
        return value

    @staticmethod
    def _semver_from_algorithm_version(version: str) -> tuple[int, int, int] | None:
        match = re.fullmatch(r"v?(\d+)\.(\d+)\.(\d+)(?:[-+._].*)?", version)
        if not match:
            return None
        return int(match.group(1)), int(match.group(2)), int(match.group(3))

    def _cache_algorithm_version(self) -> str:
        scope = self._cache_scope()
        if scope == "hyperparam":
            # Hyperparam scope keeps the original algorithm version string. The hyperparameter version (if any)
            # is appended separately via _cache_algorithm_key().
            return self.algorithm_version

        parsed = self._semver_from_algorithm_version(self.algorithm_version)
        if not parsed:
            if scope == "patch":
                return self.algorithm_version
            raise ValueError(
                f"cache_scope={scope} requires a semver-like algorithm_version (X.Y.Z); got {self.algorithm_version}"
            )
        major, minor, patch = parsed
        if scope == "major":
            return str(major)
        if scope == "minor":
            return f"{major}.{minor}"
        if scope == "patch":
            return f"{major}.{minor}.{patch}"
        raise ValueError(f"Unexpected cache_scope: {scope}")

    def _cache_algorithm_key(self) -> str:
        base = f"{self.algorithm_name}@{self._cache_algorithm_version()}"
        scope = self._cache_scope()
        if scope == "hyperparam" and self.hyper_parameter_version:
            return f"{base}-{self.hyper_parameter_version}"
        return base

    def _cache_algorithm_root(self) -> str | None:
        cache_base_dir = _recursive_get(self.algorithm_definition, ["hotvect_execution_parameters", "cache_base_dir"])
        if not cache_base_dir:
            return None
        return os.path.join(cache_base_dir, self._cache_algorithm_key())

    def _root_cache_override(self) -> bool | str | None:
        cache_override = _recursive_get(self.algorithm_definition, ["hotvect_execution_parameters", "cache"])
        if cache_override is None or cache_override is True or cache_override is False:
            return cache_override
        if isinstance(cache_override, str) and cache_override in {"run", "partition"}:
            return cache_override
        raise ValueError("Invalid cache value. Use true, false, 'run', or 'partition'.")

    def _task_cache_override(self, task_paths: list[str]) -> tuple[bool | str | None, bool]:
        task_cache_override = _recursive_get(
            self.algorithm_definition, ["hotvect_execution_parameters", *task_paths, "cache"]
        )
        if task_cache_override is not None:
            return task_cache_override, True
        return self._root_cache_override(), False

    def _encode_cache_modes(self) -> set[str]:
        cache_override, _ = self._task_cache_override(["encode"])
        cache_base_dir = _recursive_get(self.algorithm_definition, ["hotvect_execution_parameters", "cache_base_dir"])
        if cache_override is False:
            return set()
        if cache_override is True:
            return {"run", "partition"}
        if cache_override == "run":
            return {"run"}
        if cache_override == "partition":
            return {"partition"}
        if cache_override is not None and not isinstance(cache_override, str):
            raise ValueError("Invalid encode.cache value. Use true, false, 'run', 'partition', or an explicit path.")
        if cache_override is None:
            if not cache_base_dir:
                return set()
            return {"run", "partition"}
        return {"run"}

    def _encode_partition_cache_enabled(self) -> bool:
        return "partition" in self._encode_cache_modes()

    def _uses_encode_partition_cache(self) -> bool:
        return (
            self._encode_partition_cache_enabled()
            and self.should_train()
            and bool(self.algorithm_definition.get("number_of_training_days", 0))
        )

    def _should_encode_with_partition_cache(self) -> bool:
        if not self._encode_partition_cache_enabled():
            return False
        if not self.should_train():
            return False
        if self._uses_encode_partition_cache():
            return True
        cache_override, _ = self._task_cache_override(["encode"])
        if cache_override == "partition":
            raise ValueError("Encode partition cache requires date-windowed training with number_of_training_days.")
        return False

    def _encode_run_cache_enabled(self) -> bool:
        return "run" in self._encode_cache_modes()

    def algorithm_id_slug(self) -> str:
        return f"{self.algorithm_name}@{self.algorithm_version}"

    def algorithm_jar_path(self) -> Path:
        return self.algorithm_pipeline_context.algorithm_jar_path

    def _training_dates(self):
        num_of_training_days = self.algorithm_definition.get("number_of_training_days", 0)
        return [self.last_test_time - self.training_lag - timedelta(days=x) for x in range(num_of_training_days)]

    def _get_train_data_prefix(self):
        data_prefix = self.algorithm_definition.get("train_data_spec", {}).get("data_prefix")
        if not data_prefix:
            data_prefix = self.algorithm_definition.get("train_data_prefix")
        return data_prefix

    def _get_test_data_prefix(self):
        data_prefix = self.algorithm_definition.get("test_data_spec", {}).get("data_prefix")
        if not data_prefix:
            data_prefix = self.algorithm_definition.get("test_data_prefix")
        return data_prefix

    def _get_prediction_spec(self) -> dict[str, Any] | None:
        prediction_spec = self.algorithm_definition.get("prediction_spec")
        if prediction_spec is None:
            return None
        if not isinstance(prediction_spec, dict):
            raise ValueError(
                f"prediction_spec must be a dict when present, got {type(prediction_spec)} for {self.algorithm_name}"
            )
        return prediction_spec

    def _require_prediction_spec(self) -> dict[str, Any]:
        prediction_spec = self._get_prediction_spec()
        if prediction_spec is None:
            raise ValueError(
                f"target=predict requires prediction_spec in the algorithm definition for {self.algorithm_name}"
            )
        return prediction_spec

    def _get_prediction_data_prefix(self) -> str | None:
        prediction_spec = self._get_prediction_spec()
        if not prediction_spec:
            return None
        data_prefix = prediction_spec.get("data_prefix")
        if data_prefix is not None and not isinstance(data_prefix, str):
            raise ValueError(
                f"prediction_spec.data_prefix must be a string, got {type(data_prefix)} for {self.algorithm_name}"
            )
        return data_prefix

    def _prediction_dates(self) -> list[date]:
        prediction_spec = self._require_prediction_spec()
        number_of_days = prediction_spec.get("number_of_days")
        lag_days = prediction_spec.get("lag_days")
        if not isinstance(number_of_days, int) or number_of_days <= 0:
            raise ValueError(
                f"prediction_spec.number_of_days must be a positive integer for {self.algorithm_name}; "
                f"got {number_of_days!r}"
            )
        if not isinstance(lag_days, int) or lag_days < 0:
            raise ValueError(
                f"prediction_spec.lag_days must be a non-negative integer for {self.algorithm_name}; "
                f"got {lag_days!r}"
            )
        start_date = self.last_test_time - timedelta(days=lag_days)
        return [start_date - timedelta(days=x) for x in range(number_of_days)]

    def prediction_data_paths(self) -> list[str]:
        prediction_data_prefix = self._get_prediction_data_prefix()
        if not prediction_data_prefix:
            raise ValueError(f"prediction_spec.data_prefix is required for {self.algorithm_name}")

        root_dir = os.path.join(self.algorithm_pipeline_context.data_base_path, prediction_data_prefix)
        if not os.path.isdir(root_dir):
            raise ValueError(
                f"The specified directory for prediction data does not exist: {os.path.abspath(root_dir)}. "
                "Existing prediction data path is required when target=predict"
            )

        return to_local_paths(
            root_dir,
            self._prediction_dates(),
            fail_if_unavailable=True,
        )

    def _prediction_output_uri_spec(self) -> Any:
        prediction_spec = self._require_prediction_spec()
        if "output_uri" in prediction_spec:
            return prediction_spec["output_uri"]
        raise ValueError(f"prediction_spec.output_uri is required for target=predict on {self.algorithm_name}")

    def _prediction_template_context(self) -> dict[str, str]:
        return {
            "algorithm_name": self.algorithm_name,
            "algorithm_version": self.algorithm_version,
            "hyperparameter_slug": self.hyperparameter_slug(),
            "parameter_version": self.parameter_version,
            "last_test_date": self.last_test_time.isoformat(),
            "last_test_time": self.last_test_time.isoformat(),
            "data_environment": self.data_environment,
            "ran_at": self.ran_at,
        }

    def prediction_output_uri(self) -> str:
        output_uri_spec = self._prediction_output_uri_spec()
        if isinstance(output_uri_spec, dict):
            if self.data_environment not in output_uri_spec:
                raise ValueError(
                    f"Environment {self.data_environment!r} not found in prediction_spec.output_uri for "
                    f"{self.algorithm_name}. Available environments: {sorted(output_uri_spec.keys())}"
                )
            output_uri_template = output_uri_spec[self.data_environment]
        else:
            output_uri_template = output_uri_spec

        if not isinstance(output_uri_template, str) or not output_uri_template.strip():
            raise ValueError(f"prediction_spec.output_uri must resolve to a non-empty string for {self.algorithm_name}")

        output_uri = Template(output_uri_template).render(self._prediction_template_context()).strip()
        if not output_uri:
            raise ValueError(f"prediction_spec.output_uri rendered to an empty string for {self.algorithm_name}")
        return output_uri

    def training_data_paths(self):
        return to_local_paths(
            os.path.join(self.algorithm_pipeline_context.data_base_path, self._get_train_data_prefix() or "train"),
            self._training_dates(),
        )

    def _test_dates(self):
        return [self.last_test_time]

    def test_data_paths(self) -> list[str]:
        testing_dates = self._test_dates()
        # For convenience, when test data is not available, it returns an empty list rather than failing
        root_dir = os.path.join(self.algorithm_pipeline_context.data_base_path, self._get_test_data_prefix() or "test")
        if not os.path.isdir(root_dir):
            # It is legal to specify empty test data
            # However, to avoid this being a mistake, code requires that at least the root dir exists
            raise ValueError(
                f"The specified directory for test data does not exist: {os.path.abspath(root_dir)}. "
                "Existing test data path is required, even if test is meant to be skipped "
                "(to avoid it being skipped by mistake)"
            )
        return to_local_paths(
            root_dir,
            testing_dates,
            fail_if_unavailable=False,
        )

    def data_dependencies(self, *, target: str | None = None) -> list[DataDependency]:
        ret = []
        effective_target = self._resolve_run_target(evaluate=True, target=target)

        def extract_additional_properties(data_spec):
            if not data_spec:
                return {}
            main_keys = ["data_prefix", "number_of_days", "lag_days", "output_uri"]
            return {k: v for k, v in data_spec.items() if k not in main_keys}

        # Add train data dependency if present
        train_data_prefix = self._get_train_data_prefix()
        if train_data_prefix:
            ret.append(
                DataDependency(
                    algorithm_name=self.algorithm_name,
                    algorithm_version=self.algorithm_version,
                    data_prefix=train_data_prefix,
                    data_dates=set(self._training_dates()),
                    data_type="train",
                    additional_properties=extract_additional_properties(
                        self.algorithm_definition.get("train_data_spec")
                    ),
                )
            )

        if effective_target == "evaluate":
            test_data_prefix = self._get_test_data_prefix()
            if test_data_prefix:
                ret.append(
                    DataDependency(
                        algorithm_name=self.algorithm_name,
                        algorithm_version=self.algorithm_version,
                        data_prefix=test_data_prefix,
                        data_dates=set(self._test_dates()),
                        data_type="test",
                        additional_properties=extract_additional_properties(
                            self.algorithm_definition.get("test_data_spec")
                        ),
                    )
                )
        elif effective_target == "predict":
            prediction_spec = self._require_prediction_spec()
            prediction_data_prefix = self._get_prediction_data_prefix()
            if prediction_data_prefix:
                ret.append(
                    DataDependency(
                        algorithm_name=self.algorithm_name,
                        algorithm_version=self.algorithm_version,
                        data_prefix=prediction_data_prefix,
                        data_dates=set(self._prediction_dates()),
                        data_type="prediction",
                        additional_properties=extract_additional_properties(prediction_spec),
                    )
                )

        # When a prebuilt parameter package is pinned, state generation is skipped and raw source_data
        # channels should not be auto-attached for SageMaker submission.
        uses_prebuilt_parameters = bool(
            self.algorithm_definition.get("hotvect_execution_parameters", {}).get("with_parameter")
        )
        if not uses_prebuilt_parameters:
            # Add state data dependencies
            source_data = self.algorithm_definition.get("source_data", {})
            for source_prefix_name, per_source_config in source_data.items():
                data_prefix = per_source_config.get("data_prefix")
                number_of_days = per_source_config.get("number_of_days", None)
                lag_days = per_source_config.get("lag_days", None)

                # Validate: both must be present or both must be absent
                if (number_of_days is None) != (lag_days is None):
                    raise ValueError(
                        f"Invalid source data config for '{data_prefix}': "
                        f"number_of_days and lag_days must both be present or both be absent. "
                        f"Got number_of_days={number_of_days}, lag_days={lag_days}"
                    )

                # For non-partitioned data, use empty set for dates
                if number_of_days is None and lag_days is None:
                    dates = set()
                else:
                    start_date = self.last_test_time - timedelta(days=lag_days)
                    dates = {start_date - timedelta(days=n) for n in range(number_of_days)}

                ret.append(
                    DataDependency(
                        algorithm_name=self.algorithm_name,
                        algorithm_version=self.algorithm_version,
                        data_prefix=data_prefix,
                        data_dates=dates,
                        data_type="state",
                        additional_properties=extract_additional_properties(per_source_config),
                    )
                )

        # Include dependencies from dependency pipelines
        for dependency in self.dependency_pipelines.values():
            ret.extend(dependency.data_dependencies(target=self._target_for_dependency(dependency)))
        return ret

    def state_source_path(self, source_config: dict[str, Any]):
        data_prefix = source_config["data_prefix"]
        number_of_days = source_config.get("number_of_days", None)
        lag_days = source_config.get("lag_days", None)

        # Validate: both must be present or both must be absent
        if (number_of_days is None) != (lag_days is None):
            raise ValueError(
                f"Invalid source data config for '{data_prefix}': "
                f"number_of_days and lag_days must both be present or both be absent. "
                f"Got number_of_days={number_of_days}, lag_days={lag_days}"
            )

        base_dir = (
            self.algorithm_pipeline_context.state_source_base_path or self.algorithm_pipeline_context.data_base_path
        )
        root_dir = os.path.join(base_dir, data_prefix)

        if not os.path.isdir(root_dir):
            raise ValueError(
                f"State source data {data_prefix} does not exist: {os.path.abspath(root_dir)}. "
                "State source data cannot be absent"
            )

        # If both are None, treat as non-partitioned static data
        if number_of_days is None and lag_days is None:
            # Return directory path for non-partitioned data
            # Java will scan the directory itself
            return [root_dir]

        # Date-partitioned data
        start_date = self.last_test_time - timedelta(days=lag_days)
        dates = [start_date - timedelta(days=n) for n in range(number_of_days)]

        # Fail if asked dates are not available
        return to_local_paths(
            root_dir,
            dates,
            fail_if_unavailable=True,
        )

    def state_output_path(self) -> str:
        state_output_filename = self.algorithm_definition.get("state_output_filename", None)
        if state_output_filename is None:
            # Return directory path when filename is omitted
            return self.output_path()
        if not isinstance(state_output_filename, str):
            raise ValueError("state_output_filename must be a relative path string")
        return str(resolve_path_within_base(self.output_path(), state_output_filename))

    def metadata_path(self) -> str:
        return os.path.join(
            self.algorithm_pipeline_context.metadata_base_path,
            self.hyperparameter_slug(),
            self.parameter_version,
        )

    def output_path(self) -> str:
        return os.path.join(
            self.algorithm_pipeline_context.output_base_path,
            self.hyperparameter_slug(),
            self.parameter_version,
        )

    def cache_path(self) -> str:
        return os.path.join(self.output_path(), "cache")

    def encoded_data_file_path(self) -> str:
        """Returns the encoded data directory path."""
        return os.path.join(self.output_path(), "encoded")

    def encoded_schema_description_file_path(self) -> str:
        return os.path.join(self.output_path(), "encoded-schema-description")

    def encoded_test_data_file_path(self) -> str:
        return os.path.join(self.output_path(), "test-encoded")

    def parameter_file_path(self) -> str:
        return os.path.join(self.output_path(), "model_parameter")

    def test_result_file_path(self) -> str:
        return os.path.join(self.output_path(), "prediction")

    def prediction_result_staging_path(self) -> str:
        return os.path.join(self.output_path(), "predict-output-staging")

    def audit_data_file_path(self) -> str:
        return os.path.join(self.output_path(), "audit")

    def predict_parameter_file_path(self) -> str:
        return os.path.join(
            self.output_path(),
            f"{self.hyperparameter_slug()}@{self.parameter_version}.parameters.zip",
        )

    def encode_parameter_file_path(self) -> str:
        return os.path.join(
            self.output_path(),
            f"{self.hyperparameter_slug()}@{self.parameter_version}.encode.parameters.zip",
        )

    def _write_algorithm_definition(self) -> str:
        """Write algorithm definition so that Java can read it"""
        algorithm_definition_path = os.path.join(self.metadata_path(), "algorithm_definition.json")
        trydelete(algorithm_definition_path)
        with open(algorithm_definition_path, "w") as fp:
            json.dump(self.algorithm_definition, fp)
        return algorithm_definition_path

    def _write_data(self, data: dict, dest_file_name: str) -> str:
        """Write algorithm definition so that Java can read it"""
        dest = dest_file_name  # Use path as-is (callers provide full path)
        os.makedirs(os.path.dirname(dest), exist_ok=True)  # Ensure parent dirs exist
        trydelete(dest)
        with open(dest, "w") as fp:
            json.dump(data, fp)
        return dest

    def clean(self) -> None:
        for file in [
            self.encoded_data_file_path(),
            self.parameter_file_path(),
            self.test_result_file_path(),
            self.prediction_result_staging_path(),
        ]:
            trydelete(file)
        for directory in [
            self.metadata_path(),
            self.output_path(),
        ]:
            clean_dir(directory)
        logger.info("Cleaned output and metadata")

    @contextmanager
    def _pipe_python_logs_to_metadata_dir(self):
        """
        Pipe Python logs to files under this pipeline's metadata directory.

        Spec:
        - Always write logs for this pipeline run to `metadata_path()/hv.log`.
        - Dependency runs must not mix/duplicate into parent `hv.log` (only the active pipeline's `hv.log` handler is
          attached at a time).
        - The outermost pipeline run also writes a live combined log to `metadata_path()/hv.all.log`.
        """
        metadata_dir = Path(self.metadata_path())
        metadata_dir.mkdir(parents=True, exist_ok=True)
        log_path = metadata_dir / _HV_LOG_FILENAME

        root_logger = logging.getLogger()
        formatter = logging.Formatter(_HV_LOG_FORMAT)

        suspended_pipeline_handlers = []
        for existing_handler in list(root_logger.handlers):
            if getattr(existing_handler, _HV_LOG_HANDLER_KIND_ATTR, None) == _HV_LOG_HANDLER_KIND_PIPELINE:
                root_logger.removeHandler(existing_handler)
                suspended_pipeline_handlers.append(existing_handler)

        combined_handler = None
        combined_log_path = None
        owns_combined_handler = False
        for existing_handler in root_logger.handlers:
            if getattr(existing_handler, _HV_LOG_HANDLER_KIND_ATTR, None) == _HV_LOG_HANDLER_KIND_COMBINED:
                combined_handler = existing_handler
                break
        if combined_handler is None:
            combined_log_path = metadata_dir / _HV_ALL_LOG_FILENAME
            combined_handler = logging.FileHandler(combined_log_path, mode="a", encoding="utf-8")
            combined_handler.setLevel(logging.INFO)
            combined_handler.setFormatter(formatter)
            setattr(combined_handler, _HV_LOG_HANDLER_KIND_ATTR, _HV_LOG_HANDLER_KIND_COMBINED)
            root_logger.addHandler(combined_handler)
            owns_combined_handler = True

        pipeline_handler = logging.FileHandler(log_path, mode="a", encoding="utf-8")
        pipeline_handler.setLevel(logging.INFO)
        pipeline_handler.setFormatter(formatter)
        setattr(pipeline_handler, _HV_LOG_HANDLER_KIND_ATTR, _HV_LOG_HANDLER_KIND_PIPELINE)
        root_logger.addHandler(pipeline_handler)
        try:
            logger.info("Python logs: %s", log_path)
            if owns_combined_handler:
                logger.info("Combined Python logs: %s", combined_log_path)
            logger.info("======== BEGIN PIPELINE (%s) ========", self.hyperparameter_slug())
            logger.info("parameter_version=%s", self.parameter_version)
            logger.info("metadata_path=%s", metadata_dir)
            logger.info("=====================================")
            yield log_path
        finally:
            try:
                logger.info("========= END PIPELINE (%s) =========", self.hyperparameter_slug())
                logger.info("=====================================")
            except Exception:
                # Logging should not prevent handler cleanup.
                pass

            try:
                root_logger.removeHandler(pipeline_handler)
            finally:
                pipeline_handler.close()

            for suspended_handler in suspended_pipeline_handlers:
                root_logger.addHandler(suspended_handler)

            if owns_combined_handler:
                try:
                    root_logger.removeHandler(combined_handler)
                finally:
                    combined_handler.close()

    def _stream_output_to_stage_log(self, *, stage: str, cmd: list[str]) -> None:
        metadata_dir = self._stage_metadata_dir(stage)
        stdout_stderr_log_path = os.path.join(metadata_dir, "stdout-stderr.log")
        with open(stdout_stderr_log_path, "a", encoding="utf-8") as fp:

            def _display(chunk: str) -> None:
                sys.stdout.write(chunk)
                fp.write(chunk)
                fp.flush()

            env = os.environ.copy()
            env.setdefault("HOTVECT_PYTHON_EXECUTABLE", sys.executable)
            stream_output(cmd, _display, env=env)

    def run_all(
        self,
        clean: bool = False,
        evaluate: bool = True,
        target: str | None = None,
        prepare_raw_state_for_parent_packaging: bool = False,
    ) -> dict[str, Any]:
        start_time = time.time()
        execution_target = self._resolve_run_target(evaluate=evaluate, target=target)
        if prepare_raw_state_for_parent_packaging and execution_target != "parameters":
            raise ValueError("prepare_raw_state_for_parent_packaging=True requires target='parameters'")
        if prepare_raw_state_for_parent_packaging and not self.algorithm_is_state:
            raise ValueError("prepare_raw_state_for_parent_packaging=True is only supported for state algorithms")
        result: dict[str, Any] = {
            "algorithm_id": self.hyperparameter_slug(),
            "parameter_version": self.parameter_version,
            "test_data_time": self.last_test_time.isoformat(),
            "ran_at": self.ran_at,
            "run_target": execution_target,
            "algorithm_definition": self.algorithm_definition,
            "timing_info_sec": {},
        }
        if execution_target == "predict":
            prediction_spec = self._require_prediction_spec()
            result["prediction_spec"] = prediction_spec
            result["prediction_data_dates"] = [x.isoformat() for x in self._prediction_dates()]
            result["prediction_output_uri"] = self.prediction_output_uri()

        self.clean_output_after_run = clean

        self.clean()
        clean_dir(self.cache_path())
        logger.info(f"Cleaned output files for: {self.algorithm_name}")
        logger.info("Running %s with target=%s", self.algorithm_name, execution_target)

        with self._pipe_python_logs_to_metadata_dir():
            # Write algorithm definition so that training scripts etc. can read it
            self._write_algorithm_definition()

            # Prepare dependencies
            deps_time = time.time()
            with_parameter_cache_path = _recursive_get(
                self.algorithm_definition, ["hotvect_execution_parameters", "with_parameter"]
            )
            if with_parameter_cache_path and self.dependency_pipelines:
                logger.info(
                    f'"with_parameter" is specified for {self.algorithm_name}; skipping dependency preparation for: '
                    f"{sorted(self.dependency_pipelines.keys())}. "
                    f"Assuming dependency artifacts are bundled in the provided parameters ZIP: {with_parameter_cache_path}"
                )
                result["dependencies"] = {"skipped": "Because with_parameter was specified"}
            elif self.dependency_pipelines:
                result["dependencies"] = {}
                logger.info(f"Preparing dependencies for: {self.algorithm_name}")
                for algorithm_name, pipeline in self.dependency_pipelines.items():
                    logger.info(f"Preparing dependency: {algorithm_name} for {self.algorithm_name}")
                    dependency_target = self._target_for_dependency(pipeline)
                    dependency_result = pipeline.run_all(
                        clean=self.clean_output_after_run,
                        target=dependency_target,
                        prepare_raw_state_for_parent_packaging=(
                            dependency_target == "parameters" and bool(pipeline.algorithm_is_state)
                        ),
                    )
                    result["dependencies"][algorithm_name] = dependency_result
                logger.info(f"Prepared all dependencies: {self.dependency_pipelines.keys()} for {self.algorithm_name}")
            result["timing_info_sec"]["prepare_dependencies"] = time.time() - deps_time

            available_predict_parameter_cache_path = self.available_predict_parameter_cache_path()

            if not available_predict_parameter_cache_path:
                if self.algorithm_is_state:
                    # State algorithms: generate state but skip encode parameter packaging
                    logger.info(f"Algorithm {self.algorithm_name} is a state. Generating it")
                    encode_parameter_times = time.time()
                    self.generate_states()
                    result["package_encode_params"] = {"skipped": "State algorithms don't need encode parameters"}
                    result["timing_info_sec"]["encode_parameter"] = time.time() - encode_parameter_times
                elif self.should_train():
                    # Algorithms with training: full pipeline
                    self._step_encode_parameter(result)
                    self._step_encode(result)
                    self._step_train(result)
                else:
                    # Algorithms without training and not state: skip encode parameters
                    logger.info(f"Algorithm {self.algorithm_name} does not have a training step")
                    result["package_encode_params"] = {"skipped": "No training step"}
            else:
                logger.info(
                    f"Predict parameters available for {self.algorithm_name} ({available_predict_parameter_cache_path}), skipping encode and train"
                )
                if prepare_raw_state_for_parent_packaging:
                    logger.info(
                        "Hydrating raw state output for %s from cached predict parameters because the parent "
                        "will package the raw state",
                        self.algorithm_name,
                    )
                    self._hydrate_predict_parameter_archive(
                        available_predict_parameter_cache_path,
                        include_dependencies=True,
                    )

            if prepare_raw_state_for_parent_packaging:
                logger.info(
                    "Preparing raw state output for parent packaging for %s; writing metadata only without creating "
                    "a child predict-parameter ZIP",
                    self.algorithm_name,
                )
                package_times = time.time()
                result["package_predict_params"] = self._do_package_parameters(is_encode=False, skip_zip=True)
                result["timing_info_sec"]["package_predict_params"] = time.time() - package_times
            else:
                self._step_predict_parameter(result)

            inference_stages = ["predict", "evaluate", "performance_test", "encode_test", "audit"]
            if self.algorithm_is_state:
                logger.info(f"Algorithm {self.algorithm_name} is a state. There are no evaluation steps")
                self._skip_stages(result, inference_stages, "This algorithm is a state")
            elif execution_target == "parameters":
                if _recursive_get(self.algorithm_definition, ["hotvect_execution_parameters", "with_parameter"]):
                    logger.info(
                        f'"with_parameter" is specified for {self.algorithm_name}, skipping all steps {inference_stages}'
                    )
                    self._skip_stages(result, inference_stages, "Because with_parameter was specified")
                else:
                    logger.info("Target is parameters for %s, skipping inference stages", self.algorithm_name)
                    self._skip_stages(result, inference_stages, "Because target was parameters")
            else:
                inference_data_paths = (
                    self.prediction_data_paths() if execution_target == "predict" else self.test_data_paths()
                )
                if len(inference_data_paths) > 0:
                    logger.info(
                        "We have valid %s data for %s",
                        "prediction" if execution_target == "predict" else "test",
                        self.algorithm_name,
                    )
                    self._step_predict(result, execution_target)
                    if execution_target == "predict":
                        self._skip_stages(
                            result,
                            ["evaluate", "performance_test", "encode_test", "audit"],
                            "Because target was predict",
                        )
                    else:
                        self._step_evaluate(result, True)
                        if self.execute_performance_test:
                            self._step_performance_test(result, True)
                            self._record_benchmark_contract(result, inference_data_paths)
                        else:
                            result["performance_test"] = {
                                "skipped": "Because execute_performance_test=False was specified"
                            }
                            result["timing_info_sec"]["performance_test"] = 0.0
                            logger.info(
                                "Performance-test skipped: %s because execute_performance_test=False was specified",
                                self.algorithm_name,
                            )
                        self._step_encode_test_data(result)
                        self._step_execute_audit(result)
                else:
                    logger.info(
                        "No %s data available, skipping tasks %s for: %s",
                        "prediction" if execution_target == "predict" else "test",
                        inference_stages,
                        self.algorithm_name,
                    )
                    self._skip_stages(
                        result,
                        inference_stages,
                        (
                            "Because no prediction data was available"
                            if execution_target == "predict"
                            else "Because no test data was available"
                        ),
                    )

            if clean:
                logger.info(f"Cleaning output dir for {self.algorithm_name}")
                self.clean_output()
                logger.info(f"Cleaned output dir for {self.algorithm_name}")

            result["timing_info_sec"]["total_time"] = time.time() - start_time
            self._write_data(result, os.path.join(self.metadata_path(), "result.json"))
            logger.info(f"Completed {self.algorithm_name}: {self.__dict__}")
            return result

    def _target_when_used_as_dependency(self) -> str:
        for stage in ["predict", "evaluate", "performance-test"]:
            if (
                _recursive_get(
                    self.algorithm_definition,
                    ["hotvect_execution_parameters", stage, "enabled"],
                    None,
                )
                is True
            ):
                return "evaluate"
        return "parameters"

    def _target_for_dependency(self, dependency: "AlgorithmPipeline") -> str:
        training_image_major_version = _training_container_hotvect_major(
            self.algorithm_definition.get("training_container")
        )
        if training_image_major_version is not None and training_image_major_version < 10:
            return "evaluate"
        return dependency._target_when_used_as_dependency()

    def _resolve_run_target(self, *, evaluate: bool, target: str | None) -> str:
        if target is not None:
            return _normalize_run_target(target)
        if not evaluate:
            return "parameters"
        return _normalize_run_target(getattr(self, "run_target", "evaluate"))

    @staticmethod
    def _skip_stages(result: dict[str, Any], stages: list[str], reason: str) -> None:
        for stage in stages:
            result[stage] = {"skipped": reason}
            result["timing_info_sec"][stage] = 0.0

    def _step_execute_audit(self, result):
        if self.execute_audit:
            logger.info(f"Auditing: {self.algorithm_name}")
            audit_times = time.time()
            result["audit"] = self.audit()
            result["timing_info_sec"]["audit"] = time.time() - audit_times
            logger.info(f"Audited: {self.algorithm_name}")
        else:
            logger.info(f"Audit skipped: {self.algorithm_name} because execute_audit=False was specified")
            result["audit"] = {"skipped": "Because execute_audit=False was specified"}

    def _step_encode_test_data(self, result):
        if self.encode_test_data:
            logger.info(f"Encoding test data for: {self.algorithm_name}")
            encode_test_times = time.time()
            result["encode_test"] = self.encode_test()
            result["timing_info_sec"]["encode_test"] = time.time() - encode_test_times
            logger.info(f"Encoded test data for: {self.algorithm_name}")
        else:
            result["encode_test"] = {"skipped": "Because encode_test_data=False was specified"}
            logger.info(
                f"Encoding test data skipped: {self.algorithm_name} because encode_test_data=False was specified"
            )

    def _step_performance_test(self, result: dict[str, Any], evaluate: bool):
        perftest_times = time.time()
        result["performance_test"] = self.performance_test(evaluate)
        result["timing_info_sec"]["performance_test"] = time.time() - perftest_times

    def _record_benchmark_contract(self, result: dict[str, Any], source_paths: list[str]) -> None:
        performance_metadata = result.get("performance_test")
        if not isinstance(performance_metadata, dict) or "skipped" in performance_metadata:
            return
        result[BENCHMARK_CONTRACT_KEY] = self._build_benchmark_contract(performance_metadata, source_paths)

    def _build_benchmark_contract(
        self, performance_metadata: dict[str, Any], source_paths: list[str]
    ) -> dict[str, Any]:
        context_contract = None
        pipeline_context = getattr(self, "algorithm_pipeline_context", None)
        if pipeline_context is not None:
            context_contract = getattr(pipeline_context, "benchmark_contract", None)
        if context_contract is not None and not isinstance(context_contract, dict):
            context_contract = None

        parameter_path = None
        try:
            parameter_path = self.predict_parameter_file_path()
        except Exception:
            logger.debug("Could not resolve local predict parameter path for benchmark contract", exc_info=True)

        output_prefixes: dict[str, Any] = {}
        try:
            output_prefixes["metadata"] = self.metadata_path()
        except Exception:
            logger.debug("Could not resolve metadata path for benchmark contract", exc_info=True)
        try:
            output_prefixes["output"] = self.output_path()
        except Exception:
            logger.debug("Could not resolve output path for benchmark contract", exc_info=True)
        try:
            output_prefixes["performance_metadata"] = self._stage_metadata_dir("performance-test")
        except Exception:
            logger.debug("Could not resolve performance metadata path for benchmark contract", exc_info=True)

        return build_benchmark_contract(
            base_contract=context_contract,
            parameter_path=parameter_path,
            source_paths=source_paths,
            samples=performance_metadata.get("samples", performance_metadata.get("requested_samples")),
            sample_pool_size=performance_metadata.get(
                "sample_pool_size", performance_metadata.get("requested_sample_pool_size")
            ),
            target_rps=performance_metadata.get("target_rps", performance_metadata.get("requested_target_rps")),
            target_throughput_fraction=performance_metadata.get("requested_target_throughput_fraction"),
            max_threads=performance_metadata.get("max_threads"),
            workload_mode=performance_metadata.get(
                "workload_mode", performance_metadata.get("requested_workload_mode")
            ),
            execution_command=performance_metadata.get("execution_command"),
            output_prefixes=output_prefixes,
        )

    def _step_evaluate(self, result: dict[str, Any], evaluate: bool):
        evaluate_times = time.time()
        result["evaluate"] = self.evaluate(evaluate)
        result["timing_info_sec"]["evaluate"] = time.time() - evaluate_times

    def _step_predict(self, result: dict[str, Any], execution_target: str):
        predict_times = time.time()
        result["predict"] = self.predict(evaluate=(execution_target == "evaluate"), target=execution_target)
        result["timing_info_sec"]["predict"] = time.time() - predict_times

    def _step_predict_parameter(self, result):
        package_times = time.time()
        result["package_predict_params"] = self.package_predict_parameters()
        result["timing_info_sec"]["package_predict_params"] = time.time() - package_times

    def _step_train(self, result):
        train_times = time.time()
        result["train"] = self.train()
        result["timing_info_sec"]["train"] = time.time() - train_times

    def _step_encode(self, result):
        encode_times = time.time()
        result["encode"] = self.encode()
        result["timing_info_sec"]["encode"] = time.time() - encode_times

    def _step_encode_parameter(self, result):
        encode_parameter_times = time.time()
        if self.algorithm_is_state:
            logger.info(f"Algorithm {self.algorithm_name} is a state. Generating it")
            self.generate_states()
        result["package_encode_params"] = self.package_encode_parameters()
        result["timing_info_sec"]["encode_parameter"] = time.time() - encode_parameter_times

    def _stage_metadata_dir(self, stage: str) -> str:
        return os.path.join(self.metadata_path(), stage)

    def _stage_metadata_file(self, stage: str) -> str:
        return os.path.join(self._stage_metadata_dir(stage), "metadata.json")

    @staticmethod
    def _task_name_to_offline_util_subcommand(task_name: str) -> str | None:
        # hotvect-offline-util uses picocli subcommands (encode, predict, audit, generate-state, performance-test, ...).
        # Keep task_name as-is for JVM arg lookup (it is used to read hotvect_execution_parameters.<task>.jvm_args),
        # and translate only for CLI invocation.
        return {
            "encode": "encode",
            "predict": "predict",
            "audit": "audit",
            "performance-test": "performance-test",
            "generate-state": "generate-state",
        }.get(task_name)

    def _base_command(self, task_name: str, metadata_location: str, max_threads: int = -1) -> list[str]:
        ret = [
            "java",
            "-cp",
            f"{hotvect.hotvectjar.HOTVECT_JAR_PATH}",
        ]
        resolved_jvm_args = normalize_runtime_jvm_args(
            _get_jvm_args(self.algorithm_definition, task_name, self.algorithm_pipeline_context.jvm_options)
        )
        ret.extend(resolved_jvm_args)
        subcommand = self._task_name_to_offline_util_subcommand(task_name)
        ret.extend(
            [
                "com.hotvect.offlineutils.commandline.Main",
                *([subcommand] if subcommand else []),
                "--algorithm-jar",
                f"{self.algorithm_jar_path()}",
                "--algorithm-definition",
                self._write_algorithm_definition(),
                "--metadata-path",
                metadata_location,
            ]
        )
        if self.algorithm_pipeline_context.additional_jar_files:
            ret.extend(
                ["--additional-jars", ",".join(str(x) for x in self.algorithm_pipeline_context.additional_jar_files)]
            )
        # Execution options are subcommand-specific in picocli. (E.g., generate-state does not accept max-threads.)
        supports_execution_options = subcommand in {"encode", "predict", "audit", "performance-test"}
        if supports_execution_options:
            if max_threads >= 1:
                # Caller wants to override max threads parameter
                ret.extend(["--max-threads", str(max_threads)])
            elif self.algorithm_pipeline_context.max_threads:
                # No overrides at method level, but we have a base level config
                ret.extend(["--max-threads", str(self.algorithm_pipeline_context.max_threads)])
            # Using base config even if max threads was overriden isn't ideal, but use case for method
            # level override is performance test, in which case there should be no changes needed to
            # batch size, and queue length should merely be bigger than necessary which shouldn't be a
            # problem
            if self.algorithm_pipeline_context.queue_length:
                ret.extend(["--queue-length", str(self.algorithm_pipeline_context.queue_length)])
            if self.algorithm_pipeline_context.read_queue_length:
                ret.extend(["--read-queue-length", str(self.algorithm_pipeline_context.read_queue_length)])
            if self.algorithm_pipeline_context.write_queue_length:
                ret.extend(["--write-queue-length", str(self.algorithm_pipeline_context.write_queue_length)])
            if self.algorithm_pipeline_context.batch_size:
                ret.extend(["--batch-size", str(self.algorithm_pipeline_context.batch_size)])
        return ret

    def _resolve_cache_path(self, task_paths: list[str], cache_file_name=None) -> str | None:
        """
        Resolve the cache path for a given task.

        If 'cache_base_dir' is defined in 'hotvect_execution_parameters',
        caching is enabled by default for all tasks unless explicitly disabled.

        Parameters:
        - task_paths: List of task identifiers (e.g., ['encode']).
        - cache_file_name: Optional file name to append to the cache path.

        Returns:
        - The resolved cache path as a string if caching is enabled,
          or None if caching is disabled for the task.
        """
        # Retrieve the stage cache override, falling back to the root cache policy.
        cache_override, is_task_override = self._task_cache_override(task_paths)
        cache_base_dir = _recursive_get(self.algorithm_definition, ["hotvect_execution_parameters", "cache_base_dir"])
        if cache_override is not None and not isinstance(cache_override, (bool, str)):
            raise ValueError(f"Invalid {'.'.join(task_paths)}.cache value. Use true, false, or an explicit path.")

        # Determine caching behavior
        if task_paths == ["encode"] and not self._encode_run_cache_enabled():
            return None
        if task_paths == ["encode"] and cache_override in {True, "run"}:
            cache_override = True
        if not is_task_override and cache_override == "run":
            cache_override = True
        if not is_task_override and cache_override == "partition":
            return None
        if cache_override is False:
            # Caching is explicitly disabled for this task
            return None
        elif cache_override is True or (cache_override is None and cache_base_dir):
            # Use the default cache path
            if not cache_base_dir:
                raise ValueError(
                    "Caching is enabled but 'cache_base_dir' is not specified under 'hotvect_execution_parameters.cache_base_dir'."
                )
            template = os.path.join(
                cache_base_dir,
                self._cache_algorithm_key(),
                "runs",
                self.parameter_version,
                *task_paths,
            )
        elif isinstance(cache_override, str):
            # Use the specific cache path provided in 'cache' override
            template = cache_override
        else:
            # Caching is not enabled for this task
            return None

        # Render the cache path template
        cache_path = Template(template).render(
            hyperparameter_slug=self.hyperparameter_slug(),
            parameter_version=self.parameter_version,
        )

        # Append the cache file name if provided
        if cache_file_name:
            # If the user explicitly provided a cache path string override, it may already point to the final
            # file/directory. For example, docs show:
            #   hotvect_execution_parameters.train.cache = ".../predict-parameters.zip"
            # In that case, we should not append the file name again.
            normalized = cache_path[:-1] if cache_path.endswith("/") else cache_path
            if isinstance(cache_override, str) and os.path.basename(normalized) == cache_file_name:
                return cache_path
            return os.path.join(cache_path, cache_file_name)
        else:
            return cache_path

    def generate_states(self) -> dict:
        """Generate state files with directory-based caching"""
        output_path = self.state_output_path()
        state_dir_name = os.path.basename(output_path)
        state_cache_path = self._resolve_cache_path(["generate-state"], state_dir_name)

        # Handle S3 cache separately from local cache
        if state_cache_path and state_cache_path.startswith("s3://") and not self._cache_refresh_enabled():
            # S3 cache: download directly to output_path (no symlink needed)
            logger.info(f"Skipping state generation for {self.algorithm_name}, using S3 cache at {state_cache_path}")
            trydelete(output_path)
            available_cache_path = as_locally_available_content(state_cache_path, os.path.dirname(output_path))
            if available_cache_path:
                return {
                    "source": state_cache_path,
                    "skipped": f"Used cache at: {state_cache_path}",
                }
        elif state_cache_path and not self._cache_refresh_enabled():
            # Local cache: check if exists and symlink to avoid copying
            available_cache_path = as_locally_available_content(state_cache_path, self.cache_path())
            if available_cache_path:
                logger.info(
                    f"Skipping state generation for {self.algorithm_name}, using local cache at {state_cache_path}"
                )
                trydelete(output_path)
                copy_or_link(available_cache_path, output_path)
                return {
                    "source": state_cache_path,
                    "skipped": f"Used cache at: {state_cache_path}",
                }

        # Cache miss - generate state
        stage = "generate-state"
        metadata_dir = self._stage_metadata_dir(stage)
        clean_dir(metadata_dir)
        trydelete(output_path)
        logger.info(f"Generating state for {self.algorithm_name} at {output_path}")
        cmd = self._base_command("generate-state", metadata_dir)

        # Handle multiple source data and format as JSON
        source_data_dict = self.algorithm_definition["source_data"]
        source_json = {}
        for prefix_name, per_source_config in source_data_dict.items():
            source_paths = self.state_source_path(per_source_config)  # Returns List[str]
            source_json[prefix_name] = source_paths

        cmd.extend(["--source", json.dumps(source_json)])
        cmd.extend(["--dest", output_path])

        self._stream_output_to_stage_log(stage=stage, cmd=cmd)
        logger.info(f"Generated state for {self.algorithm_name} at {output_path}")
        metadata = read_json(self._stage_metadata_file(stage))

        if state_cache_path and "skipped" not in metadata.keys():
            # Store generated directory tree in cache
            logger.info(f"Storing state cache for {self.algorithm_name} at {state_cache_path}")
            store_file(output_path, state_cache_path)

        return metadata

    def package_encode_parameters(self) -> dict:
        encoding_parameter_cache_path = self._resolve_cache_path(["generate-state"], "encoding-parameters.zip")
        available_encoding_parameter_cache_path = (
            None
            if self._cache_refresh_enabled()
            else as_locally_available_content(encoding_parameter_cache_path, self.cache_path())
        )
        if available_encoding_parameter_cache_path:
            # We have cache
            logger.info(
                f"Skipping encode parameter packaging for {self.algorithm_name} because encoding parameter cache was used at {encoding_parameter_cache_path}"
            )
            copy_or_link(available_encoding_parameter_cache_path, self.encode_parameter_file_path())
            # Extract the contents of the zip file to self.output_path()
            with zipfile.ZipFile(available_encoding_parameter_cache_path, "r") as zip_ref:
                for member in zip_ref.namelist():
                    # Only extract the contents of the directory named self.algorithm_name
                    if member.startswith(self.algorithm_name + "/"):
                        # Remove the directory name from the member name
                        new_member_name = member[len(self.algorithm_name) + 1 :]
                        # Skip directory entries (end with / or result in empty name)
                        if not new_member_name or member.endswith("/"):
                            continue
                        # Extract the member to the output path with the new member name
                        target_path = resolve_path_within_base(self.output_path(), new_member_name)
                        target_dir = target_path.parent
                        if target_dir:
                            os.makedirs(target_dir, exist_ok=True)
                        with zip_ref.open(member) as source, open(target_path, "wb") as target:
                            shutil.copyfileobj(source, target)
            return {"sources": [available_encoding_parameter_cache_path], "skipped": "Because cache was available"}

        skip_zip = self.algorithm_is_state
        metadata = self._do_package_parameters(is_encode=True, skip_zip=skip_zip)
        if encoding_parameter_cache_path and metadata.get("package"):
            # Cache was not available, but there is a cache dir specified so we have to store our results there
            # Only cache if a ZIP was actually created
            logger.info(f"Caching encoding parameters for {self.algorithm_name} at {encoding_parameter_cache_path}")
            store_file(self.encode_parameter_file_path(), encoding_parameter_cache_path)
        return metadata

    def _do_package_parameters(self, is_encode: bool, skip_zip: bool = False) -> dict[str, Any]:
        parameter_package_location = (
            self.encode_parameter_file_path() if is_encode else self.predict_parameter_file_path()
        )
        trydelete(parameter_package_location)

        def to_arc_name(algorithm_name, target_file_name):
            return os.path.join(algorithm_name, os.path.basename(target_file_name))

        to_package: list[(str, str)] = []
        pinned_parameter_archives: list[tuple[str, str, str]] = []

        parameter_metadata_filename = "algorithm-parameters.json"

        def with_parameter_path(pipeline: "AlgorithmPipeline") -> str | None:
            return _recursive_get(pipeline.algorithm_definition, ["hotvect_execution_parameters", "with_parameter"])

        def process_pipeline(pipeline, algorithm_name, to_package_acc):
            if pipeline is not self:
                parameter_archive_source = with_parameter_path(pipeline)
                if parameter_archive_source:
                    available_parameter_archive = pipeline.available_predict_parameter_cache_path()
                    if not available_parameter_archive:
                        raise ValueError(
                            f'"with_parameter" option was used for dependency {algorithm_name}, '
                            f"but the specified parameter {parameter_archive_source} was not available"
                        )
                    pinned_parameter_archives.append(
                        (available_parameter_archive, parameter_archive_source, algorithm_name)
                    )
                    logger.info(
                        'Using pinned "with_parameter" archive for dependency %s; skipping dependency parameter '
                        "repackaging",
                        algorithm_name,
                    )
                    return

            parameter_metadata_path = os.path.join(pipeline.output_path(), parameter_metadata_filename)
            to_package_acc.append(
                (
                    parameter_metadata_path,
                    to_arc_name(algorithm_name, parameter_metadata_filename),
                )
            )

            if pipeline.algorithm_is_state:
                # If the algorithm is a state, it should have a state output
                state_output_base = pipeline.state_output_path()
                base_path = Path(state_output_base)
                state_output_configured = pipeline.algorithm_definition.get("state_output_filename") is not None

                def state_arc_name(state_file: str) -> str:
                    return os.path.join(algorithm_name, os.path.relpath(state_file, pipeline.output_path()))

                # Check if state_output_base is a directory
                if os.path.isdir(state_output_base):
                    # Directory mode: recursively package all files in the directory tree
                    logger.info(f"Packaging state directory for {algorithm_name}: {state_output_base}")
                    files_to_package = []
                    for root, dirs, files in os.walk(state_output_base):
                        for file in files:
                            if file != parameter_metadata_filename:
                                full_path = os.path.join(root, file)
                                # Preserve directory structure relative to the algorithm output root.
                                rel_path = os.path.relpath(full_path, pipeline.output_path())
                                files_to_package.append((full_path, rel_path))

                    # Sort by relative path for deterministic ordering
                    files_to_package.sort(key=lambda x: x[1])

                    if not files_to_package:
                        raise FileNotFoundError(
                            f"No files found in state directory for {algorithm_name}: {state_output_base}"
                        )

                    for state_file, rel_path in files_to_package:
                        to_package_acc.append(
                            (
                                state_file,
                                os.path.join(algorithm_name, rel_path),  # Preserve directory structure in archive
                            )
                        )
                elif os.path.isfile(state_output_base):
                    # File mode: the configured primary state file is required. Companion sidecars may share the same
                    # basename prefix (for example example_feature_state-zmclip.jsonl).
                    sidecar_pattern = str(base_path.parent / f"{base_path.stem}-*{base_path.suffix}")
                    sidecar_files = sorted(glob.glob(sidecar_pattern))
                    logger.info(
                        "Packaging primary state file and %s sidecars for %s",
                        len(sidecar_files),
                        algorithm_name,
                    )
                    to_package_acc.append(
                        (
                            state_output_base,
                            state_arc_name(state_output_base),
                        )
                    )
                    for sidecar_file in sidecar_files:
                        to_package_acc.append(
                            (
                                sidecar_file,
                                state_arc_name(sidecar_file),
                            )
                        )
                elif state_output_configured:
                    raise FileNotFoundError(
                        f"No state files found for {algorithm_name}. "
                        f"Expected configured state output path at {state_output_base} to be a file or directory."
                    )
                else:
                    raise FileNotFoundError(
                        f"No files found in state directory for {algorithm_name}: {state_output_base}"
                    )

            if "training_command" in pipeline.algorithm_definition:
                parameter_file = pipeline.parameter_file_path()
                should_package = (not is_encode) or (pipeline is not self)

                if should_package:
                    use_legacy_model_parameter_path = _uses_legacy_model_parameter_path(pipeline.algorithm_definition)
                    if os.path.isdir(parameter_file):
                        # Directory mode: recursively package all files
                        logger.info(f"Packaging parameter directory for {algorithm_name}: {parameter_file}")
                        files_to_package = []
                        for root, dirs, files in os.walk(parameter_file):
                            for file in files:
                                full_path = os.path.join(root, file)
                                rel_path = os.path.relpath(full_path, parameter_file)
                                files_to_package.append((full_path, rel_path))

                        files_to_package.sort(key=lambda x: x[1])

                        for param_file, rel_path in files_to_package:
                            if (
                                use_legacy_model_parameter_path
                                and os.path.basename(parameter_file) == "model_parameter"
                                and rel_path == "model.parameter"
                            ):
                                # hv9-era CatBoost factories load the model from the algorithm root.
                                arc_name = to_arc_name(algorithm_name, rel_path)
                            else:
                                arc_name = os.path.join(algorithm_name, os.path.basename(parameter_file), rel_path)
                            to_package_acc.append(
                                (
                                    param_file,
                                    arc_name,
                                )
                            )
                    else:
                        to_package_acc.append(
                            (
                                parameter_file,
                                to_arc_name(algorithm_name, os.path.basename(parameter_file)),
                            )
                        )

                    # Bundle encoding schema + algorithm definition for strict inference.
                    # (Only if present; older/non-TF pipelines may not emit these artifacts.)
                    schema_path = pipeline.encoded_schema_description_file_path()
                    if os.path.exists(schema_path):
                        to_package_acc.append((schema_path, to_arc_name(algorithm_name, os.path.basename(schema_path))))

                    algorithm_definition_path = os.path.join(pipeline.metadata_path(), "algorithm_definition.json")
                    if os.path.exists(algorithm_definition_path):
                        to_package_acc.append(
                            (
                                algorithm_definition_path,
                                to_arc_name(algorithm_name, os.path.basename(algorithm_definition_path)),
                            )
                        )

            for sub_algorithm_name, sub_pipeline in pipeline.dependency_pipelines.items():
                process_pipeline(sub_pipeline, sub_algorithm_name, to_package_acc)

        process_pipeline(self, self.algorithm_name, to_package)

        # Add the algo parameters
        algorithm_parameters = {
            "algorithm_name": self.algorithm_name,
            "algorithm_version": self.algorithm_version,
            "hyperparameter_version": self.hyper_parameter_version,
            "parameter_id": self.parameter_version,
            "ran_at": self.ran_at,
            "last_test_time": self.last_test_time.isoformat(),
            "algorithm_definition": self.algorithm_definition,
            "sources": to_package,
            "package": None if skip_zip else parameter_package_location,
            "type": "encode" if is_encode else "predict",
        }
        if pinned_parameter_archives:
            algorithm_parameters["pinned_parameter_archives"] = [
                {"algorithm_name": algorithm_name, "source": source}
                for _, source, algorithm_name in pinned_parameter_archives
            ]
        self._write_data(
            algorithm_parameters,
            os.path.join(self.output_path(), parameter_metadata_filename),
        )

        if not skip_zip:
            to_zip_archive(to_package, parameter_package_location)
            if pinned_parameter_archives:
                with zipfile.ZipFile(parameter_package_location, "a") as parameter_package_zip:
                    existing_entries = set(parameter_package_zip.namelist())
                    for (
                        available_parameter_archive,
                        parameter_archive_source,
                        algorithm_name,
                    ) in pinned_parameter_archives:
                        logger.info(
                            "Merging pinned parameter archive for %s into %s from %s",
                            algorithm_name,
                            parameter_package_location,
                            parameter_archive_source,
                        )
                        with zipfile.ZipFile(available_parameter_archive, "r") as source_zip:
                            source_infos = source_zip.infolist()
                            if not any(info.filename.startswith(f"{algorithm_name}/") for info in source_infos):
                                raise ValueError(
                                    f'Pinned "with_parameter" archive for {algorithm_name} '
                                    f"({parameter_archive_source}) does not contain entries under {algorithm_name}/"
                                )
                            for source_info in source_infos:
                                if source_info.is_dir() or source_info.filename in existing_entries:
                                    continue
                                copied_info = zipfile.ZipInfo(source_info.filename, source_info.date_time)
                                copied_info.compress_type = source_info.compress_type
                                copied_info.external_attr = source_info.external_attr
                                copied_info.comment = source_info.comment
                                with (
                                    source_zip.open(source_info, "r") as source,
                                    parameter_package_zip.open(copied_info, "w") as target,
                                ):
                                    shutil.copyfileobj(source, target)
                                existing_entries.add(source_info.filename)
        else:
            logger.info(f"Skipping ZIP creation for {self.algorithm_name}")

        return algorithm_parameters

    def _resolve_encode_partition_cache_path(self, partition_date: date) -> str | None:
        if not self._encode_partition_cache_enabled():
            return None
        cache_root = self._cache_algorithm_root()
        if not cache_root:
            raise ValueError("Encode partition cache requires hotvect_execution_parameters.cache_base_dir")
        return os.path.join(cache_root, "partitions", "encode", f"dt={partition_date.isoformat()}")

    def _encode_partition_local_cache_dir(self, partition_date: date) -> str:
        return os.path.join(self.cache_path(), "partitions", "encode", f"dt={partition_date.isoformat()}")

    def _encode_partition_work_dir(self, partition_date: date) -> str:
        return os.path.join(self.cache_path(), "partition-work", "encode", f"dt={partition_date.isoformat()}")

    def _mounted_encode_partition_cache_path(self, partition_date: date) -> str | None:
        cache_root = self._cache_algorithm_root()
        if cache_root is None:
            return None
        cache_base_paths = self.algorithm_pipeline_context.partition_cache_base_paths or {}
        cache_base_path = cache_base_paths.get(cache_root)
        if cache_base_path is None:
            return None
        partition_path = Path(cache_base_path) / "encode" / f"dt={partition_date.isoformat()}"
        return str(partition_path)

    @staticmethod
    def _write_encode_partition_success_marker(success_marker_path: str, partition_date: date) -> None:
        marker = {
            "version": ENCODE_PARTITION_SUCCESS_MARKER_VERSION,
            "type": ENCODE_PARTITION_SUCCESS_MARKER_TYPE,
            "dt": partition_date.isoformat(),
            "encoded_path": "encoded",
            "schema_path": "encoded-schema-description",
            "created_at_utc": datetime.utcnow().replace(microsecond=0).isoformat() + "Z",
        }
        Path(success_marker_path).write_text(json.dumps(marker, sort_keys=True) + "\n", encoding="utf-8")

    def _encode_partition_started_marker_data(self, partition_date: date) -> dict[str, Any]:
        return {
            "version": ENCODE_PARTITION_SUCCESS_MARKER_VERSION,
            "type": ENCODE_PARTITION_STARTED_MARKER_TYPE,
            "dt": partition_date.isoformat(),
            "algorithm_name": self.algorithm_name,
            "algorithm_version": self.algorithm_version,
            "parameter_version": self.parameter_version,
            "started_at_utc": datetime.utcnow().replace(microsecond=0).isoformat() + "Z",
            "host": socket.gethostname(),
            "pid": os.getpid(),
            "user": getpass.getuser(),
            "sagemaker_training_job_name": self.algorithm_pipeline_context.sagemaker_training_job_name,
        }

    @staticmethod
    def _read_encode_partition_success_marker(success_marker_path: str, partition_date: date) -> bool:
        if not os.path.isfile(success_marker_path):
            return False
        try:
            marker = json.loads(Path(success_marker_path).read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError):
            return False
        return (
            marker.get("version") == ENCODE_PARTITION_SUCCESS_MARKER_VERSION
            and marker.get("type") == ENCODE_PARTITION_SUCCESS_MARKER_TYPE
            and marker.get("dt") == partition_date.isoformat()
            and marker.get("encoded_path") == "encoded"
            and marker.get("schema_path") == "encoded-schema-description"
        )

    @staticmethod
    def _local_path_has_content(path: str) -> bool:
        if os.path.isfile(path) or os.path.islink(path):
            return True
        if not os.path.isdir(path):
            return False
        with os.scandir(path) as entries:
            return next(entries, None) is not None

    @staticmethod
    def _is_s3_not_found(error: ClientError) -> bool:
        return error.response.get("Error", {}).get("Code") in {"404", "NoSuchKey", "NotFound"}

    @staticmethod
    def _is_s3_precondition_failed(error: ClientError) -> bool:
        return error.response.get("Error", {}).get("Code") == "PreconditionFailed"

    @staticmethod
    def _list_s3_files(s3_prefix_uri: str, s3_client: Any) -> list[str]:
        bucket, prefix = require_s3_uri(s3_prefix_uri)
        prefix = prefix if prefix.endswith("/") else prefix + "/"
        paginator = s3_client.get_paginator("list_objects_v2")
        files = []
        for page in paginator.paginate(Bucket=bucket, Prefix=prefix):
            for obj in page.get("Contents", []):
                key = obj["Key"]
                if not key.endswith("/"):
                    files.append(f"s3://{bucket}/{key}")
        return sorted(files)

    @staticmethod
    def _s3_prefix_has_files(s3_prefix_uri: str, s3_client: Any) -> bool:
        bucket, prefix = require_s3_uri(s3_prefix_uri)
        prefix = prefix if prefix.endswith("/") else prefix + "/"
        response = s3_client.list_objects_v2(Bucket=bucket, Prefix=prefix, MaxKeys=1)
        return bool(response.get("Contents"))

    def _encode_partition_cache_has_content(self, partition_cache_path: str, s3_client: Any | None = None) -> bool:
        if partition_cache_path.startswith("s3://"):
            return self._s3_prefix_has_files(partition_cache_path, s3_client or boto3.client("s3"))
        return self._local_path_has_content(partition_cache_path)

    def _claim_encode_partition_cache_write(self, partition_cache_path: str, partition_date: date) -> bool:
        started_cache_path = os.path.join(partition_cache_path, ENCODE_PARTITION_STARTED_MARKER)
        marker = json.dumps(self._encode_partition_started_marker_data(partition_date), sort_keys=True) + "\n"
        if partition_cache_path.startswith("s3://"):
            bucket, key = require_s3_uri(started_cache_path)
            try:
                boto3.client("s3").put_object(
                    Bucket=bucket,
                    Key=key,
                    Body=marker.encode("utf-8"),
                    IfNoneMatch="*",
                    ContentType="application/json",
                )
            except ClientError as error:
                if self._is_s3_precondition_failed(error):
                    return False
                raise
            return True

        os.makedirs(partition_cache_path, exist_ok=True)
        try:
            with open(started_cache_path, "x", encoding="utf-8") as marker_file:
                marker_file.write(marker)
        except FileExistsError:
            return False
        return True

    def _load_s3_encode_partition_cache(
        self,
        partition_date: date,
        partition_cache_path: str,
        local_cache_dir: str,
        source_paths: list[str],
    ) -> tuple[dict[str, Any] | None, bool]:
        s3_client = boto3.client("s3")
        success_cache_path = os.path.join(partition_cache_path, ENCODE_PARTITION_SUCCESS_MARKER)
        success_local_path = os.path.join(local_cache_dir, ENCODE_PARTITION_SUCCESS_MARKER)
        os.makedirs(os.path.dirname(success_local_path), exist_ok=True)
        try:
            download_s3_file(success_cache_path, Path(success_local_path), s3_client)
        except ClientError as error:
            if self._is_s3_not_found(error):
                has_content = self._s3_prefix_has_files(partition_cache_path, s3_client)
                if has_content:
                    logger.warning(
                        "Ignoring incomplete S3 encode partition cache for %s at %s; missing success marker. "
                        "This partition cache will not be overwritten.",
                        partition_date,
                        partition_cache_path,
                    )
                    return None, True
                logger.info("Encode partition cache miss for %s at %s", partition_date, partition_cache_path)
                return None, False
            raise

        if not self._read_encode_partition_success_marker(success_local_path, partition_date):
            logger.warning(
                "Ignoring incomplete S3 encode partition cache for %s at %s; invalid success marker. "
                "This partition cache will not be overwritten.",
                partition_date,
                partition_cache_path,
            )
            return None, True

        encoded_cache_path = os.path.join(partition_cache_path, "encoded")
        schema_cache_path = os.path.join(partition_cache_path, "encoded-schema-description")
        encoded_s3_files = self._list_s3_files(encoded_cache_path, s3_client)

        schema_local_path = os.path.join(local_cache_dir, "encoded-schema-description")
        os.makedirs(os.path.dirname(schema_local_path), exist_ok=True)
        download_s3_file(schema_cache_path, Path(schema_local_path), s3_client)

        return {
            "dt": partition_date.isoformat(),
            "cache": "hit",
            "encoded_s3_files": encoded_s3_files,
            "schema_path": schema_local_path,
            "source_paths": source_paths,
            "source": "s3-cache",
        }, False

    def _do_encode(
        self,
        is_test: bool,
        source_paths: list[str] | None = None,
        encoded_data_location: str | None = None,
        schema_description_location: str | None = None,
        stage: str | None = None,
    ) -> dict[str, Any]:
        if "training_command" not in self.algorithm_definition:
            logger.info(
                f"Skipping {'test' if is_test else 'train'} encoding for {self.algorithm_name} because no training command exists"
            )
            return {"skipped": "Skipped because there is no training command in the algorithm definition"}
        stage = stage or ("encode-test" if is_test else "encode")
        metadata_dir = self._stage_metadata_dir(stage)
        clean_dir(metadata_dir)
        encoded_data_location = encoded_data_location or (
            self.encoded_test_data_file_path() if is_test else self.encoded_data_file_path()
        )
        schema_description_location = schema_description_location or self.encoded_schema_description_file_path()
        trydelete(encoded_data_location)
        cmd = self._base_command("encode", metadata_dir)
        if is_test:
            # If it's test encoding, use ordered so that it's easier to figure out which encoded row
            # corresponds to which source data
            cmd.append("--ordered")
        source_paths = source_paths or (self.test_data_paths() if is_test else self.training_data_paths())
        cmd.extend(["--source", ",".join(source_paths)])
        cmd.extend(["--dest-schema-description", schema_description_location])
        cmd.extend(["--dest", encoded_data_location])
        cmd.extend(["--parameters", self.encode_parameter_file_path()])
        logger.info(
            f"Encoding {'test' if is_test else 'train'} data for {self.algorithm_name} to {encoded_data_location}"
        )
        self._stream_output_to_stage_log(stage=stage, cmd=cmd)
        logger.info(
            f"Encoded {'test' if is_test else 'train'} data for {self.algorithm_name} at {encoded_data_location}"
        )
        return read_json(self._stage_metadata_file(stage))

    def _load_or_create_encode_partition(
        self,
        partition_date: date,
        source_paths: list[str],
    ) -> dict[str, Any]:
        partition_cache_path = self._resolve_encode_partition_cache_path(partition_date)
        local_cache_dir = self._encode_partition_local_cache_dir(partition_date)
        encoded_cache_path = os.path.join(partition_cache_path, "encoded")
        schema_cache_path = os.path.join(partition_cache_path, "encoded-schema-description")
        success_cache_path = os.path.join(partition_cache_path, ENCODE_PARTITION_SUCCESS_MARKER)

        should_publish_partition_cache = True

        mounted_partition_path = self._mounted_encode_partition_cache_path(partition_date)
        if mounted_partition_path:
            mounted_encoded_path = os.path.join(mounted_partition_path, "encoded")
            mounted_schema_path = os.path.join(mounted_partition_path, "encoded-schema-description")
            mounted_success_path = os.path.join(mounted_partition_path, ENCODE_PARTITION_SUCCESS_MARKER)
            if self._read_encode_partition_success_marker(mounted_success_path, partition_date):
                return {
                    "dt": partition_date.isoformat(),
                    "cache": "hit",
                    "encoded_path": mounted_encoded_path,
                    "schema_path": mounted_schema_path,
                    "source_paths": source_paths,
                    "source": "mounted-sagemaker-cache",
                }
            logger.info(
                "Ignoring mounted encode partition cache for %s at %s; missing or invalid success marker",
                partition_date,
                mounted_partition_path,
            )

        if partition_cache_path.startswith("s3://"):
            s3_partition, s3_partition_write_blocked = self._load_s3_encode_partition_cache(
                partition_date=partition_date,
                partition_cache_path=partition_cache_path,
                local_cache_dir=local_cache_dir,
                source_paths=source_paths,
            )
            if s3_partition:
                return s3_partition
            if s3_partition_write_blocked:
                should_publish_partition_cache = False

        if not partition_cache_path.startswith("s3://"):
            if self._read_encode_partition_success_marker(success_cache_path, partition_date):
                return {
                    "dt": partition_date.isoformat(),
                    "cache": "hit",
                    "encoded_path": encoded_cache_path,
                    "schema_path": schema_cache_path,
                    "source_paths": source_paths,
                    "source": "cache",
                }
            if self._encode_partition_cache_has_content(partition_cache_path):
                logger.warning(
                    "Ignoring incomplete encode partition cache for %s at %s. "
                    "This partition cache will not be overwritten.",
                    partition_date,
                    partition_cache_path,
                )
                should_publish_partition_cache = False

        if should_publish_partition_cache:
            if self._encode_partition_cache_has_content(partition_cache_path):
                logger.warning(
                    "Skipping encode partition cache write for %s at %s because the partition cache path already exists",
                    partition_date,
                    partition_cache_path,
                )
                should_publish_partition_cache = False
            else:
                should_publish_partition_cache = self._claim_encode_partition_cache_write(
                    partition_cache_path,
                    partition_date,
                )
                if not should_publish_partition_cache:
                    logger.warning(
                        "Skipping encode partition cache write for %s at %s because another writer already started it",
                        partition_date,
                        partition_cache_path,
                    )

        work_dir = self._encode_partition_work_dir(partition_date)
        clean_dir(work_dir)
        encoded_path = os.path.join(work_dir, "encoded")
        schema_path = os.path.join(work_dir, "encoded-schema-description")
        metadata = self._do_encode(
            is_test=False,
            source_paths=source_paths,
            encoded_data_location=encoded_path,
            schema_description_location=schema_path,
            stage=f"encode-dt-{partition_date.isoformat()}",
        )
        if not os.path.isdir(encoded_path):
            raise FileNotFoundError(f"Encode partition did not produce encoded directory: {encoded_path}")
        if not os.path.isfile(schema_path):
            raise FileNotFoundError(f"Encode partition did not produce schema description: {schema_path}")

        success_marker_path = os.path.join(work_dir, ENCODE_PARTITION_SUCCESS_MARKER)
        if should_publish_partition_cache:
            self._write_encode_partition_success_marker(success_marker_path, partition_date)
            store_file(encoded_path, encoded_cache_path)
            store_file(schema_path, schema_cache_path)
            store_file(success_marker_path, success_cache_path)
        return {
            "dt": partition_date.isoformat(),
            "cache": "miss",
            "encoded_path": encoded_path,
            "schema_path": schema_path,
            "source_paths": source_paths,
            "metadata": metadata,
        }

    def _assemble_encode_partitions(self, partitions: list[dict[str, Any]]) -> None:
        encoded_output_path = self.encoded_data_file_path()
        schema_output_path = self.encoded_schema_description_file_path()
        trydelete(encoded_output_path)
        os.makedirs(encoded_output_path, exist_ok=True)

        schema_output_written = False
        global_part_index = 0
        s3_client = None
        for partition in partitions:
            schema_path = partition["schema_path"]
            if not schema_output_written:
                trydelete(schema_output_path)
                os.makedirs(os.path.dirname(schema_output_path), exist_ok=True)
                shutil.copy(schema_path, schema_output_path)
                schema_output_written = True
            elif Path(schema_path).read_bytes() != Path(schema_output_path).read_bytes():
                raise ValueError(
                    f"Encoded schema mismatch for dt={partition['dt']}. "
                    f"Expected schema matching {schema_output_path}, got {schema_path}"
                )

            if "encoded_s3_files" in partition:
                if s3_client is None:
                    s3_client = boto3.client("s3")
                encoded_files = partition["encoded_s3_files"]
                if not encoded_files:
                    raise FileNotFoundError(f"Encode partition has no encoded files: dt={partition['dt']}")

                for encoded_file in encoded_files:
                    _, key = require_s3_uri(encoded_file)
                    dest_name = f"part-{global_part_index:05d}{Path(key).suffix}"
                    download_s3_file(encoded_file, Path(encoded_output_path) / dest_name, s3_client)
                    global_part_index += 1
            else:
                encoded_path = Path(partition["encoded_path"])
                encoded_files = sorted(path for path in encoded_path.rglob("*") if path.is_file())
                if not encoded_files:
                    raise FileNotFoundError(f"Encode partition has no encoded files: {encoded_path}")

                for encoded_file in encoded_files:
                    dest_name = f"part-{global_part_index:05d}{encoded_file.suffix}"
                    copy_or_link(str(encoded_file), os.path.join(encoded_output_path, dest_name))
                    global_part_index += 1

    def _encode_with_partition_cache(self) -> dict[str, Any]:
        training_dates = sorted(self._training_dates())
        if not training_dates:
            raise ValueError("Encode partition cache requires at least one training date")

        train_data_prefix = self._get_train_data_prefix() or "train"
        root_dir = os.path.join(self.algorithm_pipeline_context.data_base_path, train_data_prefix)
        if not os.path.isdir(root_dir):
            raise ValueError(f"The specified directory for training data does not exist: {os.path.abspath(root_dir)}")

        partitions = []
        for partition_date in training_dates:
            source_paths = to_local_paths(root_dir, [partition_date], fail_if_unavailable=True)
            partitions.append(
                self._load_or_create_encode_partition(
                    partition_date=partition_date,
                    source_paths=source_paths,
                )
            )

        self._assemble_encode_partitions(partitions)
        return {
            "partition_cache": True,
            "partition_cache_hits": sum(1 for partition in partitions if partition["cache"] == "hit"),
            "partition_cache_misses": sum(1 for partition in partitions if partition["cache"] == "miss"),
            "partitions": [
                {
                    "dt": partition["dt"],
                    "cache": partition["cache"],
                    "source_paths": partition["source_paths"],
                }
                for partition in partitions
            ],
        }

    def should_train(self) -> bool:
        return "training_command" in self.algorithm_definition

    def encode(self) -> dict:
        encoded_filename = os.path.basename(self.encoded_data_file_path())
        encoded_schema_description_filename = os.path.basename(self.encoded_schema_description_file_path())
        encoded_cache_path = self._resolve_cache_path(["encode"], encoded_filename)
        encoded_schema_description_cache_path = self._resolve_cache_path(
            ["encode"], encoded_schema_description_filename
        )
        if not self._encode_run_cache_enabled() or self._cache_refresh_enabled():
            available_encoded_cache_path = None
            available_encoded_schema_description_cache_path = None
        else:
            available_encoded_cache_path = as_locally_available_content(encoded_cache_path, self.cache_path())
            available_encoded_schema_description_cache_path = as_locally_available_content(
                encoded_schema_description_cache_path, self.cache_path()
            )

        if available_encoded_cache_path and available_encoded_schema_description_cache_path:
            # We have cache for both encoded data and schema description
            logger.info(
                f"Skipping encoding for {self.algorithm_name} because cache was available at {available_encoded_cache_path}"
            )
            copy_or_link(available_encoded_cache_path, self.encoded_data_file_path())
            copy_or_link(available_encoded_schema_description_cache_path, self.encoded_schema_description_file_path())
            return {
                "sources": [available_encoded_cache_path, available_encoded_schema_description_cache_path],
                "skipped": "Because cache was available",
            }

        if self._should_encode_with_partition_cache():
            metadata = self._encode_with_partition_cache()
        else:
            metadata = self._do_encode(is_test=False)
        if encoded_cache_path and encoded_schema_description_cache_path and "skipped" not in metadata.keys():
            # Cache was not available, but there is a cache dir specified so we have to store our results there
            # Note that we only do this if we did encode, if we skipped encoding we don't have a result to store
            store_file(self.encoded_data_file_path(), encoded_cache_path)
            store_file(self.encoded_schema_description_file_path(), encoded_schema_description_cache_path)
        return metadata

    def encode_test(self) -> dict:
        return self._do_encode(is_test=True)

    def train(self) -> dict:
        if "training_command" not in self.algorithm_definition:
            logger.info(f"Skipping training for {self.algorithm_name} because no training command exists")
            return {"skipped": "Because no training command exists for this algorithm"}
        parameter_cache_path = self._resolve_cache_path(["train"], "predict-parameters.zip")
        available_parameter_cache_path = (
            None
            if self._cache_refresh_enabled()
            else as_locally_available_content(parameter_cache_path, self.cache_path())
        )
        if available_parameter_cache_path:
            # We have cache, no need to train
            logger.info(
                f"Skipping training for {self.algorithm_name} because predict parameter cache is available at {available_parameter_cache_path}"
            )
            return {"skipped": f"Because cache was available in {available_parameter_cache_path}"}
        logger.info(f"Training: {self.algorithm_name}")
        stage = "train"
        metadata_dir = self._stage_metadata_dir(stage)
        clean_dir(metadata_dir)
        parameter_location = self.parameter_file_path()
        trydelete(parameter_location)
        scratch_dir = os.path.join(self.output_path(), "train_scratch_dir")
        clean_dir(scratch_dir)
        if not os.path.isdir(self.encoded_data_file_path()):
            raise ValueError(
                f"Encoded directory does not exist! Expected directory here: {os.path.abspath(self.encoded_data_file_path())}"
            )
        train_command_template = Template(self.algorithm_definition["training_command"])
        python_executable = sys.executable
        train_command = train_command_template.render(
            algorithm_definition_path=os.path.join(self.metadata_path(), "algorithm_definition.json"),
            algorithm_jar_path=self.algorithm_jar_path(),
            encoded_data_file_path=self.encoded_data_file_path(),
            encoded_schema_description_file_path=self.encoded_schema_description_file_path(),
            parameter_output_path=parameter_location,
            scratch_dir=scratch_dir,
            python_executable=python_executable,
            encode_parameter_path=self.encode_parameter_file_path(),
        )
        # Note: Training uses shell=True to support complex command strings with pipes, redirects, etc.
        # We convert to list form for stream_output which requires list format
        self._stream_output_to_stage_log(stage=stage, cmd=["/bin/bash", "-c", train_command])
        logger.info(f"Training completed for {self.algorithm_name}")
        metadata = {"training_command": train_command}
        with open(self._stage_metadata_file(stage), "w") as f:
            json.dump(metadata, f)
        return metadata

    def package_predict_parameters(self) -> dict:
        original_parameter_cache_path = self.predict_parameter_cache_original_path()
        available_parameter_cache_path = self.available_predict_parameter_cache_path()
        if available_parameter_cache_path:
            # We have cache
            copy_or_link(available_parameter_cache_path, self.predict_parameter_file_path())
            self._hydrate_predict_parameter_archive(
                available_parameter_cache_path,
                include_dependencies=False,
            )
            logger.info(
                f"Using cached predict parameters for {self.algorithm_name} at {available_parameter_cache_path}"
            )
            return {"sources": [original_parameter_cache_path], "skipped": "Because cache was available"}
        logger.info(f"No predict parameters cache available for {self.algorithm_name}, creating package")
        skip_zip = self.algorithm_is_state and not self._package_state_as_predict_parameters_enabled()
        metadata = self._do_package_parameters(is_encode=False, skip_zip=skip_zip)
        if metadata.get("package"):
            logger.info(f"Predict parameters packaged for: {self.algorithm_name} at {metadata['package']}")
        else:
            logger.info(f"Predict parameter metadata created for {self.algorithm_name} (ZIP skipped)")
        if original_parameter_cache_path and metadata.get("package"):
            # Cache was not available, but there is a cache dir specified so we have to store our results there
            # Only cache if a ZIP was actually created
            logger.info(f"Caching predict parameters at {original_parameter_cache_path} for {self.algorithm_name}")
            store_file(self.predict_parameter_file_path(), original_parameter_cache_path)
        return metadata

    def _hydrate_predict_parameter_archive(
        self,
        available_parameter_cache_path: str,
        *,
        include_dependencies: bool,
    ) -> None:
        pipelines_by_name = {self.algorithm_name: self}
        if include_dependencies:
            self._collect_dependency_pipelines_by_name(pipelines_by_name)

        with zipfile.ZipFile(available_parameter_cache_path, "r") as zip_ref:
            for zip_info in zip_ref.infolist():
                member = zip_info.filename
                algorithm_name, separator, relative_name = member.partition("/")
                if not separator or algorithm_name not in pipelines_by_name or not relative_name:
                    continue

                target_pipeline = pipelines_by_name[algorithm_name]
                target_path = resolve_path_within_base(target_pipeline.output_path(), relative_name)
                if zip_info.is_dir():
                    os.makedirs(target_path, exist_ok=True)
                    continue

                os.makedirs(target_path.parent, exist_ok=True)
                with zip_ref.open(zip_info) as source, open(target_path, "wb") as target:
                    shutil.copyfileobj(source, target)

    def _collect_dependency_pipelines_by_name(self, pipelines_by_name: dict[str, "AlgorithmPipeline"]) -> None:
        for algorithm_name, dependency_pipeline in self.dependency_pipelines.items():
            pipelines_by_name[algorithm_name] = dependency_pipeline
            dependency_pipeline._collect_dependency_pipelines_by_name(pipelines_by_name)

    def should_publish_predict_parameters_zip(self) -> bool:
        """Whether this pipeline is expected to materialize a predict-parameters ZIP artifact."""
        if self.algorithm_is_state:
            return self._package_state_as_predict_parameters_enabled()
        return True

    def _package_state_as_predict_parameters_enabled(self) -> bool:
        value = _recursive_get(
            self.algorithm_definition,
            ["hotvect_execution_parameters", "package_state_as_predict_parameters"],
            True,
        )
        return bool(value)

    def _should_predict(self, evaluate: bool, target: str | None = None) -> bool:
        """Determine if prediction should run for the effective run target."""
        execution_target = self._resolve_run_target(evaluate=evaluate, target=target)
        if execution_target == "parameters":
            return False

        predict_enabled = _recursive_get(
            self.algorithm_definition, ["hotvect_execution_parameters", "predict", "enabled"], None
        )
        evaluate_enabled = _recursive_get(
            self.algorithm_definition, ["hotvect_execution_parameters", "evaluate", "enabled"], None
        )

        if execution_target == "predict":
            if predict_enabled is not None:
                return bool(predict_enabled)
            return True

        # Evaluate target still implies prediction when evaluation is enabled.
        if evaluate_enabled is True:
            return True
        if predict_enabled is not None:
            return predict_enabled
        return evaluate

    def predict(self, evaluate, target: str | None = None) -> dict:
        execution_target = self._resolve_run_target(evaluate=evaluate, target=target)
        if execution_target == "predict":
            source_paths = self.prediction_data_paths()
            score_location = self.prediction_result_staging_path()
            output_uri = self.prediction_output_uri()
        else:
            source_paths = self.test_data_paths()
            score_location = self.test_result_file_path()
            output_uri = None

        if not self._should_predict(evaluate, target=execution_target):
            logger.info(f"Prediction is disabled for {self.algorithm_name}")
            return {"skipped": "Because prediction is disabled"}

        logger.info(f"Generating predictions: {self.algorithm_name}")
        stage = "predict"
        metadata_dir = self._stage_metadata_dir(stage)
        clean_dir(metadata_dir)
        trydelete(score_location)
        cmd = self._base_command("predict", metadata_dir)
        cmd.extend(["--source", ",".join(source_paths)])
        cmd.extend(["--dest", score_location])
        cmd.extend(["--parameters", self.predict_parameter_file_path()])
        self._stream_output_to_stage_log(stage=stage, cmd=cmd)
        if output_uri:
            if not (
                not output_uri.startswith("s3://") and os.path.abspath(output_uri) == os.path.abspath(score_location)
            ):
                if output_uri.startswith("s3://"):
                    assert_s3_directory_uri_empty(output_uri)
                store_file(score_location, output_uri)
        logger.info(f"Generated predictions for {self.algorithm_name}")
        metadata = read_json(self._stage_metadata_file(stage))
        metadata["prediction_input_kind"] = "prediction_spec" if execution_target == "predict" else "test_data_spec"
        metadata["prediction_source_paths"] = source_paths
        metadata["prediction_local_output_path"] = score_location
        if output_uri:
            metadata["prediction_output_uri"] = output_uri
        with open(self._stage_metadata_file(stage), "w") as f:
            json.dump(metadata, f)
        return metadata

    def _should_evaluate(self, evaluate: bool) -> bool:
        """Determine if evaluation should run."""
        evaluate_enabled = _recursive_get(
            self.algorithm_definition, ["hotvect_execution_parameters", "evaluate", "enabled"], evaluate
        )
        return evaluate_enabled

    def evaluate(self, evaluate: bool) -> dict:
        # Check if prediction will run (evaluation requires prediction)
        if not self._should_predict(evaluate):
            logger.info(f"Evaluation won't run because prediction is disabled for {self.algorithm_name}")
            return {"skipped": "Because prediction is disabled"}

        if not self._should_evaluate(evaluate):
            logger.info(f"Evaluation is disabled for {self.algorithm_name}")
            return {"skipped": "Because evaluation is disabled"}
        stage = "evaluate"
        metadata_dir = self._stage_metadata_dir(stage)
        clean_dir(metadata_dir)
        logger.info(
            f"Evaluating {self.algorithm_name} with {self.evaluation_function.__name__} using {self.test_result_file_path()}"
        )
        kwargs = (
            _recursive_get(
                self.algorithm_definition, ["hotvect_execution_parameters", "evaluation_function", "arguments"]
            )
            or {}
        )
        if not isinstance(kwargs, dict):
            raise ValueError(
                "Algorithm definition evaluation_function.arguments must be a dict when provided. "
                f"Got: {type(kwargs)}"
            )
        meta_data = self.evaluation_function(self.test_result_file_path(), **kwargs)
        logger.info(
            f"Evaluated {self.algorithm_name} with {self.evaluation_function.__name__}, results at {self._stage_metadata_file(stage)}"
        )
        with open(self._stage_metadata_file(stage), "w") as f:
            json.dump(meta_data, f)
        return meta_data

    def performance_test(self, evaluate: bool) -> dict:
        if not _recursive_get(
            self.algorithm_definition, ["hotvect_execution_parameters", "performance-test", "enabled"], evaluate
        ):
            logger.info(f"Performance test is disabled for {self.algorithm_name}")
            return {"skipped": "Because performance test is disabled"}
        stage = "performance-test"
        metadata_dir = self._stage_metadata_dir(stage)
        clean_dir(metadata_dir)
        samples = _recursive_get(
            self.algorithm_definition, ["hotvect_execution_parameters", "performance-test", "samples"]
        )
        sample_pool_size = _recursive_get(
            self.algorithm_definition, ["hotvect_execution_parameters", "performance-test", "sample_pool_size"]
        )
        target_rps = _recursive_get(
            self.algorithm_definition, ["hotvect_execution_parameters", "performance-test", "target_rps"]
        )
        target_throughput_fraction = _recursive_get(
            self.algorithm_definition,
            ["hotvect_execution_parameters", "performance-test", "target_throughput_fraction"],
        )
        workload_mode = _recursive_get(
            self.algorithm_definition,
            ["hotvect_execution_parameters", "performance-test", "workload_mode"],
        )
        available_number_of_cores = psutil.cpu_count(logical=False)
        if available_number_of_cores is None:
            available_number_of_cores = psutil.cpu_count(logical=True) or 1
        if available_number_of_cores >= 4:
            appropriate_thread_num_for_performance_test = 2
        else:
            appropriate_thread_num_for_performance_test = 1
        logger.info(
            f"Performance testing {self.algorithm_name} with {appropriate_thread_num_for_performance_test} threads"
        )
        cmd = self._base_command(
            "performance-test", metadata_dir, max_threads=appropriate_thread_num_for_performance_test
        )
        cmd.extend(["--source", ",".join(self.test_data_paths())])
        cmd.extend(["--parameters", self.predict_parameter_file_path()])
        if samples is not None:
            cmd.extend(["--samples", str(int(samples))])
        if sample_pool_size is not None:
            cmd.extend(["--sample-pool-size", str(int(sample_pool_size))])
        if target_rps is not None:
            cmd.extend(["--target-rps", str(float(target_rps))])
        if target_throughput_fraction is not None:
            cmd.extend(["--target-throughput-fraction", str(float(target_throughput_fraction))])
        if workload_mode is not None:
            cmd.extend(["--workload-mode", str(workload_mode)])
        execution_command = list(cmd)
        self._stream_output_to_stage_log(stage=stage, cmd=cmd)
        logger.info(f"Performance tested {self.algorithm_name}")
        ret = read_json(self._stage_metadata_file(stage))
        if isinstance(ret, dict):
            ret.setdefault("max_threads", appropriate_thread_num_for_performance_test)
            ret.setdefault("execution_command", execution_command)
        if samples is not None and isinstance(ret, dict):
            ret.setdefault("requested_samples", int(samples))
        if sample_pool_size is not None and isinstance(ret, dict):
            ret.setdefault("requested_sample_pool_size", int(sample_pool_size))
        if target_rps is not None and isinstance(ret, dict):
            ret.setdefault("requested_target_rps", float(target_rps))
        if target_throughput_fraction is not None and isinstance(ret, dict):
            ret.setdefault("requested_target_throughput_fraction", float(target_throughput_fraction))
        if workload_mode is not None and isinstance(ret, dict):
            ret.setdefault("requested_workload_mode", str(workload_mode))
        return ret

    def clean_output(self) -> None:
        # TODO decide what to do here
        trydelete(self.encoded_data_file_path())
        # trydelete(self.parameter_file_path())
        trydelete(self.test_result_file_path())
        trydelete(self.prediction_result_staging_path())
        # trydelete(self.output_path())

    def audit(self) -> dict[str, Any]:
        if not self._should_predict(False):
            return {"skipped": "Because prediction is disabled"}
        if "training_command" not in self.algorithm_definition:
            return {"skipped": "Because you cannot audit algorithms that do not encode (train)"}
        stage = "audit"
        metadata_dir = self._stage_metadata_dir(stage)
        clean_dir(metadata_dir)
        audit_data_location = self.audit_data_file_path()
        trydelete(audit_data_location)
        cmd = self._base_command("audit", metadata_dir)
        parameters = []
        if os.path.exists(self.encode_parameter_file_path()):
            # We have encode parameters
            parameters.append(self.encode_parameter_file_path())
        parameters.append(self.predict_parameter_file_path())
        cmd.extend(["--parameters", ",".join(parameters)])
        cmd.extend(["--source", ",".join(self.test_data_paths())])
        cmd.extend(["--dest", audit_data_location])
        self._stream_output_to_stage_log(stage=stage, cmd=cmd)
        return read_json(self._stage_metadata_file(stage))

    def predict_parameter_cache_original_path(self) -> str:
        parameter_cache_path = _recursive_get(
            self.algorithm_definition, ["hotvect_execution_parameters"] + ["with_parameter"]
        )
        if not parameter_cache_path:
            parameter_cache_path = self._resolve_cache_path(["train"], "predict-parameters.zip")
        return parameter_cache_path

    def available_predict_parameter_cache_path(self) -> str | None:
        if self.available_parameter_cache_path:
            return self.available_parameter_cache_path
        parameter_cache_path = _recursive_get(
            self.algorithm_definition, ["hotvect_execution_parameters"] + ["with_parameter"]
        )
        if parameter_cache_path:
            # If "with_parameter" is used, the cache path must be already available
            available_parameter_cache_path = as_locally_available_content(parameter_cache_path, self.cache_path())
            if not available_parameter_cache_path:
                raise ValueError(
                    f'"with_parameter" option was used, but the specified parameter {parameter_cache_path} was not available'
                )
        else:
            parameter_cache_path = self._resolve_cache_path(["train"], "predict-parameters.zip")
            if self._cache_refresh_enabled():
                available_parameter_cache_path = None
            else:
                available_parameter_cache_path = as_locally_available_content(parameter_cache_path, self.cache_path())
        self.available_parameter_cache_path = available_parameter_cache_path
        return available_parameter_cache_path


def _get_jvm_args(algorithm_definition: dict[str, Any], task_name: str, jvm_args: list[str] | None) -> list[str] | None:
    execution_parameters = algorithm_definition.get("hotvect_execution_parameters", {})

    # See if there is a task specific override
    task_specific_jvm_args = None
    if isinstance(execution_parameters, dict):
        task_parameters = execution_parameters.get(task_name)
        if isinstance(task_parameters, dict) and task_parameters.get("jvm_args", None):
            task_specific_jvm_args = task_parameters["jvm_args"]

    # Try to get the value without the task name
    global_jvm_args = execution_parameters.get("jvm_args", None) if isinstance(execution_parameters, dict) else None
    # Store the JVM arguments in a list
    jvm_args_list = [task_specific_jvm_args, global_jvm_args, jvm_args]
    # Iterate over the list and return the first non-falsy value
    for arg_set in jvm_args_list:
        if arg_set:
            for arg in arg_set:
                if "-cp" in arg or "-classpath" in arg:
                    raise ValueError("You cannot modify the classpath through override")
            return arg_set


def _recursive_get(dx: dict[Any, Any], keys: list[str], default: Any = None):
    def get_with_default(d, key):
        return d.get(key, default) if isinstance(d, dict) else default

    try:
        return reduce(get_with_default, keys, dx)
    except TypeError:
        return default
