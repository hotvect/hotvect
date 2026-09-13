from datetime import date, timedelta
from pathlib import Path
from unittest.mock import ANY, MagicMock

import pytest

from hotvect.utils import read_json


def _make_pipeline(tmp_path: Path, *, algorithm_definition: dict, algorithm_name="algo", algorithm_version="55.7.0"):
    from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext

    pipeline = AlgorithmPipeline.__new__(AlgorithmPipeline)
    pipeline.algorithm_definition = algorithm_definition
    pipeline.algorithm_definition_override = None
    pipeline.algorithm_override_metadata = None
    pipeline.algorithm_name = algorithm_name
    pipeline.algorithm_version = algorithm_version
    pipeline.algorithm_is_state = bool(algorithm_definition.get("generator_factory_classname"))
    pipeline.hyper_parameter_version = ""
    pipeline.parameter_version = "last_test_date_2026-02-17"
    pipeline.run_target = "evaluate"
    pipeline.available_parameter_cache_path = None
    pipeline.algorithm_pipeline_context = AlgorithmPipelineContext(
        algorithm_jar_path=tmp_path / "algo.jar",
        data_base_path=tmp_path,
        metadata_base_path=tmp_path,
        output_base_path=tmp_path,
        jvm_options=None,
    )
    pipeline.dependency_pipelines = {}
    pipeline.encode_partition_dates = None
    return pipeline


def _write_partition_success_marker(partition_cache_dir: Path) -> None:
    (partition_cache_dir / "_SUCCESS").touch()


def _write_batched_encoded_partitions(mappings, schema_description_location, stage):
    assert stage == "encode-partitions"
    schema_path = Path(schema_description_location)
    schema_path.parent.mkdir(parents=True, exist_ok=True)
    schema_path.write_text("schema\n", encoding="utf-8")
    for mapping in mappings:
        encoded_path = Path(mapping["dest"])
        encoded_path.mkdir(parents=True)
        (encoded_path / "part-00000.tsv").write_text("encoded\n", encoding="utf-8")
    return {"source_dest_mappings": mappings}


def _make_partition_cache_pipeline(tmp_path: Path, *, name: str, definition: dict):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_name=name,
        algorithm_version=definition.get("algorithm_version", "1.0.0"),
        algorithm_definition={
            "algorithm_name": name,
            "algorithm_version": "1.0.0",
            "algorithm_factory_classname": f"example.{name.title()}Factory",
            **definition,
        },
    )
    pipeline.dependency_pipelines = {}
    return pipeline


def test_partition_cache_allows_unpinned_trainable_dependency(tmp_path: Path):
    parent = _make_partition_cache_pipeline(
        tmp_path,
        name="parent",
        definition={
            "training_command": ["train"],
            "number_of_training_days": 2,
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "encode": {"cache": "partition"},
            },
        },
    )
    child = _make_partition_cache_pipeline(
        tmp_path,
        name="child",
        definition={"training_command": ["train"], "number_of_training_days": 2},
    )
    parent.dependency_pipelines = {"child": child}

    assert parent._uses_encode_partition_cache() is True


def test_partition_cache_allows_pinned_trainable_dependency(tmp_path: Path):
    parent = _make_partition_cache_pipeline(
        tmp_path,
        name="parent",
        definition={
            "training_command": ["train"],
            "number_of_training_days": 2,
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "encode": {"cache": "partition"},
            },
        },
    )
    child = _make_partition_cache_pipeline(
        tmp_path,
        name="child",
        definition={
            "training_command": ["train"],
            "number_of_training_days": 2,
            "hotvect_execution_parameters": {"with_parameter": "s3://bucket/child-parameters.zip"},
        },
    )
    parent.dependency_pipelines = {"child": child}

    assert parent._uses_encode_partition_cache() is True


def test_partition_cache_does_not_prewarm_pinned_pipeline(tmp_path: Path):
    pipeline = _make_partition_cache_pipeline(
        tmp_path,
        name="pinned",
        definition={
            "training_command": ["train"],
            "number_of_training_days": 2,
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "encode": {"cache": "partition"},
                "with_parameter": "s3://bucket/pinned-parameters.zip",
            },
        },
    )

    assert pipeline._uses_encode_partition_cache() is False
    assert pipeline._should_encode_with_partition_cache() is False


def test_partition_cache_allows_dated_state_dependency(tmp_path: Path):
    parent = _make_partition_cache_pipeline(
        tmp_path,
        name="parent",
        definition={
            "training_command": ["train"],
            "number_of_training_days": 2,
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "encode": {"cache": "partition"},
            },
        },
    )
    state = _make_partition_cache_pipeline(
        tmp_path,
        name="state",
        definition={
            "generator_factory_classname": "example.StateGeneratorFactory",
            "source_data": {
                "events": {
                    "data_prefix": "events",
                    "number_of_days": 1,
                    "lag_days": 1,
                }
            },
        },
    )
    parent.dependency_pipelines = {"state": state}

    assert parent._uses_encode_partition_cache() is True


def test_encode_cache_data_dependencies_include_state_dependency_source_data(tmp_path: Path):
    parent = _make_partition_cache_pipeline(
        tmp_path,
        name="parent",
        definition={
            "training_command": ["train"],
            "number_of_training_days": 2,
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "encode": {"cache": "partition"},
            },
        },
    )
    parent.run_target = "encode-cache"
    parent.last_test_time = date(2000, 2, 17)
    state = _make_partition_cache_pipeline(
        tmp_path,
        name="state",
        definition={
            "generator_factory_classname": "example.StateGeneratorFactory",
            "source_data": {
                "events": {
                    "data_prefix": "events",
                    "number_of_days": 1,
                    "lag_days": 1,
                }
            },
        },
    )
    state.last_test_time = parent.last_test_time
    parent.dependency_pipelines = {"state": state}

    dependencies = parent.data_dependencies(target="encode-cache")

    assert [(dependency.algorithm_name, dependency.data_prefix) for dependency in dependencies] == [("state", "events")]


def test_generate_states_materializes_s3_cached_state_at_the_configured_output_path(tmp_path: Path, monkeypatch):
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_name="state",
        algorithm_definition={
            "generator_factory_classname": "example.StateGeneratorFactory",
            "state_output_filename": "state.bin",
            "hotvect_execution_parameters": {"cache_base_dir": "s3://bucket/cache"},
        },
    )
    cached_state = tmp_path / "cached-state.bin"
    cached_state.write_bytes(b"cached state")
    monkeypatch.setattr(
        pyhotvect_module,
        "as_locally_available_content",
        lambda *_: str(cached_state),
    )

    result = pipeline.generate_states()

    assert Path(pipeline.state_output_path()).read_bytes() == b"cached state"
    assert result["skipped"].startswith("Used cache at:")


def test_root_encode_cache_mode_inherits_to_dependency_pipeline(tmp_path: Path, monkeypatch):
    from hotvect import pyhotvect as pyhotvect_module
    from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext

    child_definition = {
        "algorithm_name": "child-algo",
        "algorithm_version": "1.0.0",
        "algorithm_factory_classname": "example.ChildAlgorithmFactory",
    }
    monkeypatch.setattr(
        pyhotvect_module,
        "read_algorithm_definition_from_jar",
        lambda **_: child_definition,
    )
    context = AlgorithmPipelineContext(
        algorithm_jar_path=tmp_path / "algo.jar",
        data_base_path=tmp_path,
        metadata_base_path=tmp_path,
        output_base_path=tmp_path,
    )

    pipeline = AlgorithmPipeline(
        algorithm_pipeline_context=context,
        algorithm_definition={
            "algorithm_name": "parent-algo",
            "algorithm_version": "1.0.0",
            "algorithm_factory_classname": "example.ParentAlgorithmFactory",
            "dependencies": ["child-algo"],
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "encode": {"cache": True},
            },
        },
        last_test_time=date(2026, 2, 17),
        evaluation_func=lambda _: {},
    )

    assert pipeline._uses_encode_partition_cache() is False
    assert pipeline.dependency_pipelines["child-algo"]._encode_cache_modes() == {"run"}


def test_encode_cache_partition_dates_by_algorithm_inherit_to_dependency_pipeline(tmp_path: Path, monkeypatch):
    from hotvect import pyhotvect as pyhotvect_module
    from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext

    child_definition = {
        "algorithm_name": "child-algo",
        "algorithm_version": "1.0.0",
        "algorithm_factory_classname": "example.ChildAlgorithmFactory",
    }
    monkeypatch.setattr(
        pyhotvect_module,
        "read_algorithm_definition_from_jar",
        lambda **_: child_definition,
    )
    context = AlgorithmPipelineContext(
        algorithm_jar_path=tmp_path / "algo.jar",
        data_base_path=tmp_path,
        metadata_base_path=tmp_path,
        output_base_path=tmp_path,
    )

    pipeline = AlgorithmPipeline(
        algorithm_pipeline_context=context,
        algorithm_definition={
            "algorithm_name": "parent-algo",
            "algorithm_version": "1.0.0",
            "algorithm_factory_classname": "example.ParentAlgorithmFactory",
            "dependencies": ["child-algo"],
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "cache": "partition",
            },
        },
        last_test_time=date(2000, 2, 17),
        evaluation_func=lambda _: {},
        run_target="encode-cache",
        encode_partition_dates_by_algorithm={
            "parent-algo": [date(2000, 2, 16)],
            "child-algo": [date(2000, 2, 16), date(2000, 2, 15)],
        },
    )

    assert pipeline.encode_partition_dates == [date(2000, 2, 16)]
    assert pipeline.dependency_pipelines["child-algo"].encode_partition_dates == [
        date(2000, 2, 16),
        date(2000, 2, 15),
    ]


def test_unassigned_encode_cache_ancestor_does_not_require_its_training_input(tmp_path: Path, monkeypatch):
    from hotvect import pyhotvect as pyhotvect_module
    from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext

    child_definition = {
        "algorithm_name": "child-algo",
        "algorithm_version": "1.0.0",
        "algorithm_factory_classname": "example.ChildAlgorithmFactory",
        "training_command": ["train"],
        "train_data_spec": {"data_prefix": "child-train"},
        "number_of_training_days": 1,
        "training_lag_days": 1,
    }
    monkeypatch.setattr(
        pyhotvect_module,
        "read_algorithm_definition_from_jar",
        lambda **_: child_definition,
    )
    pipeline = AlgorithmPipeline(
        algorithm_pipeline_context=AlgorithmPipelineContext(
            algorithm_jar_path=tmp_path / "algo.jar",
            data_base_path=tmp_path,
            metadata_base_path=tmp_path,
            output_base_path=tmp_path,
        ),
        algorithm_definition={
            "algorithm_name": "parent-algo",
            "algorithm_version": "1.0.0",
            "algorithm_factory_classname": "example.ParentAlgorithmFactory",
            "training_command": ["train"],
            "train_data_spec": {"data_prefix": "parent-train"},
            "number_of_training_days": 1,
            "training_lag_days": 1,
            "dependencies": ["child-algo"],
        },
        last_test_time=date(2000, 2, 17),
        evaluation_func=lambda _: {},
        run_target="encode-cache",
        encode_partition_dates_by_algorithm={"child-algo": [date(2000, 2, 16)]},
    )

    dependencies = pipeline.data_dependencies(target="encode-cache")

    assert [(dependency.algorithm_name, dependency.data_prefix) for dependency in dependencies] == [
        ("child-algo", "child-train")
    ]


@pytest.mark.parametrize(
    ("assignments", "expected_dependencies"),
    [
        ({}, []),
        (
            {"grandchild-algo": [date(2000, 2, 16)]},
            [("grandchild-algo", "grandchild-train")],
        ),
    ],
)
def test_encode_cache_assignment_propagates_through_nested_dependencies(
    tmp_path: Path,
    monkeypatch,
    assignments: dict,
    expected_dependencies: list[tuple[str, str]],
):
    from hotvect import pyhotvect as pyhotvect_module
    from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext

    definitions = {
        "child-algo": {
            "algorithm_name": "child-algo",
            "algorithm_version": "1.0.0",
            "algorithm_factory_classname": "example.ChildAlgorithmFactory",
            "training_command": ["train"],
            "train_data_spec": {"data_prefix": "child-train"},
            "number_of_training_days": 1,
            "training_lag_days": 1,
            "dependencies": ["grandchild-algo"],
        },
        "grandchild-algo": {
            "algorithm_name": "grandchild-algo",
            "algorithm_version": "1.0.0",
            "algorithm_factory_classname": "example.GrandchildAlgorithmFactory",
            "training_command": ["train"],
            "train_data_spec": {"data_prefix": "grandchild-train"},
            "number_of_training_days": 1,
            "training_lag_days": 1,
        },
    }
    monkeypatch.setattr(
        pyhotvect_module,
        "read_algorithm_definition_from_jar",
        lambda algorithm_name, **_: definitions[algorithm_name],
    )
    pipeline = AlgorithmPipeline(
        algorithm_pipeline_context=AlgorithmPipelineContext(
            algorithm_jar_path=tmp_path / "algo.jar",
            data_base_path=tmp_path,
            metadata_base_path=tmp_path,
            output_base_path=tmp_path,
        ),
        algorithm_definition={
            "algorithm_name": "parent-algo",
            "algorithm_version": "1.0.0",
            "algorithm_factory_classname": "example.ParentAlgorithmFactory",
            "training_command": ["train"],
            "train_data_spec": {"data_prefix": "parent-train"},
            "number_of_training_days": 1,
            "training_lag_days": 1,
            "dependencies": ["child-algo"],
        },
        last_test_time=date(2026, 2, 17),
        evaluation_func=lambda _: {},
        run_target="encode-cache",
        encode_partition_dates_by_algorithm=assignments,
    )

    dependencies = pipeline.data_dependencies(target="encode-cache")

    assert [(dependency.algorithm_name, dependency.data_prefix) for dependency in dependencies] == expected_dependencies


