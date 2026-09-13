from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

import pytest

import hotvect.offline_source_manifest as manifest_module
from hotvect.offline_source_manifest import (
    materialize_offline_algorithm_source,
    stage_legacy_one_shot_source,
    stage_offline_algorithm_source,
)
from hotvect.offline_task import DirectAlgorithmSource, EmsSnapshotSource, FixedCompositionSource


class StagingUploads:
    def __init__(self, monkeypatch: pytest.MonkeyPatch) -> None:
        self.files: list[tuple[Path, str]] = []
        self.documents: dict[str, dict[str, Any]] = {}
        monkeypatch.setattr(manifest_module, "upload_file_to_s3", self.upload_file)
        monkeypatch.setattr(manifest_module, "upload_json_to_s3", self.upload_json)

    def upload_file(
        self,
        local_file_path: str,
        s3_target_uri: str,
        _s3_client: Any,
        *,
        fail_fast: bool,
    ) -> None:
        assert fail_fast
        self.files.append((Path(local_file_path), s3_target_uri))

    def upload_json(
        self,
        payload: dict[str, Any],
        s3_target_uri: str,
        _s3_client: Any,
        *,
        fail_fast: bool,
    ) -> None:
        assert fail_fast
        self.documents[s3_target_uri] = payload


def _artifact_uri(path: Path) -> str:
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    return f"s3://bucket/run/offline-source/artifacts/{digest}-{path.name}"


