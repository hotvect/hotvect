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
            self.algorithm_name = "example-algorithm"
            self.committed_algorithm_definition = self.algorithm_definition
            self.algorithm_definition_override = None
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

    pipeline_contexts = []

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
            pipeline_contexts.append(algorithm_pipeline_context)
            self.algorithm_definition = {"algorithm_name": "example-algorithm", "algorithm_version": "1.0.0"}
            self.algorithm_name = "example-algorithm"
            self.committed_algorithm_definition = self.algorithm_definition
            self.algorithm_definition_override = None
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
        max_threads_per_process=7,
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
    assert pipeline_contexts[0].max_threads == 7
    assert pipeline_contexts[0].queue_length == 28


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
            self.algorithm_name = "example-algorithm"
            self.committed_algorithm_definition = self.algorithm_definition
            self.algorithm_definition_override = None
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
            self.algorithm_name = "example-algorithm"
            self.committed_algorithm_definition = self.algorithm_definition
            self.algorithm_definition_override = None
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
            self.algorithm_name = "example-algorithm"
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
            self.algorithm_name = "example-algorithm"
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


@pytest.mark.parametrize(
    ("prewarm_encode_cache", "prewarm_instance_type", "error"),
    [
        (True, None, "prewarm_encode_cache requires SageMaker execution"),
        (False, "ml.m7i.4xlarge", "prewarm_instance_type requires prewarm_encode_cache"),
    ],
)
def test_run_all_rejects_local_prewarm_before_preparing_algorithm(
    monkeypatch,
    tmp_path,
    prewarm_encode_cache,
    prewarm_instance_type,
    error,
):
    import hotvect.backtest as backtest_module

    pipeline = backtest_module.BacktestPipeline(
        algo_repo_url="https://example.invalid/repo.git",
        algo_git_reference="deadbeef",
        data_base_dir=str(tmp_path / "data"),
        output_base_dir=str(tmp_path / "out"),
        hyperparameter_base_dir=str(tmp_path / "hp"),
        evaluation_function=lambda _: {},
        last_test_time=datetime.date(2026, 3, 26),
        number_of_runs=1,
        prewarm_encode_cache=prewarm_encode_cache,
        prewarm_instance_type=prewarm_instance_type,
    )
    monkeypatch.setattr(
        pipeline,
        "_prepare_algorithm_jar",
        lambda _git_reference: pytest.fail("local prewarm must fail before preparing the algorithm"),
    )

    with pytest.raises(ValueError, match=error):
        pipeline.run_all(sagemaker_training_job_definition=None)


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
        algorithm_definition_override_metadata={"reasons": ["test override"], "files": ["/tmp/override.json"]},
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
        captured["algorithm_definition_override_metadata"] = _kwargs["algorithm_definition_override_metadata"]
        return backtest_module.BacktestResult(algo_git_reference="deadbeef", backtest_iteration_results=[])

    def fake_execute_on_sagemaker(*, algorithm_definition_override, **_kwargs):
        captured["algorithm_definition_override"] = algorithm_definition_override
        captured["algorithm_definition_override_metadata"] = _kwargs["algorithm_definition_override_metadata"]
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
    assert captured["algorithm_definition_override_metadata"] == {
        "reasons": ["test override"],
        "files": ["/tmp/override.json"],
    }


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
            self.algorithm_name = "example-algorithm"
            self.committed_algorithm_definition = self.algorithm_definition
            self.algorithm_definition_override = None
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


