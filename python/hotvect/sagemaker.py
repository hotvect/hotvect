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

from hotvect.algorithm_definition_overrides import (
    parse_effective_algorithm_definition_payload,
    serialize_effective_algorithm_definition,
)
from hotvect.benchmark_contract import build_benchmark_contract
from hotvect.evaluation import evaluation
from hotvect.jvm_args import normalize_pipeline_jvm_options
from hotvect.pyhotvect import PARTITION_CACHE_CHANNEL_NAME, AlgorithmPipeline, AlgorithmPipelineContext
from hotvect.s3_utils import download_json_from_s3, join_s3_uri, normalize_s3_prefix_uri, require_s3_uri
from hotvect.s3_utils import upload_directory_to_s3 as _shared_upload_directory_to_s3
from hotvect.s3_utils import upload_file_to_s3 as _shared_upload_file_to_s3
from hotvect.s3_utils import upload_json_to_s3 as _shared_upload_json_to_s3
from hotvect.sagemaker_config import HOTVECT_PREFERRED_INSTANCE_TYPES_KEY, HOTVECT_SUBMISSION_OPTIONS_KEY
from hotvect.sagemaker_contracts import ALGO_DEF_S3_URI_HYPERPARAMETER, PREDICT_PARAMETERS_ZIP_HYPERPARAMETER
from hotvect.utils import get_boto_session_after_assuming_role

SAGEMAKER_TAR_INCLUDE_METADATA_ENV_VARIABLE = "SAGEMAKER_TAR_INCLUDE_METADATA"
SAGEMAKER_TAR_INCLUDE_OUTPUT_ENV_VARIABLE = "SAGEMAKER_TAR_INCLUDE_OUTPUT"

# Backward compatibility: older entrypoints and internal wheels imported these names.
WRITE_METADATA_ENV_VARIABLE = SAGEMAKER_TAR_INCLUDE_METADATA_ENV_VARIABLE
WRITE_OUTPUT_ENV_VARIABLE = SAGEMAKER_TAR_INCLUDE_OUTPUT_ENV_VARIABLE

EFFECTIVE_TRAINING_JOB_DEFINITION_BASENAME = "effective_training_job_definition.json"
EFFECTIVE_ALGORITHM_DEFINITION_BASENAME = "effective_algorithm_definition.json"

JOB_STATUS_POLLING_WAIT_IN_SECS = 60
"""Time to wait between checks for the status of a SageMaker training job"""
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
PARTITION_CACHE_CHANNEL_PREFIX = f"{PARTITION_CACHE_CHANNEL_NAME}_"
SAGEMAKER_CHANNEL_NAME_MAX_LENGTH = 64
"""List of hyperparameters that HAVE to be set in order to successfully rebuild the AlgorithmPipeline.
Algorithm definitions must be provided via s3_uri_algorithm_definition."""

logger = logging.getLogger(__name__)

