import importlib.util
import json
import sys
from importlib.machinery import SourceFileLoader
from pathlib import Path
from types import ModuleType, SimpleNamespace

import pytest

from hotvect.algorithm_definition_overrides import apply_algorithm_definition_override


def _load_hv_module():
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "bin" / "hv"
        if candidate.exists():
            loader = SourceFileLoader("hv_cli_sagemaker_perf", str(candidate))
            spec = importlib.util.spec_from_loader(loader.name, loader)
            assert spec is not None
            module = importlib.util.module_from_spec(spec)
            loader.exec_module(module)
            return module
    raise FileNotFoundError("Could not locate bin/hv relative to test file")


def _make_fake_algorithm_pipeline(base_definition, captured):
    class FakeAlgorithmPipeline:
        def __init__(self, **kwargs):
            captured["algorithm_definition_arg"] = kwargs["algorithm_definition"]
            if isinstance(kwargs["algorithm_definition"], tuple):
                _, override_fragment = kwargs["algorithm_definition"]
                self.algorithm_definition = apply_algorithm_definition_override(base_definition, override_fragment)
            else:
                self.algorithm_definition = kwargs["algorithm_definition"]

        def hyperparameter_slug(self):
            return "perf-slug"

    return FakeAlgorithmPipeline


def _install_fake_backtest_module(monkeypatch):
    def _recursive_update(target, patch):
        for key, value in patch.items():
            if isinstance(target.get(key), dict) and isinstance(value, dict):
                _recursive_update(target[key], value)
            else:
                target[key] = value

    def _legacy_to_overrides(params):
        if not params:
            return None
        overrides = {}
        if "instance_type" in params or "volume_size_in_gb" in params:
            overrides["ResourceConfig"] = {}
            if "instance_type" in params:
                overrides["ResourceConfig"]["InstanceType"] = params["instance_type"]
            if "volume_size_in_gb" in params:
                overrides["ResourceConfig"]["VolumeSizeInGB"] = params["volume_size_in_gb"]
        if "max_runtime" in params:
            overrides["StoppingCondition"] = {"MaxRuntimeInSeconds": params["max_runtime"]}
        return overrides

    monkeypatch.setitem(
        sys.modules,
        "hotvect.backtest",
        SimpleNamespace(
            apply_sagemaker_job_overrides=lambda job_definition, overrides: _recursive_update(
                job_definition, overrides
            ),
            legacy_sagemaker_params_to_overrides=_legacy_to_overrides,
        ),
    )


def _make_fake_definition_s3_client(algorithm_definition: dict) -> SimpleNamespace:
    return SimpleNamespace(
        download_file=lambda Bucket, Key, Filename: Path(Filename).write_text(json.dumps(algorithm_definition)),
        download_fileobj=lambda Bucket, Key, Fileobj: Fileobj.write(json.dumps(algorithm_definition).encode("utf-8")),
    )


