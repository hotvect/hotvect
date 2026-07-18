from __future__ import annotations

import atexit
import json
from datetime import date
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest


def _ensure_offline_util_jar_present() -> None:
    jar_dir = Path(__file__).resolve().parents[2] / "hotvectjar"
    jar_dir.mkdir(parents=True, exist_ok=True)
    required_patterns = {
        "hotvect-offline-util-*-jar-with-dependencies.jar": "hotvect-offline-util-test-jar-with-dependencies.jar",
        "hotvect-algorithm-serve-*-jar-with-dependencies.jar": "hotvect-algorithm-serve-test-jar-with-dependencies.jar",
        "hotvect-algorithm-demo-*-jar-with-dependencies.jar": "hotvect-algorithm-demo-test-jar-with-dependencies.jar",
    }
    for pattern, filename in required_patterns.items():
        existing = list(jar_dir.glob(pattern))
        if len(existing) == 0:
            dummy = jar_dir / filename
            dummy.touch(exist_ok=True)
            atexit.register(lambda path=dummy: path.unlink(missing_ok=True))


def _partition_cache_channel(channel_name: str, s3_uri: str) -> dict:
    return {
        "ChannelName": channel_name,
        "DataSource": {
            "S3DataSource": {
                "S3DataType": "S3Prefix",
                "S3Uri": s3_uri,
                "S3DataDistributionType": "FullyReplicated",
            }
        },
        "InputMode": "FastFile",
    }


def test_sagemaker_pipeline_params_include_encode_and_audit_flags() -> None:
    _ensure_offline_util_jar_present()
    from hotvect.sagemaker import ALGO_PIPELINE_HYPERPARAMETER_PREFIX, SagemakerTrainingExecutor

    def _eval(_: str) -> dict:
        return {"ok": True}

    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor.training_job_definition = {"HyperParameters": {}}
    executor.algorithm_pipeline = SimpleNamespace(
        last_test_time=date(2026, 1, 1),
        evaluation_function=_eval,
        parameter_version="pv",
        execute_performance_test=False,
        encode_test_data=True,
        execute_audit=True,
        data_environment="staging",
        ran_at="2026-01-01T12:00:00+00:00",
    )

    executor._add_algorithm_pipeline_params_as_hyperparameters()

    payload = json.loads(executor.hyperparameters[ALGO_PIPELINE_HYPERPARAMETER_PREFIX])
    assert "evaluation_func" not in payload
    assert payload["data_environment"] == "staging"
    assert payload["execute_performance_test"] is False
    assert payload["encode_test_data"] is True
    assert payload["execute_audit"] is True
    assert payload["ran_at"] == "2026-01-01T12:00:00+00:00"


def test_sagemaker_pipeline_context_includes_split_queue_lengths() -> None:
    _ensure_offline_util_jar_present()
    from hotvect.sagemaker import ALGO_PIPELINE_CONTEXT_PREFIX, SagemakerTrainingExecutor

    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor.training_job_definition = {"HyperParameters": {}}
    executor._partition_cache_channel_names_by_root = {
        "s3://bucket/cache/algo@1.2.3": "hotvect_partition_cache_algo_1-2-3"
    }
    executor.algorithm_pipeline = SimpleNamespace(
        algorithm_pipeline_context=SimpleNamespace(
            jvm_options=None,
            max_threads=None,
            queue_length=7,
            read_queue_length=13,
            write_queue_length=17,
            batch_size=11,
        )
    )

    executor._add_algorithm_pipeline_context_as_hyperparameters()

    payload = json.loads(executor.hyperparameters[ALGO_PIPELINE_CONTEXT_PREFIX])
    assert payload["queue_length"] == 7
    assert payload["read_queue_length"] == 13
    assert payload["write_queue_length"] == 17
    assert payload["batch_size"] == 11
    assert payload["partition_cache_channels"] == {"s3://bucket/cache/algo@1.2.3": "hotvect_partition_cache_algo_1-2-3"}


