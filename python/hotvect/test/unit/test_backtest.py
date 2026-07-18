import copy
import datetime
import io
import json
import os.path
import sys
import tarfile
import types
from pathlib import Path

import pytest

from hotvect.backtest import list_output_dirs
from hotvect.evaluation.conversion import extract_evaluation

example_result_base_dir = Path(os.path.dirname(os.path.realpath(__file__))) / "testfiles" / "meta"


def test_read_results():
    expected = {
        "algorithm_id": "example-algorithm@1.1.1",
        "algorithm.pr_auc": {
            "value": 0.005562758750636963,
            "ci95_lower": 0.0053649898987606325,
            "ci95_upper": 0.006263398258247414,
        },
        "algorithm.roc_auc": {
            "value": 0.7954481367249178,
            "ci95_lower": 0.7924082107414825,
            "ci95_upper": 0.7977865292570848,
        },
        "diversity@10": {"value": 0.5389605324569872},
        "diversity@30": {"value": 0.31078265891765283},
        "diversity@5": {"value": 0.7129776800271087},
        "impression.map_at_10": {"value": 0.005073945326479579},
        "impression.map_at_50": {"value": 0.005326563142979179},
        "impression.map_at_all": {"value": 0.0053234890023868536},
        "impression.ndcg_at_10": {"value": 0.006412982533851097},
        "impression.ndcg_at_50": {"value": 0.0085194559010677},
        "impression.ndcg_at_all": {"value": 0.008574699913360101},
        "map_at_10": {"value": 0.005358904300327959},
        "map_at_50": {"value": 0.00556907716885868},
        "map_at_all": {"value": 0.00556959610794158},
        "max_memory_usage": 12341234,
        "mean_throughput": 1234,
        "ndcg_at_10": {"value": 0.006818835796431752},
        "ndcg_at_50": {"value": 0.008758071563963004},
        "ndcg_at_all": {"value": 0.008805847221075294},
        "p50": 626158.0,
        "p75": 1401592.6,
        "p95": 4464158.4,
        "p99": 11725205.4,
        "p999": 35302789.4,
        "pr_auc": {
            "value": 0.0060868409998212965,
            "ci95_lower": 0.005865690406792519,
            "ci95_upper": 0.006414071194163521,
        },
        "roc_auc": {
            "value": 0.8196221521258884,
            "ci95_lower": 0.8159905500571191,
            "ci95_upper": 0.8219820169826263,
        },
        "test_date": datetime.datetime(2022, 9, 3, 1, 2, 3, 123456, tzinfo=datetime.UTC),
    }

    jsons = list_output_dirs(
        str(example_result_base_dir),
        algorithm_name_pattern="example*",
        algorithm_version_pattern="1.*",
        from_including_test_date=datetime.date.fromisoformat("2022-09-03"),
        to_including_test_date=datetime.date.fromisoformat("2022-09-03"),
    )
    for result in jsons:
        with open(os.path.join(result, "result.json")) as f:
            parsed = json.load(f)
            assert extract_evaluation(parsed) == expected


def test_read_results_of_range():
    jsons = list_output_dirs(
        str(example_result_base_dir),
        from_including_test_date=datetime.date(2022, 9, 3),
        to_including_test_date=datetime.date(2022, 9, 3),
    )
    assert len(jsons) == 1
    assert str(jsons[0]).endswith("example-algorithm@1.1.1/last_test_date_2022-09-03")


def test_legacy_sagemaker_params_to_overrides():
    from hotvect.backtest import legacy_sagemaker_params_to_overrides

    params = {
        "instance_type": "ml.m5.12xlarge",
        "volume_size_in_gb": 150,
        "max_runtime": 15000,
    }
    overrides = legacy_sagemaker_params_to_overrides(params)
    assert overrides["ResourceConfig"]["InstanceType"] == "ml.m5.12xlarge"
    assert overrides["ResourceConfig"]["VolumeSizeInGB"] == 150
    assert overrides["StoppingCondition"]["MaxRuntimeInSeconds"] == 15000

    params_partial = {
        "instance_type": "ml.c5.9xlarge",
    }
    overrides_partial = legacy_sagemaker_params_to_overrides(params_partial)
    assert overrides_partial["ResourceConfig"]["InstanceType"] == "ml.c5.9xlarge"
    assert "StoppingCondition" not in overrides_partial
    assert legacy_sagemaker_params_to_overrides(None) is None


def test_sagemaker_params_precedence():
    from hotvect.backtest import (
        apply_sagemaker_job_overrides,
        apply_training_container,
        legacy_sagemaker_params_to_overrides,
    )

    # Simulate precedence: Template < Algorithm declaration
    # Start with template
    job_definition = {
        "AlgorithmSpecification": {
            "TrainingImage": "registry.example/hotvect:9.29.0",
        },
        "ResourceConfig": {"InstanceType": "ml.m5.xlarge", "VolumeSizeInGB": 50},  # Template default
        "StoppingCondition": {"MaxRuntimeInSeconds": 3600},
    }

    # Apply algorithm parameters via legacy shim
    algorithm_params = {"instance_type": "ml.m5.12xlarge", "max_runtime": 15000}  # Algorithm declares this
    overrides = legacy_sagemaker_params_to_overrides(algorithm_params)
    apply_sagemaker_job_overrides(job_definition, overrides)

    assert job_definition["ResourceConfig"]["InstanceType"] == "ml.m5.12xlarge"
    assert job_definition["ResourceConfig"]["VolumeSizeInGB"] == 50  # Unchanged
    assert job_definition["StoppingCondition"]["MaxRuntimeInSeconds"] == 15000
    assert job_definition["AlgorithmSpecification"]["TrainingImage"] == "registry.example/hotvect:9.29.0"

    # Apply algorithm-declared training container above the template.
    algorithm_definition = {"training_container": "registry.example/hotvect:10.11.0"}
    apply_training_container(job_definition, algorithm_definition)
    assert job_definition["AlgorithmSpecification"]["TrainingImage"] == "registry.example/hotvect:10.11.0"