def test_submit_one_shot_sagemaker_job_includes_perf_pacing_hyperparameters(monkeypatch, tmp_path: Path):
    hv = _load_hv_module()
    captured = {}
    base_definition = {
        "algorithm_name": "demo-algo",
        "algorithm_version": "1.2.3",
        "hyperparameter_version": "perf-hp",
        "dependencies": ["child-model"],
    }

    class FakeAlgorithmPipelineContext:
        def __init__(self, **_kwargs):
            pass

    class FakeExecutor:
        def __init__(self, algorithm_pipeline, training_job_definition, role_arn_to_assume):
            captured["job_def"] = training_job_definition
            captured["role_arn_to_assume"] = role_arn_to_assume
            self.hyperparameters = training_job_definition["HyperParameters"]
            self.training_job_name = training_job_definition["TrainingJobName"]

        def run(self):
            captured["ran"] = True

    monkeypatch.setitem(
        sys.modules,
        "hotvect.pyhotvect",
        SimpleNamespace(
            AlgorithmPipeline=_make_fake_algorithm_pipeline(base_definition, captured),
            AlgorithmPipelineContext=FakeAlgorithmPipelineContext,
        ),
    )
    monkeypatch.setitem(sys.modules, "hotvect.evaluation.evaluation", SimpleNamespace(standard_evaluation=object()))
    monkeypatch.setitem(sys.modules, "hotvect.sagemaker", SimpleNamespace(SagemakerTrainingExecutor=FakeExecutor))
    _install_fake_backtest_module(monkeypatch)
    monkeypatch.setattr(hv, "resolve_template_path", lambda *_args, **_kwargs: SimpleNamespace(path=None))
    monkeypatch.setattr(hv, "resolve_training_image", lambda **_kwargs: "training-image")
    monkeypatch.setattr(hv, "build_one_shot_training_job_name", lambda **_kwargs: "perf-job")
    monkeypatch.setattr(
        hv,
        "build_one_shot_effective_algorithm_definition",
        lambda *_args, **_kwargs: {
            **base_definition,
            "hotvect_execution_parameters": {"with_parameter": "s3://bucket/params.zip"},
        },
    )
    monkeypatch.setattr(
        hv,
        "build_training_job_definition",
        lambda **_kwargs: {
            "TrainingJobName": "perf-job",
            "OutputDataConfig": {"S3OutputPath": "s3://bucket/output"},
            "AlgorithmSpecification": {"TrainingImage": "training-image"},
            "ResourceConfig": {"InstanceType": "ml.m5.large"},
            "HyperParameters": {},
            "InputDataConfig": [],
        },
    )

    args = SimpleNamespace(
        sagemaker_job_prefix="ml-exp-test",
        source_s3_uri="s3://bucket/source",
        algorithm_jar=tmp_path / "algo.jar",
        algorithm_name="demo-algo",
        sagemaker_config=None,
        role_arn="arn:aws:iam::123456789012:role/TestRole",
        s3_output_base="s3://bucket/output",
        instance_type="ml.m5.large",
        volume_gb=None,
        max_runtime_seconds=None,
        training_image=None,
        parameter_s3_uri="s3://bucket/params.zip",
        include_feature_store_responses=False,
        samples=321,
        sample_pool_size=6543,
        target_rps=120.0,
        target_throughput_fraction=0.5,
        workload_mode="batch",
        assume_role_arn=None,
    )

    hv._submit_one_shot_sagemaker_job(task="performance-test", task_kind_short="perf", args=args)

    hp = captured["job_def"]["HyperParameters"]
    assert hp["hotvect_task"] == "performance-test"
    assert hp["hotvect_samples"] == "321"
    assert hp["hotvect_sample_pool_size"] == "6543"
    assert hp["hotvect_target_rps"] == "120.0"
    assert hp["hotvect_target_throughput_fraction"] == "0.5"
    assert hp["hotvect_workload_mode"] == "batch"
    assert hp["s3_uri_parameter_zip"] == "s3://bucket/params.zip"
    assert hp["hotvect_source_s3_uri"] == "s3://bucket/source/"
    assert hp["hotvect_instance_type"] == "ml.m5.large"
    assert hp["hotvect_training_image"] == "training-image"
    assert hp["hotvect_sagemaker_output_s3_uri"] == "s3://bucket/output/perf-job"
    assert captured["ran"] is True


