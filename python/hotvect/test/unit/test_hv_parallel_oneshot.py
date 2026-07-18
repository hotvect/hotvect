import importlib.util
import json
import logging
import sys
from datetime import date
from importlib.machinery import SourceFileLoader
from pathlib import Path
from types import ModuleType, SimpleNamespace

import pytest


def _load_hv_module(monkeypatch):
    fake_hotvectjar = ModuleType("hotvect.hotvectjar")
    fake_hotvectjar.HOTVECT_JAR_PATH = Path("/tmp/offline.jar")
    fake_hotvectjar.HOTVECT_ALGORITHM_SERVE_JAR_PATH = Path("/tmp/serve.jar")
    fake_hotvectjar.HOTVECT_ALGORITHM_DEMO_JAR_PATH = Path("/tmp/demo.jar")
    monkeypatch.setitem(sys.modules, "hotvect.hotvectjar", fake_hotvectjar)
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "bin" / "hv"
        if candidate.exists():
            loader = SourceFileLoader("hv_cli_parallel_oneshot", str(candidate))
            spec = importlib.util.spec_from_loader(loader.name, loader)
            assert spec is not None
            module = importlib.util.module_from_spec(spec)
            loader.exec_module(module)
            return module
    raise FileNotFoundError("Could not locate bin/hv relative to test file")


def _base_args():
    return SimpleNamespace(
        algorithm_jar="algo.jar",
        algorithm_name="demo-algo",
        algorithm_override=None,
        metadata_path="meta",
        source_path=None,
        dest_path="s3://bucket/out/",
        parameter_path=None,
        dest_schema_path=None,
        samples=None,
        max_threads=None,
        sagemaker=True,
        sagemaker_job_prefix="ml-exp-test",
        sagemaker_config=None,
        role_arn="arn:aws:iam::123456789012:role/TestRole",
        assume_role_arn=None,
        s3_output_base="s3://bucket/managed/",
        instance_type="ml.c7i.4xlarge",
        volume_gb=None,
        max_runtime_seconds=None,
        training_image=None,
        source_s3_uri="s3://bucket/source/",
        parameter_s3_uri="s3://bucket/params.zip",
        job_parallelism=4,
        verify=False,
        no_wait=False,
        compression="gzip",
        ordered=False,
        unordered=False,
        writer_num_shards=None,
        include_feature_store_responses=False,
        target_rps=None,
        target_throughput_fraction=None,
        workload_mode=None,
        log_features=False,
        verbose=False,
        parallel_preuploaded_algorithm_jar_s3_uri=None,
        _jvm_args=[],
    )


def test_add_or_keep_source_channel_keeps_matching_template_channel(monkeypatch):
    hv = _load_hv_module(monkeypatch)
    job_def = {
        "InputDataConfig": [
            {
                "ChannelName": "source",
                "DataSource": {"S3DataSource": {"S3DataType": "S3Prefix", "S3Uri": "s3://bucket/source"}},
                "InputMode": "FastFile",
            }
        ]
    }

    hv._add_or_keep_source_channel(job_def, source_s3_uri="s3://bucket/source/", channel_name="source")

    assert len(job_def["InputDataConfig"]) == 1
    assert job_def["InputDataConfig"][0]["DataSource"]["S3DataSource"]["S3Uri"] == "s3://bucket/source/"


def test_add_or_keep_source_channel_overwrites_mismatched_template_channel(monkeypatch):
    hv = _load_hv_module(monkeypatch)
    job_def = {
        "InputDataConfig": [
            {
                "ChannelName": "source",
                "DataSource": {"S3DataSource": {"S3DataType": "S3Prefix", "S3Uri": "s3://bucket/old-source/"}},
                "InputMode": "FastFile",
            }
        ]
    }

    hv._add_or_keep_source_channel(job_def, source_s3_uri="s3://bucket/new-source/", channel_name="source")

    assert len(job_def["InputDataConfig"]) == 1
    assert job_def["InputDataConfig"][0]["DataSource"]["S3DataSource"]["S3Uri"] == "s3://bucket/new-source/"


def test_predict_parallel_routes_to_parallel_runner(monkeypatch):
    hv = _load_hv_module(monkeypatch)
    called = {}

    def _fake_parallel_runner(**kwargs):
        called.update(kwargs)

    monkeypatch.setattr(hv, "_run_parallel_one_shot_sagemaker_job", _fake_parallel_runner)

    args = _base_args()
    hv.PredictCommand().execute(args)

    assert called["task"] == "predict"
    assert called["command_name"] == "predict"
    assert called["args"] is args


def test_single_job_sagemaker_predict_allows_no_wait_and_submits(monkeypatch):
    hv = _load_hv_module(monkeypatch)
    called = {}

    def _fake_submit(**kwargs):
        called.update(kwargs)
        return {"training_job_name": "single-job"}

    monkeypatch.setattr(hv, "_submit_one_shot_sagemaker_job", _fake_submit)

    args = _base_args()
    args.job_parallelism = 1
    args.no_wait = True
    args.compression = "none"

    hv.PredictCommand().execute(args)

    assert called["task"] == "predict"
    assert called["task_kind_short"] == "predict"
    assert called["args"] is args