def test_dependency_encode_cache_mode_overrides_root_inherited_mode(tmp_path: Path, monkeypatch):
    from hotvect import pyhotvect as pyhotvect_module
    from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext

    child_definition = {
        "algorithm_name": "child-algo",
        "algorithm_version": "1.0.0",
        "algorithm_factory_classname": "example.ChildAlgorithmFactory",
    }
    monkeypatch.setattr(
        pyhotvect_module,
        "read_algorithm_definition_from_jar",
        lambda **_: child_definition,
    )
    context = AlgorithmPipelineContext(
        algorithm_jar_path=tmp_path / "algo.jar",
        data_base_path=tmp_path,
        metadata_base_path=tmp_path,
        output_base_path=tmp_path,
    )

    pipeline = AlgorithmPipeline(
        algorithm_pipeline_context=context,
        algorithm_definition={
            "algorithm_name": "parent-algo",
            "algorithm_version": "1.0.0",
            "algorithm_factory_classname": "example.ParentAlgorithmFactory",
            "dependencies": {
                "child-algo": {
                    "hotvect_execution_parameters": {
                        "encode": {"cache": "run"},
                    }
                }
            },
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "encode": {"cache": True},
            },
        },
        last_test_time=date(2026, 2, 17),
        evaluation_func=lambda _: {},
    )

    assert pipeline._uses_encode_partition_cache() is False
    assert pipeline.dependency_pipelines["child-algo"]._encode_cache_modes() == {"run"}


@pytest.mark.parametrize(
    ("cache_scope", "expected_key"),
    [
        ("major", "algo@55"),
        ("minor", "algo@55.7"),
        ("patch", "algo@55.7.0"),
        ("hyperparam", "algo@55.7.0"),
    ],
)
def test_cache_scope_affects_algorithm_cache_key(tmp_path: Path, cache_scope: str, expected_key: str):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={"hotvect_execution_parameters": {"cache_scope": cache_scope}},
        algorithm_name="algo",
        algorithm_version="55.7.0",
    )
    assert pipeline._cache_algorithm_key() == expected_key


def test_cache_scope_minor_accepts_semver_suffix(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={"hotvect_execution_parameters": {"cache_scope": "minor"}},
        algorithm_name="algo",
        algorithm_version="55.7.0-SNAPSHOT",
    )

    assert pipeline._cache_algorithm_key() == "algo@55.7"


def test_cache_scope_minor_rejects_embedded_semver(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={"hotvect_execution_parameters": {"cache_scope": "minor"}},
        algorithm_name="algo",
        algorithm_version="release-55.7.0",
    )

    with pytest.raises(ValueError, match="requires a semver-like"):
        pipeline._cache_algorithm_key()


def test_cache_scope_hyperparam_appends_hyperparameter_version(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={"hotvect_execution_parameters": {"cache_scope": "hyperparam"}},
        algorithm_name="algo",
        algorithm_version="55.7.0",
    )
    pipeline.hyper_parameter_version = "ordered"
    assert pipeline._cache_algorithm_key() == "algo@55.7.0-ordered"


def test_resolve_cache_path_uses_cache_algorithm_key_and_parameter_version(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "cache_base_dir": "/tmp/hotvect-cache",
                "cache_scope": "minor",
            }
        },
        algorithm_name="algo",
        algorithm_version="55.7.9",
    )
    pipeline.parameter_version = "last_test_date_2026-02-17"
    cache_path = pipeline._resolve_cache_path(["encode"], "encoded.bin")
    assert cache_path == "/tmp/hotvect-cache/algo@55.7/runs/last_test_date_2026-02-17/encode/encoded.bin"


def test_encode_partition_cache_path_uses_algorithm_scoped_partition_namespace(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "cache_base_dir": "/tmp/hotvect-cache",
                "cache_scope": "minor",
                "encode": {"cache": "partition"},
            }
        },
        algorithm_name="algo",
        algorithm_version="55.7.9",
    )

    cache_path = pipeline._resolve_encode_partition_cache_path(date(2026, 2, 16))

    assert cache_path == "/tmp/hotvect-cache/algo@55.7/partitions/encode/dt=2026-02-16"
    assert pipeline._resolve_cache_path(["encode"], "encoded.bin") is None


def test_encode_cache_true_enables_run_mode(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "train_data_spec": {"data_prefix": "train"},
            "number_of_training_days": 2,
            "training_command": "echo train",
            "hotvect_execution_parameters": {
                "cache_base_dir": "/tmp/hotvect-cache",
                "cache_scope": "minor",
                "encode": {"cache": True},
            },
        },
        algorithm_name="algo",
        algorithm_version="55.7.9",
    )
    pipeline.parameter_version = "last_test_date_2026-02-17"

    assert pipeline._resolve_cache_path(["encode"], "encoded.bin") == (
        "/tmp/hotvect-cache/algo@55.7/runs/last_test_date_2026-02-17/encode/encoded.bin"
    )
    assert pipeline._resolve_encode_partition_cache_path(date(2000, 2, 16)) is None
    assert pipeline._uses_encode_partition_cache() is False


def test_cache_base_dir_enables_run_mode_by_default(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "train_data_spec": {"data_prefix": "train"},
            "number_of_training_days": 2,
            "training_command": "echo train",
            "hotvect_execution_parameters": {
                "cache_base_dir": "/tmp/hotvect-cache",
                "cache_scope": "minor",
            },
        },
        algorithm_name="algo",
        algorithm_version="55.7.9",
    )
    pipeline.parameter_version = "last_test_date_2026-02-17"

    assert pipeline._encode_cache_modes() == {"run"}
    assert pipeline._resolve_cache_path(["encode"], "encoded.bin") == (
        "/tmp/hotvect-cache/algo@55.7/runs/last_test_date_2026-02-17/encode/encoded.bin"
    )
    assert pipeline._resolve_encode_partition_cache_path(date(2000, 2, 16)) is None
    assert pipeline._uses_encode_partition_cache() is False


def test_root_cache_false_disables_default_caches_even_with_cache_base_dir(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "train_data_spec": {"data_prefix": "train"},
            "number_of_training_days": 2,
            "training_command": "echo train",
            "hotvect_execution_parameters": {
                "cache_base_dir": "/tmp/hotvect-cache",
                "cache": False,
            },
        },
    )
    assert pipeline._encode_cache_modes() == set()
    assert pipeline._resolve_cache_path(["encode"], "encoded.bin") is None
    assert pipeline._resolve_cache_path(["generate-state"], "state") is None
    assert pipeline._resolve_cache_path(["train"], "predict-parameters.zip") is None
    assert pipeline._resolve_encode_partition_cache_path(date(2026, 2, 16)) is None


def test_root_cache_run_enables_run_encode_mode_only(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "train_data_spec": {"data_prefix": "train"},
            "number_of_training_days": 2,
            "training_command": "echo train",
            "hotvect_execution_parameters": {
                "cache_base_dir": "/tmp/hotvect-cache",
                "cache": "run",
            },
        },
    )

    assert pipeline._encode_cache_modes() == {"run"}
    assert pipeline._resolve_cache_path(["encode"], "encoded.bin") == (
        "/tmp/hotvect-cache/algo@55.7.0/runs/last_test_date_2026-02-17/encode/encoded.bin"
    )
    assert pipeline._resolve_cache_path(["train"], "predict-parameters.zip") == (
        "/tmp/hotvect-cache/algo@55.7.0/runs/last_test_date_2026-02-17/train/predict-parameters.zip"
    )
    assert pipeline._resolve_encode_partition_cache_path(date(2026, 2, 16)) is None


def test_root_cache_partition_enables_only_encode_partition_mode(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "train_data_spec": {"data_prefix": "train"},
            "number_of_training_days": 2,
            "training_command": "echo train",
            "hotvect_execution_parameters": {
                "cache_base_dir": "/tmp/hotvect-cache",
                "cache": "partition",
            },
        },
    )

    assert pipeline._encode_cache_modes() == {"partition"}
    assert pipeline._resolve_cache_path(["encode"], "encoded.bin") is None
    assert pipeline._resolve_cache_path(["train"], "predict-parameters.zip") is None
    assert pipeline._resolve_encode_partition_cache_path(date(2026, 2, 16)) == (
        "/tmp/hotvect-cache/algo@55.7.0/partitions/encode/dt=2026-02-16"
    )


def test_stage_cache_setting_overrides_root_cache_mode(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "train_data_spec": {"data_prefix": "train"},
            "number_of_training_days": 2,
            "training_command": "echo train",
            "hotvect_execution_parameters": {
                "cache_base_dir": "/tmp/hotvect-cache",
                "cache": "partition",
                "encode": {"cache": "run"},
                "train": {"cache": True},
            },
        },
    )

    assert pipeline._encode_cache_modes() == {"run"}
    assert pipeline._resolve_cache_path(["encode"], "encoded.bin") == (
        "/tmp/hotvect-cache/algo@55.7.0/runs/last_test_date_2026-02-17/encode/encoded.bin"
    )
    assert pipeline._resolve_cache_path(["train"], "predict-parameters.zip") == (
        "/tmp/hotvect-cache/algo@55.7.0/runs/last_test_date_2026-02-17/train/predict-parameters.zip"
    )
    assert pipeline._resolve_encode_partition_cache_path(date(2026, 2, 16)) is None


def test_stage_cache_setting_can_enable_cache_when_root_cache_is_false(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "train_data_spec": {"data_prefix": "train"},
            "number_of_training_days": 2,
            "training_command": "echo train",
            "hotvect_execution_parameters": {
                "cache_base_dir": "/tmp/hotvect-cache",
                "cache": False,
                "encode": {"cache": "partition"},
                "train": {"cache": True},
            },
        },
    )

    assert pipeline._encode_cache_modes() == {"partition"}
    assert pipeline._resolve_cache_path(["encode"], "encoded.bin") is None
    assert pipeline._resolve_cache_path(["train"], "predict-parameters.zip") == (
        "/tmp/hotvect-cache/algo@55.7.0/runs/last_test_date_2026-02-17/train/predict-parameters.zip"
    )
    assert pipeline._resolve_encode_partition_cache_path(date(2026, 2, 16)) == (
        "/tmp/hotvect-cache/algo@55.7.0/partitions/encode/dt=2026-02-16"
    )


def test_root_cache_setting_is_inherited_by_dependency_pipeline(tmp_path: Path, monkeypatch):
    from hotvect import pyhotvect as pyhotvect_module
    from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext

    child_definition = {
        "algorithm_name": "child-algo",
        "algorithm_version": "1.0.0",
        "algorithm_factory_classname": "example.ChildAlgorithmFactory",
    }
    monkeypatch.setattr(
        pyhotvect_module,
        "read_algorithm_definition_from_jar",
        lambda **_: child_definition,
    )
    context = AlgorithmPipelineContext(
        algorithm_jar_path=tmp_path / "algo.jar",
        data_base_path=tmp_path,
        metadata_base_path=tmp_path,
        output_base_path=tmp_path,
    )

    pipeline = AlgorithmPipeline(
        algorithm_pipeline_context=context,
        algorithm_definition={
            "algorithm_name": "parent-algo",
            "algorithm_version": "1.0.0",
            "algorithm_factory_classname": "example.ParentAlgorithmFactory",
            "dependencies": ["child-algo"],
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "cache": "run",
            },
        },
        last_test_time=date(2026, 2, 17),
        evaluation_func=lambda _: {},
    )

    assert pipeline.dependency_pipelines["child-algo"]._encode_cache_modes() == {"run"}


def test_invalid_root_cache_value_is_rejected(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "cache_base_dir": "/tmp/hotvect-cache",
                "cache": "s3://bucket/cache",
            }
        },
    )

    with pytest.raises(ValueError, match="Invalid cache value"):
        pipeline._resolve_cache_path(["train"], "predict-parameters.zip")


def test_invalid_stage_cache_value_is_rejected(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "cache_base_dir": "/tmp/hotvect-cache",
                "train": {"cache": ["invalid"]},
            }
        },
    )

    with pytest.raises(ValueError, match=r"Invalid train\.cache value"):
        pipeline._resolve_cache_path(["train"], "predict-parameters.zip")


def test_root_cache_true_without_cache_base_dir_is_rejected(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "cache": True,
            }
        },
    )

    with pytest.raises(ValueError, match="cache_base_dir"):
        pipeline._resolve_cache_path(["train"], "predict-parameters.zip")


def test_encode_partition_cache_without_cache_base_dir_is_rejected(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "encode": {"cache": "partition"},
            }
        },
    )

    with pytest.raises(ValueError, match="cache_base_dir"):
        pipeline._resolve_encode_partition_cache_path(date(2026, 2, 16))