def test_sagemaker_pipeline_context_includes_benchmark_contract() -> None:
    _ensure_offline_util_jar_present()
    from hotvect.sagemaker import (
        ALGO_PIPELINE_CONTEXT_PREFIX,
        PARTITION_CACHE_CHANNEL_NAME,
        PREDICT_PARAMETERS_ZIP_HYPERPARAMETER,
        SagemakerTrainingExecutor,
    )

    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor._partition_cache_channel_names_by_root = {}
    executor.training_job_definition = {
        "TrainingJobName": "bt-demo",
        "OutputDataConfig": {"S3OutputPath": "s3://bucket/output"},
        "AlgorithmSpecification": {"TrainingImage": "training-image"},
        "ResourceConfig": {"InstanceType": "ml.c7i.2xlarge"},
        "InputDataConfig": [
            {
                "ChannelName": "test-data",
                "DataSource": {"S3DataSource": {"S3Uri": "s3://bucket/test-data/"}},
            },
            {
                "ChannelName": f"{PARTITION_CACHE_CHANNEL_NAME}_algo_1-2-3",
                "DataSource": {"S3DataSource": {"S3Uri": "s3://bucket/cache/algo/partitions/"}},
            },
        ],
        "HyperParameters": {
            "s3_uri_metadata": "s3://bucket/output/bt-demo/algo/metadata",
            "s3_uri_result_file": "s3://bucket/output/bt-demo/algo/result.json",
            PREDICT_PARAMETERS_ZIP_HYPERPARAMETER: "s3://bucket/output/bt-demo/algo/params.zip",
        },
    }
    executor.algorithm_pipeline = SimpleNamespace(
        algorithm_pipeline_context=SimpleNamespace(
            jvm_options=None,
            max_threads=8,
            queue_length=None,
            read_queue_length=None,
            write_queue_length=None,
            batch_size=None,
        )
    )

    executor._add_algorithm_pipeline_context_as_hyperparameters()

    payload = json.loads(executor.hyperparameters[ALGO_PIPELINE_CONTEXT_PREFIX])
    assert payload["benchmark_contract"] == {
        "parameter_s3_uri": "s3://bucket/output/bt-demo/algo/params.zip",
        "instance_type": "ml.c7i.2xlarge",
        "training_image": "training-image",
        "max_threads": 8,
        "input_channels": {"test-data": "s3://bucket/test-data/"},
        "output_prefixes": {
            "metadata": "s3://bucket/output/bt-demo/algo/metadata",
            "result": "s3://bucket/output/bt-demo/algo/result.json",
            "predict_parameters": "s3://bucket/output/bt-demo/algo/params.zip",
            "sagemaker_output": "s3://bucket/output/bt-demo",
        },
    }


def test_sagemaker_adds_fastfile_partition_cache_channel_when_cache_exists() -> None:
    _ensure_offline_util_jar_present()
    from hotvect.pyhotvect import PARTITION_CACHE_CHANNEL_NAME
    from hotvect.sagemaker import SagemakerTrainingExecutor

    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor.training_job_definition = {
        "AlgorithmSpecification": {"TrainingInputMode": "FastFile"},
        "InputDataConfig": [],
    }
    executor._s3_client = MagicMock()
    executor._s3_client.list_objects_v2.return_value = {"KeyCount": 1}
    executor.algorithm_pipeline = SimpleNamespace(
        _uses_encode_partition_cache=lambda: True,
        _cache_refresh_enabled=lambda: False,
        _cache_algorithm_root=lambda: "s3://bucket/cache/algo@1.2.3",
        _cache_algorithm_key=lambda: "algo@1.2.3",
        dependency_pipelines={},
    )

    executor._maybe_add_partition_cache_input_channel()

    assert executor.training_job_definition["InputDataConfig"] == [
        _partition_cache_channel(
            f"{PARTITION_CACHE_CHANNEL_NAME}_algo_1-2-3",
            "s3://bucket/cache/algo@1.2.3/partitions/",
        )
    ]
    assert executor._partition_cache_channel_names_by_root == {
        "s3://bucket/cache/algo@1.2.3": f"{PARTITION_CACHE_CHANNEL_NAME}_algo_1-2-3"
    }


