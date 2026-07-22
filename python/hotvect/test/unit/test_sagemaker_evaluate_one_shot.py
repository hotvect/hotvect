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
            loader = SourceFileLoader("hv_cli_sagemaker_eval", str(candidate))
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
            return "eval-slug"

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


def test_submit_one_shot_sagemaker_job_supports_evaluate_without_parameter_zip(monkeypatch, tmp_path: Path):
    hv = _load_hv_module()
    captured = {}
    base_definition = {
        "algorithm_name": "demo-algo",
        "algorithm_version": "1.2.3",
        "hyperparameter_version": "eval-hp",
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
    monkeypatch.setattr(hv, "build_one_shot_training_job_name", lambda **_kwargs: "eval-job")
    monkeypatch.setattr(
        hv,
        "build_one_shot_effective_algorithm_definition",
        lambda *_args, **_kwargs: base_definition,
    )
    monkeypatch.setattr(
        hv,
        "build_training_job_definition",
        lambda **_kwargs: {
            "TrainingJobName": "eval-job",
            "OutputDataConfig": {"S3OutputPath": "s3://bucket/output"},
            "HyperParameters": {},
            "InputDataConfig": [],
        },
    )

    args = SimpleNamespace(
        sagemaker_job_prefix="ml-exp-test",
        source_s3_uri="s3://bucket/cached-prediction",
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
        parameter_s3_uri=None,
        include_feature_store_responses=False,
        samples=None,
        target_rps=None,
        target_throughput_fraction=None,
        assume_role_arn=None,
    )

    hv._submit_one_shot_sagemaker_job(task="evaluate", task_kind_short="eval", args=args)

    hp = captured["job_def"]["HyperParameters"]
    assert hp["hotvect_task"] == "evaluate"
    assert hp["hotvect_source_channel"] == "source"
    assert "s3_uri_parameter_zip" not in hp
    assert captured["ran"] is True


def test_submit_one_shot_sagemaker_job_uses_override_fragment_for_evaluate(monkeypatch, tmp_path: Path):
    hv = _load_hv_module()
    captured = {}
    base_definition = {
        "algorithm_name": "demo-algo",
        "algorithm_version": "1.2.3",
        "hyperparameter_version": "eval-hp",
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
    monkeypatch.setattr(hv, "build_one_shot_training_job_name", lambda **_kwargs: "eval-job")
    monkeypatch.setattr(
        hv,
        "build_one_shot_effective_algorithm_definition",
        lambda *_args, **_kwargs: {
            **base_definition,
            "training_lag_days": 7,
        },
    )
    monkeypatch.setattr(
        hv,
        "build_training_job_definition",
        lambda **_kwargs: {
            "TrainingJobName": "eval-job",
            "OutputDataConfig": {"S3OutputPath": "s3://bucket/output"},
            "HyperParameters": {},
            "InputDataConfig": [],
        },
    )

    args = SimpleNamespace(
        sagemaker_job_prefix="ml-exp-test",
        source_s3_uri="s3://bucket/cached-prediction",
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
        parameter_s3_uri=None,
        include_feature_store_responses=False,
        samples=None,
        target_rps=None,
        target_throughput_fraction=None,
        assume_role_arn=None,
    )

    hv._submit_one_shot_sagemaker_job(task="evaluate", task_kind_short="eval", args=args)

    assert captured["algorithm_definition_arg"] == {
        **base_definition,
        "training_lag_days": 7,
    }
    assert captured["executor_algorithm_definition"]["training_lag_days"] == 7
    assert captured["ran"] is True


def test_submit_one_shot_sagemaker_job_applies_algorithm_job_overrides(monkeypatch, tmp_path: Path):
    hv = _load_hv_module()
    captured = {}
    base_definition = {
        "algorithm_name": "demo-algo",
        "algorithm_version": "1.2.3",
        "hyperparameter_version": "eval-hp",
        "dependencies": ["child-model"],
        "sagemaker_training_job_definition": {
            "EnableManagedSpotTraining": True,
            "StoppingCondition": {"MaxWaitTimeInSeconds": 172800},
        },
        "sagemaker_execution_parameters": {
            "instance_type": "ml.m6i.4xlarge",
            "volume_size_in_gb": 64,
            "max_runtime": 7200,
        },
    }

    class FakeAlgorithmPipelineContext:
        def __init__(self, **_kwargs):
            pass

    class FakeExecutor:
        def __init__(self, algorithm_pipeline, training_job_definition, role_arn_to_assume):
            captured["job_def"] = training_job_definition
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
    monkeypatch.setattr(hv, "build_one_shot_training_job_name", lambda **_kwargs: "eval-job")
    monkeypatch.setattr(
        hv,
        "build_one_shot_effective_algorithm_definition",
        lambda *_args, **_kwargs: base_definition,
    )
    monkeypatch.setattr(
        hv,
        "build_training_job_definition",
        lambda **_kwargs: {
            "TrainingJobName": "eval-job",
            "OutputDataConfig": {"S3OutputPath": "s3://bucket/output"},
            "AlgorithmSpecification": {"TrainingImage": "training-image", "TrainingInputMode": "FastFile"},
            "HyperParameters": {},
            "InputDataConfig": [],
            "ResourceConfig": {"InstanceType": "ml.t3.medium", "InstanceCount": 1, "VolumeSizeInGB": 30},
            "StoppingCondition": {"MaxRuntimeInSeconds": 3600},
        },
    )

    args = SimpleNamespace(
        sagemaker_job_prefix="ml-exp-test",
        source_s3_uri="s3://bucket/cached-prediction",
        algorithm_jar=tmp_path / "algo.jar",
        algorithm_name="demo-algo",
        algorithm_override=None,
        sagemaker_config=None,
        role_arn="arn:aws:iam::123456789012:role/TestRole",
        s3_output_base="s3://bucket/output",
        instance_type="ml.t3.medium",
        volume_gb=None,
        max_runtime_seconds=None,
        training_image=None,
        parameter_s3_uri=None,
        include_feature_store_responses=False,
        samples=None,
        target_rps=None,
        target_throughput_fraction=None,
        assume_role_arn=None,
    )

    hv._submit_one_shot_sagemaker_job(task="evaluate", task_kind_short="eval", args=args)

    assert captured["job_def"]["EnableManagedSpotTraining"] is True
    assert captured["job_def"]["StoppingCondition"]["MaxWaitTimeInSeconds"] == 172800
    assert captured["job_def"]["StoppingCondition"]["MaxRuntimeInSeconds"] == 7200
    assert captured["job_def"]["ResourceConfig"]["InstanceType"] == "ml.t3.medium"
    assert captured["job_def"]["ResourceConfig"]["VolumeSizeInGB"] == 64
    assert captured["ran"] is True


def test_run_task_evaluate_writes_evaluation_output(monkeypatch, tmp_path: Path):
    import hotvect.sagemaker_tasks as st
    from hotvect.evaluation import evaluation

    source_dir = tmp_path / "source"
    metadata_dir = tmp_path / "meta"
    output_dir = tmp_path / "out"
    source_dir.mkdir()
    metadata_dir.mkdir()
    output_dir.mkdir()
    (source_dir / "part-00000.jsonl").write_text('{"example_id":"example-1","result":[]}\n')

    req = st.OneShotTaskRequest(
        task="evaluate",
        source_dir=source_dir,
        metadata_dir=metadata_dir,
        output_dir=output_dir,
        task_output_s3_uri="s3://bucket/output",
        samples=None,
        target_rps=None,
        target_throughput_fraction=None,
        algorithm_definition={"algorithm_name": "demo-algo"},
    )

    captured = {}

    def _fake_standard_evaluation(path):
        captured["path"] = path
        return {"roc_auc": {"mean": 0.5}, "ndcg_at_10": 0.4}

    monkeypatch.setattr(evaluation, "standard_evaluation", _fake_standard_evaluation)

    task_metadata = st._run_task(
        req,
        local_algorithm_jar=tmp_path / "algo.jar",
        local_parameter_zip=None,
    )

    result_path = output_dir / "evaluation.json"
    metadata_path = metadata_dir / "evaluate" / "metadata.json"
    assert captured["path"] == str(source_dir)
    assert result_path.is_file()
    assert metadata_path.is_file()
    assert json.loads(result_path.read_text()) == {"roc_auc": {"mean": 0.5}, "ndcg_at_10": 0.4}
    assert task_metadata["destination_file"] == str(result_path)
    assert task_metadata["evaluated_source_path"] == str(source_dir)


def test_run_task_evaluate_accepts_root_level_part_files_with_control_sidecar(monkeypatch, tmp_path: Path):
    import hotvect.sagemaker_tasks as st
    from hotvect.evaluation import evaluation

    source_dir = tmp_path / "source"
    metadata_dir = tmp_path / "meta"
    output_dir = tmp_path / "out"
    source_dir.mkdir()
    metadata_dir.mkdir()
    output_dir.mkdir()
    (source_dir / "_SUCCESS").write_text("")
    (source_dir / "part-00000.jsonl").write_text('{"example_id":"example-1","result":[]}\n')

    req = st.OneShotTaskRequest(
        task="evaluate",
        source_dir=source_dir,
        metadata_dir=metadata_dir,
        output_dir=output_dir,
        task_output_s3_uri="s3://bucket/output",
        samples=None,
        target_rps=None,
        target_throughput_fraction=None,
        algorithm_definition={"algorithm_name": "demo-algo"},
    )

    captured = {}

    def _fake_standard_evaluation(path):
        captured["path"] = path
        return {"roc_auc": {"mean": 0.5}}

    monkeypatch.setattr(evaluation, "standard_evaluation", _fake_standard_evaluation)

    st._run_task(
        req,
        local_algorithm_jar=tmp_path / "algo.jar",
        local_parameter_zip=None,
    )

    assert captured["path"] == str(source_dir)


def test_run_task_evaluate_accepts_predict_task_root_with_out_prediction(monkeypatch, tmp_path: Path):
    import hotvect.sagemaker_tasks as st
    from hotvect.evaluation import evaluation

    source_dir = tmp_path / "source"
    prediction_dir = source_dir / "out" / "prediction"
    metadata_dir = tmp_path / "meta"
    output_dir = tmp_path / "out"
    prediction_dir.mkdir(parents=True)
    metadata_dir.mkdir()
    output_dir.mkdir()
    (source_dir / "meta").mkdir()
    (prediction_dir / "part-00000.jsonl").write_text('{"example_id":"example-1","result":[]}\n')

    req = st.OneShotTaskRequest(
        task="evaluate",
        source_dir=source_dir,
        metadata_dir=metadata_dir,
        output_dir=output_dir,
        task_output_s3_uri="s3://bucket/output",
        samples=None,
        target_rps=None,
        target_throughput_fraction=None,
        algorithm_definition={"algorithm_name": "demo-algo"},
    )

    captured = {}

    def _fake_standard_evaluation(path):
        captured["path"] = path
        return {"roc_auc": {"mean": 0.5}}

    monkeypatch.setattr(evaluation, "standard_evaluation", _fake_standard_evaluation)

    st._run_task(
        req,
        local_algorithm_jar=tmp_path / "algo.jar",
        local_parameter_zip=None,
    )

    assert captured["path"] == str(prediction_dir)


def test_run_task_evaluate_rejects_multiple_root_level_non_part_prediction_files(tmp_path: Path):
    import hotvect.sagemaker_tasks as st

    source_dir = tmp_path / "source"
    metadata_dir = tmp_path / "meta"
    output_dir = tmp_path / "out"
    source_dir.mkdir()
    metadata_dir.mkdir()
    output_dir.mkdir()
    (source_dir / "shard_0.jsonl").write_text('{"example_id":"example-1","result":[]}\n')
    (source_dir / "shard_1.jsonl").write_text('{"example_id":"example-2","result":[]}\n')

    req = st.OneShotTaskRequest(
        task="evaluate",
        source_dir=source_dir,
        metadata_dir=metadata_dir,
        output_dir=output_dir,
        task_output_s3_uri="s3://bucket/output",
        samples=None,
        target_rps=None,
        target_throughput_fraction=None,
        algorithm_definition={"algorithm_name": "demo-algo"},
    )

    with pytest.raises(FileNotFoundError, match="Could not locate prediction input"):
        st._run_task(
            req,
            local_algorithm_jar=tmp_path / "algo.jar",
            local_parameter_zip=None,
        )


def test_upload_single_public_output_writes_predict_parts_at_public_prefix(monkeypatch, tmp_path: Path):
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
        task_output_s3_uri="s3://bucket/output",
        samples=None,
        target_rps=None,
        target_throughput_fraction=None,
        algorithm_definition={"algorithm_name": "demo-algo"},
    )

    uploads = []

    def _capture_upload(local_path: str, s3_uri: str, _s3_client, *, fail_fast: bool = False) -> None:
        uploads.append((Path(local_path).name, s3_uri, fail_fast))

    monkeypatch.setattr(st, "_upload_file_to_s3", _capture_upload)

    st._upload_single_public_output(req, object())

    assert uploads == [
        ("part-00000.jsonl", "s3://bucket/output/part-00000.jsonl", True),
        ("part-00001.jsonl", "s3://bucket/output/part-00001.jsonl", True),
    ]


def test_run_task_evaluate_accepts_single_prediction_file_in_source_channel(monkeypatch, tmp_path: Path):
    import hotvect.sagemaker_tasks as st
    from hotvect.evaluation import evaluation

    source_dir = tmp_path / "source"
    metadata_dir = tmp_path / "meta"
    output_dir = tmp_path / "out"
    source_dir.mkdir()
    metadata_dir.mkdir()
    output_dir.mkdir()
    prediction_file = source_dir / "prediction.jsonl"
    prediction_file.write_text('{"example_id": "example-1", "result": []}\n')

    req = st.OneShotTaskRequest(
        task="evaluate",
        source_dir=source_dir,
        metadata_dir=metadata_dir,
        output_dir=output_dir,
        task_output_s3_uri="s3://bucket/output",
        samples=None,
        target_rps=None,
        target_throughput_fraction=None,
        algorithm_definition={"algorithm_name": "demo-algo"},
    )

    captured = {}

    def _fake_standard_evaluation(path):
        captured["path"] = path
        return {"roc_auc": {"mean": 0.5}}

    monkeypatch.setattr(evaluation, "standard_evaluation", _fake_standard_evaluation)

    st._run_task(
        req,
        local_algorithm_jar=tmp_path / "algo.jar",
        local_parameter_zip=None,
    )

    assert captured["path"] == str(prediction_file)


def test_run_task_evaluate_accepts_single_prediction_file_with_underscore_sidecar(monkeypatch, tmp_path: Path):
    import hotvect.sagemaker_tasks as st
    from hotvect.evaluation import evaluation

    source_dir = tmp_path / "source"
    metadata_dir = tmp_path / "meta"
    output_dir = tmp_path / "out"
    source_dir.mkdir()
    metadata_dir.mkdir()
    output_dir.mkdir()
    (source_dir / "_metadata.json").write_text('{"job": "metadata"}\n')
    prediction_file = source_dir / "prediction.jsonl"
    prediction_file.write_text('{"example_id": "example-1", "result": []}\n')

    req = st.OneShotTaskRequest(
        task="evaluate",
        source_dir=source_dir,
        metadata_dir=metadata_dir,
        output_dir=output_dir,
        task_output_s3_uri="s3://bucket/output",
        samples=None,
        target_rps=None,
        target_throughput_fraction=None,
        algorithm_definition={"algorithm_name": "demo-algo"},
    )

    captured = {}

    def _fake_standard_evaluation(path):
        captured["path"] = path
        return {"roc_auc": {"mean": 0.5}}

    monkeypatch.setattr(evaluation, "standard_evaluation", _fake_standard_evaluation)

    st._run_task(
        req,
        local_algorithm_jar=tmp_path / "algo.jar",
        local_parameter_zip=None,
    )

    assert captured["path"] == str(prediction_file)


def test_run_task_evaluate_rejects_underscore_metadata_without_prediction(tmp_path: Path):
    import hotvect.sagemaker_tasks as st

    source_dir = tmp_path / "source"
    metadata_dir = tmp_path / "meta"
    output_dir = tmp_path / "out"
    source_dir.mkdir()
    metadata_dir.mkdir()
    output_dir.mkdir()
    (source_dir / "_metadata.json").write_text('{"job": "metadata"}\n')

    req = st.OneShotTaskRequest(
        task="evaluate",
        source_dir=source_dir,
        metadata_dir=metadata_dir,
        output_dir=output_dir,
        task_output_s3_uri="s3://bucket/output",
        samples=None,
        target_rps=None,
        target_throughput_fraction=None,
        algorithm_definition={"algorithm_name": "demo-algo"},
    )

    with pytest.raises(FileNotFoundError, match="Could not locate prediction input"):
        st._run_task(
            req,
            local_algorithm_jar=tmp_path / "algo.jar",
            local_parameter_zip=None,
        )


def test_run_one_shot_from_sagemaker_env_supports_evaluate_without_parameter_zip(monkeypatch, tmp_path: Path):
    import hotvect.sagemaker_tasks as st

    captured = {}
    downloads = []

    class FakeEnvironment:
        def __init__(self):
            self.hyperparameters = {
                "hotvect_task": "evaluate",
                "hotvect_task_output": json.dumps({"s3_uri": "s3://bucket/output", "compression": "none"}),
                "s3_uri_algorithm_jar": "s3://bucket/algo.jar",
                "s3_uri_algorithm_definition": "s3://bucket/algo-def.json",
                "s3_uri_metadata": "s3://bucket/meta",
                "s3_uri_result_file": "s3://bucket/result.json",
                "hotvect_source_channel": "source",
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
    monkeypatch.setattr(
        st,
        "_download_s3_file",
        lambda s3_uri, dest_path, _client: downloads.append(s3_uri) or dest_path.write_text("x"),
    )
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
    assert req.task == "evaluate"
    assert req.source_dir == input_source_dir
    assert req.task_output_s3_uri == "s3://bucket/output"
    assert downloads == ["s3://bucket/algo.jar"]


def test_run_one_shot_from_sagemaker_env_does_not_use_predict_parameter_output_as_input(monkeypatch, tmp_path: Path):
    import hotvect.sagemaker_tasks as st

    captured = {}
    downloads = []

    class FakeEnvironment:
        def __init__(self):
            self.hyperparameters = {
                "hotvect_task": "encode",
                "hotvect_task_output": json.dumps({"s3_uri": "s3://bucket/output", "compression": "none"}),
                "s3_uri_algorithm_jar": "s3://bucket/algo.jar",
                "s3_uri_algorithm_definition": "s3://bucket/algo-def.json",
                "s3_uri_metadata": "s3://bucket/meta",
                "s3_uri_result_file": "s3://bucket/result.json",
                "s3_uri_predict_parameters_zip": "s3://bucket/predict-params.zip",
                "hotvect_source_channel": "source",
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
    monkeypatch.setattr(
        st,
        "_download_s3_file",
        lambda s3_uri, dest_path, _client: downloads.append(s3_uri) or dest_path.write_text("x"),
    )
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
    assert req.task == "encode"
    assert req.source_dir == input_source_dir
    assert req.task_output_s3_uri == "s3://bucket/output"
    assert downloads == ["s3://bucket/algo.jar"]


def test_run_one_shot_from_sagemaker_env_skips_legacy_output_upload_for_parallel_worker(monkeypatch, tmp_path: Path):
    import hotvect.sagemaker_tasks as st

    captured = {"directory_uploads": []}

    class FakeEnvironment:
        def __init__(self):
            self.hyperparameters = {
                "hotvect_task": "predict",
                "s3_uri_algorithm_jar": "s3://bucket/algo.jar",
                "s3_uri_algorithm_definition": "s3://bucket/algo-def.json",
                "s3_uri_metadata": "s3://bucket/meta",
                "s3_uri_result_file": "s3://bucket/result.json",
                "hotvect_task_output": json.dumps({"s3_uri": "s3://bucket/final/", "compression": "none"}),
                "hotvect_parallel_execution": json.dumps({"worker_count": 4, "worker_index": 1}),
                "hotvect_source_channel": "source",
                "hotvect_unordered": "true",
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
    monkeypatch.setattr(
        st,
        "_upload_directory_to_s3",
        lambda local_dir, s3_uri, _client, **_kwargs: captured["directory_uploads"].append((local_dir, s3_uri)),
    )
    monkeypatch.setattr(st, "_upload_file_to_s3", lambda *_args, **_kwargs: None)
    monkeypatch.setattr(
        st,
        "_upload_parallel_public_output",
        lambda req, _client: captured.setdefault("parallel_upload_req", req),
    )
    monkeypatch.setattr(st, "_run_task", lambda *_args, **_kwargs: {"ok": True})

    input_source_dir = tmp_path / "input" / "data" / "source"
    input_source_dir.mkdir(parents=True)
    (tmp_path / "output").mkdir()

    st.run_one_shot_from_sagemaker_env()

    assert captured["parallel_upload_req"].parallel_worker_index == 1
    assert captured["parallel_upload_req"].task_output_s3_uri == "s3://bucket/final/"
    assert [upload[1] for upload in captured["directory_uploads"]] == ["s3://bucket/meta"]


def test_run_one_shot_from_sagemaker_env_skips_public_output_upload_for_skipped_parallel_worker(
    monkeypatch, tmp_path: Path
):
    import hotvect.sagemaker_tasks as st

    captured = {"directory_uploads": [], "file_uploads": []}

    class FakeEnvironment:
        def __init__(self):
            self.hyperparameters = {
                "hotvect_task": "predict",
                "s3_uri_algorithm_jar": "s3://bucket/algo.jar",
                "s3_uri_algorithm_definition": "s3://bucket/algo-def.json",
                "s3_uri_metadata": "s3://bucket/meta",
                "s3_uri_result_file": "s3://bucket/result.json",
                "hotvect_task_output": json.dumps({"s3_uri": "s3://bucket/final/", "compression": "none"}),
                "hotvect_parallel_execution": json.dumps({"worker_count": 4, "worker_index": 1}),
                "hotvect_source_channel": "source",
                "hotvect_unordered": "true",
            }
            self.input_dir = str(tmp_path / "input")
            self.output_data_dir = str(tmp_path / "output_data")
            self.output_dir = str(tmp_path / "output")
            self.job_name = "oneshot-job"

    env_module = ModuleType("sagemaker_training.environment")
    env_module.Environment = FakeEnvironment
    monkeypatch.setitem(sys.modules, "sagemaker_training", ModuleType("sagemaker_training"))
    monkeypatch.setitem(sys.modules, "sagemaker_training.environment", env_module)
    monkeypatch.setattr(
        st.boto3, "client", lambda *_args, **_kwargs: _make_fake_definition_s3_client({"algorithm_name": "demo-algo"})
    )
    monkeypatch.setattr(st, "_download_s3_file", lambda _s3_uri, dest_path, _client: dest_path.write_text("x"))
    monkeypatch.setattr(
        st,
        "_upload_directory_to_s3",
        lambda local_dir, s3_uri, _client, **_kwargs: captured["directory_uploads"].append((local_dir, s3_uri)),
    )
    monkeypatch.setattr(
        st,
        "_upload_file_to_s3",
        lambda local_path, s3_uri, _client, **_kwargs: captured["file_uploads"].append((local_path, s3_uri)),
    )
    monkeypatch.setattr(
        st,
        "_upload_parallel_public_output",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(AssertionError("public output upload should be skipped")),
    )
    monkeypatch.setattr(st, "_run_task", lambda *_args, **_kwargs: {"skipped": True, "source_files_assigned": 0})

    input_source_dir = tmp_path / "input" / "data" / "source"
    input_source_dir.mkdir(parents=True)
    (tmp_path / "output").mkdir()

    st.run_one_shot_from_sagemaker_env()

    assert [upload[1] for upload in captured["directory_uploads"]] == ["s3://bucket/meta"]
    assert [upload[1] for upload in captured["file_uploads"]] == ["s3://bucket/result.json"]
    result = json.loads(Path(captured["file_uploads"][0][0]).read_text())
    assert result["sagemaker_training_job_name"] == "oneshot-job"
    assert result["s3_uri_result_file"] == "s3://bucket/result.json"


def test_run_one_shot_from_sagemaker_env_requires_hotvect_task_output(monkeypatch, tmp_path: Path):
    import hotvect.sagemaker_tasks as st

    class FakeEnvironment:
        def __init__(self):
            self.hyperparameters = {
                "hotvect_task": "evaluate",
                "s3_uri_algorithm_jar": "s3://bucket/algo.jar",
                "s3_uri_algorithm_definition": "s3://bucket/algo-def.json",
                "s3_uri_metadata": "s3://bucket/meta",
                "s3_uri_result_file": "s3://bucket/result.json",
                "s3_uri_task_output": "s3://bucket/output",
                "hotvect_source_channel": "source",
            }
            self.input_dir = str(tmp_path / "input")
            self.output_data_dir = str(tmp_path / "output_data")
            self.output_dir = str(tmp_path / "output")

    env_module = ModuleType("sagemaker_training.environment")
    env_module.Environment = FakeEnvironment
    monkeypatch.setitem(sys.modules, "sagemaker_training", ModuleType("sagemaker_training"))
    monkeypatch.setitem(sys.modules, "sagemaker_training.environment", env_module)

    with pytest.raises(ValueError, match="Missing: hotvect_task_output"):
        st.run_one_shot_from_sagemaker_env()


def test_run_one_shot_from_sagemaker_env_requires_algorithm_definition_uri(monkeypatch, tmp_path: Path):
    import hotvect.sagemaker_tasks as st

    class FakeEnvironment:
        def __init__(self):
            self.hyperparameters = {
                "hotvect_task": "evaluate",
                "hotvect_task_output": json.dumps({"s3_uri": "s3://bucket/output", "compression": "none"}),
                "s3_uri_algorithm_jar": "s3://bucket/algo.jar",
                "s3_uri_metadata": "s3://bucket/meta",
                "s3_uri_result_file": "s3://bucket/result.json",
                "hotvect_source_channel": "source",
            }
            self.input_dir = str(tmp_path / "input")
            self.output_data_dir = str(tmp_path / "output_data")
            self.output_dir = str(tmp_path / "output")

    env_module = ModuleType("sagemaker_training.environment")
    env_module.Environment = FakeEnvironment
    monkeypatch.setitem(sys.modules, "sagemaker_training", ModuleType("sagemaker_training"))
    monkeypatch.setitem(sys.modules, "sagemaker_training.environment", env_module)
    monkeypatch.setattr(st.boto3, "client", lambda *_args, **_kwargs: object())

    with pytest.raises(ValueError, match="Missing: s3_uri_algorithm_definition"):
        st.run_one_shot_from_sagemaker_env()