def test_submit_one_shot_sagemaker_job_pins_top_level_with_parameter_without_override(monkeypatch, tmp_path: Path):
    hv = _load_hv_module()
    captured = {}
    base_definition = {
        "algorithm_name": "demo-algo",
        "algorithm_version": "1.2.3",
        "hyperparameter_version": "perf-hp",
        "dependencies": ["child-model"],
    }

    class FakeAlgorithmPipelineContext:
        def __init__(self, **_kwargs):
            pass

    class FakeExecutor:
        def __init__(self, algorithm_pipeline, training_job_definition, role_arn_to_assume):
            captured["executor_algorithm_definition"] = algorithm_pipeline.algorithm_definition
            self.hyperparameters = training_job_definition["HyperParameters"]
            self.training_job_name = training_job_definition["TrainingJobName"]

        def run(self):
            captured["ran"] = True

    monkeypatch.setitem(
        sys.modules,
        "hotvect.pyhotvect",
        SimpleNamespace(
            AlgorithmPipeline=_make_fake_algorithm_pipeline(base_definition, captured),
            AlgorithmPipelineContext=FakeAlgorithmPipelineContext,
        ),
    )
    monkeypatch.setitem(sys.modules, "hotvect.evaluation.evaluation", SimpleNamespace(standard_evaluation=object()))
    monkeypatch.setitem(sys.modules, "hotvect.sagemaker", SimpleNamespace(SagemakerTrainingExecutor=FakeExecutor))
    _install_fake_backtest_module(monkeypatch)
    monkeypatch.setattr(hv, "resolve_template_path", lambda *_args, **_kwargs: SimpleNamespace(path=None))
    monkeypatch.setattr(hv, "resolve_training_image", lambda **_kwargs: "training-image")
    monkeypatch.setattr(hv, "build_one_shot_training_job_name", lambda **_kwargs: "perf-job")
    monkeypatch.setattr(
        hv,
        "build_one_shot_effective_algorithm_definition",
        lambda *_args, **_kwargs: {
            **base_definition,
            "hotvect_execution_parameters": {"with_parameter": "s3://bucket/params.zip"},
        },
    )
    monkeypatch.setattr(
        hv,
        "build_training_job_definition",
        lambda **_kwargs: {
            "TrainingJobName": "perf-job",
            "OutputDataConfig": {"S3OutputPath": "s3://bucket/output"},
            "HyperParameters": {},
            "InputDataConfig": [],
        },
    )

    args = SimpleNamespace(
        sagemaker_job_prefix="ml-exp-test",
        source_s3_uri="s3://bucket/source",
        algorithm_jar=tmp_path / "algo.jar",
        algorithm_name="demo-algo",
        algorithm_override=None,
        sagemaker_config=None,
        role_arn="arn:aws:iam::123456789012:role/TestRole",
        s3_output_base="s3://bucket/output",
        instance_type="ml.m5.large",
        volume_gb=None,
        max_runtime_seconds=None,
        training_image=None,
        parameter_s3_uri="s3://bucket/params.zip",
        include_feature_store_responses=False,
        samples=321,
        target_rps=120.0,
        target_throughput_fraction=0.5,
        workload_mode="batch",
        assume_role_arn=None,
    )

    hv._submit_one_shot_sagemaker_job(task="performance-test", task_kind_short="perf", args=args)

    algorithm_definition_arg = captured["algorithm_definition_arg"]
    assert algorithm_definition_arg == {
        **base_definition,
        "hotvect_execution_parameters": {"with_parameter": "s3://bucket/params.zip"},
    }
    assert (
        captured["executor_algorithm_definition"]["hotvect_execution_parameters"]["with_parameter"]
        == "s3://bucket/params.zip"
    )
    assert captured["ran"] is True


