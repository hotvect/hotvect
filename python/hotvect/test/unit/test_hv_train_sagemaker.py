import importlib.util
import json
import sys
from importlib.machinery import SourceFileLoader
from pathlib import Path
from types import SimpleNamespace


def _load_hv_module():
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "bin" / "hv"
        if candidate.exists():
            loader = SourceFileLoader("hv_cli_train_sagemaker", str(candidate))
            spec = importlib.util.spec_from_loader(loader.name, loader)
            assert spec is not None
            module = importlib.util.module_from_spec(spec)
            loader.exec_module(module)
            return module
    raise FileNotFoundError("Could not locate bin/hv relative to test file")


def _recursive_update(target, patch):
    for key, value in patch.items():
        if isinstance(target.get(key), dict) and isinstance(value, dict):
            _recursive_update(target[key], value)
        else:
            target[key] = value


def test_train_command_remote_uses_override_payload_and_applies_job_overrides(monkeypatch, tmp_path: Path):
    hv = _load_hv_module()
    captured = {}

    override_path = tmp_path / "override.json"
    override_path.write_text(
        json.dumps(
            {
                "hyperparameter_version": "remote-test",
                "dependencies": {
                    "example-ranking-algorithm-engagement-model": {
                        "number_of_training_days": 1,
                        "dependencies": {
                            "example-feature-state": {"source_data": {"training_data": {"number_of_days": 1}}}
                        },
                    }
                },
                "sagemaker_training_job_definition": {
                    "EnableManagedSpotTraining": True,
                    "StoppingCondition": {"MaxWaitTimeInSeconds": 172800},
                },
            }
        )
    )

    class FakeAlgorithmPipelineContext:
        def __init__(self, **kwargs):
            captured["jvm_options"] = kwargs["jvm_options"]

    class FakeAlgorithmPipeline:
        def __init__(self, **kwargs):
            algorithm_definition_arg = kwargs["algorithm_definition"]
            captured["algorithm_definition_arg"] = algorithm_definition_arg
            if isinstance(algorithm_definition_arg, tuple):
                self.algorithm_name = algorithm_definition_arg[0]
                self.algorithm_definition_override = algorithm_definition_arg[1]
            else:
                self.algorithm_name = algorithm_definition_arg
                self.algorithm_definition_override = None
            self.algorithm_version = "86.1.13"
            self.algorithm_definition = {
                "algorithm_name": self.algorithm_name,
                "algorithm_version": "86.1.13",
                "hyperparameter_version": "remote-test",
                "sagemaker_training_job_definition": {
                    "EnableManagedSpotTraining": True,
                    "StoppingCondition": {"MaxWaitTimeInSeconds": 172800},
                },
                "sagemaker_execution_parameters": {
                    "instance_type": "ml.m6i.12xlarge",
                    "volume_size_in_gb": 40,
                    "max_runtime": 86400,
                },
            }

    class FakeExecutor:
        def __init__(self, algorithm_pipeline, training_job_definition, role_arn_to_assume):
            captured["payload"] = algorithm_pipeline.algorithm_definition_override
            captured["job_def"] = training_job_definition
            captured["role_arn_to_assume"] = role_arn_to_assume
            self.hyperparameters = training_job_definition["HyperParameters"]
            self.training_job_name = training_job_definition["TrainingJobName"]

        def run(self):
            captured["ran"] = True

    def _legacy_to_overrides(params):
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
        "hotvect.pyhotvect",
        SimpleNamespace(
            AlgorithmPipeline=FakeAlgorithmPipeline,
            AlgorithmPipelineContext=FakeAlgorithmPipelineContext,
        ),
    )
    monkeypatch.setitem(sys.modules, "hotvect.evaluation.evaluation", SimpleNamespace(standard_evaluation=object()))
    monkeypatch.setitem(sys.modules, "hotvect.sagemaker", SimpleNamespace(SagemakerTrainingExecutor=FakeExecutor))
    monkeypatch.setitem(
        sys.modules,
        "hotvect.backtest",
        SimpleNamespace(
            attach_input_data_config_for_dependencies=lambda **_kwargs: None,
            apply_sagemaker_job_overrides=lambda job_definition, overrides: _recursive_update(
                job_definition, overrides
            ),
            legacy_sagemaker_params_to_overrides=_legacy_to_overrides,
        ),
    )
    monkeypatch.setattr(hv, "resolve_template_path", lambda *_args, **_kwargs: SimpleNamespace(path=None))
    monkeypatch.setattr(hv, "resolve_training_image", lambda **_kwargs: "training-image")
    monkeypatch.setattr(hv, "build_one_shot_training_job_name", lambda **_kwargs: "train-job")
    monkeypatch.setattr(
        hv,
        "build_training_job_definition",
        lambda **_kwargs: {
            "TrainingJobName": "train-job",
            "RoleArn": "arn:aws:iam::123456789012:role/TestRole",
            "OutputDataConfig": {"S3OutputPath": "s3://bucket/output"},
            "AlgorithmSpecification": {"TrainingImage": "training-image", "TrainingInputMode": "FastFile"},
            "HyperParameters": {},
            "InputDataConfig": [],
            "ResourceConfig": {"InstanceType": "ml.m5.large", "InstanceCount": 1, "VolumeSizeInGB": 30},
            "StoppingCondition": {"MaxRuntimeInSeconds": 3600},
        },
    )

    args = SimpleNamespace(
        sagemaker=True,
        sagemaker_config=None,
        sagemaker_job_prefix="ml-exp-test",
        algorithm_jar=tmp_path / "algo.jar",
        algorithm_name="example-algorithm",
        algorithm_override=str(override_path),
        last_test_time="2026-04-14",
        data_base_dir=str(tmp_path / "data"),
        output_base_dir=None,
        role_arn="arn:aws:iam::123456789012:role/TestRole",
        s3_output_base="s3://bucket/output",
        instance_type="ml.m5.large",
        volume_gb=None,
        max_runtime_seconds=None,
        training_image=None,
        assume_role_arn=None,
        extra_jvm_args=None,
        cache_refresh=False,
        cache_base_dir=None,
        cache_scope=None,
        performance_test_samples=None,
        performance_test_sample_pool_size=None,
        auto_attach_data=False,
        auto_attach_data_default_s3_base=None,
        auto_attach_data_environment="production",
        max_threads=None,
    )

    hv.TrainCommand().execute(args)

    assert captured["ran"] is True
    assert captured["payload"] == {
        "hyperparameter_version": "remote-test",
        "dependencies": {
            "example-ranking-algorithm-engagement-model": {
                "number_of_training_days": 1,
                "dependencies": {"example-feature-state": {"source_data": {"training_data": {"number_of_days": 1}}}},
            }
        },
        "sagemaker_training_job_definition": {
            "EnableManagedSpotTraining": True,
            "StoppingCondition": {"MaxWaitTimeInSeconds": 172800},
        },
    }
    assert captured["job_def"]["EnableManagedSpotTraining"] is True
    assert captured["job_def"]["StoppingCondition"]["MaxWaitTimeInSeconds"] == 172800
    assert captured["job_def"]["ResourceConfig"]["InstanceType"] == "ml.m5.large"
    assert captured["job_def"]["ResourceConfig"]["VolumeSizeInGB"] == 40
    assert captured["job_def"]["StoppingCondition"]["MaxRuntimeInSeconds"] == 86400
    assert captured["jvm_options"] == ["-XX:MaxRAMPercentage=80"]