@pytest.mark.parametrize("root_cache", [None, True])
def test_default_or_true_root_cache_uses_run_encode_path(tmp_path: Path, monkeypatch, root_cache):
    from hotvect import pyhotvect as pyhotvect_module

    execution_parameters = {"cache_base_dir": str(tmp_path / "cache")}
    if root_cache is not None:
        execution_parameters["cache"] = root_cache

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "training_command": "echo train",
            "hotvect_execution_parameters": execution_parameters,
        },
    )
    monkeypatch.setattr(pyhotvect_module, "as_locally_available_content", MagicMock(return_value=None))
    store_file = MagicMock()
    monkeypatch.setattr(pyhotvect_module, "store_file", store_file)

    pipeline._do_encode = MagicMock(return_value={})
    pipeline._encode_with_partition_cache = MagicMock(side_effect=AssertionError("partition cache should not run"))
    pipeline.encoded_data_file_path = MagicMock(return_value=str(tmp_path / "encoded"))
    pipeline.encoded_schema_description_file_path = MagicMock(return_value=str(tmp_path / "schema"))
    pipeline.cache_path = MagicMock(return_value=str(tmp_path / "local-cache"))

    pipeline.encode()

    assert pipeline._encode_cache_modes() == {"run"}
    assert pipeline._uses_encode_partition_cache() is False
    pipeline._do_encode.assert_called_once_with(is_test=False)
    pipeline._encode_with_partition_cache.assert_not_called()
    assert store_file.call_count == 2


@pytest.mark.parametrize(
    "execution_parameters",
    [
        {"cache_base_dir": "/tmp/hotvect-cache", "cache": "partition"},
        {"cache_base_dir": "/tmp/hotvect-cache", "encode": {"cache": "partition"}},
    ],
)
def test_explicit_partition_cache_without_partition_training_config_is_rejected(tmp_path: Path, execution_parameters):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "training_command": "echo train",
            "hotvect_execution_parameters": execution_parameters,
        },
    )

    with pytest.raises(ValueError, match="number_of_training_days"):
        pipeline._should_encode_with_partition_cache()


def test_encode_cache_run_enables_only_run_cache(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "cache_base_dir": "/tmp/hotvect-cache",
                "encode": {"cache": "run"},
            }
        },
    )

    assert pipeline._resolve_cache_path(["encode"], "encoded.bin") == (
        "/tmp/hotvect-cache/algo@55.7.0/runs/last_test_date_2026-02-17/encode/encoded.bin"
    )
    assert pipeline._resolve_encode_partition_cache_path(date(2026, 2, 16)) is None


def test_encode_cache_rejects_list_modes(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "cache_base_dir": "/tmp/hotvect-cache",
                "encode": {"cache": ["run", "partition"]},
            }
        },
    )

    with pytest.raises(ValueError, match="Invalid encode.cache"):
        pipeline._encode_cache_modes()


def test_explicit_encode_cache_path_is_not_partition_cache(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "cache_base_dir": "/tmp/hotvect-cache",
                "encode": {"cache": "/tmp/encode-cache"},
            }
        },
    )

    assert pipeline._resolve_encode_partition_cache_path(date(2026, 2, 16)) is None
    assert pipeline._resolve_cache_path(["encode"], "encoded.bin") == "/tmp/encode-cache/encoded.bin"


def test_encode_ignores_cache_reads_when_cache_refresh_enabled(tmp_path: Path, monkeypatch):
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "cache_scope": "hyperparam",
                "cache": "run",
                "cache_refresh": True,
            }
        },
        algorithm_name="algo",
        algorithm_version="55.7.0",
    )

    def fail_if_called(*args, **kwargs):
        raise AssertionError("as_locally_available_content should not be called when cache_refresh is enabled")

    monkeypatch.setattr(pyhotvect_module, "as_locally_available_content", fail_if_called)

    store_calls = []

    def record_store(src: str, dest: str):
        store_calls.append((src, dest))

    monkeypatch.setattr(pyhotvect_module, "store_file", record_store)

    pipeline._do_encode = MagicMock(return_value={})

    # Provide minimal paths used by encode()
    pipeline.encoded_data_file_path = MagicMock(return_value=str(tmp_path / "encoded.bin"))
    pipeline.encoded_schema_description_file_path = MagicMock(return_value=str(tmp_path / "schema.json"))
    pipeline.cache_path = MagicMock(return_value=str(tmp_path / "local-cache"))

    pipeline.encode()

    assert pipeline._do_encode.call_count == 1
    assert len(store_calls) == 2


def test_available_predict_parameter_cache_path_returns_none_when_cache_refresh_enabled(tmp_path: Path, monkeypatch):
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "cache": "run",
                "cache_refresh": True,
            }
        },
    )

    as_local = MagicMock(return_value=str(tmp_path / "predict-parameters.zip"))
    monkeypatch.setattr(pyhotvect_module, "as_locally_available_content", as_local)

    result = pipeline.available_predict_parameter_cache_path()

    assert result is None
    as_local.assert_not_called()


def test_available_predict_parameter_cache_path_still_requires_with_parameter(tmp_path: Path, monkeypatch):
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "cache": "run",
                "cache_refresh": True,
                "with_parameter": "s3://bucket/prefix/params.zip",
            }
        },
    )

    as_local = MagicMock(return_value=None)
    monkeypatch.setattr(pyhotvect_module, "as_locally_available_content", as_local)

    with pytest.raises(ValueError, match="with_parameter"):
        pipeline.available_predict_parameter_cache_path()

    assert as_local.call_count == 1


@pytest.mark.parametrize("cache_override", [None, True, "partition"])
def test_cache_refresh_requires_root_run_cache_mode(tmp_path: Path, cache_override):
    hotvect_execution_parameters = {
        "cache_base_dir": str(tmp_path / "cache"),
        "cache_refresh": True,
    }
    if cache_override is not None:
        hotvect_execution_parameters["cache"] = cache_override

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={"hotvect_execution_parameters": hotvect_execution_parameters},
    )

    with pytest.raises(ValueError, match="cache_refresh requires effective cache mode 'run'"):
        pipeline._cache_refresh_enabled()


def test_cache_refresh_requires_effective_cache_base_dir(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "cache": "run",
                "cache_refresh": True,
            }
        },
    )

    with pytest.raises(ValueError, match="cache_refresh requires effective cache_base_dir"):
        pipeline._cache_refresh_enabled()


def test_cache_refresh_rejects_partition_encode_mode(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "cache": "run",
                "cache_refresh": True,
                "encode": {"cache": "partition"},
            }
        },
    )

    with pytest.raises(ValueError, match="Partition cache refresh is not supported"):
        pipeline._cache_refresh_enabled()


def test_cache_refresh_value_must_be_boolean(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "cache": "run",
                "cache_refresh": "false",
            }
        },
    )

    with pytest.raises(ValueError, match="Invalid cache_refresh value"):
        pipeline._cache_refresh_enabled()


@pytest.mark.parametrize("scope", ["major", "minor"])
def test_cache_scope_major_minor_requires_semver_like_algorithm_version(tmp_path: Path, scope: str):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={"hotvect_execution_parameters": {"cache_scope": scope}},
        algorithm_version="v82",
    )
    with pytest.raises(ValueError, match="requires a semver-like"):
        pipeline._cache_algorithm_key()


def test_cache_scope_patch_allows_non_semver_algorithm_version(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={"hotvect_execution_parameters": {"cache_scope": "patch"}},
        algorithm_version="v82",
    )
    assert pipeline._cache_algorithm_key() == "algo@v82"


def test_per_task_cache_can_be_disabled_even_if_cache_base_dir_is_set(tmp_path: Path, monkeypatch):
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "encode": {"cache": False},
            }
        },
    )

    store_file = MagicMock()
    monkeypatch.setattr(pyhotvect_module, "store_file", store_file)

    pipeline._do_encode = MagicMock(return_value={})
    pipeline.encoded_data_file_path = MagicMock(return_value=str(tmp_path / "encoded.bin"))
    pipeline.encoded_schema_description_file_path = MagicMock(return_value=str(tmp_path / "schema.json"))
    pipeline.cache_path = MagicMock(return_value=str(tmp_path / "local-cache"))

    pipeline.encode()

    assert pipeline._resolve_cache_path(["encode"], "encoded.bin") is None
    assert store_file.call_count == 0


def test_encode_partition_cache_reuses_cached_date_partitions_without_writing_whole_run_cache(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "train_data_spec": {"data_prefix": "train"},
            "number_of_training_days": 2,
            "training_lag_days": 1,
            "training_command": "echo train",
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "encode": {"cache": "partition"},
            },
        },
    )
    pipeline.last_test_time = date(2000, 2, 17)
    pipeline.training_lag_days = 1
    pipeline.training_lag = timedelta(days=1)

    train_root = tmp_path / "train"
    expected_dates = [date(2000, 2, 15), date(2000, 2, 16)]
    for partition_date in expected_dates:
        source_dir = train_root / f"dt={partition_date.isoformat()}"
        source_dir.mkdir(parents=True)
        (source_dir / "part-00000.jsonl").write_text(f"{partition_date}\n", encoding="utf-8")

        partition_cache_dir = Path(pipeline._resolve_encode_partition_cache_path(partition_date))
        encoded_dir = partition_cache_dir / "encoded"
        encoded_dir.mkdir(parents=True)
        (encoded_dir / "part-00000.tsv").write_text(f"encoded-{partition_date}\n", encoding="utf-8")
        schema_path = partition_cache_dir / "encoded-schema-description"
        schema_path.write_text("schema\n", encoding="utf-8")
        _write_partition_success_marker(partition_cache_dir)

    pipeline._do_encode_source_dest_mappings = MagicMock(side_effect=AssertionError("all partitions should hit cache"))

    metadata = pipeline.encode()

    assert metadata["partition_cache"] is True
    assert metadata["partition_cache_hits"] == 2
    assert metadata["partition_cache_misses"] == 0
    assert sorted(path.name for path in Path(pipeline.encoded_data_file_path()).iterdir()) == [
        "dt=2000-02-15",
        "dt=2000-02-16",
    ]
    assert (Path(pipeline.encoded_data_file_path()) / "dt=2000-02-15" / "part-00000.tsv").read_text(
        encoding="utf-8"
    ) == "encoded-2000-02-15\n"
    assert (Path(pipeline.encoded_data_file_path()) / "dt=2000-02-16" / "part-00000.tsv").read_text(
        encoding="utf-8"
    ) == "encoded-2000-02-16\n"
    assert Path(pipeline.encoded_schema_description_file_path()).read_text(encoding="utf-8") == "schema\n"
    assert not (
        tmp_path / "cache" / "algo@55.7.0" / "runs" / "last_test_date_2000-02-17" / "encode" / "encoded"
    ).exists()


def test_encode_partition_cache_batches_misses_in_one_encode_process(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "train_data_spec": {"data_prefix": "train"},
            "number_of_training_days": 3,
            "training_command": "echo train",
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "encode": {"cache": "partition"},
            },
        },
    )
    dates = [date(2000, 2, 14), date(2000, 2, 15), date(2000, 2, 16)]
    hit_date = dates[1]
    miss_dates = [dates[0], dates[2]]
    pipeline.run_target = "encode-cache"
    pipeline.encode_partition_dates = dates

    for partition_date in dates:
        source_dir = tmp_path / "train" / f"dt={partition_date.isoformat()}"
        source_dir.mkdir(parents=True)
        (source_dir / "part-00000.jsonl").write_text(f"{partition_date}\n", encoding="utf-8")

    hit_cache_dir = Path(pipeline._resolve_encode_partition_cache_path(hit_date))
    (hit_cache_dir / "encoded").mkdir(parents=True)
    (hit_cache_dir / "encoded" / "part-00000.tsv").write_text("cached\n", encoding="utf-8")
    (hit_cache_dir / "encoded-schema-description").write_text("schema\n", encoding="utf-8")
    _write_partition_success_marker(hit_cache_dir)

    def encode_mappings(mappings, schema_description_location, stage):
        assert stage == "encode-partitions"
        assert len(mappings) == 2
        for partition_date in miss_dates:
            partition_cache_dir = Path(pipeline._resolve_encode_partition_cache_path(partition_date))
            assert not (partition_cache_dir / "_STARTED").exists()
        schema_path = Path(schema_description_location)
        schema_path.parent.mkdir(parents=True, exist_ok=True)
        schema_path.write_text("schema\n", encoding="utf-8")
        for mapping in mappings:
            encoded_dir = Path(mapping["dest"])
            encoded_dir.mkdir(parents=True)
            (encoded_dir / "part-00000.tsv").write_text("encoded\n", encoding="utf-8")
        return {"source_dest_mappings": mappings}

    pipeline._do_encode_source_dest_mappings = MagicMock(side_effect=encode_mappings)

    metadata = pipeline._encode_with_partition_cache()

    pipeline._do_encode_source_dest_mappings.assert_called_once()
    mappings = pipeline._do_encode_source_dest_mappings.call_args.kwargs["mappings"]
    assert [Path(mapping["dest"]).parent.name for mapping in mappings] == [
        "dt=2000-02-14",
        "dt=2000-02-16",
    ]
    assert metadata["partition_cache_hits"] == 1
    assert metadata["partition_cache_misses"] == 2
    assert metadata["partition_cache_blocked"] == 0
    for partition_date in miss_dates:
        partition_cache_dir = Path(pipeline._resolve_encode_partition_cache_path(partition_date))
        assert (partition_cache_dir / "_STARTED").is_file()
        assert (partition_cache_dir / "_SUCCESS").is_file()
        assert (partition_cache_dir / "encoded" / "part-00000.tsv").read_text(encoding="utf-8") == "encoded\n"


