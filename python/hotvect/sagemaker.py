"""Training in SageMaker.

This module has code that will facilitate the execution of the Hotvect algorithms in SageMaker.
"""

import copy
import datetime
import json
import logging
import os
import re
import tempfile
import time
import traceback
import typing as t
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

import boto3
from botocore.exceptions import ClientError
from mypy_boto3_s3 import S3Client
from mypy_boto3_sagemaker import SageMakerClient
from mypy_boto3_sagemaker.type_defs import CreateTrainingJobResponseTypeDef, DescribeTrainingJobResponseTypeDef
from sagemaker_training.environment import Environment

import hotvect.utils as hotvect_utils
from hotvect import mlutils
from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext
from hotvect.utils import get_boto_session_after_assuming_role

WRITE_METADATA_ENV_VARIABLE = "WRITE_METADATA_TO_S3"
WRITE_OUTPUT_ENV_VARIABLE = "WRITE_OUTPUT_TO_S3"

JOB_STATUS_POLLING_WAIT_IN_SECS = 60
"""Time to wait between checks for the status of a SageMaker training job"""
ALGO_DEF_HYPERPARAMETER_PREFIX: str = "_algo_def_"
"""This is the prefix that the algorithm definition keys will have in the hyperparameter dictionary."""
ALGO_PIPELINE_HYPERPARAMETER_PREFIX: str = "_pipeline_params_"
"""This is the prefix that the algorithm pipeline params keys will have in the hyperparameter dictionary."""
ALGO_PIPELINE_CONTEXT_PREFIX: str = "_pipeline_context_"
"""This is the prefix that the algorithm pipeline context values will have in the hyperparameter dictionary."""
MANDATORY_PARAMETERS = [
    "s3_uri_result_file",
    "s3_uri_algorithm_jar",
    f"{ALGO_DEF_HYPERPARAMETER_PREFIX}algorithm_name",
    f"{ALGO_DEF_HYPERPARAMETER_PREFIX}algorithm_version",
]
HOTVECT_JAVA_LOG_PATH = "/var/log/hotvect.log"
"""List of hyperparameters that HAVE to be set in order to successfully rebuild the AlgorithmPipeline"""

logger = logging.getLogger(__name__)


@dataclass
class _ParsedKey:
    key: str
    sub_key: str
    key_type: str


def _parse_key(key: str, object_separator_char: str = ".") -> _ParsedKey:
    if not key:
        ValueError(f"{key} should have a value")
    escaped_separator = re.escape(object_separator_char)
    split_key = re.split(f"{escaped_separator}|\\[(\\d+)]{escaped_separator}?", key, maxsplit=1)
    # If there was no split, it's a basic key.
    if len(split_key) == 1:
        return _ParsedKey(split_key[0], "", "string")

    # "key.sub_key" will result in ["key", None, "sub_key"]
    if not split_key[1]:
        return _ParsedKey(split_key[0], split_key[2], "object")

    # If it wasn't one of the previous cases and there's no first part of the key:
    if not split_key[0]:
        if split_key[2].startswith("["):
            # "[0][0]" will result in a split like ["", "0", "[0]"]
            key_type = "list"
        elif not split_key[2]:
            # "[0]" will result in a split like ["", "0", ""]
            key_type = "string"
        else:
            # "[0].sub_key" will result in a split like ["", "0", "sub_key"]
            key_type = "object"
        return _ParsedKey(split_key[1], split_key[2], key_type)

    # "key[0]" will result in ["key", "0", ""].
    if not split_key[2]:
        return _ParsedKey(split_key[0], f"[{split_key[1]}]", "list")
    return _ParsedKey(split_key[0], f"[{split_key[1]}].{split_key[2]}", "list")


