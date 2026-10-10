from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import unquote, urlparse

from hotvect.offline_task import (
    DirectAlgorithmSource,
    EmsSnapshotSource,
    FixedCompositionSource,
    OfflineAlgorithmSource,
)
from hotvect.s3_utils import download_s3_file, join_s3_uri, require_s3_uri, upload_file_to_s3, upload_json_to_s3

OFFLINE_SOURCE_MANIFEST_SCHEMA_VERSION = 1
OFFLINE_SOURCE_MANIFEST_HYPERPARAMETER = "hotvect_offline_source_manifest_s3_uri"

_ARTIFACT_URI_FIELDS = {
    "jar_uri",
    "uri",
    "absolute_s3_algorithm_jar_path",
    "absolute_s3_algorithm_parameter_path",
}


@dataclass(frozen=True)
class StagedLegacyOneShotSource:
    algorithm_jar_s3_uri: str
    algorithm_definition_s3_uri: str
    parameter_s3_uri: str | None


@dataclass(frozen=True)
class StagedOfflineAlgorithmSource:
    manifest_s3_uri: str
    parameter_s3_uri: str | None


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON object key: {key}")
        result[key] = value
    return result


def _read_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as source:
        value = json.load(source, object_pairs_hook=_reject_duplicate_keys)
    if not isinstance(value, dict):
        raise ValueError(f"Expected a JSON object in {path}")
    return value


def _require_exact_keys(value: dict[str, Any], *, required: set[str], optional: set[str], context: str) -> None:
    missing = required - value.keys()
    if missing:
        raise ValueError(f"{context} is missing required fields: {sorted(missing)}")
    unexpected = value.keys() - required - optional
    if unexpected:
        raise ValueError(f"{context} has unexpected fields: {sorted(unexpected)}")


def _require_string(value: Any, context: str) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{context} must be a non-empty string")
    return value


def _require_string_list(value: Any, context: str) -> list[str]:
    if not isinstance(value, list):
        raise ValueError(f"{context} must be an array")
    result = [_require_string(item, f"{context}[]") for item in value]
    if len(result) != len(set(result)):
        raise ValueError(f"{context} must not contain duplicates")
    return result


def _content_addressed_artifact_uri(path: Path, destination_prefix: str) -> str:
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    return join_s3_uri(destination_prefix, "artifacts", f"{digest}-{path.name}")


def _stage_local_file(path: Path, destination_prefix: str, s3_client: Any) -> str:
    resolved = path.expanduser().resolve(strict=True)
    if not resolved.is_file():
        raise ValueError(f"Offline runtime artifact must be a file: {resolved}")
    destination = _content_addressed_artifact_uri(resolved, destination_prefix)
    upload_file_to_s3(str(resolved), destination, s3_client, fail_fast=True)
    return destination


def _stage_domain_model_jars(
    paths: tuple[Path, ...],
    destination_prefix: str,
    s3_client: Any,
) -> list[str]:
    staged = [_stage_local_file(path, destination_prefix, s3_client) for path in paths]
    if len(staged) != len(set(staged)):
        raise ValueError("Domain-model JARs must resolve to distinct staged artifacts")
    return staged


def _stage_artifact_uri(uri: str, destination_prefix: str, s3_client: Any) -> str:
    parsed = urlparse(uri)
    if parsed.scheme == "s3":
        require_s3_uri(uri)
        return uri
    if parsed.scheme != "file" or parsed.netloc:
        raise ValueError(f"Remote offline artifacts must use s3:// or local file:// URIs: {uri!r}")
    return _stage_local_file(Path(unquote(parsed.path)), destination_prefix, s3_client)


def _stage_document_artifacts(value: Any, destination_prefix: str, s3_client: Any) -> Any:
    if isinstance(value, list):
        return [_stage_document_artifacts(item, destination_prefix, s3_client) for item in value]
    if not isinstance(value, dict):
        return value
    staged: dict[str, Any] = {}
    for key, item in value.items():
        if key in _ARTIFACT_URI_FIELDS and item is not None:
            staged[key] = _stage_artifact_uri(_require_string(item, key), destination_prefix, s3_client)
        else:
            staged[key] = _stage_document_artifacts(item, destination_prefix, s3_client)
    return staged