@pytest.mark.parametrize(
    ("failure_mode", "expected_error"),
    [
        ("missing_schema", "schema description"),
        ("missing_second_output", "non-empty encoded data"),
    ],
)
def test_encode_partition_cache_validates_complete_batch_before_publishing(
    tmp_path: Path,
    monkeypatch,
    failure_mode: str,
    expected_error: str,
):
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "train_data_spec": {"data_prefix": "train"},
            "training_command": "echo train",
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "encode": {"cache": "partition"},
            },
        },
    )
    dates = [date(2000, 2, 15), date(2000, 2, 16)]
    pipeline.run_target = "encode-cache"
    pipeline.encode_partition_dates = dates

    for partition_date in dates:
        source_dir = tmp_path / "train" / f"dt={partition_date.isoformat()}"
        source_dir.mkdir(parents=True)
        (source_dir / "part-00000.jsonl").write_text("source\n", encoding="utf-8")

    def write_incomplete_batch(mappings, schema_description_location, stage):
        assert stage == "encode-partitions"
        assert len(mappings) == 2
        if failure_mode != "missing_schema":
            schema_path = Path(schema_description_location)
            schema_path.parent.mkdir(parents=True, exist_ok=True)
            schema_path.write_text("schema\n", encoding="utf-8")
        for index, mapping in enumerate(mappings):
            encoded_dir = Path(mapping["dest"])
            encoded_dir.mkdir(parents=True)
            if failure_mode != "missing_second_output" or index == 0:
                (encoded_dir / "part-00000.tsv").write_text("encoded\n", encoding="utf-8")
        return {"source_dest_mappings": mappings}

    pipeline._do_encode_source_dest_mappings = MagicMock(side_effect=write_incomplete_batch)
    store_file = MagicMock()
    monkeypatch.setattr(pyhotvect_module, "store_file", store_file)

    with pytest.raises(FileNotFoundError, match=expected_error):
        pipeline._encode_with_partition_cache()

    pipeline._do_encode_source_dest_mappings.assert_called_once()
    store_file.assert_not_called()
    for partition_date in dates:
        partition_cache_dir = Path(pipeline._resolve_encode_partition_cache_path(partition_date))
        assert not (partition_cache_dir / "_STARTED").exists()


def test_encode_cache_target_marks_post_encode_claim_race_blocked(tmp_path: Path, monkeypatch):
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "train_data_spec": {"data_prefix": "train"},
            "training_command": "echo train",
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "encode": {"cache": "partition"},
            },
        },
    )
    partition_date = date(2000, 2, 16)
    pipeline.run_target = "encode-cache"
    pipeline.encode_partition_dates = [partition_date]
    source_dir = tmp_path / "train" / f"dt={partition_date.isoformat()}"
    source_dir.mkdir(parents=True)
    (source_dir / "part-00000.jsonl").write_text("source\n", encoding="utf-8")

    pipeline._do_encode_source_dest_mappings = MagicMock(side_effect=_write_batched_encoded_partitions)
    pipeline._claim_encode_partition_cache_write = MagicMock(return_value=False)
    store_file = MagicMock()
    monkeypatch.setattr(pyhotvect_module, "store_file", store_file)

    metadata = pipeline._encode_with_partition_cache()

    assert metadata["partition_cache_hits"] == 0
    assert metadata["partition_cache_misses"] == 0
    assert metadata["partition_cache_blocked"] == 1
    assert metadata["partitions"][0]["cache"] == "blocked"
    assert metadata["partitions"][0]["reason"] == "Another writer already claimed the partition cache"
    pipeline._do_encode_source_dest_mappings.assert_called_once()
    pipeline._claim_encode_partition_cache_write.assert_called_once()
    store_file.assert_not_called()


def test_encode_source_dest_mappings_writes_manifest_and_invokes_java(tmp_path: Path, monkeypatch):
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(tmp_path, algorithm_definition={})
    pipeline._base_command = MagicMock(return_value=["java", "Main", "encode"])
    pipeline.encode_parameter_file_path = MagicMock(return_value="parameters.zip")
    pipeline._stream_output_to_stage_log = MagicMock()
    monkeypatch.setattr(pyhotvect_module, "read_json", MagicMock(return_value={"encoded": 2}))
    mappings = [
        {"sources": ["data/day-1"], "dest": "encoded/day-1"},
        {"sources": ["data/day-2"], "dest": "encoded/day-2"},
    ]
    schema_path = tmp_path / "encoded-schema-description"

    metadata = pipeline._do_encode_source_dest_mappings(
        mappings=mappings,
        schema_description_location=str(schema_path),
        stage="encode-partitions",
    )

    metadata_dir = pipeline._stage_metadata_dir("encode-partitions")
    assert metadata == {"encoded": 2}
    assert read_json(Path(metadata_dir) / "source-dest-mappings.json") == mappings
    pipeline._stream_output_to_stage_log.assert_called_once_with(
        stage="encode-partitions",
        cmd=[
            "java",
            "Main",
            "encode",
            "--source-dest-mappings",
            str(Path(metadata_dir) / "source-dest-mappings.json"),
            "--dest-schema-description",
            str(schema_path),
            "--parameters",
            "parameters.zip",
        ],
    )


def test_encode_partition_cache_can_target_explicit_partition_dates(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "train_data_spec": {"data_prefix": "train"},
            "number_of_training_days": 2,
            "training_lag_days": 1,
            "training_command": "echo train",
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "encode": {"cache": "partition"},
            },
        },
    )
    pipeline.last_test_time = date(2000, 2, 17)
    pipeline.training_lag_days = 1
    pipeline.training_lag = timedelta(days=1)
    pipeline.encode_partition_dates = [date(2000, 2, 10)]

    source_dir = tmp_path / "train" / "dt=2000-02-10"
    source_dir.mkdir(parents=True)
    (source_dir / "part-00000.jsonl").write_text("source\n", encoding="utf-8")

    partition_cache_dir = Path(pipeline._resolve_encode_partition_cache_path(date(2000, 2, 10)))
    encoded_dir = partition_cache_dir / "encoded"
    encoded_dir.mkdir(parents=True)
    (encoded_dir / "part-00000.tsv").write_text("encoded\n", encoding="utf-8")
    (partition_cache_dir / "encoded-schema-description").write_text("schema\n", encoding="utf-8")
    _write_partition_success_marker(partition_cache_dir)

    pipeline._do_encode_source_dest_mappings = MagicMock(
        side_effect=AssertionError("explicit partition should hit cache")
    )

    metadata = pipeline.encode()

    assert metadata["partition_cache_hits"] == 1
    assert metadata["partitions"][0]["dt"] == "2000-02-10"
    assert sorted(path.name for path in Path(pipeline.encoded_data_file_path()).iterdir()) == ["dt=2000-02-10"]
    assert (Path(pipeline.encoded_data_file_path()) / "dt=2000-02-10" / "part-00000.tsv").read_text(
        encoding="utf-8"
    ) == "encoded\n"


def test_encode_partition_cache_skips_explicit_empty_prewarm_assignment(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "train_data_spec": {"data_prefix": "train"},
            "number_of_training_days": 2,
            "training_lag_days": 1,
            "training_command": "echo train",
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "encode": {"cache": "partition"},
            },
        },
    )
    pipeline.run_target = "encode-cache"
    pipeline.encode_partition_dates = []

    metadata = pipeline._encode_with_partition_cache()

    assert metadata == {
        "partition_cache": True,
        "partition_cache_hits": 0,
        "partition_cache_misses": 0,
        "partition_cache_blocked": 0,
        "partitions": [],
        "skipped": "No encode partition dates assigned",
    }


def test_encode_cache_target_does_not_assemble_cached_partitions(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "train_data_spec": {"data_prefix": "train"},
            "number_of_training_days": 1,
            "training_lag_days": 1,
            "training_command": "echo train",
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "encode": {"cache": "partition"},
            },
        },
    )
    pipeline.run_target = "encode-cache"
    pipeline.last_test_time = date(2000, 2, 17)
    pipeline.training_lag = timedelta(days=1)
    partition_date = date(2000, 2, 16)
    pipeline.encode_partition_dates = [partition_date]

    source_dir = tmp_path / "train" / f"dt={partition_date.isoformat()}"
    source_dir.mkdir(parents=True)
    partition_cache_dir = Path(pipeline._resolve_encode_partition_cache_path(partition_date))
    (partition_cache_dir / "encoded").mkdir(parents=True)
    (partition_cache_dir / "encoded" / "part-00000.tsv").write_text("encoded\n", encoding="utf-8")
    (partition_cache_dir / "encoded-schema-description").write_text("schema\n", encoding="utf-8")
    _write_partition_success_marker(partition_cache_dir)
    pipeline._build_encode_partition_view = MagicMock(side_effect=AssertionError("prewarm must not build a view"))

    metadata = pipeline._encode_with_partition_cache()

    assert metadata["partition_cache_hits"] == 1
    assert metadata["partition_cache_blocked"] == 0
    pipeline._build_encode_partition_view.assert_not_called()


def test_encode_cache_target_does_not_write_whole_run_encode_cache(tmp_path: Path, monkeypatch):
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "train_data_spec": {"data_prefix": "train"},
            "number_of_training_days": 1,
            "training_lag_days": 1,
            "training_command": "echo train",
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "encode": {"cache": "partition"},
            },
        },
    )
    pipeline.run_target = "encode-cache"
    pipeline.last_test_time = date(2000, 2, 17)
    pipeline.training_lag_days = 1
    pipeline.training_lag = timedelta(days=1)
    pipeline.encode_partition_dates = [date(2000, 2, 16)]

    partition_date = date(2000, 2, 16)
    source_dir = tmp_path / "train" / f"dt={partition_date.isoformat()}"
    source_dir.mkdir(parents=True)
    (source_dir / "part-00000.jsonl").write_text("source\n", encoding="utf-8")

    partition_cache_dir = Path(pipeline._resolve_encode_partition_cache_path(partition_date))
    encoded_dir = partition_cache_dir / "encoded"
    encoded_dir.mkdir(parents=True)
    (encoded_dir / "part-00000.tsv").write_text("encoded\n", encoding="utf-8")
    (partition_cache_dir / "encoded-schema-description").write_text("schema\n", encoding="utf-8")
    _write_partition_success_marker(partition_cache_dir)

    store_file = MagicMock()
    monkeypatch.setattr(pyhotvect_module, "store_file", store_file)
    pipeline._do_encode_source_dest_mappings = MagicMock(side_effect=AssertionError("partition should hit cache"))

    metadata = pipeline.encode()

    assert metadata["partition_cache_hits"] == 1
    assert store_file.call_count == 0


def test_encode_partition_cache_uses_mounted_partition_when_success_marker_exists(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "train_data_spec": {"data_prefix": "train"},
            "number_of_training_days": 1,
            "training_lag_days": 1,
            "training_command": "echo train",
            "hotvect_execution_parameters": {
                "cache_base_dir": "s3://bucket/cache",
                "encode": {"cache": "partition"},
            },
        },
    )
    pipeline.algorithm_pipeline_context = pipeline.algorithm_pipeline_context._replace(
        partition_cache_base_paths={"s3://bucket/cache/algo@55.7.0": tmp_path / "mounted-cache"}
    )

    partition_date = date(2000, 2, 16)
    mounted_partition = tmp_path / "mounted-cache" / f"dt={partition_date.isoformat()}"
    (mounted_partition / "encoded").mkdir(parents=True)
    (mounted_partition / "encoded-schema-description").write_text("schema-from-mount\n", encoding="utf-8")
    _write_partition_success_marker(mounted_partition)

    pipeline._load_s3_encode_partition_cache = MagicMock(
        side_effect=AssertionError("existing mounted success marker must be authoritative")
    )
    pipeline._do_encode_source_dest_mappings = MagicMock(
        side_effect=AssertionError("partition should hit mounted cache")
    )

    result = pipeline._load_or_create_encode_partition(
        partition_date=partition_date,
        source_paths=[str(tmp_path / "source")],
    )

    assert result["cache"] == "hit"
    assert result["encoded_path"] == str(mounted_partition / "encoded")
    assert result["schema_path"] == str(mounted_partition / "encoded-schema-description")


def test_encode_partition_cache_falls_back_to_s3_when_mounted_content_is_not_materialized(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "train_data_spec": {"data_prefix": "train"},
            "number_of_training_days": 1,
            "training_lag_days": 1,
            "training_command": "echo train",
            "hotvect_execution_parameters": {
                "cache_base_dir": "s3://bucket/cache",
                "encode": {"cache": "partition"},
            },
        },
    )
    pipeline.algorithm_pipeline_context = pipeline.algorithm_pipeline_context._replace(
        partition_cache_base_paths={"s3://bucket/cache/algo@55.7.0": tmp_path / "mounted-cache"}
    )

    partition_date = date(2000, 2, 16)
    mounted_partition = tmp_path / "mounted-cache" / f"dt={partition_date.isoformat()}"
    mounted_partition.mkdir(parents=True)
    _write_partition_success_marker(mounted_partition)
    s3_result = {
        "dt": partition_date.isoformat(),
        "cache": "hit",
        "encoded_path": str(tmp_path / "downloaded-encoded"),
        "schema_path": str(tmp_path / "downloaded-schema"),
        "source_paths": [str(tmp_path / "source")],
    }
    pipeline._load_s3_encode_partition_cache = MagicMock(return_value=(s3_result, False))
    pipeline._do_encode_source_dest_mappings = MagicMock(side_effect=AssertionError("partition should hit S3 cache"))

    result = pipeline._load_or_create_encode_partition(
        partition_date=partition_date,
        source_paths=[str(tmp_path / "source")],
    )

    assert result == s3_result
    pipeline._load_s3_encode_partition_cache.assert_called_once()


