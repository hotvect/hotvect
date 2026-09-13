import json
import pathlib
import re

import pytest

from hotvect.algorithm_definition_overrides import (
    _ALGORITHM_DEFINITION_FIELDS,
    apply_algorithm_definition_override,
    build_algorithm_override_metadata,
    load_algorithm_definition_override_fragment,
    merge_algorithm_definition_override_fragments,
    validate_algorithm_override_metadata,
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


def test_apply_override_rejects_same_identity_field_values():
    base = {
        "algorithm_name": "algo",
        "algorithm_version": "10.0.0",
        "algorithm_factory_classname": "com.example.AlgoFactory",
        "training_lag_days": 7,
    }

    with pytest.raises(ValueError, match="must not contain identity field: algorithm_name"):
        apply_algorithm_definition_override(
            base,
            {
                "algorithm_name": "algo",
                "algorithm_version": "10.0.0",
                "training_lag_days": 14,
            },
        )


@pytest.mark.parametrize(
    ("identity_field", "identity_value"),
    [("algorithm_name", "algo"), ("algorithm_version", "10.0.0")],
)
def test_loaded_override_rejects_identity_fields_even_when_they_match(tmp_path, identity_field, identity_value):
    override_path = tmp_path / "override.json"
    override_path.write_text(json.dumps({identity_field: identity_value, "training_lag_days": 14}))

    with pytest.raises(ValueError, match="override fragment, not a full algorithm definition"):
        load_algorithm_definition_override_fragment("algo", str(override_path))


def test_apply_override_rejects_unknown_top_level_field():
    base = {
        "algorithm_name": "algo",
        "algorithm_version": "10.0.0",
        "algorithm_factory_classname": "com.example.AlgoFactory",
    }

    with pytest.raises(ValueError, match="Unknown algorithm definition override fields: .*algoritm_parameters"):
        apply_algorithm_definition_override(base, {"algoritm_parameters": {"threshold": 0.7}})


def test_apply_override_allows_extension_field_already_declared_by_base():
    base = {
        "algorithm_name": "algo",
        "algorithm_version": "10.0.0",
        "algorithm_factory_classname": "com.example.AlgoFactory",
        "custom_runtime_settings": {"threshold": 0.5},
    }

    effective = apply_algorithm_definition_override(base, {"custom_runtime_settings": {"threshold": 0.7}})

    assert effective["custom_runtime_settings"] == {"threshold": 0.7}


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


def test_apply_override_preserves_dependency_policies_and_versioned_keys():
    base = {
        "algorithm_name": "root",
        "algorithm_version": "1",
        "algorithm_factory_classname": "example.RootFactory",
        "dependencies": {
            "candidate-slot": {"scope": "slot"},
            "shared-child@2": {"scope": "shared"},
        },
    }

    effective = apply_algorithm_definition_override(
        base,
        {"dependencies": {"candidate-slot": {}, "shared-child": {}}},
    )

    assert effective["dependencies"] == {
        "candidate-slot": {"scope": "slot"},
        "shared-child@2": {"scope": "shared"},
    }


@pytest.mark.parametrize(
    "dependencies",
    [
        {"foo@1": {"scope": "shared"}, "foo": {}},
        {"foo": {}, "foo@1": {"scope": "shared"}},
    ],
)
def test_apply_override_rejects_colliding_declared_dependency_names(dependencies):
    base = {
        "algorithm_name": "root",
        "algorithm_version": "1",
        "algorithm_factory_classname": "example.RootFactory",
        "dependencies": dependencies,
    }

    with pytest.raises(
        ValueError,
        match=r"Dependencies (foo@1 and foo|foo and foo@1) resolve to the same logical dependency name foo",
    ):
        apply_algorithm_definition_override(base, {"dependencies": {"foo": {}}})


def test_apply_override_rejects_duplicate_declared_list_dependencies():
    base = {
        "algorithm_name": "root",
        "algorithm_version": "1",
        "algorithm_factory_classname": "example.RootFactory",
        "dependencies": ["foo", "foo"],
    }

    with pytest.raises(ValueError, match="Dependency foo is declared more than once"):
        apply_algorithm_definition_override(base, {"dependencies": {"foo": {}}})


def test_apply_override_rejects_version_qualified_dependency_key():
    base = {
        "algorithm_name": "root",
        "algorithm_version": "1",
        "algorithm_factory_classname": "example.RootFactory",
        "dependencies": {"shared-child@2": {"scope": "shared"}},
    }

    with pytest.raises(ValueError, match="shared-child@999 must be an unversioned dependency name"):
        apply_algorithm_definition_override(base, {"dependencies": {"shared-child@999": {}}})


def test_merge_override_fragments_rejects_version_qualified_dependency_key():
    with pytest.raises(ValueError, match="shared-child@2 must be an unversioned dependency name"):
        merge_algorithm_definition_override_fragments(
            {"dependencies": {"shared-child@2": {}}},
            {},
        )


def test_apply_override_rejects_dependency_scope():
    base = {
        "algorithm_name": "root",
        "algorithm_version": "1",
        "algorithm_factory_classname": "example.RootFactory",
        "dependencies": {"candidate-slot": {"scope": "slot"}},
    }

    with pytest.raises(ValueError, match="must not declare scope"):
        apply_algorithm_definition_override(
            base,
            {"dependencies": {"candidate-slot": {"scope": None}}},
        )


def test_apply_override_rejects_nonempty_overrides_for_scoped_dependencies():
    base = {
        "algorithm_name": "root",
        "algorithm_version": "1",
        "algorithm_factory_classname": "example.RootFactory",
        "dependencies": {
            "candidate-slot": {"scope": "slot"},
            "shared-child@2": {"scope": "shared"},
        },
    }

    with pytest.raises(ValueError, match="scoped dependency candidate-slot must be empty"):
        apply_algorithm_definition_override(
            base,
            {"dependencies": {"candidate-slot": {"algorithm_parameters": {"threshold": 0.7}}}},
        )

    with pytest.raises(ValueError, match="scoped dependency shared-child must be empty"):
        apply_algorithm_definition_override(
            base,
            {"dependencies": {"shared-child": {"algorithm_parameters": {"threshold": 0.7}}}},
        )


def test_merge_override_fragments_rejects_dependency_scope_already_present_in_base_fragment():
    with pytest.raises(ValueError, match="candidate-slot must not declare scope"):
        merge_algorithm_definition_override_fragments(
            {"dependencies": {"candidate-slot": {"scope": "slot"}}},
            {},
        )


def test_offline_override_treats_shared_dependency_as_private_and_ignores_versions():
    base = {
        "algorithm_name": "root",
        "algorithm_version": "1",
        "algorithm_factory_classname": "example.RootFactory",
        "dependencies": {"shared-child@2": {"scope": "shared"}},
    }

    effective = apply_algorithm_definition_override(
        base,
        {"dependencies": {"shared-child@999": {"algorithm_parameters": {"threshold": 0.7}}}},
        offline=True,
    )

    assert effective["dependencies"] == {"shared-child": {"algorithm_parameters": {"threshold": 0.7}}}

    merged = merge_algorithm_definition_override_fragments(
        {"dependencies": {"shared-child@999": {"algorithm_parameters": {"threshold": 0.7}}}},
        {"dependencies": {"shared-child": {"algorithm_parameters": {"limit": 3}}}},
        offline=True,
    )
    assert merged == {"dependencies": {"shared-child": {"algorithm_parameters": {"threshold": 0.7, "limit": 3}}}}


@pytest.mark.parametrize("base_override", [[], "", 0, False])
def test_merge_override_fragments_rejects_falsy_non_object_base_fragment(base_override):
    with pytest.raises(ValueError, match="Base algorithm definition override fragment must be a JSON object"):
        merge_algorithm_definition_override_fragments(base_override, None)


def test_build_algorithm_override_metadata_records_fields_files_and_reasons():
    metadata = build_algorithm_override_metadata(
        {
            "training_lag_days": 7,
            "hotvect_execution_parameters": {
                "with_parameter": "s3://bucket/params.zip",
                "performance-test": {"enabled": False},
            },
        },
        files=["/tmp/override.json"],
        reasons=["Use date-aligned production parameters"],
    )

    assert metadata == {
        "supplied": True,
        "fields": [
            "hotvect_execution_parameters.performance-test.enabled",
            "hotvect_execution_parameters.with_parameter",
            "training_lag_days",
        ],
        "files": ["/tmp/override.json"],
        "reasons": ["Use date-aligned production parameters"],
    }


def test_build_algorithm_override_metadata_records_clean_no_override_status():
    assert build_algorithm_override_metadata(None) == {"supplied": False}


def test_validate_algorithm_override_metadata_rejects_unsupported_fields():
    with pytest.raises(ValueError, match="Unsupported algorithm override metadata fields: algorithm_id"):
        validate_algorithm_override_metadata({"algorithm_id": "candidate-a"})

    with pytest.raises(ValueError, match="Unsupported algorithm override metadata fields: reason"):
        validate_algorithm_override_metadata({"reason": "use production parameters"})


def test_validate_algorithm_override_metadata_normalizes_iterables():
    assert validate_algorithm_override_metadata({"files": ("candidate.override.json",)}) == {
        "files": ["candidate.override.json"]
    }


def _java_algorithm_definition_fields() -> set[str] | None:
    java_source = (
        pathlib.Path(__file__).resolve().parents[4]
        / "hotvect-api/src/main/java/com/hotvect/utils/AlgorithmDefinitionOverrideUtils.java"
    )
    if not java_source.is_file():
        return None
    body = java_source.read_text().split("ALGORITHM_DEFINITION_FIELDS = Set.of(", 1)[1].split(");", 1)[0]
    return set(re.findall(r'"([^"]+)"', body))


def test_python_and_java_algorithm_definition_field_allowlists_agree():
    java_fields = _java_algorithm_definition_fields()
    if java_fields is None:
        pytest.skip("Java sources are not available in this checkout")

    assert java_fields == _ALGORITHM_DEFINITION_FIELDS


def test_apply_override_accepts_recognized_training_backend_fields():
    base = {
        "algorithm_name": "parent-algo",
        "algorithm_version": "10.0.0",
        "algorithm_factory_classname": "com.example.ParentFactory",
    }

    effective = apply_algorithm_definition_override(
        base,
        {
            "catboost_options": {"iterations": 24},
            "training_container": "hotvect-training:10.49.0",
            "git_describe": "v10.49.0-1-gabc1234",
        },
    )

    assert effective["catboost_options"] == {"iterations": 24}
    assert effective["training_container"] == "hotvect-training:10.49.0"
    assert effective["git_describe"] == "v10.49.0-1-gabc1234"
