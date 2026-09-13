import copy
import json
import re
from collections.abc import Iterable
from pathlib import Path
from typing import Any

_PROTECTED_FIELDS = {"algorithm_name", "algorithm_version"}
_ALGORITHM_DEFINITION_FIELDS = {
    "algorithm_name",
    "algorithm_version",
    "hyperparameter_version",
    "hotvect_version",
    "dependencies",
    "generator_factory_classname",
    "decoder_factory_classname",
    "transformer_factory_classname",
    "vectorizer_factory_classname",
    "reward_function_factory_classname",
    "encoder_factory_classname",
    "algorithm_factory_classname",
    "transformer_parameters",
    "vectorizer_parameters",
    "train_decoder_parameters",
    "test_decoder_parameters",
    "algorithm_parameters",
    "catboost_options",
    "train_data_spec",
    "test_data_spec",
    "prediction_spec",
    "performance_data_spec",
    "train_data_prefix",
    "test_data_prefix",
    "number_of_training_days",
    "training_lag_days",
    "training_command",
    "training_container",
    "git_describe",
    "source_data",
    "state_output_filename",
    "hotvect_execution_parameters",
    "sagemaker_training_job_definition",
}
_ALGORITHM_ID_PATTERN = re.compile(r"^([\w\-_]+)(@[\w\-.]+)?$")
_ALGORITHM_OVERRIDE_METADATA_FIELDS = frozenset({"supplied", "files", "fields", "reasons"})
_ALGORITHM_OVERRIDE_METADATA_LIST_FIELDS = frozenset({"files", "fields", "reasons"})


def _normalize_dependency_name(algorithm_id: str) -> str:
    match = _ALGORITHM_ID_PATTERN.match(algorithm_id)
    if not match:
        raise ValueError(
            f"Specified algorithm name {algorithm_id} does not match pattern {_ALGORITHM_ID_PATTERN.pattern}"
        )
    return match.group(1)


def _normalize_dependency_override_name(algorithm_id: str, *, offline: bool) -> str:
    child_name = _normalize_dependency_name(algorithm_id)
    if not offline and child_name != algorithm_id:
        raise ValueError(f"Dependency override key {algorithm_id} must be an unversioned dependency name")
    return child_name


def apply_algorithm_definition_override(
    base_definition: dict[str, Any], override: dict[str, Any] | None, *, offline: bool = False
) -> dict[str, Any]:
    if override is None:
        return copy.deepcopy(base_definition)
    if not isinstance(base_definition, dict):
        raise ValueError(f"Base algorithm definition must be a JSON object, got {type(base_definition).__name__}")
    if not isinstance(override, dict):
        raise ValueError(f"Algorithm definition override must be a JSON object, got {type(override).__name__}")

    merged = copy.deepcopy(base_definition)
    _merge_algorithm_definition_object(
        merged,
        override,
        validate_dependencies_against_base=True,
        offline=offline,
    )
    return merged


def merge_algorithm_definition_override_fragments(
    base_override: dict[str, Any] | None,
    extra_override: dict[str, Any] | None,
    *,
    offline: bool = False,
) -> dict[str, Any]:
    merged = copy.deepcopy(base_override) if base_override is not None else {}
    if not isinstance(merged, dict):
        raise ValueError(
            f"Base algorithm definition override fragment must be a JSON object, got {type(merged).__name__}"
        )
    _validate_override_fragment_dependency_scopes(merged, offline=offline)
    if extra_override is None:
        return merged
    if not isinstance(extra_override, dict):
        raise ValueError(
            f"Algorithm definition override fragment must be a JSON object, got {type(extra_override).__name__}"
        )
    _validate_override_fragment_dependency_scopes(extra_override, offline=offline)
    return _merge_override_fragment_objects(merged, extra_override, offline=offline)


def _override_field_paths(value: Any, prefix: str = "") -> list[str]:
    if not isinstance(value, dict):
        return [prefix] if prefix else []
    paths: list[str] = []
    for key in sorted(value):
        child_prefix = f"{prefix}.{key}" if prefix else str(key)
        child = value[key]
        if isinstance(child, dict) and child:
            paths.extend(_override_field_paths(child, child_prefix))
        else:
            paths.append(child_prefix)
    return paths


def _unique_strings(values: Iterable[str] | None) -> list[str]:
    if values is None:
        return []
    return list(dict.fromkeys(value for value in values if value))


def validate_algorithm_override_metadata(metadata: dict[str, Any] | None) -> dict[str, Any]:
    if metadata is None:
        return {}
    if not isinstance(metadata, dict):
        raise ValueError(f"Algorithm override metadata must be a JSON object, got {type(metadata).__name__}")

    unsupported_fields = sorted(set(metadata) - _ALGORITHM_OVERRIDE_METADATA_FIELDS)
    if unsupported_fields:
        raise ValueError(f"Unsupported algorithm override metadata fields: {', '.join(unsupported_fields)}")

    supplied = metadata.get("supplied")
    if supplied is not None and not isinstance(supplied, bool):
        raise ValueError("Algorithm override metadata field 'supplied' must be a boolean")

    validated_metadata = dict(metadata)
    for field_name in _ALGORITHM_OVERRIDE_METADATA_LIST_FIELDS:
        values = metadata.get(field_name)
        if values is None:
            continue
        if isinstance(values, (str, bytes)) or not isinstance(values, Iterable):
            raise ValueError(f"Algorithm override metadata field '{field_name}' must be a list of strings")
        values = list(values)
        if any(not isinstance(value, str) for value in values):
            raise ValueError(f"Algorithm override metadata field '{field_name}' must be a list of strings")
        validated_metadata[field_name] = values

    return validated_metadata


