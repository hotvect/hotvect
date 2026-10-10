import json
import zipfile
from pathlib import Path

import pytest

from hotvect.dto import AlgorithmDefinition, DependencyStateMetadata, TrainableAlgorithmDefinition
from hotvect.utils import (
    ALGORITHM_DEFINITION_FILENAME,
    ALGORITHM_DEPENDENCIES_FILENAME,
    MalformedAlgorithmException,
    hotvect_algorithm_definition,
    hotvect_state_dependency_metadata,
    read_algorithm_definition_from_jar,
)


def _write_json(path: Path, value) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
    return path


def _write_record(definition_path: Path, dependency_slugs: dict) -> Path:
    return _write_json(definition_path.with_name(ALGORITHM_DEPENDENCIES_FILENAME), dependency_slugs)


@pytest.fixture
def definition_path(tmp_path):
    return tmp_path / "metadata" / "scorer@ranker-4.0.0" / "last_test_date_2000-08-19" / ALGORITHM_DEFINITION_FILENAME


def test_dtos_are_plain_dictionaries():
    definition = AlgorithmDefinition(
        algorithm_name="scorer",
        algorithm_version="ranker-4.0.0",
        algorithm_factory_classname="example.ScorerFactory",
    )
    assert type(definition) is dict
    assert DependencyStateMetadata() == {}
    assert DependencyStateMetadata(generate_state_metadata={"features": ["price"]}) == {
        "generate_state_metadata": {"features": ["price"]}
    }


def test_trainable_definition_can_include_partial_dependency_overrides():
    """Combine training and dependency contracts without requiring complete overrides."""

    definition = TrainableAlgorithmDefinition(
        algorithm_name="scorer",
        algorithm_version="1.0.0",
        algorithm_factory_classname="example.ScorerFactory",
        training_command="train-model",
        dependencies={"encoder": {"algorithm_parameters": {"oov": 0}}},
    )
    assert set(definition) == TrainableAlgorithmDefinition.__required_keys__ | {"dependencies"}
    assert definition["dependencies"]["encoder"] == {"algorithm_parameters": {"oov": 0}}


@pytest.mark.parametrize("as_string", [False, True])
def test_read_effective_definition_preserves_all_fields(definition_path, as_string):
    definition = {
        "algorithm_name": "scorer",
        "algorithm_version": "ranker-4.0.0",
        "number_of_training_days": 3,
        "training_lag_days": 1,
        "dependencies": {"encoder": {"transformer_parameters": {"features": ["größe"]}}},
        "algorithm_parameters": {"nested": [None, True, 1.25]},
    }
    _write_json(definition_path, definition)
    assert hotvect_algorithm_definition(str(definition_path) if as_string else definition_path) == definition


def test_dependency_metadata_uses_exact_direct_artifacts(definition_path):
    _write_record(
        definition_path,
        {"encoder": "encoder@ranker-4.0.0", "model": "model@2.0.0", "missing": "missing@ranker-4.0.0"},
    )
    metadata_root = definition_path.parents[2]
    parameter_version = definition_path.parent.name
    encoder_path = metadata_root / "encoder@ranker-4.0.0" / parameter_version / "generate-state" / "metadata.json"
    model_path = metadata_root / "model@2.0.0" / parameter_version / "generate-state" / "metadata.json"
    encoder_metadata = {
        "configured_feature_names": ["größe", "price"],
        "indexed_feature_vocab_sizes": {"größe": 42},
        "nested": {"values": [None, True, 0.25]},
    }
    _write_json(encoder_path, encoder_metadata)
    _write_json(model_path, {})
    # Other parameter versions and stages must never satisfy the missing node.
    _write_json(metadata_root / "missing@ranker-4.0.0" / "other-run" / "generate-state" / "metadata.json", {})
    _write_json(metadata_root / "missing@ranker-4.0.0" / parameter_version / "train" / "metadata.json", {})
    assert hotvect_state_dependency_metadata(str(definition_path)) == {
        "encoder": {
            "generate_state_metadata_path": str(encoder_path),
            "generate_state_metadata": encoder_metadata,
        },
        "model": {"generate_state_metadata_path": str(model_path), "generate_state_metadata": {}},
        "missing": {},
    }


def test_empty_dependencies(definition_path):
    _write_record(definition_path, {})
    assert hotvect_state_dependency_metadata(definition_path) == {}


def test_missing_dependency_record_fails(definition_path):
    _write_json(definition_path, {"dependencies": ["encoder"]})
    with pytest.raises(FileNotFoundError, match=ALGORITHM_DEPENDENCIES_FILENAME):
        hotvect_state_dependency_metadata(definition_path)


