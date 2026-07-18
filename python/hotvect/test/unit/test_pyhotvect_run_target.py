import contextlib
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import MagicMock

import pytest


def _make_run_all_pipeline(tmp_path: Path):
    from hotvect.pyhotvect import AlgorithmPipeline

    pipeline = AlgorithmPipeline.__new__(AlgorithmPipeline)
    pipeline.algorithm_definition = {}
    pipeline.algorithm_name = "algo"
    pipeline.algorithm_version = "55.7.0"
    pipeline.hyper_parameter_version = ""
    pipeline.parameter_version = "last_test_date_2026-02-17"
    pipeline.last_test_time = datetime(2026, 2, 17, tzinfo=timezone.utc)
    pipeline.ran_at = "2026-02-17T00:00:00Z"
    pipeline.hyperparameter_slug = MagicMock(return_value="algo@55.7.0")
    pipeline.clean = MagicMock()
    pipeline.cache_path = MagicMock(return_value=str(tmp_path / "cache"))
    pipeline._pipe_python_logs_to_metadata_dir = MagicMock(return_value=contextlib.nullcontext())
    pipeline._write_algorithm_definition = MagicMock(return_value=str(tmp_path / "algo-definition.json"))
    pipeline.available_predict_parameter_cache_path = MagicMock(return_value=str(tmp_path / "params.zip"))
    pipeline._step_predict_parameter = MagicMock()
    pipeline.generate_states = MagicMock(return_value={"ok": True})
    pipeline.test_data_paths = MagicMock(return_value=[str(tmp_path / "test.jsonl")])
    pipeline.prediction_data_paths = MagicMock(return_value=[str(tmp_path / "predict.jsonl")])
    pipeline._require_prediction_spec = MagicMock(
        return_value={"data_prefix": "predict", "output_uri": "s3://bucket/out"}
    )
    pipeline._prediction_dates = MagicMock(return_value=[datetime(2026, 2, 17, tzinfo=timezone.utc).date()])
    pipeline.prediction_output_uri = MagicMock(return_value="s3://bucket/out/dt=2026-02-17/")
    pipeline._write_data = MagicMock()
    pipeline.metadata_path = MagicMock(return_value=str(tmp_path / "metadata"))
    pipeline.output_path = MagicMock(return_value=str(tmp_path / "output"))
    pipeline.predict_parameter_file_path = MagicMock(return_value=str(tmp_path / "params.zip"))
    pipeline.algorithm_is_state = False
    pipeline.should_train = MagicMock(return_value=False)
    pipeline.execute_performance_test = True
    pipeline.encode_test_data = False
    pipeline.execute_audit = False
    pipeline.dependency_pipelines = {}
    pipeline.run_target = "evaluate"
    pipeline._step_predict = MagicMock(side_effect=lambda result, evaluate: result.update({"predict": {"ok": True}}))
    pipeline._step_evaluate = MagicMock(side_effect=lambda result, evaluate: result.update({"evaluate": {"ok": True}}))
    pipeline._step_performance_test = MagicMock(
        side_effect=lambda result, evaluate: result.update({"performance_test": {"ok": True}})
    )
    pipeline._step_encode_test_data = MagicMock(side_effect=lambda result: result.update({"encode_test": {"ok": True}}))
    pipeline._step_execute_audit = MagicMock(side_effect=lambda result: result.update({"audit": {"ok": True}}))
    return pipeline


def test_run_all_uses_pipeline_run_target_by_default(tmp_path: Path, monkeypatch) -> None:
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_run_all_pipeline(tmp_path)
    pipeline.run_target = "predict"
    pipeline.test_data_paths = MagicMock(side_effect=AssertionError("predict target should not use test data"))

    monkeypatch.setattr(pyhotvect_module, "clean_dir", MagicMock())

    result = pipeline.run_all(clean=False)

    pipeline.prediction_data_paths.assert_called()
    assert pipeline._step_predict.call_count == 1
    assert pipeline._step_predict.call_args.args[1] == "predict"
    assert pipeline._step_evaluate.call_count == 0
    assert result["evaluate"] == {"skipped": "Because target was predict"}
    assert result["performance_test"] == {"skipped": "Because target was predict"}
    assert result["encode_test"] == {"skipped": "Because target was predict"}
    assert result["audit"] == {"skipped": "Because target was predict"}


