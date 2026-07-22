from datetime import date

from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext


def test_algorithm_pipeline_preserves_original_override_fragment(tmp_path, monkeypatch) -> None:
    algo_jar = tmp_path / "algo.jar"
    algo_jar.write_bytes(b"dummy")

    base_definition = {
        "algorithm_name": "demo-algo",
        "algorithm_version": "1.2.3",
        "algorithm_factory_classname": "x.y.Z",
        "training_lag_days": 1,
    }
    override_fragment = {
        "hyperparameter_version": "hp-v1",
        "training_lag_days": 7,
    }

    monkeypatch.setattr("hotvect.pyhotvect.read_algorithm_definition_from_jar", lambda **kwargs: base_definition)

    pipeline = AlgorithmPipeline(
        algorithm_pipeline_context=AlgorithmPipelineContext(
            algorithm_jar_path=algo_jar,
            data_base_path=tmp_path / "data",
            metadata_base_path=tmp_path / "meta",
            output_base_path=tmp_path / "out",
            state_source_base_path=tmp_path / "state",
            jvm_options=[],
        ),
        algorithm_definition=("demo-algo", override_fragment),
        last_test_time=date(2026, 4, 18),
        evaluation_func=None,
    )

    assert pipeline.algorithm_definition_override == override_fragment
    assert pipeline.algorithm_definition["hyperparameter_version"] == "hp-v1"
    assert pipeline.algorithm_definition["training_lag_days"] == 7