def test_submit_one_shot_sagemaker_job_uses_override_fragment_when_override_is_set(monkeypatch, tmp_path: Path):
    hv = _load_hv_module()
    captured = {}
    base_definition = {
        "algorithm_name": "demo-algo",
        "algorithm_version": "1.2.3",
        "hyperparameter_version": "perf-hp",
        "dependencies": ["child-model"],
    }
    override_path = tmp_path / "override.json"
    override_path.write_text(json.dumps({"training_lag_days": 7}))

    class FakeAlgorithmPipelineContext:
        def __init__(self, **_kwargs):
            pass

    class FakeExecutor:
        def __init__(self, algorithm_pipeline, training_job_definition, role_arn_to_assume):
            captured["job_def"] = training_job_definition
            captured["role_arn_to_assume"] = role_arn_to_assume
            captured["executor_algorithm_definition"] = algorithm_pipeline.algorithm_definition
            self.hyperparameters = training_job_definition["HyperParameters"]
            self.training_job_name = training_job_definition["TrainingJobName"]

        def run(self):
            captured["ran"] = True

    monkeypatch.setitem(
        sys.modules,
        "hotvect.pyhotvect",
        SimpleNamespace(
            AlgorithmPipeline=_make_fake_algorithm_pipeline(base_definition, captured),
            AlgorithmPipelineContext=FakeAlgorithmPipelineContext,
        ),
    )
    monkeypatch.setitem(sys.modules, "hotvect.evaluation.evaluation", SimpleNamespace(standard_evaluation=object()))
    monkeypatch.setitem(sys.modules, "hotvect.sagemaker", SimpleNamespace(SagemakerTrainingExecutor=FakeExecutor))
    _install_fake_backtest_module(monkeypatch)
    monkeypatch.setattr(hv, "resolve_template_path", lambda *_args, **_kwargs: SimpleNamespace(path=None))
    monkeypatch.setattr(hv, "resolve_training_image", lambda **_kwargs: "training-image")
    monkeypatch.setattr(hv, "build_one_shot_training_job_name", lambda **_kwargs: "perf-job")
    monkeypatch.setattr(
        hv,
        "build_one_shot_effective_algorithm_definition",
        lambda *_args, **_kwargs: {
            **base_definition,
            "training_lag_days": 7,
            "hotvect_execution_parameters": {"with_parameter": "s3://bucket/params.zip"},
        },
    )
    monkeypatch.setattr(
        hv,
        "build_training_job_definition",
        lambda **_kwargs: {
            "TrainingJobName": "perf-job",
            "OutputDataConfig": {"S3OutputPath": "s3://bucket/output"},
            "HyperParameters": {},
            "InputDataConfig": [],
        },
    )

    args = SimpleNamespace(
        sagemaker_job_prefix="ml-exp-test",
        source_s3_uri="s3://bucket/source",
        algorithm_jar=tmp_path / "algo.jar",
        algorithm_name="demo-algo",
        algorithm_override=str(override_path),
        sagemaker_config=None,
        role_arn="arn:aws:iam::123456789012:role/TestRole",
        s3_output_base="s3://bucket/output",
        instance_type="ml.m5.large",
        volume_gb=None,
        max_runtime_seconds=None,
        training_image=None,
        parameter_s3_uri="s3://bucket/params.zip",
        include_feature_store_responses=False,
        samples=321,
        target_rps=120.0,
        target_throughput_fraction=0.5,
        workload_mode="batch",
        assume_role_arn=None,
    )

    hv._submit_one_shot_sagemaker_job(task="performance-test", task_kind_short="perf", args=args)

    algorithm_definition_arg = captured["algorithm_definition_arg"]
    assert algorithm_definition_arg == {
        **base_definition,
        "training_lag_days": 7,
        "hotvect_execution_parameters": {"with_parameter": "s3://bucket/params.zip"},
    }
    assert captured["executor_algorithm_definition"]["training_lag_days"] == 7
    assert (
        captured["executor_algorithm_definition"]["hotvect_execution_parameters"]["with_parameter"]
        == "s3://bucket/params.zip"
    )
    assert captured["ran"] is True