def test_run_all_persists_total_time(tmp_path: Path, monkeypatch) -> None:
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_run_all_pipeline(tmp_path)

    monkeypatch.setattr(pyhotvect_module, "clean_dir", MagicMock())

    result = pipeline.run_all(clean=False)

    written_result = pipeline._write_data.call_args.args[0]
    assert "total_time" in result["timing_info_sec"]
    assert "total_time" in written_result["timing_info_sec"]


def test_dependency_preparation_uses_parameters_target_by_default(tmp_path: Path, monkeypatch) -> None:
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_run_all_pipeline(tmp_path)
    dependency_pipeline = MagicMock()
    dependency_pipeline._target_when_used_as_dependency = MagicMock(return_value="parameters")
    dependency_pipeline.algorithm_is_state = False
    dependency_pipeline.run_all = MagicMock(return_value={"ok": True})
    pipeline.dependency_pipelines = {"dep": dependency_pipeline}

    monkeypatch.setattr(pyhotvect_module, "clean_dir", MagicMock())

    pipeline.run_all(clean=False)

    dependency_pipeline.run_all.assert_called_once_with(
        clean=False,
        target="parameters",
        prepare_raw_state_for_parent_packaging=False,
    )


def test_dependency_preparation_requests_raw_state_for_state_dependencies(tmp_path: Path, monkeypatch) -> None:
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_run_all_pipeline(tmp_path)
    dependency_pipeline = MagicMock()
    dependency_pipeline.algorithm_is_state = "generator_factory_classname"
    dependency_pipeline._target_when_used_as_dependency = MagicMock(return_value="parameters")
    dependency_pipeline.run_all = MagicMock(return_value={"ok": True})
    pipeline.dependency_pipelines = {"dep": dependency_pipeline}

    monkeypatch.setattr(pyhotvect_module, "clean_dir", MagicMock())

    pipeline.run_all(clean=False)

    dependency_pipeline.run_all.assert_called_once_with(
        clean=False,
        target="parameters",
        prepare_raw_state_for_parent_packaging=True,
    )


def test_dependency_preparation_requests_raw_state_packaging_for_pinned_state_dependencies(
    tmp_path: Path, monkeypatch
) -> None:
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_run_all_pipeline(tmp_path)
    dependency_pipeline = MagicMock()
    dependency_pipeline.algorithm_definition = {
        "hotvect_execution_parameters": {"with_parameter": "s3://bucket/child.zip"}
    }
    dependency_pipeline.algorithm_is_state = "generator_factory_classname"
    dependency_pipeline._target_when_used_as_dependency = MagicMock(return_value="parameters")
    dependency_pipeline.run_all = MagicMock(return_value={"ok": True})
    pipeline.dependency_pipelines = {"dep": dependency_pipeline}

    monkeypatch.setattr(pyhotvect_module, "clean_dir", MagicMock())

    pipeline.run_all(clean=False)

    dependency_pipeline.run_all.assert_called_once_with(
        clean=False,
        target="parameters",
        prepare_raw_state_for_parent_packaging=True,
    )


def test_dependency_preparation_uses_inferred_dependency_target(tmp_path: Path, monkeypatch) -> None:
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_run_all_pipeline(tmp_path)
    dependency_pipeline = MagicMock()
    dependency_pipeline.algorithm_is_state = False
    dependency_pipeline._target_when_used_as_dependency = MagicMock(return_value="evaluate")
    dependency_pipeline.run_all = MagicMock(return_value={"ok": True})
    pipeline.dependency_pipelines = {"dep": dependency_pipeline}

    monkeypatch.setattr(pyhotvect_module, "clean_dir", MagicMock())

    pipeline.run_all(clean=False)

    dependency_pipeline.run_all.assert_called_once_with(
        clean=False,
        target="evaluate",
        prepare_raw_state_for_parent_packaging=False,
    )