def test_single_job_sagemaker_predict_allows_missing_parameter_uri(monkeypatch):
    hv = _load_hv_module(monkeypatch)
    called = {}

    def _fake_submit(**kwargs):
        called.update(kwargs)
        return {"training_job_name": "single-job"}

    monkeypatch.setattr(hv, "_submit_one_shot_sagemaker_job", _fake_submit)

    args = _base_args()
    args.job_parallelism = 1
    args.compression = "none"
    args.parameter_s3_uri = None

    hv.PredictCommand().execute(args)

    assert called["task"] == "predict"
    assert called["task_kind_short"] == "predict"
    assert called["args"] is args


def test_single_job_sagemaker_no_wait_logs_behavior(monkeypatch, caplog):
    hv = _load_hv_module(monkeypatch)

    monkeypatch.setattr(
        hv,
        "_submit_one_shot_sagemaker_job",
        lambda **_kwargs: {"training_job_name": "single-job"},
    )

    args = _base_args()
    args.job_parallelism = 1
    args.no_wait = True
    args.compression = "none"

    with caplog.at_level(logging.INFO):
        hv.PredictCommand().execute(args)

    assert "Single-job SageMaker one-shot runs already return after submission" in caplog.text


def test_single_job_sagemaker_perf_allows_no_wait_and_submits(monkeypatch):
    hv = _load_hv_module(monkeypatch)
    called = {}

    def _fake_submit(**kwargs):
        called.update(kwargs)
        return {"training_job_name": "perf-job"}

    monkeypatch.setattr(hv, "_submit_one_shot_sagemaker_job", _fake_submit)

    args = _base_args()
    args.job_parallelism = 1
    args.no_wait = True
    args.compression = "none"

    hv.PerformanceTestCommand().execute(args)

    assert called["task"] == "performance-test"
    assert called["task_kind_short"] == "perf"
    assert called["args"] is args


def test_verify_routes_without_algorithm_metadata(monkeypatch):
    hv = _load_hv_module(monkeypatch)
    called = {}

    def _fake_verify(**kwargs):
        called.update(kwargs)

    monkeypatch.setattr(hv, "_verify_parallel_one_shot_sagemaker_job", _fake_verify)

    args = _base_args()
    args.verify = True
    args.job_parallelism = 1
    args.algorithm_jar = None
    args.algorithm_name = None
    args.parameter_s3_uri = None
    args.source_s3_uri = None

    hv.PredictCommand().execute(args)

    assert called["command_name"] == "predict"
    assert called["args"] is args


def test_encode_rejects_compression(monkeypatch):
    hv = _load_hv_module(monkeypatch)
    args = _base_args()
    args.parameter_s3_uri = None

    with pytest.raises(ValueError, match="compression is not supported for encode"):
        hv.EncodeCommand().execute(args)


def test_local_no_wait_is_rejected(monkeypatch):
    hv = _load_hv_module(monkeypatch)
    args = _base_args()
    args.sagemaker = False
    args.job_parallelism = 1
    args.no_wait = True

    with pytest.raises(ValueError, match="--no-wait is only supported with --sagemaker"):
        hv.PredictCommand().execute(args)


def test_parallel_mode_allows_writer_num_shards(monkeypatch):
    hv = _load_hv_module(monkeypatch)
    called = {}

    def _fake_parallel_runner(**kwargs):
        called.update(kwargs)

    monkeypatch.setattr(hv, "_run_parallel_one_shot_sagemaker_job", _fake_parallel_runner)

    args = _base_args()
    args.writer_num_shards = 8

    hv.PredictCommand().execute(args)

    assert called["task"] == "predict"
    assert called["args"].writer_num_shards == 8


def test_parallel_mode_allows_explicit_unordered(monkeypatch):
    hv = _load_hv_module(monkeypatch)
    called = {}

    def _fake_parallel_runner(**kwargs):
        called.update(kwargs)

    monkeypatch.setattr(hv, "_run_parallel_one_shot_sagemaker_job", _fake_parallel_runner)

    args = _base_args()
    args.unordered = True

    hv.PredictCommand().execute(args)

    assert called["task"] == "predict"
    assert called["args"] is args


def test_parallel_runner_rejects_effective_predict_ordered_config(monkeypatch):
    hv = _load_hv_module(monkeypatch)
    args = _base_args()
    monkeypatch.setattr(
        hv,
        "build_one_shot_effective_algorithm_definition",
        lambda **_kwargs: {"hotvect_execution_parameters": {"predict": {"ordered": True}}},
    )

    with pytest.raises(ValueError, match=r"requires unordered output.*predict\.ordered=true"):
        hv._run_parallel_one_shot_sagemaker_job(
            task="predict",
            task_kind_short="pred",
            command_name="predict",
            args=args,
        )


def test_parallel_runner_rejects_effective_encode_ordered_config(monkeypatch):
    hv = _load_hv_module(monkeypatch)
    args = _base_args()
    args.parameter_s3_uri = None
    monkeypatch.setattr(
        hv,
        "build_one_shot_effective_algorithm_definition",
        lambda **_kwargs: {"train_decoder_parameters": {"ordering": "ordered"}},
    )

    with pytest.raises(ValueError, match=r"requires unordered output.*train_decoder_parameters\.ordering='ordered'"):
        hv._run_parallel_one_shot_sagemaker_job(
            task="encode",
            task_kind_short="encode",
            command_name="encode",
            args=args,
        )


def test_parallel_runner_allows_effective_encode_writer_num_shards(monkeypatch):
    hv = _load_hv_module(monkeypatch)
    args = _base_args()
    args.parameter_s3_uri = None

    hv._validate_parallel_one_shot_output_contract(
        task="encode",
        args=args,
        effective_algorithm_definition={"transformer_parameters": {"writer_num_shards": 16}},
    )


