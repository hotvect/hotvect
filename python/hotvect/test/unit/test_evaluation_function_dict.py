from __future__ import annotations

import json
from datetime import date
from pathlib import Path

import pytest

import hotvect.pyhotvect as pyhotvect
from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext


def _write_test_jar(path: Path, *, algo_name: str, algo_def: dict) -> None:
    import zipfile

    with zipfile.ZipFile(path, "w") as z:
        z.writestr(f"{algo_name}-algorithm-definition.json", json.dumps(algo_def).encode("utf-8"))


def test_evaluation_function_dict_supports_name_and_arguments(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    calls = []

    def _test_eval(result_path: str, *, alpha: int) -> dict:
        calls.append({"result_path": result_path, "alpha": alpha})
        return {"ok": True}

    monkeypatch.setitem(pyhotvect.EVALUATION_FUNCTIONS, "test_eval", _test_eval)

    algo_name = "test-algo"
    jar_def = {
        "algorithm_name": algo_name,
        "algorithm_version": "1.0.0",
        "algorithm_factory_classname": "com.acme.Factory",
    }
    _write_test_jar(tmp_path / "algo.jar", algo_name=algo_name, algo_def=jar_def)

    override_def = {
        "algorithm_name": "test-algo",
        "hotvect_execution_parameters": {
            "evaluation_function": {"name": "test_eval", "arguments": {"alpha": 123}},
        },
    }

    ctx = AlgorithmPipelineContext(
        algorithm_jar_path=tmp_path / "algo.jar",
        data_base_path=tmp_path / "data",
        metadata_base_path=tmp_path / "meta",
        output_base_path=tmp_path / "out",
        state_source_base_path=tmp_path / "state",
        jvm_options=[],
    )

    pipeline = AlgorithmPipeline(
        algorithm_pipeline_context=ctx,
        algorithm_definition=(algo_name, override_def),
        last_test_time=date(2026, 1, 1),
        evaluation_func=lambda _: {"fallback": True},
        parameter_version="pv",
    )

    # Ensure a "predictions" file exists, so the evaluation function can open it if it wants.
    score_path = Path(pipeline.test_result_file_path())
    score_path.parent.mkdir(parents=True, exist_ok=True)
    score_path.write_text(json.dumps({"dummy": True}))

    pipeline.evaluate(evaluate=True)

    assert calls == [{"result_path": str(score_path), "alpha": 123}]


def test_evaluation_function_dict_requires_name(tmp_path: Path) -> None:
    algo_name = "test-algo"
    jar_def = {
        "algorithm_name": algo_name,
        "algorithm_version": "1.0.0",
        "algorithm_factory_classname": "com.acme.Factory",
    }
    _write_test_jar(tmp_path / "algo.jar", algo_name=algo_name, algo_def=jar_def)

    override_def = {
        "algorithm_name": algo_name,
        "hotvect_execution_parameters": {
            "evaluation_function": {"arguments": {"alpha": 123}},
        },
    }

    ctx = AlgorithmPipelineContext(
        algorithm_jar_path=tmp_path / "algo.jar",
        data_base_path=tmp_path / "data",
        metadata_base_path=tmp_path / "meta",
        output_base_path=tmp_path / "out",
        state_source_base_path=tmp_path / "state",
        jvm_options=[],
    )

    with pytest.raises(ValueError, match=r"evaluation_function is a dict but has no 'name'"):
        AlgorithmPipeline(
            algorithm_pipeline_context=ctx,
            algorithm_definition=(algo_name, override_def),
            last_test_time=date(2026, 1, 1),
            evaluation_func=lambda _: {"fallback": True},
            parameter_version="pv",
        )