def stage_legacy_one_shot_source(
    source: DirectAlgorithmSource,
    *,
    destination_prefix: str,
    s3_client: Any,
    effective_algorithm_definition: dict[str, Any],
    parameter_s3_uri: str | None,
) -> StagedLegacyOneShotSource:
    """Stage the direct-source artifacts consumed by Hotvect 10.41.1–10.48.x one-shot images."""

    require_s3_uri(destination_prefix)
    if source.parameter_path is not None and parameter_s3_uri is not None:
        raise ValueError("--parameter-path and --parameter-s3-uri are mutually exclusive")

    algorithm_jar_s3_uri = _stage_local_file(source.algorithm_jar_path, destination_prefix, s3_client)
    algorithm_definition_s3_uri = join_s3_uri(destination_prefix, "effective_algorithm_definition.json")
    upload_json_to_s3(
        effective_algorithm_definition,
        algorithm_definition_s3_uri,
        s3_client,
        fail_fast=True,
    )

    selected_parameter_s3_uri = parameter_s3_uri
    if source.parameter_path is not None:
        selected_parameter_s3_uri = _stage_local_file(source.parameter_path, destination_prefix, s3_client)
    if selected_parameter_s3_uri is not None:
        require_s3_uri(selected_parameter_s3_uri)

    return StagedLegacyOneShotSource(
        algorithm_jar_s3_uri=algorithm_jar_s3_uri,
        algorithm_definition_s3_uri=algorithm_definition_s3_uri,
        parameter_s3_uri=selected_parameter_s3_uri,
    )


def stage_offline_algorithm_source(
    source: OfflineAlgorithmSource,
    *,
    destination_prefix: str,
    s3_client: Any,
    effective_algorithm_definition: dict[str, Any] | None,
    parameter_s3_uri: str | None,
) -> StagedOfflineAlgorithmSource:
    """Stage one complete offline source and return its immutable artifact URIs."""

    require_s3_uri(destination_prefix)
    selected_parameter_s3_uri = None
    if isinstance(source, DirectAlgorithmSource):
        if effective_algorithm_definition is None:
            raise ValueError("A direct offline source requires its effective algorithm definition")
        if source.parameter_path is not None and parameter_s3_uri is not None:
            raise ValueError("--parameter-path and --parameter-s3-uri are mutually exclusive")
        algorithm_jar_s3_uri = _stage_local_file(source.algorithm_jar_path, destination_prefix, s3_client)
        selected_parameter_s3_uri = parameter_s3_uri
        if source.parameter_path is not None:
            selected_parameter_s3_uri = _stage_local_file(source.parameter_path, destination_prefix, s3_client)
        if selected_parameter_s3_uri is not None:
            require_s3_uri(selected_parameter_s3_uri)
        manifest_source: dict[str, Any] = {
            "kind": "direct",
            "algorithm_jar_s3_uri": algorithm_jar_s3_uri,
            "algorithm_definition": effective_algorithm_definition,
            "domain_model_jar_s3_uris": _stage_domain_model_jars(
                source.domain_model_jars,
                destination_prefix,
                s3_client,
            ),
        }
        if selected_parameter_s3_uri is not None:
            manifest_source["parameter_s3_uri"] = selected_parameter_s3_uri
    elif isinstance(source, FixedCompositionSource):
        if effective_algorithm_definition is not None:
            raise ValueError("A fixed composition must not provide a direct algorithm definition")
        if parameter_s3_uri is not None:
            raise ValueError("--parameter-s3-uri cannot be combined with --composition")
        composition = _stage_document_artifacts(_read_json(source.composition_path), destination_prefix, s3_client)
        composition_s3_uri = join_s3_uri(destination_prefix, "composition.json")
        upload_json_to_s3(composition, composition_s3_uri, s3_client, fail_fast=True)
        manifest_source = {
            "kind": "fixed",
            "composition_s3_uri": composition_s3_uri,
            "domain_model_jar_s3_uris": _stage_domain_model_jars(
                source.domain_model_jars,
                destination_prefix,
                s3_client,
            ),
        }
    elif isinstance(source, EmsSnapshotSource):
        if effective_algorithm_definition is not None:
            raise ValueError("An EMS snapshot must not provide a direct algorithm definition")
        if parameter_s3_uri is not None:
            raise ValueError("--parameter-s3-uri cannot be combined with --ems-slot")
        state = _stage_document_artifacts(_read_json(source.state_path), destination_prefix, s3_client)
        state_s3_uri = join_s3_uri(destination_prefix, "ems-state.json")
        upload_json_to_s3(state, state_s3_uri, s3_client, fail_fast=True)
        manifest_source = {
            "kind": "ems",
            "root_slot": source.root_slot,
            "state_s3_uri": state_s3_uri,
            "assignment_key_json_pointer": source.assignment_key_json_pointer,
            "domain_model_jar_s3_uris": _stage_domain_model_jars(
                source.domain_model_jars,
                destination_prefix,
                s3_client,
            ),
        }
    else:
        raise TypeError(f"Unsupported offline algorithm source: {type(source).__name__}")

    manifest = {
        "schema_version": OFFLINE_SOURCE_MANIFEST_SCHEMA_VERSION,
        "source": manifest_source,
    }
    manifest_s3_uri = join_s3_uri(destination_prefix, "manifest.json")
    upload_json_to_s3(manifest, manifest_s3_uri, s3_client, fail_fast=True)
    return StagedOfflineAlgorithmSource(
        manifest_s3_uri=manifest_s3_uri,
        parameter_s3_uri=selected_parameter_s3_uri,
    )