def test_submit_parallel_one_shot_sets_public_output_hyperparameters(monkeypatch, tmp_path: Path):
    hv = _load_hv_module(monkeypatch)
    captured = {}

    class FakeAlgorithmPipelineContext:
        def __init__(self, **_kwargs):
            pass

    class FakeAlgorithmPipeline:
        def __init__(self, **_kwargs):
            self.algorithm_definition = {"hyperparameter_version": "hp-1"}
            self.parameter_version = "oneshot"
            self.last_test_time = date(2026, 4, 5)

        def hyperparameter_slug(self):
            return "hp-slug"

    class FakeExecutor:
        def __init__(self, algorithm_pipeline, training_job_definition, role_arn_to_assume):
            captured["job_def"] = training_job_definition
            captured["role_arn_to_assume"] = role_arn_to_assume
            self.algorithm_pipeline = algorithm_pipeline
            self.hyperparameters = training_job_definition["HyperParameters"]
            self.training_job_name = training_job_definition["TrainingJobName"]
            self.hyperparameters.setdefault("s3_uri_result_file", "s3://bucket/output/result.json")
            self.hyperparameters.setdefault("s3_uri_metadata", "s3://bucket/output/meta/")

        def run(self):
            return {"TrainingJobArn": "arn:aws:sagemaker:job/demo"}

        def build_submission_manifest(self, submission_response):
            return {
                "training_job_name": self.training_job_name,
                "training_job_arn": submission_response["TrainingJobArn"],
                "s3_uri_result_file": self.hyperparameters["s3_uri_result_file"],
                "s3_uri_metadata": self.hyperparameters["s3_uri_metadata"],
            }

    monkeypatch.setitem(
        sys.modules,
        "hotvect.pyhotvect",
        SimpleNamespace(
            AlgorithmPipeline=FakeAlgorithmPipeline,
            AlgorithmPipelineContext=FakeAlgorithmPipelineContext,
        ),
    )
    monkeypatch.setitem(sys.modules, "hotvect.evaluation.evaluation", SimpleNamespace(standard_evaluation=object()))
    monkeypatch.setitem(sys.modules, "hotvect.sagemaker", SimpleNamespace(SagemakerTrainingExecutor=FakeExecutor))
    monkeypatch.setattr(hv, "resolve_template_path", lambda *_args, **_kwargs: SimpleNamespace(path=None))
    monkeypatch.setattr(hv, "resolve_training_image", lambda **_kwargs: "training-image")
    monkeypatch.setattr(hv, "build_one_shot_training_job_name", lambda **_kwargs: "parallel-job")
    monkeypatch.setattr(
        hv,
        "build_one_shot_effective_algorithm_definition",
        lambda *_args, **_kwargs: {
            "algorithm_name": "demo-algo",
            "hyperparameter_version": "hp-1",
        },
    )
    monkeypatch.setattr(
        hv,
        "build_training_job_definition",
        lambda **_kwargs: {
            "TrainingJobName": "parallel-job",
            "OutputDataConfig": {"S3OutputPath": "s3://bucket/output"},
            "HyperParameters": {},
            "InputDataConfig": [],
        },
    )

    args = _base_args()
    args.algorithm_jar = tmp_path / "algo.jar"
    args.parallel_task_output_s3_uri = "s3://bucket/final/"
    args.parallel_worker_count = 8
    args.parallel_worker_index = 3
    args.unordered = True
    args.writer_num_shards = 5

    submission = hv._submit_one_shot_sagemaker_job(task="predict", task_kind_short="preds03", args=args)

    hp = captured["job_def"]["HyperParameters"]
    assert json.loads(hp["hotvect_task_output"]) == {
        "compression": "gzip",
        "s3_uri": "s3://bucket/final/",
    }
    assert json.loads(hp["hotvect_parallel_execution"]) == {
        "worker_count": 8,
        "worker_index": 3,
    }
    assert hp["hotvect_unordered"] == "true"
    assert "hotvect_ordered" not in hp
    assert hp["hotvect_writer_num_shards"] == "5"
    assert submission["training_job_name"] == "parallel-job"
    assert submission["task_output_s3_uri"] == "s3://bucket/final/"


