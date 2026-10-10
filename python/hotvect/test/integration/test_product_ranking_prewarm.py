from __future__ import annotations

import json
import os
import sys
import zipfile
from datetime import date
from pathlib import Path


def _repository_root() -> Path:
    return Path(__file__).resolve().parents[4]


def _product_ranker_shaded_jar() -> Path:
    shaded_jars = sorted(
        (_repository_root() / "examples/product-search-and-ranking/product-ranker/target").glob(
            "hotvect-example-product-ranker-*-shaded.jar"
        )
    )
    assert len(shaded_jars) == 1, (
        f"Exactly one product ranker shaded JAR is required for this integration test; found {shaded_jars}. "
        "Build the Maven reactor before running Python tests."
    )
    return shaded_jars[0]


def _scorer_pipeline(algorithm_pipeline):
    return algorithm_pipeline.dependency_pipelines["example-product-ranker"].dependency_pipelines[
        "example-product-scorer"
    ]


def _scorer_result(result):
    return result["dependencies"]["example-product-ranker"]["dependencies"]["example-product-scorer"]


def test_product_ranking_graph_prewarm_uses_scorer_partition_cache(tmp_path) -> None:
    from hotvect.backtest import BacktestPipeline
    from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext
    from hotvect.utils import AlgorithmSpec

    product_ranker_jar = _product_ranker_shaded_jar()
    last_test_days = [date(2000, 1, 5), date(2000, 1, 4), date(2000, 1, 3), date(2000, 1, 2)]
    algorithm_definition_override = {
        "hotvect_execution_parameters": {
            "cache_base_dir": "s3://example-bucket/product-cache",
            "encode": {"cache": "partition"},
        }
    }
    context = AlgorithmPipelineContext(
        algorithm_jar_path=product_ranker_jar,
        data_base_path=tmp_path / "data",
        metadata_base_path=tmp_path / "metadata",
        output_base_path=tmp_path / "output",
    )

    planning_pipeline = AlgorithmPipeline(
        algorithm_pipeline_context=context,
        algorithm_definition=("example-product-search-topk", algorithm_definition_override),
        last_test_time=last_test_days[0],
        evaluation_func=lambda _: {},
        parameter_version="last_test_date_2000-01-05",
        execute_performance_test=False,
        encode_test_data=False,
        execute_audit=False,
        run_target="encode-cache",
    )

    assert set(planning_pipeline.dependency_pipelines) == {
        "example-product-search-index",
        "example-product-ranker",
    }
    scorer = _scorer_pipeline(planning_pipeline)
    assert scorer._uses_encode_partition_cache()
    assert scorer.algorithm_definition["hotvect_execution_parameters"] == {
        "cache_base_dir": "s3://example-bucket/product-cache",
        "encode": {"cache": "partition"},
    }

    pipeline = BacktestPipeline(
        algo_repo_url="https://example.invalid/product-ranking.git",
        algo_git_reference="a1b2c3d4e5f6",
        data_base_dir=str(tmp_path / "data"),
        output_base_dir=str(tmp_path / "output"),
        hyperparameter_base_dir=str(tmp_path / "hyperparameters"),
        evaluation_function=lambda _: {},
        last_test_time=last_test_days[0],
        number_of_runs=len(last_test_days),
        auto_attach_data_default_s3_base="s3://example-bucket/example-data",
        prewarm_encode_cache=True,
        prewarm_instance_count=3,
        prewarm_instance_type="ml.c7i.2xlarge",
    )

    jobs = pipeline._build_encode_cache_prewarm_jobs(
        algorithm_pipeline=planning_pipeline,
        last_test_days=last_test_days,
        maximum_contexts=pipeline.prewarm_instance_count,
    )
    assert [(job.representative_last_test_day, job.encode_partition_dates_by_algorithm) for job in jobs] == [
        (
            date(2000, 1, 5),
            {"example-product-scorer": (date(2000, 1, 4), date(2000, 1, 3))},
        ),
        (
            date(2000, 1, 4),
            {"example-product-scorer": (date(2000, 1, 2),)},
        ),
        (
            date(2000, 1, 2),
            {"example-product-scorer": (date(2000, 1, 1), date(1999, 12, 31))},
        ),
    ]

    events = []
    executors = []

    class RecordingExecutor:
        def __init__(self, *, algorithm_pipeline, training_job_definition, role_arn_to_assume) -> None:
            self.algorithm_pipeline = algorithm_pipeline
            self.training_job_definition = training_job_definition
            self.training_job_name = training_job_definition["TrainingJobName"]
            executors.append(self)

        def run(self):
            events.append(("submit", self.training_job_name))
            return {"TrainingJobArn": f"arn:aws:sagemaker:eu-central-1:123:training-job/{self.training_job_name}"}

        def wait_for_sagemaker_job_completion(self):
            events.append(("wait", self.training_job_name))
            return {"TrainingJobStatus": "Completed"}

        def _download_hotvect_result(self):
            return {}

    pipeline._prewarm_encode_partition_cache_on_sagemaker(
        algorithm_spec=AlgorithmSpec(
            "example-product-search-topk",
            product_ranker_jar,
            "a1b2c3d4e5f6",
        ),
        algorithm_definition_arg=("example-product-search-topk", algorithm_definition_override),
        context=context,
        last_test_days=last_test_days,
        sagemaker_training_job_definition={
            "TrainingJobName": "product-cache",
            "OutputDataConfig": {"S3OutputPath": "s3://example-bucket/backtest-output"},
            "ResourceConfig": {"InstanceType": "ml.m5.4xlarge", "InstanceCount": 8},
        },
        sagemaker_cli_job_overrides=None,
        role_arn_to_assume=None,
        sagemaker_executor_cls=RecordingExecutor,
    )

    assert len(executors) == len(jobs)
    assert all(executor.training_job_name.startswith("product-cache-prewarm-a1b2c3-") for executor in executors)
    assert events == [("submit", executor.training_job_name) for executor in executors] + [
        ("wait", executor.training_job_name) for executor in executors
    ]
    assert [_scorer_pipeline(executor.algorithm_pipeline).encode_partition_dates for executor in executors] == [
        list(job.encode_partition_dates_by_algorithm["example-product-scorer"]) for job in jobs
    ]
    assert all(executor.algorithm_pipeline.encode_partition_dates == [] for executor in executors)
    assert all(executor.algorithm_pipeline.run_target == "encode-cache" for executor in executors)
    assert all(
        executor.training_job_definition["ResourceConfig"]
        == {
            "InstanceType": "ml.c7i.2xlarge",
            "InstanceCount": 1,
        }
        for executor in executors
    )
    assert all(
        [channel["ChannelName"] for channel in executor.training_job_definition["InputDataConfig"]]
        == ["example_product_catalog", "example_product_examples"]
        for executor in executors
    )