def test_dependency_preparation_uses_evaluate_target_for_hotvect_9_training_image(tmp_path: Path, monkeypatch) -> None:
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_run_all_pipeline(tmp_path)
    pipeline.algorithm_definition = {
        "training_container": ("123456789012.dkr.ecr.eu-central-1.amazonaws.com/example-namespace/hotvect:9.34.0")
    }
    dependency_pipeline = MagicMock()
    dependency_pipeline.algorithm_is_state = False
    dependency_pipeline._target_when_used_as_dependency = MagicMock(return_value="parameters")
    dependency_pipeline.run_all = MagicMock(return_value={"ok": True})
    pipeline.dependency_pipelines = {"dep": dependency_pipeline}

    monkeypatch.setattr(pyhotvect_module, "clean_dir", MagicMock())

    pipeline.run_all(clean=False)

    dependency_pipeline.run_all.assert_called_once_with(
        clean=False,
        target="evaluate",
        prepare_raw_state_for_parent_packaging=False,
    )


def test_parameters_target_skips_inference_without_resolving_test_data(tmp_path: Path, monkeypatch) -> None:
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_run_all_pipeline(tmp_path)
    pipeline.test_data_paths = MagicMock(side_effect=AssertionError("parameters target should not use test data"))
    pipeline.prediction_data_paths = MagicMock(
        side_effect=AssertionError("parameters target should not use prediction data")
    )

    monkeypatch.setattr(pyhotvect_module, "clean_dir", MagicMock())

    result = pipeline.run_all(clean=False, target="parameters")

    pipeline.test_data_paths.assert_not_called()
    pipeline.prediction_data_paths.assert_not_called()
    pipeline._step_predict.assert_not_called()
    pipeline._step_evaluate.assert_not_called()
    assert result["predict"] == {"skipped": "Because target was parameters"}
    assert result["evaluate"] == {"skipped": "Because target was parameters"}
    assert result["performance_test"] == {"skipped": "Because target was parameters"}
    assert result["encode_test"] == {"skipped": "Because target was parameters"}
    assert result["audit"] == {"skipped": "Because target was parameters"}


def test_state_dependency_preparation_hydrates_predict_parameter_cache_and_skips_zip(
    tmp_path: Path, monkeypatch
) -> None:
    import zipfile

    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_run_all_pipeline(tmp_path)
    pipeline.algorithm_definition = {"state_output_filename": "state.jsonl"}
    pipeline.algorithm_is_state = "generator_factory_classname"
    cached_zip = tmp_path / "params.zip"
    with zipfile.ZipFile(cached_zip, "w") as zf:
        zf.writestr("algo/algorithm-parameters.json", "{}")
        zf.writestr("algo/state.jsonl", "{}\n")
    pipeline.available_predict_parameter_cache_path = MagicMock(return_value=str(cached_zip))

    monkeypatch.setattr(pyhotvect_module, "clean_dir", MagicMock())

    result = pipeline.run_all(clean=False, target="parameters", prepare_raw_state_for_parent_packaging=True)

    pipeline.available_predict_parameter_cache_path.assert_called_once()
    pipeline.generate_states.assert_not_called()
    pipeline._step_predict_parameter.assert_not_called()
    assert (tmp_path / "output" / "state.jsonl").read_text(encoding="utf-8") == "{}\n"
    assert result["package_predict_params"]["package"] is None
    assert (str(tmp_path / "output" / "state.jsonl"), "algo/state.jsonl") in result["package_predict_params"]["sources"]
    assert result["timing_info_sec"]["package_predict_params"] >= 0.0