def test_apply_training_container_handles_missing_spec():
    from hotvect.backtest import apply_training_container

    job_definition = {}
    algorithm_definition = {"training_container": "registry.example/hotvect:10.11.0"}

    apply_training_container(job_definition, algorithm_definition)
    assert job_definition["AlgorithmSpecification"]["TrainingImage"] == "registry.example/hotvect:10.11.0"


def test_algorithm_sagemaker_job_configuration_prefers_native_training_image_over_legacy_training_container():
    from hotvect.backtest import apply_algorithm_sagemaker_job_configuration

    job_definition = {"AlgorithmSpecification": {"TrainingImage": "template-image"}}
    algorithm_definition = {
        "training_container": "legacy-training-container",
        "sagemaker_training_job_definition": {"AlgorithmSpecification": {"TrainingImage": "native-training-image"}},
    }

    apply_algorithm_sagemaker_job_configuration(job_definition, algorithm_definition)

    assert job_definition["AlgorithmSpecification"]["TrainingImage"] == "native-training-image"


def test_apply_sagemaker_job_overrides():
    from hotvect.backtest import apply_sagemaker_job_overrides

    job_definition = {
        "AlgorithmSpecification": {
            "TrainingInputMode": "File",
        },
        "ResourceConfig": {
            "InstanceType": "ml.m5.xlarge",
            "InstanceCount": 1,
        },
        "Tags": [{"Key": "team", "Value": "ml"}],
    }

    overrides = {
        "AlgorithmSpecification": {"MetricDefinitions": [{"Name": "ndcg_at_all", "Regex": "'ndcg_at_all': (.*?)[,}]"}]},
        "ResourceConfig": {
            "InstanceCount": 2,
            "VolumeSizeInGB": 200,
        },
        "Tags": [{"Key": "environment", "Value": "staging"}],
    }

    apply_sagemaker_job_overrides(job_definition, overrides)

    assert job_definition["AlgorithmSpecification"]["TrainingInputMode"] == "File"
    assert job_definition["AlgorithmSpecification"]["MetricDefinitions"][0]["Name"] == "ndcg_at_all"
    assert job_definition["ResourceConfig"]["InstanceType"] == "ml.m5.xlarge"
    assert job_definition["ResourceConfig"]["InstanceCount"] == 2
    assert job_definition["ResourceConfig"]["VolumeSizeInGB"] == 200
    # Tags should be replaced with override contents because recursive update sets the key
    assert job_definition["Tags"] == overrides["Tags"]


def test_execute_on_sagemaker_rejects_overlong_training_job_name_before_submit(monkeypatch, tmp_path):
    import hotvect.backtest as backtest_module
    from hotvect.utils import AlgorithmSpec

    executor_init_calls = []

    class DummyAlgorithmPipeline:
        def __init__(
            self,
            *,
            algorithm_pipeline_context,
            algorithm_definition,
            last_test_time,
            evaluation_func,
            parameter_version,
            execute_performance_test,
            encode_test_data,
            execute_audit,
        ):
            assert algorithm_definition == ("example-algorithm", {"hyperparameter_version": "hp-v1"})
            self.algorithm_definition = {"algorithm_name": "example-algorithm", "algorithm_version": "1.0.0"}
            self.parameter_version = parameter_version
            self.last_test_time = last_test_time

    class DummyExecutor:
        def __init__(self, *, algorithm_pipeline, training_job_definition, role_arn_to_assume):
            executor_init_calls.append(training_job_definition["TrainingJobName"])
            self.algorithm_pipeline = algorithm_pipeline
            self.training_job_name = training_job_definition["TrainingJobName"]

        def run(self):
            raise AssertionError("executor should not run when TrainingJobName validation fails")

    monkeypatch.setattr(backtest_module, "AlgorithmPipeline", DummyAlgorithmPipeline)
    monkeypatch.setattr(backtest_module.BacktestPipeline, "_attach_input_data_config", lambda *args, **kwargs: None)
    monkeypatch.setitem(
        sys.modules, "hotvect.sagemaker", types.SimpleNamespace(SagemakerTrainingExecutor=DummyExecutor)
    )

    pipeline = backtest_module.BacktestPipeline(
        algo_repo_url="https://example.invalid/repo.git",
        algo_git_reference="deadbeef",
        data_base_dir=str(tmp_path / "data"),
        output_base_dir=str(tmp_path / "out"),
        hyperparameter_base_dir=str(tmp_path / "hp"),
        evaluation_function=lambda path: {},
        last_test_time=datetime.date(2026, 3, 26),
        number_of_runs=1,
    )

    with pytest.raises(ValueError, match="SageMaker TrainingJobName must be <= 63 characters"):
        pipeline._execute_on_sagemaker(
            algorithm_spec=AlgorithmSpec("example-algorithm", tmp_path / "algo.jar", "abcdef1234567890"),
            algorithm_definition_override={"hyperparameter_version": "hp-v1"},
            last_test_time=datetime.date(2026, 3, 26),
            number_of_runs=1,
            system_performance_test=True,
            jvm_options=[],
            clean=True,
            sagemaker_training_job_definition={"TrainingJobName": "bt-" + ("a" * 39)},
            role_arn_to_assume=None,
        )

    assert executor_init_calls == []


