from __future__ import annotations

import json
from datetime import date
from pathlib import Path
from unittest.mock import MagicMock

import pytest

from hotvect import pyhotvect as pyhotvect_module
from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext


def _make_pipeline(
    tmp_path: Path, *, run_target: str = "predict", data_environment: str = "staging"
) -> AlgorithmPipeline:
    data_base_dir = tmp_path / "data"
    output_base_dir = tmp_path / "out"
    metadata_base_dir = tmp_path / "meta"
    data_base_dir.mkdir(parents=True, exist_ok=True)
    output_base_dir.mkdir(parents=True, exist_ok=True)
    metadata_base_dir.mkdir(parents=True, exist_ok=True)

    algorithm_definition = {
        "algorithm_name": "test-algo",
        "algorithm_version": "1.0.0",
        "algorithm_factory_classname": "com.example.TestFactory",
        "test_data_spec": {
            "data_prefix": "test-input",
            "number_of_days": 1,
            "lag_days": 0,
            "s3_uri": {
                "production": "s3://bucket/test/prod/",
                "staging": "s3://bucket/test/staging/",
            },
        },
        "prediction_spec": {
            "data_prefix": "predict-input",
            "number_of_days": 1,
            "lag_days": 0,
            "s3_uri": {
                "production": "s3://bucket/predict/prod/",
                "staging": "s3://bucket/predict/staging/",
            },
            "output_uri": {
                "production": "s3://bucket/out/prod/dt={{ last_test_date }}/{{ parameter_version }}/",
                "staging": str(tmp_path / "published" / "{{ parameter_version }}" / "dt={{ last_test_date }}"),
            },
        },
    }

    context = AlgorithmPipelineContext(
        algorithm_jar_path=tmp_path / "algo.jar",
        state_source_base_path=data_base_dir,
        data_base_path=data_base_dir,
        metadata_base_path=metadata_base_dir,
        output_base_path=output_base_dir,
        jvm_options=["-XX:MaxRAMPercentage=90"],
    )
    return AlgorithmPipeline(
        algorithm_pipeline_context=context,
        algorithm_definition=algorithm_definition,
        last_test_time=date(2026, 1, 3),
        evaluation_func=None,
        run_target=run_target,
        data_environment=data_environment,
    )


def test_prediction_spec_adds_prediction_dependency_and_omits_output_uri(tmp_path: Path) -> None:
    pipeline = _make_pipeline(tmp_path, run_target="predict")

    dependencies = pipeline.data_dependencies()

    assert [dep.data_type for dep in dependencies] == ["prediction"]
    dependency = dependencies[0]
    assert dependency.data_prefix == "predict-input"
    assert dependency.data_dates == {date(2026, 1, 3)}
    assert dependency.additional_properties["s3_uri"]["staging"] == "s3://bucket/predict/staging/"
    assert "output_uri" not in dependency.additional_properties


def test_prediction_spec_requires_output_uri_key(tmp_path: Path) -> None:
    pipeline = _make_pipeline(tmp_path, run_target="predict")
    prediction_spec = dict(pipeline.algorithm_definition["prediction_spec"])
    prediction_spec["output_s3_uri"] = prediction_spec.pop("output_uri")
    pipeline.algorithm_definition["prediction_spec"] = prediction_spec

    with pytest.raises(ValueError, match="prediction_spec.output_uri is required"):
        pipeline.prediction_output_uri()


def test_prediction_output_uri_renders_environment_template(tmp_path: Path) -> None:
    pipeline = _make_pipeline(tmp_path, run_target="predict", data_environment="staging")

    output_uri = pipeline.prediction_output_uri()

    assert output_uri.endswith("/published/last_test_date_2026-01-03/dt=2026-01-03")