def test_encode_partition_cache_uses_s3_partition_cache_when_mounted_cache_is_incomplete(tmp_path: Path, monkeypatch):
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "train_data_spec": {"data_prefix": "train"},
            "number_of_training_days": 1,
            "training_lag_days": 1,
            "training_command": "echo train",
            "hotvect_execution_parameters": {
                "cache_base_dir": "s3://bucket/cache",
                "encode": {"cache": "partition"},
            },
        },
    )
    pipeline.algorithm_pipeline_context = pipeline.algorithm_pipeline_context._replace(
        partition_cache_base_paths={"s3://bucket/cache/algo@55.7.0": tmp_path / "mounted-cache"}
    )

    partition_date = date(2000, 2, 16)
    source_dir = tmp_path / "train" / f"dt={partition_date.isoformat()}"
    source_dir.mkdir(parents=True)
    (source_dir / "part-00000.jsonl").write_text("source\n", encoding="utf-8")

    mounted_partition = tmp_path / "mounted-cache" / f"dt={partition_date.isoformat()}"
    mounted_partition.mkdir(parents=True)

    s3_client = MagicMock()
    monkeypatch.setattr(pyhotvect_module.boto3, "client", MagicMock(return_value=s3_client))
    local_encoded_path = tmp_path / "local-cache" / "encoded"

    def write_s3_file(s3_uri, dest_path, _s3_client):
        if s3_uri.endswith("_SUCCESS"):
            dest_path.touch()
        else:
            dest_path.write_text("schema\n", encoding="utf-8")

    monkeypatch.setattr(pyhotvect_module, "download_s3_file", write_s3_file)

    def materialize_encoded_cache(cache_path, local_cache_path):
        assert cache_path == "s3://bucket/cache/algo@55.7.0/partitions/encode/dt=2000-02-16/encoded/"
        assert local_cache_path.endswith("partitions/encode/dt=2000-02-16")
        local_encoded_path.mkdir(parents=True)
        (local_encoded_path / "part-00000.tsv").write_text("encoded\n", encoding="utf-8")
        return str(local_encoded_path)

    monkeypatch.setattr(pyhotvect_module, "as_locally_available_content", materialize_encoded_cache)
    pipeline._do_encode_source_dest_mappings = MagicMock(side_effect=AssertionError("partition should hit S3 cache"))

    result = pipeline._load_or_create_encode_partition(
        partition_date=partition_date,
        source_paths=[str(source_dir)],
    )

    assert result["cache"] == "hit"
    assert result["encoded_path"] == str(local_encoded_path)
    assert Path(result["schema_path"]).read_text(encoding="utf-8") == "schema\n"


def test_encode_cache_target_reports_started_partition_without_reencoding(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "train_data_spec": {"data_prefix": "train"},
            "number_of_training_days": 1,
            "training_lag_days": 1,
            "training_command": "echo train",
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "encode": {"cache": "partition"},
            },
        },
    )
    pipeline.run_target = "encode-cache"
    pipeline.last_test_time = date(2000, 2, 17)
    pipeline.training_lag = timedelta(days=1)
    partition_date = date(2026, 2, 16)
    pipeline.encode_partition_dates = [partition_date]

    source_dir = tmp_path / "train" / f"dt={partition_date.isoformat()}"
    source_dir.mkdir(parents=True)
    partition_cache_path = Path(pipeline._resolve_encode_partition_cache_path(partition_date))
    partition_cache_path.mkdir(parents=True)
    (partition_cache_path / "_STARTED").touch()
    pipeline._do_encode_source_dest_mappings = MagicMock(side_effect=AssertionError("blocked prewarm must not encode"))

    metadata = pipeline._encode_with_partition_cache()

    assert metadata["partition_cache_hits"] == 0
    assert metadata["partition_cache_misses"] == 0
    assert metadata["partition_cache_blocked"] == 1
    assert metadata["partitions"][0]["cache"] == "blocked"
    pipeline._do_encode_source_dest_mappings.assert_not_called()


def test_s3_encode_partition_cache_without_success_marker_is_incomplete(tmp_path: Path, monkeypatch):
    from botocore.exceptions import ClientError

    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(tmp_path, algorithm_definition={})
    s3_client = MagicMock()
    s3_client.list_objects_v2.return_value = {}
    monkeypatch.setattr(pyhotvect_module.boto3, "client", MagicMock(return_value=s3_client))

    def missing_success_marker(s3_uri, dest_path, _s3_client):
        assert s3_uri.endswith("_SUCCESS")
        raise ClientError({"Error": {"Code": "404"}}, "GetObject")

    monkeypatch.setattr(pyhotvect_module, "download_s3_file", missing_success_marker)

    result, publish_blocked = pipeline._load_s3_encode_partition_cache(
        partition_date=date(2026, 2, 16),
        partition_cache_path="s3://bucket/cache/algo@55.7.0/partitions/encode/dt=2026-02-16",
        local_cache_dir=str(tmp_path / "local-cache"),
        source_paths=[str(tmp_path / "source")],
    )

    assert result is None
    assert publish_blocked is False


def test_s3_encode_partition_cache_without_success_marker_but_existing_content_blocks_publish(
    tmp_path: Path, monkeypatch
):
    from botocore.exceptions import ClientError

    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(tmp_path, algorithm_definition={})
    s3_client = MagicMock()
    s3_client.list_objects_v2.return_value = {
        "Contents": [{"Key": "cache/algo@55.7.0/partitions/encode/dt=2026-02-16/encoded/part-00000.tsv"}]
    }
    monkeypatch.setattr(pyhotvect_module.boto3, "client", MagicMock(return_value=s3_client))

    def missing_success_marker(s3_uri, dest_path, _s3_client):
        assert s3_uri.endswith("_SUCCESS")
        raise ClientError({"Error": {"Code": "404"}}, "GetObject")

    monkeypatch.setattr(pyhotvect_module, "download_s3_file", missing_success_marker)

    result, publish_blocked = pipeline._load_s3_encode_partition_cache(
        partition_date=date(2026, 2, 16),
        partition_cache_path="s3://bucket/cache/algo@55.7.0/partitions/encode/dt=2026-02-16",
        local_cache_dir=str(tmp_path / "local-cache"),
        source_paths=[str(tmp_path / "source")],
    )

    assert result is None
    assert publish_blocked is True


def test_s3_encode_partition_cache_started_marker_blocks_publish(tmp_path: Path, monkeypatch):
    from botocore.exceptions import ClientError

    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(tmp_path, algorithm_definition={})
    s3_client = MagicMock()
    s3_client.list_objects_v2.return_value = {
        "Contents": [{"Key": "cache/algo@55.7.0/partitions/encode/dt=2000-02-16/_STARTED"}]
    }
    monkeypatch.setattr(pyhotvect_module.boto3, "client", MagicMock(return_value=s3_client))

    def missing_success_marker(s3_uri, dest_path, _s3_client):
        assert s3_uri.endswith("_SUCCESS")
        raise ClientError({"Error": {"Code": "404"}}, "GetObject")

    monkeypatch.setattr(pyhotvect_module, "download_s3_file", missing_success_marker)

    result, publish_blocked = pipeline._load_s3_encode_partition_cache(
        partition_date=date(2026, 2, 16),
        partition_cache_path="s3://bucket/cache/algo@55.7.0/partitions/encode/dt=2026-02-16",
        local_cache_dir=str(tmp_path / "local-cache"),
        source_paths=[str(tmp_path / "source")],
    )

    assert result is None
    assert publish_blocked is True


def test_s3_encode_partition_cache_materializes_published_encoded_directory(tmp_path: Path, monkeypatch):
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(tmp_path, algorithm_definition={})
    s3_client = MagicMock()
    monkeypatch.setattr(pyhotvect_module.boto3, "client", MagicMock(return_value=s3_client))
    encoded_dir = tmp_path / "local-cache" / "encoded"
    encoded_dir.mkdir(parents=True)
    materialize_encoded = MagicMock(return_value=str(encoded_dir))
    monkeypatch.setattr(pyhotvect_module, "as_locally_available_content", materialize_encoded)

    def write_success_marker(s3_uri, dest_path, _s3_client):
        if s3_uri.endswith("_SUCCESS"):
            _write_partition_success_marker(dest_path.parent)
        else:
            dest_path.write_text("schema\n", encoding="utf-8")

    monkeypatch.setattr(pyhotvect_module, "download_s3_file", write_success_marker)

    result, publish_blocked = pipeline._load_s3_encode_partition_cache(
        partition_date=date(2000, 2, 16),
        partition_cache_path="s3://bucket/cache/algo@55.7.0/partitions/encode/dt=2000-02-16",
        local_cache_dir=str(tmp_path / "local-cache"),
        source_paths=[str(tmp_path / "source")],
    )

    assert result["cache"] == "hit"
    assert result["encoded_path"] == str(encoded_dir)
    assert publish_blocked is False
    materialize_encoded.assert_called_once_with(
        "s3://bucket/cache/algo@55.7.0/partitions/encode/dt=2000-02-16/encoded/",
        str(tmp_path / "local-cache"),
    )


def test_s3_encode_partition_cache_with_success_marker_propagates_missing_schema(tmp_path: Path, monkeypatch):
    from botocore.exceptions import ClientError

    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(tmp_path, algorithm_definition={})
    s3_client = MagicMock()
    monkeypatch.setattr(pyhotvect_module.boto3, "client", MagicMock(return_value=s3_client))
    encoded_dir = tmp_path / "local-cache" / "encoded"
    encoded_dir.mkdir(parents=True)
    monkeypatch.setattr(pyhotvect_module, "as_locally_available_content", MagicMock(return_value=str(encoded_dir)))

    def download_cache_file(s3_uri, dest_path, _s3_client):
        if s3_uri.endswith("_SUCCESS"):
            _write_partition_success_marker(dest_path.parent)
            return
        raise ClientError({"Error": {"Code": "404"}}, "GetObject")

    monkeypatch.setattr(pyhotvect_module, "download_s3_file", download_cache_file)

    with pytest.raises(ClientError):
        pipeline._load_s3_encode_partition_cache(
            partition_date=date(2000, 2, 16),
            partition_cache_path="s3://bucket/cache/algo@55.7.0/partitions/encode/dt=2000-02-16",
            local_cache_dir=str(tmp_path / "local-cache"),
            source_paths=[str(tmp_path / "source")],
        )


def test_encode_partition_cache_miss_stores_encoded_data_schema_and_success_marker(tmp_path: Path, monkeypatch):
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "train_data_spec": {"data_prefix": "train"},
            "number_of_training_days": 1,
            "training_lag_days": 1,
            "training_command": "echo train",
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "encode": {"cache": "partition"},
            },
        },
    )
    partition_date = date(2026, 2, 16)
    source_dir = tmp_path / "train" / f"dt={partition_date.isoformat()}"
    source_dir.mkdir(parents=True)
    (source_dir / "part-00000.jsonl").write_text("source\n", encoding="utf-8")

    store_calls = []

    def record_store(src, dest):
        store_calls.append((src, dest))

    monkeypatch.setattr(pyhotvect_module, "store_file", record_store)
    pipeline._do_encode_source_dest_mappings = MagicMock(side_effect=_write_batched_encoded_partitions)

    result = pipeline._load_or_create_encode_partition(
        partition_date=partition_date,
        source_paths=[str(source_dir)],
    )
    assert result["cache"] == "pending"
    pipeline._encode_pending_partitions([result])

    partition_cache_path = pipeline._resolve_encode_partition_cache_path(partition_date)
    assert result["cache"] == "miss"
    assert [dest for _, dest in store_calls] == [
        f"{partition_cache_path}/encoded",
        f"{partition_cache_path}/encoded-schema-description",
        f"{partition_cache_path}/_SUCCESS",
    ]
    assert (Path(partition_cache_path) / "_STARTED").is_file()


def test_s3_encode_partition_cache_claim_returns_false_when_started_marker_exists(tmp_path: Path, monkeypatch):
    from botocore.exceptions import ClientError

    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(tmp_path, algorithm_definition={})
    s3_client = MagicMock()
    s3_client.put_object.side_effect = ClientError({"Error": {"Code": "PreconditionFailed"}}, "PutObject")
    monkeypatch.setattr(pyhotvect_module.boto3, "client", MagicMock(return_value=s3_client))

    claimed = pipeline._claim_encode_partition_cache_write(
        "s3://bucket/cache/algo@55.7.0/partitions/encode/dt=2000-02-16"
    )

    assert claimed is False