def test_execute_on_sagemaker_returns_submission_manifest(monkeypatch, tmp_path):
    import hotvect.backtest as backtest_module
    from hotvect.utils import AlgorithmSpec

    class DummyAlgorithmPipeline:
        def __init__(
            self,
            *,
            algorithm_pipeline_context,
            algorithm_definition,
            last_test_time,
            evaluation_func,
            parameter_version,
            execute_performance_test,
            encode_test_data,
            execute_audit,
        ):
            assert algorithm_definition == ("example-algorithm", {"hyperparameter_version": "hp-v1"})
            self.algorithm_definition = {"algorithm_name": "example-algorithm", "algorithm_version": "1.0.0"}
            self.parameter_version = parameter_version
            self.last_test_time = last_test_time

        def hyperparameter_slug(self):
            return "pv1"

    class DummyExecutor:
        def __init__(self, *, algorithm_pipeline, training_job_definition, role_arn_to_assume):
            self.algorithm_pipeline = algorithm_pipeline
            self.training_job_name = training_job_definition["TrainingJobName"]

        def run(self):
            return {"TrainingJobArn": f"arn:aws:sagemaker:eu-central-1:123:training-job/{self.training_job_name}"}

        def build_submission_manifest(self, submission_response):
            return {
                "training_job_name": self.training_job_name,
                "training_job_arn": submission_response["TrainingJobArn"],
                "submission_status": "submitted",
                "s3_uri_result_file": f"s3://bucket/out/{self.training_job_name}/pv1/result.json",
                "s3_uri_metadata": f"s3://bucket/out/{self.training_job_name}/pv1/metadata",
            }

    monkeypatch.setattr(backtest_module, "AlgorithmPipeline", DummyAlgorithmPipeline)
    monkeypatch.setattr(backtest_module.BacktestPipeline, "_attach_input_data_config", lambda *args, **kwargs: None)
    monkeypatch.setitem(
        sys.modules, "hotvect.sagemaker", types.SimpleNamespace(SagemakerTrainingExecutor=DummyExecutor)
    )

    pipeline = backtest_module.BacktestPipeline(
        algo_repo_url="https://example.invalid/repo.git",
        algo_git_reference="deadbeef",
        data_base_dir=str(tmp_path / "data"),
        output_base_dir=str(tmp_path / "out"),
        hyperparameter_base_dir=str(tmp_path / "hp"),
        evaluation_function=lambda path: {},
        last_test_time=datetime.date(2026, 3, 26),
        number_of_runs=1,
    )

    result = pipeline._execute_on_sagemaker(
        algorithm_spec=AlgorithmSpec("example-algorithm", tmp_path / "algo.jar", "abcdef1234567890"),
        algorithm_definition_override={"hyperparameter_version": "hp-v1"},
        last_test_time=datetime.date(2026, 3, 26),
        number_of_runs=1,
        system_performance_test=True,
        jvm_options=[],
        clean=True,
        sagemaker_training_job_definition={
            "TrainingJobName": "bt-demo",
            "OutputDataConfig": {"S3OutputPath": "s3://bucket/out"},
        },
        role_arn_to_assume=None,
    )

    assert result.backtest_iteration_results is not None
    assert result.backtest_iteration_results[0].result["training_job_name"].startswith("bt-demo")
    assert (
        result.backtest_iteration_results[0]
        .result["training_job_arn"]
        .endswith(result.backtest_iteration_results[0].result["training_job_name"])
    )
    assert result.backtest_iteration_results[0].result["submission_status"] == "submitted"


def test_execute_on_sagemaker_reapplies_cli_job_overrides_after_algorithm_overrides(monkeypatch, tmp_path):
    import hotvect.backtest as backtest_module
    from hotvect.utils import AlgorithmSpec

    captured = {}

    class DummyAlgorithmPipeline:
        def __init__(
            self,
            *,
            algorithm_pipeline_context,
            algorithm_definition,
            last_test_time,
            evaluation_func,
            parameter_version,
            execute_performance_test,
            encode_test_data,
            execute_audit,
        ):
            self.algorithm_definition = {
                "algorithm_name": "example-algorithm",
                "algorithm_version": "1.0.0",
                "training_container": "algo-training-image",
                "sagemaker_training_job_definition": {
                    "AlgorithmSpecification": {"TrainingImage": "algo-job-image"},
                    "ResourceConfig": {"InstanceType": "ml.m6i.12xlarge"},
                    "StoppingCondition": {"MaxWaitTimeInSeconds": 172800},
                },
                "sagemaker_execution_parameters": {
                    "volume_size_in_gb": 64,
                    "max_runtime": 7200,
                },
            }
            self.parameter_version = parameter_version
            self.last_test_time = last_test_time

        def hyperparameter_slug(self):
            return "pv1"

    class DummyExecutor:
        def __init__(self, *, algorithm_pipeline, training_job_definition, role_arn_to_assume):
            captured["job_def"] = training_job_definition
            self.algorithm_pipeline = algorithm_pipeline
            self.training_job_name = training_job_definition["TrainingJobName"]

        def run(self):
            return {"TrainingJobArn": f"arn:aws:sagemaker:eu-central-1:123:training-job/{self.training_job_name}"}

        def build_submission_manifest(self, submission_response):
            return {
                "training_job_name": self.training_job_name,
                "training_job_arn": submission_response["TrainingJobArn"],
                "submission_status": "submitted",
            }

    monkeypatch.setattr(backtest_module, "AlgorithmPipeline", DummyAlgorithmPipeline)
    monkeypatch.setattr(backtest_module.BacktestPipeline, "_attach_input_data_config", lambda *args, **kwargs: None)
    monkeypatch.setitem(
        sys.modules, "hotvect.sagemaker", types.SimpleNamespace(SagemakerTrainingExecutor=DummyExecutor)
    )

    pipeline = backtest_module.BacktestPipeline(
        algo_repo_url="https://example.invalid/repo.git",
        algo_git_reference="deadbeef",
        data_base_dir=str(tmp_path / "data"),
        output_base_dir=str(tmp_path / "out"),
        hyperparameter_base_dir=str(tmp_path / "hp"),
        evaluation_function=lambda path: {},
        last_test_time=datetime.date(2026, 3, 26),
        number_of_runs=1,
    )

    pipeline._execute_on_sagemaker(
        algorithm_spec=AlgorithmSpec("example-algorithm", tmp_path / "algo.jar", "abcdef1234567890"),
        algorithm_definition_override=None,
        last_test_time=datetime.date(2026, 3, 26),
        number_of_runs=1,
        system_performance_test=True,
        jvm_options=[],
        clean=True,
        sagemaker_training_job_definition={
            "TrainingJobName": "bt-demo",
            "OutputDataConfig": {"S3OutputPath": "s3://template/out"},
            "AlgorithmSpecification": {"TrainingImage": "template-image"},
            "ResourceConfig": {"InstanceType": "ml.t3.medium", "VolumeSizeInGB": 30},
            "StoppingCondition": {"MaxRuntimeInSeconds": 3600},
        },
        sagemaker_cli_job_overrides={
            "AlgorithmSpecification": {"TrainingImage": "cli-image"},
            "ResourceConfig": {"InstanceType": "ml.c7i.2xlarge"},
            "StoppingCondition": {"MaxRuntimeInSeconds": 1800},
        },
        role_arn_to_assume=None,
    )

    assert captured["job_def"]["AlgorithmSpecification"]["TrainingImage"] == "cli-image"
    assert captured["job_def"]["ResourceConfig"]["InstanceType"] == "ml.c7i.2xlarge"
    assert captured["job_def"]["ResourceConfig"]["VolumeSizeInGB"] == 64
    assert captured["job_def"]["StoppingCondition"]["MaxRuntimeInSeconds"] == 1800
    assert captured["job_def"]["StoppingCondition"]["MaxWaitTimeInSeconds"] == 172800