def test_run_task_adds_perf_pacing_flags(monkeypatch, tmp_path: Path):
    import hotvect.sagemaker_tasks as st

    captured = {}

    def _capture_runshell(cmd):
        captured["cmd"] = cmd

    monkeypatch.setattr(st, "runshell", _capture_runshell)

    source_dir = tmp_path / "source"
    metadata_dir = tmp_path / "meta"
    output_dir = tmp_path / "out"
    source_dir.mkdir()
    metadata_dir.mkdir()
    output_dir.mkdir()

    req = st.OneShotTaskRequest(
        task="performance-test",
        source_dir=source_dir,
        metadata_dir=metadata_dir,
        output_dir=output_dir,
        task_output_s3_uri="s3://bucket/output",
        samples=222,
        sample_pool_size=3333,
        target_rps=120.0,
        target_throughput_fraction=0.5,
        workload_mode="batch",
        algorithm_definition={"algorithm_name": "demo-algo"},
    )

    st._run_task(
        req,
        local_algorithm_jar=tmp_path / "algo.jar",
        local_parameter_zip=tmp_path / "params.zip",
    )

    cmd = captured["cmd"]
    assert "--samples" in cmd
    assert cmd[cmd.index("--samples") + 1] == "222"
    assert "--sample-pool-size" in cmd
    assert cmd[cmd.index("--sample-pool-size") + 1] == "3333"
    assert "--target-rps" in cmd
    assert cmd[cmd.index("--target-rps") + 1] == "120.0"
    assert "--target-throughput-fraction" in cmd
    assert cmd[cmd.index("--target-throughput-fraction") + 1] == "0.5"
    assert "--workload-mode" in cmd
    assert cmd[cmd.index("--workload-mode") + 1] == "batch"


def test_run_task_for_encode_includes_parameter_zip(monkeypatch, tmp_path: Path):
    import hotvect.sagemaker_tasks as st

    captured = {}

    def _capture_runshell(cmd):
        captured["cmd"] = cmd

    monkeypatch.setattr(st, "runshell", _capture_runshell)

    source_dir = tmp_path / "source"
    metadata_dir = tmp_path / "meta"
    output_dir = tmp_path / "out"
    source_dir.mkdir()
    metadata_dir.mkdir()
    output_dir.mkdir()

    req = st.OneShotTaskRequest(
        task="encode",
        source_dir=source_dir,
        metadata_dir=metadata_dir,
        output_dir=output_dir,
        task_output_s3_uri=None,
        samples=None,
        target_rps=None,
        target_throughput_fraction=None,
        workload_mode=None,
        algorithm_definition={"algorithm_name": "demo-algo"},
    )

    st._run_task(
        req,
        local_algorithm_jar=tmp_path / "algo.jar",
        local_parameter_zip=tmp_path / "params.zip",
    )

    cmd = captured["cmd"]
    assert "--ordered" not in cmd
    assert "--parameters" in cmd
    assert cmd[cmd.index("--parameters") + 1] == str(tmp_path / "params.zip")


def test_run_task_for_encode_appends_ordered_only_when_requested(monkeypatch, tmp_path: Path):
    import hotvect.sagemaker_tasks as st

    captured = {}

    def _capture_runshell(cmd):
        captured["cmd"] = cmd

    monkeypatch.setattr(st, "runshell", _capture_runshell)

    source_dir = tmp_path / "source"
    metadata_dir = tmp_path / "meta"
    output_dir = tmp_path / "out"
    source_dir.mkdir()
    metadata_dir.mkdir()
    output_dir.mkdir()

    req = st.OneShotTaskRequest(
        task="encode",
        source_dir=source_dir,
        metadata_dir=metadata_dir,
        output_dir=output_dir,
        task_output_s3_uri=None,
        samples=None,
        target_rps=None,
        target_throughput_fraction=None,
        workload_mode=None,
        algorithm_definition={"algorithm_name": "demo-algo"},
        ordered=True,
    )

    st._run_task(
        req,
        local_algorithm_jar=tmp_path / "algo.jar",
        local_parameter_zip=None,
    )

    assert "--ordered" in captured["cmd"]


