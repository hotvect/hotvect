import json
import zipfile
from pathlib import Path

import pytest

from hotvect.utils import (
    MalformedAlgorithmException,
    read_algorithm_definition_from_jars,
    select_algorithm_definition_provider,
)


def test_equal_provider_identities_choose_the_same_canonical_path_for_every_lookup(
    tmp_path: Path,
) -> None:
    canonical = _write_algorithm_jar(
        tmp_path / "a-canonical.jar",
        {
            "algorithm_name": "child",
            "algorithm_version": "1",
            "algorithm_factory_classname": "example.ChildFactory",
            "provider": "canonical",
        },
    )
    alias = _write_algorithm_jar(
        tmp_path / "z-alias.jar",
        {
            "algorithm_name": "child",
            "algorithm_version": "1",
            "algorithm_factory_classname": "example.ChildFactory",
            "provider": "alias",
        },
    )

    provider = select_algorithm_definition_provider("child", [alias, canonical])

    assert provider.path == canonical.resolve()
    assert provider.definition["provider"] == "canonical"
    assert read_algorithm_definition_from_jars("child", [alias, canonical])["provider"] == "canonical"


def test_conflicting_provider_identities_fail_instead_of_selecting_by_caller_order(tmp_path: Path) -> None:
    first = _write_algorithm_jar(
        tmp_path / "a-first.jar",
        {
            "algorithm_name": "child",
            "algorithm_version": "1",
            "algorithm_factory_classname": "example.ChildFactory",
        },
    )
    second = _write_algorithm_jar(
        tmp_path / "z-second.jar",
        {
            "algorithm_name": "child",
            "algorithm_version": "2",
            "algorithm_factory_classname": "example.ChildFactory",
        },
    )

    with pytest.raises(MalformedAlgorithmException, match="conflicting provider identities") as error:
        select_algorithm_definition_provider("child", [second, first])

    assert "child@1" in str(error.value)
    assert "child@2" in str(error.value)


def test_offline_only_fields_are_rejected_in_committed_definitions(tmp_path: Path) -> None:
    hyperparameterized = _write_algorithm_jar(
        tmp_path / "hyperparameterized.jar",
        {
            "algorithm_name": "child",
            "algorithm_version": "1",
            "hyperparameter_version": "candidate-a",
            "algorithm_factory_classname": "example.ChildFactory",
        },
    )

    with pytest.raises(MalformedAlgorithmException, match="must not contain offline-only hyperparameter_version"):
        select_algorithm_definition_provider("child", [hyperparameterized])

    versioned_private = _write_algorithm_jar(
        tmp_path / "versioned-private.jar",
        {
            "algorithm_name": "child",
            "algorithm_version": "1",
            "algorithm_factory_classname": "example.ChildFactory",
            "dependencies": {"private-child@2": {}},
        },
    )

    with pytest.raises(
        MalformedAlgorithmException,
        match="Private dependency private-child must not declare an algorithm version",
    ):
        select_algorithm_definition_provider("child", [versioned_private])


def test_nontextual_provider_identity_is_rejected(tmp_path: Path) -> None:
    jar = _write_algorithm_jar(
        tmp_path / "invalid.jar",
        {
            "algorithm_name": "child",
            "algorithm_version": 1,
            "algorithm_factory_classname": "example.ChildFactory",
        },
    )

    with pytest.raises(MalformedAlgorithmException, match="non-blank string algorithm_version"):
        select_algorithm_definition_provider("child", [jar])


def _write_algorithm_jar(path: Path, definition: dict[str, object]) -> Path:
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr(
            f"{definition['algorithm_name']}-algorithm-definition.json",
            json.dumps(definition),
        )
    return path