def test_submit_single_job_encode_defaults_to_ordered_hyperparameter(monkeypatch, tmp_path: Path):
    hv = _load_hv_module(monkeypatch)
    captured = {}

    class FakeAlgorithmPipelineContext:
        def __init__(self, **_kwargs):
            pass

    class FakeAlgorithmPipeline:
        def __init__(self, **_kwargs):
            self.algorithm_definition = {"hyperparameter_version": "hp-1"}
            self.parameter_version = "oneshot"
            self.last_test_time = date(2026, 4, 5)

        def hyperparameter_slug(self):
            return "hp-slug"

    class FakeExecutor:
        def __init__(self, algorithm_pipeline, training_job_definition, role_arn_to_assume):
            captured["job_def"] = training_job_definition
            self.algorithm_pipeline = algorithm_pipeline
            self.hyperparameters = training_job_definition["HyperParameters"]
            self.training_job_name = training_job_definition["TrainingJobName"]
            self.hyperparameters.setdefault("s3_uri_result_file", "s3://bucket/output/result.json")
            self.hyperparameters.setdefault("s3_uri_metadata", "s3://bucket/output/meta/")

        def run(self):
            return {"TrainingJobArn": "arn:aws:sagemaker:job/demo"}

        def build_submission_manifest(self, submission_response):
            return {
                "training_job_name": self.training_job_name,
                "training_job_arn": submission_response["TrainingJobArn"],
            }

    monkeypatch.setitem(
        sys.modules,
        "hotvect.pyhotvect",
        SimpleNamespace(
            AlgorithmPipeline=FakeAlgorithmPipeline,
            AlgorithmPipelineContext=FakeAlgorithmPipelineContext,
        ),
    )
    monkeypatch.setitem(sys.modules, "hotvect.evaluation.evaluation", SimpleNamespace(standard_evaluation=object()))
    monkeypatch.setitem(sys.modules, "hotvect.sagemaker", SimpleNamespace(SagemakerTrainingExecutor=FakeExecutor))
    monkeypatch.setattr(hv, "resolve_template_path", lambda *_args, **_kwargs: SimpleNamespace(path=None))
    monkeypatch.setattr(hv, "resolve_training_image", lambda **_kwargs: "training-image")
    monkeypatch.setattr(hv, "build_one_shot_training_job_name", lambda **_kwargs: "encode-job")
    monkeypatch.setattr(
        hv,
        "build_one_shot_effective_algorithm_definition",
        lambda *_args, **_kwargs: {
            "algorithm_name": "demo-algo",
            "hyperparameter_version": "hp-1",
        },
    )
    monkeypatch.setattr(
        hv,
        "build_training_job_definition",
        lambda **_kwargs: {
            "TrainingJobName": "encode-job",
            "OutputDataConfig": {"S3OutputPath": "s3://bucket/output"},
            "HyperParameters": {},
            "InputDataConfig": [],
        },
    )

    args = _base_args()
    args.algorithm_jar = tmp_path / "algo.jar"
    args.parameter_s3_uri = None
    args.job_parallelism = 1
    args.compression = "none"

    hv._submit_one_shot_sagemaker_job(task="encode", task_kind_short="encode", args=args)

    hp = captured["job_def"]["HyperParameters"]
    assert hp["hotvect_ordered"] == "true"
    assert "hotvect_parallel_execution" not in hp


def test_submit_single_job_encode_respects_explicit_unordered_flag(monkeypatch, tmp_path: Path):
    hv = _load_hv_module(monkeypatch)
    captured = {}

    class FakeAlgorithmPipelineContext:
        def __init__(self, **_kwargs):
            pass

    class FakeAlgorithmPipeline:
        def __init__(self, **_kwargs):
            self.algorithm_definition = {"hyperparameter_version": "hp-1"}
            self.parameter_version = "oneshot"
            self.last_test_time = date(2026, 4, 5)

        def hyperparameter_slug(self):
            return "hp-slug"

    class FakeExecutor:
        def __init__(self, algorithm_pipeline, training_job_definition, role_arn_to_assume):
            captured["job_def"] = training_job_definition
            self.algorithm_pipeline = algorithm_pipeline
            self.hyperparameters = training_job_definition["HyperParameters"]
            self.training_job_name = training_job_definition["TrainingJobName"]
            self.hyperparameters.setdefault("s3_uri_result_file", "s3://bucket/output/result.json")
            self.hyperparameters.setdefault("s3_uri_metadata", "s3://bucket/output/meta/")

        def run(self):
            return {"TrainingJobArn": "arn:aws:sagemaker:job/demo"}

        def build_submission_manifest(self, submission_response):
            return {
                "training_job_name": self.training_job_name,
                "training_job_arn": submission_response["TrainingJobArn"],
            }

    monkeypatch.setitem(
        sys.modules,
        "hotvect.pyhotvect",
        SimpleNamespace(
            AlgorithmPipeline=FakeAlgorithmPipeline,
            AlgorithmPipelineContext=FakeAlgorithmPipelineContext,
        ),
    )
    monkeypatch.setitem(sys.modules, "hotvect.evaluation.evaluation", SimpleNamespace(standard_evaluation=object()))
    monkeypatch.setitem(sys.modules, "hotvect.sagemaker", SimpleNamespace(SagemakerTrainingExecutor=FakeExecutor))
    monkeypatch.setattr(hv, "resolve_template_path", lambda *_args, **_kwargs: SimpleNamespace(path=None))
    monkeypatch.setattr(hv, "resolve_training_image", lambda **_kwargs: "training-image")
    monkeypatch.setattr(hv, "build_one_shot_training_job_name", lambda **_kwargs: "encode-job")
    monkeypatch.setattr(
        hv,
        "build_one_shot_effective_algorithm_definition",
        lambda *_args, **_kwargs: {
            "algorithm_name": "demo-algo",
            "hyperparameter_version": "hp-1",
        },
    )
    monkeypatch.setattr(
        hv,
        "build_training_job_definition",
        lambda **_kwargs: {
            "TrainingJobName": "encode-job",
            "OutputDataConfig": {"S3OutputPath": "s3://bucket/output"},
            "HyperParameters": {},
            "InputDataConfig": [],
        },
    )

    args = _base_args()
    args.algorithm_jar = tmp_path / "algo.jar"
    args.parameter_s3_uri = None
    args.job_parallelism = 1
    args.compression = "none"
    args.unordered = True

    hv._submit_one_shot_sagemaker_job(task="encode", task_kind_short="encode", args=args)

    hp = captured["job_def"]["HyperParameters"]
    assert hp["hotvect_unordered"] == "true"
    assert "hotvect_ordered" not in hp
    assert "hotvect_parallel_execution" not in hp


