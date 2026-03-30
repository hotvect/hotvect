import datetime
import io
import json
import os.path
import tarfile
from pathlib import Path

import pytest

from hotvect.backtest import list_output_dirs
from hotvect.mlutils import extract_evaluation

example_result_base_dir = Path(os.path.dirname(os.path.realpath(__file__))) / "testfiles" / "meta"


def test_read_results():
    expected = {
        "algorithm_id": "example-algorithm@1.1.1",
        "algorithm.pr_auc": 0.005562758750636963,
        "algorithm.roc_auc": 0.7954481367249178,
        "diversity@10": 0.5389605324569872,
        "diversity@30": 0.31078265891765283,
        "diversity@5": 0.7129776800271087,
        "impression.map_at_10": 0.005073945326479579,
        "impression.map_at_50": 0.005326563142979179,
        "impression.map_at_all": 0.0053234890023868536,
        "impression.ndcg_at_10": 0.006412982533851097,
        "impression.ndcg_at_50": 0.0085194559010677,
        "impression.ndcg_at_all": 0.008574699913360101,
        "map_at_10": 0.005358904300327959,
        "map_at_50": 0.00556907716885868,
        "map_at_all": 0.00556959610794158,
        "max_memory_usage": 12341234,
        "mean_throughput": 1234,
        "ndcg_at_10": 0.006818835796431752,
        "ndcg_at_50": 0.008758071563963004,
        "ndcg_at_all": 0.008805847221075294,
        "p50": 626158.0,
        "p75": 1401592.6,
        "p95": 4464158.4,
        "p99": 11725205.4,
        "p999": 35302789.4,
        "pr_auc": 0.0060868409998212965,
        "roc_auc": 0.8196221521258884,
        "test_date": datetime.datetime(2022, 9, 3, 1, 2, 3, 123456, tzinfo=datetime.timezone.utc),
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

    # Apply algorithm declared training container override
    algorithm_definition = {"training_container": "registry.example/hotvect:10.11.0"}
    apply_training_container(job_definition, algorithm_definition)
    # If the job definition already set an image explicitly, keep it.
    assert job_definition["AlgorithmSpecification"]["TrainingImage"] == "registry.example/hotvect:9.29.0"


def test_apply_training_container_handles_missing_spec():
    from hotvect.backtest import apply_training_container

    job_definition = {}
    algorithm_definition = {"training_container": "registry.example/hotvect:10.11.0"}

    apply_training_container(job_definition, algorithm_definition)
    assert job_definition["AlgorithmSpecification"]["TrainingImage"] == "registry.example/hotvect:10.11.0"


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


def test_run_backtest_on_git_reference_cache_refresh_requires_cache_base_dir():
    from hotvect.backtest import run_backtest_on_git_reference

    with pytest.raises(ValueError, match="cache_refresh requires cache_base_dir"):
        run_backtest_on_git_reference(
            algo_repo_url="https://example.invalid/repo.git",
            algo_git_reference="deadbeef",
            data_base_dir="/tmp/data",
            output_base_dir="/tmp/out",
            hyperparameter_base_dir="/tmp/hp",
            evaluation_function=lambda path: {},
            last_test_time=datetime.date(2026, 2, 17),
            number_of_runs=1,
            cache_refresh=True,
            cache_base_dir=None,
        )


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
        execution_date=datetime.datetime.now(datetime.timezone.utc),
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
        execution_date=datetime.datetime.now(datetime.timezone.utc),
        key="prefix/job-2026-02-15/algo@1.0.0/result.json",
    )

    with pytest.raises(RuntimeError, match="download failed"):
        downloader._download_result_from_s3(result, "algo@1.0.0/result.json", "meta/algo@1.0.0/result.json")


def test_backtest_downloader_rejects_unsafe_algorithm_components():
    from hotvect.backtest import SageMakerBacktestResultsDownloader

    downloader = SageMakerBacktestResultsDownloader.__new__(SageMakerBacktestResultsDownloader)
    downloader._match_regex = r"prefix/(.+?)-(\d\d\d\d-\d\d-\d\d)/(.+?)@(.+?)(-(.+))?/result.json"
    downloader._from_including_test_date = None
    downloader._to_including_test_date = None

    relevant = {}
    key = {
        "Key": "prefix/job-2026-02-15/../../evil@1.0.0/result.json",
        "LastModified": datetime.datetime.now(datetime.timezone.utc),
    }

    with pytest.raises(ValueError, match="Unsafe 'algorithm_name'"):
        downloader._add_or_replace_key_if_relevant(key, relevant)