def test_prewarm_submits_top_level_jobs_before_waiting_with_fresh_attempt_names(monkeypatch, tmp_path):
    import hotvect.backtest as backtest_module
    from hotvect.pyhotvect import AlgorithmPipelineContext
    from hotvect.utils import AlgorithmSpec

    captured = []
    events = []
    failed_job_names = set()
    pipeline_constructions = []

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
            run_target="evaluate",
            encode_partition_dates_by_algorithm=None,
        ):
            pipeline_constructions.append(last_test_time)
            if isinstance(algorithm_definition, tuple):
                self.algorithm_name = "example-algorithm"
                self.algorithm_definition = {"algorithm_name": "example-algorithm", "algorithm_version": "1.2.3"}
            else:
                self.algorithm_name = algorithm_definition["algorithm_name"]
                self.algorithm_definition = algorithm_definition
            self.committed_algorithm_definition = self.algorithm_definition
            self.algorithm_definition_override = None
            self.dependency_pipelines = {}
            self.hyper_parameter_version = ""
            self.parameter_version = parameter_version
            self.last_test_time = last_test_time
            self.run_target = run_target
            self.encode_partition_dates_by_algorithm = encode_partition_dates_by_algorithm

        def _training_dates_for_last_test_time(self, last_test_time):
            return [last_test_time - datetime.timedelta(days=1), last_test_time - datetime.timedelta(days=2)]

        def _uses_encode_partition_cache(self):
            return True

        def _uses_prebuilt_parameters(self):
            return False

        def should_train(self):
            return True

        def _effective_cache_base_dir(self):
            return "s3://bucket/cache"

    class DummyExecutor:
        def __init__(self, *, algorithm_pipeline, training_job_definition, role_arn_to_assume):
            self.algorithm_pipeline = algorithm_pipeline
            self.training_job_definition = training_job_definition
            self.training_job_name = training_job_definition["TrainingJobName"]
            captured.append((algorithm_pipeline, training_job_definition))

        def run(self):
            events.append(("run", self.training_job_name))
            return {"TrainingJobArn": f"arn:aws:sagemaker:eu-central-1:123456789012:training-job/{self.training_job_name}"}

        def wait_for_sagemaker_job_completion(self):
            events.append(("wait", self.training_job_name))
            return self.get_job_description()

        def get_job_description(self):
            if self.training_job_name in failed_job_names:
                return {"TrainingJobStatus": "Failed", "FailureReason": "prewarm failed"}
            return {"TrainingJobStatus": "Completed"}

        def _download_hotvect_result(self):
            return {"algorithm_id": "example-algorithm@1.2.3", "encode": {"partition_cache_blocked": []}}

    monkeypatch.setattr(backtest_module, "AlgorithmPipeline", DummyAlgorithmPipeline)
    monkeypatch.setattr(backtest_module.BacktestPipeline, "_attach_input_data_config", lambda *args, **kwargs: None)
    prewarm_job_ids = iter(["attempt001", "attempt002", "attempt003", "attempt004"])
    monkeypatch.setattr(backtest_module, "generate_runid", lambda: next(prewarm_job_ids))

    pipeline = backtest_module.BacktestPipeline(
        algo_repo_url="https://example.invalid/repo.git",
        algo_git_reference="deadbeef",
        data_base_dir=str(tmp_path / "data"),
        output_base_dir=str(tmp_path / "out"),
        hyperparameter_base_dir=str(tmp_path / "hp"),
        evaluation_function=lambda path: {},
        last_test_time=datetime.date(2000, 3, 27),
        number_of_runs=3,
        prewarm_encode_cache=True,
        prewarm_instance_type="ml.m7i.4xlarge",
    )

    def prewarm(training_job_name="bt-demo"):
        pipeline._prewarm_encode_partition_cache_on_sagemaker(
            algorithm_spec=AlgorithmSpec("example-algorithm", tmp_path / "algo.jar", "abcdef1234567890"),
            algorithm_definition_arg=("example-algorithm", None),
            context=AlgorithmPipelineContext(
                algorithm_jar_path=tmp_path / "algo.jar",
                data_base_path=tmp_path / "data",
                metadata_base_path=tmp_path / "meta",
                output_base_path=tmp_path / "out",
            ),
            last_test_days=[datetime.date(2000, 3, 27), datetime.date(2000, 3, 26), datetime.date(2000, 3, 25)],
            sagemaker_training_job_definition={
                "TrainingJobName": training_job_name,
                "OutputDataConfig": {"S3OutputPath": "s3://bucket/out"},
                "ResourceConfig": {"InstanceType": "ml.m5.4xlarge", "InstanceCount": 3},
                "HotvectSubmissionOptions": {
                    "PreferredInstanceTypes": ["ml.m5.4xlarge", "ml.m7i.4xlarge", "ml.r7i.4xlarge"]
                },
            },
            sagemaker_cli_job_overrides=None,
            role_arn_to_assume=None,
            sagemaker_executor_cls=DummyExecutor,
        )

    prewarm()

    assert pipeline_constructions == [
        datetime.date(2000, 3, 27),
        datetime.date(2000, 3, 27),
        datetime.date(2000, 3, 25),
    ]
    submitted_algorithm_groups = [item[0].encode_partition_dates_by_algorithm for item in captured]
    assert submitted_algorithm_groups == [
        {"example-algorithm": [datetime.date(2000, 3, 26), datetime.date(2000, 3, 25)]},
        {"example-algorithm": [datetime.date(2000, 3, 24), datetime.date(2000, 3, 23)]},
    ]
    assert [event for event, _ in events] == ["run", "run", "wait", "wait"]
    assert [job_name for _, job_name in events[:2]] == [job_name for _, job_name in events[2:]]
    assert all(item[0].run_target == "encode-cache" for item in captured)
    assert all(item[0].algorithm_name == "example-algorithm" for item in captured)
    assert all(item[1]["ResourceConfig"]["InstanceType"] == "ml.m7i.4xlarge" for item in captured)
    assert all(item[1]["ResourceConfig"]["InstanceCount"] == 1 for item in captured)
    assert all(item[1]["HotvectSubmissionOptions"]["PreferredInstanceTypes"] == ["ml.m7i.4xlarge"] for item in captured)
    assert [item[0].parameter_version for item in captured] == [
        "last_test_date_2000-03-27",
        "last_test_date_2000-03-25",
    ]
    assert len({item[1]["TrainingJobName"] for item in captured}) == 2

    from hotvect.sagemaker import SagemakerTrainingExecutor

    for _, job_definition in captured:
        executor = SagemakerTrainingExecutor.__new__(SagemakerTrainingExecutor)
        executor.training_job_definition = copy.deepcopy(job_definition)
        assert executor._normalize_instance_type_preferences() == []

    original_job_names = {job_definition["TrainingJobName"] for _, job_definition in captured}
    failed_job_names.add(next(iter(original_job_names)))
    events.clear()
    prewarm()
    retry_job_names = {job_definition["TrainingJobName"] for _, job_definition in captured[2:]}
    assert [event for event, _ in events] == ["run", "run", "wait", "wait"]
    assert original_job_names.isdisjoint(retry_job_names)


