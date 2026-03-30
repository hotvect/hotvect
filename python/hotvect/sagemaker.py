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
from botocore.exceptions import ClientError, NoRegionError
from mypy_boto3_s3 import S3Client
from mypy_boto3_sagemaker import SageMakerClient
from mypy_boto3_sagemaker.type_defs import CreateTrainingJobResponseTypeDef, DescribeTrainingJobResponseTypeDef

import hotvect.utils as hotvect_utils
from hotvect import mlutils
from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext
from hotvect.utils import get_boto_session_after_assuming_role

SAGEMAKER_TAR_INCLUDE_METADATA_ENV_VARIABLE = "SAGEMAKER_TAR_INCLUDE_METADATA"
SAGEMAKER_TAR_INCLUDE_OUTPUT_ENV_VARIABLE = "SAGEMAKER_TAR_INCLUDE_OUTPUT"
LEGACY_WRITE_METADATA_ENV_VARIABLE = "WRITE_METADATA_TO_S3"
LEGACY_WRITE_OUTPUT_ENV_VARIABLE = "WRITE_OUTPUT_TO_S3"

# Backward compatibility: older entrypoints and internal wheels imported these names.
WRITE_METADATA_ENV_VARIABLE = SAGEMAKER_TAR_INCLUDE_METADATA_ENV_VARIABLE
WRITE_OUTPUT_ENV_VARIABLE = SAGEMAKER_TAR_INCLUDE_OUTPUT_ENV_VARIABLE

EFFECTIVE_TRAINING_JOB_DEFINITION_BASENAME = "effective_training_job_definition.json"
PREDICT_PARAMETERS_ZIP_HYPERPARAMETER = "s3_uri_predict_parameters_zip"

JOB_STATUS_POLLING_WAIT_IN_SECS = 60
"""Time to wait between checks for the status of a SageMaker training job"""
ALGO_DEF_HYPERPARAMETER_PREFIX: str = "_algo_def_"
ALGO_DEF_S3_URI_HYPERPARAMETER: str = "s3_uri_algorithm_definition"
# Backward compatibility: some internal wheels referenced the old constant name.
ALGORITHM_DEFINITION_S3_URI_HYPERPARAMETER: str = ALGO_DEF_S3_URI_HYPERPARAMETER
"""This is the prefix that the algorithm definition keys will have in the hyperparameter dictionary."""
ALGO_PIPELINE_HYPERPARAMETER_PREFIX: str = "_pipeline_params_"
"""This is the prefix that the algorithm pipeline params keys will have in the hyperparameter dictionary."""
ALGO_PIPELINE_CONTEXT_PREFIX: str = "_pipeline_context_"
"""This is the prefix that the algorithm pipeline context values will have in the hyperparameter dictionary."""
MANDATORY_PARAMETERS = [
    "s3_uri_result_file",
    "s3_uri_algorithm_jar",
]
HOTVECT_JAVA_LOG_PATH = "/var/log/hotvect.log"
"""List of hyperparameters that HAVE to be set in order to successfully rebuild the AlgorithmPipeline.
Algorithm definitions must be provided via s3_uri_algorithm_definition or _algo_def_* hyperparameters."""

logger = logging.getLogger(__name__)


@dataclass
class _ParsedKey:
    key: str
    sub_key: str
    key_type: str