def test_sagemaker_adds_fastfile_partition_cache_channel_for_dependency() -> None:
    _ensure_offline_util_jar_present()
    from hotvect.pyhotvect import PARTITION_CACHE_CHANNEL_NAME
    from hotvect.sagemaker import SagemakerTrainingExecutor

    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor.training_job_definition = {
        "AlgorithmSpecification": {"TrainingInputMode": "FastFile"},
        "InputDataConfig": [],
    }
    executor._s3_client = MagicMock()
    executor._s3_client.list_objects_v2.return_value = {"KeyCount": 1}
    child_pipeline = SimpleNamespace(
        _uses_encode_partition_cache=lambda: True,
        _cache_refresh_enabled=lambda: False,
        _cache_algorithm_root=lambda: "s3://bucket/cache/child@1.2.3",
        _cache_algorithm_key=lambda: "child@1.2.3",
        dependency_pipelines={},
    )
    executor.algorithm_pipeline = SimpleNamespace(
        _uses_encode_partition_cache=lambda: False,
        _cache_refresh_enabled=lambda: False,
        _cache_algorithm_root=lambda: "s3://bucket/cache/parent@1.2.3",
        dependency_pipelines={"child": child_pipeline},
    )

    executor._maybe_add_partition_cache_input_channel()

    assert executor.training_job_definition["InputDataConfig"] == [
        _partition_cache_channel(
            f"{PARTITION_CACHE_CHANNEL_NAME}_child_1-2-3",
            "s3://bucket/cache/child@1.2.3/partitions/",
        )
    ]


def test_sagemaker_adds_fastfile_partition_cache_channels_for_multiple_roots() -> None:
    _ensure_offline_util_jar_present()
    from hotvect.pyhotvect import PARTITION_CACHE_CHANNEL_NAME
    from hotvect.sagemaker import SagemakerTrainingExecutor

    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor.training_job_definition = {"InputDataConfig": []}
    executor._s3_client = MagicMock()
    executor._s3_client.list_objects_v2.return_value = {"KeyCount": 1}
    child_a = SimpleNamespace(
        _uses_encode_partition_cache=lambda: True,
        _cache_refresh_enabled=lambda: False,
        _cache_algorithm_root=lambda: "s3://bucket/cache/child-a@1.2.3",
        _cache_algorithm_key=lambda: "child-a@1.2.3",
        dependency_pipelines={},
    )
    child_b = SimpleNamespace(
        _uses_encode_partition_cache=lambda: True,
        _cache_refresh_enabled=lambda: False,
        _cache_algorithm_root=lambda: "s3://bucket/cache/child-b@1.2.3",
        _cache_algorithm_key=lambda: "child-b@1.2.3",
        dependency_pipelines={},
    )
    executor.algorithm_pipeline = SimpleNamespace(
        _uses_encode_partition_cache=lambda: False,
        _cache_refresh_enabled=lambda: False,
        _cache_algorithm_root=lambda: None,
        dependency_pipelines={"child-a": child_a, "child-b": child_b},
    )

    executor._maybe_add_partition_cache_input_channel()

    assert executor.training_job_definition["InputDataConfig"] == [
        _partition_cache_channel(
            f"{PARTITION_CACHE_CHANNEL_NAME}_child-a_1-2-3",
            "s3://bucket/cache/child-a@1.2.3/partitions/",
        ),
        _partition_cache_channel(
            f"{PARTITION_CACHE_CHANNEL_NAME}_child-b_1-2-3",
            "s3://bucket/cache/child-b@1.2.3/partitions/",
        ),
    ]
    assert executor._partition_cache_channel_names_by_root == {
        "s3://bucket/cache/child-a@1.2.3": f"{PARTITION_CACHE_CHANNEL_NAME}_child-a_1-2-3",
        "s3://bucket/cache/child-b@1.2.3": f"{PARTITION_CACHE_CHANNEL_NAME}_child-b_1-2-3",
    }