def build_algorithm_override_metadata(
    override: dict[str, Any] | None,
    *,
    supplied: bool | None = None,
    files: Iterable[str] | None = None,
    fields: Iterable[str] | None = None,
    reasons: Iterable[str] | None = None,
) -> dict[str, Any]:
    supplied_value = override is not None if supplied is None else supplied
    metadata: dict[str, Any] = {"supplied": supplied_value}
    field_values = _unique_strings(
        [
            *_override_field_paths(override or {}),
            *_unique_strings(fields),
        ]
    )
    if field_values:
        metadata["fields"] = field_values
    file_values = _unique_strings(files)
    if file_values:
        metadata["files"] = file_values
    reason_values = _unique_strings(reasons)
    if reason_values:
        metadata["reasons"] = reason_values
    elif supplied_value:
        metadata["reasons"] = ["Algorithm definition override fragment was supplied."]
    return metadata


def _merge_algorithm_definition_object(
    target: dict[str, Any],
    patch: dict[str, Any],
    *,
    validate_dependencies_against_base: bool,
    offline: bool,
) -> None:
    _validate_override_fields(target, patch)
    for field_name, patch_value in patch.items():
        if field_name in _PROTECTED_FIELDS:
            raise ValueError(f"Algorithm definition override must not contain identity field: {field_name}")
        if field_name == "dependencies":
            _merge_dependencies(
                target,
                patch_value,
                validate_dependencies_against_base=validate_dependencies_against_base,
                offline=offline,
            )
        else:
            _merge_generic_field(target, field_name, patch_value)


def _validate_override_fields(target: dict[str, Any], patch: dict[str, Any]) -> None:
    unknown = sorted(
        field_name
        for field_name in patch
        if field_name not in target and field_name not in _ALGORITHM_DEFINITION_FIELDS
    )
    if unknown:
        raise ValueError(f"Unknown algorithm definition override fields: {unknown}")


def _merge_generic_field(target: dict[str, Any], field_name: str, patch_value: Any) -> None:
    if patch_value is None:
        target.pop(field_name, None)
        return

    existing = target.get(field_name)
    if isinstance(existing, dict) and isinstance(patch_value, dict):
        _merge_generic_object(existing, patch_value)
    else:
        target[field_name] = copy.deepcopy(patch_value)


def _merge_generic_object(target: dict[str, Any], patch: dict[str, Any]) -> None:
    for field_name, patch_value in patch.items():
        if patch_value is None:
            target.pop(field_name, None)
            continue

        existing = target.get(field_name)
        if isinstance(existing, dict) and isinstance(patch_value, dict):
            _merge_generic_object(existing, patch_value)
        else:
            target[field_name] = copy.deepcopy(patch_value)


def _merge_dependencies(
    target: dict[str, Any],
    patch_value: Any,
    *,
    validate_dependencies_against_base: bool,
    offline: bool,
) -> None:
    if not isinstance(patch_value, dict):
        raise ValueError("dependencies override must be a JSON object keyed by child algorithm name")

    merged_dependencies: dict[str, tuple[str, dict[str, Any]]] = (
        _normalize_declared_dependencies(target.get("dependencies"))
        if validate_dependencies_against_base
        else _normalize_override_fragment_dependencies(target.get("dependencies"), offline=offline)
    )

    for child_key, child_patch in patch_value.items():
        child_name = _normalize_dependency_override_name(child_key, offline=offline)
        if validate_dependencies_against_base and child_name not in merged_dependencies:
            raise ValueError(f"Override references unknown dependency: {child_name}")
        if not isinstance(child_patch, dict):
            raise ValueError(f"Override for dependency {child_name} must be a JSON object")
        if "scope" in child_patch:
            raise ValueError(f"Override for dependency {child_name} must not declare scope")

        existing = merged_dependencies.get(child_name)
        declaration_key, base_override = existing if existing is not None else (child_key, {})
        is_static_shared = existing is not None and base_override.get("scope") == "shared"
        if existing is not None and "scope" in base_override and child_patch and (not offline or not is_static_shared):
            raise ValueError(f"Override for scoped dependency {child_name} must be empty")
        if offline and is_static_shared:
            base_override.pop("scope")
            declaration_key = child_name
        elif offline and existing is None:
            declaration_key = child_name
        merged_dependencies[child_name] = (
            declaration_key,
            _merge_override_fragment_objects(base_override, child_patch, offline=offline),
        )

    target["dependencies"] = {
        declaration_key: declaration for declaration_key, declaration in merged_dependencies.values()
    }