def test_pipeline_writes_the_layout_the_reader_resolves(tmp_path, monkeypatch):
    from datetime import date

    from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext

    definitions = {
        "scorer": {
            "algorithm_name": "scorer",
            "algorithm_version": "1.0.0",
            "algorithm_factory_classname": "x.Scorer",
            "training_command": "train",
            "dependencies": ["encoder"],
        },
        "encoder": {
            "algorithm_name": "encoder",
            "algorithm_version": "2.0.0",
            "algorithm_factory_classname": "x.Encoder",
            "generator_factory_classname": "x.EncoderGenerator",
        },
    }
    monkeypatch.setattr(
        "hotvect.pyhotvect.read_algorithm_definition_from_jar", lambda **kw: dict(definitions[kw["algorithm_name"]])
    )
    jar = tmp_path / "algo.jar"
    jar.write_bytes(b"dummy")
    pipeline = AlgorithmPipeline(
        algorithm_pipeline_context=AlgorithmPipelineContext(
            algorithm_jar_path=jar,
            data_base_path=tmp_path / "data",
            metadata_base_path=tmp_path / "meta",
            output_base_path=tmp_path / "out",
            state_source_base_path=tmp_path / "state",
            jvm_options=[],
        ),
        algorithm_definition=("scorer", {"hyperparameter_version": "hp1"}),
        last_test_time=date(2000, 8, 19),
        evaluation_func=None,
    )
    encoder = pipeline.dependency_pipelines["encoder"]
    assert encoder.algorithm_version != pipeline.algorithm_version
    for materialized in (pipeline, encoder):
        Path(materialized.metadata_path()).mkdir(parents=True)
        materialized._write_algorithm_definition()
    metadata_path = _write_json(Path(encoder.metadata_path()) / "generate-state" / "metadata.json", {"features": 1})
    assert hotvect_state_dependency_metadata(Path(pipeline.metadata_path()) / ALGORITHM_DEFINITION_FILENAME) == {
        "encoder": {"generate_state_metadata_path": str(metadata_path), "generate_state_metadata": {"features": 1}}
    }


@pytest.mark.parametrize(
    "relative_path",
    [
        "metadata/scorer@1/run/wrong.json",
        "algorithm_definition.json",
        "run/algorithm_definition.json",
        "scorer@1/run/algorithm_definition.json",
    ],
)
def test_dependency_metadata_rejects_invalid_layout(relative_path):
    with pytest.raises(ValueError, match="Hotvect algorithm_definition_path must"):
        hotvect_state_dependency_metadata(relative_path)


def test_missing_definition_fails(definition_path):
    with pytest.raises(FileNotFoundError):
        hotvect_algorithm_definition(definition_path)
    with pytest.raises(FileNotFoundError):
        hotvect_state_dependency_metadata(definition_path)


@pytest.mark.parametrize("value", [[], None, "definition"])
def test_non_object_definition_fails(definition_path, value):
    _write_json(definition_path, value)
    with pytest.raises(ValueError, match="algorithm definition must be a JSON object"):
        hotvect_algorithm_definition(definition_path)


def test_invalid_metadata_json_fails(definition_path):
    _write_record(definition_path, {"encoder": "encoder@ranker-4.0.0"})
    metadata_path = (
        definition_path.parents[2]
        / "encoder@ranker-4.0.0"
        / definition_path.parent.name
        / "generate-state"
        / "metadata.json"
    )
    metadata_path.parent.mkdir(parents=True)
    metadata_path.write_text("{bad json", encoding="utf-8")
    with pytest.raises(json.JSONDecodeError):
        hotvect_state_dependency_metadata(definition_path)


def test_invalid_definition_json_fails(definition_path):
    definition_path.parent.mkdir(parents=True)
    definition_path.write_text("{bad json", encoding="utf-8")
    with pytest.raises(json.JSONDecodeError):
        hotvect_algorithm_definition(definition_path)


def test_jar_loader_preserves_search_precedence_and_exact_resource_name(tmp_path):
    jars = [tmp_path / name for name in ("missing.jar", "additional.jar", "main.jar")]
    definitions = [{"algorithm_version": "additional"}, {"algorithm_version": "main"}]
    with zipfile.ZipFile(jars[0], "w") as archive:
        archive.writestr("nested/scorer-algorithm-definition.json", json.dumps({"wrong": True}))
    for jar, definition in zip(jars[1:], definitions):
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("scorer-algorithm-definition.json", json.dumps(definition))
    assert read_algorithm_definition_from_jar("scorer", jars[2], jars[:2]) == definitions[0]
    assert read_algorithm_definition_from_jar("scorer", jars[2], [jars[0]]) == definitions[1]
    assert read_algorithm_definition_from_jar("scorer", jars[2]) == definitions[1]
    with pytest.raises(MalformedAlgorithmException, match="not in any Jars"):
        read_algorithm_definition_from_jar("scorer", jars[0])