def _parse_key(key: str, object_separator_char: str = ".") -> _ParsedKey:
    if not key:
        raise ValueError(f"{key} should have a value")
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
        - The algorithm definition should be loaded from `s3_uri_algorithm_definition` (fallback: `_algo_def_*`)
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
        try:
            self._s3_client: S3Client = session.client("s3")
            self._sagemaker_client: SageMakerClient = session.client("sagemaker")
        except NoRegionError as e:
            raise ValueError(
                "AWS region is not configured. Set `AWS_DEFAULT_REGION` (or `AWS_REGION`), "
                "or configure a default region in your AWS profile (e.g. in `~/.aws/config`)."
            ) from e

        if "HyperParameters" not in self.training_job_definition:
            self.training_job_definition["HyperParameters"] = {}

        self._add_s3_uri_result_file()
        self._add_s3_uri_metadata()
        self._add_s3_uri_python_log_file()
        self._add_s3_uri_predict_parameters_zip()
        self._add_algorithm_pipeline_params_as_hyperparameters()
        self._add_algorithm_pipeline_context_as_hyperparameters()
        self._fail_if_algorithm_overrides_would_be_ignored()

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

    def _add_s3_uri_predict_parameters_zip(self) -> None:
        try:
            local_name = Path(self.algorithm_pipeline.predict_parameter_file_path()).name
        except Exception:
            # Some pipeline modes may not produce a parameters zip; don't force it.
            return
        self.hyperparameters[PREDICT_PARAMETERS_ZIP_HYPERPARAMETER] = "/".join(
            [
                self.sagemaker_output_s3_path,
                self.algorithm_pipeline.hyperparameter_slug(),
                local_name,
            ]
        )

    def _add_algorithm_pipeline_params_as_hyperparameters(self):
        """This method flattens the pipeline parameters, so it can be sent to Sagemaker."""
        algorithm_pipeline_params_to_send = {
            "last_test_time": self.algorithm_pipeline.last_test_time.isoformat(),
            "parameter_version": self.algorithm_pipeline.parameter_version,
            "execute_performance_test": getattr(self.algorithm_pipeline, "execute_performance_test", True),
            "encode_test_data": getattr(self.algorithm_pipeline, "encode_test_data", False),
            "execute_audit": getattr(self.algorithm_pipeline, "execute_audit", False),
        }
        self.hyperparameters[ALGO_PIPELINE_HYPERPARAMETER_PREFIX] = json.dumps(algorithm_pipeline_params_to_send)

    def _add_algorithm_pipeline_context_as_hyperparameters(self):
        """This method flattens the algorithm context, so it can be sent to Sagemaker."""
        pipeline_context = self.algorithm_pipeline.algorithm_pipeline_context
        algorithm_pipeline_context_to_send = {
            "jvm_options": pipeline_context.jvm_options,
            "max_threads": pipeline_context.max_threads,
            "queue_length": pipeline_context.queue_length,
            "batch_size": pipeline_context.batch_size,
        }
        self.hyperparameters[ALGO_PIPELINE_CONTEXT_PREFIX] = json.dumps(algorithm_pipeline_context_to_send)

    def _fail_if_algorithm_overrides_would_be_ignored(self) -> None:
        """Fail fast when an effective algo override would be ignored by legacy images.

        Older training images may ignore `s3_uri_algorithm_definition` and rebuild the pipeline using the algorithm
        definition embedded in the algorithm JAR. In that case, `--algorithm-override` would silently not take effect.
        """
        hyperparameters = self.training_job_definition.get("HyperParameters")
        if isinstance(hyperparameters, dict) and hyperparameters.get("s3_uri_custom_jar"):
            # Script-mode runs a caller-provided payload (custom.py), which can install a newer Hotvect runtime and
            # honor the effective algorithm definition written to S3, even if the base container image is v9.x.
            return

        def _parse_semver_major_from_training_image(training_image: str) -> t.Optional[int]:
            # Accept: "...:9.34.0", "...:9.34.0-suffix", "...:9.34.0_suffix"
            m = re.search(r":(\d+)\.(\d+)\.(\d+)(?:[._-].*)?$", training_image)
            return int(m.group(1)) if m else None

        algo_spec = self.training_job_definition.get("AlgorithmSpecification")
        training_image = algo_spec.get("TrainingImage") if isinstance(algo_spec, dict) else None
        if not isinstance(training_image, str) or not training_image:
            return

        major = _parse_semver_major_from_training_image(training_image)

        try:
            algorithm_definition_in_jar = hotvect_utils.read_algorithm_definition_from_jar(
                algorithm_name=self.algorithm_pipeline.algorithm_name,
                algorithm_jar_path=Path(self.algorithm_pipeline.algorithm_jar_path()),
                additional_jars=list(),
            )
        except Exception:
            return

        effective_definition = self.algorithm_pipeline.algorithm_definition
        if effective_definition == algorithm_definition_in_jar:
            return

        if major is None:
            logger.warning(
                "Training image tag is not a recognized semver; cannot verify whether it honors %s. "
                "If this image ignores it, --algorithm-override will not take effect. TrainingImage=%r",
                ALGO_DEF_S3_URI_HYPERPARAMETER,
                training_image,
            )
            return

        if major < 10:
            raise ValueError(
                "Refusing to submit SageMaker job: the effective algorithm definition differs from the one embedded "
                "in the algorithm jar (i.e. an algorithm override is in effect), but the selected TrainingImage looks "
                f"like a legacy hotvect image (v{major}.x), which may ignore `{ALGO_DEF_S3_URI_HYPERPARAMETER}` "
                "and rebuild from the jar instead. Use a v10+ training image or script-mode (`s3_uri_custom_jar`) "
                "to run with overrides."
            )

    def run(self) -> CreateTrainingJobResponseTypeDef:
        self._prepare_algorithm_definition_for_sagemaker_execution()
        self._prepare_jar_for_sagemaker_execution()
        self._check_that_mandatory_params_are_present()
        self._upload_effective_training_job_definition()
        logger.debug(f"Final training job params: {json.dumps(self.training_job_definition, indent=2)}")
        return self._sagemaker_client.create_training_job(**self.training_job_definition)

    def _prepare_algorithm_definition_for_sagemaker_execution(self):
        algorithm_definition_name = f"{self.algorithm_pipeline.hyperparameter_slug()}-algorithm_definition.json"
        s3_uri_algorithm_definition = f"{self.sagemaker_output_s3_path}/{algorithm_definition_name}"
        self._upload_algorithm_definition_to_s3(s3_uri_algorithm_definition)
        self._add_s3_uri_algorithm_definition_to_hyperparameters(s3_uri_algorithm_definition)

    def _upload_effective_training_job_definition(self) -> None:
        s3_uri_metadata = self.hyperparameters.get("s3_uri_metadata")
        if not s3_uri_metadata:
            return
        with tempfile.NamedTemporaryFile("w", delete=False) as fp:
            json.dump(self.training_job_definition, fp, indent=2, sort_keys=True)
            fp.flush()
            local_path = fp.name
        try:
            target = "/".join([s3_uri_metadata.rstrip("/"), EFFECTIVE_TRAINING_JOB_DEFINITION_BASENAME])
            _upload_file_to_s3(local_path, target, self._s3_client)
        finally:
            try:
                os.unlink(local_path)
            except Exception:
                pass

    def _prepare_jar_for_sagemaker_execution(self):
        """Uploads the jar to s3 and adds its s3 uri to the HyperParameters."""
        algorithm_jar_name: str = self.algorithm_pipeline.algorithm_jar_path().name
        s3_uri_jar = f"{self.sagemaker_output_s3_path}/{algorithm_jar_name}"

        self._upload_jar_to_s3(s3_uri_jar)
        self._add_s3_uri_algorithm_jar_to_hyperparameters(s3_uri_jar)

    def _check_that_mandatory_params_are_present(self):
        missing_parameters = list(filter(lambda x: x not in self.hyperparameters, MANDATORY_PARAMETERS))
        if not _has_algorithm_definition(self.hyperparameters):
            missing_parameters.append(ALGO_DEF_S3_URI_HYPERPARAMETER)
        if missing_parameters:
            raise HotvectSagemakerError(f"The following mandatory parameters are missing: {missing_parameters}")

    def _upload_jar_to_s3(self, s3_uri_algorithm_jar: str):
        _upload_file_to_s3(
            str(self.algorithm_pipeline.algorithm_jar_path()),
            s3_uri_algorithm_jar,
            self._s3_client,
            fail_fast=True,
        )
        logger.info(f"Jar uploaded to {s3_uri_algorithm_jar}")

    def _add_s3_uri_algorithm_jar_to_hyperparameters(self, s3_uri_algorithm_jar: str):
        self.hyperparameters["s3_uri_algorithm_jar"] = s3_uri_algorithm_jar

    def _upload_algorithm_definition_to_s3(self, s3_uri_algorithm_definition: str):
        temp_path = None
        try:
            with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as temp_file:
                json.dump(self.algorithm_pipeline.algorithm_definition, temp_file, default=str)
                temp_path = temp_file.name
            _upload_file_to_s3(
                temp_path,
                s3_uri_algorithm_definition,
                self._s3_client,
                fail_fast=True,
            )
        finally:
            if temp_path and os.path.exists(temp_path):
                os.remove(temp_path)
        logger.info(f"Algorithm definition uploaded to {s3_uri_algorithm_definition}")

    def _add_s3_uri_algorithm_definition_to_hyperparameters(self, s3_uri_algorithm_definition: str):
        self.hyperparameters[ALGO_DEF_S3_URI_HYPERPARAMETER] = s3_uri_algorithm_definition

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
            test_data_time=self.algorithm_pipeline.last_test_time.isoformat(),
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
    `s3_uri_algorithm_jar` to run the reconstructed AlgorithmPipeline and prefers
    `s3_uri_algorithm_definition` for the algorithm definition.

    After the execution is finished, the result file will be written to `s3_uri_result_file` so the local
    Hotvect that triggered the job can retrieve and analyze the results asynchronously.
    """

    def __init__(self):
        from sagemaker_training.environment import Environment

        self.sagemaker_env: "Environment" = Environment()
        self._s3_client: S3Client = boto3.client("s3")

        self.algorithm_pipeline: t.Optional[AlgorithmPipeline] = None

    def rebuild_pipeline_and_run(self) -> None:
        logging.getLogger().setLevel(self.sagemaker_env.log_level)
        try:
            self._check_mandatory_parameters()
            self.algorithm_pipeline = self.rebuild_algorithm_pipeline()
            result = self.algorithm_pipeline.run_all(clean=False)
            self._upload_metadata_to_s3()
            self._maybe_upload_predict_parameters_zip()
            self._save_result_file_to_s3()
            logger.info(f"Evaluation: {result['evaluate']}")
        except (Exception, SystemExit):
            self._upload_metadata_to_s3()
            traceback_ = traceback.format_exc()
            self._write_failure_reason_and_exit(traceback_)

    def _upload_metadata_to_s3(self) -> None:
        """
        Upload metadata for the entire pipeline run.

        Dependency pipelines can store their metadata outside the top-level pipeline's metadata directory.
        Upload dependency metadata as well so failures are debuggable from S3 without relying solely on
        CloudWatch logs.
        """
        if not self.algorithm_pipeline:
            return

        s3_uri_metadata = self.sagemaker_env.hyperparameters["s3_uri_metadata"].rstrip("/")

        try:
            _upload_directory_to_s3(self.algorithm_pipeline.metadata_path(), s3_uri_metadata, self._s3_client)
        except Exception:
            logger.exception("Failed to upload top-level metadata to %s", s3_uri_metadata)

        try:
            for dep in _collect_dependency_pipelines(self.algorithm_pipeline):
                dep_target = f"{s3_uri_metadata}/deps/{dep.hyperparameter_slug()}"
                _upload_directory_to_s3(dep.metadata_path(), dep_target, self._s3_client)
        except Exception:
            logger.exception("Failed to upload dependency metadata to %s", s3_uri_metadata)

    def _check_mandatory_parameters(self):
        missing_parameters = list(filter(lambda x: x not in self.sagemaker_env.hyperparameters, MANDATORY_PARAMETERS))
        if not _has_algorithm_definition(self.sagemaker_env.hyperparameters):
            missing_parameters.append(ALGO_DEF_S3_URI_HYPERPARAMETER)
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
        execute_performance_test = algorithm_pipeline_params.get("execute_performance_test", True)
        algorithm_pipeline = AlgorithmPipeline(
            algorithm_pipeline_context=algorithm_pipeline_context,
            algorithm_definition=(
                algorithm_definition["algorithm_name"],
                algorithm_definition,
            ),
            last_test_time=datetime.date.fromisoformat(algorithm_pipeline_params["last_test_time"]),
            # TODO: support other evaluation functions.
            evaluation_func=mlutils.standard_evaluation,
            hyperparameter_version=None,
            parameter_version=algorithm_pipeline_params["parameter_version"],
            execute_performance_test=execute_performance_test,
            encode_test_data=algorithm_pipeline_params.get("encode_test_data", False),
            execute_audit=algorithm_pipeline_params.get("execute_audit", False),
        )
        return algorithm_pipeline

    def _get_algorithm_definition(self, algorithm_jar_local_path):
        s3_uri_algorithm_definition = self.sagemaker_env.hyperparameters.get(ALGO_DEF_S3_URI_HYPERPARAMETER)
        if s3_uri_algorithm_definition:
            algorithm_definition = self._download_algorithm_definition(s3_uri_algorithm_definition)
            logger.info(
                "Algorithm Definition loaded from s3_uri_algorithm_definition: " + json.dumps(algorithm_definition)
            )
            return algorithm_definition

        algorithm_definition = unflatten_dict(self.sagemaker_env.hyperparameters, ALGO_DEF_HYPERPARAMETER_PREFIX)
        if not algorithm_definition:
            raise KeyError(
                "Missing algorithm definition. Provide s3_uri_algorithm_definition or _algo_def_* hyperparameters."
            )
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

    def _download_algorithm_definition(self, s3_uri_algorithm_definition: str) -> t.Dict[str, t.Any]:
        s3_uri_algorithm_definition_parsed = urlparse(s3_uri_algorithm_definition)
        s3_algorithm_definition_bucket: str = s3_uri_algorithm_definition_parsed.netloc
        s3_algorithm_definition_key: str = s3_uri_algorithm_definition_parsed.path.lstrip("/")
        with tempfile.NamedTemporaryFile() as temp_file:
            self._s3_client.download_fileobj(
                Bucket=s3_algorithm_definition_bucket,
                Key=s3_algorithm_definition_key,
                Fileobj=temp_file,
            )
            temp_file.seek(0)
            return json.load(temp_file)

    def _rebuild_algorithm_pipeline_context(self, algorithm_jar_path) -> AlgorithmPipelineContext:
        metadata_dir = self._get_metadata_dir()
        output_dir = self._get_output_dir()

        data_base_path = Path(self.sagemaker_env.input_dir).joinpath("data")

        metadata_base_path = Path(metadata_dir).joinpath("meta")
        output_data_base_path = Path(output_dir)
        metadata_base_path.mkdir(parents=True, exist_ok=True)
        output_data_base_path.mkdir(parents=True, exist_ok=True)

        context_in_hyperparameters = self.sagemaker_env.hyperparameters.get(ALGO_PIPELINE_CONTEXT_PREFIX, {})
        logger.info(f"Algorithm context got from Hyperparameters: {json.dumps(context_in_hyperparameters, indent=2)}")
        algorithm_pipeline_context = AlgorithmPipelineContext(
            algorithm_jar_path=Path(algorithm_jar_path),
            # State source inputs should be resolved from SageMaker input channels.
            # This matches how train/test data is mounted under /opt/ml/input/data/{ChannelName}.
            state_source_base_path=data_base_path,
            data_base_path=data_base_path,
            metadata_base_path=metadata_base_path,
            output_base_path=output_data_base_path,
            jvm_options=context_in_hyperparameters.get("jvm_options", ["-XX:MaxRAMPercentage=80"]),
            max_threads=context_in_hyperparameters.get("max_threads"),
            queue_length=context_in_hyperparameters.get("queue_length"),
            batch_size=context_in_hyperparameters.get("batch_size"),
            additional_jar_files=list(),
        )
        return algorithm_pipeline_context

    def _get_metadata_dir(self) -> str:
        if _get_bool_from_env_variable_with_fallback(
            SAGEMAKER_TAR_INCLUDE_METADATA_ENV_VARIABLE,
            LEGACY_WRITE_METADATA_ENV_VARIABLE,
            False,
        ):
            return self.sagemaker_env.output_data_dir
        logger.info(
            f"{SAGEMAKER_TAR_INCLUDE_METADATA_ENV_VARIABLE} env variable was set to a falsy value. "
            "Metadata will not be included in output.tar.gz."
        )
        return "/tmp/"

    def _get_output_dir(self) -> str:
        if _get_bool_from_env_variable_with_fallback(
            SAGEMAKER_TAR_INCLUDE_OUTPUT_ENV_VARIABLE,
            LEGACY_WRITE_OUTPUT_ENV_VARIABLE,
            False,
        ):
            return self.sagemaker_env.output_data_dir
        logger.info(
            f"{SAGEMAKER_TAR_INCLUDE_OUTPUT_ENV_VARIABLE} env variable was set to a falsy value. "
            "Output artifacts will not be included in output.tar.gz."
        )
        return "/tmp/output"

    def _save_result_file_to_s3(self):
        _upload_file_to_s3(
            str(Path(self.algorithm_pipeline.metadata_path()).joinpath("result.json")),
            self.sagemaker_env.hyperparameters["s3_uri_result_file"],
            self._s3_client,
            fail_fast=True,
        )
        logger.info(f"result.json saved to {self.sagemaker_env.hyperparameters['s3_uri_result_file']}")

    def _maybe_upload_predict_parameters_zip(self) -> None:
        s3_uri = self.sagemaker_env.hyperparameters.get(PREDICT_PARAMETERS_ZIP_HYPERPARAMETER)
        if not s3_uri:
            return
        try:
            local_zip = str(self.algorithm_pipeline.predict_parameter_file_path())
        except Exception:
            return
        if not os.path.exists(local_zip):
            logger.warning("Predict parameters zip not found at %s; skipping upload to %s", local_zip, s3_uri)
            return
        _upload_file_to_s3(local_zip, s3_uri, self._s3_client)

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
                str(failure_file_path),
                self.sagemaker_env.hyperparameters["s3_uri_python_log_file"],
                self._s3_client,
                fail_fast=False,
            )
        else:
            logger.error(f"Failed to create the failure file at {failure_file_path}")

        exit(1)


def _upload_file_to_s3(
    local_file_path: str,
    s3_target_uri: str,
    s3_client: S3Client,
    *,
    fail_fast: bool = False,
) -> None:
    s3_uri_parsed = urlparse(s3_target_uri)
    s3_target_bucket: str = s3_uri_parsed.netloc
    s3_target_key: str = s3_uri_parsed.path.lstrip("/")

    try:
        s3_client.upload_file(Filename=local_file_path, Bucket=s3_target_bucket, Key=s3_target_key)
        logger.info(f"Successfully uploaded {local_file_path} to {s3_target_bucket}/{s3_target_key}")
    except Exception:
        logger.exception(f"Failed to upload {local_file_path} to {s3_target_bucket}/{s3_target_key}")
        if fail_fast:
            raise


def _upload_directory_to_s3(local_dir_path: str, s3_target_uri: str, s3_client: S3Client):
    basedir_name = os.path.basename(os.path.normpath(local_dir_path))
    for root, dirs, files in os.walk(local_dir_path):
        for file in files:
            file_path = os.path.join(root, file)
            relative_path = os.path.relpath(file_path, local_dir_path)
            s3_uri = os.path.join(s3_target_uri, basedir_name, relative_path).replace("\\", "/")
            _upload_file_to_s3(file_path, s3_uri, s3_client)


def _collect_dependency_pipelines(pipeline: "AlgorithmPipeline") -> t.List["AlgorithmPipeline"]:
    ret: t.List["AlgorithmPipeline"] = []
    visited: t.Set[int] = set()

    def _walk(node: "AlgorithmPipeline") -> None:
        node_id = id(node)
        if node_id in visited:
            return
        visited.add(node_id)
        for child in (node.dependency_pipelines or {}).values():
            ret.append(child)
            _walk(child)

    _walk(pipeline)
    return ret


def _get_bool_from_env_variable(env_variable: str, default: bool):
    if env_variable not in os.environ:
        return default
    value = os.getenv(env_variable)
    if not value or value.lower() in ["0", "false"]:
        return False
    return True


def _get_bool_from_env_variable_with_fallback(env_variable: str, legacy_env_variable: str, default: bool) -> bool:
    if env_variable in os.environ:
        return _get_bool_from_env_variable(env_variable, default)
    if legacy_env_variable in os.environ:
        logger.warning(
            "Using legacy env var %s; please migrate to %s.",
            legacy_env_variable,
            env_variable,
        )
        return _get_bool_from_env_variable(legacy_env_variable, default)
    return default


def _has_algorithm_definition(hyperparameters: t.Dict[str, str]) -> bool:
    if ALGO_DEF_S3_URI_HYPERPARAMETER in hyperparameters:
        return True
    return any(key.startswith(ALGO_DEF_HYPERPARAMETER_PREFIX) for key in hyperparameters)