def test_predict_target_uses_prediction_spec_source_and_publishes_output(tmp_path: Path) -> None:
    pipeline = _make_pipeline(tmp_path, run_target="predict", data_environment="staging")
    predict_dt_dir = tmp_path / "data" / "predict-input" / "dt=2026-01-03"
    predict_dt_dir.mkdir(parents=True, exist_ok=True)
    (predict_dt_dir / "part-00000.jsonl").write_text('{"example_id":"a","score":1.0}\n', encoding="utf-8")

    pipeline._base_command = MagicMock(return_value=["fake-predict"])

    def _fake_stream_output(*, stage: str, cmd: list[str]) -> None:
        assert "--source" in cmd
        source_arg = cmd[cmd.index("--source") + 1]
        assert "predict-input" in source_arg
        assert "test-input" not in source_arg
        dest_arg = cmd[cmd.index("--dest") + 1]
        dest_path = Path(dest_arg)
        dest_path.mkdir(parents=True, exist_ok=True)
        (dest_path / "part-00000.jsonl").write_text('{"example_id":"a","score":1.0}\n', encoding="utf-8")
        metadata_path = Path(pipeline._stage_metadata_file(stage))
        metadata_path.parent.mkdir(parents=True, exist_ok=True)
        metadata_path.write_text(json.dumps({"ok": True}), encoding="utf-8")

    pipeline._stream_output_to_stage_log = _fake_stream_output

    metadata = pipeline.predict(evaluate=False, target="predict")

    published_dir = tmp_path / "published" / pipeline.parameter_version / "dt=2026-01-03"
    published_file = published_dir / "part-00000.jsonl"
    assert published_file.exists()
    assert published_file.read_text(encoding="utf-8").strip() == '{"example_id":"a","score":1.0}'
    assert metadata["prediction_input_kind"] == "prediction_spec"
    assert metadata["prediction_output_uri"] == str(published_dir)
    assert all("predict-input" in path for path in metadata["prediction_source_paths"])