def test_sagemaker_fails_when_partition_cache_channel_already_exists() -> None:
    _ensure_offline_util_jar_present()
    from hotvect.pyhotvect import PARTITION_CACHE_CHANNEL_NAME
    from hotvect.sagemaker import SagemakerTrainingExecutor

    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor.training_job_definition = {
        "InputDataConfig": [
            _partition_cache_channel(
                f"{PARTITION_CACHE_CHANNEL_NAME}_algo_1-2-3",
                "s3://bucket/other/",
            )
        ]
    }
    executor._s3_client = MagicMock()
    executor.algorithm_pipeline = SimpleNamespace(
        _uses_encode_partition_cache=lambda: True,
        _cache_refresh_enabled=lambda: False,
        _cache_algorithm_root=lambda: "s3://bucket/cache/algo@1.2.3",
        _cache_algorithm_key=lambda: "algo@1.2.3",
        dependency_pipelines={},
    )

    with pytest.raises(ValueError, match="already contains Hotvect partition cache channel"):
        executor._maybe_add_partition_cache_input_channel()


def test_sagemaker_partition_cache_channel_name_is_readable_and_within_sagemaker_limit() -> None:
    from hotvect.pyhotvect import PARTITION_CACHE_CHANNEL_NAME
    from hotvect.sagemaker import SAGEMAKER_CHANNEL_NAME_MAX_LENGTH, SagemakerTrainingExecutor

    channel_name = SagemakerTrainingExecutor._partition_cache_channel_name(
        "example-ranking-algorithm-engagement-model@81.1.0"
    )

    assert len(channel_name) <= SAGEMAKER_CHANNEL_NAME_MAX_LENGTH
    assert channel_name.startswith(f"{PARTITION_CACHE_CHANNEL_NAME}_example-ranking")
    assert channel_name.endswith("_81-1-0")


def test_sagemaker_skips_partition_cache_channel_when_cache_is_empty() -> None:
    _ensure_offline_util_jar_present()
    from hotvect.sagemaker import SagemakerTrainingExecutor

    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor.training_job_definition = {"InputDataConfig": []}
    executor._s3_client = MagicMock()
    executor._s3_client.list_objects_v2.return_value = {"KeyCount": 0}
    executor.algorithm_pipeline = SimpleNamespace(
        _uses_encode_partition_cache=lambda: True,
        _cache_refresh_enabled=lambda: False,
        _cache_algorithm_root=lambda: "s3://bucket/cache/algo@1.2.3",
        _cache_algorithm_key=lambda: "algo@1.2.3",
        dependency_pipelines={},
    )

    executor._maybe_add_partition_cache_input_channel()

    assert executor.training_job_definition["InputDataConfig"] == []


def test_sagemaker_skips_partition_cache_channel_when_cache_refresh_is_enabled() -> None:
    _ensure_offline_util_jar_present()
    from hotvect.sagemaker import SagemakerTrainingExecutor

    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor.training_job_definition = {"InputDataConfig": []}
    executor._s3_client = MagicMock()
    executor.algorithm_pipeline = SimpleNamespace(
        _uses_encode_partition_cache=lambda: True,
        _cache_refresh_enabled=lambda: True,
        _cache_algorithm_root=lambda: "s3://bucket/cache/algo@1.2.3",
        dependency_pipelines={},
    )

    executor._maybe_add_partition_cache_input_channel()

    assert executor.training_job_definition["InputDataConfig"] == []
    executor._s3_client.list_objects_v2.assert_not_called()


def test_sagemaker_validates_cache_refresh_before_creating_aws_clients(monkeypatch) -> None:
    _ensure_offline_util_jar_present()
    from hotvect.sagemaker import SagemakerTrainingExecutor

    child_pipeline = SimpleNamespace(
        _cache_refresh_enabled=MagicMock(side_effect=ValueError("cache_refresh requires effective cache_base_dir.")),
        dependency_pipelines={},
    )
    pipeline = SimpleNamespace(
        _cache_refresh_enabled=MagicMock(return_value=False),
        dependency_pipelines={"child-algo": child_pipeline},
    )

    def fail_if_called():
        raise AssertionError("SageMaker executor should validate cache_refresh before opening AWS clients")

    monkeypatch.setattr("hotvect.sagemaker.boto3.Session", fail_if_called)

    with pytest.raises(ValueError, match="cache_refresh requires effective cache_base_dir"):
        SagemakerTrainingExecutor(
            algorithm_pipeline=pipeline,
            training_job_definition={},
        )

    pipeline._cache_refresh_enabled.assert_called_once_with()
    child_pipeline._cache_refresh_enabled.assert_called_once_with()