def test_submit_parallel_one_shot_reuses_preuploaded_algorithm_jar(monkeypatch, tmp_path: Path):
    hv = _load_hv_module(monkeypatch)
    captured = {}

    class FakeAlgorithmPipelineContext:
        def __init__(self, **_kwargs):
            pass

    class FakeAlgorithmPipeline:
        def __init__(self, **_kwargs):
            self.algorithm_definition = {"hyperparameter_version": "hp-1"}
            self.parameter_version = "oneshot"
            self.last_test_time = date(2026, 4, 5)

        def hyperparameter_slug(self):
            return "hp-slug"

    class FakeExecutor:
        def __init__(self, algorithm_pipeline, training_job_definition, role_arn_to_assume):
            captured["job_def"] = training_job_definition
            self.algorithm_pipeline = algorithm_pipeline
            self.hyperparameters = training_job_definition["HyperParameters"]
            self.training_job_name = training_job_definition["TrainingJobName"]
            self.hyperparameters.setdefault("s3_uri_result_file", "s3://bucket/output/result.json")
            self.hyperparameters.setdefault("s3_uri_metadata", "s3://bucket/output/meta/")

        def run(self):
            return {"TrainingJobArn": "arn:aws:sagemaker:job/demo"}

        def build_submission_manifest(self, submission_response):
            return {
                "training_job_name": self.training_job_name,
                "training_job_arn": submission_response["TrainingJobArn"],
            }

    monkeypatch.setitem(
        sys.modules,
        "hotvect.pyhotvect",
        SimpleNamespace(
            AlgorithmPipeline=FakeAlgorithmPipeline,
            AlgorithmPipelineContext=FakeAlgorithmPipelineContext,
        ),
    )
    monkeypatch.setitem(sys.modules, "hotvect.evaluation.evaluation", SimpleNamespace(standard_evaluation=object()))
    monkeypatch.setitem(sys.modules, "hotvect.sagemaker", SimpleNamespace(SagemakerTrainingExecutor=FakeExecutor))
    monkeypatch.setattr(hv, "resolve_template_path", lambda *_args, **_kwargs: SimpleNamespace(path=None))
    monkeypatch.setattr(hv, "resolve_training_image", lambda **_kwargs: "training-image")
    monkeypatch.setattr(hv, "build_one_shot_training_job_name", lambda **_kwargs: "parallel-job")
    monkeypatch.setattr(
        hv,
        "build_one_shot_effective_algorithm_definition",
        lambda *_args, **_kwargs: {
            "algorithm_name": "demo-algo",
            "hyperparameter_version": "hp-1",
        },
    )
    monkeypatch.setattr(
        hv,
        "build_training_job_definition",
        lambda **_kwargs: {
            "TrainingJobName": "parallel-job",
            "OutputDataConfig": {"S3OutputPath": "s3://bucket/output"},
            "HyperParameters": {},
            "InputDataConfig": [],
        },
    )

    args = _base_args()
    args.algorithm_jar = tmp_path / "algo.jar"
    args.parallel_preuploaded_algorithm_jar_s3_uri = "s3://bucket/shared/algo.jar"

    hv._submit_one_shot_sagemaker_job(task="audit", task_kind_short="audis00", args=args)

    hp = captured["job_def"]["HyperParameters"]
    assert hp["s3_uri_algorithm_jar"] == "s3://bucket/shared/algo.jar"