def test_predict_target_accepts_relative_prediction_output_uri(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.chdir(tmp_path)
    pipeline = _make_pipeline(tmp_path, run_target="predict", data_environment="staging")
    pipeline.algorithm_definition["prediction_spec"]["output_uri"]["staging"] = "published"

    predict_dt_dir = tmp_path / "data" / "predict-input" / "dt=2026-01-03"
    predict_dt_dir.mkdir(parents=True, exist_ok=True)
    (predict_dt_dir / "part-00000.jsonl").write_text('{"example_id":"a","score":1.0}\n', encoding="utf-8")

    pipeline._base_command = MagicMock(return_value=["fake-predict"])

    def _fake_stream_output(*, stage: str, cmd: list[str]) -> None:
        dest_arg = cmd[cmd.index("--dest") + 1]
        dest_path = Path(dest_arg)
        dest_path.mkdir(parents=True, exist_ok=True)
        (dest_path / "part-00000.jsonl").write_text('{"example_id":"a","score":1.0}\n', encoding="utf-8")
        metadata_path = Path(pipeline._stage_metadata_file(stage))
        metadata_path.parent.mkdir(parents=True, exist_ok=True)
        metadata_path.write_text(json.dumps({"ok": True}), encoding="utf-8")

    pipeline._stream_output_to_stage_log = _fake_stream_output

    metadata = pipeline.predict(evaluate=False, target="predict")

    published_dir = tmp_path / "published"
    published_file = published_dir / "part-00000.jsonl"
    assert published_file.exists()
    assert published_file.read_text(encoding="utf-8").strip() == '{"example_id":"a","score":1.0}'
    assert metadata["prediction_output_uri"] == "published"


def test_predict_target_requires_clean_s3_publish_prefix(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    pipeline = _make_pipeline(tmp_path, run_target="predict", data_environment="production")
    predict_dt_dir = tmp_path / "data" / "predict-input" / "dt=2026-01-03"
    predict_dt_dir.mkdir(parents=True, exist_ok=True)
    (predict_dt_dir / "part-00000.jsonl").write_text('{"example_id":"a","score":1.0}\n', encoding="utf-8")

    pipeline._base_command = MagicMock(return_value=["fake-predict"])

    def _fake_stream_output(*, stage: str, cmd: list[str]) -> None:
        dest_arg = cmd[cmd.index("--dest") + 1]
        dest_path = Path(dest_arg)
        dest_path.mkdir(parents=True, exist_ok=True)
        (dest_path / "part-00000.jsonl").write_text('{"example_id":"a","score":1.0}\n', encoding="utf-8")
        metadata_path = Path(pipeline._stage_metadata_file(stage))
        metadata_path.parent.mkdir(parents=True, exist_ok=True)
        metadata_path.write_text(json.dumps({"ok": True}), encoding="utf-8")

    pipeline._stream_output_to_stage_log = _fake_stream_output
    store_file_mock = MagicMock()

    def _raise_if_dirty(uri: str) -> None:
        raise ValueError(f"S3 prediction output destination must be empty before publish: {uri}")

    monkeypatch.setattr(pyhotvect_module, "store_file", store_file_mock)
    monkeypatch.setattr(pyhotvect_module, "assert_s3_directory_uri_empty", _raise_if_dirty)

    with pytest.raises(ValueError, match="must be empty before publish"):
        pipeline.predict(evaluate=False, target="predict")

    store_file_mock.assert_not_called()


def test_prediction_output_uri_requires_matching_environment(tmp_path: Path) -> None:
    pipeline = _make_pipeline(tmp_path, run_target="predict", data_environment="test")

    with pytest.raises(ValueError, match="Environment 'test' not found"):
        pipeline.prediction_output_uri()


def test_prediction_output_uri_requires_exact_environment_key(tmp_path: Path) -> None:
    pipeline = _make_pipeline(tmp_path, run_target="predict", data_environment="Production")

    with pytest.raises(ValueError, match="Environment 'Production' not found"):
        pipeline.prediction_output_uri()


def test_prediction_dependency_auto_attach_requires_exact_environment_key() -> None:
    from hotvect.backtest import resolve_dependency_s3_uri
    from hotvect.pyhotvect import DataDependency

    dependency = DataDependency(
        algorithm_name="test-algo",
        algorithm_version="1.0.0",
        data_prefix="predict-input",
        data_dates={date(2026, 1, 3)},
        data_type="prediction",
        additional_properties={
            "s3_uri": {
                "production": "s3://bucket/predict/prod/",
                "staging": "s3://bucket/predict/staging/",
            }
        },
    )

    with pytest.raises(ValueError, match="Environment 'Production' not found"):
        resolve_dependency_s3_uri(
            dependency,
            auto_attach_data_environment="Production",
            auto_attach_data_default_s3_base=None,
        )


def test_dependency_pipelines_use_parameters_target_even_when_parent_uses_predict(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    data_base_dir = tmp_path / "data"
    output_base_dir = tmp_path / "out"
    metadata_base_dir = tmp_path / "meta"
    data_base_dir.mkdir(parents=True, exist_ok=True)
    output_base_dir.mkdir(parents=True, exist_ok=True)
    metadata_base_dir.mkdir(parents=True, exist_ok=True)

    parent_algorithm_definition = {
        "algorithm_name": "parent-algo",
        "algorithm_version": "1.0.0",
        "algorithm_factory_classname": "com.example.ParentFactory",
        "train_data_spec": {"data_prefix": "train-input", "number_of_days": 1, "lag_days": 1},
        "prediction_spec": {
            "data_prefix": "predict-input",
            "number_of_days": 1,
            "lag_days": 0,
            "s3_uri": {"production": "s3://bucket/predict/"},
            "output_uri": {"production": "s3://bucket/out/"},
        },
        "dependencies": ["dep-algo"],
    }

    dep_algorithm_definition = {
        "algorithm_name": "dep-algo",
        "algorithm_version": "1.0.0",
        "algorithm_factory_classname": "com.example.DepFactory",
        "train_data_spec": {"data_prefix": "dep-train", "number_of_days": 1, "lag_days": 1},
        "test_data_spec": {"data_prefix": "dep-test", "number_of_days": 1, "lag_days": 0},
    }

    monkeypatch.setattr(
        pyhotvect_module,
        "read_algorithm_definition_from_jar",
        lambda **kwargs: dep_algorithm_definition,
    )

    pipeline = AlgorithmPipeline(
        algorithm_pipeline_context=AlgorithmPipelineContext(
            algorithm_jar_path=tmp_path / "algo.jar",
            state_source_base_path=data_base_dir,
            data_base_path=data_base_dir,
            metadata_base_path=metadata_base_dir,
            output_base_path=output_base_dir,
            jvm_options=["-XX:MaxRAMPercentage=90"],
        ),
        algorithm_definition=parent_algorithm_definition,
        last_test_time=date(2026, 1, 3),
        evaluation_func=None,
        run_target="predict",
        data_environment="production",
    )

    assert pipeline.dependency_pipelines["dep-algo"].run_target == "parameters"
    dep_pipeline = pipeline.dependency_pipelines["dep-algo"]
    original_dep_data_dependencies = dep_pipeline.data_dependencies
    dep_pipeline.data_dependencies = MagicMock(side_effect=original_dep_data_dependencies)

    dependencies = pipeline.data_dependencies()
    dep_dependencies = [dep for dep in dependencies if dep.algorithm_name == "dep-algo"]

    dep_pipeline.data_dependencies.assert_called_once_with(target="parameters")
    assert [dep.data_type for dep in dep_dependencies] == ["train"]

    dep_pipeline.data_dependencies.reset_mock()
    dependencies = pipeline.data_dependencies(target="parameters")
    dep_dependencies = [dep for dep in dependencies if dep.algorithm_name == "dep-algo"]

    dep_pipeline.data_dependencies.assert_called_once_with(target="parameters")
    assert [dep.data_type for dep in dep_dependencies] == ["train"]


def test_dependency_pipeline_escalates_to_evaluate_when_child_enables_evaluate(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    data_base_dir = tmp_path / "data"
    output_base_dir = tmp_path / "out"
    metadata_base_dir = tmp_path / "meta"
    data_base_dir.mkdir(parents=True, exist_ok=True)
    output_base_dir.mkdir(parents=True, exist_ok=True)
    metadata_base_dir.mkdir(parents=True, exist_ok=True)

    parent_algorithm_definition = {
        "algorithm_name": "parent-algo",
        "algorithm_version": "1.0.0",
        "algorithm_factory_classname": "com.example.ParentFactory",
        "test_data_spec": {"data_prefix": "parent-test", "number_of_days": 1, "lag_days": 0},
        "dependencies": ["dep-algo"],
    }

    dep_algorithm_definition = {
        "algorithm_name": "dep-algo",
        "algorithm_version": "1.0.0",
        "algorithm_factory_classname": "com.example.DepFactory",
        "train_data_spec": {"data_prefix": "dep-train", "number_of_days": 1, "lag_days": 1},
        "test_data_spec": {"data_prefix": "dep-test", "number_of_days": 1, "lag_days": 0},
        "hotvect_execution_parameters": {"evaluate": {"enabled": True}},
    }

    monkeypatch.setattr(
        pyhotvect_module,
        "read_algorithm_definition_from_jar",
        lambda **kwargs: dep_algorithm_definition,
    )

    pipeline = AlgorithmPipeline(
        algorithm_pipeline_context=AlgorithmPipelineContext(
            algorithm_jar_path=tmp_path / "algo.jar",
            state_source_base_path=data_base_dir,
            data_base_path=data_base_dir,
            metadata_base_path=metadata_base_dir,
            output_base_path=output_base_dir,
            jvm_options=["-XX:MaxRAMPercentage=90"],
        ),
        algorithm_definition=parent_algorithm_definition,
        last_test_time=date(2026, 1, 3),
        evaluation_func=None,
        run_target="evaluate",
        data_environment="production",
    )

    dep_pipeline = pipeline.dependency_pipelines["dep-algo"]
    original_dep_data_dependencies = dep_pipeline.data_dependencies
    dep_pipeline.data_dependencies = MagicMock(side_effect=original_dep_data_dependencies)

    dependencies = pipeline.data_dependencies()
    dep_dependencies = [dep for dep in dependencies if dep.algorithm_name == "dep-algo"]

    dep_pipeline.data_dependencies.assert_called_once_with(target="evaluate")
    assert [dep.data_type for dep in dep_dependencies] == ["train", "test"]


def test_dependency_pipeline_escalates_to_evaluate_when_dependency_override_enables_evaluate(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    data_base_dir = tmp_path / "data"
    output_base_dir = tmp_path / "out"
    metadata_base_dir = tmp_path / "meta"
    data_base_dir.mkdir(parents=True, exist_ok=True)
    output_base_dir.mkdir(parents=True, exist_ok=True)
    metadata_base_dir.mkdir(parents=True, exist_ok=True)

    parent_algorithm_definition = {
        "algorithm_name": "parent-algo",
        "algorithm_version": "1.0.0",
        "algorithm_factory_classname": "com.example.ParentFactory",
        "test_data_spec": {"data_prefix": "parent-test", "number_of_days": 1, "lag_days": 0},
        "dependencies": {
            "dep-algo": {
                "hotvect_execution_parameters": {
                    "evaluate": {
                        "enabled": True,
                    }
                }
            }
        },
    }

    dep_algorithm_definition = {
        "algorithm_name": "dep-algo",
        "algorithm_version": "1.0.0",
        "algorithm_factory_classname": "com.example.DepFactory",
        "train_data_spec": {"data_prefix": "dep-train", "number_of_days": 1, "lag_days": 1},
        "test_data_spec": {"data_prefix": "dep-test", "number_of_days": 1, "lag_days": 0},
    }

    monkeypatch.setattr(
        pyhotvect_module,
        "read_algorithm_definition_from_jar",
        lambda **kwargs: dep_algorithm_definition,
    )

    pipeline = AlgorithmPipeline(
        algorithm_pipeline_context=AlgorithmPipelineContext(
            algorithm_jar_path=tmp_path / "algo.jar",
            state_source_base_path=data_base_dir,
            data_base_path=data_base_dir,
            metadata_base_path=metadata_base_dir,
            output_base_path=output_base_dir,
            jvm_options=["-XX:MaxRAMPercentage=90"],
        ),
        algorithm_definition=parent_algorithm_definition,
        last_test_time=date(2026, 1, 3),
        evaluation_func=None,
        run_target="evaluate",
        data_environment="production",
    )

    dep_pipeline = pipeline.dependency_pipelines["dep-algo"]
    original_dep_data_dependencies = dep_pipeline.data_dependencies
    dep_pipeline.data_dependencies = MagicMock(side_effect=original_dep_data_dependencies)

    dependencies = pipeline.data_dependencies()
    dep_dependencies = [dep for dep in dependencies if dep.algorithm_name == "dep-algo"]

    dep_pipeline.data_dependencies.assert_called_once_with(target="evaluate")
    assert [dep.data_type for dep in dep_dependencies] == ["train", "test"]


def test_dependency_pipeline_escalates_to_evaluate_for_hotvect_9_training_image(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    data_base_dir = tmp_path / "data"
    output_base_dir = tmp_path / "out"
    metadata_base_dir = tmp_path / "meta"
    data_base_dir.mkdir(parents=True, exist_ok=True)
    output_base_dir.mkdir(parents=True, exist_ok=True)
    metadata_base_dir.mkdir(parents=True, exist_ok=True)

    parent_algorithm_definition = {
        "algorithm_name": "parent-algo",
        "algorithm_version": "1.0.0",
        "algorithm_factory_classname": "com.example.ParentFactory",
        "test_data_spec": {"data_prefix": "parent-test", "number_of_days": 1, "lag_days": 0},
        "training_container": ("123456789012.dkr.ecr.eu-central-1.amazonaws.com/example-namespace/hotvect:9.34.0"),
        "dependencies": ["dep-algo"],
    }

    dep_algorithm_definition = {
        "algorithm_name": "dep-algo",
        "algorithm_version": "1.0.0",
        "algorithm_factory_classname": "com.example.DepFactory",
        "train_data_spec": {"data_prefix": "dep-train", "number_of_days": 1, "lag_days": 1},
        "test_data_spec": {"data_prefix": "dep-test", "number_of_days": 1, "lag_days": 0},
    }

    monkeypatch.setattr(
        pyhotvect_module,
        "read_algorithm_definition_from_jar",
        lambda **kwargs: dep_algorithm_definition,
    )

    pipeline = AlgorithmPipeline(
        algorithm_pipeline_context=AlgorithmPipelineContext(
            algorithm_jar_path=tmp_path / "algo.jar",
            state_source_base_path=data_base_dir,
            data_base_path=data_base_dir,
            metadata_base_path=metadata_base_dir,
            output_base_path=output_base_dir,
            jvm_options=["-XX:MaxRAMPercentage=90"],
        ),
        algorithm_definition=parent_algorithm_definition,
        last_test_time=date(2026, 1, 3),
        evaluation_func=None,
        run_target="evaluate",
        data_environment="production",
    )

    dep_pipeline = pipeline.dependency_pipelines["dep-algo"]
    original_dep_data_dependencies = dep_pipeline.data_dependencies
    dep_pipeline.data_dependencies = MagicMock(side_effect=original_dep_data_dependencies)

    dependencies = pipeline.data_dependencies()
    dep_dependencies = [dep for dep in dependencies if dep.algorithm_name == "dep-algo"]

    dep_pipeline.data_dependencies.assert_called_once_with(target="evaluate")
    assert [dep.data_type for dep in dep_dependencies] == ["train", "test"]


def test_data_dependencies_accept_target_override(tmp_path: Path) -> None:
    pipeline = _make_pipeline(tmp_path, run_target="evaluate")

    dependencies = pipeline.data_dependencies(target="predict")

    assert [dep.data_type for dep in dependencies] == ["prediction"]