def test_execute_on_sagemaker_uses_algorithm_training_container_over_template_training_image(monkeypatch, tmp_path):
    import hotvect.backtest as backtest_module
    from hotvect.utils import AlgorithmSpec

    captured = {}

    class DummyAlgorithmPipeline:
        def __init__(
            self,
            *,
            algorithm_pipeline_context,
            algorithm_definition,
            last_test_time,
            evaluation_func,
            parameter_version,
            execute_performance_test,
            encode_test_data,
            execute_audit,
        ):
            self.algorithm_definition = {
                "algorithm_name": "example-algorithm",
                "algorithm_version": "1.0.0",
                "training_container": "legacy-algo-image",
            }
            self.parameter_version = parameter_version
            self.last_test_time = last_test_time

        def hyperparameter_slug(self):
            return "pv1"

    class DummyExecutor:
        def __init__(self, *, algorithm_pipeline, training_job_definition, role_arn_to_assume):
            captured["job_def"] = training_job_definition
            self.algorithm_pipeline = algorithm_pipeline
            self.training_job_name = training_job_definition["TrainingJobName"]

        def run(self):
            return {"TrainingJobArn": f"arn:aws:sagemaker:eu-central-1:123:training-job/{self.training_job_name}"}

        def build_submission_manifest(self, submission_response):
            return {
                "training_job_name": self.training_job_name,
                "training_job_arn": submission_response["TrainingJobArn"],
                "submission_status": "submitted",
            }

    monkeypatch.setattr(backtest_module, "AlgorithmPipeline", DummyAlgorithmPipeline)
    monkeypatch.setattr(backtest_module.BacktestPipeline, "_attach_input_data_config", lambda *args, **kwargs: None)
    monkeypatch.setitem(
        sys.modules, "hotvect.sagemaker", types.SimpleNamespace(SagemakerTrainingExecutor=DummyExecutor)
    )

    pipeline = backtest_module.BacktestPipeline(
        algo_repo_url="https://example.invalid/repo.git",
        algo_git_reference="deadbeef",
        data_base_dir=str(tmp_path / "data"),
        output_base_dir=str(tmp_path / "out"),
        hyperparameter_base_dir=str(tmp_path / "hp"),
        evaluation_function=lambda path: {},
        last_test_time=datetime.date(2026, 3, 26),
        number_of_runs=1,
    )

    pipeline._execute_on_sagemaker(
        algorithm_spec=AlgorithmSpec("example-algorithm", tmp_path / "algo.jar", "abcdef1234567890"),
        algorithm_definition_override=None,
        last_test_time=datetime.date(2026, 3, 26),
        number_of_runs=1,
        system_performance_test=True,
        jvm_options=[],
        clean=True,
        sagemaker_training_job_definition={
            "TrainingJobName": "bt-demo",
            "OutputDataConfig": {"S3OutputPath": "s3://template/out"},
            "AlgorithmSpecification": {"TrainingImage": "template-image"},
            "ResourceConfig": {"InstanceType": "ml.t3.medium", "VolumeSizeInGB": 30},
            "StoppingCondition": {"MaxRuntimeInSeconds": 3600},
        },
        sagemaker_cli_job_overrides={},
        role_arn_to_assume=None,
    )

    assert captured["job_def"]["AlgorithmSpecification"]["TrainingImage"] == "legacy-algo-image"