def _download_artifact(uri: str, destination: Path, s3_client: Any) -> Path:
    require_s3_uri(uri)
    destination.parent.mkdir(parents=True, exist_ok=True)
    download_s3_file(uri, destination, s3_client)
    return destination


def _s3_basename(uri: str) -> str:
    _, key = require_s3_uri(uri)
    name = Path(key).name
    if not name:
        raise ValueError(f"S3 artifact URI must identify a file: {uri!r}")
    return name


def _materialize_domain_model_jars(uris: Any, scratch: Path, s3_client: Any) -> tuple[Path, ...]:
    selected = _require_string_list(uris, "source.domain_model_jar_s3_uris")
    return tuple(
        _download_artifact(uri, scratch / "domain-model-jars" / f"{index}-{_s3_basename(uri)}", s3_client)
        for index, uri in enumerate(selected)
    )


def materialize_offline_algorithm_source(
    manifest_s3_uri: str,
    *,
    scratch: Path,
    s3_client: Any,
) -> OfflineAlgorithmSource:
    """Download a staged manifest's control files and build the normal local source model."""

    manifest_path = _download_artifact(manifest_s3_uri, scratch / "manifest.json", s3_client)
    manifest = _read_json(manifest_path)
    _require_exact_keys(manifest, required={"schema_version", "source"}, optional=set(), context="manifest")
    if (
        type(manifest["schema_version"]) is not int
        or manifest["schema_version"] != OFFLINE_SOURCE_MANIFEST_SCHEMA_VERSION
    ):
        raise ValueError(f"Unsupported offline source manifest schema version: {manifest['schema_version']!r}")
    source = manifest["source"]
    if not isinstance(source, dict):
        raise ValueError("manifest.source must be an object")
    kind = _require_string(source.get("kind"), "manifest.source.kind")

    if kind == "direct":
        _require_exact_keys(
            source,
            required={
                "kind",
                "algorithm_jar_s3_uri",
                "algorithm_definition",
                "domain_model_jar_s3_uris",
            },
            optional={"parameter_s3_uri"},
            context="direct manifest source",
        )
        definition = source["algorithm_definition"]
        if not isinstance(definition, dict):
            raise ValueError("direct manifest source.algorithm_definition must be an object")
        jar_uri = _require_string(source["algorithm_jar_s3_uri"], "source.algorithm_jar_s3_uri")
        jar = _download_artifact(jar_uri, scratch / "direct" / _s3_basename(jar_uri), s3_client)
        definition_path = scratch / "direct" / "effective_algorithm_definition.json"
        definition_path.write_text(json.dumps(definition, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        parameter = None
        if "parameter_s3_uri" in source:
            parameter_uri = _require_string(source["parameter_s3_uri"], "source.parameter_s3_uri")
            parameter = _download_artifact(parameter_uri, scratch / "direct" / _s3_basename(parameter_uri), s3_client)
        domain_jars = _materialize_domain_model_jars(
            source["domain_model_jar_s3_uris"],
            scratch / "direct",
            s3_client,
        )
        return DirectAlgorithmSource(jar, str(definition_path), parameter, domain_jars)

    if kind == "fixed":
        _require_exact_keys(
            source,
            required={"kind", "composition_s3_uri", "domain_model_jar_s3_uris"},
            optional=set(),
            context="fixed manifest source",
        )
        composition_uri = _require_string(source["composition_s3_uri"], "source.composition_s3_uri")
        composition = _download_artifact(composition_uri, scratch / "fixed" / "composition.json", s3_client)
        domain_jars = _materialize_domain_model_jars(source["domain_model_jar_s3_uris"], scratch / "fixed", s3_client)
        return FixedCompositionSource(composition, domain_jars)

    if kind == "ems":
        _require_exact_keys(
            source,
            required={
                "kind",
                "root_slot",
                "state_s3_uri",
                "assignment_key_json_pointer",
                "domain_model_jar_s3_uris",
            },
            optional=set(),
            context="EMS manifest source",
        )
        state_uri = _require_string(source["state_s3_uri"], "source.state_s3_uri")
        state = _download_artifact(state_uri, scratch / "ems" / "state.json", s3_client)
        domain_jars = _materialize_domain_model_jars(source["domain_model_jar_s3_uris"], scratch / "ems", s3_client)
        return EmsSnapshotSource(
            _require_string(source["root_slot"], "source.root_slot"),
            state,
            _require_string(source["assignment_key_json_pointer"], "source.assignment_key_json_pointer"),
            domain_jars,
        )

    raise ValueError(f"Unsupported offline source kind: {kind!r}")