def test_sagemaker_fails_partition_cache_channel_for_local_cache_root() -> None:
    _ensure_offline_util_jar_present()
    from hotvect.sagemaker import SagemakerTrainingExecutor

    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor.training_job_definition = {"InputDataConfig": []}
    executor._s3_client = MagicMock()
    executor.algorithm_pipeline = SimpleNamespace(
        _uses_encode_partition_cache=lambda: True,
        _cache_refresh_enabled=lambda: False,
        _cache_algorithm_root=lambda: "/tmp/hotvect-cache/algo@1.2.3",
        dependency_pipelines={},
    )

    with pytest.raises(ValueError, match="requires an s3:// cache_base_dir"):
        executor._maybe_add_partition_cache_input_channel()

    executor._s3_client.list_objects_v2.assert_not_called()


def test_sagemaker_predict_requires_s3_prediction_output_uri() -> None:
    _ensure_offline_util_jar_present()
    from hotvect.sagemaker import SagemakerTrainingExecutor

    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor.algorithm_pipeline = SimpleNamespace(
        run_target="predict",
        algorithm_name="demo-algo",
        prediction_output_uri=lambda: "/tmp/predictions",
    )

    with pytest.raises(ValueError, match="must be an s3:// URI"):
        executor._fail_if_prediction_output_uri_is_not_s3_for_sagemaker()


def test_sagemaker_predict_accepts_s3_prediction_output_uri() -> None:
    _ensure_offline_util_jar_present()
    from hotvect.sagemaker import SagemakerTrainingExecutor

    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor.algorithm_pipeline = SimpleNamespace(
        run_target="predict",
        algorithm_name="demo-algo",
        prediction_output_uri=lambda: "s3://bucket/predictions/dt=2026-01-01/",
    )

    executor._fail_if_prediction_output_uri_is_not_s3_for_sagemaker()


def test_sagemaker_skips_predict_parameters_zip_hyperparameter_when_pipeline_wont_publish_zip() -> None:
    _ensure_offline_util_jar_present()
    from hotvect.sagemaker import PREDICT_PARAMETERS_ZIP_HYPERPARAMETER, SagemakerTrainingExecutor

    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor.training_job_definition = {
        "HyperParameters": {},
        "TrainingJobName": "demo-job",
        "OutputDataConfig": {"S3OutputPath": "s3://bucket/output"},
    }
    executor.algorithm_pipeline = SimpleNamespace(
        should_publish_predict_parameters_zip=lambda: False,
        predict_parameter_file_path=lambda: "/tmp/unused.parameters.zip",
        hyperparameter_slug=lambda: "demo-slug",
    )

    executor._add_s3_uri_predict_parameters_zip()

    assert PREDICT_PARAMETERS_ZIP_HYPERPARAMETER not in executor.hyperparameters


def test_sagemaker_adds_predict_parameters_zip_hyperparameter_when_pipeline_publishes_zip(tmp_path) -> None:
    _ensure_offline_util_jar_present()
    from hotvect.sagemaker import PREDICT_PARAMETERS_ZIP_HYPERPARAMETER, SagemakerTrainingExecutor

    zip_path = tmp_path / "predict-parameters.zip"

    executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
    executor.training_job_definition = {
        "HyperParameters": {},
        "TrainingJobName": "demo-job",
        "OutputDataConfig": {"S3OutputPath": "s3://bucket/output"},
    }
    executor.algorithm_pipeline = SimpleNamespace(
        should_publish_predict_parameters_zip=lambda: True,
        predict_parameter_file_path=lambda: str(zip_path),
        hyperparameter_slug=lambda: "demo-slug",
    )

    executor._add_s3_uri_predict_parameters_zip()

    assert executor.hyperparameters[PREDICT_PARAMETERS_ZIP_HYPERPARAMETER] == (
        "s3://bucket/output/demo-job/demo-slug/predict-parameters.zip"
    )