def test_execute_on_sagemaker_applies_algorithm_override_layer_over_committed_definition(monkeypatch, tmp_path):
    import hotvect.backtest as backtest_module
    from hotvect.utils import AlgorithmSpec

    captured = {}

    class DummyAlgorithmPipeline:
        def __init__(
            self,
            *,
            algorithm_pipeline_context,
            algorithm_definition,
            last_test_time,
            evaluation_func,
            parameter_version,
            execute_performance_test,
            encode_test_data,
            execute_audit,
        ):
            self.committed_algorithm_definition = {
                "algorithm_name": "example-algorithm",
                "algorithm_version": "1.0.0",
                "training_container": "committed-training-container",
                "sagemaker_training_job_definition": {
                    "AlgorithmSpecification": {"TrainingImage": "committed-job-image"},
                    "ResourceConfig": {"InstanceType": "ml.m6i.4xlarge"},
                },
            }
            self.algorithm_definition_override = {
                "training_container": "override-training-container",
                "sagemaker_training_job_definition": {
                    "ResourceConfig": {"VolumeSizeInGB": 128},
                },
            }
            self.algorithm_definition = {
                **self.committed_algorithm_definition,
                "training_container": "override-training-container",
                "ResourceConfig": {"VolumeSizeInGB": 128},
            }
            self.parameter_version = parameter_version
            self.last_test_time = last_test_time

        def hyperparameter_slug(self):
            return "pv1"

    class DummyExecutor:
        def __init__(self, *, algorithm_pipeline, training_job_definition, role_arn_to_assume):
            captured["job_def"] = training_job_definition
            self.algorithm_pipeline = algorithm_pipeline
            self.training_job_name = training_job_definition["TrainingJobName"]

        def run(self):
            return {"TrainingJobArn": f"arn:aws:sagemaker:eu-central-1:123:training-job/{self.training_job_name}"}

        def build_submission_manifest(self, submission_response):
            return {
                "training_job_name": self.training_job_name,
                "training_job_arn": submission_response["TrainingJobArn"],
                "submission_status": "submitted",
            }

    monkeypatch.setattr(backtest_module, "AlgorithmPipeline", DummyAlgorithmPipeline)
    monkeypatch.setattr(backtest_module.BacktestPipeline, "_attach_input_data_config", lambda *args, **kwargs: None)
    monkeypatch.setitem(
        sys.modules, "hotvect.sagemaker", types.SimpleNamespace(SagemakerTrainingExecutor=DummyExecutor)
    )

    pipeline = backtest_module.BacktestPipeline(
        algo_repo_url="https://example.invalid/repo.git",
        algo_git_reference="deadbeef",
        data_base_dir=str(tmp_path / "data"),
        output_base_dir=str(tmp_path / "out"),
        hyperparameter_base_dir=str(tmp_path / "hp"),
        evaluation_function=lambda path: {},
        last_test_time=datetime.date(2026, 3, 26),
        number_of_runs=1,
    )

    pipeline._execute_on_sagemaker(
        algorithm_spec=AlgorithmSpec("example-algorithm", tmp_path / "algo.jar", "abcdef1234567890"),
        algorithm_definition_override={"training_container": "override-training-container"},
        last_test_time=datetime.date(2026, 3, 26),
        number_of_runs=1,
        system_performance_test=True,
        jvm_options=[],
        clean=True,
        sagemaker_training_job_definition={
            "TrainingJobName": "bt-demo",
            "OutputDataConfig": {"S3OutputPath": "s3://template/out"},
            "AlgorithmSpecification": {"TrainingImage": "template-image"},
            "ResourceConfig": {"InstanceType": "ml.t3.medium", "VolumeSizeInGB": 30},
            "StoppingCondition": {"MaxRuntimeInSeconds": 3600},
        },
        sagemaker_cli_job_overrides={},
        role_arn_to_assume=None,
    )

    assert captured["job_def"]["AlgorithmSpecification"]["TrainingImage"] == "override-training-container"
    assert captured["job_def"]["ResourceConfig"]["InstanceType"] == "ml.m6i.4xlarge"
    assert captured["job_def"]["ResourceConfig"]["VolumeSizeInGB"] == 128


def test_execute_on_sagemaker_preserves_explicit_empty_override_layer(monkeypatch, tmp_path):
    import hotvect.backtest as backtest_module
    from hotvect.utils import AlgorithmSpec

    applied_layers = []

    class DummyAlgorithmPipeline:
        def __init__(
            self,
            *,
            algorithm_pipeline_context,
            algorithm_definition,
            last_test_time,
            evaluation_func,
            parameter_version,
            execute_performance_test,
            encode_test_data,
            execute_audit,
        ):
            self.committed_algorithm_definition = {
                "algorithm_name": "example-algorithm",
                "algorithm_version": "1.0.0",
                "training_container": "committed-training-container",
            }
            self.algorithm_definition_override = {}
            self.algorithm_definition = copy.deepcopy(self.committed_algorithm_definition)
            self.parameter_version = parameter_version
            self.last_test_time = last_test_time

        def hyperparameter_slug(self):
            return "pv1"

    class DummyExecutor:
        def __init__(self, *, algorithm_pipeline, training_job_definition, role_arn_to_assume):
            self.algorithm_pipeline = algorithm_pipeline
            self.training_job_name = training_job_definition["TrainingJobName"]

        def run(self):
            return {"TrainingJobArn": f"arn:aws:sagemaker:eu-central-1:123:training-job/{self.training_job_name}"}

        def build_submission_manifest(self, submission_response):
            return {
                "training_job_name": self.training_job_name,
                "training_job_arn": submission_response["TrainingJobArn"],
                "submission_status": "submitted",
            }

    original_apply = backtest_module.apply_algorithm_sagemaker_job_configuration

    def tracking_apply(job_definition, algorithm_definition):
        applied_layers.append(copy.deepcopy(algorithm_definition))
        return original_apply(job_definition, algorithm_definition)

    monkeypatch.setattr(backtest_module, "AlgorithmPipeline", DummyAlgorithmPipeline)
    monkeypatch.setattr(backtest_module, "apply_algorithm_sagemaker_job_configuration", tracking_apply)
    monkeypatch.setattr(backtest_module.BacktestPipeline, "_attach_input_data_config", lambda *args, **kwargs: None)
    monkeypatch.setitem(
        sys.modules, "hotvect.sagemaker", types.SimpleNamespace(SagemakerTrainingExecutor=DummyExecutor)
    )

    pipeline = backtest_module.BacktestPipeline(
        algo_repo_url="https://example.invalid/repo.git",
        algo_git_reference="deadbeef",
        data_base_dir=str(tmp_path / "data"),
        output_base_dir=str(tmp_path / "out"),
        hyperparameter_base_dir=str(tmp_path / "hp"),
        evaluation_function=lambda path: {},
        last_test_time=datetime.date(2026, 3, 26),
        number_of_runs=1,
    )

    pipeline._execute_on_sagemaker(
        algorithm_spec=AlgorithmSpec("example-algorithm", tmp_path / "algo.jar", "abcdef1234567890"),
        algorithm_definition_override={},
        last_test_time=datetime.date(2026, 3, 26),
        number_of_runs=1,
        system_performance_test=True,
        jvm_options=[],
        clean=True,
        sagemaker_training_job_definition={
            "TrainingJobName": "bt-demo",
            "OutputDataConfig": {"S3OutputPath": "s3://template/out"},
            "AlgorithmSpecification": {"TrainingImage": "template-image"},
        },
        sagemaker_cli_job_overrides={},
        role_arn_to_assume=None,
    )

    assert applied_layers == [
        {
            "algorithm_name": "example-algorithm",
            "algorithm_version": "1.0.0",
            "training_container": "committed-training-container",
        },
        {},
    ]