def _install_parallel_runner_fakes(monkeypatch, hv):
    class FakeS3Client:
        def __init__(self):
            self.puts = []
            self.uploads = []

        def put_object(self, Bucket, Key, Body, ContentType=None):
            del ContentType
            self.puts.append((Bucket, Key, Body))

        def upload_file(self, Filename, Bucket, Key):
            self.uploads.append((Filename, Bucket, Key))

    client = FakeS3Client()

    class FakeSession:
        def client(self, _name):
            return client

    monkeypatch.setattr(hv, "create_session", lambda _assume_role_arn=None: FakeSession())
    monkeypatch.setattr(hv, "build_one_shot_effective_algorithm_definition", lambda **_kwargs: {})
    monkeypatch.setattr(hv, "validate_parallel_dest_path_is_empty", lambda **_kwargs: None)
    monkeypatch.setattr(
        hv,
        "_best_effort_validate_parallel_source_prefix",
        lambda **_kwargs: {"status": "validated", "source_object_count": 1, "source_total_bytes": 42},
    )
    monkeypatch.setattr(hv, "generate_runid", lambda: "run-123")
    shard_plan = SimpleNamespace(
        index=0,
        input_s3_uri="s3://bucket/source/",
        expected_output={
            "type": "part-files",
            "uri_prefix": "s3://bucket/out/",
            "filename_glob": "part-00000-*.jsonl.gz",
        },
        training_job_name=None,
        s3_uri_result_file=None,
        s3_uri_metadata=None,
        task_output_s3_uri=None,
        sagemaker_output_s3_path=None,
        hyperparameter_slug=None,
    )
    monkeypatch.setattr(hv, "build_parallel_worker_plans", lambda **_kwargs: [shard_plan])
    monkeypatch.setattr(hv, "normalize_s3_prefix_uri", lambda uri: uri)
    monkeypatch.setattr(hv, "managed_manifest_uri", lambda _base, _run_id: "s3://bucket/managed/manifest.json")
    monkeypatch.setattr(
        hv,
        "managed_submission_state_uri",
        lambda _base, _run_id: "s3://bucket/managed/submission_state.json",
    )
    monkeypatch.setattr(hv, "managed_status_uri", lambda _base, _run_id: "s3://bucket/managed/status.json")
    monkeypatch.setattr(hv, "parallel_submission_pointer_uri", lambda _dest: "s3://bucket/out/_SUBMISSION.json")
    monkeypatch.setattr(
        hv,
        "build_submission_pointer",
        lambda **_kwargs: {
            "manifest_s3_uri": "s3://bucket/managed/manifest.json",
            "submission_state_s3_uri": "s3://bucket/managed/submission_state.json",
            "run_id": "run-123",
        },
    )
    monkeypatch.setattr(
        hv,
        "build_parallel_manifest",
        lambda **_kwargs: {
            "run_id": "run-123",
            "verify_command": _kwargs["verify_command"],
            "task": "predict",
            "dest_path": "s3://bucket/out/",
            "shards": [{"index": 0}],
        },
    )
    monkeypatch.setattr(
        hv,
        "build_parallel_submission_state",
        lambda **_kwargs: {
            "run_id": "run-123",
            "task": "predict",
            "dest_path": "s3://bucket/out/",
            "shards": [{"index": 0, "training_job_name": "parallel-job-0"}],
        },
    )
    monkeypatch.setattr(hv, "put_json_to_s3", lambda *_args, **_kwargs: None)
    monkeypatch.setattr(
        hv,
        "_submit_one_shot_sagemaker_job",
        lambda **_kwargs: {
            "training_job_name": "parallel-job-0",
            "s3_uri_result_file": "s3://bucket/managed/result.json",
            "s3_uri_metadata": "s3://bucket/managed/meta/",
        },
    )
    return shard_plan, client


class _FakeListObjectsPaginator:
    def __init__(self, *, pages=None, error=None):
        self.pages = pages or []
        self.error = error
        self.calls = []

    def paginate(self, **kwargs):
        self.calls.append(kwargs)
        if self.error:
            raise self.error
        return self.pages


class _FakeListObjectsS3Client:
    def __init__(self, paginator):
        self.paginator = paginator
        self.paginator_names = []

    def get_paginator(self, name):
        self.paginator_names.append(name)
        return self.paginator


def test_parallel_source_preflight_validates_usable_source_objects(monkeypatch):
    hv = _load_hv_module(monkeypatch)
    paginator = _FakeListObjectsPaginator(
        pages=[
            {
                "Contents": [
                    {"Key": "source/_SUCCESS", "Size": 0},
                    {"Key": "source/_temporary/part-00000.jsonl", "Size": 10},
                    {"Key": "source/part-00000.jsonl", "Size": 20},
                    {"Key": "source/nested/part-00001.jsonl", "Size": 22},
                    {"Key": "source/folder/", "Size": 0},
                ]
            }
        ]
    )
    s3_client = _FakeListObjectsS3Client(paginator)

    result = hv._best_effort_validate_parallel_source_prefix(
        s3_client=s3_client,
        source_s3_uri="s3://bucket/source/",
    )

    assert result == {"status": "validated", "source_object_count": 2, "source_total_bytes": 42}
    assert s3_client.paginator_names == ["list_objects_v2"]
    assert paginator.calls == [{"Bucket": "bucket", "Prefix": "source/"}]


def test_parallel_source_preflight_rejects_empty_or_control_only_prefix(monkeypatch):
    hv = _load_hv_module(monkeypatch)
    paginator = _FakeListObjectsPaginator(
        pages=[
            {
                "Contents": [
                    {"Key": "source/_SUCCESS", "Size": 0},
                    {"Key": "source/_temporary/part-00000.jsonl", "Size": 10},
                    {"Key": "source/folder/", "Size": 0},
                ]
            }
        ]
    )

    with pytest.raises(ValueError, match="No source objects found under s3://bucket/source/"):
        hv._best_effort_validate_parallel_source_prefix(
            s3_client=_FakeListObjectsS3Client(paginator),
            source_s3_uri="s3://bucket/source/",
        )


def test_parallel_source_preflight_skips_access_denied_listing(monkeypatch, caplog):
    hv = _load_hv_module(monkeypatch)
    error = hv.ClientError(
        {"Error": {"Code": "AccessDenied", "Message": "not allowed"}},
        "ListObjectsV2",
    )
    paginator = _FakeListObjectsPaginator(error=error)

    with caplog.at_level(logging.WARNING):
        result = hv._best_effort_validate_parallel_source_prefix(
            s3_client=_FakeListObjectsS3Client(paginator),
            source_s3_uri="s3://bucket/source/",
        )

    assert result == {"status": "skipped", "reason": "access_denied", "error_code": "AccessDenied"}
    assert (
        "Skipping parallel source-prefix validation because listing s3://bucket/source/ is not permitted" in caplog.text
    )