def test_sagemaker_rebuilder_fails_when_advertised_predict_parameters_zip_is_missing(tmp_path) -> None:
    _ensure_offline_util_jar_present()
    from hotvect.sagemaker import PREDICT_PARAMETERS_ZIP_HYPERPARAMETER, SagemakerAlgorithmPipelineRebuilder

    rebuilder = SagemakerAlgorithmPipelineRebuilder.__new__(SagemakerAlgorithmPipelineRebuilder)
    rebuilder.sagemaker_env = SimpleNamespace(
        hyperparameters={PREDICT_PARAMETERS_ZIP_HYPERPARAMETER: "s3://bucket/output/predict-parameters.zip"}
    )
    rebuilder.algorithm_pipeline = SimpleNamespace(
        predict_parameter_file_path=lambda: str(tmp_path / "missing.parameters.zip")
    )
    rebuilder._s3_client = object()

    with pytest.raises(FileNotFoundError, match="advertised for upload"):
        rebuilder._maybe_upload_predict_parameters_zip()


def test_sagemaker_rebuilder_uploads_predict_parameters_zip_fail_fast(monkeypatch, tmp_path) -> None:
    _ensure_offline_util_jar_present()
    from hotvect.sagemaker import PREDICT_PARAMETERS_ZIP_HYPERPARAMETER, SagemakerAlgorithmPipelineRebuilder

    local_zip = tmp_path / "predict-parameters.zip"
    local_zip.write_text("zip-bytes", encoding="utf-8")
    uploaded = {}

    def _fake_upload(local_file_path, s3_target_uri, s3_client, *, fail_fast=False):
        uploaded["local_file_path"] = local_file_path
        uploaded["s3_target_uri"] = s3_target_uri
        uploaded["s3_client"] = s3_client
        uploaded["fail_fast"] = fail_fast

    monkeypatch.setattr("hotvect.sagemaker._upload_file_to_s3", _fake_upload)

    rebuilder = SagemakerAlgorithmPipelineRebuilder.__new__(SagemakerAlgorithmPipelineRebuilder)
    rebuilder.sagemaker_env = SimpleNamespace(
        hyperparameters={PREDICT_PARAMETERS_ZIP_HYPERPARAMETER: "s3://bucket/output/predict-parameters.zip"}
    )
    rebuilder.algorithm_pipeline = SimpleNamespace(predict_parameter_file_path=lambda: str(local_zip))
    rebuilder._s3_client = object()

    rebuilder._maybe_upload_predict_parameters_zip()

    assert uploaded == {
        "local_file_path": str(local_zip),
        "s3_target_uri": "s3://bucket/output/predict-parameters.zip",
        "s3_client": rebuilder._s3_client,
        "fail_fast": True,
    }


def test_sagemaker_rebuilder_passes_materialized_algorithm_definition_dict(monkeypatch) -> None:
    from hotvect.sagemaker import ALGO_PIPELINE_HYPERPARAMETER_PREFIX, SagemakerAlgorithmPipelineRebuilder

    full_definition = {
        "algorithm_name": "demo-algo",
        "algorithm_version": "1.2.3",
        "dependencies": [],
    }
    seen = {}

    class DummyAlgorithmPipeline:
        def __init__(
            self,
            *,
            algorithm_pipeline_context,
            algorithm_definition,
            last_test_time,
            evaluation_func,
            hyperparameter_version,
            parameter_version,
            execute_performance_test,
            encode_test_data,
            execute_audit,
            run_target,
            data_environment,
            ran_at,
        ) -> None:
            seen["algorithm_pipeline_context"] = algorithm_pipeline_context
            seen["algorithm_definition"] = algorithm_definition
            seen["last_test_time"] = last_test_time
            seen["evaluation_func"] = evaluation_func
            seen["hyperparameter_version"] = hyperparameter_version
            seen["parameter_version"] = parameter_version
            seen["execute_performance_test"] = execute_performance_test
            seen["encode_test_data"] = encode_test_data
            seen["execute_audit"] = execute_audit
            seen["run_target"] = run_target
            seen["data_environment"] = data_environment
            seen["ran_at"] = ran_at

    monkeypatch.setattr("hotvect.sagemaker.AlgorithmPipeline", DummyAlgorithmPipeline)

    rebuilder = SagemakerAlgorithmPipelineRebuilder.__new__(SagemakerAlgorithmPipelineRebuilder)
    rebuilder.sagemaker_env = SimpleNamespace(
        hyperparameters={
            "s3_uri_algorithm_jar": "s3://bucket/demo-algo.jar",
            ALGO_PIPELINE_HYPERPARAMETER_PREFIX: {
                "last_test_time": "2026-04-01",
                "parameter_version": "pv1",
                "execute_performance_test": False,
                "encode_test_data": True,
                "execute_audit": True,
                "run_target": "evaluate",
                "data_environment": "production",
                "ran_at": "2026-04-01T08:15:00+00:00",
            },
        }
    )
    rebuilder._download_algorithm_jar = lambda *_args, **_kwargs: None
    rebuilder._get_algorithm_definition = lambda *_args, **_kwargs: full_definition
    rebuilder._rebuild_algorithm_pipeline_context = lambda *_args, **_kwargs: "context"

    rebuilder.rebuild_algorithm_pipeline()

    assert seen["algorithm_definition"] == full_definition
    assert isinstance(seen["algorithm_definition"], dict)
    assert seen["algorithm_pipeline_context"] == "context"
    assert seen["last_test_time"] == date(2026, 4, 1)
    assert seen["parameter_version"] == "pv1"
    assert seen["execute_performance_test"] is False
    assert seen["encode_test_data"] is True
    assert seen["execute_audit"] is True
    assert seen["run_target"] == "evaluate"
    assert seen["data_environment"] == "production"
    assert seen["ran_at"] == "2026-04-01T08:15:00+00:00"