def test_product_ranking_prewarm_then_lifecycle_uses_real_cached_catboost_artifacts(tmp_path, monkeypatch) -> None:
    from hotvect.evaluation.evaluation import standard_evaluation
    from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext

    repository_root = _repository_root()
    python_bin_dir = Path(sys.executable).parent
    catboost_train = python_bin_dir / "catboost_train"
    if not catboost_train.is_file():
        raise FileNotFoundError(f"CatBoost training entrypoint is required for this integration test: {catboost_train}")
    monkeypatch.setenv("PATH", os.pathsep.join([str(python_bin_dir), os.environ["PATH"]]))

    product_data = repository_root / "examples/product-search-and-ranking/example-data"
    cache_base_dir = tmp_path / "partition-cache"
    algorithm_definition_override = {
        "hotvect_execution_parameters": {
            "cache_base_dir": str(cache_base_dir),
            "encode": {"cache": "partition"},
        }
    }
    context = AlgorithmPipelineContext(
        algorithm_jar_path=_product_ranker_shaded_jar(),
        data_base_path=product_data,
        metadata_base_path=tmp_path / "metadata",
        output_base_path=tmp_path / "output",
    )
    last_test_day = date(2000, 1, 3)
    partition_dates = [date(2000, 1, 1), date(2000, 1, 2)]

    prewarm_pipeline = AlgorithmPipeline(
        algorithm_pipeline_context=context,
        algorithm_definition=("example-product-search-topk", algorithm_definition_override),
        last_test_time=last_test_day,
        evaluation_func=standard_evaluation,
        parameter_version="product-prewarm",
        execute_performance_test=False,
        encode_test_data=False,
        execute_audit=False,
        run_target="encode-cache",
        encode_partition_dates_by_algorithm={"example-product-scorer": partition_dates},
    )
    prewarm_result = prewarm_pipeline.run_all(clean=False, target="encode-cache")
    prewarm_scorer = _scorer_result(prewarm_result)

    assert prewarm_scorer["run_target"] == "encode-cache"
    assert prewarm_scorer["encode"]["partition_cache_misses"] == 2
    assert prewarm_scorer["encode"]["partition_cache_hits"] == 0
    assert prewarm_scorer["encode"]["partition_cache_blocked"] == 0
    assert [(partition["dt"], partition["cache"]) for partition in prewarm_scorer["encode"]["partitions"]] == [
        ("2000-01-01", "miss"),
        ("2000-01-02", "miss"),
    ]

    prewarm_scorer_pipeline = _scorer_pipeline(prewarm_pipeline)
    for partition_day in partition_dates:
        partition_cache = Path(prewarm_scorer_pipeline._resolve_encode_partition_cache_path(partition_day))
        assert (partition_cache / "_SUCCESS").is_file()
        assert (partition_cache / "encoded-schema-description").is_file()
        assert list((partition_cache / "encoded").glob("*.tsv"))

    lifecycle_pipeline = AlgorithmPipeline(
        algorithm_pipeline_context=context,
        algorithm_definition=("example-product-search-topk", algorithm_definition_override),
        last_test_time=last_test_day,
        evaluation_func=standard_evaluation,
        parameter_version="product-lifecycle",
        execute_performance_test=False,
        encode_test_data=False,
        execute_audit=False,
    )
    lifecycle_result = lifecycle_pipeline.run_all(clean=False, target="evaluate")
    lifecycle_scorer = _scorer_result(lifecycle_result)
    lifecycle_scorer_pipeline = _scorer_pipeline(lifecycle_pipeline)

    assert lifecycle_scorer["encode"]["partition_cache_hits"] == 2
    assert lifecycle_scorer["encode"]["partition_cache_misses"] == 0
    assert lifecycle_scorer["encode"]["partition_cache_blocked"] == 0
    assert [(partition["dt"], partition["cache"]) for partition in lifecycle_scorer["encode"]["partitions"]] == [
        ("2000-01-01", "hit"),
        ("2000-01-02", "hit"),
    ]
    assert "catboost_train" in lifecycle_scorer["train"]["training_command"]
    model_parameter = Path(lifecycle_scorer_pipeline.parameter_file_path()) / "model.parameter"
    assert model_parameter.is_file()
    assert model_parameter.stat().st_size > 0

    product_parameters = Path(lifecycle_pipeline.parameter_source_path())
    assert product_parameters.is_dir()
    assert Path(lifecycle_result["predict"]["parameters"]) == product_parameters
    expected_parameter_files = {
        "example-product-search-index/catalog.jsonl",
        "example-product-ranker/algorithm-parameters.json",
        "example-product-scorer/model_parameter/model.parameter",
        "example-product-scorer/encoded-schema-description",
    }
    for parameter_file in expected_parameter_files:
        assert (product_parameters / parameter_file).is_file()
    assert Path(lifecycle_scorer_pipeline.parameter_source_path()) == product_parameters

    search_index_pipeline = lifecycle_pipeline.dependency_pipelines["example-product-search-index"]
    assert Path(search_index_pipeline.parameter_source_path()) == product_parameters
    catalog_lines = (Path(search_index_pipeline.state_output_path()) / "catalog.jsonl").read_text().splitlines()
    assert len(catalog_lines) == 12
    assert len({json.loads(line)["action_id"] for line in catalog_lines}) == 12

    prediction_rows = [
        json.loads(line)
        for prediction_file in sorted(Path(lifecycle_pipeline.test_result_file_path()).glob("*.jsonl"))
        for line in prediction_file.read_text().splitlines()
    ]
    assert sorted(row["example_id"] for row in prediction_rows) == [
        f"product-search-2000-01-03-{index:03d}" for index in range(12)
    ]
    for row in prediction_rows:
        assert row["additional_properties"] == {
            "catalog_size": 12,
            "retrieved_candidates": 8,
            "retrieval_policy": "lexical-title-category-v1",
        }
        decisions = row["result"]
        assert len(decisions) == 4
        assert [decision["rank"] for decision in decisions] == [0, 1, 2, 3]
        assert [decision["score"] for decision in decisions] == sorted(
            (decision["score"] for decision in decisions), reverse=True
        )
        assert sum(decision["reward"] for decision in decisions) == 1.0
        assert len({decision["action_id"] for decision in decisions}) == 4
        assert all(
            decision["additional_properties"]["retrieval_policy"] == "lexical-title-category-v1"
            for decision in decisions
        )

    assert lifecycle_result["predict"]["records_processed"] == 12
    assert lifecycle_result["evaluate"]["evaluation_policy"] == {"missing_reward_policy": "error"}
    for metric_name in ("ndcg_at_10", "map_at_10", "roc_auc", "pr_auc"):
        assert 0.0 < lifecycle_result["evaluate"][metric_name]["value"] <= 1.0

    parameter_package = Path(lifecycle_pipeline.materialize_predict_parameter_package())
    assert parameter_package.is_file()
    with zipfile.ZipFile(parameter_package) as parameter_archive:
        assert expected_parameter_files.issubset(parameter_archive.namelist())