def test_parallel_runner_fails_before_submitting_when_source_preflight_finds_no_objects(monkeypatch):
    hv = _load_hv_module(monkeypatch)
    _install_parallel_runner_fakes(monkeypatch, hv)
    submitted = False

    def _raise_empty_source(**_kwargs):
        raise ValueError("No source objects found under s3://bucket/source/")

    def _capture_submit(**_kwargs):
        nonlocal submitted
        submitted = True
        return {}

    monkeypatch.setattr(hv, "_best_effort_validate_parallel_source_prefix", _raise_empty_source)
    monkeypatch.setattr(hv, "_submit_one_shot_sagemaker_job", _capture_submit)

    with pytest.raises(ValueError, match="No source objects found under s3://bucket/source/"):
        hv._run_parallel_one_shot_sagemaker_job(
            task="predict",
            task_kind_short="pred",
            command_name="predict",
            args=_base_args(),
        )

    assert submitted is False


def test_parallel_runner_logs_verify_command_for_no_wait(monkeypatch, caplog):
    hv = _load_hv_module(monkeypatch)
    _install_parallel_runner_fakes(monkeypatch, hv)
    args = _base_args()
    args.no_wait = True
    expected_verify = hv._parallel_verify_command("predict", args)

    with caplog.at_level(logging.INFO):
        hv._run_parallel_one_shot_sagemaker_job(
            task="predict",
            task_kind_short="pred",
            command_name="predict",
            args=args,
        )

    assert "To finalize the run later and write _SUCCESS, use:" in caplog.text
    assert expected_verify in caplog.text
    assert "--no-wait was set, so the command is exiting after submission." in caplog.text


def test_parallel_runner_uploads_shared_algorithm_jar_via_standard_s3_upload(monkeypatch):
    hv = _load_hv_module(monkeypatch)
    _shard_plan, s3_client = _install_parallel_runner_fakes(monkeypatch, hv)
    monkeypatch.setattr(
        hv,
        "wait_for_parallel_manifest",
        lambda **_kwargs: {"complete": True, "success_marker_uri": "s3://bucket/out/_SUCCESS"},
    )
    args = _base_args()

    hv._run_parallel_one_shot_sagemaker_job(
        task="predict",
        task_kind_short="pred",
        command_name="predict",
        args=args,
    )

    assert s3_client.uploads == [("algo.jar", "bucket", "managed/_parallel_oneshot_runs/run-123/algo.jar")]


def test_parallel_runner_logs_verify_command_while_waiting(monkeypatch, caplog):
    hv = _load_hv_module(monkeypatch)
    _install_parallel_runner_fakes(monkeypatch, hv)
    monkeypatch.setattr(
        hv,
        "wait_for_parallel_manifest",
        lambda **_kwargs: {"complete": True, "success_marker_uri": "s3://bucket/out/_SUCCESS"},
    )
    args = _base_args()
    expected_verify = hv._parallel_verify_command("predict", args)

    with caplog.at_level(logging.INFO):
        hv._run_parallel_one_shot_sagemaker_job(
            task="predict",
            task_kind_short="pred",
            command_name="predict",
            args=args,
        )

    assert "To finalize the run later and write _SUCCESS, use:" in caplog.text
    assert "Waiting for all shard jobs to finish. Press Ctrl-C to stop waiting; later run:" in caplog.text
    assert expected_verify in caplog.text


def test_parallel_runner_leaves_predict_unordered_by_default(monkeypatch):
    hv = _load_hv_module(monkeypatch)
    captured = {}

    class FakeS3Client:
        def put_object(self, Bucket, Key, Body, ContentType=None):
            del Bucket, Key, Body, ContentType

        def upload_file(self, Filename, Bucket, Key):
            del Filename, Bucket, Key

    class FakeSession:
        def client(self, _name):
            return FakeS3Client()

    monkeypatch.setattr(hv, "create_session", lambda _assume_role_arn=None: FakeSession())
    monkeypatch.setattr(hv, "build_one_shot_effective_algorithm_definition", lambda **_kwargs: {})
    monkeypatch.setattr(hv, "validate_parallel_dest_path_is_empty", lambda **_kwargs: None)
    monkeypatch.setattr(
        hv,
        "_best_effort_validate_parallel_source_prefix",
        lambda **_kwargs: {"status": "validated", "source_object_count": 1, "source_total_bytes": 42},
    )
    monkeypatch.setattr(hv, "generate_runid", lambda: "run-123")

    def _capture_build_parallel_worker_plans(**_kwargs):
        captured["planned_source_s3_uri"] = _kwargs["source_s3_uri"]
        captured["planned_job_parallelism"] = _kwargs["job_parallelism"]
        return [
            SimpleNamespace(
                index=0,
                training_job_name=None,
                s3_uri_result_file=None,
                s3_uri_metadata=None,
                task_output_s3_uri=None,
                sagemaker_output_s3_path=None,
                hyperparameter_slug=None,
            )
        ]

    monkeypatch.setattr(
        hv,
        "build_parallel_worker_plans",
        _capture_build_parallel_worker_plans,
    )
    monkeypatch.setattr(hv, "managed_manifest_uri", lambda _base, _run_id: "s3://bucket/managed/manifest.json")
    monkeypatch.setattr(
        hv,
        "managed_submission_state_uri",
        lambda _base, _run_id: "s3://bucket/managed/submission_state.json",
    )
    monkeypatch.setattr(hv, "managed_status_uri", lambda _base, _run_id: "s3://bucket/managed/status.json")
    monkeypatch.setattr(hv, "parallel_submission_pointer_uri", lambda _dest: "s3://bucket/out/_SUBMISSION.json")
    monkeypatch.setattr(
        hv,
        "build_submission_pointer",
        lambda **_kwargs: {
            "manifest_s3_uri": "s3://bucket/managed/manifest.json",
            "submission_state_s3_uri": "s3://bucket/managed/submission_state.json",
            "run_id": "run-123",
        },
    )
    monkeypatch.setattr(
        hv,
        "build_parallel_manifest",
        lambda **_kwargs: {
            "run_id": "run-123",
            "verify_command": _kwargs["verify_command"],
            "task": "predict",
            "dest_path": "s3://bucket/out/",
            "shards": [{"index": 0}],
        },
    )
    monkeypatch.setattr(
        hv,
        "build_parallel_submission_state",
        lambda **_kwargs: {
            "run_id": "run-123",
            "task": "predict",
            "dest_path": "s3://bucket/out/",
            "shards": [{"index": 0, "training_job_name": "parallel-job-0"}],
        },
    )
    monkeypatch.setattr(hv, "put_json_to_s3", lambda *_args, **_kwargs: None)

    def _capture_submit(**_kwargs):
        captured["submitted_source_s3_uri"] = _kwargs["args"].source_s3_uri
        captured["ordered"] = _kwargs["args"].ordered
        captured["unordered"] = _kwargs["args"].unordered
        return {
            "training_job_name": "parallel-job-0",
            "s3_uri_result_file": "s3://bucket/managed/result.json",
            "s3_uri_metadata": "s3://bucket/managed/meta/",
        }

    monkeypatch.setattr(hv, "_submit_one_shot_sagemaker_job", _capture_submit)
    monkeypatch.setattr(
        hv,
        "wait_for_parallel_manifest",
        lambda **_kwargs: {"complete": True, "success_marker_uri": "s3://bucket/out/_SUCCESS"},
    )

    args = _base_args()
    args.source_s3_uri = "s3://bucket/source"
    hv._run_parallel_one_shot_sagemaker_job(
        task="predict",
        task_kind_short="pred",
        command_name="predict",
        args=args,
    )

    assert captured["ordered"] is False
    assert captured["unordered"] is True
    assert captured["planned_source_s3_uri"] == "s3://bucket/source/"
    assert captured["planned_job_parallelism"] == 4
    assert captured["submitted_source_s3_uri"] == "s3://bucket/source/"