@pytest.mark.parametrize(
    ("jvm_options", "expected"),
    [
        ([], ["-XX:MaxRAMPercentage=80"]),
        (["-Dfoo=bar"], ["-Dfoo=bar", "-XX:MaxRAMPercentage=80"]),
    ],
)
def test_sagemaker_rebuilder_normalizes_jvm_options_to_include_default_heap_cap(
    monkeypatch, tmp_path, jvm_options, expected
) -> None:
    from hotvect.sagemaker import ALGO_PIPELINE_CONTEXT_PREFIX, SagemakerAlgorithmPipelineRebuilder

    rebuilder = SagemakerAlgorithmPipelineRebuilder.__new__(SagemakerAlgorithmPipelineRebuilder)
    rebuilder.sagemaker_env = SimpleNamespace(
        input_dir=str(tmp_path / "input"),
        job_name="train-job",
        hyperparameters={ALGO_PIPELINE_CONTEXT_PREFIX: {"jvm_options": jvm_options}},
    )
    monkeypatch.setattr(rebuilder, "_get_metadata_dir", lambda: str(tmp_path / "meta-root"))
    monkeypatch.setattr(rebuilder, "_get_output_dir", lambda: str(tmp_path / "out-root"))

    context = rebuilder._rebuild_algorithm_pipeline_context(tmp_path / "algo.jar")

    assert context.jvm_options == expected


def test_sagemaker_rebuilder_maps_partition_cache_channels_to_mount_paths(monkeypatch, tmp_path) -> None:
    from hotvect.sagemaker import ALGO_PIPELINE_CONTEXT_PREFIX, SagemakerAlgorithmPipelineRebuilder

    data_dir = tmp_path / "input" / "data"
    channel_dir = data_dir / "hotvect_partition_cache_algo_1-2-3"
    channel_dir.mkdir(parents=True)

    rebuilder = SagemakerAlgorithmPipelineRebuilder.__new__(SagemakerAlgorithmPipelineRebuilder)
    rebuilder.sagemaker_env = SimpleNamespace(
        input_dir=str(tmp_path / "input"),
        job_name="train-job",
        hyperparameters={
            ALGO_PIPELINE_CONTEXT_PREFIX: {
                "partition_cache_channels": {"s3://bucket/cache/algo@1.2.3": "hotvect_partition_cache_algo_1-2-3"}
            }
        },
    )
    monkeypatch.setattr(rebuilder, "_get_metadata_dir", lambda: str(tmp_path / "meta-root"))
    monkeypatch.setattr(rebuilder, "_get_output_dir", lambda: str(tmp_path / "out-root"))

    context = rebuilder._rebuild_algorithm_pipeline_context(tmp_path / "algo.jar")

    assert context.partition_cache_base_paths == {"s3://bucket/cache/algo@1.2.3": channel_dir}
    assert context.sagemaker_training_job_name == "train-job"
