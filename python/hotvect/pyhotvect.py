import json
import logging
import os
import shutil
import sys
import time
import zipfile
from dataclasses import dataclass
from datetime import date, datetime, timedelta, tzinfo
from pathlib import Path
from typing import Any, Callable, Dict, List, NamedTuple, Optional, Set, Tuple, Union

import psutil
import six
from jinja2 import Template

import hotvect.hotvectjar
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
    train_data_prefix: str
    test_data_prefix: str
    train_data_dates: Set[date]
    test_data_dates: Set[date]


class AlgorithmPipeline:
    def __init__(
        self,
        algorithm_pipeline_context: AlgorithmPipelineContext,
        algorithm_definition: Union[str, Tuple[str, Dict[str, Any]]],
        last_training_time: date,
        test_lag: timedelta,
        evaluation_func: Callable[[str], Dict[str, Any]],
        hyperparameter_version: str = None,
        parameter_version: str = None,
        hotvect_jar_path: Path = hotvect.hotvectjar.HOTVECT_JAR_PATH,
        clean_output_after_run=False,
        execute_performance_test=True,
        encode_test_data=False,
        execute_audit=False,
    ):
        self.clean_output_after_run = clean_output_after_run
        self.execute_performance_test = execute_performance_test
        self.encode_test_data = encode_test_data
        self.execute_audit = execute_audit

        # Hotvect jar path
        self.hotvect_jar_path = hotvect_jar_path

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

        # Parameter version and runtime
        nowtime = datetime.utcnow().replace(tzinfo=SimpleUTC())
        self.ran_at: str = nowtime.isoformat()
        # If no parameter version was specified, we use ran_at
        if parameter_version:
            self.parameter_version = parameter_version
        else:
            self.parameter_version = (
                f"last_train_date_{last_training_time}-last_test_date_{last_training_time + test_lag}"
            )

        # Execution parameters
        self.last_training_time = last_training_time
        self.test_lag = test_lag

        self.feature_states: Dict[str, str] = {}

        self.evaluation_function = evaluation_func

        # Prepare dependency pipelines
        self.dependency_pipelines: Dict[str, AlgorithmPipeline] = {}
        if self.algorithm_definition.get("dependencies"):
            dependencies = self.algorithm_definition["dependencies"]
            if isinstance(dependencies, list):
                # Dependencies are specified as names, so no algorithm definition overrides
                for algorithm_name in dependencies:
                    assert isinstance(algorithm_name, str)
                    pipeline = AlgorithmPipeline(
                        algorithm_pipeline_context=self.algorithm_pipeline_context,
                        algorithm_definition=algorithm_name,
                        last_training_time=self.last_training_time,
                        test_lag=self.test_lag,
                        hyperparameter_version=self.hyper_parameter_version,
                        parameter_version=self.parameter_version,
                        evaluation_func=self.evaluation_function,
                        execute_performance_test=execute_performance_test,
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
                    pipeline = AlgorithmPipeline(
                        algorithm_pipeline_context=self.algorithm_pipeline_context,
                        algorithm_definition=(algorithm_name, algo_def_override),
                        last_training_time=self.last_training_time,
                        test_lag=self.test_lag,
                        hyperparameter_version=self.hyper_parameter_version,
                        parameter_version=self.parameter_version,
                        evaluation_func=self.evaluation_function,
                        execute_performance_test=execute_performance_test,
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
        return [self.last_training_time - timedelta(days=x) for x in range(num_of_training_days)]

    def training_data_paths(self):
        return to_local_paths(
            os.path.join(
                self.algorithm_pipeline_context.data_base_path,
                self.algorithm_definition.get("train_data_prefix", "train"),
            ),
            self._training_dates(),
        )

    def _test_dates(self):
        return [self.last_training_time + self.test_lag]

    def test_data_paths(self) -> List[str]:
        testing_dates = self._test_dates()
        # For convenience, when test data is not available, it returns an empty list rather than failing
        root_dir = os.path.join(
            self.algorithm_pipeline_context.data_base_path,
            self.algorithm_definition.get("test_data_prefix", "test"),
        )
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
        ret = [
            DataDependency(
                algorithm_name=self.algorithm_name,
                algorithm_version=self.algorithm_version,
                train_data_prefix=self.algorithm_definition.get("train_data_prefix"),
                test_data_prefix=self.algorithm_definition.get("test_data_prefix"),
                train_data_dates=set(self._training_dates()),
                test_data_dates=set(self._test_dates()),
            )
        ]
        for dependency in self.dependency_pipelines.values():
            ret.extend(dependency.data_dependencies())
        return ret

    def state_source_path(self, source_data_prefix: str):
        # States are taken from the train date
        training_dates = [self.last_training_time]
        root_dir = os.path.join(
            self.algorithm_pipeline_context.data_base_path,
            source_data_prefix,
        )
        if not os.path.isdir(root_dir):
            raise ValueError(
                (
                    f"State source data {source_data_prefix} does not exist: {os.path.abspath(root_dir)}. ",
                    "State source data cannot be absent",
                )
            )

        # Fail if asked dates are not available
        return to_local_paths(
            root_dir,
            training_dates,
            fail_if_unavailable=True,
        )

    def state_output_path(self, state_name: str):
        state_filename = f"{state_name}"
        return os.path.join(self.output_path(), state_filename)

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
        clean=False,
        performance_test=True,
    ) -> Dict[str, Any]:
        start_time = time.time()
        result: Dict[str, Any] = {
            "algorithm_id": self.hyperparameter_slug(),
            "parameter_version": self.parameter_version,
            "test_data_time": (self.last_training_time + self.test_lag).isoformat(),
            "train_last_data_time": self.last_training_time.isoformat(),
            "ran_at": self.ran_at,
            "algorithm_definition": self.algorithm_definition,
            "timing_info_sec": {},
        }

        self.clean_output_after_run = clean
        self.execute_performance_test = performance_test

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
                logger.info(f"Preparing dependency: {algorithm_name}")
                dependency_result = pipeline.run_all(
                    clean=self.clean_output_after_run,
                    performance_test=self.execute_performance_test,
                )
                result["dependencies"][algorithm_name] = dependency_result
            logger.info(f"Prepared all dependencies: {self.dependency_pipelines.keys()}")
        result["timing_info_sec"]["prepare_dependencies"] = time.time() - deps_time

        if not self.available_predict_parameter_cache_path():
            # If we don't have available predict parameters, we might need encoding parameters
            self._step_encode_parameter(result)
            if self.should_train():
                # If additionally we should train, we need these steps
                self._step_encode(result)
                self._step_train(result)

        self._step_predict_parameter(result)

        if len(self.test_data_paths()) > 0:
            logger.info(f"We have valid test data for {self.algorithm_name}")

            if _recursive_get(self.algorithm_definition, ["hotvect_execution_parameters", "with_parameter"]):
                reason_for_skipping = {"skipped": "Because with_parameter was specified"}
                tasks_to_skip = ["predict", "evaluate", "performance_test", "encode_test"]
                logger.info(f'"with_parameter" is specified for {self.algorithm_name}, skipping all steps')
                for task_id in tasks_to_skip:
                    result[task_id] = reason_for_skipping
            else:
                self._step_predict(result)

                self._step_evaluate(result)

                self._step_performance_test(result)

                self._step_encode_test_data(result)

                self._step_execute_audit(result)

        else:
            reason_for_skipping = {"skipped": "Because no test data was available"}
            tasks_to_skip = ["predict", "evaluate", "performance_test", "encode_test"]
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
            logger.info(f"Audit skipped: {self.algorithm_name}")
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

    def _step_performance_test(self, result):
        if self.execute_performance_test:
            logger.info(f"Performance testing: {self.algorithm_name}")
            perftest_times = time.time()
            result["performance_test"] = self.performance_test()
            result["timing_info_sec"]["performance_test"] = time.time() - perftest_times
            logger.info(f"Performance tested: {self.algorithm_name}")
        else:
            result["performance_test"] = {"skipped": "Because performance_test=False was specified"}
            logger.info(f"Performance testing skipped: {self.algorithm_name}")

    def _step_evaluate(self, result):
        logger.info(f"Evaluating: {self.algorithm_name}")
        evaluate_times = time.time()
        result["evaluate"] = self.evaluate()
        result["timing_info_sec"]["evaluate"] = time.time() - evaluate_times
        logger.info(f"Evaluated: {self.algorithm_name}")

    def _step_predict(self, result):
        logger.info(f"Generating predictions: {self.algorithm_name}")
        predict_times = time.time()
        result["predict"] = self.predict()
        result["timing_info_sec"]["predict"] = time.time() - predict_times
        logger.info(f"Predictions generated for: {self.algorithm_name}")

    def _step_predict_parameter(self, result):
        logger.info(f"Preparing predict parameters for: {self.algorithm_name}")
        package_times = time.time()
        result["package_predict_params"] = self.package_predict_parameters()
        result["timing_info_sec"]["package_predict_params"] = time.time() - package_times
        logger.info(f"Predict parameters prepared: {self.algorithm_name}")

    def _step_train(self, result):
        logger.info(f"Training : {self.algorithm_name}")
        train_times = time.time()
        result["train"] = self.train()
        result["timing_info_sec"]["train"] = time.time() - train_times
        logger.info(f"Trained: {self.algorithm_name}")

    def _step_encode(self, result):
        logger.info(f"Preparing encoded data: {self.algorithm_name}")
        encode_times = time.time()
        result["encode"] = self.encode()
        result["timing_info_sec"]["encode"] = time.time() - encode_times
        logger.info(f"Encoded data prepared for: {self.algorithm_name}")

    def _step_encode_parameter(self, result):
        logger.info(f"Preparing encode parameters for: {self.algorithm_name}")
        result["states"] = self.generate_states()
        result["package_encode_params"] = self.package_encode_parameters()
        logger.info(f"Encode parameters prepared: {self.algorithm_name}")

    def _base_command(self, task_name: str, metadata_location: str, max_threads: int = -1) -> List[str]:
        ret = [
            "java",
            "-cp",
            f"{self.hotvect_jar_path}",
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
        # Get the cache path or cache instruction from the task specific parameters

        override = _recursive_get(
            self.algorithm_definition, ["hotvect_execution_parameters"] + list(task_paths) + ["cache"]
        )

        if override:
            # We are asked to use cache
            if override is True:
                # We are instructed to use the default cache path
                cache_base_dir = self.algorithm_definition.get("hotvect_execution_parameters", {}).get("cache_base_dir")
                if not cache_base_dir:
                    raise ValueError(
                        "Was asked to use default cache path, but no cache_base_dir was specified under hotvect_execution_parameters.cache_base_dir"
                    )
                template = os.path.join(
                    cache_base_dir, f"{self.hyperparameter_slug()}", f"{self.parameter_version}", *task_paths
                )
            else:
                # We are instructed to use a specific cache path
                template = override
            cache_path = Template(template).render(
                hyperparameter_slug=self.hyperparameter_slug(),
                parameter_version=self.parameter_version,
            )
            if cache_file_name:
                return os.path.join(cache_path, cache_file_name)
            else:
                return cache_path
        else:
            return None

    def generate_states(self) -> Dict:
        states = self.algorithm_definition.get("states", {})
        metadata = {}
        encoding_parameter_cache_path = self._resolve_cache_path(["generate-state"], "encoding-parameters.zip")
        available_encoding_parameter_cache_path = as_locally_available_content(
            encoding_parameter_cache_path, self.cache_path()
        )
        if available_encoding_parameter_cache_path:
            # We have cache
            metadata["skipped"] = f"Because cache was used at {encoding_parameter_cache_path}"
            return metadata

        for state_name, instruction in states.items():
            metadata_path = os.path.join(self.metadata_path(), f"generate-state-{state_name}.json")
            trydelete(metadata_path)

            output_path = self.state_output_path(state_name)
            trydelete(output_path)

            generator_factory_classname = instruction["generator_factory_classname"]

            logger.info(f"Generating state: {state_name}")
            cmd = self._base_command("generate_state", metadata_path)

            cmd.extend(
                [
                    "--generate-state",
                    generator_factory_classname,
                ]
            )

            # Handle multiple source data prefixes and format as JSON
            source_data_prefixes = instruction["source_data_prefix"]
            source_json = {}
            for prefix_name, prefix_value in source_data_prefixes.items():
                source_path = self.state_source_path(prefix_value)
                source_json[prefix_name] = source_path

            cmd.extend(["--source", json.dumps(source_json)])

            cmd.extend(
                [
                    "--dest",
                    output_path,
                ]
            )
            runshell(cmd)
            metadata[state_name] = read_json(metadata_path)
            self.feature_states[state_name] = output_path

        return metadata

    def package_encode_parameters(self) -> Dict:
        encoding_parameter_cache_path = self._resolve_cache_path(["generate-state"], "encoding-parameters.zip")
        available_encoding_parameter_cache_path = as_locally_available_content(
            encoding_parameter_cache_path, self.cache_path()
        )
        if available_encoding_parameter_cache_path:
            # We have cache
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
        parameter_metadata_filename = f"algorithm-{'encode-' if is_encode else ''}parameters.json"

        def process_pipeline(pipeline, algorithm_name, to_package_acc):
            parameter_metadata_path = os.path.join(pipeline.output_path(), parameter_metadata_filename)
            to_package_acc.append(
                (
                    parameter_metadata_path,
                    to_arc_name(algorithm_name, parameter_metadata_filename),
                )
            )
            states = pipeline.algorithm_definition.get("states", {})
            for state_name, instruction in states.items():
                output_path = self.state_output_path(state_name)
                to_package_acc.append((output_path, to_arc_name(algorithm_name, os.path.basename(output_path))))

            if not is_encode and "training_command" in pipeline.algorithm_definition:
                parameter_file = pipeline.parameter_file_path()
                to_package_acc.append(
                    (
                        parameter_file,
                        to_arc_name(algorithm_name, parameter_file),
                    )
                )
            for sub_algorithm_name, sub_pipeline in pipeline.dependency_pipelines.items():
                process_pipeline(sub_pipeline, sub_algorithm_name, to_package_acc)

        process_pipeline(self, self.algorithm_name, to_package)

        last_training_time = (
            datetime(
                year=self.last_training_time.year, month=self.last_training_time.month, day=self.last_training_time.day
            )
            .replace(tzinfo=SimpleUTC())
            .isoformat()
            if self.last_training_time
            else None
        )

        # Add the algo parameters
        algorithm_parameters = {
            "algorithm_name": self.algorithm_name,
            "algorithm_version": self.algorithm_version,
            "hyperparameter_version": self.hyper_parameter_version,
            "parameter_id": self.parameter_version,
            "ran_at": self.ran_at,
            "last_training_time": last_training_time,
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
        runshell(cmd)
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
            return {"skipped": "Because no training command exists for this algorithm"}

        parameter_cache_path = self._resolve_cache_path(["train"], "predict-parameters.zip")
        available_parameter_cache_path = as_locally_available_content(parameter_cache_path, self.cache_path())
        if available_parameter_cache_path:
            # We have cache, no need to train
            return {"skipped": f"Because cache was available in {available_parameter_cache_path}"}

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

            return {"sources": [original_parameter_cache_path], "skipped": "Because cache was available"}

        metadata = self._do_package_parameters(is_encode=False)
        if original_parameter_cache_path:
            # Cache was not available, but there is a cache dir specified so we have to store our results there
            store_file(self.predict_parameter_file_path(), original_parameter_cache_path)

        return metadata

    def predict(self) -> Dict:
        if not _recursive_get(self.algorithm_definition, ["hotvect_execution_parameters", "predict", "enabled"], True):
            return {"skipped": "Because prediction is disabled"}

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
        return read_json(metadata_location)

    def evaluate(self) -> Dict:
        if not _recursive_get(self.algorithm_definition, ["hotvect_execution_parameters", "predict", "enabled"], True):
            return {"skipped": "Because prediction is disabled"}

        metadata_location = os.path.join(self.metadata_path(), "evaluate_metadata.json")
        trydelete(metadata_location)

        logger.debug(f"Calling evaluation function on {self.test_result_file_path()}")
        meta_data = self.evaluation_function(self.test_result_file_path())
        with open(metadata_location, "w") as f:
            json.dump(meta_data, f)
        return meta_data

    def performance_test(self) -> Dict:
        if not _recursive_get(
            self.algorithm_definition, ["hotvect_execution_parameters", "performance-test", "enabled"], True
        ):
            return {"skipped": "Because performance test is disabled"}

        metadata_location = os.path.join(self.metadata_path(), "performance_test_metadata.json")
        trydelete(metadata_location)

        available_number_of_cores = psutil.cpu_count(logical=False)
        if available_number_of_cores >= 4:
            appropriate_thread_num_for_performance_test = 2
        else:
            appropriate_thread_num_for_performance_test = 1

        cmd = self._base_command(
            "performance-test", metadata_location, max_threads=appropriate_thread_num_for_performance_test
        )
        cmd.append("--performance-test")
        cmd.extend(["--source", ",".join(self.test_data_paths())])
        cmd.extend(["--parameters", self.predict_parameter_file_path()])
        runshell(cmd)
        return read_json(metadata_location)

    def clean_output(self) -> None:
        # TODO decide what to do here
        trydelete(self.encoded_data_file_path())
        # trydelete(self.parameter_file_path())
        trydelete(self.test_result_file_path())
        # trydelete(self.output_path())

    def audit(self) -> Dict[str, Any]:
        if not _recursive_get(self.algorithm_definition, ["hotvect_execution_parameters", "predict", "enabled"], False):
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
        if self.feature_states:
            # We have feature states
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


def _recursive_get(d, keys, default=None):
    if keys and d:
        element = d.get(keys[0])
        if element:
            return _recursive_get(element, keys[1:], default=default)
    # If there are still keys left, return None
    if keys:
        return default
    return d