def test_run_task_for_encode_appends_unordered_and_writer_shards_when_requested(monkeypatch, tmp_path: Path):
    import hotvect.sagemaker_tasks as st

    captured = {}

    def _capture_runshell(cmd):
        captured["cmd"] = cmd

    monkeypatch.setattr(st, "runshell", _capture_runshell)

    source_dir = tmp_path / "source"
    metadata_dir = tmp_path / "meta"
    output_dir = tmp_path / "out"
    source_dir.mkdir()
    metadata_dir.mkdir()
    output_dir.mkdir()

    req = st.OneShotTaskRequest(
        task="encode",
        source_dir=source_dir,
        metadata_dir=metadata_dir,
        output_dir=output_dir,
        task_output_s3_uri=None,
        samples=None,
        target_rps=None,
        target_throughput_fraction=None,
        workload_mode=None,
        algorithm_definition={"algorithm_name": "demo-algo"},
        unordered=True,
        writer_num_shards=8,
    )

    st._run_task(
        req,
        local_algorithm_jar=tmp_path / "algo.jar",
        local_parameter_zip=None,
    )

    assert "--unordered" in captured["cmd"]
    assert "--writer-num-shards" in captured["cmd"]
    assert "8" in captured["cmd"]
    assert "--ordered" not in captured["cmd"]


def test_run_task_for_encode_omits_parameter_zip_when_absent(monkeypatch, tmp_path: Path):
    import hotvect.sagemaker_tasks as st

    captured = {}

    def _capture_runshell(cmd):
        captured["cmd"] = cmd

    monkeypatch.setattr(st, "runshell", _capture_runshell)

    source_dir = tmp_path / "source"
    metadata_dir = tmp_path / "meta"
    output_dir = tmp_path / "out"
    source_dir.mkdir()
    metadata_dir.mkdir()
    output_dir.mkdir()

    req = st.OneShotTaskRequest(
        task="encode",
        source_dir=source_dir,
        metadata_dir=metadata_dir,
        output_dir=output_dir,
        task_output_s3_uri=None,
        samples=None,
        target_rps=None,
        target_throughput_fraction=None,
        workload_mode=None,
        algorithm_definition={"algorithm_name": "demo-algo"},
    )

    st._run_task(
        req,
        local_algorithm_jar=tmp_path / "algo.jar",
        local_parameter_zip=None,
    )

    assert "--parameters" not in captured["cmd"]


def test_run_task_for_predict_omits_parameter_zip_when_absent(monkeypatch, tmp_path: Path):
    import hotvect.sagemaker_tasks as st

    captured = {}

    def _capture_runshell(cmd):
        captured["cmd"] = cmd

    monkeypatch.setattr(st, "runshell", _capture_runshell)

    source_dir = tmp_path / "source"
    metadata_dir = tmp_path / "meta"
    output_dir = tmp_path / "out"
    source_dir.mkdir()
    metadata_dir.mkdir()
    output_dir.mkdir()

    req = st.OneShotTaskRequest(
        task="predict",
        source_dir=source_dir,
        metadata_dir=metadata_dir,
        output_dir=output_dir,
        task_output_s3_uri=None,
        samples=None,
        target_rps=None,
        target_throughput_fraction=None,
        workload_mode=None,
        algorithm_definition={"algorithm_name": "demo-algo"},
    )

    st._run_task(
        req,
        local_algorithm_jar=tmp_path / "algo.jar",
        local_parameter_zip=None,
    )

    assert "--parameters" not in captured["cmd"]


def test_run_task_for_parallel_predict_skips_when_worker_has_no_assigned_source(monkeypatch, tmp_path: Path):
    import hotvect.sagemaker_tasks as st

    monkeypatch.setattr(st, "stable_shard_index", lambda *_args, **_kwargs: 0)

    source_dir = tmp_path / "source"
    metadata_dir = tmp_path / "meta"
    output_dir = tmp_path / "out"
    source_dir.mkdir()
    metadata_dir.mkdir()
    output_dir.mkdir()
    (source_dir / "input.jsonl").write_text('{"example_id":"a"}\n', encoding="utf-8")

    req = st.OneShotTaskRequest(
        task="predict",
        source_dir=source_dir,
        metadata_dir=metadata_dir,
        output_dir=output_dir,
        task_output_s3_uri=None,
        samples=None,
        target_rps=None,
        target_throughput_fraction=None,
        workload_mode=None,
        algorithm_definition={"algorithm_name": "demo-algo"},
        parallel_worker_count=2,
        parallel_worker_index=1,
    )

    metadata = st._run_task(
        req,
        local_algorithm_jar=tmp_path / "algo.jar",
        local_parameter_zip=None,
    )

    assert metadata["skipped"] is True
    assert metadata["source_files_assigned"] == 0
    assert metadata["parallel_worker_index"] == 1