def _merge_override_fragment_objects(
    base_override: dict[str, Any], extra_override: dict[str, Any], *, offline: bool
) -> dict[str, Any]:
    merged = copy.deepcopy(base_override)
    _merge_algorithm_definition_object(
        merged,
        extra_override,
        validate_dependencies_against_base=False,
        offline=offline,
    )
    return merged


def _validate_override_fragment_dependency_scopes(fragment: dict[str, Any], *, offline: bool) -> None:
    dependencies = fragment.get("dependencies")
    if not isinstance(dependencies, dict):
        return
    for child_key, child_override in dependencies.items():
        if not isinstance(child_override, dict):
            continue
        child_name = _normalize_dependency_override_name(child_key, offline=offline)
        if "scope" in child_override:
            raise ValueError(f"Override for dependency {child_name} must not declare scope")
        _validate_override_fragment_dependency_scopes(child_override, offline=offline)


def _normalize_declared_dependencies(base_dependencies: Any) -> dict[str, tuple[str, dict[str, Any]]]:
    normalized: dict[str, tuple[str, dict[str, Any]]] = {}
    if base_dependencies is None:
        return normalized

    if isinstance(base_dependencies, list):
        for dependency in base_dependencies:
            if not isinstance(dependency, str):
                raise ValueError(f"Dependency entries must be strings but found {dependency!r}")
            child_name = _normalize_dependency_name(dependency)
            if child_name in normalized:
                raise ValueError(f"Dependency {dependency} is declared more than once")
            normalized[child_name] = (dependency, {})
        return normalized

    if not isinstance(base_dependencies, dict):
        raise ValueError(f"dependencies must be an array or object but found {type(base_dependencies).__name__}")

    for child_key, child_override in base_dependencies.items():
        child_name = _normalize_dependency_name(child_key)
        if not isinstance(child_override, dict):
            raise ValueError(f"Embedded dependency override for {child_name} must be a JSON object")
        previous = normalized.get(child_name)
        if previous is not None:
            raise ValueError(
                f"Dependencies {previous[0]} and {child_key} resolve to the same logical dependency name {child_name}"
            )
        normalized[child_name] = (child_key, copy.deepcopy(child_override))
    return normalized


def _normalize_override_fragment_dependencies(
    base_dependencies: Any, *, offline: bool
) -> dict[str, tuple[str, dict[str, Any]]]:
    normalized: dict[str, tuple[str, dict[str, Any]]] = {}
    if base_dependencies is None:
        return normalized
    if not isinstance(base_dependencies, dict):
        raise ValueError("dependencies override fragment must be a JSON object")

    for child_key, child_override in base_dependencies.items():
        child_name = _normalize_dependency_override_name(child_key, offline=offline)
        if not isinstance(child_override, dict):
            raise ValueError(f"Embedded dependency override for {child_name} must be a JSON object")
        normalized[child_name] = (child_name if offline else child_key, copy.deepcopy(child_override))
    return normalized


def load_algorithm_definition_override_fragment(
    algorithm_name: str,
    algorithm_override: str | None,
) -> dict[str, Any] | None:
    if not algorithm_override:
        return None

    with open(algorithm_override, encoding="utf-8") as f:
        override = json.load(f)
    if not isinstance(override, dict):
        raise ValueError(
            f"--algorithm-override must be a JSON object, got {type(override).__name__}: {algorithm_override}"
        )
    identity_fields = sorted(_PROTECTED_FIELDS.intersection(override))
    if identity_fields:
        raise ValueError(
            "--algorithm-override must be an override fragment, not a full algorithm definition. "
            f"Remove {', '.join(identity_fields)} from {algorithm_override!r}; the base definition is loaded from the "
            f"algorithm JAR for {algorithm_name!r} and the override is merged into it."
        )
    return override


def load_effective_algorithm_definition(
    algorithm_jar: str,
    algorithm_name: str,
    algorithm_override: str | None,
    *,
    offline: bool = False,
) -> dict[str, Any]:
    from hotvect.utils import read_algorithm_definition_from_jar

    effective = read_algorithm_definition_from_jar(algorithm_name, Path(algorithm_jar))
    override = load_algorithm_definition_override_fragment(algorithm_name, algorithm_override)
    if not override:
        return effective

    effective = apply_algorithm_definition_override(effective, override, offline=offline)
    if effective.get("algorithm_name") != algorithm_name:
        raise ValueError(
            f"--algorithm-override changed algorithm_name to {effective.get('algorithm_name')}; this is not supported"
        )
    return effective


def serialize_effective_algorithm_definition(algorithm_definition: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(algorithm_definition, dict) or not algorithm_definition:
        raise ValueError("Effective algorithm definition must be a non-empty JSON object")
    return copy.deepcopy(algorithm_definition)


def parse_effective_algorithm_definition_payload(payload: Any) -> dict[str, Any]:
    if not isinstance(payload, dict) or not payload:
        raise ValueError("s3_uri_algorithm_definition must point to a non-empty JSON object")
    return copy.deepcopy(payload)
