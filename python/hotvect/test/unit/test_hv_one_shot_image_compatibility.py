import importlib.util
import sys
from importlib.machinery import SourceFileLoader
from pathlib import Path
from types import SimpleNamespace

import pytest

import hotvect.sagemaker_tasks as sagemaker_tasks
from hotvect.offline_source_manifest import StagedLegacyOneShotSource, StagedOfflineAlgorithmSource
from hotvect.offline_task import DirectAlgorithmSource, EmsSnapshotSource, FixedCompositionSource
from hotvect.sagemaker_contracts import OneShotSagemakerHyperparameters


def _load_hv_module():
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "bin" / "hv"
        if candidate.exists():
            loader = SourceFileLoader("hv_cli_one_shot_image_compatibility", str(candidate))
            spec = importlib.util.spec_from_loader(loader.name, loader)
            assert spec is not None
            module = importlib.util.module_from_spec(spec)
            loader.exec_module(module)
            return module
    raise FileNotFoundError("Could not locate bin/hv relative to test file")


def _args(tmp_path: Path, training_image: str) -> SimpleNamespace:
    return SimpleNamespace(
        sagemaker_job_prefix="compatibility-test",
        source_s3_uri="s3://bucket/source/",
        sagemaker_config=None,
        role_arn="arn:aws:iam::123456789012:role/TestRole",
        s3_output_base="s3://bucket/output/",
        instance_type="ml.m5.large",
        volume_gb=None,
        max_runtime_seconds=None,
        training_image=training_image,
        parameter_s3_uri="s3://bucket/parameters.zip",
        algorithm_override=None,
        assume_role_arn=None,
        compression="none",
        include_feature_store_responses=False,
        samples=None,
        max_threads=None,
        target_rps=None,
        target_throughput_fraction=None,
        workload_mode=None,
        log_features=False,
        ordered=False,
        unordered=False,
        writer_num_shards=None,
        algorithm_jar=tmp_path / "algorithm.jar",
        algorithm_name="ranker",
    )


def _install_submission_fakes(monkeypatch: pytest.MonkeyPatch, hv, captured: dict) -> None:
    class FakeExecutor:
        def __init__(self, training_job_definition, output_slug, role_arn_to_assume):
            del output_slug, role_arn_to_assume
            captured["job_def"] = training_job_definition
            captured["executor_created"] = True
            self.hyperparameters = training_job_definition["HyperParameters"]
            self.hyperparameters["s3_uri_result_file"] = "s3://bucket/output/result.json"
            self.hyperparameters["s3_uri_metadata"] = "s3://bucket/output/metadata/"
            self.training_job_name = training_job_definition["TrainingJobName"]
            self.sagemaker_output_s3_path = "s3://bucket/output/job"
            self.s3_client = object()

        def run(self):
            return {"TrainingJobArn": "arn:aws:sagemaker:job/compatibility-test"}

        def build_submission_manifest(self, _response):
            return {"training_job_name": self.training_job_name}

    monkeypatch.setitem(sys.modules, "hotvect.sagemaker", SimpleNamespace(OneShotSagemakerExecutor=FakeExecutor))
    monkeypatch.setattr(hv, "resolve_template_path", lambda *_args, **_kwargs: SimpleNamespace(path=None))
    monkeypatch.setattr(hv, "build_one_shot_training_job_name", lambda **_kwargs: "compatibility-test")
    monkeypatch.setattr(
        hv,
        "build_one_shot_effective_algorithm_definition",
        lambda **_kwargs: {
            "algorithm_name": "ranker",
            "algorithm_version": "1.0.0",
            "hyperparameter_version": "hp-1",
        },
    )


def test_old_direct_image_submits_original_jar_definition_contract(monkeypatch, tmp_path: Path) -> None:
    hv = _load_hv_module()
    captured = {}
    _install_submission_fakes(monkeypatch, hv, captured)
    staged = StagedLegacyOneShotSource(
        algorithm_jar_s3_uri="s3://bucket/staged/algorithm.jar",
        algorithm_definition_s3_uri="s3://bucket/staged/effective-definition.json",
        parameter_s3_uri="s3://bucket/staged/parameters.zip",
    )
    monkeypatch.setattr(hv, "stage_legacy_one_shot_source", lambda *_args, **_kwargs: staged)
    monkeypatch.setattr(
        hv,
        "stage_offline_algorithm_source",
        lambda *_args, **_kwargs: pytest.fail("legacy images must not stage an offline-source manifest"),
    )
    args = _args(tmp_path, "registry.example/hotvect:10.41.1")

    hv._submit_one_shot_sagemaker_job(
        task="predict",
        task_kind_short="pred",
        args=args,
        algorithm_source=DirectAlgorithmSource(args.algorithm_jar, args.algorithm_name),
    )

    hyperparameters = captured["job_def"]["HyperParameters"]
    assert hyperparameters["s3_uri_algorithm_jar"] == staged.algorithm_jar_s3_uri
    assert hyperparameters["s3_uri_algorithm_definition"] == staged.algorithm_definition_s3_uri
    assert hyperparameters["s3_uri_parameter_zip"] == staged.parameter_s3_uri
    assert "hotvect_offline_source_manifest_s3_uri" not in hyperparameters