def test_prewarm_reports_blocked_partition_publication():
    import hotvect.backtest as backtest_module

    result = {
        "algorithm_id": "parent@1.0.0",
        "encode": {
            "partition_cache_blocked": 1,
            "partitions": [
                {
                    "dt": "2000-03-25",
                    "cache": "blocked",
                    "cache_path": "s3://bucket/cache/parent/dt=2000-03-25",
                }
            ],
        },
        "dependencies": {
            "child": {
                "algorithm_id": "child@2.0.0",
                "encode": {
                    "partition_cache_blocked": 1,
                    "partitions": [{"dt": "2000-03-24", "cache": "blocked"}],
                },
            }
        },
    }

    assert backtest_module.BacktestPipeline._blocked_prewarm_partitions(result) == [
        "parent@1.0.0: s3://bucket/cache/parent/dt=2000-03-25",
        "child@2.0.0: dt=2000-03-24",
    ]


def test_prewarm_encode_partition_cache_plans_dates_from_dependency_pipeline(tmp_path):
    import hotvect.backtest as backtest_module

    def make_pipeline(name, *, uses_partition_cache, day_offsets, dependencies=()):
        return types.SimpleNamespace(
            algorithm_name=name,
            dependency_pipelines={dependency.algorithm_name: dependency for dependency in dependencies},
            _uses_prebuilt_parameters=lambda: False,
            _uses_encode_partition_cache=lambda: uses_partition_cache,
            _training_dates_for_last_test_time=lambda last_test_day: [
                last_test_day - datetime.timedelta(days=day_offset) for day_offset in day_offsets
            ],
        )

    child_pipeline = make_pipeline("child-algorithm", uses_partition_cache=True, day_offsets=(3, 4))
    parent_pipeline = make_pipeline(
        "parent-algorithm",
        uses_partition_cache=False,
        day_offsets=(1, 2),
        dependencies=(child_pipeline,),
    )
    pipeline = backtest_module.BacktestPipeline.__new__(backtest_module.BacktestPipeline)

    jobs = pipeline._build_encode_cache_prewarm_jobs(
        algorithm_pipeline=parent_pipeline,
        last_test_days=[datetime.date(2026, 3, 27), datetime.date(2026, 3, 26)],
    )

    assert [job.representative_last_test_day for job in jobs] == [
        datetime.date(2026, 3, 27),
        datetime.date(2026, 3, 26),
    ]
    assert [job.encode_partition_dates_by_algorithm for job in jobs] == [
        {"child-algorithm": (datetime.date(2026, 3, 24), datetime.date(2026, 3, 23))},
        {"child-algorithm": (datetime.date(2026, 3, 22),)},
    ]


