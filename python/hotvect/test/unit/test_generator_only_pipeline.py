import json
from datetime import date
from pathlib import Path
from zipfile import ZipFile

import pytest

from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext
from hotvect.utils import MalformedAlgorithmException


def _definition():
    return {
        "algorithm_name": "generated-state",
        "algorithm_version": "1.0.0",
        "generator_factory_classname": "example.StateGeneratorFactory",
        "source_data": {},
        "state_output_filename": "state.txt",
    }


def _pipeline(tmp_path, definition, target="parameters"):
    jar = tmp_path / "algorithm.jar"
    with ZipFile(jar, "w") as archive:
        archive.writestr("generated-state-algorithm-definition.json", json.dumps(definition))
    return AlgorithmPipeline(
        algorithm_pipeline_context=AlgorithmPipelineContext(
            algorithm_jar_path=jar,
            data_base_path=tmp_path / "data",
            metadata_base_path=tmp_path / "metadata",
            output_base_path=tmp_path / "output",
        ),
        algorithm_definition="generated-state",
        last_test_time=date(2026, 9, 7),
        evaluation_func=None,
        run_target=target,
    )


@pytest.mark.parametrize("with_runtime_factory", [False, True])
def test_parameter_target_generates_state_without_running_inference(tmp_path, monkeypatch, with_runtime_factory):
    definition = _definition()
    if with_runtime_factory:
        definition["algorithm_factory_classname"] = "example.StateLoaderFactory"
    pipeline = _pipeline(tmp_path, definition)
    calls = []

    def run_generator(*, stage, cmd):
        assert stage == "generate-state"
        assert "generate-state" in cmd
        destination = Path(cmd[cmd.index("--dest") + 1])
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text("generated lookup table")
        Path(pipeline._stage_metadata_file(stage)).write_text(json.dumps({"records": 1}))
        calls.append(cmd)

    # Exercise real pipeline initialization, target selection and packaging; only the JVM process is replaced.
    monkeypatch.setattr(pipeline, "_stream_output_to_stage_log", run_generator)
    result = pipeline.run_all()

    assert len(calls) == 1
    assert Path(pipeline.state_output_path()).read_text() == "generated lookup table"
    assert result["run_target"] == "parameters"
    for stage in ("predict", "evaluate", "performance_test", "encode_test", "audit"):
        assert result[stage]["skipped"] == "This algorithm is a state"


@pytest.mark.parametrize("target", ["predict", "evaluate", "encode-cache"])
def test_generator_only_definition_rejects_runtime_targets(tmp_path, target):
    with pytest.raises(ValueError, match=f"requires algorithm_factory_classname for target '{target}'"):
        _pipeline(tmp_path, _definition(), target)


@pytest.mark.parametrize("target", ["predict", "evaluate", "encode-cache"])
def test_generator_only_pipeline_rejects_runtime_target_override(tmp_path, target):
    pipeline = _pipeline(tmp_path, _definition())
    with pytest.raises(ValueError, match=f"requires algorithm_factory_classname for target '{target}'"):
        pipeline.run_all(target=target)


def test_parameter_target_still_requires_a_construction_entry_point(tmp_path):
    definition = _definition()
    del definition["generator_factory_classname"]
    with pytest.raises(MalformedAlgorithmException, match="algorithm_factory_classname or generator_factory_classname"):
        _pipeline(tmp_path, definition)