def test_run_task_for_parallel_predict_fails_when_source_is_empty(tmp_path: Path):
    import hotvect.sagemaker_tasks as st

    source_dir = tmp_path / "source"
    metadata_dir = tmp_path / "meta"
    output_dir = tmp_path / "out"
    source_dir.mkdir()
    metadata_dir.mkdir()
    output_dir.mkdir()

    req = st.OneShotTaskRequest(
        task="predict",
        source_dir=source_dir,
        metadata_dir=metadata_dir,
        output_dir=output_dir,
        task_output_s3_uri=None,
        samples=None,
        target_rps=None,
        target_throughput_fraction=None,
        workload_mode=None,
        algorithm_definition={"algorithm_name": "demo-algo"},
        parallel_worker_count=2,
        parallel_worker_index=1,
    )

    with pytest.raises(ValueError, match="No source files found"):
        st._run_task(
            req,
            local_algorithm_jar=tmp_path / "algo.jar",
            local_parameter_zip=None,
        )


def test_upload_parallel_public_output_writes_predict_parts_without_merge(monkeypatch, tmp_path: Path):
    import gzip

    import hotvect.sagemaker_tasks as st

    output_dir = tmp_path / "out"
    prediction_dir = output_dir / "prediction"
    prediction_dir.mkdir(parents=True)
    (prediction_dir / "part-00000.jsonl").write_text('{"example_id":"a"}\n', encoding="utf-8")
    (prediction_dir / "part-00001.jsonl").write_text('{"example_id":"b"}\n', encoding="utf-8")

    req = st.OneShotTaskRequest(
        task="predict",
        source_dir=tmp_path / "source",
        metadata_dir=tmp_path / "meta",
        output_dir=output_dir,
        task_output_s3_uri="s3://bucket/final/",
        samples=None,
        target_rps=None,
        target_throughput_fraction=None,
        workload_mode=None,
        algorithm_definition={"algorithm_name": "demo-algo"},
        parallel_worker_index=3,
        compression="gzip",
    )

    uploads = []

    def _capture_upload(local_file_path: str, s3_target_uri: str, _client, *, fail_fast: bool = False) -> None:
        uploads.append((s3_target_uri, Path(local_file_path).read_bytes(), fail_fast))

    monkeypatch.setattr(st, "_upload_file_to_s3", _capture_upload)

    st._upload_parallel_public_output(req, object())

    assert [(uri, fail_fast) for uri, _body, fail_fast in uploads] == [
        ("s3://bucket/final/part-00003-00000.jsonl.gz", True),
        ("s3://bucket/final/part-00003-00001.jsonl.gz", True),
    ]
    assert gzip.decompress(uploads[0][1]).decode("utf-8") == '{"example_id":"a"}\n'
    assert gzip.decompress(uploads[1][1]).decode("utf-8") == '{"example_id":"b"}\n'