class _DictUnflattener:
    def __init__(self, dictionary: t.Dict[str, str], prefix: str):
        self._data = {}
        for key, value in dictionary.items():
            if key.startswith(prefix):
                # remove the prefix and add the value
                self._add_data(key[len(prefix) :], value)

    def _add_data(self, key: str, value: str):
        parsed_key: _ParsedKey = _parse_key(key)
        if parsed_key.key not in self._data:
            self._data[parsed_key.key] = _DictNode()
        self._data[parsed_key.key].add_data(parsed_key, value)

    def unflatten(self):
        final_dict = {}
        for k, v in self._data.items():
            final_dict[k] = v.get_data()
        return final_dict


class _DictNode:
    def __init__(self) -> None:
        self._type: t.Optional[str] = None
        self._data: t.Union[str, t.Dict, None] = None

    def get_data(self):
        if self._type == "string":
            return self._data
        elif self._type == "object":
            return {k: v.get_data() for k, v in self._data.items()}
        elif self._type == "list":
            nodes_sorted_by_index = sorted(self._data.items(), key=lambda x: x[0])
            # return a list with only the data of the values, the keys were already used to get the order
            return [node[1].get_data() for node in nodes_sorted_by_index]

    def add_data(self, key: _ParsedKey, value: str) -> None:
        self._set_as(key.key_type)
        if key.key_type == "string":
            self._data = value
            return

        # Initialize the data if not yet done
        if not self._data:
            self._data = {}

        parsed_subkey: _ParsedKey = _parse_key(key.sub_key)
        if parsed_subkey.key not in self._data:
            self._data[parsed_subkey.key] = _DictNode()
        self._data[parsed_subkey.key].add_data(parsed_subkey, value)

    def _set_as(self, type):
        if not self._type:
            self._type = type
        assert self._type == type, f"This node already had another type ({self._type}). You can't change it to {type}"


def unflatten_dict(dictionary: t.Dict[str, str], prefix: str = ""):
    unflattener = _DictUnflattener(dictionary, prefix=prefix)
    return unflattener.unflatten()


def flatten_dict(dictionary: t.Dict[str, t.Any], prefix: str = "", separator: str = "") -> t.Dict[str, str]:
    """Flattens the algorithm definition.

    This function assumes that the keys won't contain dots. If they do, it could result into clobbering issues as
    the dot is used for the separation of the nested dicts. So {"a.b": {"c": "x"}} will look the same as
    {"a": {"b.c": "x"}}
    """
    prefix = "" or prefix
    items = []
    for key, value in dictionary.items():
        new_key = f"{prefix}{separator}{key}"
        if isinstance(value, dict):
            items.extend(flatten_dict(value, prefix=new_key, separator=".").items())
        elif isinstance(value, list):
            for position, v in enumerate(value):
                items.extend(flatten_dict({f"[{position}]": v}, new_key).items())
        else:
            items.append((new_key, json.dumps(value)))
    return dict(items)


class HotvectSagemakerError(Exception):
    pass