def test_current_direct_image_submits_offline_source_manifest_contract(monkeypatch, tmp_path: Path) -> None:
    hv = _load_hv_module()
    captured = {}
    _install_submission_fakes(monkeypatch, hv, captured)
    monkeypatch.setattr(
        hv,
        "stage_legacy_one_shot_source",
        lambda *_args, **_kwargs: pytest.fail("current images must not stage the legacy contract"),
    )

    def stage(source, **_kwargs):
        captured["staged_source"] = source
        return StagedOfflineAlgorithmSource(
            manifest_s3_uri="s3://bucket/staged/manifest.json",
            parameter_s3_uri="s3://bucket/parameters.zip",
        )

    monkeypatch.setattr(
        hv,
        "stage_offline_algorithm_source",
        stage,
    )
    args = _args(tmp_path, "registry.example/hotvect:10.49.0")
    source = DirectAlgorithmSource(
        args.algorithm_jar,
        args.algorithm_name,
        domain_model_jars=(tmp_path / "domain.jar",),
    )

    hv._submit_one_shot_sagemaker_job(
        task="predict",
        task_kind_short="pred",
        args=args,
        algorithm_source=source,
    )

    assert captured["staged_source"] == source
    hyperparameters = captured["job_def"]["HyperParameters"]
    assert hyperparameters["hotvect_offline_source_manifest_s3_uri"] == "s3://bucket/staged/manifest.json"
    assert "s3_uri_algorithm_jar" not in hyperparameters
    assert "s3_uri_algorithm_definition" not in hyperparameters


def test_old_direct_image_rejects_domain_model_jars_before_executor_creation(monkeypatch, tmp_path: Path) -> None:
    hv = _load_hv_module()
    captured = {}
    _install_submission_fakes(monkeypatch, hv, captured)
    args = _args(tmp_path, "registry.example/hotvect:10.48.9")

    with pytest.raises(ValueError, match=r"direct --domain-model-jar execution requires.*>= 10\.49\.0"):
        hv._submit_one_shot_sagemaker_job(
            task="predict",
            task_kind_short="pred",
            args=args,
            algorithm_source=DirectAlgorithmSource(
                args.algorithm_jar,
                args.algorithm_name,
                domain_model_jars=(tmp_path / "domain.jar",),
            ),
        )

    assert "executor_created" not in captured


def test_current_remote_performance_test_records_staged_local_parameter_uri(monkeypatch, tmp_path: Path) -> None:
    hv = _load_hv_module()
    captured = {}
    _install_submission_fakes(monkeypatch, hv, captured)
    local_parameter_path = tmp_path / "parameters.zip"
    staged_parameter_s3_uri = "s3://bucket/staged/local-parameters.zip"

    def _stage(source, **_kwargs):
        assert source.parameter_path == local_parameter_path
        return StagedOfflineAlgorithmSource(
            manifest_s3_uri="s3://bucket/staged/manifest.json",
            parameter_s3_uri=staged_parameter_s3_uri,
        )

    monkeypatch.setattr(hv, "stage_offline_algorithm_source", _stage)
    args = _args(tmp_path, "registry.example/hotvect:10.49.0")
    args.parameter_s3_uri = None

    hv._submit_one_shot_sagemaker_job(
        task="performance-test",
        task_kind_short="perf",
        args=args,
        algorithm_source=DirectAlgorithmSource(args.algorithm_jar, args.algorithm_name, local_parameter_path),
    )

    hyperparameters = captured["job_def"]["HyperParameters"]
    assert hyperparameters["s3_uri_parameter_zip"] == staged_parameter_s3_uri
    request_hp = OneShotSagemakerHyperparameters.from_hyperparameters(hyperparameters)
    benchmark_contract = sagemaker_tasks._build_one_shot_benchmark_contract(
        request_hp=request_hp,
        task_metadata={},
        s3_uri_metadata=request_hp.metadata_s3_uri,
        s3_uri_result_file=request_hp.result_file_s3_uri,
        task_output_s3_uri=request_hp.task_output.s3_uri,
    )
    assert benchmark_contract["parameter_s3_uri"] == staged_parameter_s3_uri


@pytest.mark.parametrize(
    "source",
    [
        FixedCompositionSource(Path("composition.json")),
        EmsSnapshotSource("root", Path("ems-state.json"), "/customer_id"),
    ],
)
def test_old_image_rejects_composed_sources_before_executor_creation(monkeypatch, tmp_path: Path, source) -> None:
    hv = _load_hv_module()
    captured = {}
    _install_submission_fakes(monkeypatch, hv, captured)
    args = _args(tmp_path, "registry.example/hotvect:10.48.9")

    with pytest.raises(ValueError, match=r"requires a Hotvect training image >= 10\.49\.0"):
        hv._submit_one_shot_sagemaker_job(
            task="predict",
            task_kind_short="pred",
            args=args,
            algorithm_source=source,
        )

    assert "executor_created" not in captured


def test_unversioned_image_is_rejected_before_executor_creation(monkeypatch, tmp_path: Path) -> None:
    hv = _load_hv_module()
    captured = {}
    _install_submission_fakes(monkeypatch, hv, captured)
    args = _args(tmp_path, "registry.example/hotvect:latest")

    with pytest.raises(ValueError, match="requires a versioned Hotvect training image tag"):
        hv._submit_one_shot_sagemaker_job(
            task="predict",
            task_kind_short="pred",
            args=args,
            algorithm_source=DirectAlgorithmSource(args.algorithm_jar, args.algorithm_name),
        )

    assert "executor_created" not in captured