@pytest.mark.parametrize("execution_mode", ["local", "sagemaker"])
@pytest.mark.parametrize("override_fragment", [None, {"training_lag_days": 7}])
def test_run_all_passes_only_override_fragment_to_execution(monkeypatch, tmp_path, execution_mode, override_fragment):
    import hotvect.backtest as backtest_module
    from hotvect.utils import AlgorithmSpec

    effective_definition = {
        "algorithm_name": "demo-algo",
        "algorithm_version": "1.2.3",
        "hyperparameter_version": "hp-v1",
        "dependencies": ["child-model"],
    }
    if override_fragment:
        effective_definition.update(override_fragment)

    algorithm_definition_path = tmp_path / "algorithm-definition.json"
    algorithm_definition_path.write_text(json.dumps(effective_definition))

    pipeline = backtest_module.BacktestPipeline(
        algo_repo_url="https://example.invalid/repo.git",
        algo_git_reference="deadbeef",
        data_base_dir=str(tmp_path / "data"),
        output_base_dir=str(tmp_path / "out"),
        hyperparameter_base_dir=str(tmp_path / "hp"),
        evaluation_function=lambda path: {},
        last_test_time=datetime.date(2026, 3, 26),
        number_of_runs=1,
        algorithm_definition_override=override_fragment,
    )

    monkeypatch.setattr(
        pipeline,
        "_prepare_algorithm_jar",
        lambda _git_reference: AlgorithmSpec("demo-algo", tmp_path / "algo.jar", "abcdef1234567890"),
    )
    monkeypatch.setattr(
        pipeline,
        "_prepare_algorithm_definition",
        lambda _algorithm_spec, _algorithm_definition_override: algorithm_definition_path,
    )

    captured = {}

    def fake_execute_on_local(*, algorithm_definition_override, **_kwargs):
        captured["algorithm_definition_override"] = algorithm_definition_override
        return backtest_module.BacktestResult(algo_git_reference="deadbeef", backtest_iteration_results=[])

    def fake_execute_on_sagemaker(*, algorithm_definition_override, **_kwargs):
        captured["algorithm_definition_override"] = algorithm_definition_override
        return backtest_module.BacktestResult(algo_git_reference="deadbeef", backtest_iteration_results=[])

    if execution_mode == "local":
        monkeypatch.setattr(pipeline, "_execute_on_local", fake_execute_on_local)
        result = pipeline.run_all(clean=True, system_performance_test=True, sagemaker_training_job_definition=None)
    else:
        monkeypatch.setattr(pipeline, "_execute_on_sagemaker", fake_execute_on_sagemaker)
        result = pipeline.run_all(
            clean=True,
            system_performance_test=True,
            sagemaker_training_job_definition={"TrainingJobName": "bt-demo"},
        )

    assert result.algo_git_reference == "deadbeef"
    assert captured["algorithm_definition_override"] == override_fragment


def test_execute_on_sagemaker_uses_effective_hyperparameter_version_for_job_name(monkeypatch, tmp_path):
    import hotvect.backtest as backtest_module
    from hotvect.sagemaker_job_name import compute_hph
    from hotvect.utils import AlgorithmSpec

    captured = {}

    class DummyAlgorithmPipeline:
        def __init__(
            self,
            *,
            algorithm_pipeline_context,
            algorithm_definition,
            last_test_time,
            evaluation_func,
            parameter_version,
            execute_performance_test,
            encode_test_data,
            execute_audit,
        ):
            assert algorithm_definition == ("example-algorithm", None)
            self.algorithm_definition = {"hyperparameter_version": "hp-from-base"}
            self.hyper_parameter_version = "hp-from-base"
            self.parameter_version = parameter_version
            self.last_test_time = last_test_time

    class DummyExecutor:
        def __init__(self, *, algorithm_pipeline, training_job_definition, role_arn_to_assume):
            captured["training_job_name"] = training_job_definition["TrainingJobName"]
            self.algorithm_pipeline = algorithm_pipeline
            self.training_job_name = training_job_definition["TrainingJobName"]

        def run(self):
            return {"TrainingJobArn": f"arn:aws:sagemaker:eu-central-1:123:training-job/{self.training_job_name}"}

        def build_submission_manifest(self, submission_response):
            return {
                "training_job_name": self.training_job_name,
                "training_job_arn": submission_response["TrainingJobArn"],
                "submission_status": "submitted",
            }

    monkeypatch.setattr(backtest_module, "AlgorithmPipeline", DummyAlgorithmPipeline)
    monkeypatch.setattr(backtest_module.BacktestPipeline, "_attach_input_data_config", lambda *args, **kwargs: None)
    monkeypatch.setitem(
        sys.modules, "hotvect.sagemaker", types.SimpleNamespace(SagemakerTrainingExecutor=DummyExecutor)
    )

    pipeline = backtest_module.BacktestPipeline(
        algo_repo_url="https://example.invalid/repo.git",
        algo_git_reference="deadbeef",
        data_base_dir=str(tmp_path / "data"),
        output_base_dir=str(tmp_path / "out"),
        hyperparameter_base_dir=str(tmp_path / "hp"),
        evaluation_function=lambda path: {},
        last_test_time=datetime.date(2026, 3, 26),
        number_of_runs=1,
    )

    pipeline._execute_on_sagemaker(
        algorithm_spec=AlgorithmSpec("example-algorithm", tmp_path / "algo.jar", "abcdef1234567890"),
        algorithm_definition_override=None,
        last_test_time=datetime.date(2026, 3, 26),
        number_of_runs=1,
        system_performance_test=True,
        jvm_options=[],
        clean=True,
        sagemaker_training_job_definition={
            "TrainingJobName": "bt-demo",
            "OutputDataConfig": {"S3OutputPath": "s3://bucket/out"},
        },
        role_arn_to_assume=None,
    )

    assert captured["training_job_name"] == f"bt-demo-abcdef-{compute_hph('hp-from-base')}-2026-03-26"