def test_state_dependency_preparation_hydrates_with_parameter_cache_when_packaging_skip_requested(
    tmp_path: Path, monkeypatch
) -> None:
    import zipfile

    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_run_all_pipeline(tmp_path)
    pipeline.algorithm_definition = {
        "state_output_filename": "state.jsonl",
        "hotvect_execution_parameters": {"with_parameter": "s3://bucket/child.zip"},
    }
    pipeline.algorithm_is_state = "generator_factory_classname"
    cached_zip = tmp_path / "child.zip"
    with zipfile.ZipFile(cached_zip, "w") as zf:
        zf.writestr("algo/algorithm-parameters.json", "{}")
        zf.writestr("algo/state.jsonl", "{}\n")
    pipeline.available_predict_parameter_cache_path = MagicMock(return_value=str(cached_zip))

    monkeypatch.setattr(pyhotvect_module, "clean_dir", MagicMock())

    result = pipeline.run_all(clean=False, target="parameters", prepare_raw_state_for_parent_packaging=True)

    pipeline.available_predict_parameter_cache_path.assert_called_once()
    pipeline.generate_states.assert_not_called()
    pipeline._step_predict_parameter.assert_not_called()
    assert (tmp_path / "output" / "state.jsonl").read_text(encoding="utf-8") == "{}\n"
    assert result["package_predict_params"]["package"] is None


def test_prepare_raw_state_for_parent_packaging_requires_parameters_target(tmp_path: Path, monkeypatch) -> None:
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_run_all_pipeline(tmp_path)
    pipeline.algorithm_is_state = "generator_factory_classname"

    monkeypatch.setattr(pyhotvect_module, "clean_dir", MagicMock())

    with pytest.raises(ValueError, match="requires target='parameters'"):
        pipeline.run_all(clean=False, prepare_raw_state_for_parent_packaging=True)


def test_prepare_raw_state_for_parent_packaging_requires_state_algorithm(tmp_path: Path, monkeypatch) -> None:
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_run_all_pipeline(tmp_path)

    monkeypatch.setattr(pyhotvect_module, "clean_dir", MagicMock())

    with pytest.raises(ValueError, match="only supported for state algorithms"):
        pipeline.run_all(clean=False, target="parameters", prepare_raw_state_for_parent_packaging=True)


def test_run_all_records_benchmark_contract_for_performance_test(tmp_path: Path, monkeypatch) -> None:
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_run_all_pipeline(tmp_path)
    pipeline._step_performance_test = MagicMock(
        side_effect=lambda result, evaluate: result.update(
            {
                "performance_test": {
                    "samples": 1000,
                    "sample_pool_size": 250,
                    "target_rps": 120.0,
                    "workload_mode": "batch",
                    "max_threads": 2,
                    "execution_command": ["java", "-Xmx16g", "Main", "performance-test"],
                }
            }
        )
    )

    monkeypatch.setattr(pyhotvect_module, "clean_dir", MagicMock())

    result = pipeline.run_all(clean=False)

    assert result["benchmark_contract"] == {
        "parameter_path": str(tmp_path / "params.zip"),
        "source_paths": [str(tmp_path / "test.jsonl")],
        "samples": 1000,
        "sample_pool_size": 250,
        "target_rps": 120.0,
        "max_threads": 2,
        "workload_mode": "batch",
        "execution_command": ["java", "-Xmx16g", "Main", "performance-test"],
        "output_prefixes": {
            "metadata": str(tmp_path / "metadata"),
            "output": str(tmp_path / "output"),
            "performance_metadata": str(tmp_path / "metadata" / "performance-test"),
        },
    }


def test_run_all_predict_requires_prediction_spec(tmp_path: Path, monkeypatch) -> None:
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_run_all_pipeline(tmp_path)
    pipeline.run_target = "predict"
    pipeline._require_prediction_spec = MagicMock(side_effect=ValueError("missing prediction_spec"))

    monkeypatch.setattr(pyhotvect_module, "clean_dir", MagicMock())

    with pytest.raises(ValueError, match="missing prediction_spec"):
        pipeline.run_all(clean=False)