def test_prewarm_does_not_descend_into_pinned_pipeline():
    import hotvect.backtest as backtest_module

    def fail_if_planned():
        raise AssertionError("pinned pipeline must not be planned")

    pinned_pipeline = types.SimpleNamespace(
        algorithm_name="pinned-algorithm",
        dependency_pipelines={
            "unused-child": types.SimpleNamespace(
                algorithm_name="unused-child",
                dependency_pipelines={},
                _uses_prebuilt_parameters=lambda: False,
                _uses_encode_partition_cache=lambda: True,
                _training_dates_for_last_test_time=lambda last_test_day: [last_test_day],
            )
        },
        _uses_prebuilt_parameters=lambda: True,
        _uses_encode_partition_cache=fail_if_planned,
    )
    pipeline = backtest_module.BacktestPipeline.__new__(backtest_module.BacktestPipeline)

    jobs = pipeline._build_encode_cache_prewarm_jobs(
        algorithm_pipeline=pinned_pipeline,
        last_test_days=[datetime.date(2026, 3, 27)],
    )

    assert jobs == []


def test_prewarm_rejects_trainable_descendant_required_by_cached_pipeline():
    import hotvect.backtest as backtest_module

    def make_pipeline(name, *, uses_partition_cache=False, should_train=False, dependencies=(), prebuilt=False):
        return types.SimpleNamespace(
            algorithm_name=name,
            dependency_pipelines={dependency.algorithm_name: dependency for dependency in dependencies},
            _uses_prebuilt_parameters=lambda: prebuilt,
            _uses_encode_partition_cache=lambda: uses_partition_cache,
            should_train=lambda: should_train,
        )

    trainable_leaf = make_pipeline("trainable-leaf", should_train=True)
    middle = make_pipeline("middle", dependencies=(trainable_leaf,))
    cached_parent = make_pipeline(
        "cached-parent",
        uses_partition_cache=True,
        should_train=True,
        dependencies=(middle,),
    )

    with pytest.raises(
        ValueError,
        match="cannot encode cached-parent: its trainable dependency trainable-leaf requires a trained model",
    ):
        backtest_module.BacktestPipeline._validate_encode_cache_prewarm_graph(cached_parent)

    trainable_leaf._uses_prebuilt_parameters = lambda: True
    backtest_module.BacktestPipeline._validate_encode_cache_prewarm_graph(cached_parent)


def test_prewarm_allows_trainable_cached_pipeline_below_uncached_parent():
    import hotvect.backtest as backtest_module

    cached_child = types.SimpleNamespace(
        algorithm_name="cached-child",
        dependency_pipelines={},
        _uses_prebuilt_parameters=lambda: False,
        _uses_encode_partition_cache=lambda: True,
        should_train=lambda: True,
    )
    uncached_parent = types.SimpleNamespace(
        algorithm_name="uncached-parent",
        dependency_pipelines={"cached-child": cached_child},
        _uses_prebuilt_parameters=lambda: False,
        _uses_encode_partition_cache=lambda: False,
        should_train=lambda: False,
    )

    backtest_module.BacktestPipeline._validate_encode_cache_prewarm_graph(uncached_parent)


def test_prewarm_encode_partition_cache_creates_required_parameter_contexts(tmp_path):
    import hotvect.backtest as backtest_module

    algorithm_pipeline = types.SimpleNamespace(
        algorithm_name="child-algorithm",
        dependency_pipelines={},
        _uses_prebuilt_parameters=lambda: False,
        _uses_encode_partition_cache=lambda: True,
        _training_dates_for_last_test_time=lambda last_test_day: [
            last_test_day - datetime.timedelta(days=day_offset) for day_offset in (1, 2)
        ],
    )
    pipeline = backtest_module.BacktestPipeline.__new__(backtest_module.BacktestPipeline)

    jobs = pipeline._build_encode_cache_prewarm_jobs(
        algorithm_pipeline=algorithm_pipeline,
        last_test_days=[datetime.date(2026, 3, 27), datetime.date(2026, 3, 26)],
    )

    assert [job.representative_last_test_day for job in jobs] == [
        datetime.date(2026, 3, 27),
        datetime.date(2026, 3, 26),
    ]
    assert [job.encode_partition_dates_by_algorithm for job in jobs] == [
        {
            "child-algorithm": (
                datetime.date(2026, 3, 26),
                datetime.date(2026, 3, 25),
            )
        },
        {"child-algorithm": (datetime.date(2026, 3, 24),)},
    ]