class SagemakerTrainingExecutor:
    """A class that sets up everything needed to create a training execution in Sagemaker.

    A SageMaker image that wants to be successfully be called using this executor has to implement the following:
        - The algorithm jar has to be taken from the s3 path specified in the hyperparameter `s3_uri_algorithm_jar`
        - If the execution finishes successfully, the result file should be copied to the path specified in the
        the hyperparameter `s3_uri_result_file`.

    Those URIs are defined by a combination of the the `OutputDataConfig` and the `TrainingJobName` of the training
    job definition. That's the same path that SageMaker writes its outputs to (model and extra data), so doing it like
    that everything would be under the same path.
    See https://docs.aws.amazon.com/sagemaker/latest/dg/your-algorithms-training-algo-output.html

    This class creates boto3 sessions to interact with s3 and sagemaker. For them to work, you have to have configured
    some credentials with permissions to write to the `OutputDataConfig` and to execute TrainingJobs in SageMaker.
    See more about credentials in https://boto3.amazonaws.com/v1/documentation/api/latest/guide/credentials.html

    Args:
        algorithm_pipeline: The algorithm pipeline that will be executed.
        training_job_definition: The training job definition that will be sent to SageMaker. For the options you can
            check https://docs.aws.amazon.com/sagemaker/latest/APIReference/API_CreateTrainingJob.html
        role_arn_to_assume: If passed, this role would get tried to be assumed in order to make the SageMaker Requests

    TODO:
        - Send the hotvect version so the image can warn against differences
    """

    def __init__(
        self,
        *,
        algorithm_pipeline: AlgorithmPipeline,
        training_job_definition: t.Dict[str, t.Any],
        role_arn_to_assume: t.Optional[str] = None,
    ) -> None:
        self.algorithm_pipeline: AlgorithmPipeline = algorithm_pipeline
        self.training_job_definition: t.Dict[str, t.Any] = copy.deepcopy(training_job_definition)

        session = get_boto_session_after_assuming_role(role_arn_to_assume) if role_arn_to_assume else boto3.Session()
        self._s3_client: S3Client = session.client("s3")
        self._sagemaker_client: SageMakerClient = session.client("sagemaker")

        if "HyperParameters" not in self.training_job_definition:
            self.training_job_definition["HyperParameters"] = {}

        self._add_s3_uri_result_file()
        self._add_s3_uri_metadata()
        self._add_s3_uri_python_log_file()
        self._add_algorithm_definition_as_hyperparameters()
        self._add_algorithm_pipeline_params_as_hyperparameters()
        self._add_algorithm_pipeline_context_as_hyperparameters()

    @property
    def hyperparameters(self) -> t.Dict[str, str]:
        return self.training_job_definition["HyperParameters"]

    @property
    def sagemaker_output_s3_path(self) -> str:
        s3_output_path: str = self.training_job_definition["OutputDataConfig"]["S3OutputPath"]
        return f"{s3_output_path.rstrip('/')}/{self.training_job_name}"

    @property
    def training_job_name(self):
        return self.training_job_definition["TrainingJobName"]

    def _add_s3_uri_result_file(self) -> None:
        self.hyperparameters["s3_uri_result_file"] = "/".join(
            [
                self.sagemaker_output_s3_path,
                self.algorithm_pipeline.hyperparameter_slug(),
                "result.json",
            ]
        )
        logger.info(f"Result file expected in {self.hyperparameters['s3_uri_result_file']}")

    def _add_s3_uri_metadata(self) -> None:
        self.hyperparameters["s3_uri_metadata"] = "/".join(
            [
                self.sagemaker_output_s3_path,
                self.algorithm_pipeline.hyperparameter_slug(),
                "metadata",
            ]
        )
        logger.info(f"Metadata expected in {self.hyperparameters['s3_uri_metadata']}")

    def _add_s3_uri_python_log_file(self) -> None:
        self.hyperparameters["s3_uri_python_log_file"] = "/".join(
            [
                self.sagemaker_output_s3_path,
                self.algorithm_pipeline.hyperparameter_slug(),
                "hotvect_python.log",
            ]
        )
        logger.info(f"Result file expected in {self.hyperparameters['s3_uri_python_log_file']}")

    def _add_algorithm_definition_as_hyperparameters(self):
        """This method flattens the algorithm definition, so it can be sent to Sagemaker."""
        flattened_algorithm_definition = flatten_dict(
            self.algorithm_pipeline.algorithm_definition, prefix=ALGO_DEF_HYPERPARAMETER_PREFIX
        )
        self.hyperparameters.update(flattened_algorithm_definition)

    def _add_algorithm_pipeline_params_as_hyperparameters(self):
        """This method flattens the pipeline parameters, so it can be sent to Sagemaker."""
        algorithm_pipeline_params_to_send = {
            "last_training_time": self.algorithm_pipeline.last_training_time.isoformat(),
            "test_lag": self.algorithm_pipeline.test_lag.total_seconds(),
            "evaluation_func": self.algorithm_pipeline.evaluation_function.__name__,
            "parameter_version": self.algorithm_pipeline.parameter_version,
        }
        self.hyperparameters[ALGO_PIPELINE_HYPERPARAMETER_PREFIX] = json.dumps(algorithm_pipeline_params_to_send)

    def _add_algorithm_pipeline_context_as_hyperparameters(self):
        """This method flattens the algorithm context, so it can be sent to Sagemaker."""
        pipeline_context = self.algorithm_pipeline.algorithm_pipeline_context
        algorithm_pipeline_context_to_send = {
            "enable_gzip": pipeline_context.enable_gzip,
            "jvm_options": pipeline_context.jvm_options,
            "max_threads": pipeline_context.max_threads,
            "queue_length": pipeline_context.queue_length,
            "batch_size": pipeline_context.batch_size,
        }
        self.hyperparameters[ALGO_PIPELINE_CONTEXT_PREFIX] = json.dumps(algorithm_pipeline_context_to_send)

    def run(self) -> CreateTrainingJobResponseTypeDef:
        self._prepare_jar_for_sagemaker_execution()
        self._check_that_mandatory_params_are_present()
        logger.debug(f"Final training job params: {json.dumps(self.training_job_definition, indent=2)}")
        return self._sagemaker_client.create_training_job(**self.training_job_definition)

    def _prepare_jar_for_sagemaker_execution(self):
        """Uploads the jar to s3 and adds its s3 uri to the HyperParameters."""
        algorithm_jar_name: str = self.algorithm_pipeline.algorithm_jar_path().name
        s3_uri_jar = f"{self.sagemaker_output_s3_path}/{algorithm_jar_name}"

        self._upload_jar_to_s3(s3_uri_jar)
        self._add_s3_uri_algorithm_jar_to_hyperparameters(s3_uri_jar)

    def _check_that_mandatory_params_are_present(self):
        missing_parameters = list(filter(lambda x: x not in self.hyperparameters, MANDATORY_PARAMETERS))
        if missing_parameters:
            raise HotvectSagemakerError(f"The following mandatory parameters are missing: {missing_parameters}")

    def _upload_jar_to_s3(self, s3_uri_algorithm_jar: str):
        _upload_file_to_s3(
            str(self.algorithm_pipeline.algorithm_jar_path()),
            s3_uri_algorithm_jar,
            self._s3_client,
        )
        logger.info(f"Jar uploaded to {s3_uri_algorithm_jar}")

    def _add_s3_uri_algorithm_jar_to_hyperparameters(self, s3_uri_algorithm_jar: str):
        self.hyperparameters["s3_uri_algorithm_jar"] = s3_uri_algorithm_jar

    def get_hotvect_result(self) -> t.Dict:
        """Gets the file specified in the HyperParameter `s3_uri_result_file`

        Raises:
            HotvectSagemakerError: If the job didn't complete successfully or if it no execution could be found with
            this TrainingJobName.
        """
        self.wait_for_sagemaker_job_completion()
        job_description: DescribeTrainingJobResponseTypeDef = self.get_job_description()
        if job_description["TrainingJobStatus"] != "Completed":
            raise HotvectSagemakerError(job_description["FailureReason"])

        s3_uri_result_file = self.hyperparameters["s3_uri_result_file"]
        s3_uri_parsed = urlparse(s3_uri_result_file)
        s3_result_file_bucket: str = s3_uri_parsed.netloc
        s3_result_file_key: str = s3_uri_parsed.path.lstrip("/")

        with tempfile.NamedTemporaryFile() as temp_file:
            self._s3_client.download_fileobj(Bucket=s3_result_file_bucket, Key=s3_result_file_key, Fileobj=temp_file)
            temp_file.seek(0)
            result = json.load(temp_file)
        return result

    def wait_for_sagemaker_job_completion(self) -> None:
        while self.is_job_running():
            logger.info(f"Job still running. Checking again in {JOB_STATUS_POLLING_WAIT_IN_SECS} seconds.")
            time.sleep(JOB_STATUS_POLLING_WAIT_IN_SECS)

    def is_job_running(self):
        job_description = self.get_job_description()
        logger.debug(f"{self.training_job_name} is {job_description['TrainingJobStatus']}")
        return job_description["TrainingJobStatus"] == "InProgress"

    def get_job_description(self) -> DescribeTrainingJobResponseTypeDef:
        try:
            return self._sagemaker_client.describe_training_job(TrainingJobName=self.training_job_name)
        except ClientError:
            raise HotvectSagemakerError(
                f"Couldn't retrieve the job {self.training_job_name}. Are you sure the job was created/run?"
            )

    def get_results_as_iteration_results_params(self) -> t.Dict[str, t.Any]:
        """Returns a dictionary with parameters needed to create a BacktestIterationResult.

        Ideally this method would return the BacktestIterationResult object itself, but importing
        that class here would cause a circular dependency since this module is imported in the
        backtest module. The BacktestIterationResult could be moved to another module and be
        consumed by both this and the backtest module from there, but that would force (if being)
        strict a new major version... so leaving it like this for now.
        """
        try:
            result = self.get_hotvect_result()
            error = None
        except HotvectSagemakerError as e:
            result = None
            error = str(e)
        return dict(
            parameter_version=self.algorithm_pipeline.parameter_version,
            training_data_time=self.algorithm_pipeline.last_training_time.isoformat(),
            test_data_time=(self.algorithm_pipeline.last_training_time + self.algorithm_pipeline.test_lag).isoformat(),
            result=result,
            error=error,
        )