# Keep these module-level names stable so existing tests and internal callers can
# monkeypatch them without needing to know about the shared helper module.
_upload_file_to_s3 = _shared_upload_file_to_s3
_upload_directory_to_s3 = _shared_upload_directory_to_s3
_upload_json_to_s3 = _shared_upload_json_to_s3


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
        - The full effective algorithm definition should be loaded from `s3_uri_algorithm_definition`
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
        self._validate_cache_refresh_configuration(self.algorithm_pipeline)
        self._instance_type_fallbacks: t.List[str] = self._normalize_instance_type_preferences()
        self._partition_cache_channel_names_by_root: t.Dict[str, str] = {}

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
        self._maybe_add_partition_cache_input_channel()
        self._add_algorithm_pipeline_context_as_hyperparameters()
        self._fail_if_prediction_output_uri_is_not_s3_for_sagemaker()
        self._fail_if_algorithm_overrides_would_be_ignored()

    @property
    def hyperparameters(self) -> t.Dict[str, str]:
        return self.training_job_definition["HyperParameters"]

    def _validate_cache_refresh_configuration(self, pipeline: AlgorithmPipeline) -> None:
        pipeline._cache_refresh_enabled()
        for dependency_pipeline in pipeline.dependency_pipelines.values():
            self._validate_cache_refresh_configuration(dependency_pipeline)

    @property
    def sagemaker_output_s3_path(self) -> str:
        s3_output_path: str = self.training_job_definition["OutputDataConfig"]["S3OutputPath"]
        return f"{s3_output_path.rstrip('/')}/{self.training_job_name}"

    @property
    def training_job_name(self):
        return self.training_job_definition["TrainingJobName"]

    def build_submission_manifest(self, submission_response: CreateTrainingJobResponseTypeDef) -> t.Dict[str, t.Any]:
        prediction_output_uri = None
        try:
            if getattr(self.algorithm_pipeline, "run_target", "evaluate") == "predict":
                prediction_output_uri = self.algorithm_pipeline.prediction_output_uri()
        except Exception:
            logger.exception("Failed to resolve prediction output URI while building submission manifest")
        return {
            "training_job_name": self.training_job_name,
            "training_job_arn": submission_response.get("TrainingJobArn"),
            "submission_status": "submitted",
            "sagemaker_output_s3_path": self.sagemaker_output_s3_path,
            "parameter_version": self.algorithm_pipeline.parameter_version,
            "test_data_time": self.algorithm_pipeline.last_test_time.isoformat(),
            "hyperparameter_slug": self.algorithm_pipeline.hyperparameter_slug(),
            "run_target": getattr(self.algorithm_pipeline, "run_target", "evaluate"),
            "data_environment": getattr(self.algorithm_pipeline, "data_environment", "production"),
            "prediction_output_uri": prediction_output_uri,
            "s3_uri_result_file": self.hyperparameters.get("s3_uri_result_file"),
            "s3_uri_metadata": self.hyperparameters.get("s3_uri_metadata"),
            "s3_uri_python_log_file": self.hyperparameters.get("s3_uri_python_log_file"),
            "s3_uri_predict_parameters_zip": self.hyperparameters.get(PREDICT_PARAMETERS_ZIP_HYPERPARAMETER),
            "s3_uri_algorithm_jar": self.hyperparameters.get("s3_uri_algorithm_jar"),
            "s3_uri_algorithm_definition": self.hyperparameters.get(ALGO_DEF_S3_URI_HYPERPARAMETER),
        }

    def _normalize_instance_type_preferences(self) -> t.List[str]:
        """Normalize Hotvect-only instance preference config into a primary type plus retry list."""
        options = self.training_job_definition.pop(HOTVECT_SUBMISSION_OPTIONS_KEY, None)
        if options is None:
            return []
        if not isinstance(options, dict):
            raise ValueError(
                f"{HOTVECT_SUBMISSION_OPTIONS_KEY} must be an object with {HOTVECT_PREFERRED_INSTANCE_TYPES_KEY!r}"
            )
        unexpected_option_keys = set(options) - {HOTVECT_PREFERRED_INSTANCE_TYPES_KEY}
        if unexpected_option_keys:
            raise ValueError(
                f"{HOTVECT_SUBMISSION_OPTIONS_KEY} only supports {HOTVECT_PREFERRED_INSTANCE_TYPES_KEY!r}; "
                f"got unexpected keys: {sorted(unexpected_option_keys)!r}"
            )
        preferred_instance_types = options.get(HOTVECT_PREFERRED_INSTANCE_TYPES_KEY)

        resource_config = self.training_job_definition.setdefault("ResourceConfig", {})
        if not isinstance(resource_config, dict):
            raise ValueError("ResourceConfig must be an object/dict in SageMaker training job definitions")
        current_instance_type = resource_config.get("InstanceType")

        if preferred_instance_types is not None:
            preferred = self._normalize_instance_type_list(
                preferred_instance_types,
                key_name=HOTVECT_PREFERRED_INSTANCE_TYPES_KEY,
                require_non_empty=True,
            )
            first_preferred_instance_type = preferred[0]
            if current_instance_type is None:
                resource_config["InstanceType"] = first_preferred_instance_type
                current_instance_type = first_preferred_instance_type
            elif current_instance_type != first_preferred_instance_type:
                raise ValueError(
                    "ResourceConfig.InstanceType must match "
                    f"{HOTVECT_SUBMISSION_OPTIONS_KEY}.{HOTVECT_PREFERRED_INSTANCE_TYPES_KEY}[0] "
                    f"when both are provided. Got {current_instance_type!r} vs {first_preferred_instance_type!r}"
                )
            return preferred[1:]
        return []

    @staticmethod
    def _normalize_instance_type_list(
        values: t.Any,
        *,
        key_name: str,
        require_non_empty: bool,
        value_to_skip: t.Optional[str] = None,
    ) -> t.List[str]:
        if not isinstance(values, list):
            raise ValueError(f"{key_name} must be a list of SageMaker instance types")

        normalized: t.List[str] = []
        seen = set()
        for value in values:
            if not isinstance(value, str):
                raise ValueError(f"{key_name} must contain only SageMaker instance type strings")
            if not value:
                continue
            if value == value_to_skip or value in seen:
                continue
            seen.add(value)
            normalized.append(value)

        if require_non_empty and not normalized:
            raise ValueError(f"{key_name} must contain at least one SageMaker instance type")
        return normalized

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
            should_publish = self.algorithm_pipeline.should_publish_predict_parameters_zip()
        except Exception as exc:
            raise ValueError(
                "Failed to determine whether the algorithm pipeline should publish a predict-parameters ZIP."
            ) from exc
        if not should_publish:
            return
        try:
            local_name = Path(self.algorithm_pipeline.predict_parameter_file_path()).name
        except Exception as exc:
            raise ValueError(
                "This SageMaker run is expected to publish a predict-parameters ZIP, but "
                "AlgorithmPipeline.predict_parameter_file_path() could not be resolved."
            ) from exc
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
            "run_target": getattr(self.algorithm_pipeline, "run_target", "evaluate"),
            "data_environment": getattr(self.algorithm_pipeline, "data_environment", "production"),
            "execute_performance_test": getattr(self.algorithm_pipeline, "execute_performance_test", True),
            "encode_test_data": getattr(self.algorithm_pipeline, "encode_test_data", False),
            "execute_audit": getattr(self.algorithm_pipeline, "execute_audit", False),
        }
        ran_at = getattr(self.algorithm_pipeline, "ran_at", None)
        if ran_at is not None:
            algorithm_pipeline_params_to_send["ran_at"] = ran_at
        self.hyperparameters[ALGO_PIPELINE_HYPERPARAMETER_PREFIX] = json.dumps(algorithm_pipeline_params_to_send)

    def _add_algorithm_pipeline_context_as_hyperparameters(self):
        """This method flattens the algorithm context, so it can be sent to Sagemaker."""
        pipeline_context = self.algorithm_pipeline.algorithm_pipeline_context
        algorithm_pipeline_context_to_send = {
            "jvm_options": pipeline_context.jvm_options,
            "max_threads": pipeline_context.max_threads,
            "queue_length": pipeline_context.queue_length,
            "read_queue_length": pipeline_context.read_queue_length,
            "write_queue_length": pipeline_context.write_queue_length,
            "batch_size": pipeline_context.batch_size,
            "benchmark_contract": self._build_pipeline_benchmark_contract(),
            "partition_cache_channels": self._partition_cache_channel_names_by_root,
        }
        self.hyperparameters[ALGO_PIPELINE_CONTEXT_PREFIX] = json.dumps(algorithm_pipeline_context_to_send)

    def _maybe_add_partition_cache_input_channel(self) -> None:
        self._partition_cache_channel_names_by_root: t.Dict[str, str] = {}
        cache_roots = self._collect_partition_cache_roots(self.algorithm_pipeline)
        if not cache_roots:
            return

        input_data_config = self.training_job_definition.setdefault("InputDataConfig", [])
        if not isinstance(input_data_config, list):
            raise ValueError("InputDataConfig must be a list in SageMaker training job definitions")

        existing_partition_channels = [
            channel.get("ChannelName")
            for channel in input_data_config
            if isinstance(channel, dict) and self._is_partition_cache_channel_name(channel.get("ChannelName"))
        ]
        if existing_partition_channels:
            raise ValueError(
                f"InputDataConfig already contains Hotvect partition cache channel(s): {existing_partition_channels}"
            )

        generated_channels: t.Dict[str, str] = {}
        for cache_root, cache_key in sorted(cache_roots.items()):
            channel_name = self._partition_cache_channel_name(cache_key)
            previous_root = generated_channels.get(channel_name)
            if previous_root and previous_root != cache_root:
                raise ValueError(
                    "Multiple partition cache roots resolve to the same SageMaker input channel "
                    f"{channel_name}: {previous_root}, {cache_root}"
                )
            generated_channels[channel_name] = cache_root

            cache_s3_uri = normalize_s3_prefix_uri(join_s3_uri(cache_root, "partitions"))
            if not self._s3_prefix_has_objects(cache_s3_uri):
                logger.info("Partition cache input channel skipped because %s has no objects", cache_s3_uri)
                continue

            input_data_config.append(
                {
                    "ChannelName": channel_name,
                    "DataSource": {
                        "S3DataSource": {
                            "S3DataType": "S3Prefix",
                            "S3Uri": cache_s3_uri,
                            "S3DataDistributionType": "FullyReplicated",
                        }
                    },
                    "InputMode": "FastFile",
                }
            )
            self._partition_cache_channel_names_by_root[cache_root] = channel_name
            logger.info("Added partition cache input channel %s with %s", channel_name, cache_s3_uri)

    @staticmethod
    def _is_partition_cache_channel_name(channel_name: t.Any) -> bool:
        return isinstance(channel_name, str) and channel_name.startswith(PARTITION_CACHE_CHANNEL_PREFIX)

    @staticmethod
    def _sanitize_partition_cache_channel_segment(value: str) -> str:
        segment = re.sub(r"[^A-Za-z0-9\-_]+", "-", value)
        return re.sub(r"-+", "-", segment).strip("-_")

    @classmethod
    def _partition_cache_channel_name(cls, cache_key: str) -> str:
        suffix_max_length = SAGEMAKER_CHANNEL_NAME_MAX_LENGTH - len(PARTITION_CACHE_CHANNEL_PREFIX)
        algorithm_name, cache_version = cache_key.split("@", 1)
        version_segment = cls._sanitize_partition_cache_channel_segment(cache_version)
        version_suffix = f"_{version_segment}"
        if len(version_suffix) >= suffix_max_length:
            suffix = cls._sanitize_partition_cache_channel_segment(cache_key.replace("@", "_"))[:suffix_max_length]
        else:
            algorithm_segment = cls._sanitize_partition_cache_channel_segment(algorithm_name)
            suffix = f"{algorithm_segment[: suffix_max_length - len(version_suffix)]}{version_suffix}"

        return f"{PARTITION_CACHE_CHANNEL_PREFIX}{suffix}"

    def _collect_partition_cache_roots(self, pipeline: AlgorithmPipeline) -> t.Dict[str, str]:
        cache_roots = {}
        if pipeline._uses_encode_partition_cache() and not pipeline._cache_refresh_enabled():
            cache_root = pipeline._cache_algorithm_root()
            if not cache_root:
                raise ValueError(
                    "SageMaker encode partition cache requires hotvect_execution_parameters.cache_base_dir"
                )
            if not cache_root.startswith("s3://"):
                raise ValueError(f"SageMaker encode partition cache requires an s3:// cache_base_dir, got {cache_root}")
            cache_roots[cache_root] = pipeline._cache_algorithm_key()

        for dependency_pipeline in pipeline.dependency_pipelines.values():
            cache_roots.update(self._collect_partition_cache_roots(dependency_pipeline))
        return cache_roots

    def _s3_prefix_has_objects(self, s3_uri: str) -> bool:
        bucket, key = require_s3_uri(s3_uri)
        response = self._s3_client.list_objects_v2(Bucket=bucket, Prefix=key, MaxKeys=1)
        return bool(response.get("KeyCount", 0))

    def _build_pipeline_benchmark_contract(self) -> t.Dict[str, t.Any]:
        resource_config = self.training_job_definition.get("ResourceConfig", {})
        algorithm_spec = self.training_job_definition.get("AlgorithmSpecification", {})
        input_channels = {}
        for channel in self.training_job_definition.get("InputDataConfig", []):
            if not isinstance(channel, dict) or not channel.get("ChannelName"):
                continue
            if self._is_partition_cache_channel_name(channel["ChannelName"]):
                continue
            s3_uri = channel.get("DataSource", {}).get("S3DataSource", {}).get("S3Uri")
            if s3_uri:
                input_channels[channel["ChannelName"]] = s3_uri

        output_prefixes = {
            "metadata": self.hyperparameters.get("s3_uri_metadata"),
            "result": self.hyperparameters.get("s3_uri_result_file"),
            "predict_parameters": self.hyperparameters.get(PREDICT_PARAMETERS_ZIP_HYPERPARAMETER),
        }
        try:
            output_prefixes["sagemaker_output"] = self.sagemaker_output_s3_path
        except KeyError:
            logger.debug("Could not resolve SageMaker output prefix for benchmark contract", exc_info=True)
        base_contract = {"input_channels": input_channels} if input_channels else None
        return build_benchmark_contract(
            base_contract=base_contract,
            parameter_s3_uri=self.hyperparameters.get(PREDICT_PARAMETERS_ZIP_HYPERPARAMETER),
            instance_type=resource_config.get("InstanceType"),
            training_image=algorithm_spec.get("TrainingImage"),
            max_threads=getattr(self.algorithm_pipeline.algorithm_pipeline_context, "max_threads", None),
            output_prefixes=output_prefixes,
        )

    def _fail_if_algorithm_overrides_would_be_ignored(self) -> None:
        """Fail fast when the selected SageMaker image predates uploaded algorithm-definition support."""
        hyperparameters = self.training_job_definition.get("HyperParameters")
        if isinstance(hyperparameters, dict) and hyperparameters.get("s3_uri_custom_jar"):
            # Script-mode runs a caller-provided payload (custom.py), which can install a newer Hotvect runtime and
            # honor the effective algorithm definition written to S3, even if the base container image is v9.x.
            return

        def _parse_semver_from_training_image(training_image: str) -> t.Optional[t.Tuple[int, int, int]]:
            # Accept: "...:9.34.0", "...:9.34.0-suffix", "...:9.34.0_suffix"
            m = re.search(r":(\d+)\.(\d+)\.(\d+)(?:[._-].*)?$", training_image)
            if not m:
                return None
            return int(m.group(1)), int(m.group(2)), int(m.group(3))

        algo_spec = self.training_job_definition.get("AlgorithmSpecification")
        training_image = algo_spec.get("TrainingImage") if isinstance(algo_spec, dict) else None
        if not isinstance(training_image, str) or not training_image:
            return

        semver = _parse_semver_from_training_image(training_image)

        if semver is None:
            logger.warning(
                "Training image tag is not a recognized semver; cannot verify whether it honors %s. "
                "If this image predates that support, SageMaker rebuilds may fail. TrainingImage=%r",
                ALGO_DEF_S3_URI_HYPERPARAMETER,
                training_image,
            )
            return

        minimum_supported_version = (10, 14, 0)
        if semver >= minimum_supported_version:
            return

        raise ValueError(
            "Refusing to submit SageMaker job: the selected TrainingImage predates the uploaded algorithm-definition "
            f"contract required by this Hotvect submission path. TrainingImage={training_image!r} resolves to "
            f"v{semver[0]}.{semver[1]}.{semver[2]}, but SageMaker training images must be >= v10.14.0 or use "
            "script-mode (`s3_uri_custom_jar`)."
        )

    def _fail_if_prediction_output_uri_is_not_s3_for_sagemaker(self) -> None:
        if getattr(self.algorithm_pipeline, "run_target", "evaluate") != "predict":
            return

        prediction_output_uri = self.algorithm_pipeline.prediction_output_uri()
        if not prediction_output_uri.startswith("s3://"):
            raise ValueError(
                "Refusing to submit SageMaker predict job: prediction_spec.output_uri must be an s3:// URI when "
                f"executing on SageMaker, got {prediction_output_uri!r} for {self.algorithm_pipeline.algorithm_name}."
            )

    def run(self) -> CreateTrainingJobResponseTypeDef:
        self._prepare_algorithm_definition_for_sagemaker_execution()
        self._prepare_jar_for_sagemaker_execution()
        self._check_that_mandatory_params_are_present()
        self._upload_effective_training_job_definition()
        logger.debug(f"Final training job params: {json.dumps(self.training_job_definition, indent=2)}")
        return self._create_training_job_with_instance_fallbacks()

    def _create_training_job_with_instance_fallbacks(self) -> CreateTrainingJobResponseTypeDef:
        current_instance_type = self.training_job_definition["ResourceConfig"]["InstanceType"]
        attempt_instance_types = [current_instance_type, *self._instance_type_fallbacks]

        for attempt_index, instance_type in enumerate(attempt_instance_types):
            self.training_job_definition["ResourceConfig"]["InstanceType"] = instance_type
            if attempt_index > 0:
                logger.warning(
                    "Retrying SageMaker job %s with fallback instance type %s after ResourceLimitExceeded",
                    self.training_job_name,
                    instance_type,
                )
                self._upload_effective_training_job_definition()
            try:
                return self._sagemaker_client.create_training_job(**self.training_job_definition)
            except ClientError as e:
                error_code = e.response.get("Error", {}).get("Code")
                if error_code != "ResourceLimitExceeded" or attempt_index == len(attempt_instance_types) - 1:
                    raise
        raise AssertionError("Unreachable: retry loop exhausted without returning or raising")

    def _prepare_algorithm_definition_for_sagemaker_execution(self):
        algorithm_definition_name = f"{self.algorithm_pipeline.hyperparameter_slug()}-algorithm_definition.json"
        s3_uri_algorithm_definition = f"{self.sagemaker_output_s3_path}/{algorithm_definition_name}"
        self._upload_algorithm_definition_to_s3(s3_uri_algorithm_definition)
        self._add_s3_uri_algorithm_definition_to_hyperparameters(s3_uri_algorithm_definition)
        self._upload_effective_algorithm_definition()

    def _upload_effective_training_job_definition(self) -> None:
        s3_uri_metadata = self.hyperparameters.get("s3_uri_metadata")
        if not s3_uri_metadata:
            return
        _upload_json_to_s3(
            self.training_job_definition,
            join_s3_uri(s3_uri_metadata, EFFECTIVE_TRAINING_JOB_DEFINITION_BASENAME),
            self._s3_client,
            default=str,
        )

    def _upload_effective_algorithm_definition(self) -> None:
        s3_uri_metadata = self.hyperparameters.get("s3_uri_metadata")
        if not s3_uri_metadata:
            return
        _upload_json_to_s3(
            serialize_effective_algorithm_definition(self.algorithm_pipeline.algorithm_definition),
            join_s3_uri(s3_uri_metadata, EFFECTIVE_ALGORITHM_DEFINITION_BASENAME),
            self._s3_client,
            default=str,
        )

    def _prepare_jar_for_sagemaker_execution(self):
        """Uploads the jar to s3 and adds its s3 uri to the HyperParameters.

        When a caller already provided `s3_uri_algorithm_jar`, reuse it instead of
        re-uploading the same jar again.
        """
        existing_s3_uri_jar = self.hyperparameters.get("s3_uri_algorithm_jar")
        if existing_s3_uri_jar:
            logger.info(f"Reusing pre-uploaded jar from {existing_s3_uri_jar}")
            return

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

    def _algorithm_definition_payload_for_remote_rebuild(self) -> t.Dict[str, t.Any]:
        # Remote SageMaker consumers pass this JSON directly into AlgorithmPipeline / Java as the
        # algorithm definition, so it must already be the fully materialized effective definition.
        return serialize_effective_algorithm_definition(self.algorithm_pipeline.algorithm_definition)

    def _upload_algorithm_definition_to_s3(self, s3_uri_algorithm_definition: str):
        _upload_json_to_s3(
            self._algorithm_definition_payload_for_remote_rebuild(),
            s3_uri_algorithm_definition,
            self._s3_client,
            fail_fast=True,
            default=str,
        )
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
    `s3_uri_algorithm_jar` to run the reconstructed AlgorithmPipeline and requires
    `s3_uri_algorithm_definition` for the full effective algorithm definition.

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
            self._add_sagemaker_metadata_to_result_file()
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

    def _add_sagemaker_metadata_to_result_file(self) -> None:
        if self.algorithm_pipeline is None:
            raise RuntimeError("Cannot add SageMaker metadata before rebuilding the algorithm pipeline.")

        result_path = Path(self.algorithm_pipeline.metadata_path()).joinpath("result.json")
        result = json.loads(result_path.read_text())
        if not isinstance(result, dict):
            raise ValueError(f"Expected result.json to contain an object: {result_path}")

        result["sagemaker_training_job_name"] = self.sagemaker_env.job_name
        result["s3_uri_result_file"] = self.sagemaker_env.hyperparameters["s3_uri_result_file"]
        result_path.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")

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
            # _get_algorithm_definition() returns the full effective definition, not an override fragment.
            algorithm_definition=algorithm_definition,
            last_test_time=datetime.date.fromisoformat(algorithm_pipeline_params["last_test_time"]),
            # TODO: support other evaluation functions.
            evaluation_func=evaluation.standard_evaluation,
            hyperparameter_version=None,
            parameter_version=algorithm_pipeline_params["parameter_version"],
            execute_performance_test=execute_performance_test,
            encode_test_data=algorithm_pipeline_params.get("encode_test_data", False),
            execute_audit=algorithm_pipeline_params.get("execute_audit", False),
            run_target=algorithm_pipeline_params.get("run_target", "evaluate"),
            data_environment=algorithm_pipeline_params.get("data_environment", "production"),
            ran_at=algorithm_pipeline_params.get("ran_at"),
        )
        return algorithm_pipeline

    def _get_algorithm_definition(self, algorithm_jar_local_path):
        s3_uri_algorithm_definition = self.sagemaker_env.hyperparameters.get(ALGO_DEF_S3_URI_HYPERPARAMETER)
        if not s3_uri_algorithm_definition:
            raise KeyError(f"Missing algorithm definition. Provide {ALGO_DEF_S3_URI_HYPERPARAMETER}.")

        algorithm_definition = self._download_algorithm_definition(s3_uri_algorithm_definition)
        logger.info(
            "Effective algorithm definition loaded from s3_uri_algorithm_definition: "
            + json.dumps(algorithm_definition)
        )
        return algorithm_definition

    def _download_algorithm_jar(self, algorithm_jar, s3_uri_algorithm_jar):
        s3_uri_algorithm_jar_parsed = urlparse(s3_uri_algorithm_jar)
        s3_algorithm_jar_bucket: str = s3_uri_algorithm_jar_parsed.netloc
        s3_algorithm_jar_key: str = s3_uri_algorithm_jar_parsed.path.lstrip("/")
        self._s3_client.download_file(Bucket=s3_algorithm_jar_bucket, Key=s3_algorithm_jar_key, Filename=algorithm_jar)

    def _download_algorithm_definition(self, s3_uri_algorithm_definition: str) -> t.Dict[str, t.Any]:
        return parse_effective_algorithm_definition_payload(
            download_json_from_s3(s3_uri_algorithm_definition, self._s3_client)
        )

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
        jvm_options = normalize_pipeline_jvm_options(context_in_hyperparameters.get("jvm_options"))
        partition_cache_base_paths = self._rebuild_partition_cache_base_paths(
            data_base_path,
            context_in_hyperparameters.get("partition_cache_channels", {}),
        )
        algorithm_pipeline_context = AlgorithmPipelineContext(
            algorithm_jar_path=Path(algorithm_jar_path),
            # State source inputs should be resolved from SageMaker input channels.
            # This matches how train/test data is mounted under /opt/ml/input/data/{ChannelName}.
            state_source_base_path=data_base_path,
            data_base_path=data_base_path,
            metadata_base_path=metadata_base_path,
            output_base_path=output_data_base_path,
            jvm_options=jvm_options,
            max_threads=context_in_hyperparameters.get("max_threads"),
            queue_length=context_in_hyperparameters.get("queue_length"),
            read_queue_length=context_in_hyperparameters.get("read_queue_length"),
            write_queue_length=context_in_hyperparameters.get("write_queue_length"),
            batch_size=context_in_hyperparameters.get("batch_size"),
            additional_jar_files=list(),
            benchmark_contract=context_in_hyperparameters.get("benchmark_contract"),
            partition_cache_base_paths=partition_cache_base_paths or None,
            sagemaker_training_job_name=self.sagemaker_env.job_name,
        )
        return algorithm_pipeline_context

    @staticmethod
    def _rebuild_partition_cache_base_paths(
        data_base_path: Path,
        partition_cache_channels: t.Any,
    ) -> t.Dict[str, Path]:
        if partition_cache_channels is None:
            return {}
        if not isinstance(partition_cache_channels, dict):
            raise ValueError("partition_cache_channels must be a mapping of cache root to SageMaker channel name")
        if not partition_cache_channels:
            return {}

        partition_cache_base_paths = {}
        for cache_root, channel_name in partition_cache_channels.items():
            if not isinstance(cache_root, str) or not isinstance(channel_name, str):
                raise ValueError("partition_cache_channels must map string cache roots to string channel names")
            channel_path = data_base_path / channel_name
            if not channel_path.exists():
                raise FileNotFoundError(
                    f"Partition cache channel {channel_name} for {cache_root} was not mounted at {channel_path}"
                )
            partition_cache_base_paths[cache_root] = channel_path
        return partition_cache_base_paths

    def _get_metadata_dir(self) -> str:
        if _get_bool_from_env_variable(SAGEMAKER_TAR_INCLUDE_METADATA_ENV_VARIABLE, False):
            return self.sagemaker_env.output_data_dir
        logger.info(
            f"{SAGEMAKER_TAR_INCLUDE_METADATA_ENV_VARIABLE} env variable was set to a falsy value. "
            "Metadata will not be included in output.tar.gz."
        )
        return "/tmp/"

    def _get_output_dir(self) -> str:
        if _get_bool_from_env_variable(SAGEMAKER_TAR_INCLUDE_OUTPUT_ENV_VARIABLE, False):
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
        local_zip = str(self.algorithm_pipeline.predict_parameter_file_path())
        if not os.path.isfile(local_zip):
            raise FileNotFoundError(
                f"Predict parameters ZIP was advertised for upload to {s3_uri}, but no local ZIP exists at {local_zip}"
            )
        _upload_file_to_s3(local_zip, s3_uri, self._s3_client, fail_fast=True)

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


def _has_algorithm_definition(hyperparameters: t.Dict[str, str]) -> bool:
    return ALGO_DEF_S3_URI_HYPERPARAMETER in hyperparameters
