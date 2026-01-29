import json
import logging
import os
import shutil
import sys
import time
import zipfile
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, tzinfo
from functools import reduce
from pathlib import Path
from typing import Any, Callable, Dict, List, NamedTuple, Optional, Set, Tuple, Union

import psutil
import six
from jinja2 import Template

import hotvect.hotvectjar
from hotvect import mlutils
from hotvect.utils import (
    as_locally_available_content,
    clean_dir,
    copy_or_link,
    read_algorithm_definition_from_jar,
    read_json,
    recursive_dict_update,
    runshell,
    store_file,
    to_local_paths,
    to_zip_archive,
    trydelete,
    verify_algorithm_hyperparameter_version,
    verify_algorithm_name,
    verify_algorithm_version,
)

logger = logging.getLogger(__name__)

EVALUATION_FUNCTIONS = {
    "standard_evaluation": mlutils.standard_evaluation,
    "real_numbers_reward_evaluation": mlutils.real_numbers_reward_evaluation,
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

    # Parameter sources
    state_soruce_base_path: Path
    data_base_path: Path

    # Output locations
    metadata_base_path: Path
    output_base_path: Path

    # Execution parameters
    enable_gzip: Optional[bool] = None
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
    ):
        self.clean_output_after_run = clean_output_after_run
        self.encode_test_data = encode_test_data
        self.execute_audit = execute_audit

        # Context
        self.algorithm_pipeline_context = algorithm_pipeline_context

        # Algorithm definition
        if isinstance(algorithm_definition, six.string_types):
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
        algo_def_evaluation_function = _recursive_get(
            self.algorithm_definition, ["hotvect_execution_parameters", "evaluation_function"]
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
        # Extract cache_base_dir from the top-level algorithm definition
        cache_base_dir = _recursive_get(self.algorithm_definition, ["hotvect_execution_parameters", "cache_base_dir"])

        self.dependency_pipelines: Dict[str, AlgorithmPipeline] = {}
        if self.algorithm_definition.get("dependencies"):
            dependencies = self.algorithm_definition["dependencies"]

            if isinstance(dependencies, list):
                # Dependencies are specified as names, so no algorithm definition overrides
                for algorithm_name in dependencies:
                    assert isinstance(algorithm_name, str)
                    algo_def_override = {}
                    if cache_base_dir:
                        inherited_def = {"hotvect_execution_parameters": {"cache_base_dir": cache_base_dir}}
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
                # Dependencies are specified as dict, so there are algorithm definition overrides
                for algorithm_name, algo_def_override in dependencies.items():
                    assert isinstance(algorithm_name, str)
                    verify_algorithm_name(algorithm_name)
                    assert isinstance(algo_def_override, dict)
                    if cache_base_dir:
                        inherited_def = {"hotvect_execution_parameters": {"cache_base_dir": cache_base_dir}}
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

        # Add state data dependencies
        source_data = self.algorithm_definition.get("source_data", {})
        for source_prefix_name, per_source_config in source_data.items():
            data_prefix = per_source_config.get("data_prefix")
            number_of_days = per_source_config.get("number_of_days", 1)
            lag_days = per_source_config.get("lag_days", 1)

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
        number_of_days = source_config.get("number_of_days", 1)
        lag_days = source_config.get("lag_days", 1)

        start_date = self.last_test_time - timedelta(days=lag_days)
        dates = [start_date - timedelta(days=n) for n in range(number_of_days)]

        root_dir = os.path.join(
            self.algorithm_pipeline_context.data_base_path,
            data_prefix,
        )

        if not os.path.isdir(root_dir):
            raise ValueError(
                (
                    f"State source data {data_prefix} does not exist: {os.path.abspath(root_dir)}.",
                    "State source data cannot be absent",
                )
            )

        # Fail if asked dates are not available
        return to_local_paths(
            root_dir,
            dates,
            fail_if_unavailable=True,
        )

    def state_output_path(self) -> str:
        state_output_filename = self.algorithm_definition.get("state_output_filename", "state_output")
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
        encode_suffix = "encoded.gz" if self.algorithm_pipeline_context.enable_gzip else "encoded"
        return os.path.join(self.output_path(), encode_suffix)

    def encoded_schema_description_file_path(self) -> str:
        return os.path.join(self.output_path(), "encoded-schema-description")

    def encoded_test_data_file_path(self) -> str:
        encode_suffix = "test-encoded.gz" if self.algorithm_pipeline_context.enable_gzip else "test-encoded"
        return os.path.join(self.output_path(), encode_suffix)

    def parameter_file_path(self) -> str:
        return os.path.join(self.output_path(), "model.parameter")

    def test_result_file_path(self) -> str:
        predict_suffix = "prediction.jsonl.gz" if self.algorithm_pipeline_context.enable_gzip else "prediction.jsonl"
        return os.path.join(self.output_path(), predict_suffix)

    def audit_data_file_path(self) -> str:
        encode_suffix = "audit.jsonl.gz" if self.algorithm_pipeline_context.enable_gzip else "audit.jsonl"
        return os.path.join(self.output_path(), encode_suffix)

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
        dest = os.path.join(self.output_path(), dest_file_name)
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

        # Write algorithm definition so that training scripts etc. can read it
        self._write_algorithm_definition()

        # Prepare dependencies
        deps_time = time.time()
        if self.dependency_pipelines:
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
            # If we don't have available predict parameters, we might need encoding parameters
            self._step_encode_parameter(result)
            if self.should_train():
                # If additionally we should train, we need these steps
                self._step_encode(result)
                self._step_train(result)
            else:
                logger.info(f"Algorithm {self.algorithm_name} does not have a training step")
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
                if _recursive_get(self.algorithm_definition, ["hotvect_execution_parameters", "with_parameter"]):
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
                    self._step_performance_test(result, evaluate)
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
        # TODO not sure if we need to package encode parameter when the algorithm is a state
        result["package_encode_params"] = self.package_encode_parameters()
        result["timing_info_sec"]["encode_parameter"] = time.time() - encode_parameter_times

    def _base_command(self, task_name: str, metadata_location: str, max_threads: int = -1) -> List[str]:
        ret = [
            "java",
            "-cp",
            f"{hotvect.hotvectjar.HOTVECT_JAR_PATH}",
        ]
        jvm_args = _get_jvm_args(self.algorithm_definition, task_name, self.algorithm_pipeline_context.jvm_options)
        if jvm_args:
            ret.extend(jvm_args)
        if "-XX:+ExitOnOutOfMemoryError" not in self.algorithm_pipeline_context.jvm_options:
            ret.append("-XX:+ExitOnOutOfMemoryError")
        ret.extend(
            [
                "com.hotvect.offlineutils.commandline.Main",
                "--algorithm-jar",
                f"{self.algorithm_jar_path()}",
                "--algorithm-definition",
                self._write_algorithm_definition(),
                "--meta-data",
                metadata_location,
            ]
        )
        if self.algorithm_pipeline_context.additional_jar_files:
            ret.extend(
                ["--additional-jars", ",".join(str(x) for x in self.algorithm_pipeline_context.additional_jar_files)]
            )
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
            template = os.path.join(cache_base_dir, self.hyperparameter_slug(), self.parameter_version, *task_paths)
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
            return os.path.join(cache_path, cache_file_name)
        else:
            return cache_path

    def generate_states(self) -> Dict:
        metadata = {}
        encoding_parameter_cache_path = self._resolve_cache_path(["generate-state"], "encoding-parameters.zip")
        available_encoding_parameter_cache_path = as_locally_available_content(
            encoding_parameter_cache_path, self.cache_path()
        )
        if available_encoding_parameter_cache_path:
            # We have cache
            logger.info(
                f"Skipping generate-state for {self.algorithm_name} because encoding parameter cache was used at {encoding_parameter_cache_path}"
            )
            metadata["skipped"] = f"Because cache was used at {encoding_parameter_cache_path}"
            return metadata

        metadata_path = os.path.join(self.metadata_path(), "generate-state.json")
        trydelete(metadata_path)
        output_path = self.state_output_path()
        trydelete(output_path)
        generator_factory_classname = self.algorithm_definition["generator_factory_classname"]
        logger.info(f"Generating state for {self.algorithm_name} at {output_path}")
        cmd = self._base_command("generate_state", metadata_path)
        cmd.extend(
            [
                "--generate-state",
                generator_factory_classname,
            ]
        )

        # Handle multiple source data and format as JSON
        source_data_dict = self.algorithm_definition["source_data"]
        source_json = {}
        for prefix_name, per_source_config in source_data_dict.items():
            source_path = self.state_source_path(per_source_config)
            source_json[prefix_name] = source_path

        cmd.extend(["--source", json.dumps(source_json)])

        cmd.extend(
            [
                "--dest",
                output_path,
            ]
        )
        runshell(cmd)
        logger.info(f"Generated state for {self.algorithm_name} at {output_path}")
        metadata = read_json(metadata_path)
        return metadata

    def package_encode_parameters(self) -> Dict:
        encoding_parameter_cache_path = self._resolve_cache_path(["generate-state"], "encoding-parameters.zip")
        available_encoding_parameter_cache_path = as_locally_available_content(
            encoding_parameter_cache_path, self.cache_path()
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
                        # Extract the member to the output path with the new member name
                        with zip_ref.open(member) as source, open(
                            os.path.join(self.output_path(), new_member_name), "wb"
                        ) as target:
                            shutil.copyfileobj(source, target)
            return {"sources": [available_encoding_parameter_cache_path], "skipped": "Because cache was available"}
        metadata = self._do_package_parameters(is_encode=True)
        if encoding_parameter_cache_path:
            # Cache was not available, but there is a cache dir specified so we have to store our results there
            logger.info(f"Caching encoding parameters for {self.algorithm_name} at {encoding_parameter_cache_path}")
            store_file(self.encode_parameter_file_path(), encoding_parameter_cache_path)
        return metadata

    def _do_package_parameters(self, is_encode: bool) -> Dict[str, Any]:
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
                state_output_path = pipeline.state_output_path()
                to_package_acc.append(
                    (
                        state_output_path,
                        to_arc_name(algorithm_name, os.path.basename(state_output_path)),
                    )
                )

            if "training_command" in pipeline.algorithm_definition:
                parameter_file = pipeline.parameter_file_path()
                if is_encode:
                    # Include parameter files from dependencies only during encode parameter packaging
                    if pipeline is not self:
                        to_package_acc.append(
                            (
                                parameter_file,
                                to_arc_name(algorithm_name, os.path.basename(parameter_file)),
                            )
                        )
                else:
                    # Include parameter files from all algorithms during predict parameter packaging
                    to_package_acc.append(
                        (
                            parameter_file,
                            to_arc_name(algorithm_name, os.path.basename(parameter_file)),
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
        to_zip_archive(to_package, parameter_package_location)
        return algorithm_parameters

    def _do_encode(self, is_test: bool) -> Dict[str, Any]:
        if "training_command" not in self.algorithm_definition:
            logger.info(
                f"Skipping {'test' if is_test else 'train'} encoding for {self.algorithm_name} because no training command exists"
            )
            return {"skipped": "Skipped because there is no training command in the algorithm definition"}
        metadata_location = os.path.join(self.metadata_path(), f"{'test-' if is_test else ''}encode_metadata.json")
        trydelete(metadata_location)
        encoded_data_location = self.encoded_test_data_file_path() if is_test else self.encoded_data_file_path()
        trydelete(encoded_data_location)
        cmd = self._base_command("encode", metadata_location)
        cmd.append("--encode")
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
        runshell(cmd)
        logger.info(
            f"Encoded {'test' if is_test else 'train'} data for {self.algorithm_name} at {encoded_data_location}"
        )
        return read_json(metadata_location)

    def should_train(self) -> bool:
        return "training_command" in self.algorithm_definition

    def encode(self) -> Dict:
        encoded_filename = os.path.basename(self.encoded_data_file_path())
        encoded_schema_description_filename = os.path.basename(self.encoded_schema_description_file_path())
        encoded_cache_path = self._resolve_cache_path(["encode"], encoded_filename)
        encoded_schema_description_cache_path = self._resolve_cache_path(
            ["encode"], encoded_schema_description_filename
        )
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
        available_parameter_cache_path = as_locally_available_content(parameter_cache_path, self.cache_path())
        if available_parameter_cache_path:
            # We have cache, no need to train
            logger.info(
                f"Skipping training for {self.algorithm_name} because predict parameter cache is available at {available_parameter_cache_path}"
            )
            return {"skipped": f"Because cache was available in {available_parameter_cache_path}"}
        logger.info(f"Training: {self.algorithm_name}")
        metadata_location = os.path.join(self.metadata_path(), "train_metadata.json")
        trydelete(metadata_location)
        parameter_location = self.parameter_file_path()
        trydelete(parameter_location)
        scratch_dir = os.path.join(self.output_path(), "train_scratch_dir")
        clean_dir(scratch_dir)
        if not os.path.isfile(self.encoded_data_file_path()):
            raise ValueError(
                f"Encoded file does not exist! Expected file here: {os.path.abspath(self.encoded_data_file_path())}"
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
        )
        train_log = runshell([train_command], shell=True)
        logger.info(f"Training completed for {self.algorithm_name}")
        metadata = {"training_command": train_command, "train_log": train_log}
        with open(metadata_location, "w") as f:
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
                        # Extract the member to the output path with the new member name
                        with zip_ref.open(member) as source, open(
                            os.path.join(self.output_path(), new_member_name), "wb"
                        ) as target:
                            shutil.copyfileobj(source, target)
            logger.info(
                f"Using cached predict parameters for {self.algorithm_name} at {available_parameter_cache_path}"
            )
            return {"sources": [original_parameter_cache_path], "skipped": "Because cache was available"}
        logger.info(f"No predict parameters cache available for {self.algorithm_name}, creating package")
        metadata = self._do_package_parameters(is_encode=False)
        logger.info(f"Predict parameters packaged for: {self.algorithm_name} at {metadata['package']}")
        if original_parameter_cache_path:
            # Cache was not available, but there is a cache dir specified so we have to store our results there
            logger.info(f"Caching predict parameters at {original_parameter_cache_path} for {self.algorithm_name}")
            store_file(self.predict_parameter_file_path(), original_parameter_cache_path)
        return metadata

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
        metadata_location = os.path.join(self.metadata_path(), "predict_metadata.json")
        trydelete(metadata_location)
        score_location = self.test_result_file_path()
        trydelete(score_location)
        cmd = self._base_command("predict", metadata_location)
        cmd.append("--predict")
        cmd.extend(["--source", ",".join(self.test_data_paths())])
        cmd.extend(["--dest", score_location])
        cmd.extend(["--parameters", self.predict_parameter_file_path()])
        runshell(cmd)
        logger.info(f"Generated predictions for {self.algorithm_name}")
        return read_json(metadata_location)

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
        metadata_location = os.path.join(self.metadata_path(), "evaluate_metadata.json")
        trydelete(metadata_location)
        logger.info(
            f"Evaluating {self.algorithm_name} with {self.evaluation_function.__name__} using {self.test_result_file_path()}"
        )
        meta_data = self.evaluation_function(self.test_result_file_path())
        logger.info(
            f"Evaluated {self.algorithm_name} with {self.evaluation_function.__name__}, results at {metadata_location}"
        )
        with open(metadata_location, "w") as f:
            json.dump(meta_data, f)
        return meta_data

    def performance_test(self, evaluate: bool) -> Dict:
        if not _recursive_get(
            self.algorithm_definition, ["hotvect_execution_parameters", "performance-test", "enabled"], evaluate
        ):
            logger.info(f"Performance test is disabled for {self.algorithm_name}")
            return {"skipped": "Because performance test is disabled"}
        metadata_location = os.path.join(self.metadata_path(), "performance_test_metadata.json")
        trydelete(metadata_location)
        available_number_of_cores = psutil.cpu_count(logical=False)
        if available_number_of_cores >= 4:
            appropriate_thread_num_for_performance_test = 2
        else:
            appropriate_thread_num_for_performance_test = 1
        logger.info(
            f"Performance testing {self.algorithm_name} with {appropriate_thread_num_for_performance_test} threads"
        )
        cmd = self._base_command(
            "performance-test", metadata_location, max_threads=appropriate_thread_num_for_performance_test
        )
        cmd.append("--performance-test")
        cmd.extend(["--source", ",".join(self.test_data_paths())])
        cmd.extend(["--parameters", self.predict_parameter_file_path()])
        runshell(cmd)
        logger.info(f"Performance tested {self.algorithm_name}")
        return read_json(metadata_location)

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
        metadata_location = os.path.join(self.metadata_path(), "audit_metadata.json")
        trydelete(metadata_location)
        audit_data_location = self.audit_data_file_path()
        trydelete(audit_data_location)
        cmd = self._base_command("audit", metadata_location)
        cmd.append("--audit")
        parameters = []
        if os.path.exists(self.encode_parameter_file_path()):
            # We have encode parameters
            parameters.append(self.encode_parameter_file_path())
        parameters.append(self.predict_parameter_file_path())
        cmd.extend(["--parameters", ",".join(parameters)])
        cmd.extend(["--source", ",".join(self.test_data_paths())])
        cmd.extend(["--dest", audit_data_location])
        runshell(cmd)
        return read_json(metadata_location)

    def predict_parameter_cache_original_path(self) -> str:
        parameter_cache_path = _recursive_get(
            self.algorithm_definition, ["hotvect_execution_parameters"] + ["with_parameter"]
        )
        if not parameter_cache_path:
            parameter_cache_path = self._resolve_cache_path(["train"], "predict-parameters.zip")
        return parameter_cache_path

    def available_predict_parameter_cache_path(self) -> str:
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
            available_parameter_cache_path = as_locally_available_content(parameter_cache_path, self.cache_path())
        self.available_parameter_cache_path = available_parameter_cache_path
        return available_parameter_cache_path


def _get_jvm_args(algorithm_definition: Dict[str, Any], task_name: str, jvm_args: List[str]):
    # See if there is a task specific override
    task_specific_jvm_args = (
        algorithm_definition.get("hotvect_execution_parameters", {}).get(task_name, {}).get("jvm_args", None)
    )
    # Try to get the value without the task name
    global_jvm_args = algorithm_definition.get("hotvect_execution_parameters", {}).get("jvm_args", None)
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