def test_stage_parameterless_direct_source(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    algorithm_jar = tmp_path / "algorithm.jar"
    algorithm_jar.write_bytes(b"algorithm")
    uploads = StagingUploads(monkeypatch)
    definition = {"algorithm_name": "ranker", "algorithm_version": "1"}

    result = stage_offline_algorithm_source(
        DirectAlgorithmSource(algorithm_jar, "ranker"),
        destination_prefix="s3://bucket/run/offline-source",
        s3_client=object(),
        effective_algorithm_definition=definition,
        parameter_s3_uri=None,
    )

    jar_uri = _artifact_uri(algorithm_jar)
    assert result.manifest_s3_uri == "s3://bucket/run/offline-source/manifest.json"
    assert result.parameter_s3_uri is None
    assert uploads.files == [(algorithm_jar, jar_uri)]
    assert uploads.documents[result.manifest_s3_uri] == {
        "schema_version": 1,
        "source": {
            "kind": "direct",
            "algorithm_jar_s3_uri": jar_uri,
            "algorithm_definition": definition,
            "domain_model_jar_s3_uris": [],
        },
    }


def test_stage_direct_source_with_domain_model_jars(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    algorithm_jar = tmp_path / "algorithm.jar"
    algorithm_jar.write_bytes(b"algorithm")
    domain_jar = tmp_path / "domain.jar"
    domain_jar.write_bytes(b"domain")
    uploads = StagingUploads(monkeypatch)

    result = stage_offline_algorithm_source(
        DirectAlgorithmSource(
            algorithm_jar,
            "ranker",
            domain_model_jars=(domain_jar,),
        ),
        destination_prefix="s3://bucket/run/offline-source",
        s3_client=object(),
        effective_algorithm_definition={"algorithm_name": "ranker"},
        parameter_s3_uri=None,
    )

    assert uploads.files == [
        (algorithm_jar, _artifact_uri(algorithm_jar)),
        (domain_jar, _artifact_uri(domain_jar)),
    ]
    assert uploads.documents[result.manifest_s3_uri]["source"]["domain_model_jar_s3_uris"] == [
        _artifact_uri(domain_jar)
    ]


def test_stage_rejects_duplicate_domain_model_jars(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    algorithm_jar = tmp_path / "algorithm.jar"
    algorithm_jar.write_bytes(b"algorithm")
    domain_jar = tmp_path / "domain.jar"
    domain_jar.write_bytes(b"domain")
    StagingUploads(monkeypatch)

    with pytest.raises(ValueError, match="must resolve to distinct staged artifacts"):
        stage_offline_algorithm_source(
            DirectAlgorithmSource(
                algorithm_jar,
                "ranker",
                domain_model_jars=(domain_jar, domain_jar),
            ),
            destination_prefix="s3://bucket/run/offline-source",
            s3_client=object(),
            effective_algorithm_definition={"algorithm_name": "ranker"},
            parameter_s3_uri=None,
        )


def test_stage_direct_source_with_local_parameter(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    algorithm_jar = tmp_path / "algorithm.jar"
    algorithm_jar.write_bytes(b"algorithm")
    parameter = tmp_path / "parameters.zip"
    parameter.write_bytes(b"parameters")
    uploads = StagingUploads(monkeypatch)

    result = stage_offline_algorithm_source(
        DirectAlgorithmSource(algorithm_jar, "ranker", parameter),
        destination_prefix="s3://bucket/run/offline-source",
        s3_client=object(),
        effective_algorithm_definition={"algorithm_name": "ranker"},
        parameter_s3_uri=None,
    )

    assert uploads.files == [
        (algorithm_jar, _artifact_uri(algorithm_jar)),
        (parameter, _artifact_uri(parameter)),
    ]
    assert result.parameter_s3_uri == _artifact_uri(parameter)
    assert uploads.documents[result.manifest_s3_uri]["source"]["parameter_s3_uri"] == _artifact_uri(parameter)


def test_stage_direct_source_preserves_remote_parameter_uri(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    algorithm_jar = tmp_path / "algorithm.jar"
    algorithm_jar.write_bytes(b"algorithm")
    uploads = StagingUploads(monkeypatch)
    parameter_s3_uri = "s3://published/parameters.zip"

    result = stage_offline_algorithm_source(
        DirectAlgorithmSource(algorithm_jar, "ranker"),
        destination_prefix="s3://bucket/run/offline-source",
        s3_client=object(),
        effective_algorithm_definition={"algorithm_name": "ranker"},
        parameter_s3_uri=parameter_s3_uri,
    )

    assert result.parameter_s3_uri == parameter_s3_uri
    assert uploads.documents[result.manifest_s3_uri]["source"]["parameter_s3_uri"] == parameter_s3_uri


def test_stage_legacy_direct_source_with_local_parameter(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    algorithm_jar = tmp_path / "algorithm.jar"
    algorithm_jar.write_bytes(b"algorithm")
    parameter = tmp_path / "parameters.zip"
    parameter.write_bytes(b"parameters")
    uploads = StagingUploads(monkeypatch)
    definition = {"algorithm_name": "ranker", "algorithm_version": "1"}

    result = stage_legacy_one_shot_source(
        DirectAlgorithmSource(algorithm_jar, "ranker", parameter),
        destination_prefix="s3://bucket/run/offline-source",
        s3_client=object(),
        effective_algorithm_definition=definition,
        parameter_s3_uri=None,
    )

    assert uploads.files == [
        (algorithm_jar, _artifact_uri(algorithm_jar)),
        (parameter, _artifact_uri(parameter)),
    ]
    assert result.algorithm_jar_s3_uri == _artifact_uri(algorithm_jar)
    assert result.algorithm_definition_s3_uri == ("s3://bucket/run/offline-source/effective_algorithm_definition.json")
    assert result.parameter_s3_uri == _artifact_uri(parameter)
    assert uploads.documents[result.algorithm_definition_s3_uri] == definition


def test_stage_fixed_document_rewrites_local_artifacts(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    algorithm_jar = tmp_path / "algorithm.jar"
    algorithm_jar.write_bytes(b"algorithm")
    parameter = tmp_path / "parameters.zip"
    parameter.write_bytes(b"parameters")
    domain_jar = tmp_path / "domain.jar"
    domain_jar.write_bytes(b"domain")
    composition = tmp_path / "composition.json"
    composition.write_text(
        json.dumps(
            {
                "root": "root@1",
                "algorithms": {
                    "root@1": {
                        "jar_uri": algorithm_jar.as_uri(),
                        "parameter": {"uri": parameter.as_uri()},
                    },
                    "child@1": {"jar_uri": "s3://published/child.jar"},
                },
                "slot_bindings": {"child-slot": "child@1"},
            }
        ),
        encoding="utf-8",
    )
    uploads = StagingUploads(monkeypatch)

    result = stage_offline_algorithm_source(
        FixedCompositionSource(composition, (domain_jar,)),
        destination_prefix="s3://bucket/run/offline-source",
        s3_client=object(),
        effective_algorithm_definition=None,
        parameter_s3_uri=None,
    )

    composition_uri = "s3://bucket/run/offline-source/composition.json"
    assert uploads.documents[composition_uri] == {
        "root": "root@1",
        "algorithms": {
            "root@1": {
                "jar_uri": _artifact_uri(algorithm_jar),
                "parameter": {"uri": _artifact_uri(parameter)},
            },
            "child@1": {"jar_uri": "s3://published/child.jar"},
        },
        "slot_bindings": {"child-slot": "child@1"},
    }
    assert result.parameter_s3_uri is None
    assert uploads.documents[result.manifest_s3_uri]["source"] == {
        "kind": "fixed",
        "composition_s3_uri": composition_uri,
        "domain_model_jar_s3_uris": [_artifact_uri(domain_jar)],
    }


def test_stage_ems_document_rewrites_local_artifacts(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    algorithm_jar = tmp_path / "algorithm.jar"
    algorithm_jar.write_bytes(b"algorithm")
    parameter = tmp_path / "parameters.zip"
    parameter.write_bytes(b"parameters")
    state = tmp_path / "ems-state.json"
    state.write_text(
        json.dumps(
            {
                "algorithm": {
                    "absolute_s3_algorithm_jar_path": algorithm_jar.as_uri(),
                    "absolute_s3_algorithm_parameter_path": parameter.as_uri(),
                }
            }
        ),
        encoding="utf-8",
    )
    uploads = StagingUploads(monkeypatch)

    result = stage_offline_algorithm_source(
        EmsSnapshotSource("root-slot", state, "/shared/customer_id"),
        destination_prefix="s3://bucket/run/offline-source",
        s3_client=object(),
        effective_algorithm_definition=None,
        parameter_s3_uri=None,
    )

    state_uri = "s3://bucket/run/offline-source/ems-state.json"
    assert uploads.documents[state_uri] == {
        "algorithm": {
            "absolute_s3_algorithm_jar_path": _artifact_uri(algorithm_jar),
            "absolute_s3_algorithm_parameter_path": _artifact_uri(parameter),
        }
    }
    assert result.parameter_s3_uri is None
    assert uploads.documents[result.manifest_s3_uri]["source"] == {
        "kind": "ems",
        "root_slot": "root-slot",
        "state_s3_uri": state_uri,
        "assignment_key_json_pointer": "/shared/customer_id",
        "domain_model_jar_s3_uris": [],
    }


def test_materialize_each_manifest_source(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    objects: dict[str, bytes] = {
        "s3://bucket/direct-manifest.json": json.dumps(
            {
                "schema_version": 1,
                "source": {
                    "kind": "direct",
                    "algorithm_jar_s3_uri": "s3://bucket/algorithm.jar",
                    "algorithm_definition": {"algorithm_name": "ranker"},
                    "domain_model_jar_s3_uris": ["s3://bucket/domain.jar"],
                    "parameter_s3_uri": "s3://bucket/parameters.zip",
                },
            }
        ).encode(),
        "s3://bucket/fixed-manifest.json": json.dumps(
            {
                "schema_version": 1,
                "source": {
                    "kind": "fixed",
                    "composition_s3_uri": "s3://bucket/composition.json",
                    "domain_model_jar_s3_uris": ["s3://bucket/domain.jar"],
                },
            }
        ).encode(),
        "s3://bucket/ems-manifest.json": json.dumps(
            {
                "schema_version": 1,
                "source": {
                    "kind": "ems",
                    "root_slot": "root-slot",
                    "state_s3_uri": "s3://bucket/state.json",
                    "assignment_key_json_pointer": "/shared/customer_id",
                    "domain_model_jar_s3_uris": [],
                },
            }
        ).encode(),
        "s3://bucket/algorithm.jar": b"algorithm",
        "s3://bucket/parameters.zip": b"parameters",
        "s3://bucket/composition.json": b"{}",
        "s3://bucket/domain.jar": b"domain",
        "s3://bucket/state.json": b"{}",
    }

    def download(uri: str, destination: Path, _s3_client: Any) -> None:
        destination.write_bytes(objects[uri])

    monkeypatch.setattr(manifest_module, "download_s3_file", download)

    direct = materialize_offline_algorithm_source(
        "s3://bucket/direct-manifest.json", scratch=tmp_path / "direct", s3_client=object()
    )
    assert isinstance(direct, DirectAlgorithmSource)
    assert direct.algorithm_jar_path.read_bytes() == b"algorithm"
    assert json.loads(Path(direct.algorithm_definition_arg).read_text()) == {"algorithm_name": "ranker"}
    assert direct.parameter_path is not None
    assert direct.parameter_path.read_bytes() == b"parameters"
    assert [path.read_bytes() for path in direct.domain_model_jars] == [b"domain"]

    fixed = materialize_offline_algorithm_source(
        "s3://bucket/fixed-manifest.json", scratch=tmp_path / "fixed", s3_client=object()
    )
    assert isinstance(fixed, FixedCompositionSource)
    assert fixed.composition_path.read_bytes() == b"{}"
    assert [path.read_bytes() for path in fixed.domain_model_jars] == [b"domain"]

    ems = materialize_offline_algorithm_source(
        "s3://bucket/ems-manifest.json", scratch=tmp_path / "ems", s3_client=object()
    )
    assert isinstance(ems, EmsSnapshotSource)
    assert ems.root_slot == "root-slot"
    assert ems.state_path.read_bytes() == b"{}"
    assert ems.assignment_key_json_pointer == "/shared/customer_id"


@pytest.mark.parametrize(
    ("manifest", "message"),
    [
        ({"schema_version": 1}, "missing required fields"),
        ({"schema_version": 1, "source": {}, "extra": True}, "unexpected fields"),
        ({"schema_version": 2, "source": {}}, "Unsupported offline source manifest schema version"),
        ({"schema_version": True, "source": {}}, "Unsupported offline source manifest schema version"),
        ({"schema_version": 1.0, "source": {}}, "Unsupported offline source manifest schema version"),
        ({"schema_version": 1, "source": []}, "manifest.source must be an object"),
        (
            {
                "schema_version": 1,
                "source": {
                    "kind": "direct",
                    "algorithm_jar_s3_uri": "s3://bucket/algorithm.jar",
                    "algorithm_definition": {},
                    "domain_model_jar_s3_uris": [],
                    "extra": True,
                },
            },
            "unexpected fields",
        ),
        ({"schema_version": 1, "source": {"kind": "unknown"}}, "Unsupported offline source kind"),
    ],
)
def test_materialize_rejects_invalid_manifests(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    manifest: dict[str, Any],
    message: str,
) -> None:
    def download(_uri: str, destination: Path, _s3_client: Any) -> None:
        destination.write_text(json.dumps(manifest), encoding="utf-8")

    monkeypatch.setattr(manifest_module, "download_s3_file", download)

    with pytest.raises(ValueError, match=message):
        materialize_offline_algorithm_source(
            "s3://bucket/manifest.json", scratch=tmp_path / "invalid", s3_client=object()
        )


def test_materialize_rejects_duplicate_json_keys(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    def download(_uri: str, destination: Path, _s3_client: Any) -> None:
        destination.write_text('{"schema_version": 1, "schema_version": 1, "source": {}}', encoding="utf-8")

    monkeypatch.setattr(manifest_module, "download_s3_file", download)

    with pytest.raises(ValueError, match="Duplicate JSON object key: schema_version"):
        materialize_offline_algorithm_source(
            "s3://bucket/manifest.json", scratch=tmp_path / "duplicate", s3_client=object()
        )