def test_prewarm_encode_partition_cache_assigns_dates_per_pipeline_for_mixed_windows(tmp_path):
    import hotvect.backtest as backtest_module

    def make_pipeline(name, *, day_offsets, dependencies=()):
        return types.SimpleNamespace(
            algorithm_name=name,
            dependency_pipelines={dependency.algorithm_name: dependency for dependency in dependencies},
            _uses_prebuilt_parameters=lambda: False,
            _uses_encode_partition_cache=lambda: True,
            _training_dates_for_last_test_time=lambda last_test_day: [
                last_test_day - datetime.timedelta(days=day_offset) for day_offset in day_offsets
            ],
        )

    child_pipeline = make_pipeline("child-algorithm", day_offsets=(1, 2, 3, 4))
    parent_pipeline = make_pipeline("parent-algorithm", day_offsets=(1, 2), dependencies=(child_pipeline,))
    pipeline = backtest_module.BacktestPipeline.__new__(backtest_module.BacktestPipeline)

    jobs = pipeline._build_encode_cache_prewarm_jobs(
        algorithm_pipeline=parent_pipeline,
        last_test_days=[datetime.date(2026, 3, 27), datetime.date(2026, 3, 26)],
    )

    assert [job.representative_last_test_day for job in jobs] == [
        datetime.date(2026, 3, 27),
        datetime.date(2026, 3, 26),
    ]
    assert [job.encode_partition_dates_by_algorithm for job in jobs] == [
        {
            "child-algorithm": (
                datetime.date(2026, 3, 26),
                datetime.date(2026, 3, 25),
                datetime.date(2026, 3, 23),
            ),
            "parent-algorithm": (datetime.date(2026, 3, 26),),
        },
        {
            "child-algorithm": (
                datetime.date(2026, 3, 24),
                datetime.date(2026, 3, 22),
            ),
            "parent-algorithm": (
                datetime.date(2026, 3, 25),
                datetime.date(2026, 3, 24),
            ),
        },
    ]


def test_prewarm_encode_partition_cache_uses_minimum_compatible_contexts(tmp_path):
    import hotvect.backtest as backtest_module

    algorithm_pipeline = types.SimpleNamespace(
        algorithm_name="child-algorithm",
        dependency_pipelines={},
        _uses_prebuilt_parameters=lambda: False,
        _uses_encode_partition_cache=lambda: True,
        _training_dates_for_last_test_time=lambda last_test_day: [
            last_test_day - datetime.timedelta(days=day_offset) for day_offset in (1, 2, 3)
        ],
    )
    pipeline = backtest_module.BacktestPipeline.__new__(backtest_module.BacktestPipeline)

    jobs = pipeline._build_encode_cache_prewarm_jobs(
        algorithm_pipeline=algorithm_pipeline,
        last_test_days=[
            datetime.date(2026, 3, 27),
            datetime.date(2026, 3, 26),
            datetime.date(2026, 3, 25),
        ],
    )

    assert [job.encode_partition_dates_by_algorithm for job in jobs] == [
        {
            "child-algorithm": (
                datetime.date(2026, 3, 26),
                datetime.date(2026, 3, 25),
                datetime.date(2026, 3, 24),
            )
        },
        {"child-algorithm": (datetime.date(2026, 3, 23), datetime.date(2026, 3, 22))},
    ]
    assert [job.representative_last_test_day for job in jobs] == [
        datetime.date(2026, 3, 27),
        datetime.date(2026, 3, 25),
    ]


