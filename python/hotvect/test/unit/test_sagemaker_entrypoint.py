import importlib.util
import sys
from importlib.machinery import SourceFileLoader
from pathlib import Path
from types import ModuleType, SimpleNamespace


def _load_entrypoint_module(monkeypatch):
    sagemaker_training_module = ModuleType("sagemaker_training")
    sagemaker_training_environment_module = ModuleType("sagemaker_training.environment")
    sagemaker_training_environment_module.Environment = object
    s3fs_module = ModuleType("s3fs")
    s3fs_module.S3FileSystem = object
    monkeypatch.setitem(sys.modules, "sagemaker_training", sagemaker_training_module)
    monkeypatch.setitem(sys.modules, "sagemaker_training.environment", sagemaker_training_environment_module)
    monkeypatch.setitem(sys.modules, "s3fs", s3fs_module)

    for parent in Path(__file__).resolve().parents:
        candidate = parent / "bin" / "sagemaker-entrypoint"
        if candidate.exists():
            loader = SourceFileLoader("sagemaker_entrypoint", str(candidate))
            spec = importlib.util.spec_from_loader(loader.name, loader)
            assert spec is not None
            module = importlib.util.module_from_spec(spec)
            loader.exec_module(module)
            return module
    raise FileNotFoundError("Could not locate bin/sagemaker-entrypoint relative to test file")


def test_train_uses_experiment_management_updater_agent(monkeypatch):
    module = _load_entrypoint_module(monkeypatch)
    captured = {}

    class FakeRebuilder:
        def __init__(self):
            self.sagemaker_env = SimpleNamespace(
                hyperparameters={
                    module.SHOULD_UPDATE_EMS_PARAMETER_VAR: True,
                    module.EMS_S3_OUTPUT_PREFIX_VAR: "s3://bucket/output",
                    module.EMS_URI_VAR: "https://ems.example",
                    module.ZALANDO_TOKEN_SECRET_ID_VAR: "secret-id",
                }
            )
            self.algorithm_pipeline = "pipeline"

        def rebuild_pipeline_and_run(self):
            captured["rebuilt"] = True

    class FakeUpdater:
        def __init__(self, *, algorithm_pipeline, output_s3_path, ems_uri, secret_id):
            captured["init"] = {
                "algorithm_pipeline": algorithm_pipeline,
                "output_s3_path": output_s3_path,
                "ems_uri": ems_uri,
                "secret_id": secret_id,
            }

        def upload_result_and_update_ems(self):
            captured["uploaded"] = True

    monkeypatch.setattr(module, "SagemakerAlgorithmPipelineRebuilder", FakeRebuilder)
    monkeypatch.setattr(module, "ExperimentManagementUpdaterAgent", FakeUpdater)
    monkeypatch.setattr(module, "disable_writing_sagemaker_tar_files", lambda: captured.setdefault("disabled", True))

    module.train()

    assert captured["rebuilt"] is True
    assert captured["disabled"] is True
    assert captured["init"] == {
        "algorithm_pipeline": "pipeline",
        "output_s3_path": "s3://bucket/output",
        "ems_uri": "https://ems.example",
        "secret_id": "secret-id",
    }
    assert captured["uploaded"] is True