def test_run_one_cycle_locally_accepts_materialized_algorithm_definition_dict(monkeypatch, tmp_path):
    import hotvect.backtest as backtest_module
    from hotvect.pyhotvect import AlgorithmPipelineContext

    seen = {}

    class DummyAlgorithmPipeline:
        def __init__(
            self,
            *,
            algorithm_pipeline_context,
            algorithm_definition,
            last_test_time,
            parameter_version,
            evaluation_func,
            execute_performance_test,
            encode_test_data,
            execute_audit,
        ):
            seen["algorithm_definition"] = algorithm_definition
            seen["parameter_version"] = parameter_version
            seen["last_test_time"] = last_test_time

        def run_all(self, clean):
            return {"clean": clean}

    monkeypatch.setattr(backtest_module, "AlgorithmPipeline", DummyAlgorithmPipeline)

    result = backtest_module.run_one_cycle_locally(
        context=AlgorithmPipelineContext(
            algorithm_jar_path=tmp_path / "algo.jar",
            data_base_path=tmp_path / "data",
            metadata_base_path=tmp_path / "meta",
            output_base_path=tmp_path / "out",
        ),
        algorithm_definition={"algorithm_name": "example-algorithm", "algorithm_version": "1.2.3", "dependencies": []},
        parameter_version="last_test_date_2026-03-26",
        last_test_time=datetime.date(2026, 3, 26),
        evaluation_function=lambda path: {},
        clean=True,
        system_performance_test=False,
    )

    assert seen["algorithm_definition"]["algorithm_name"] == "example-algorithm"
    assert result.result == {"clean": True}


def test_run_backtest_on_git_reference_cache_refresh_uses_effective_cache_base_dir(monkeypatch):
    from hotvect.backtest import run_backtest_on_git_reference

    captured = {}

    class DummyBacktestPipeline:
        def __init__(self, **kwargs):
            captured["algorithm_definition_override"] = kwargs["algorithm_definition_override"]

        def run_all(self, **_kwargs):
            return None

    monkeypatch.setattr("hotvect.backtest.BacktestPipeline", DummyBacktestPipeline)

    run_backtest_on_git_reference(
        algo_repo_url="https://example.invalid/repo.git",
        algo_git_reference="deadbeef",
        data_base_dir="/tmp/data",
        output_base_dir="/tmp/out",
        hyperparameter_base_dir="/tmp/hp",
        evaluation_function=lambda path: {},
        last_test_time=datetime.date(2026, 2, 17),
        number_of_runs=1,
        algorithm_definition_override={
            "hotvect_execution_parameters": {"cache_base_dir": "/tmp/cache", "cache": "run"}
        },
        cache_refresh=True,
        cache_base_dir=None,
    )

    assert captured["algorithm_definition_override"]["hotvect_execution_parameters"] == {
        "cache_base_dir": "/tmp/cache",
        "cache": "run",
        "cache_refresh": True,
    }


def test_run_backtest_on_git_reference_cache_refresh_does_not_force_run_cache_mode(monkeypatch):
    import hotvect.backtest as backtest_module

    captured = {}

    class DummyBacktestPipeline:
        def __init__(self, **kwargs):
            captured["algorithm_definition_override"] = kwargs["algorithm_definition_override"]

        def run_all(self, **_kwargs):
            return backtest_module.BacktestResult(algo_git_reference="deadbeef")

    monkeypatch.setattr(backtest_module, "BacktestPipeline", DummyBacktestPipeline)

    backtest_module.run_backtest_on_git_reference(
        algo_repo_url="https://example.invalid/repo.git",
        algo_git_reference="deadbeef",
        data_base_dir="/tmp/data",
        output_base_dir="/tmp/out",
        hyperparameter_base_dir="/tmp/hp",
        evaluation_function=lambda path: {},
        last_test_time=datetime.date(2026, 2, 17),
        number_of_runs=1,
        algorithm_definition_override={"hotvect_execution_parameters": {"cache": "partition"}},
        cache_base_dir="s3://bucket/prefix/hotvect-cache",
        cache_refresh=True,
    )

    assert captured["algorithm_definition_override"]["hotvect_execution_parameters"] == {
        "cache": "partition",
        "cache_base_dir": "s3://bucket/prefix/hotvect-cache",
        "cache_scope": "hyperparam",
        "cache_refresh": True,
    }


def test_run_backtest_on_git_references_passes_cache_settings_to_each_git_ref(monkeypatch):
    import hotvect.backtest as backtest_module
    from hotvect.utils import ConcurrencySetting

    calls = []

    def fake_run_backtest_on_git_reference(**kwargs):
        calls.append(kwargs)
        return backtest_module.BacktestResult(algo_git_reference=kwargs["algo_git_reference"])

    class DummyProcessPool:
        def __init__(self, *args, **kwargs):
            pass

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb):
            return False

    monkeypatch.setattr(backtest_module, "run_backtest_on_git_reference", fake_run_backtest_on_git_reference)
    monkeypatch.setattr(backtest_module, "ProcessPool", DummyProcessPool)

    results = backtest_module.run_backtest_on_git_references(
        algo_repo_url="https://example.invalid/repo.git",
        algo_git_references=["ref1", "ref2"],
        data_base_dir="/tmp/data",
        output_base_dir="/tmp/out",
        hyperparameter_base_dir="/tmp/hp",
        evaluation_function=lambda path: {},
        last_test_time=datetime.date(2026, 2, 17),
        number_of_runs=1,
        concurrency_setting=ConcurrencySetting(
            total_backtest_pipelines=1,
            nproc_per_backtest_pipeline=1,
            threads_per_backtest_process=1,
            queue_length_for_threads=1,
        ),
        cache_base_dir="s3://bucket/prefix/hotvect-cache",
        cache_scope="minor",
        cache_refresh=True,
        training_image_override="registry.example/hotvect:10.11.0",
    )

    assert [r.algo_git_reference for r in results] == ["ref1", "ref2"]
    assert len(calls) == 2
    for call in calls:
        assert call["cache_base_dir"] == "s3://bucket/prefix/hotvect-cache"
        assert call["cache_scope"] == "minor"
        assert call["cache_refresh"] is True


