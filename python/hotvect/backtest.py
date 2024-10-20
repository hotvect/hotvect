import copy
import glob
import hashlib
import json
import logging
import os
import re
import secrets
import shutil
import tarfile
import tempfile
import time
import uuid
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any, Callable, Dict, List, NamedTuple, Optional, Tuple, Union
from urllib.parse import urlparse
from xml.etree import ElementTree

import boto3
import psutil
from mypy_boto3_s3 import S3Client
from pebble import ProcessFuture, ProcessPool

from hotvect import utils
from hotvect.mlutils import extract_evaluation
from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext
from hotvect.sagemaker import SagemakerTrainingExecutor, wait_for_sagemaker_executors_to_finish
from hotvect.utils import (
    AlgorithmSpec,
    ConcurrencySetting,
    DirectProcessPool,
    get_boto_session_after_assuming_role,
    hexigest_as_alphanumeric,
    read_algorithm_definition_from_jar,
    read_json,
    recursive_dict_update,
    runshell,
    trydelete,
    write_data,
)

logger = logging.getLogger(__name__)


class BacktestIterationResult(NamedTuple):
    parameter_version: str
    training_data_time: str
    test_data_time: str
    result: Optional[Dict[str, Any]] = None
    error: Optional[str] = None


class BacktestResult(NamedTuple):
    algo_git_reference: str
    algorithm_definition_override: Optional[Dict[str, Any]] = None
    backtest_iteration_results: Optional[List[BacktestIterationResult]] = None
    error: Optional[str] = None