def test_encode_partition_cache_ignores_local_partition_without_success_marker(tmp_path: Path, monkeypatch):
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "train_data_spec": {"data_prefix": "train"},
            "number_of_training_days": 1,
            "training_lag_days": 1,
            "training_command": "echo train",
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "encode": {"cache": "partition"},
            },
        },
    )

    partition_date = date(2026, 2, 16)
    source_dir = tmp_path / "train" / f"dt={partition_date.isoformat()}"
    source_dir.mkdir(parents=True)
    (source_dir / "part-00000.jsonl").write_text("source\n", encoding="utf-8")

    partition_cache_dir = Path(pipeline._resolve_encode_partition_cache_path(partition_date))
    encoded_dir = partition_cache_dir / "encoded"
    encoded_dir.mkdir(parents=True)
    (encoded_dir / "part-00000.tsv").write_text("dirty-read\n", encoding="utf-8")
    (partition_cache_dir / "encoded-schema-description").write_text("schema\n", encoding="utf-8")

    store_calls = []

    def record_store(src, dest):
        store_calls.append((src, dest))

    monkeypatch.setattr(pyhotvect_module, "store_file", record_store)
    pipeline._do_encode_source_dest_mappings = MagicMock(side_effect=_write_batched_encoded_partitions)

    result = pipeline._load_or_create_encode_partition(
        partition_date=partition_date,
        source_paths=[str(source_dir)],
    )
    pipeline._encode_pending_partitions([result])

    assert result["cache"] == "miss"
    pipeline._do_encode_source_dest_mappings.assert_called_once()
    assert store_calls == []


def test_encode_partition_cache_uses_local_partition_when_success_marker_exists(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "train_data_spec": {"data_prefix": "train"},
            "number_of_training_days": 1,
            "training_lag_days": 1,
            "training_command": "echo train",
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "encode": {"cache": "partition"},
            },
        },
    )

    partition_date = date(2026, 2, 16)
    source_dir = tmp_path / "train" / f"dt={partition_date.isoformat()}"
    source_dir.mkdir(parents=True)
    (source_dir / "part-00000.jsonl").write_text("source\n", encoding="utf-8")

    partition_cache_dir = Path(pipeline._resolve_encode_partition_cache_path(partition_date))
    partition_cache_dir.mkdir(parents=True)
    _write_partition_success_marker(partition_cache_dir)

    pipeline._do_encode_source_dest_mappings = MagicMock(
        side_effect=AssertionError("existing success marker must be authoritative")
    )

    result = pipeline._load_or_create_encode_partition(
        partition_date=partition_date,
        source_paths=[str(source_dir)],
    )

    assert result["cache"] == "hit"
    assert result["encoded_path"] == str(partition_cache_dir / "encoded")
    assert result["schema_path"] == str(partition_cache_dir / "encoded-schema-description")
    pipeline._do_encode_source_dest_mappings.assert_not_called()


def test_encode_partition_cache_does_not_publish_empty_encoded_output(tmp_path: Path, monkeypatch):
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "train_data_spec": {"data_prefix": "train"},
            "number_of_training_days": 1,
            "training_lag_days": 1,
            "training_command": "echo train",
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "encode": {"cache": "partition"},
            },
        },
    )
    partition_date = date(2000, 2, 16)
    source_dir = tmp_path / "train" / f"dt={partition_date.isoformat()}"
    source_dir.mkdir(parents=True)
    (source_dir / "part-00000.jsonl").write_text("source\n", encoding="utf-8")

    def write_empty_encoded_partition(mappings, schema_description_location, stage):
        assert stage == "encode-partitions"
        encoded_path = Path(mappings[0]["dest"])
        encoded_path.mkdir(parents=True)
        (encoded_path / "part-00000.tsv").touch()
        schema_path = Path(schema_description_location)
        schema_path.parent.mkdir(parents=True, exist_ok=True)
        schema_path.write_text("schema\n", encoding="utf-8")
        return {"source_dest_mappings": mappings}

    pipeline._do_encode_source_dest_mappings = MagicMock(side_effect=write_empty_encoded_partition)
    store_file = MagicMock()
    monkeypatch.setattr(pyhotvect_module, "store_file", store_file)

    partition = pipeline._load_or_create_encode_partition(
        partition_date=partition_date,
        source_paths=[str(source_dir)],
    )
    with pytest.raises(FileNotFoundError, match="did not produce non-empty encoded data"):
        pipeline._encode_pending_partitions([partition])

    store_file.assert_not_called()


def test_encode_partition_cache_miss_writes_direct_s3_partition_without_deleting(tmp_path: Path, monkeypatch):
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "train_data_spec": {"data_prefix": "train"},
            "number_of_training_days": 1,
            "training_lag_days": 1,
            "training_command": "echo train",
            "hotvect_execution_parameters": {
                "cache_base_dir": "s3://bucket/cache",
                "encode": {"cache": "partition"},
            },
        },
    )

    partition_date = date(2026, 2, 16)
    source_dir = tmp_path / "train" / f"dt={partition_date.isoformat()}"
    source_dir.mkdir(parents=True)
    (source_dir / "part-00000.jsonl").write_text("source\n", encoding="utf-8")
    partition_cache_path = pipeline._resolve_encode_partition_cache_path(partition_date)

    s3_client = MagicMock()
    s3_client.list_objects_v2.return_value = {}
    monkeypatch.setattr(pyhotvect_module.boto3, "client", MagicMock(return_value=s3_client))
    pipeline._load_s3_encode_partition_cache = MagicMock(return_value=(None, False))

    events = []
    s3_client.delete_objects.side_effect = lambda **kwargs: events.append(("delete", kwargs))
    monkeypatch.setattr(pyhotvect_module, "store_file", lambda src, dest: events.append(("store", dest)))
    pipeline._do_encode_source_dest_mappings = MagicMock(side_effect=_write_batched_encoded_partitions)

    result = pipeline._load_or_create_encode_partition(
        partition_date=partition_date,
        source_paths=[str(source_dir)],
    )
    pipeline._encode_pending_partitions([result])

    assert result["cache"] == "miss"
    assert events == [
        ("store", f"{partition_cache_path}/encoded"),
        ("store", f"{partition_cache_path}/encoded-schema-description"),
        ("store", f"{partition_cache_path}/_SUCCESS"),
    ]
    started_call = s3_client.put_object.call_args.kwargs
    assert started_call["Bucket"] == "bucket"
    assert started_call["Key"] == "cache/algo@55.7.0/partitions/encode/dt=2026-02-16/_STARTED"
    assert started_call["IfNoneMatch"] == "*"
    s3_client.delete_objects.assert_not_called()


def test_encode_cache_target_does_not_overwrite_dirty_s3_partition_cache(tmp_path: Path, monkeypatch):
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "train_data_spec": {"data_prefix": "train"},
            "number_of_training_days": 1,
            "training_lag_days": 1,
            "training_command": "echo train",
            "hotvect_execution_parameters": {
                "cache_base_dir": "s3://bucket/cache",
                "encode": {"cache": "partition"},
            },
        },
    )
    pipeline.run_target = "encode-cache"

    partition_date = date(2026, 2, 16)
    source_dir = tmp_path / "train" / f"dt={partition_date.isoformat()}"
    source_dir.mkdir(parents=True)
    (source_dir / "part-00000.jsonl").write_text("source\n", encoding="utf-8")
    partition_cache_path = pipeline._resolve_encode_partition_cache_path(partition_date)

    events = []
    monkeypatch.setattr(pyhotvect_module, "store_file", lambda src, dest: events.append(("store", dest)))
    pipeline._do_encode_source_dest_mappings = MagicMock(side_effect=AssertionError("dirty prewarm must not encode"))
    pipeline._load_s3_encode_partition_cache = MagicMock(return_value=(None, True))
    pipeline._claim_encode_partition_cache_write = MagicMock(
        side_effect=lambda path: events.append(("claim", path)) or True
    )

    result = pipeline._load_or_create_encode_partition(
        partition_date=partition_date,
        source_paths=[str(source_dir)],
    )

    assert result["cache"] == "blocked"
    pipeline._load_s3_encode_partition_cache.assert_called_once_with(
        partition_date=partition_date,
        partition_cache_path=partition_cache_path,
        local_cache_dir=ANY,
        source_paths=[str(source_dir)],
    )
    pipeline._claim_encode_partition_cache_write.assert_not_called()
    pipeline._do_encode_source_dest_mappings.assert_not_called()
    assert events == []


def test_s3_partition_writer_skips_publish_when_started_claim_exists(tmp_path: Path, monkeypatch):
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "train_data_spec": {"data_prefix": "train"},
            "number_of_training_days": 1,
            "training_lag_days": 1,
            "training_command": "echo train",
            "hotvect_execution_parameters": {
                "cache_base_dir": "s3://bucket/cache",
                "encode": {"cache": "partition"},
            },
        },
    )

    partition_date = date(2026, 2, 16)
    source_dir = tmp_path / "train" / f"dt={partition_date.isoformat()}"
    source_dir.mkdir(parents=True)
    (source_dir / "part-00000.jsonl").write_text("source\n", encoding="utf-8")
    partition_cache_path = pipeline._resolve_encode_partition_cache_path(partition_date)

    store_calls = []

    def record_store(src, dest):
        store_calls.append((src, dest))

    monkeypatch.setattr(pyhotvect_module, "store_file", record_store)
    pipeline._do_encode_source_dest_mappings = MagicMock(side_effect=_write_batched_encoded_partitions)
    pipeline._load_s3_encode_partition_cache = MagicMock(return_value=(None, False))
    pipeline._claim_encode_partition_cache_write = MagicMock(side_effect=[True, False])

    for _ in range(2):
        result = pipeline._load_or_create_encode_partition(
            partition_date=partition_date,
            source_paths=[str(source_dir)],
        )
        pipeline._encode_pending_partitions([result])
        assert result["cache"] == "miss"

    assert [dest for _, dest in store_calls] == [
        f"{partition_cache_path}/encoded",
        f"{partition_cache_path}/encoded-schema-description",
        f"{partition_cache_path}/_SUCCESS",
    ]


def test_build_encode_partition_view_preserves_directories_file_names_and_suffixes(tmp_path: Path):
    pipeline = _make_pipeline(tmp_path, algorithm_definition={})
    pipeline.encoded_data_file_path = MagicMock(return_value=str(tmp_path / "output" / "encoded"))
    pipeline.encoded_schema_description_file_path = MagicMock(return_value=str(tmp_path / "output" / "schema"))

    schema_path = tmp_path / "cache" / "schema"
    schema_path.parent.mkdir(parents=True)
    schema_path.write_bytes(b"schema")
    partitions = []
    expected = [b"\x01\x02", b"\x03\x04"]
    for index, payload in enumerate(expected):
        encoded_dir = tmp_path / "cache" / f"dt-{index}" / "encoded"
        (encoded_dir / "nested").mkdir(parents=True)
        (encoded_dir / "part-00000.tfrecord").write_bytes(payload)
        (encoded_dir / "nested" / "metadata.json").write_text(f'{{"partition": {index}}}\n', encoding="utf-8")
        partitions.append(
            {
                "dt": f"2000-02-{15 + index}",
                "schema_path": str(schema_path),
                "encoded_path": str(encoded_dir),
            }
        )

    pipeline._build_encode_partition_view(partitions)

    encoded_output = tmp_path / "output" / "encoded"
    assert sorted(path.name for path in encoded_output.iterdir()) == [
        "dt=2000-02-15",
        "dt=2000-02-16",
    ]
    assert [
        (encoded_output / f"dt=2000-02-{15 + index}" / "part-00000.tfrecord").read_bytes() for index in range(2)
    ] == expected
    assert (encoded_output / "dt=2000-02-16" / "nested" / "metadata.json").read_text(
        encoding="utf-8"
    ) == '{"partition": 1}\n'
    assert (tmp_path / "output" / "schema").read_bytes() == b"schema"


def test_build_encode_partition_view_does_not_revalidate_published_directory_contents(tmp_path: Path):
    pipeline = _make_pipeline(tmp_path, algorithm_definition={})
    pipeline.encoded_data_file_path = MagicMock(return_value=str(tmp_path / "output" / "encoded"))
    pipeline.encoded_schema_description_file_path = MagicMock(return_value=str(tmp_path / "output" / "schema"))

    encoded_dir = tmp_path / "cache" / "encoded"
    encoded_dir.mkdir(parents=True)
    schema_path = tmp_path / "cache" / "schema"
    schema_path.write_text("schema\n", encoding="utf-8")

    pipeline._build_encode_partition_view(
        [
            {
                "dt": "2000-02-16",
                "schema_path": str(schema_path),
                "encoded_path": str(encoded_dir),
            }
        ]
    )

    assert (tmp_path / "output" / "encoded" / "dt=2000-02-16").is_dir()


def test_build_encode_partition_view_rejects_missing_partition_directory(tmp_path: Path):
    pipeline = _make_pipeline(tmp_path, algorithm_definition={})
    pipeline.encoded_data_file_path = MagicMock(return_value=str(tmp_path / "output" / "encoded"))
    pipeline.encoded_schema_description_file_path = MagicMock(return_value=str(tmp_path / "output" / "schema"))

    schema_path = tmp_path / "cache" / "schema"
    schema_path.parent.mkdir(parents=True)
    schema_path.write_text("schema\n", encoding="utf-8")

    with pytest.raises(FileNotFoundError, match="directory does not exist"):
        pipeline._build_encode_partition_view(
            [
                {
                    "dt": "2026-02-16",
                    "schema_path": str(schema_path),
                    "encoded_path": str(tmp_path / "missing"),
                }
            ]
        )


