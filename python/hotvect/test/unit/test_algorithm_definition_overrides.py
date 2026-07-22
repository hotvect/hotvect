import pytest

from hotvect.algorithm_definition_overrides import (
    apply_algorithm_definition_override,
    load_algorithm_definition_override_fragment,
    merge_algorithm_definition_override_fragments,
)


def test_apply_override_preserves_sibling_dependencies_from_array_base():
    base = {
        "algorithm_name": "parent-algo",
        "algorithm_version": "10.0.0",
        "algorithm_factory_classname": "com.example.ParentFactory",
        "dependencies": ["child-a", "child-b"],
    }

    effective = apply_algorithm_definition_override(
        base,
        {"dependencies": {"child-a": {"number_of_training_days": 2}}},
    )

    assert effective["dependencies"] == {
        "child-a": {"number_of_training_days": 2},
        "child-b": {},
    }
    assert base["dependencies"] == ["child-a", "child-b"]


def test_apply_override_rejects_unknown_dependency_name():
    base = {
        "algorithm_name": "parent-algo",
        "algorithm_version": "10.0.0",
        "algorithm_factory_classname": "com.example.ParentFactory",
        "dependencies": ["child-a"],
    }

    with pytest.raises(ValueError, match="unknown dependency"):
        apply_algorithm_definition_override(base, {"dependencies": {"child-b": {"number_of_training_days": 2}}})


def test_apply_override_deletes_leaf_fields_on_null():
    base = {
        "algorithm_name": "algo",
        "algorithm_version": "10.0.0",
        "algorithm_factory_classname": "com.example.AlgoFactory",
        "test_data_prefix": "test-prefix",
        "hotvect_execution_parameters": {"predict": {"enabled": True, "samples": 50}},
    }

    effective = apply_algorithm_definition_override(
        base,
        {
            "test_data_prefix": None,
            "hotvect_execution_parameters": {"predict": {"samples": None}},
        },
    )

    assert "test_data_prefix" not in effective
    assert effective["hotvect_execution_parameters"]["predict"] == {"enabled": True}


def test_apply_override_allows_leaf_type_replacement():
    base = {
        "algorithm_name": "algo",
        "algorithm_version": "10.0.0",
        "algorithm_factory_classname": "com.example.AlgoFactory",
        "training_lag_days": 7,
    }

    effective = apply_algorithm_definition_override(base, {"training_lag_days": "7"})

    assert effective["training_lag_days"] == "7"


def test_apply_override_rejects_protected_fields():
    base = {
        "algorithm_name": "algo",
        "algorithm_version": "10.0.0",
        "algorithm_factory_classname": "com.example.AlgoFactory",
    }

    with pytest.raises(ValueError, match="algorithm_name"):
        apply_algorithm_definition_override(base, {"algorithm_name": "other"})


def test_apply_override_allows_same_protected_field_values():
    base = {
        "algorithm_name": "algo",
        "algorithm_version": "10.0.0",
        "algorithm_factory_classname": "com.example.AlgoFactory",
        "training_lag_days": 7,
    }

    effective = apply_algorithm_definition_override(
        base,
        {
            "algorithm_name": "algo",
            "algorithm_version": "10.0.0",
            "training_lag_days": 14,
        },
    )

    assert effective["algorithm_name"] == "algo"
    assert effective["algorithm_version"] == "10.0.0"
    assert effective["training_lag_days"] == 14


def test_loaded_override_rejects_algorithm_name_even_when_it_matches(tmp_path):
    override_path = tmp_path / "override.json"
    override_path.write_text('{"algorithm_name": "algo", "training_lag_days": 14}')

    with pytest.raises(ValueError, match="override fragment, not a full algorithm definition"):
        load_algorithm_definition_override_fragment("algo", str(override_path))


def test_merge_override_fragments_allows_grandchild_patch_without_base_definition():
    base_fragment = {"dependencies": {"grandchild-a": {"number_of_training_days": 1}}}
    extra_patch = {"dependencies": {"grandchild-b": {"training_lag_days": 2}}}

    merged = merge_algorithm_definition_override_fragments(base_fragment, extra_patch)

    assert merged == {
        "dependencies": {
            "grandchild-a": {"number_of_training_days": 1},
            "grandchild-b": {"training_lag_days": 2},
        }
    }
    assert base_fragment == {"dependencies": {"grandchild-a": {"number_of_training_days": 1}}}


def test_apply_override_rejects_null_child_dependency_patch():
    base = {
        "algorithm_name": "parent-algo",
        "algorithm_version": "10.0.0",
        "algorithm_factory_classname": "com.example.ParentFactory",
        "dependencies": ["child-a"],
    }

    with pytest.raises(ValueError, match="dependency child-a"):
        apply_algorithm_definition_override(base, {"dependencies": {"child-a": None}})