def wait_for_sagemaker_executors_to_finish(jobs_to_check: t.List[SagemakerTrainingExecutor]):
    while pending_jobs := [x.training_job_name for x in jobs_to_check if x.is_job_running()]:
        logger.info(f"Waiting for the following jobs to finish: {pending_jobs}")
        logger.info(f"Checking again in {JOB_STATUS_POLLING_WAIT_IN_SECS} seconds")
        time.sleep(JOB_STATUS_POLLING_WAIT_IN_SECS)


class SagemakerAlgorithmPipelineRebuilder:
    """Rebuilds the AlgorithmPipeline based on the Sagemaker training environment.

    It works by taking values sent in the HyperParameters and using them to reconstruct the
    AlgorithmPipeline from which the execution was launched. This module will use the JAR defined in
    `s3_uri_algorithm_jar` to run the reconstructed AlgorithmPipeline.

    After the execution is finished, the result file will be written to `s3_uri_result_file` so the local
    Hotvect that triggered the job can retrieve and analyze the results asynchronously.
    """

    def __init__(self):
        self.sagemaker_env = Environment()
        self._s3_client: S3Client = boto3.client("s3")

        self.algorithm_pipeline: t.Optional[AlgorithmPipeline] = None

    def rebuild_pipeline_and_run(self) -> None:
        logging.getLogger().setLevel(self.sagemaker_env.log_level)
        try:
            self._check_mandatory_parameters()
            self.algorithm_pipeline = self.rebuild_algorithm_pipeline()
            result = self.algorithm_pipeline.run_all(clean=False)
            _upload_directory_to_s3(
                self.algorithm_pipeline.metadata_path(),
                self.sagemaker_env.hyperparameters["s3_uri_metadata"],
                self._s3_client,
            )
            self._save_result_file_to_s3()
            logger.info(f"Evaluation: {result['evaluate']}")
        except (Exception, SystemExit):
            _upload_directory_to_s3(
                self.algorithm_pipeline.metadata_path(),
                self.sagemaker_env.hyperparameters["s3_uri_metadata"],
                self._s3_client,
            )
            traceback_ = traceback.format_exc()
            self._write_failure_reason_and_exit(traceback_)

    def _check_mandatory_parameters(self):
        missing_parameters = list(filter(lambda x: x not in self.sagemaker_env.hyperparameters, MANDATORY_PARAMETERS))
        if missing_parameters:
            raise KeyError(f"The following mandatory parameters are missing: {missing_parameters}")

    def rebuild_algorithm_pipeline(self) -> AlgorithmPipeline:
        s3_uri_algorithm_jar = self.sagemaker_env.hyperparameters["s3_uri_algorithm_jar"]
        algorithm_jar_local_path = s3_uri_algorithm_jar.split("/")[-1]

        self._download_algorithm_jar(algorithm_jar_local_path, s3_uri_algorithm_jar)

        algorithm_definition = self._get_algorithm_definition(algorithm_jar_local_path)
        algorithm_pipeline_context = self._rebuild_algorithm_pipeline_context(algorithm_jar_local_path)

        algorithm_pipeline_params = self.sagemaker_env.hyperparameters.get(ALGO_PIPELINE_HYPERPARAMETER_PREFIX, {})
        logger.info("Algorithm pipeline params: " + json.dumps(algorithm_pipeline_params))
        algorithm_pipeline = AlgorithmPipeline(
            algorithm_pipeline_context=algorithm_pipeline_context,
            algorithm_definition=(
                algorithm_definition["algorithm_name"],
                algorithm_definition,
            ),
            last_training_time=datetime.date.fromisoformat(algorithm_pipeline_params["last_training_time"]),
            test_lag=datetime.timedelta(seconds=algorithm_pipeline_params["test_lag"]),
            # TODO: support other evaluation functions.
            evaluation_func=mlutils.standard_evaluation,
            hyperparameter_version=None,
            parameter_version=algorithm_pipeline_params["parameter_version"],
        )
        return algorithm_pipeline

    def _get_algorithm_definition(self, algorithm_jar_local_path):
        algorithm_definition = unflatten_dict(self.sagemaker_env.hyperparameters, ALGO_DEF_HYPERPARAMETER_PREFIX)
        algorithm_definition_in_jar = hotvect_utils.read_algorithm_definition_from_jar(
            algorithm_name=algorithm_definition["algorithm_name"],
            algorithm_jar_path=Path(algorithm_jar_local_path),
            additional_jars=list(),
        )
        logger.info("Algorithm Definition passed in HyperParameters: " + json.dumps(algorithm_definition))
        logger.info("Algorithm Definition in jar: " + json.dumps(algorithm_definition_in_jar))
        if len(algorithm_definition) == 2:
            # This is true when only the mandatory algo params are found (algorithm_name and algorithm_version)
            logger.info("No algorithm definition was found in the HyperParameters. Using the one in the jar.")
            algorithm_definition = algorithm_definition_in_jar
        return algorithm_definition

    def _download_algorithm_jar(self, algorithm_jar, s3_uri_algorithm_jar):
        s3_uri_algorithm_jar_parsed = urlparse(s3_uri_algorithm_jar)
        s3_algorithm_jar_bucket: str = s3_uri_algorithm_jar_parsed.netloc
        s3_algorithm_jar_key: str = s3_uri_algorithm_jar_parsed.path.lstrip("/")
        self._s3_client.download_file(Bucket=s3_algorithm_jar_bucket, Key=s3_algorithm_jar_key, Filename=algorithm_jar)

    def _rebuild_algorithm_pipeline_context(self, algorithm_jar_path) -> AlgorithmPipelineContext:
        metadata_dir = self._get_metadata_dir()
        output_dir = self._get_output_dir()

        metadata_base_path = Path(metadata_dir).joinpath("meta")
        state_resource_base_path = Path(metadata_dir).joinpath("state_resources")
        output_data_base_path = Path(output_dir)
        metadata_base_path.mkdir(parents=True, exist_ok=True)
        state_resource_base_path.mkdir(parents=True, exist_ok=True)
        output_data_base_path.mkdir(parents=True, exist_ok=True)

        context_in_hyperparameters = self.sagemaker_env.hyperparameters.get(ALGO_PIPELINE_CONTEXT_PREFIX, {})
        logger.info(f"Algorithm context got from Hyperparameters: {json.dumps(context_in_hyperparameters, indent=2)}")
        algorithm_pipeline_context = AlgorithmPipelineContext(
            algorithm_jar_path=Path(algorithm_jar_path),
            state_soruce_base_path=state_resource_base_path,
            data_base_path=Path(self.sagemaker_env.input_dir).joinpath("data"),
            metadata_base_path=metadata_base_path,
            output_base_path=output_data_base_path,
            enable_gzip=context_in_hyperparameters.get("enable_gzip", False),
            jvm_options=context_in_hyperparameters.get("jvm_options", ["-Xmx32g"]),
            max_threads=context_in_hyperparameters.get("max_threads"),
            queue_length=context_in_hyperparameters.get("queue_length"),
            batch_size=context_in_hyperparameters.get("batch_size"),
            additional_jar_files=list(),
        )
        return algorithm_pipeline_context

    def _get_metadata_dir(self) -> str:
        if _get_bool_from_env_variable(WRITE_METADATA_ENV_VARIABLE, True):
            return self.sagemaker_env.output_data_dir
        logger.info(f"{WRITE_METADATA_ENV_VARIABLE} env variable was set to a falsy value. Not writing metadata to s3")
        return "/tmp/"

    def _get_output_dir(self) -> str:
        if _get_bool_from_env_variable(WRITE_OUTPUT_ENV_VARIABLE, True):
            return self.sagemaker_env.output_data_dir
        logger.info(f"{WRITE_OUTPUT_ENV_VARIABLE} env variable was set to a falsy value. Not writing output to s3")
        return "/tmp/output"

    def _save_result_file_to_s3(self):
        _upload_file_to_s3(
            str(Path(self.algorithm_pipeline.metadata_path()).joinpath("result.json")),
            self.sagemaker_env.hyperparameters["s3_uri_result_file"],
            self._s3_client,
        )
        logger.info(f"result.json saved to {self.sagemaker_env.hyperparameters['s3_uri_result_file']}")

    def _write_failure_reason_and_exit(self, traceback_: str):
        """Write traceback message to the SageMaker defined output file.

        In case of failure sagemaker will display the text written to the file as FailureReason.
        Ref: https://docs.aws.amazon.com/sagemaker/latest/dg/your-algorithms-training-algo-output.html

        This message could be anything but as the argument suggests, a very useful thing is to include
        the error traceback there.
        """
        failure_file_path = Path(self.sagemaker_env.output_dir).joinpath("failure")
        with open(failure_file_path, "w") as f:
            logger.error(traceback_)
            logger.info(f"Writing error reason to {failure_file_path}")
            f.write(traceback_)

        if failure_file_path.exists():
            _upload_file_to_s3(
                str(failure_file_path), self.sagemaker_env.hyperparameters["s3_uri_python_log_file"], self._s3_client
            )
        else:
            logger.error(f"Failed to create the failure file at {failure_file_path}")

        exit(1)