def test_build_encode_partition_view_rejects_schema_mismatch(tmp_path: Path):
    pipeline = _make_pipeline(tmp_path, algorithm_definition={})
    pipeline.encoded_data_file_path = MagicMock(return_value=str(tmp_path / "output" / "encoded"))
    pipeline.encoded_schema_description_file_path = MagicMock(return_value=str(tmp_path / "output" / "schema"))

    partitions = []
    for partition_date, schema in [("2026-02-15", "schema-a\n"), ("2026-02-16", "schema-b\n")]:
        partition_dir = tmp_path / "cache" / partition_date
        encoded_dir = partition_dir / "encoded"
        encoded_dir.mkdir(parents=True)
        (encoded_dir / "part-00000.tsv").write_text(f"{partition_date}\n", encoding="utf-8")
        schema_path = partition_dir / "encoded-schema-description"
        schema_path.write_text(schema, encoding="utf-8")
        partitions.append(
            {
                "dt": partition_date,
                "schema_path": str(schema_path),
                "encoded_path": str(encoded_dir),
            }
        )

    with pytest.raises(ValueError, match="Encoded schema mismatch"):
        pipeline._build_encode_partition_view(partitions)


def test_per_task_cache_string_override_template_is_rendered(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "encode": {
                    "cache": "/tmp/override/{{ parameter_version }}/{{ hyperparameter_slug }}",
                }
            }
        },
        algorithm_name="algo",
        algorithm_version="55.7.0",
    )
    pipeline.hyper_parameter_version = "ordered"
    pipeline.parameter_version = "last_test_date_2026-02-17"

    cache_path = pipeline._resolve_cache_path(["encode"], "encoded.bin")
    assert cache_path == "/tmp/override/last_test_date_2026-02-17/algo@55.7.0-ordered/encoded.bin"


def test_with_parameter_is_still_used_when_cache_refresh_enabled_if_available(tmp_path: Path, monkeypatch):
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "cache": "run",
                "cache_refresh": True,
                "with_parameter": "s3://bucket/prefix/params.zip",
            }
        },
    )
    pipeline.cache_path = MagicMock(return_value=str(tmp_path / "local-cache"))

    as_local = MagicMock(return_value=str(tmp_path / "params.zip"))
    monkeypatch.setattr(pyhotvect_module, "as_locally_available_content", as_local)

    result = pipeline.available_predict_parameter_cache_path()

    assert result == str(tmp_path / "params.zip")
    as_local.assert_called_once()


def test_with_parameter_skips_dependency_preparation(tmp_path: Path, monkeypatch):
    import contextlib
    from datetime import datetime, timezone

    from hotvect import pyhotvect as pyhotvect_module
    from hotvect.pyhotvect import AlgorithmPipeline

    pipeline = AlgorithmPipeline.__new__(AlgorithmPipeline)
    pipeline.algorithm_definition = {
        "algorithm_factory_classname": "example.Factory",
        "hotvect_execution_parameters": {"with_parameter": "s3://bucket/params.zip"},
    }
    pipeline.algorithm_definition_override = None
    pipeline.algorithm_override_metadata = None
    pipeline.algorithm_name = "algo"
    pipeline.algorithm_version = "55.7.0"
    pipeline.hyper_parameter_version = ""
    pipeline.parameter_version = "last_test_date_2026-02-17"
    pipeline.last_test_time = datetime(2026, 2, 17, tzinfo=timezone.utc)
    pipeline.ran_at = "2026-02-17T00:00:00Z"
    pipeline.run_target = "evaluate"
    pipeline.encode_partition_dates = None
    pipeline.hyperparameter_slug = MagicMock(return_value="algo@55.7.0")

    pipeline.clean = MagicMock()
    pipeline.cache_path = MagicMock(return_value=str(tmp_path / "cache"))
    pipeline._pipe_python_logs_to_metadata_dir = MagicMock(return_value=contextlib.nullcontext())
    pipeline._write_algorithm_definition = MagicMock(return_value=str(tmp_path / "algo-definition.json"))
    pipeline.available_predict_parameter_cache_path = MagicMock(return_value=str(tmp_path / "params.zip"))
    pipeline._step_predict_parameter = MagicMock()
    pipeline.test_data_paths = MagicMock(return_value=[])
    pipeline._write_data = MagicMock()
    pipeline.metadata_path = MagicMock(return_value=str(tmp_path / "metadata"))

    pipeline.algorithm_is_state = False
    pipeline.should_train = MagicMock(return_value=False)
    pipeline.execute_performance_test = False
    pipeline.encode_test_data = False
    pipeline.execute_audit = False

    dependency_pipeline = MagicMock()
    dependency_pipeline.run_all = MagicMock()
    pipeline.dependency_pipelines = {"dep": dependency_pipeline}

    monkeypatch.setattr(pyhotvect_module, "clean_dir", MagicMock())

    result = pipeline.run_all(clean=False, evaluate=True)

    dependency_pipeline.run_all.assert_not_called()
    assert result["dependencies"] == {"skipped": ANY}


def test_parent_packaging_reuses_pinned_child_archive_with_nested_dependency(tmp_path: Path):
    import zipfile
    from datetime import datetime, timezone

    parent = _make_pipeline(tmp_path, algorithm_definition={}, algorithm_name="parent", algorithm_version="1.0.0")
    child = _make_pipeline(
        tmp_path,
        algorithm_definition={"hotvect_execution_parameters": {"with_parameter": str(tmp_path / "child.zip")}},
        algorithm_name="child",
        algorithm_version="2.0.0",
    )
    nested = _make_pipeline(
        tmp_path,
        algorithm_definition={"training_command": "train"},
        algorithm_name="nested",
        algorithm_version="3.0.0",
    )

    for pipeline in (parent, child, nested):
        pipeline.last_test_time = datetime(2026, 2, 17, tzinfo=timezone.utc)
        pipeline.ran_at = "2026-02-17T00:00:00Z"
        pipeline.dependency_pipelines = {}
        pipeline.algorithm_is_state = False

    child.dependency_pipelines = {"nested": nested}
    parent.dependency_pipelines = {"child": child}

    child_output = Path(child.output_path())
    child_output.mkdir(parents=True, exist_ok=True)
    (child_output / "algorithm-parameters.json").write_text("{}")

    child_zip = tmp_path / "child.zip"
    with zipfile.ZipFile(child_zip, "w") as zf:
        zf.writestr("child/algorithm-parameters.json", "{}")
        zf.writestr("child/model_parameter/model.bin", b"child-model")
        zf.writestr("nested/algorithm-parameters.json", "{}")
        zf.writestr("nested/model_parameter/model.bin", b"nested-model")

    child.available_predict_parameter_cache_path = MagicMock(return_value=str(child_zip))

    metadata = parent._do_package_parameters(is_encode=False, skip_zip=False)

    assert metadata["pinned_parameter_archives"] == [{"algorithm_name": "child", "source": str(child_zip)}]

    with zipfile.ZipFile(parent.predict_parameter_file_path(), "r") as zf:
        assert "parent/algorithm-parameters.json" in zf.namelist()
        assert "child/algorithm-parameters.json" in zf.namelist()
        assert zf.read("child/model_parameter/model.bin") == b"child-model"
        assert "nested/algorithm-parameters.json" in zf.namelist()
        assert zf.read("nested/model_parameter/model.bin") == b"nested-model"

    assert not Path(nested.output_path()).exists()


def test_cache_string_override_that_points_to_file_is_not_appended_again(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "train": {
                    "cache": "s3://mybucket/some-prefix/predict-parameters.zip",
                }
            }
        },
    )
    assert (
        pipeline._resolve_cache_path(["train"], "predict-parameters.zip")
        == "s3://mybucket/some-prefix/predict-parameters.zip"
    )


def test_package_predict_parameters_cached_zip_skips_directory_entries(tmp_path: Path, monkeypatch):
    import zipfile

    from hotvect import pyhotvect as pyhotvect_module
    from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext

    out_base = tmp_path / "out"
    out_base.mkdir()

    pipeline = AlgorithmPipeline.__new__(AlgorithmPipeline)
    pipeline.algorithm_definition = {}
    pipeline.algorithm_name = "algo"
    pipeline.algorithm_version = "55.7.0"
    pipeline.hyper_parameter_version = ""
    pipeline.parameter_version = "last_test_date_2026-02-17"
    pipeline.available_parameter_cache_path = None
    pipeline.algorithm_pipeline_context = AlgorithmPipelineContext(
        algorithm_jar_path=tmp_path / "algo.jar",
        data_base_path=tmp_path,
        metadata_base_path=tmp_path,
        output_base_path=out_base,
        jvm_options=None,
    )

    cached_zip = tmp_path / "predict-parameters.zip"
    with zipfile.ZipFile(cached_zip, "w") as zf:
        zf.writestr("algo/", "")  # directory entry
        zf.writestr("algo/model.bin", b"abc")
        zf.writestr("algo/subdir/", "")  # directory entry
        zf.writestr("algo/subdir/file.txt", "hello")

    pipeline.available_predict_parameter_cache_path = MagicMock(return_value=str(cached_zip))
    monkeypatch.setattr(pyhotvect_module, "copy_or_link", lambda src, dst: None)

    result = pipeline.package_predict_parameters()

    extracted_root = Path(pipeline.output_path())
    assert (extracted_root / "model.bin").read_bytes() == b"abc"
    assert (extracted_root / "subdir" / "file.txt").read_text() == "hello"
    assert "skipped" in result


def test_package_predict_parameters_cached_zip_extracts_dependency_artifacts(tmp_path: Path, monkeypatch):
    import zipfile

    from hotvect import pyhotvect as pyhotvect_module
    from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext

    out_base = tmp_path / "out"
    out_base.mkdir()

    def make_pipeline(name: str):
        pipeline = AlgorithmPipeline.__new__(AlgorithmPipeline)
        pipeline.algorithm_definition = {}
        pipeline.algorithm_name = name
        pipeline.algorithm_version = "55.7.0"
        pipeline.hyper_parameter_version = ""
        pipeline.parameter_version = "last_test_date_2000-02-17"
        pipeline.available_parameter_cache_path = None
        pipeline.algorithm_pipeline_context = AlgorithmPipelineContext(
            algorithm_jar_path=tmp_path / "algo.jar",
            data_base_path=tmp_path,
            metadata_base_path=tmp_path / "meta",
            output_base_path=out_base,
            jvm_options=None,
        )
        pipeline.dependency_pipelines = {}
        return pipeline

    pipeline = make_pipeline("algo")
    dependency_pipeline = make_pipeline("config-feature-state")
    pipeline.dependency_pipelines = {"config-feature-state": dependency_pipeline}

    cached_zip = tmp_path / "predict-parameters.zip"
    with zipfile.ZipFile(cached_zip, "w") as zf:
        zf.writestr("algo/algorithm-parameters.json", "{}")
        zf.writestr("algo/model_parameter/model.parameter", "abc")
        zf.writestr("config-feature-state/algorithm-parameters.json", "{}")
        zf.writestr("config-feature-state/in_memory_config_feature_state.jsonl", "state")
        zf.writestr("ignored-parent/algorithm-parameters.json", "{}")

    pipeline.available_predict_parameter_cache_path = MagicMock(return_value=str(cached_zip))
    monkeypatch.setattr(pyhotvect_module, "copy_or_link", lambda src, dst: None)

    result = pipeline.package_predict_parameters()

    assert (Path(pipeline.output_path()) / "model_parameter" / "model.parameter").read_text() == "abc"
    assert (Path(dependency_pipeline.output_path()) / "in_memory_config_feature_state.jsonl").read_text() == "state"
    assert not (Path(pipeline.output_path()) / "ignored-parent").exists()
    assert "skipped" in result


def test_do_package_parameters_supports_legacy_flat_model_parameter_file(tmp_path: Path):
    import zipfile
    from datetime import datetime, timezone

    from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext

    out_base = tmp_path / "out"
    meta_base = tmp_path / "meta"
    out_base.mkdir()
    meta_base.mkdir()

    pipeline = AlgorithmPipeline.__new__(AlgorithmPipeline)
    pipeline.algorithm_definition = {"training_command": "train"}
    pipeline.algorithm_name = "algo"
    pipeline.algorithm_version = "55.7.0"
    pipeline.hyper_parameter_version = ""
    pipeline.parameter_version = "last_test_date_2000-02-17"
    pipeline.last_test_time = datetime(2000, 2, 17, tzinfo=timezone.utc)
    pipeline.ran_at = "2000-02-17T00:00:00Z"
    pipeline.available_parameter_cache_path = None
    pipeline.algorithm_pipeline_context = AlgorithmPipelineContext(
        algorithm_jar_path=tmp_path / "algo.jar",
        data_base_path=tmp_path,
        metadata_base_path=meta_base,
        output_base_path=out_base,
        jvm_options=None,
    )
    pipeline.dependency_pipelines = {}
    pipeline.algorithm_is_state = False

    output_dir = Path(pipeline.output_path())
    output_dir.mkdir(parents=True, exist_ok=True)
    metadata_dir = Path(pipeline.metadata_path())
    metadata_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "algorithm-parameters.json").write_text("{}")
    (output_dir / "model.parameter").write_text("legacy")

    metadata = pipeline._do_package_parameters(is_encode=False)

    with zipfile.ZipFile(metadata["package"], "r") as zf:
        names = zf.namelist()

    assert "algo/model.parameter" in names
    assert "algo/model_parameter/model.parameter" not in names