def test_run_backtest_on_git_references_preserves_explicit_empty_override_dict(monkeypatch):
    import hotvect.backtest as backtest_module
    from hotvect.utils import ConcurrencySetting

    calls = []

    def fake_run_backtest_on_git_reference(**kwargs):
        calls.append(kwargs)
        return backtest_module.BacktestResult(algo_git_reference=kwargs["algo_git_reference"])

    monkeypatch.setattr(backtest_module, "run_backtest_on_git_reference", fake_run_backtest_on_git_reference)

    results = backtest_module.run_backtest_on_git_references(
        algo_repo_url="https://example.invalid/repo.git",
        algo_git_references=[("ref1", {}), ("ref2", {"training_container": "override-image"})],
        data_base_dir="/tmp/data",
        output_base_dir="/tmp/out",
        hyperparameter_base_dir="/tmp/hp",
        evaluation_function=lambda path: {},
        last_test_time=datetime.date(2026, 2, 17),
        number_of_runs=1,
        concurrency_setting=ConcurrencySetting(
            total_backtest_pipelines=1,
            nproc_per_backtest_pipeline=1,
            threads_per_backtest_process=1,
            queue_length_for_threads=1,
        ),
    )

    assert [r.algo_git_reference for r in results] == ["ref1", "ref2"]
    assert calls[0]["algorithm_definition_override"] == {}
    assert calls[1]["algorithm_definition_override"] == {"training_container": "override-image"}


def test_backtest_downloader_rejects_tar_path_traversal(tmp_path):
    from hotvect.backtest import SageMakerBacktestResultsDownloader, _SuccessfulSageMakerResultsComponents

    payload = io.BytesIO()
    with tarfile.open(fileobj=payload, mode="w:gz") as tar:
        data = b"x"
        info = tarfile.TarInfo(name="../escaped.txt")
        info.size = len(data)
        tar.addfile(info, io.BytesIO(data))
    malicious_tar = payload.getvalue()

    class FakeS3:
        def download_fileobj(self, _bucket, _key, fileobj):
            fileobj.write(malicious_tar)

    downloader = SageMakerBacktestResultsDownloader.__new__(SageMakerBacktestResultsDownloader)
    downloader._s3_client = FakeS3()
    downloader._s3_source_bucket = "bucket"
    downloader._s3_source_prefix = "prefix"
    downloader._dest_base_dir = tmp_path / "dest"
    downloader._dest_base_dir.mkdir(parents=True, exist_ok=True)

    result = _SuccessfulSageMakerResultsComponents(
        backtest_test_date="2026-02-15",
        algorithm_name="algo",
        algorithm_version="1.0.0",
        training_job="job",
        hyperparameter=None,
        execution_date=datetime.datetime.now(datetime.UTC),
        key="prefix/job-2026-02-15/algo@1.0.0/result.json",
    )

    with pytest.raises(ValueError, match="outside base directory"):
        downloader._download_and_extract_result_from_s3(result, "output/output.tar.gz")
    assert not (tmp_path / "escaped.txt").exists()


def test_backtest_downloader_raises_download_error(tmp_path):
    from hotvect.backtest import SageMakerBacktestResultsDownloader, _SuccessfulSageMakerResultsComponents

    class FakeS3:
        def download_file(self, _bucket, _key, _destination):
            raise RuntimeError("download failed")

    downloader = SageMakerBacktestResultsDownloader.__new__(SageMakerBacktestResultsDownloader)
    downloader._s3_client = FakeS3()
    downloader._s3_source_bucket = "bucket"
    downloader._s3_source_prefix = "prefix"
    downloader._dest_base_dir = tmp_path / "dest"
    downloader._dest_base_dir.mkdir(parents=True, exist_ok=True)

    result = _SuccessfulSageMakerResultsComponents(
        backtest_test_date="2026-02-15",
        algorithm_name="algo",
        algorithm_version="1.0.0",
        training_job="job",
        hyperparameter=None,
        execution_date=datetime.datetime.now(datetime.UTC),
        key="prefix/job-2026-02-15/algo@1.0.0/result.json",
    )

    with pytest.raises(RuntimeError, match="download failed"):
        downloader._download_result_from_s3(result, "algo@1.0.0/result.json", "meta/algo@1.0.0/result.json")


def test_backtest_downloader_rejects_unsafe_algorithm_components(tmp_path, monkeypatch):
    import hotvect.backtest as backtest_module
    from hotvect.backtest import SageMakerBacktestResultsDownloader

    fake_s3_client = object()

    class FakeSession:
        def client(self, service_name):
            assert service_name == "s3"
            return fake_s3_client

    def mock_boto3_session():
        return FakeSession()

    monkeypatch.setattr(backtest_module.boto3, "Session", mock_boto3_session)

    downloader = SageMakerBacktestResultsDownloader(
        s3_base_prefix="s3://bucket/prefix",
        dest_base_dir=str(tmp_path),
    )

    relevant = {}
    key = {
        "Key": "prefix/job-2026-02-15/../../evil@1.0.0/result.json",
        "LastModified": datetime.datetime.now(datetime.UTC),
    }

    with pytest.raises(ValueError, match="Unsafe 'algorithm_name'"):
        downloader._add_or_replace_key_if_relevant(key, relevant)


@pytest.mark.parametrize(
    "key",
    [
        "prefix/job-2026-06-15/algo@1.0.0-override/metadata/last_test_date_2026-06-15/result.json",
        "prefix/job-2026-06-15/child@algo-1.0.0-override/metadata/deps/child@algo-10.1.0-override/last_test_date_2026-06-15/result.json",
    ],
)
def test_backtest_downloader_ignores_nested_composite_result_json_keys(
    key: str, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    import hotvect.backtest as backtest_module
    from hotvect.backtest import SageMakerBacktestResultsDownloader

    fake_s3_client = object()

    class FakeSession:
        def client(self, service_name):
            assert service_name == "s3"
            return fake_s3_client

    def mock_boto3_session():
        return FakeSession()

    monkeypatch.setattr(backtest_module.boto3, "Session", mock_boto3_session)

    downloader = SageMakerBacktestResultsDownloader(
        s3_base_prefix="s3://bucket/prefix",
        dest_base_dir=str(tmp_path),
    )

    relevant = {}
    nested_key = {
        "Key": key,
        "LastModified": datetime.datetime.now(datetime.UTC),
    }

    downloader._add_or_replace_key_if_relevant(nested_key, relevant)
    assert relevant == {}