def _upload_file_to_s3(local_file_path: str, s3_target_uri: str, s3_client: S3Client):
    s3_uri_parsed = urlparse(s3_target_uri)
    s3_target_bucket: str = s3_uri_parsed.netloc
    s3_target_key: str = s3_uri_parsed.path.lstrip("/")

    try:
        s3_client.upload_file(Filename=local_file_path, Bucket=s3_target_bucket, Key=s3_target_key)
        logger.info(f"Successfully uploaded {local_file_path} to {s3_target_bucket}/{s3_target_key}")
    except Exception as e:
        logger.error(f"Failed to upload {local_file_path} to S3: {e}")


def _upload_directory_to_s3(local_dir_path: str, s3_target_uri: str, s3_client: S3Client):
    basedir_name = os.path.basename(os.path.normpath(local_dir_path))
    for root, dirs, files in os.walk(local_dir_path):
        for file in files:
            file_path = os.path.join(root, file)
            relative_path = os.path.relpath(file_path, local_dir_path)
            s3_uri = os.path.join(s3_target_uri, basedir_name, relative_path).replace("\\", "/")
            _upload_file_to_s3(file_path, s3_uri, s3_client)


def _get_bool_from_env_variable(env_variable: str, default: bool):
    if env_variable not in os.environ:
        return default
    value = os.getenv(env_variable)
    if not value or value.lower() in ["0", "false"]:
        return False
    return True