def test_prewarm_encode_partition_cache_balancing_does_not_add_parameter_contexts(tmp_path):
    import hotvect.backtest as backtest_module

    shared_dates = [datetime.date(2026, 3, 26), datetime.date(2026, 3, 25)]
    algorithm_pipeline = types.SimpleNamespace(
        algorithm_name="child-algorithm",
        dependency_pipelines={},
        _uses_prebuilt_parameters=lambda: False,
        _uses_encode_partition_cache=lambda: True,
        _training_dates_for_last_test_time=lambda _last_test_day: shared_dates,
    )
    pipeline = backtest_module.BacktestPipeline.__new__(backtest_module.BacktestPipeline)

    jobs = pipeline._build_encode_cache_prewarm_jobs(
        algorithm_pipeline=algorithm_pipeline,
        last_test_days=[
            datetime.date(2026, 3, 27),
            datetime.date(2026, 3, 26),
            datetime.date(2026, 3, 25),
        ],
    )

    assert len(jobs) == 1
    assert jobs[0].representative_last_test_day == datetime.date(2026, 3, 27)
    assert jobs[0].encode_partition_dates_by_algorithm == {"child-algorithm": tuple(shared_dates)}


def test_prewarm_encode_partition_cache_expands_compatible_contexts():
    import hotvect.backtest as backtest_module

    last_test_days = [
        datetime.date(2026, 3, 27),
        datetime.date(2026, 3, 26),
        datetime.date(2026, 3, 25),
    ]
    shared_partition_dates = (
        datetime.date(2026, 3, 24),
        datetime.date(2026, 3, 23),
        datetime.date(2026, 3, 22),
    )
    valid_partition_dates_by_context = {last_test_day: shared_partition_dates for last_test_day in last_test_days}
    algorithm_pipeline = types.SimpleNamespace(
        algorithm_name="child-algorithm",
        dependency_pipelines={},
        _uses_prebuilt_parameters=lambda: False,
        _uses_encode_partition_cache=lambda: True,
        _training_dates_for_last_test_time=lambda last_test_day: valid_partition_dates_by_context[last_test_day],
    )
    pipeline = backtest_module.BacktestPipeline.__new__(backtest_module.BacktestPipeline)

    automatic_jobs = pipeline._build_encode_cache_prewarm_jobs(
        algorithm_pipeline=algorithm_pipeline,
        last_test_days=last_test_days,
    )
    expanded_jobs = pipeline._build_encode_cache_prewarm_jobs(
        algorithm_pipeline=algorithm_pipeline,
        last_test_days=last_test_days,
        maximum_contexts=3,
    )

    assert len(automatic_jobs) == 1
    assert [job.representative_last_test_day for job in expanded_jobs] == last_test_days
    assigned_partition_dates = [
        partition_date
        for job in expanded_jobs
        for partition_dates in job.encode_partition_dates_by_algorithm.values()
        for partition_date in partition_dates
    ]
    assert set(assigned_partition_dates) == set(shared_partition_dates)
    assert len(assigned_partition_dates) == len(set(assigned_partition_dates))
    assert all(
        partition_date in valid_partition_dates_by_context[job.representative_last_test_day]
        for job in expanded_jobs
        for partition_dates in job.encode_partition_dates_by_algorithm.values()
        for partition_date in partition_dates
    )


def test_prewarm_encode_partition_cache_rejects_too_low_maximum_contexts():
    import hotvect.backtest as backtest_module

    algorithm_pipeline = types.SimpleNamespace(
        algorithm_name="child-algorithm",
        dependency_pipelines={},
        _uses_prebuilt_parameters=lambda: False,
        _uses_encode_partition_cache=lambda: True,
        _training_dates_for_last_test_time=lambda last_test_day: [
            last_test_day - datetime.timedelta(days=day_offset) for day_offset in (1, 2)
        ],
    )
    pipeline = backtest_module.BacktestPipeline.__new__(backtest_module.BacktestPipeline)

    with pytest.raises(ValueError, match="--prewarm-instance-count"):
        pipeline._build_encode_cache_prewarm_jobs(
            algorithm_pipeline=algorithm_pipeline,
            last_test_days=[datetime.date(2026, 3, 27), datetime.date(2026, 3, 26)],
            maximum_contexts=1,
        )