def test_run_one_shot_from_sagemaker_env_parses_perf_pacing_hyperparameters(monkeypatch, tmp_path: Path):
    import hotvect.sagemaker_tasks as st

    captured = {}

    class FakeEnvironment:
        def __init__(self):
            self.hyperparameters = {
                "hotvect_task": "performance-test",
                "hotvect_task_output": json.dumps({"s3_uri": "s3://bucket/output", "compression": "none"}),
                "s3_uri_algorithm_jar": "s3://bucket/algo.jar",
                "s3_uri_algorithm_definition": "s3://bucket/algo-def.json",
                "s3_uri_parameter_zip": "s3://bucket/params.zip",
                "s3_uri_metadata": "s3://bucket/meta",
                "s3_uri_result_file": "s3://bucket/result.json",
                "hotvect_source_channel": "source",
                "hotvect_samples": "444",
                "hotvect_sample_pool_size": "25000",
                "hotvect_target_rps": "120.0",
                "hotvect_target_throughput_fraction": "0.5",
                "hotvect_workload_mode": "batch",
            }
            self.input_dir = str(tmp_path / "input")
            self.output_data_dir = str(tmp_path / "output_data")
            self.output_dir = str(tmp_path / "output")

    env_module = ModuleType("sagemaker_training.environment")
    env_module.Environment = FakeEnvironment
    monkeypatch.setitem(sys.modules, "sagemaker_training", ModuleType("sagemaker_training"))
    monkeypatch.setitem(sys.modules, "sagemaker_training.environment", env_module)
    monkeypatch.setattr(
        st.boto3, "client", lambda *_args, **_kwargs: _make_fake_definition_s3_client({"algorithm_name": "demo-algo"})
    )
    monkeypatch.setattr(st, "_download_s3_file", lambda _s3_uri, dest_path, _client: dest_path.write_text("x"))
    monkeypatch.setattr(st, "_upload_directory_to_s3", lambda *_args, **_kwargs: None)
    monkeypatch.setattr(st, "_upload_file_to_s3", lambda *_args, **_kwargs: None)

    def _capture_run_task(req, **_kwargs):
        captured["req"] = req
        return {"ok": True}

    monkeypatch.setattr(st, "_run_task", _capture_run_task)

    input_source_dir = tmp_path / "input" / "data" / "source"
    input_source_dir.mkdir(parents=True)
    (tmp_path / "output").mkdir()

    st.run_one_shot_from_sagemaker_env()

    req = captured["req"]
    assert req.samples == 444
    assert req.sample_pool_size == 25000
    assert req.target_rps == 120.0
    assert req.target_throughput_fraction == 0.5
    assert req.workload_mode == "batch"
    assert req.task_output_s3_uri == "s3://bucket/output"


def test_build_one_shot_benchmark_contract_uses_measured_perf_values():
    import hotvect.sagemaker_tasks as st
    from hotvect.sagemaker_contracts import OneShotSagemakerHyperparameters, TaskOutputConfig

    request_hp = OneShotSagemakerHyperparameters(
        task="performance-test",
        task_output=TaskOutputConfig(s3_uri="s3://bucket/task-output"),
        source_s3_uri="s3://bucket/source/",
        parameter_zip_s3_uri="s3://bucket/params.zip",
        instance_type="ml.c7i.2xlarge",
        training_image="training-image",
        sagemaker_output_s3_uri="s3://bucket/sagemaker/job",
        samples=100,
        sample_pool_size=10,
        target_rps=50.0,
        target_throughput_fraction=0.5,
        workload_mode="realtime",
        max_threads=4,
    )

    contract = st._build_one_shot_benchmark_contract(
        request_hp=request_hp,
        task_metadata={
            "samples": 1000,
            "sample_pool_size": 250,
            "target_rps": 120.0,
            "workload_mode": "batch",
            "execution_command": ["java", "-Xmx16g", "Main", "performance-test"],
        },
        s3_uri_metadata="s3://bucket/meta",
        s3_uri_result_file="s3://bucket/result.json",
        task_output_s3_uri="s3://bucket/task-output",
    )

    assert contract == {
        "parameter_s3_uri": "s3://bucket/params.zip",
        "source_s3_uri": "s3://bucket/source/",
        "instance_type": "ml.c7i.2xlarge",
        "training_image": "training-image",
        "samples": 1000,
        "sample_pool_size": 250,
        "target_rps": 120.0,
        "target_throughput_fraction": 0.5,
        "max_threads": 4,
        "workload_mode": "batch",
        "execution_command": ["java", "-Xmx16g", "Main", "performance-test"],
        "output_prefixes": {
            "sagemaker_output": "s3://bucket/sagemaker/job",
            "metadata": "s3://bucket/meta",
            "result": "s3://bucket/result.json",
            "task_output": "s3://bucket/task-output",
        },
    }