def test_verify_parallel_runner_rejects_unsubmitted_shards(monkeypatch):
    hv = _load_hv_module(monkeypatch)

    class FakeSession:
        def client(self, name):
            return object()

    monkeypatch.setattr(hv, "create_session", lambda _assume_role_arn=None: FakeSession())
    monkeypatch.setattr(
        hv,
        "load_parallel_manifest_for_dest",
        lambda **_kwargs: {"run_id": "run-123", "task": "predict", "dest_path": "s3://bucket/out/", "shards": []},
    )
    monkeypatch.setattr(
        hv,
        "load_parallel_submission_state_for_dest",
        lambda **_kwargs: {"run_id": "run-123", "task": "predict", "dest_path": "s3://bucket/out/", "shards": []},
    )
    monkeypatch.setattr(
        hv,
        "verify_parallel_manifest",
        lambda **_kwargs: {
            "complete": False,
            "success_marker_uri": "s3://bucket/out/_SUCCESS",
            "failed_jobs": [],
            "running_jobs": [],
            "unsubmitted_shards": [1, 3],
        },
    )
    monkeypatch.setattr(hv, "put_json_to_s3", lambda *_args, **_kwargs: None)
    monkeypatch.setattr(hv, "managed_status_uri", lambda _base, _run_id: "s3://bucket/managed/status.json")

    args = _base_args()
    args.verify = True
    args.job_parallelism = 1

    with pytest.raises(ValueError, match="Missing training-job names for shards: 1, 3"):
        hv._verify_parallel_one_shot_sagemaker_job(command_name="predict", args=args)


def test_parallel_runner_logs_recovery_command_on_keyboard_interrupt(monkeypatch, caplog):
    hv = _load_hv_module(monkeypatch)
    _install_parallel_runner_fakes(monkeypatch, hv)

    def _interrupt(**_kwargs):
        raise KeyboardInterrupt()

    monkeypatch.setattr(hv, "wait_for_parallel_manifest", _interrupt)
    args = _base_args()
    expected_verify = hv._parallel_verify_command("predict", args)

    with caplog.at_level(logging.INFO):
        with pytest.raises(KeyboardInterrupt):
            hv._run_parallel_one_shot_sagemaker_job(
                task="predict",
                task_kind_short="pred",
                command_name="predict",
                args=args,
            )

    assert "Local process interrupted. Remote SageMaker jobs keep running." in caplog.text
    assert "Run the following later to verify/finalize and write _SUCCESS:" in caplog.text
    assert expected_verify in caplog.text


def test_encode_parallel_runner_defaults_compression_to_none_when_flag_absent(monkeypatch):
    hv = _load_hv_module(monkeypatch)
    _install_parallel_runner_fakes(monkeypatch, hv)
    monkeypatch.setattr(
        hv,
        "wait_for_parallel_manifest",
        lambda **_kwargs: {"complete": True, "success_marker_uri": "s3://bucket/out/_SUCCESS"},
    )
    args = _base_args()
    args.parameter_s3_uri = None
    delattr(args, "compression")

    hv._run_parallel_one_shot_sagemaker_job(
        task="encode",
        task_kind_short="encode",
        command_name="encode",
        args=args,
    )