class BacktestPipeline:
    def __init__(
        self,
        algo_repo_url: str,
        algo_git_reference: str,
        data_base_dir: str,
        output_base_dir: str,
        hyperparameter_base_dir: str,
        evaluation_function: Callable[[str], Dict[str, Any]],
        last_training_time: date,
        number_of_runs: int,
        test_data_lag: timedelta,
        additional_jars: List[Path] = None,
        algorithm_definition_override: Dict[str, Any] = None,
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
        self.last_training_time = last_training_time
        self.number_of_runs = number_of_runs
        self.test_data_lag = test_data_lag
        self.algorithm_definition_override = algorithm_definition_override

        logger.info(f"Initialized BacktestPipeline with {self.__dict__}")

    def _prepare_algorithm_jar(self, git_reference) -> AlgorithmSpec:
        algo_source_path = Path(os.path.join(self.hyperparameter_dir, "algo_source"))
        algo_source_path.mkdir()
        runshell(
            f"cd {algo_source_path} && git clone {self.algo_repo_url}",
            shell=True,
        )
        cloned_path = utils.get_immediate_subdirectories(algo_source_path)
        assert (
            len(cloned_path) == 1
        ), f"More than one path found in algo source path after cloning: {os.path.abspath(algo_source_path)}"
        cloned_path = next(iter(cloned_path))
        runshell(
            (f"cd {cloned_path} && " "git fetch --all --tags && " f"git checkout {git_reference} && " "git clean -df"),
            shell=True,
        )
        runshell(
            f"cd {cloned_path} && mvn clean package -DskipTests -B",
            shell=True,
        )
        pom_path = f"{cloned_path}/pom.xml"
        xml_root = ElementTree.parse(pom_path).getroot()
        ns = re.match(r"{.*}", xml_root.tag).group(0)
        algorithm_name = xml_root.find(ns + "artifactId").text.strip()
        algorithm_version = xml_root.find(ns + "version").text.strip()
        algorithm_jars = [
            file
            for file in glob.glob(
                os.path.join(
                    cloned_path,
                    "target",
                    f"{algorithm_name}-{algorithm_version}*.jar",
                )
            )
            if os.path.isfile(file)
        ]
        if len(algorithm_jars) != 1:
            raise ValueError(f"Algorithm JAR not found or there are more than one! {algorithm_jars}")
        algorithm_jar = algorithm_jars[0]
        destination_dir = Path(self.hyperparameter_dir)
        destination_dir.mkdir(exist_ok=True)
        destination = os.path.join(destination_dir, os.path.basename(algorithm_jar))
        shutil.copyfile(algorithm_jar, destination)
        return AlgorithmSpec(
            algorithm_name=algorithm_name,
            algorithm_jar_path=Path(destination),
        )

    def _resolve_algorithm_definition(
        self,
        algorithm_spec: AlgorithmSpec,
        algorithm_definition_override: Dict[str, Any],
    ) -> Dict[str, Any]:
        if algorithm_definition_override and (
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

        if algorithm_definition_override:
            recursive_dict_update(committed_algorithm_definition, algorithm_definition_override)
        return committed_algorithm_definition

    def _prepare_algorithm_definition(
        self,
        algorithm_spec: AlgorithmSpec,
        algorithm_definition_override: Dict[str, Any],
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
        last_training_time: date,
        number_of_runs: int,
        clean: bool,
        system_performance_test: bool,
        jvm_options: List[str],
    ):
        execution_parameters = {
            "last_training_time": last_training_time.isoformat(),
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
        jvm_options: List[str] = None,
        n_process: int = -1,
        max_threads_per_process: int = -1,
        sagemaker_training_job_definition: Dict[str, Any] = None,
        role_arn_to_assume: Optional[str] = None,
    ) -> BacktestResult:
        laptime = time.time()
        algorithm_spec = self._prepare_algorithm_jar(self.algo_git_reference)
        logger.info(f"Prepared algorithm jar {algorithm_spec} in {(time.time() - laptime): .1f}")

        algorithm_definition_path = self._prepare_algorithm_definition(
            algorithm_spec, self.algorithm_definition_override
        )
        algorithm_definition = read_json(algorithm_definition_path)
        logger.info(f"Prepared algorithm definition: {algorithm_definition}")

        execution_parameter_path = self._write_execution_parameters(
            self.last_training_time,
            self.number_of_runs,
            clean,
            system_performance_test,
            jvm_options,
        )
        execution_parameters = read_json(execution_parameter_path)
        logger.info(f"Wrote down execution parameters: {execution_parameters}")

        algorithm_spec = AlgorithmSpec(algorithm_definition["algorithm_name"], algorithm_spec.algorithm_jar_path)

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
                algorithm_definition_override=algorithm_definition,
                last_training_time=self.last_training_time,
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
                role_arn_to_assume=role_arn_to_assume,
                algorithm_spec=algorithm_spec,
                algorithm_definition_override=algorithm_definition,
                last_training_time=self.last_training_time,
                number_of_runs=self.number_of_runs,
                system_performance_test=system_performance_test,
                jvm_options=jvm_options,
                clean=clean,
            )

    def _execute_on_sagemaker(
        self,
        algorithm_spec: AlgorithmSpec,
        algorithm_definition_override: Dict[str, Any],
        last_training_time: date,
        number_of_runs: int,
        system_performance_test: bool,
        jvm_options: List[str],
        clean: bool,
        sagemaker_training_job_definition: Dict[str, Any],
        role_arn_to_assume: Optional[str],
    ) -> BacktestResult:
        last_training_days = [last_training_time - timedelta(days=i) for i in range(number_of_runs)]
        additional_jar_files = self.additional_jars
        context = AlgorithmPipelineContext(
            algorithm_jar_path=algorithm_spec.algorithm_jar_path,
            state_soruce_base_path=Path(os.path.join(self.data_base_dir, "states")),
            data_base_path=Path(self.data_base_dir),
            metadata_base_path=Path(os.path.join(self.output_data_dir, "meta")),
            output_base_path=Path(os.path.join(self.output_data_dir, "out")),
            enable_gzip=False,
            jvm_options=jvm_options if jvm_options else ["-Xmx32g"],
            max_threads=None,
            queue_length=None,
            batch_size=None,
            additional_jar_files=additional_jar_files,
        )
        backtest_sagemaker_executors: List[SagemakerTrainingExecutor] = []
        for last_training_day in last_training_days:
            parameter_version = (
                f"last_train_date_{last_training_day}-last_test_date_{last_training_day + self.test_data_lag}"
            )
            algorithm_pipeline = AlgorithmPipeline(
                algorithm_pipeline_context=context,
                algorithm_definition=(
                    algorithm_spec.algorithm_name,
                    algorithm_definition_override,
                ),
                last_training_time=last_training_day,
                test_lag=self.test_data_lag,
                # passing the evaluation function to SageMaker is not supported yet. So the next line has no effect
                evaluation_func=self.evaluation_function,
                parameter_version=parameter_version,
            )

            this_iteration_sagemaker_training_job_definition = copy.deepcopy(sagemaker_training_job_definition)
            last_test_day = last_training_day + self.test_data_lag
            this_iteration_sagemaker_training_job_definition["TrainingJobName"] += f"-{last_test_day}"
            sagemaker_executor = SagemakerTrainingExecutor(
                algorithm_pipeline=algorithm_pipeline,
                training_job_definition=this_iteration_sagemaker_training_job_definition,
                role_arn_to_assume=role_arn_to_assume,
            )
            sagemaker_executor.run()
            backtest_sagemaker_executors.append(sagemaker_executor)

        wait_for_sagemaker_executors_to_finish(backtest_sagemaker_executors)
        backtest_iteration_results: List[BacktestIterationResult] = []
        errors: List[str] = []
        for backtest_sagemaker_executor in backtest_sagemaker_executors:
            backtest_iteration_result = BacktestIterationResult(
                **backtest_sagemaker_executor.get_results_as_iteration_results_params()
            )
            backtest_iteration_results.append(backtest_iteration_result)
            if backtest_iteration_result.error:
                errors.append(backtest_iteration_result.error)
        error = "\n".join(errors) if errors else None
        backtest_result = BacktestResult(
            algo_git_reference=self.algo_git_reference,
            backtest_iteration_results=backtest_iteration_results,
            error=error,
        )
        return backtest_result

    def _execute_on_local(
        self,
        algorithm_spec: AlgorithmSpec,
        algorithm_definition_override: Dict[str, Any],
        last_training_time: date,
        number_of_runs: int,
        system_performance_test: bool,
        jvm_options: List[str],
        n_process: int,
        max_thread_per_process: int,
        queue_length: int,
        clean: bool = False,
    ) -> BacktestResult:
        last_training_days = [last_training_time - timedelta(days=i) for i in range(number_of_runs)]

        additional_jars = list(self.additional_jars) if self.additional_jars else []

        context = AlgorithmPipelineContext(
            algorithm_jar_path=algorithm_spec.algorithm_jar_path,
            state_soruce_base_path=Path(os.path.join(self.data_base_dir, "states")),
            data_base_path=Path(self.data_base_dir),
            metadata_base_path=Path(os.path.join(self.output_data_dir, "meta")),
            output_base_path=Path(os.path.join(self.output_data_dir, "out")),
            enable_gzip=False,
            jvm_options=jvm_options if jvm_options else ["-Xmx32g"],
            max_threads=max_thread_per_process,
            queue_length=queue_length,
            batch_size=None,
            additional_jar_files=additional_jars,
        )

        futures: List[Tuple[BacktestIterationResult, ProcessFuture]] = []

        with ProcessPool(max_workers=n_process) as pool:
            if n_process == 1:
                # We only have one process - let's use DirectProcessPool so that we can see logs
                exec_pool = DirectProcessPool()
            else:
                exec_pool = pool
            for last_training_day in last_training_days:
                parameter_version = (
                    f"last_train_date_{last_training_day}-last_test_date_{last_training_day + self.test_data_lag}"
                )
                logging.info(f"Scheduling parameter_version: {parameter_version}")
                future = exec_pool.schedule(
                    run_one_cycle_locally,
                    kwargs={
                        "context": context,
                        "algorithm_definition": (
                            algorithm_spec.algorithm_name,
                            algorithm_definition_override,
                        ),
                        "parameter_version": parameter_version,
                        "last_training_time": last_training_day,
                        "test_data_lag": self.test_data_lag,
                        "evaluation_function": self.evaluation_function,
                        "clean": clean,
                        "performance_test": system_performance_test,
                    },
                )
                futures.append(
                    (
                        BacktestIterationResult(
                            parameter_version=parameter_version,
                            training_data_time=last_training_day.isoformat(),
                            test_data_time=(last_training_day + self.test_data_lag).isoformat(),
                            result=None,
                            error=None,
                        ),
                        future,
                    )
                )

        ret: List[BacktestIterationResult] = []
        last_error = None
        for partial_result, future in futures:
            result = utils.get_result(future)
            if type(result) == BacktestIterationResult:
                ret.append(result)
            else:
                assert "error" in result.keys() and len(result.keys()) == 1
                ret.append(partial_result._replace(error=result["error"]))
                last_error = result["error"]

        return BacktestResult(
            algo_git_reference=self.algo_git_reference,
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

    def recommended_queue_length(self, thread_count: Optional[int]) -> Optional[int]:
        return None if thread_count is None else thread_count * 4


def run_one_cycle_locally(
    context: AlgorithmPipelineContext,
    algorithm_definition: Union[str, Tuple[str, Dict[str, Any]]],
    parameter_version: str,
    last_training_time: date,
    test_data_lag: timedelta,
    evaluation_function: Callable[[str], Dict[str, Any]],
    clean: bool,
    performance_test: bool,
) -> BacktestIterationResult:
    pipeline = AlgorithmPipeline(
        algorithm_pipeline_context=context,
        algorithm_definition=algorithm_definition,
        last_training_time=last_training_time,
        test_lag=test_data_lag,
        parameter_version=parameter_version,
        evaluation_func=evaluation_function,
    )
    result = pipeline.run_all(
        clean=clean,
        performance_test=performance_test,
    )
    return BacktestIterationResult(
        parameter_version=parameter_version,
        training_data_time=last_training_time.isoformat(),
        test_data_time=(last_training_time + test_data_lag).isoformat(),
        result=result,
        error=None,
    )


def run_backtest_on_git_reference(
    algo_repo_url: str,
    algo_git_reference: str,
    data_base_dir: str,
    output_base_dir: str,
    hyperparameter_base_dir: str,
    evaluation_function: Callable[[str], Dict[str, Any]],
    last_training_time: date,
    number_of_runs: int,
    test_data_lag: timedelta,
    algorithm_definition_override: Dict[str, Any] = None,
    jvm_options: List[str] = None,
    additional_jars: List[Path] = None,
    n_process: int = 1,
    max_threads_per_process: int = -1,
    clean: bool = False,
    system_performance_test=True,
    sagemaker_training_job_definition: Dict[str, Any] = None,
    role_arn_to_assume: Optional[str] = None,
    online_offline_analysis_on_variant_id: Optional[str] = None,
) -> BacktestResult:
    def updated_sagemaker_training_job_definition():
        if not sagemaker_training_job_definition:
            return None
        copy_of_sagemaker_training_job_definition = copy.deepcopy(sagemaker_training_job_definition)

        def extract_algorithm_version_identifier():
            algo_git_ref = algo_git_reference
            hyperparameter_version = (
                algorithm_definition_override.get("hyperparameter_version", "") if algorithm_definition_override else ""
            )
            if online_offline_analysis_on_variant_id:
                # If this is a online offline analysis, and there is no explicit hyperparameter, use the variant ID
                hyperparameter_version = (
                    hyperparameter_version if hyperparameter_version else online_offline_analysis_on_variant_id
                )
            hyperparam_hash = hashlib.md5(hyperparameter_version.encode("utf-8")).hexdigest()[:6]
            return f"-{algo_git_ref[:6]}-{hexigest_as_alphanumeric(hyperparam_hash)}"

        copy_of_sagemaker_training_job_definition["TrainingJobName"] += extract_algorithm_version_identifier()
        return copy_of_sagemaker_training_job_definition

    this_gitref_sagemaker_training_job_definition = updated_sagemaker_training_job_definition()

    pipeline = BacktestPipeline(
        algo_repo_url=algo_repo_url,
        algo_git_reference=algo_git_reference,
        data_base_dir=data_base_dir,
        output_base_dir=output_base_dir,
        hyperparameter_base_dir=hyperparameter_base_dir,
        evaluation_function=evaluation_function,
        last_training_time=last_training_time,
        number_of_runs=number_of_runs,
        test_data_lag=test_data_lag,
        algorithm_definition_override=algorithm_definition_override,
        additional_jars=additional_jars,
    )
    return pipeline.run_all(
        clean=clean,
        system_performance_test=system_performance_test,
        role_arn_to_assume=role_arn_to_assume,
        sagemaker_training_job_definition=this_gitref_sagemaker_training_job_definition,
        jvm_options=jvm_options,
        n_process=n_process,
        max_threads_per_process=max_threads_per_process,
    )


def run_backtest_on_git_references(
    algo_repo_url: str,
    algo_git_references: Union[List[str], List[Tuple[str, Dict[str, Any]]]],
    data_base_dir: str,
    output_base_dir: str,
    hyperparameter_base_dir: str,
    evaluation_function: Callable[[str], Dict[str, Any]],
    last_training_time: date,
    number_of_runs: int,
    test_data_lag: timedelta,
    available_physical_cores: int = psutil.cpu_count(logical=False),
    clean: bool = False,
    system_performance_test: bool = True,
    jvm_options: List[str] = None,
    additional_jars: List[Path] = None,
    sagemaker_training_job_definition: Dict[str, Any] = None,
    role_arn_to_assume: Optional[str] = None,
    concurrency_setting: ConcurrencySetting = None,
    online_offline_analysis_on_variant_id: Optional[str] = None,
) -> List[BacktestResult]:
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
        algo_git_references = [(e, {}) for e in algo_git_references]
        logger.info(f"Starting backtest on git references: {[x[0] for x in algo_git_references]}")
    else:
        logger.info(f"Starting backtest on git references with algorithm definition overrides: {algo_git_references}")

    if sagemaker_training_job_definition is not None:
        if additional_jars:
            raise ValueError(
                "Currently you cannot use the additional_jars option with Sagemaker. Please use local execution mode"
            )

        this_sagemaker_training_job_definition = copy.deepcopy(sagemaker_training_job_definition)
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
            (
                f"Running in local mode, using concurrency setting {concurrency_setting} "
                f"for available cores: {available_physical_cores}"
            )
        )

    futures: List[Tuple[BacktestResult, ProcessFuture]] = []

    with ProcessPool(max_workers=concurrency_setting.total_backtest_pipelines) as pool:
        if concurrency_setting.total_backtest_pipelines == 1:
            # We only have one process - let's use DirectProcessPool so that we can see logs
            exec_pool = DirectProcessPool()
        else:
            exec_pool = pool

        for (
            algo_git_reference,
            algorithm_definition_override,
        ) in algo_git_references:
            if algorithm_definition_override:
                logger.info(f"Starting backtest on {algo_git_reference} with override: {algorithm_definition_override}")
            else:
                logger.info(f"Starting backtest on {algo_git_reference}")

                algorithm_definition_override = algorithm_definition_override if algorithm_definition_override else None

            future = exec_pool.schedule(
                run_backtest_on_git_reference,
                kwargs={
                    "algo_repo_url": algo_repo_url,
                    "algo_git_reference": algo_git_reference,
                    "data_base_dir": data_base_dir,
                    "output_base_dir": output_base_dir,
                    "hyperparameter_base_dir": hyperparameter_base_dir,
                    "evaluation_function": evaluation_function,
                    "last_training_time": last_training_time,
                    "number_of_runs": number_of_runs,
                    "test_data_lag": test_data_lag,
                    "algorithm_definition_override": algorithm_definition_override,
                    "jvm_options": jvm_options,
                    "n_process": concurrency_setting.nproc_per_backtest_pipeline,
                    "max_threads_per_process": concurrency_setting.threads_per_backtest_process,
                    "clean": clean,
                    "system_performance_test": system_performance_test,
                    "sagemaker_training_job_definition": this_sagemaker_training_job_definition,
                    "role_arn_to_assume": role_arn_to_assume,
                    "online_offline_analysis_on_variant_id": online_offline_analysis_on_variant_id,
                },
            )
            futures.append(
                (
                    BacktestResult(
                        algo_git_reference=algo_git_reference,
                        algorithm_definition_override=algorithm_definition_override,
                    ),
                    future,
                )
            )

    ret = []
    for partial_result, future in futures:
        result = utils.get_result(future)
        if type(result) == BacktestResult:
            ret.append(result)
        else:
            assert "error" in result.keys() and len(result.keys()) == 1
            ret.append(partial_result._replace(error=result["error"]))
    return ret


def list_output_dirs(
    output_base_dir: str,
    algorithm_name_pattern: str = ".*",
    algorithm_version_pattern: str = ".*",
    from_including_test_date: date = None,
    to_including_test_date: date = None,
) -> List[Path]:
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


PATTERN = r"/([\w-]+)@([\.\w-]+)/last_train_date_(\d{4}-\d{2}-\d{2}|NA)-last_test_date_(\d{4}-\d{2}-\d{2})"


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
        return date.fromisoformat(match.group(4))
    else:
        raise ValueError(path)


def extract_evaluation_result(
    output_base_dir: str,
    algorithm_name_pattern: str = ".*",
    algorithm_version_pattern: str = ".*",
    from_including_test_date: date = None,
    to_including_test_date: date = None,
):
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
        from_including_test_date: date = None,
        to_including_test_date: date = None,
        role_arn_to_assume: Optional[str] = None,
    ):
        self._s3_base_prefix: str = s3_base_prefix
        self._training_job_id_pattern: str = training_job_id_pattern
        self._algorithm_name_pattern: str = algorithm_name_pattern
        self._algorithm_version_pattern: str = algorithm_version_pattern
        self._skip_data_if_already_present: bool = skip_data_if_already_present
        self._from_including_test_date: date = from_including_test_date
        self._to_including_test_date: date = to_including_test_date

        self._dest_base_dir: Path = Path(dest_base_dir)
        self._dest_base_dir.mkdir(parents=True, exist_ok=True)

        self._include_metadata: bool = include_metadata
        self._include_output_data: bool = include_output_data

        s3_uri_parsed = urlparse(self._s3_base_prefix)
        self._s3_source_bucket: str = s3_uri_parsed.netloc
        self._s3_source_prefix: str = s3_uri_parsed.path.strip("/")

        session = get_boto_session_after_assuming_role(role_arn_to_assume) if role_arn_to_assume else boto3.Session()
        self._s3_client: S3Client = session.client("s3")

        self._match_regex = (
            rf"{self._s3_source_prefix}/({self._training_job_id_pattern})-(\d\d\d\d-\d\d-\d\d)/"
            rf"({self._algorithm_name_pattern})@({self._algorithm_version_pattern})(-(.+))?/result.json"
        )
        logger.debug(f"Regex used to find successful SageMaker runs {self._match_regex}")

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
        training_job, last_training_backtest_date, algorithm_name = match.group(1, 2, 3)
        algorithm_version, hyperparameter = match.group(4, 6)
        if not self._is_date_in_range(last_training_backtest_date):
            return
        backtest_key = (last_training_backtest_date, algorithm_name, algorithm_version, hyperparameter)
        if (
            backtest_key not in relevant_executions
            or relevant_executions[backtest_key].execution_date < key["LastModified"]
        ):
            relevant_executions[backtest_key] = _SuccessfulSageMakerResultsComponents(
                backtest_test_date=last_training_backtest_date,
                algorithm_name=algorithm_name,
                algorithm_version=algorithm_version,
                training_job=training_job,
                hyperparameter=hyperparameter,
                execution_date=key["LastModified"],
                key=key["Key"],
            )

    def _is_date_in_range(self, last_training_backtest_date: str):
        if self._from_including_test_date and self._from_including_test_date > date.fromisoformat(
            last_training_backtest_date
        ):
            return False
        if (
            self._to_including_test_date
            and date.fromisoformat(last_training_backtest_date) > self._to_including_test_date
        ):
            return False
        return True

    def _download_results(
        self, relevant_executions: Dict[Tuple[str, str, str, Optional[str]], _SuccessfulSageMakerResultsComponents]
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
                local_result_json_path = (
                    f"{algo_id}/last_train_date_NA-last_test_date_{result_components.backtest_test_date}/result.json"
                )
                self._download_result_from_s3(
                    result_components, remote_result_json_path, os.path.join("meta", local_result_json_path)
                )

    def _download_result_from_s3(
        self,
        result_components: _SuccessfulSageMakerResultsComponents,
        remote_result_file: str,
        dest_file: str,
    ):
        destination = self._dest_base_dir.joinpath(dest_file)
        os.makedirs(destination.parent, exist_ok=True)
        s3_prefix_results = self._get_base_prefix_to_download(result_components)
        try:
            self._s3_client.download_file(
                self._s3_source_bucket,
                f"{s3_prefix_results}/{remote_result_file}",
                str(destination),
            )
        except Exception as e:
            logger.warning(
                f"Failed to download remote file {remote_result_file} due to {e}. Skipping result_component: {result_components}"
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
                file.extractall(self._dest_base_dir.joinpath(local_sub_dir))
            os.remove(tmp_download_location)
        except Exception as e:
            logger.warning(
                f"Failed to download remote file {remote_result_file} due to {e}. Skipping result_component: {result_components}"
            )
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
                            f"meta/{result_components.algorithm_name}@{result_components.algorithm_version}{hyperparam}/last_train_date*last_test_date_{result_components.backtest_test_date}/result.json"
                        )
                    )
                )
            )
            >= 1
        )