def test_prewarm_job_name_is_unique_per_attempt(monkeypatch, tmp_path):
    import hotvect.backtest as backtest_module
    from hotvect.utils import AlgorithmSpec

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
    algorithm_pipeline = types.SimpleNamespace(hyper_parameter_version="")
    run_ids = iter(["attempt001", "attempt002"])
    monkeypatch.setattr(backtest_module, "generate_runid", lambda: next(run_ids))

    training_job_name_a = pipeline._prewarm_job_name(
        base_prefix="bt-demo",
        algorithm_spec=AlgorithmSpec("example-algorithm", tmp_path / "algo.jar", "abcdef1234567890"),
        algorithm_pipeline=algorithm_pipeline,
        last_test_day=datetime.date(2026, 3, 26),
    )
    training_job_name_b = pipeline._prewarm_job_name(
        base_prefix="bt-demo",
        algorithm_spec=AlgorithmSpec("example-algorithm", tmp_path / "algo.jar", "abcdef1234567890"),
        algorithm_pipeline=algorithm_pipeline,
        last_test_day=datetime.date(2026, 3, 26),
    )

    assert training_job_name_a != training_job_name_b
    assert training_job_name_a.endswith("-attempt001")
    assert training_job_name_b.endswith("-attempt002")


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


def test_run_backtest_on_git_reference_prewarm_forces_partition_only_encode_cache(monkeypatch):
    import hotvect.backtest as backtest_module

    captured = {}

    class DummyBacktestPipeline:
        def __init__(self, **kwargs):
            captured["algorithm_definition_override"] = kwargs["algorithm_definition_override"]
            captured["prewarm_instance_count"] = kwargs["prewarm_instance_count"]

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
        algorithm_definition_override={
            "hotvect_execution_parameters": {
                "cache": "run",
                "encode": {"cache": "run"},
            }
        },
        cache_base_dir="s3://bucket/prefix/hotvect-cache",
        prewarm_encode_cache=True,
        prewarm_instance_count=4,
        sagemaker_training_job_definition={"TrainingJobName": "bt-demo"},
    )

    assert captured["algorithm_definition_override"]["hotvect_execution_parameters"] == {
        "cache": "run",
        "cache_base_dir": "s3://bucket/prefix/hotvect-cache",
        "cache_scope": "hyperparam",
        "encode": {"cache": "partition"},
    }
    assert captured["prewarm_instance_count"] == 4


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
        assert call["prewarm_encode_cache"] is False
        assert call["prewarm_instance_count"] is None
        assert call["prewarm_instance_type"] is None


def test_run_backtest_on_git_references_passes_prewarm_to_each_git_ref(monkeypatch):
    import hotvect.backtest as backtest_module
    from hotvect.utils import ConcurrencySetting

    calls = []

    def fake_run_backtest_on_git_reference(**kwargs):
        calls.append(kwargs)
        return backtest_module.BacktestResult(algo_git_reference=kwargs["algo_git_reference"])

    monkeypatch.setattr(backtest_module, "run_backtest_on_git_reference", fake_run_backtest_on_git_reference)

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
        sagemaker_training_job_definition={"TrainingJobName": "bt-demo"},
        cache_base_dir="s3://bucket/prefix/hotvect-cache",
        prewarm_encode_cache=True,
        prewarm_instance_count=4,
    )

    assert [result.algo_git_reference for result in results] == ["ref1", "ref2"]
    assert len(calls) == 2
    assert all(call["prewarm_encode_cache"] is True for call in calls)
    assert all(call["prewarm_instance_count"] == 4 for call in calls)


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


def test_backtest_downloader_applies_regexes_to_parsed_fields(tmp_path, monkeypatch):
    import hotvect.backtest as backtest_module
    from hotvect.backtest import SageMakerBacktestResultsDownloader

    fake_s3_client = object()

    class FakeSession:
        def client(self, service_name):
            assert service_name == "s3"
            return fake_s3_client

    monkeypatch.setattr(backtest_module.boto3, "Session", FakeSession)

    downloader = SageMakerBacktestResultsDownloader(
        s3_base_prefix="s3://bucket/prefix.with.regex-characters",
        dest_base_dir=str(tmp_path),
        training_job_id_pattern=r"^exp-example-.*-test$",
        algorithm_name_pattern=r"^example-ranking-algorithm$",
        algorithm_version_pattern=r"^1\.0\.1$",
    )

    relevant = {}
    downloader._add_or_replace_key_if_relevant(
        {
            "Key": (
                "prefix.with.regex-characters/exp-example-abc123-def456-test-2000-04-14/"
                "example-ranking-algorithm@1.0.1-example-run/result.json"
            ),
            "LastModified": datetime.datetime.now(datetime.UTC),
        },
        relevant,
    )

    assert list(relevant) == [("2000-04-14", "example-ranking-algorithm", "1.0.1", "example-run")]


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
