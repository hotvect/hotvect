import re
from datetime import date
from pathlib import Path

import pytest

from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext


def _context(tmp_path: Path) -> AlgorithmPipelineContext:
    return AlgorithmPipelineContext(
        algorithm_jar_path=tmp_path / "algorithm.jar",
        data_base_path=tmp_path / "data",
        metadata_base_path=tmp_path / "metadata",
        output_base_path=tmp_path / "output",
    )


def _definition(name: str, version: str, dependencies: dict[str, dict] | list[str] | None = None) -> dict:
    definition = {
        "algorithm_name": name,
        "algorithm_version": version,
        "algorithm_factory_classname": "example.Factory",
    }
    if dependencies is not None:
        definition["dependencies"] = dependencies
    return definition


def test_algorithm_pipeline_skips_slots_and_treats_shared_dependencies_as_private_offline(tmp_path, monkeypatch):
    definitions = {
        "shared-child": {
            **_definition("shared-child", "3.0.0"),
            "algorithm_parameters": {"base": True},
        },
        "private-child": _definition("private-child", "1.0.0"),
    }
    monkeypatch.setattr(
        "hotvect.pyhotvect.read_algorithm_definition_from_jar",
        lambda *, algorithm_name, **_: definitions[algorithm_name],
    )

    pipeline = AlgorithmPipeline(
        algorithm_pipeline_context=_context(tmp_path),
        algorithm_definition=_definition(
            "parent",
            "1.0.0",
            {
                "candidate-algorithms": {"scope": "slot"},
                "shared-child": {"scope": "shared", "algorithm_parameters": {"threshold": 0.7}},
                "private-child": {},
            },
        ),
        last_test_time=date(2026, 8, 14),
        evaluation_func=None,
    )

    assert set(pipeline.dependency_pipelines) == {"shared-child", "private-child"}
    assert pipeline.dependency_pipelines["shared-child"].algorithm_version == "3.0.0"
    assert pipeline.dependency_pipelines["shared-child"].algorithm_definition["algorithm_parameters"] == {
        "base": True,
        "threshold": 0.7,
    }


def test_algorithm_pipeline_ignores_shared_dependency_version_offline(tmp_path, monkeypatch):
    monkeypatch.setattr(
        "hotvect.pyhotvect.read_algorithm_definition_from_jar",
        lambda **_: _definition("shared-child", "3.0.0"),
    )

    pipeline = AlgorithmPipeline(
        algorithm_pipeline_context=_context(tmp_path),
        algorithm_definition=_definition(
            "parent",
            "1.0.0",
            {"shared-child@2.0.0": {"scope": "shared"}},
        ),
        last_test_time=date(2026, 8, 14),
        evaluation_func=None,
    )

    assert pipeline.dependency_pipelines["shared-child"].algorithm_version == "3.0.0"


def test_algorithm_pipeline_ignores_list_dependency_version_offline(tmp_path, monkeypatch):
    monkeypatch.setattr(
        "hotvect.pyhotvect.read_algorithm_definition_from_jar",
        lambda **_: _definition("child", "3.0.0"),
    )

    pipeline = AlgorithmPipeline(
        algorithm_pipeline_context=_context(tmp_path),
        algorithm_definition=_definition("parent", "1.0.0", ["child@2.0.0"]),
        last_test_time=date(2026, 8, 14),
        evaluation_func=None,
    )

    assert set(pipeline.dependency_pipelines) == {"child"}
    assert pipeline.dependency_pipelines["child"].algorithm_version == "3.0.0"


@pytest.mark.parametrize(
    "dependencies",
    [
        {"foo@1": {"scope": "shared"}, "foo": {}},
        {"foo": {}, "foo@1": {"scope": "shared"}},
    ],
)
def test_algorithm_pipeline_rejects_colliding_logical_dependency_names(tmp_path, dependencies):
    with pytest.raises(
        ValueError,
        match=r"Dependencies (foo@1 and foo|foo and foo@1) resolve to the same logical dependency name foo",
    ):
        AlgorithmPipeline(
            algorithm_pipeline_context=_context(tmp_path),
            algorithm_definition=_definition("parent", "1.0.0", dependencies),
            last_test_time=date(2026, 8, 14),
            evaluation_func=None,
        )


def test_algorithm_pipeline_rejects_duplicate_list_dependency_names(tmp_path):
    with pytest.raises(ValueError, match=r"Dependency foo is declared more than once"):
        AlgorithmPipeline(
            algorithm_pipeline_context=_context(tmp_path),
            algorithm_definition=_definition("parent", "1.0.0", ["foo", "foo"]),
            last_test_time=date(2026, 8, 14),
            evaluation_func=None,
        )


@pytest.mark.parametrize(
    ("dependency", "declaration", "message"),
    [
        ("candidate@2", {"scope": "slot"}, "must not declare an algorithm version"),
        ("candidate_slot", {"scope": "slot"}, "must match ^[a-z0-9-]+$"),
        ("private", {"scope": "private"}, "must not declare scope: private"),
    ],
)
def test_algorithm_pipeline_rejects_invalid_scoped_dependency_declarations(tmp_path, dependency, declaration, message):
    with pytest.raises(ValueError, match=re.escape(message)):
        AlgorithmPipeline(
            algorithm_pipeline_context=_context(tmp_path),
            algorithm_definition=_definition("parent", "1.0.0", {dependency: declaration}),
            last_test_time=date(2026, 8, 14),
            evaluation_func=None,
        )


@pytest.mark.parametrize("dependencies", [False, 0, ""])
def test_algorithm_pipeline_rejects_falsy_non_collection_dependencies(tmp_path, dependencies):
    with pytest.raises(
        ValueError, match=f"dependencies must be an array or object but found {type(dependencies).__name__}"
    ):
        AlgorithmPipeline(
            algorithm_pipeline_context=_context(tmp_path),
            algorithm_definition=_definition("parent", "1.0.0", dependencies),
            last_test_time=date(2026, 8, 14),
            evaluation_func=None,
        )


@pytest.mark.parametrize(
    ("dependencies", "message"),
    [
        ([False], "Dependency entries must be strings"),
        ({1: {}}, "Dependency references must be strings"),
        ({"child": False}, "Dependency declaration for child must be a JSON object"),
    ],
)
def test_algorithm_pipeline_rejects_invalid_dependency_entries(tmp_path, dependencies, message):
    with pytest.raises(ValueError, match=message):
        AlgorithmPipeline(
            algorithm_pipeline_context=_context(tmp_path),
            algorithm_definition=_definition("parent", "1.0.0", dependencies),
            last_test_time=date(2026, 8, 14),
            evaluation_func=None,
        )
