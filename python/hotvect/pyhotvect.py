import glob
import json
import logging
import os
import re
import shutil
import sys
import time
import zipfile
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, tzinfo
from functools import reduce
from pathlib import Path
from typing import Any, Callable, Dict, List, NamedTuple, Optional, Set, Tuple, Union

import psutil
from jinja2 import Template

import hotvect.hotvectjar
from hotvect.utils import (
    as_locally_available_content,
    clean_dir,
    copy_or_link,
    read_algorithm_definition_from_jar,
    read_json,
    recursive_dict_update,
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
    from hotvect import mlutils

    return mlutils.standard_evaluation(*args, **kwargs)


def _real_numbers_reward_evaluation(*args, **kwargs):
    from hotvect import mlutils

    return mlutils.real_numbers_reward_evaluation(*args, **kwargs)


EVALUATION_FUNCTIONS = {
    "standard_evaluation": _standard_evaluation,
    "real_numbers_reward_evaluation": _real_numbers_reward_evaluation,
}


# Courtesy of https://stackoverflow.com/a/23705687/234901
# Under CC BY-SA 3.0
class SimpleUTC(tzinfo):
    def tzname(self, **kwargs):
        return "UTC"

    def utcoffset(self, dt):
        return timedelta(0)


class AlgorithmPipelineContext(NamedTuple):
    # Algorithm jar
    algorithm_jar_path: Path

    # Output locations
    data_base_path: Path
    metadata_base_path: Path
    output_base_path: Path

    # Parameter sources
    # Optional for backward compatibility with older `hv` entrypoints; when omitted, state sources are read from `data_base_path`.
    state_source_base_path: Optional[Path] = None

    # Execution parameters
    jvm_options: Optional[List[str]] = None
    max_threads: Optional[int] = None
    queue_length: Optional[int] = None
    batch_size: Optional[int] = None
    additional_jar_files: Optional[List[Path]] = None


@dataclass(frozen=True)
class DataDependency:
    algorithm_name: str
    algorithm_version: str
    data_prefix: str
    data_dates: Set[date]
    data_type: str  # e.g., 'train', 'test', or 'state'
    additional_properties: Dict[str, Any] = field(default_factory=dict)


class AlgorithmPipeline:
    def __init__(
        self,
        algorithm_pipeline_context: AlgorithmPipelineContext,
        algorithm_definition: Union[str, Tuple[str, Dict[str, Any]]],
        last_test_time: date,
        evaluation_func: Optional[Callable[[str], Dict[str, Any]]],
        hyperparameter_version: Optional[str] = None,
        parameter_version: Optional[str] = None,
        clean_output_after_run: bool = False,
        encode_test_data: bool = False,
        execute_audit: bool = False,
        execute_performance_test: bool = True,
    ):
        self.clean_output_after_run = clean_output_after_run
        self.encode_test_data = encode_test_data
        self.execute_audit = execute_audit
        self.execute_performance_test = execute_performance_test

        # Context
        self.algorithm_pipeline_context = algorithm_pipeline_context

        # Algorithm definition
        if isinstance(algorithm_definition, str):
            self.algorithm_name = verify_algorithm_name(algorithm_definition)
            self.algorithm_definition: Dict[str, Any] = read_algorithm_definition_from_jar(
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
            self.hyper_parameter_version = (
                "" if not hyperparameter_version else verify_algorithm_hyperparameter_version(hyperparameter_version)
            )
        else:
            # Algorithm has algorithm definition override
            algorithm_name, algorithm_definition_override = algorithm_definition
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
            recursive_dict_update(algo_def, algorithm_definition_override)
            self.algorithm_definition = algo_def
            if hyperparameter_version and "hyperparameter_version" in algo_def.keys():
                self.hyper_parameter_version = f"{algo_def['hyperparameter_version']}-{hyperparameter_version}"
            else:
                self.hyper_parameter_version = algo_def.get("hyperparameter_version", hyperparameter_version)

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
        self.ran_at: str = nowtime.isoformat()
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
        cache_inherited_parameters: Dict[str, Any] = {}
        cache_base_dir = _recursive_get(self.algorithm_definition, ["hotvect_execution_parameters", "cache_base_dir"])
        if cache_base_dir:
            cache_inherited_parameters["cache_base_dir"] = cache_base_dir
        cache_scope = _recursive_get(self.algorithm_definition, ["hotvect_execution_parameters", "cache_scope"])
        if cache_scope is not None:
            cache_inherited_parameters["cache_scope"] = cache_scope
        cache_refresh = _recursive_get(self.algorithm_definition, ["hotvect_execution_parameters", "cache_refresh"])
        if cache_refresh is not None:
            cache_inherited_parameters["cache_refresh"] = cache_refresh

        self.dependency_pipelines: Dict[str, AlgorithmPipeline] = {}
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
                        algo_def_override = recursive_dict_update(algo_def_override, inherited_def)
                    pipeline = AlgorithmPipeline(
                        algorithm_pipeline_context=self.algorithm_pipeline_context,
                        algorithm_definition=(algorithm_name, algo_def_override),
                        last_test_time=self.last_test_time,
                        hyperparameter_version=self.hyper_parameter_version,
                        parameter_version=self.parameter_version,
                        evaluation_func=self.evaluation_function,
                        encode_test_data=encode_test_data,
                        execute_audit=execute_audit,
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
                        algo_def_override = recursive_dict_update(algo_def_override, inherited_def)
                    pipeline = AlgorithmPipeline(
                        algorithm_pipeline_context=self.algorithm_pipeline_context,
                        algorithm_definition=(algorithm_name, algo_def_override),
                        last_test_time=self.last_test_time,
                        hyperparameter_version=self.hyper_parameter_version,
                        parameter_version=self.parameter_version,
                        evaluation_func=self.evaluation_function,
                        encode_test_data=encode_test_data,
                        execute_audit=execute_audit,
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
        return bool(value)

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
    def _semver_from_algorithm_version(version: str) -> Optional[Tuple[int, int, int]]:
        matches = list(re.finditer(r"(\d+)\.(\d+)\.(\d+)", version))
        if not matches:
            return None
        m = matches[-1]
        return int(m.group(1)), int(m.group(2)), int(m.group(3))

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

    def training_data_paths(self):
        return to_local_paths(
            os.path.join(self.algorithm_pipeline_context.data_base_path, self._get_train_data_prefix() or "train"),
            self._training_dates(),
        )

    def _test_dates(self):
        return [self.last_test_time]

    def test_data_paths(self) -> List[str]:
        testing_dates = self._test_dates()
        # For convenience, when test data is not available, it returns an empty list rather than failing
        root_dir = os.path.join(self.algorithm_pipeline_context.data_base_path, self._get_test_data_prefix() or "test")
        if not os.path.isdir(root_dir):
            # It is legal to specify empty test data
            # However, to avoid this being a mistake, code requires that at least the root dir exists
            raise ValueError(
                (
                    f"The specified directory for test data does not exist: {os.path.abspath(root_dir)}. ",
                    "Existing test data path is required, even if test is meant to be skipped",
                    "(to avoid it being skipped by mistake)",
                )
            )
        return to_local_paths(
            root_dir,
            testing_dates,
            fail_if_unavailable=False,
        )

    def data_dependencies(self) -> List[DataDependency]:
        ret = []

        def extract_additional_properties(data_spec):
            if not data_spec:
                return {}
            main_keys = ["data_prefix", "number_of_days", "lag_days"]
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

        # Add test data dependency if present
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
            ret.extend(dependency.data_dependencies())
        return ret

    def state_source_path(self, source_config: Dict[str, Any]):
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
        else:
            # Return file path when filename is specified
            return os.path.join(self.output_path(), state_output_filename)

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
        return os.path.join(self.output_path(), "prediction.jsonl")

    def audit_data_file_path(self) -> str:
        return os.path.join(self.output_path(), "audit.jsonl")

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

    def _write_data(self, data: Dict, dest_file_name: str) -> str:
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

    def _stream_output_to_stage_log(self, *, stage: str, cmd: List[str]) -> None:
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
    ) -> Dict[str, Any]:
        start_time = time.time()
        result: Dict[str, Any] = {
            "algorithm_id": self.hyperparameter_slug(),
            "parameter_version": self.parameter_version,
            "test_data_time": self.last_test_time.isoformat(),
            "ran_at": self.ran_at,
            "algorithm_definition": self.algorithm_definition,
            "timing_info_sec": {},
        }

        self.clean_output_after_run = clean

        self.clean()
        clean_dir(self.cache_path())
        logger.info(f"Cleaned output files for: {self.algorithm_name}")

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
                    dependency_result = pipeline.run_all(
                        clean=self.clean_output_after_run,
                        # When it's run as a dependency, we do not evaluate by default
                        evaluate=False,
                    )
                    result["dependencies"][algorithm_name] = dependency_result
                logger.info(f"Prepared all dependencies: {self.dependency_pipelines.keys()} for {self.algorithm_name}")
            result["timing_info_sec"]["prepare_dependencies"] = time.time() - deps_time

            if not self.available_predict_parameter_cache_path():
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
                    f"Predict parameters available for {self.algorithm_name} ({self.available_predict_parameter_cache_path()}), skipping encode and train"
                )

            self._step_predict_parameter(result)

            tasks_to_skip = ["predict", "evaluate", "performance_test", "encode_test"]
            if self.algorithm_is_state:
                logger.info(f"Algorithm {self.algorithm_name} is a state. There are no evaluation steps")
                reason_for_skipping = {"skipped": "This algorithm is a state"}
                for task_id in tasks_to_skip:
                    result[task_id] = reason_for_skipping
            else:
                if len(self.test_data_paths()) > 0:
                    logger.info(f"We have valid test data for {self.algorithm_name}")
                    if (
                        _recursive_get(self.algorithm_definition, ["hotvect_execution_parameters", "with_parameter"])
                        and not evaluate
                    ):
                        reason_for_skipping = {"skipped": "Because with_parameter was specified"}
                        tasks_to_skip = ["predict", "evaluate", "performance_test", "encode_test"]
                        logger.info(
                            f'"with_parameter" is specified for {self.algorithm_name}, skipping all steps {tasks_to_skip}'
                        )
                        for task_id in tasks_to_skip:
                            result[task_id] = reason_for_skipping
                    else:
                        self._step_predict(result, evaluate)
                        self._step_evaluate(result, evaluate)
                        if self.execute_performance_test:
                            self._step_performance_test(result, evaluate)
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
                    reason_for_skipping = {"skipped": "Because no test data was available"}
                    logger.info(f"No test data available, skipping tasks {tasks_to_skip} for: {self.algorithm_name}")
                    for task_id in tasks_to_skip:
                        result[task_id] = reason_for_skipping

            if clean:
                logger.info(f"Cleaning output dir for {self.algorithm_name}")
                self.clean_output()
                logger.info(f"Cleaned output dir for {self.algorithm_name}")

            self._write_data(result, os.path.join(self.metadata_path(), "result.json"))
            logger.info(f"Completed {self.algorithm_name}: {self.__dict__}")
            result["timing_info_sec"]["total_time"] = time.time() - start_time
            return result

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

    def _step_performance_test(self, result: Dict[str, Any], evaluate: bool):
        perftest_times = time.time()
        result["performance_test"] = self.performance_test(evaluate)
        result["timing_info_sec"]["performance_test"] = time.time() - perftest_times

    def _step_evaluate(self, result: Dict[str, Any], evaluate: bool):
        evaluate_times = time.time()
        result["evaluate"] = self.evaluate(evaluate)
        result["timing_info_sec"]["evaluate"] = time.time() - evaluate_times

    def _step_predict(self, result: Dict[str, Any], evaluate: bool):
        predict_times = time.time()
        result["predict"] = self.predict(evaluate)
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
    def _task_name_to_offline_util_subcommand(task_name: str) -> Optional[str]:
        # hotvect-offline-util uses picocli subcommands (encode, predict, audit, generate-state, performance-test, ...).
        # Keep task_name as-is for JVM arg lookup (it is used to read hotvect_execution_parameters.<task>.jvm_args),
        # and translate only for CLI invocation.
        return {
            "encode": "encode",
            "predict": "predict",
            "audit": "audit",
            "generate_state": "generate-state",
            "performance-test": "performance-test",
            "generate-state": "generate-state",
        }.get(task_name)

    def _base_command(self, task_name: str, metadata_location: str, max_threads: int = -1) -> List[str]:
        ret = [
            "java",
            "-cp",
            f"{hotvect.hotvectjar.HOTVECT_JAR_PATH}",
        ]
        resolved_jvm_args = (
            _get_jvm_args(self.algorithm_definition, task_name, self.algorithm_pipeline_context.jvm_options) or []
        )
        ret.extend(resolved_jvm_args)
        if "-XX:+ExitOnOutOfMemoryError" not in resolved_jvm_args:
            ret.append("-XX:+ExitOnOutOfMemoryError")
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
            if self.algorithm_pipeline_context.batch_size:
                ret.extend(["--batch-size", str(self.algorithm_pipeline_context.batch_size)])
        return ret

    def _resolve_cache_path(self, task_paths: List[str], cache_file_name=None) -> Optional[str]:
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
        # Retrieve the cache override and cache base directory from the algorithm definition
        cache_override = _recursive_get(
            self.algorithm_definition, ["hotvect_execution_parameters", *task_paths, "cache"]
        )
        cache_base_dir = _recursive_get(self.algorithm_definition, ["hotvect_execution_parameters", "cache_base_dir"])

        # Determine caching behavior
        if cache_override is False:
            # Caching is explicitly disabled for this task
            return None
        elif cache_override is True or (cache_override is None and cache_base_dir):
            # Use the default cache path
            if not cache_base_dir:
                raise ValueError(
                    "Caching is enabled but 'cache_base_dir' is not specified under 'hotvect_execution_parameters.cache_base_dir'."
                )
            template = os.path.join(cache_base_dir, self._cache_algorithm_key(), self.parameter_version, *task_paths)
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

    def generate_states(self) -> Dict:
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
        cmd = self._base_command("generate_state", metadata_dir)

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

    def package_encode_parameters(self) -> Dict:
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

    def _do_package_parameters(self, is_encode: bool, skip_zip: bool = False) -> Dict[str, Any]:
        parameter_package_location = (
            self.encode_parameter_file_path() if is_encode else self.predict_parameter_file_path()
        )
        trydelete(parameter_package_location)

        def to_arc_name(algorithm_name, target_file_name):
            return os.path.join(algorithm_name, os.path.basename(target_file_name))

        to_package: List[(str, str)] = []

        parameter_metadata_filename = "algorithm-parameters.json"

        def process_pipeline(pipeline, algorithm_name, to_package_acc):
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

                # Check if state_output_base is a directory
                if os.path.isdir(state_output_base):
                    # Directory mode: recursively package all files in the directory tree
                    logger.info(f"Packaging state directory for {algorithm_name}: {state_output_base}")
                    files_to_package = []
                    for root, dirs, files in os.walk(state_output_base):
                        for file in files:
                            if file != parameter_metadata_filename:
                                full_path = os.path.join(root, file)
                                # Preserve directory structure relative to state_output_base
                                rel_path = os.path.relpath(full_path, state_output_base)
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
                else:
                    # File mode: Handle both single file and sharded state files (e.g., state-0.ext, state-1.ext, ...)
                    # Look for sharded files with pattern: basename-*.ext
                    shard_pattern = str(base_path.parent / f"{base_path.stem}-*{base_path.suffix}")
                    shard_files = sorted(glob.glob(shard_pattern))

                    if shard_files:
                        # Multiple shards found - package all of them
                        logger.info(f"Packaging {len(shard_files)} sharded state files for {algorithm_name}")
                        for shard_file in shard_files:
                            to_package_acc.append(
                                (
                                    shard_file,
                                    to_arc_name(algorithm_name, os.path.basename(shard_file)),
                                )
                            )
                    elif os.path.exists(state_output_base):
                        # Single file (backward compatibility)
                        logger.info(f"Packaging single state file for {algorithm_name}")
                        to_package_acc.append(
                            (
                                state_output_base,
                                to_arc_name(algorithm_name, os.path.basename(state_output_base)),
                            )
                        )
                    else:
                        raise FileNotFoundError(
                            f"No state files found for {algorithm_name}. "
                            f"Expected either single file at {state_output_base} or sharded files matching {shard_pattern}"
                        )

            if "training_command" in pipeline.algorithm_definition:
                parameter_file = pipeline.parameter_file_path()
                should_package = (not is_encode) or (pipeline is not self)

                if should_package:
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
                            to_package_acc.append(
                                (
                                    param_file,
                                    os.path.join(algorithm_name, os.path.basename(parameter_file), rel_path),
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
            "package": parameter_package_location,
            "type": "encode" if is_encode else "predict",
        }
        self._write_data(
            algorithm_parameters,
            os.path.join(self.output_path(), parameter_metadata_filename),
        )

        if not skip_zip:
            to_zip_archive(to_package, parameter_package_location)
        else:
            logger.info(f"Skipping ZIP creation for {self.algorithm_name}")
            algorithm_parameters["package"] = None  # No ZIP created

        return algorithm_parameters

    def _do_encode(self, is_test: bool) -> Dict[str, Any]:
        if "training_command" not in self.algorithm_definition:
            logger.info(
                f"Skipping {'test' if is_test else 'train'} encoding for {self.algorithm_name} because no training command exists"
            )
            return {"skipped": "Skipped because there is no training command in the algorithm definition"}
        stage = "encode-test" if is_test else "encode"
        metadata_dir = self._stage_metadata_dir(stage)
        clean_dir(metadata_dir)
        encoded_data_location = self.encoded_test_data_file_path() if is_test else self.encoded_data_file_path()
        trydelete(encoded_data_location)
        cmd = self._base_command("encode", metadata_dir)
        if is_test:
            # If it's test encoding, use ordered so that it's easier to figure out which encoded row
            # corresponds to which source data
            cmd.append("--ordered")
        cmd.extend(["--source", ",".join((self.test_data_paths() if is_test else self.training_data_paths()))])
        cmd.extend(["--dest-schema-description", self.encoded_schema_description_file_path()])
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

    def should_train(self) -> bool:
        return "training_command" in self.algorithm_definition

    def encode(self) -> Dict:
        encoded_filename = os.path.basename(self.encoded_data_file_path())
        encoded_schema_description_filename = os.path.basename(self.encoded_schema_description_file_path())
        encoded_cache_path = self._resolve_cache_path(["encode"], encoded_filename)
        encoded_schema_description_cache_path = self._resolve_cache_path(
            ["encode"], encoded_schema_description_filename
        )
        if self._cache_refresh_enabled():
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

        metadata = self._do_encode(is_test=False)
        if encoded_cache_path and encoded_schema_description_cache_path and "skipped" not in metadata.keys():
            # Cache was not available, but there is a cache dir specified so we have to store our results there
            # Note that we only do this if we did encode, if we skipped encoding we don't have a result to store
            store_file(self.encoded_data_file_path(), encoded_cache_path)
            store_file(self.encoded_schema_description_file_path(), encoded_schema_description_cache_path)
        return metadata

    def encode_test(self) -> Dict:
        return self._do_encode(is_test=True)

    def train(self) -> Dict:
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
            encoded_data_file_path=self.encoded_data_file_path(),  # Pass directory, trainer resolves shard files
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

    def package_predict_parameters(self) -> Dict:
        original_parameter_cache_path = self.predict_parameter_cache_original_path()
        available_parameter_cache_path = self.available_predict_parameter_cache_path()
        if available_parameter_cache_path:
            # We have cache
            copy_or_link(available_parameter_cache_path, self.predict_parameter_file_path())
            # Extract the contents of the zip file to self.output_path()
            with zipfile.ZipFile(available_parameter_cache_path, "r") as zip_ref:
                for member in zip_ref.namelist():
                    # Only extract the contents of the directory named self.algorithm_name
                    if member.startswith(self.algorithm_name + "/"):
                        # Remove the directory name from the member name
                        new_member_name = member[len(self.algorithm_name) + 1 :]
                        if not new_member_name:
                            # This is the directory entry (e.g. "<algorithm_name>/"); nothing to extract.
                            continue

                        # Extract the member to the output path with the new member name.
                        target_path = resolve_path_within_base(self.output_path(), new_member_name)

                        zip_info = zip_ref.getinfo(member)
                        if zip_info.is_dir():
                            os.makedirs(target_path, exist_ok=True)
                            continue

                        target_dir = target_path.parent
                        if target_dir:
                            os.makedirs(target_dir, exist_ok=True)
                        with zip_ref.open(member) as source, open(target_path, "wb") as target:
                            shutil.copyfileobj(source, target)
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

    def _package_state_as_predict_parameters_enabled(self) -> bool:
        value = _recursive_get(
            self.algorithm_definition,
            ["hotvect_execution_parameters", "package_state_as_predict_parameters"],
            False,
        )
        return bool(value)

    def _should_predict(self, evaluate: bool) -> bool:
        """Determine if prediction should run.
        Prediction runs if:
        1. predict.enabled is explicitly True, OR
        2. evaluate.enabled is True (evaluation requires prediction), OR
        3. Neither is specified and evaluate=True (default behavior)
        """
        predict_enabled = _recursive_get(
            self.algorithm_definition, ["hotvect_execution_parameters", "predict", "enabled"], None
        )
        evaluate_enabled = _recursive_get(
            self.algorithm_definition, ["hotvect_execution_parameters", "evaluate", "enabled"], None
        )

        # If evaluate is enabled, we need prediction
        if evaluate_enabled is True:
            return True

        # If predict is explicitly configured, use that
        if predict_enabled is not None:
            return predict_enabled

        # Fall back to the runtime default
        return evaluate

    def predict(self, evaluate) -> Dict:
        if not self._should_predict(evaluate):
            logger.info(f"Prediction is disabled for {self.algorithm_name}")
            return {"skipped": "Because prediction is disabled"}

        logger.info(f"Generating predictions: {self.algorithm_name}")
        stage = "predict"
        metadata_dir = self._stage_metadata_dir(stage)
        clean_dir(metadata_dir)
        score_location = self.test_result_file_path()
        trydelete(score_location)
        cmd = self._base_command("predict", metadata_dir)
        cmd.extend(["--source", ",".join(self.test_data_paths())])
        cmd.extend(["--dest", score_location])
        cmd.extend(["--parameters", self.predict_parameter_file_path()])
        self._stream_output_to_stage_log(stage=stage, cmd=cmd)
        logger.info(f"Generated predictions for {self.algorithm_name}")
        return read_json(self._stage_metadata_file(stage))

    def _should_evaluate(self, evaluate: bool) -> bool:
        """Determine if evaluation should run."""
        evaluate_enabled = _recursive_get(
            self.algorithm_definition, ["hotvect_execution_parameters", "evaluate", "enabled"], evaluate
        )
        return evaluate_enabled

    def evaluate(self, evaluate: bool) -> Dict:
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

    def performance_test(self, evaluate: bool) -> Dict:
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
        if target_rps is not None:
            cmd.extend(["--target-rps", str(float(target_rps))])
        if target_throughput_fraction is not None:
            cmd.extend(["--target-throughput-fraction", str(float(target_throughput_fraction))])
        if workload_mode is not None:
            cmd.extend(["--workload-mode", str(workload_mode)])
        self._stream_output_to_stage_log(stage=stage, cmd=cmd)
        logger.info(f"Performance tested {self.algorithm_name}")
        ret = read_json(self._stage_metadata_file(stage))
        if samples is not None and isinstance(ret, dict):
            ret.setdefault("requested_samples", int(samples))
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
        # trydelete(self.output_path())

    def audit(self) -> Dict[str, Any]:
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

    def available_predict_parameter_cache_path(self) -> Optional[str]:
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


def _get_jvm_args(
    algorithm_definition: Dict[str, Any], task_name: str, jvm_args: Optional[List[str]]
) -> Optional[List[str]]:
    execution_parameters = algorithm_definition.get("hotvect_execution_parameters", {})
    task_names = [task_name]
    if task_name in {"generate-state", "generate_state"}:
        task_names = ["generate-state", "generate_state"]

    # See if there is a task specific override
    task_specific_jvm_args = None
    if isinstance(execution_parameters, dict):
        for candidate_task_name in task_names:
            task_parameters = execution_parameters.get(candidate_task_name)
            if isinstance(task_parameters, dict) and task_parameters.get("jvm_args", None):
                task_specific_jvm_args = task_parameters["jvm_args"]
                break

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


def _recursive_get(dx: Dict[Any, Any], keys: List[str], default: Any = None):
    def get_with_default(d, key):
        return d.get(key, default) if isinstance(d, dict) else default

    try:
        return reduce(get_with_default, keys, dx)
    except TypeError:
        return default