def _make_model_parameter_pipeline(tmp_path: Path, *, hotvect_version: str):
    from datetime import datetime, timezone

    from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext

    out_base = tmp_path / "out"
    meta_base = tmp_path / "meta"
    out_base.mkdir()
    meta_base.mkdir()

    pipeline = AlgorithmPipeline.__new__(AlgorithmPipeline)
    pipeline.algorithm_definition = {"hotvect_version": hotvect_version, "training_command": "train"}
    pipeline.algorithm_name = "algo"
    pipeline.algorithm_version = "55.7.0"
    pipeline.hyper_parameter_version = ""
    pipeline.parameter_version = "last_test_date_2026-02-17"
    pipeline.last_test_time = datetime(2026, 2, 17, tzinfo=timezone.utc)
    pipeline.ran_at = "2026-02-17T00:00:00Z"
    pipeline.available_parameter_cache_path = None
    pipeline.algorithm_pipeline_context = AlgorithmPipelineContext(
        algorithm_jar_path=tmp_path / "algo.jar",
        data_base_path=tmp_path,
        metadata_base_path=meta_base,
        output_base_path=out_base,
        jvm_options=None,
    )
    pipeline.dependency_pipelines = {}
    pipeline.algorithm_is_state = False

    output_dir = Path(pipeline.output_path())
    model_dir = output_dir / "model_parameter"
    model_dir.mkdir(parents=True, exist_ok=True)
    metadata_dir = Path(pipeline.metadata_path())
    metadata_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "algorithm-parameters.json").write_text("{}")
    (model_dir / "model.parameter").write_text("model")
    return pipeline


def test_do_package_parameters_uses_legacy_model_parameter_path_for_hv9_directory(tmp_path: Path):
    import zipfile

    pipeline = _make_model_parameter_pipeline(tmp_path, hotvect_version="9.36.0")

    metadata = pipeline._do_package_parameters(is_encode=False)

    with zipfile.ZipFile(metadata["package"], "r") as zf:
        names = zf.namelist()

    assert "algo/model_parameter/model.parameter" not in names
    assert "algo/model.parameter" in names


def test_do_package_parameters_uses_v10_model_parameter_path_for_hv10_directory(tmp_path: Path):
    import zipfile

    pipeline = _make_model_parameter_pipeline(tmp_path, hotvect_version="10.31.4")

    metadata = pipeline._do_package_parameters(is_encode=False)

    with zipfile.ZipFile(metadata["package"], "r") as zf:
        names = zf.namelist()

    assert "algo/model_parameter/model.parameter" in names
    assert "algo/model.parameter" not in names


@pytest.mark.parametrize(
    ("algorithm_definition", "expected"),
    [
        ({"hotvect_version": "8.12.0"}, True),
        ({"hotvect_version": "9.36.0"}, True),
        ({"hotvect_version": "10.31.4"}, False),
        ({"training_container": "registry.example/hotvect:8.12.0"}, True),
        ({"training_container": "registry.example/hotvect:9.35.0"}, True),
        ({"training_container": "registry.example/hotvect:10.31.4"}, False),
        ({"hotvect_version": "10.31.4", "training_container": "registry.example/hotvect:9.35.0"}, False),
        ({}, False),
    ],
)
def test_uses_legacy_model_parameter_path_detects_pre_v10(algorithm_definition: dict, expected: bool):
    from hotvect.pyhotvect import _uses_legacy_model_parameter_path

    assert _uses_legacy_model_parameter_path(algorithm_definition) is expected


def test_state_predict_parameters_zip_is_enabled_by_default(tmp_path: Path, monkeypatch):
    from hotvect.pyhotvect import AlgorithmPipeline

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={},
        algorithm_name="state-algo",
    )
    pipeline.algorithm_is_state = True
    pipeline.predict_parameter_cache_original_path = MagicMock(return_value=None)
    pipeline.available_predict_parameter_cache_path = MagicMock(return_value=None)
    pipeline._do_package_parameters = MagicMock(return_value={"package": None})

    AlgorithmPipeline.package_predict_parameters(pipeline)

    pipeline._do_package_parameters.assert_called_once_with(is_encode=False, skip_zip=False)


def test_state_predict_parameters_include_primary_file_when_sidecars_exist(tmp_path: Path):
    import zipfile

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "state_output_filename": "example_feature_state.jsonl",
            "hotvect_execution_parameters": {"package_state_as_predict_parameters": True},
        },
        algorithm_name="example-feature-state",
        algorithm_version="55.7.0",
    )
    pipeline.algorithm_is_state = True
    pipeline.dependency_pipelines = {}
    pipeline.ran_at = "2026-02-17T00:00:00+00:00"
    pipeline.last_test_time = date(2026, 2, 17)

    state_output = Path(pipeline.output_path())
    state_output.mkdir(parents=True, exist_ok=True)
    (state_output / "example_feature_state.jsonl").write_text("{}\n")
    (state_output / "example_feature_state-zmclip.jsonl").write_text("{}\n")

    result = pipeline._do_package_parameters(is_encode=False, skip_zip=False)

    assert (
        str(state_output / "example_feature_state.jsonl"),
        "example-feature-state/example_feature_state.jsonl",
    ) in result["sources"]
    assert (
        str(state_output / "example_feature_state-zmclip.jsonl"),
        "example-feature-state/example_feature_state-zmclip.jsonl",
    ) in result["sources"]

    with zipfile.ZipFile(pipeline.predict_parameter_file_path()) as zip_ref:
        names = zip_ref.namelist()

    assert "example-feature-state/example_feature_state.jsonl" in names
    assert "example-feature-state/example_feature_state-zmclip.jsonl" in names
    assert names.count("example-feature-state/algorithm-parameters.json") == 1


def test_state_predict_parameters_preserve_configured_file_path(tmp_path: Path):
    import zipfile

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "state_output_filename": "states/example_feature_state.jsonl",
            "hotvect_execution_parameters": {"package_state_as_predict_parameters": True},
        },
        algorithm_name="example-feature-state",
        algorithm_version="55.7.0",
    )
    pipeline.algorithm_is_state = True
    pipeline.dependency_pipelines = {}
    pipeline.ran_at = "2026-02-17T00:00:00+00:00"
    pipeline.last_test_time = date(2026, 2, 17)

    state_output = Path(pipeline.output_path()) / "states"
    state_output.mkdir(parents=True, exist_ok=True)
    (state_output / "example_feature_state.jsonl").write_text("{}\n")
    (state_output / "example_feature_state-zmclip.jsonl").write_text("{}\n")

    pipeline._do_package_parameters(is_encode=False, skip_zip=False)

    with zipfile.ZipFile(pipeline.predict_parameter_file_path()) as zip_ref:
        names = zip_ref.namelist()

    assert "example-feature-state/states/example_feature_state.jsonl" in names
    assert "example-feature-state/states/example_feature_state-zmclip.jsonl" in names


def test_state_predict_parameters_allow_configured_directory_path(tmp_path: Path):
    import zipfile

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "state_output_filename": "states",
            "hotvect_execution_parameters": {"package_state_as_predict_parameters": True},
        },
        algorithm_name="example-feature-state",
        algorithm_version="55.7.0",
    )
    pipeline.algorithm_is_state = True
    pipeline.dependency_pipelines = {}
    pipeline.ran_at = "2026-02-17T00:00:00+00:00"
    pipeline.last_test_time = date(2026, 2, 17)

    state_output = Path(pipeline.output_path()) / "states"
    (state_output / "nested").mkdir(parents=True, exist_ok=True)
    (state_output / "root.jsonl").write_text("{}\n")
    (state_output / "nested" / "part.jsonl").write_text("{}\n")

    pipeline._do_package_parameters(is_encode=False, skip_zip=False)

    with zipfile.ZipFile(pipeline.predict_parameter_file_path()) as zip_ref:
        names = zip_ref.namelist()

    assert "example-feature-state/states/root.jsonl" in names
    assert "example-feature-state/states/nested/part.jsonl" in names


def test_state_predict_parameters_require_configured_primary_file(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "state_output_filename": "example_feature_state.jsonl",
            "hotvect_execution_parameters": {"package_state_as_predict_parameters": True},
        },
        algorithm_name="example-feature-state",
        algorithm_version="55.7.0",
    )
    pipeline.algorithm_is_state = True
    pipeline.dependency_pipelines = {}
    pipeline.ran_at = "2026-02-17T00:00:00+00:00"
    pipeline.last_test_time = date(2026, 2, 17)

    state_output = Path(pipeline.output_path())
    state_output.mkdir(parents=True, exist_ok=True)
    (state_output / "example_feature_state-00000.jsonl").write_text("{}\n")

    with pytest.raises(FileNotFoundError, match="Expected configured state output path"):
        pipeline._do_package_parameters(is_encode=False, skip_zip=False)


@pytest.mark.parametrize("state_output_filename", ["/tmp/state.jsonl", "../state.jsonl"])
def test_state_output_filename_rejects_paths_outside_output_dir(tmp_path: Path, state_output_filename: str):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={"state_output_filename": state_output_filename},
    )

    with pytest.raises(ValueError, match="outside base directory"):
        pipeline.state_output_path()


def test_state_predict_parameters_zip_can_be_disabled(tmp_path: Path, monkeypatch):
    from hotvect.pyhotvect import AlgorithmPipeline

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={"hotvect_execution_parameters": {"package_state_as_predict_parameters": False}},
        algorithm_name="state-algo",
    )
    pipeline.algorithm_is_state = True
    pipeline.predict_parameter_cache_original_path = MagicMock(return_value=None)
    pipeline.available_predict_parameter_cache_path = MagicMock(return_value=None)
    pipeline._do_package_parameters = MagicMock(return_value={"package": str(tmp_path / "params.zip")})

    AlgorithmPipeline.package_predict_parameters(pipeline)

    pipeline._do_package_parameters.assert_called_once_with(is_encode=False, skip_zip=True)


def test_state_predict_parameters_skip_zip_still_writes_metadata_file(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "state_output_filename": "example_feature_state.jsonl",
            "hotvect_execution_parameters": {"package_state_as_predict_parameters": False},
        },
        algorithm_name="example-feature-state",
        algorithm_version="55.7.0",
    )
    pipeline.algorithm_is_state = True
    pipeline.dependency_pipelines = {}
    pipeline.ran_at = "2026-02-17T00:00:00+00:00"
    pipeline.last_test_time = date(2026, 2, 17)

    state_output = Path(pipeline.output_path())
    state_output.mkdir(parents=True, exist_ok=True)
    (state_output / "example_feature_state.jsonl").write_text("{}\n")

    result = pipeline._do_package_parameters(is_encode=False, skip_zip=True)

    metadata_path = state_output / "algorithm-parameters.json"
    assert metadata_path.exists()
    assert result["package"] is None
    assert not Path(pipeline.predict_parameter_file_path()).exists()
    assert read_json(str(metadata_path))["package"] is None
    assert (
        str(metadata_path),
        "example-feature-state/algorithm-parameters.json",
    ) in result["sources"]


def test_package_encode_parameters_cached_zip_rejects_zip_slip(tmp_path: Path, monkeypatch):
    import zipfile

    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={"hotvect_execution_parameters": {"cache_base_dir": str(tmp_path / "cache")}},
        algorithm_name="algo",
        algorithm_version="55.7.0",
    )

    cached_zip = tmp_path / "encoding-parameters.zip"
    with zipfile.ZipFile(cached_zip, "w") as zf:
        zf.writestr("algo/../escaped.txt", "bad")

    monkeypatch.setattr(pyhotvect_module, "copy_or_link", lambda src, dst: None)
    monkeypatch.setattr(pyhotvect_module, "as_locally_available_content", lambda *_args, **_kwargs: str(cached_zip))

    escaped = Path(pipeline.output_path()).parent / "escaped.txt"
    with pytest.raises(ValueError, match="outside base directory"):
        pipeline.package_encode_parameters()

    assert not escaped.exists()


def test_train_preserves_canonical_encoded_directory_for_part_files(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "training_command": ("echo {{ encoded_data_file_path }} {{ encoded_schema_description_file_path }}")
        },
    )
    encoded_dir = Path(pipeline.encoded_data_file_path())
    encoded_dir.mkdir(parents=True, exist_ok=True)
    (encoded_dir / "part-00000.tfrecord").write_text("first")
    schema_path = Path(pipeline.encoded_schema_description_file_path())
    schema_path.write_text("schema")

    pipeline._stream_output_to_stage_log = MagicMock()

    pipeline.train()

    cmd = pipeline._stream_output_to_stage_log.call_args.kwargs["cmd"]
    train_command = cmd[2]
    training_view_dir = Path(pipeline.output_path()) / "train_scratch_dir" / "encoded-train-input"
    assert str(encoded_dir) in train_command
    assert str(schema_path) in train_command
    assert str(training_view_dir) not in train_command
    assert not training_view_dir.exists()
