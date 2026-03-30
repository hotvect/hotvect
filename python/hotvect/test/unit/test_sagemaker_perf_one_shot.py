import importlib.util
import sys
from importlib.machinery import SourceFileLoader
from pathlib import Path
from types import ModuleType, SimpleNamespace


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


def test_submit_one_shot_sagemaker_job_includes_perf_pacing_hyperparameters(monkeypatch, tmp_path: Path):
    hv = _load_hv_module()
    captured = {}

    class FakeAlgorithmPipelineContext:
        def __init__(self, **_kwargs):
            pass

    class FakeAlgorithmPipeline:
        def __init__(self, **_kwargs):
            self.algorithm_definition = {"hyperparameter_version": "perf-hp"}

        def hyperparameter_slug(self):
            return "perf-slug"

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
            AlgorithmPipeline=FakeAlgorithmPipeline,
            AlgorithmPipelineContext=FakeAlgorithmPipelineContext,
        ),
    )
    monkeypatch.setitem(sys.modules, "hotvect.mlutils", SimpleNamespace(standard_evaluation=object()))
    monkeypatch.setitem(sys.modules, "hotvect.sagemaker", SimpleNamespace(SagemakerTrainingExecutor=FakeExecutor))
    monkeypatch.setattr(hv, "resolve_template_path", lambda *_args, **_kwargs: SimpleNamespace(path=None))
    monkeypatch.setattr(hv, "resolve_training_image", lambda **_kwargs: "training-image")
    monkeypatch.setattr(hv, "build_one_shot_training_job_name", lambda **_kwargs: "perf-job")
    monkeypatch.setattr(
        hv,
        "_load_effective_algorithm_definition",
        lambda *_args, **_kwargs: {
            "algorithm_name": "demo-algo",
            "hyperparameter_version": "perf-hp",
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
        sagemaker_config=None,
        role_arn="arn:aws:iam::123456789012:role/example-role",
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

    hp = captured["job_def"]["HyperParameters"]
    assert hp["hotvect_task"] == "performance-test"
    assert hp["hotvect_samples"] == "321"
    assert hp["hotvect_target_rps"] == "120.0"
    assert hp["hotvect_target_throughput_fraction"] == "0.5"
    assert hp["hotvect_workload_mode"] == "batch"
    assert hp["s3_uri_parameter_zip"] == "s3://bucket/params.zip"
    assert captured["ran"] is True


def test_submit_one_shot_sagemaker_job_pins_top_level_with_parameter_without_override(monkeypatch, tmp_path: Path):
    hv = _load_hv_module()
    captured = {}

    class FakeAlgorithmPipelineContext:
        def __init__(self, **_kwargs):
            pass

    class FakeAlgorithmPipeline:
        def __init__(self, **kwargs):
            captured["algorithm_definition_arg"] = kwargs["algorithm_definition"]
            if isinstance(kwargs["algorithm_definition"], tuple):
                _, effective_definition = kwargs["algorithm_definition"]
                self.algorithm_definition = effective_definition
            else:
                self.algorithm_definition = {"hyperparameter_version": "perf-hp"}

        def hyperparameter_slug(self):
            return "perf-slug"

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
            AlgorithmPipeline=FakeAlgorithmPipeline,
            AlgorithmPipelineContext=FakeAlgorithmPipelineContext,
        ),
    )
    monkeypatch.setitem(sys.modules, "hotvect.mlutils", SimpleNamespace(standard_evaluation=object()))
    monkeypatch.setitem(sys.modules, "hotvect.sagemaker", SimpleNamespace(SagemakerTrainingExecutor=FakeExecutor))
    monkeypatch.setattr(hv, "resolve_template_path", lambda *_args, **_kwargs: SimpleNamespace(path=None))
    monkeypatch.setattr(hv, "resolve_training_image", lambda **_kwargs: "training-image")
    monkeypatch.setattr(hv, "build_one_shot_training_job_name", lambda **_kwargs: "perf-job")
    monkeypatch.setattr(
        hv,
        "_load_effective_algorithm_definition",
        lambda *_args, **_kwargs: {
            "algorithm_name": "demo-algo",
            "hyperparameter_version": "perf-hp",
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
        role_arn="arn:aws:iam::123456789012:role/example-role",
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
    assert isinstance(algorithm_definition_arg, tuple)
    assert algorithm_definition_arg[0] == "demo-algo"
    assert algorithm_definition_arg[1]["hotvect_execution_parameters"]["with_parameter"] == "s3://bucket/params.zip"
    assert (
        captured["executor_algorithm_definition"]["hotvect_execution_parameters"]["with_parameter"]
        == "s3://bucket/params.zip"
    )
    assert captured["ran"] is True


def test_submit_one_shot_sagemaker_job_uses_effective_algorithm_definition_when_override_is_set(
    monkeypatch, tmp_path: Path
):
    hv = _load_hv_module()
    captured = {}

    class FakeAlgorithmPipelineContext:
        def __init__(self, **_kwargs):
            pass

    class FakeAlgorithmPipeline:
        def __init__(self, **kwargs):
            captured["algorithm_definition_arg"] = kwargs["algorithm_definition"]
            if isinstance(kwargs["algorithm_definition"], tuple):
                _, effective_definition = kwargs["algorithm_definition"]
                self.algorithm_definition = effective_definition
            else:
                self.algorithm_definition = {"hyperparameter_version": "perf-hp"}

        def hyperparameter_slug(self):
            return "perf-slug"

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
            AlgorithmPipeline=FakeAlgorithmPipeline,
            AlgorithmPipelineContext=FakeAlgorithmPipelineContext,
        ),
    )
    monkeypatch.setitem(sys.modules, "hotvect.mlutils", SimpleNamespace(standard_evaluation=object()))
    monkeypatch.setitem(sys.modules, "hotvect.sagemaker", SimpleNamespace(SagemakerTrainingExecutor=FakeExecutor))
    monkeypatch.setattr(hv, "resolve_template_path", lambda *_args, **_kwargs: SimpleNamespace(path=None))
    monkeypatch.setattr(hv, "resolve_training_image", lambda **_kwargs: "training-image")
    monkeypatch.setattr(hv, "build_one_shot_training_job_name", lambda **_kwargs: "perf-job")
    monkeypatch.setattr(
        hv,
        "_load_effective_algorithm_definition",
        lambda *_args, **_kwargs: {
            "algorithm_name": "demo-algo",
            "hyperparameter_version": "perf-hp",
            "dependencies": {
                "example-algorithm-model": {
                    "hotvect_execution_parameters": {"with_parameter": "s3://bucket/params.zip"}
                }
            },
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
        algorithm_override=str(tmp_path / "override.json"),
        sagemaker_config=None,
        role_arn="arn:aws:iam::123456789012:role/example-role",
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
    assert isinstance(algorithm_definition_arg, tuple)
    assert algorithm_definition_arg[0] == "demo-algo"
    assert (
        algorithm_definition_arg[1]["dependencies"]["example-algorithm-model"][
            "hotvect_execution_parameters"
        ]["with_parameter"]
        == "s3://bucket/params.zip"
    )
    assert (
        captured["executor_algorithm_definition"]["dependencies"][
            "example-algorithm-model"
        ]["hotvect_execution_parameters"]["with_parameter"]
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
        algorithm_jar_s3_uri="s3://bucket/algo.jar",
        parameter_zip_s3_uri="s3://bucket/params.zip",
        source_dir=source_dir,
        metadata_dir=metadata_dir,
        output_dir=output_dir,
        s3_uri_metadata="s3://bucket/meta",
        s3_uri_result_file="s3://bucket/result.json",
        s3_uri_task_output="s3://bucket/output",
        samples=222,
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
    assert "--target-rps" in cmd
    assert cmd[cmd.index("--target-rps") + 1] == "120.0"
    assert "--target-throughput-fraction" in cmd
    assert cmd[cmd.index("--target-throughput-fraction") + 1] == "0.5"
    assert "--workload-mode" in cmd
    assert cmd[cmd.index("--workload-mode") + 1] == "batch"


def test_run_one_shot_from_sagemaker_env_parses_perf_pacing_hyperparameters(monkeypatch, tmp_path: Path):
    import hotvect.sagemaker_tasks as st

    captured = {}

    class FakeEnvironment:
        def __init__(self):
            self.hyperparameters = {
                "hotvect_task": "performance-test",
                "s3_uri_algorithm_jar": "s3://bucket/algo.jar",
                "s3_uri_parameter_zip": "s3://bucket/params.zip",
                "s3_uri_metadata": "s3://bucket/meta",
                "s3_uri_result_file": "s3://bucket/result.json",
                "s3_uri_task_output": "s3://bucket/output",
                "hotvect_source_channel": "source",
                "hotvect_samples": "444",
                "hotvect_target_rps": "120.0",
                "hotvect_target_throughput_fraction": "0.5",
                "hotvect_workload_mode": "batch",
                "_algo_def_algorithm_name": "demo-algo",
            }
            self.input_dir = str(tmp_path / "input")
            self.output_data_dir = str(tmp_path / "output_data")
            self.output_dir = str(tmp_path / "output")

    env_module = ModuleType("sagemaker_training.environment")
    env_module.Environment = FakeEnvironment
    monkeypatch.setitem(sys.modules, "sagemaker_training", ModuleType("sagemaker_training"))
    monkeypatch.setitem(sys.modules, "sagemaker_training.environment", env_module)
    monkeypatch.setattr(st.boto3, "client", lambda *_args, **_kwargs: object())
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
    assert req.target_rps == 120.0
    assert req.target_throughput_fraction == 0.5
    assert req.workload_mode == "batch"
