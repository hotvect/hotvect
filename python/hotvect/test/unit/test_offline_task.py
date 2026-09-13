from pathlib import Path

import pytest

from hotvect.offline_task import OfflineTaskSpec, build_offline_task_main_args


def _encode_spec(**overrides) -> OfflineTaskSpec:
    values = {
        "task": "encode",
        "algorithm_jar_path": Path("algorithm.jar"),
        "algorithm_definition_arg": "ranker",
        "metadata_path": Path("metadata"),
        "dest_schema_description_path": Path("encoded-schema-description"),
    }
    values.update(overrides)
    return OfflineTaskSpec(**values)


def test_encode_builds_source_dest_mapping_args_without_source_or_dest() -> None:
    args = build_offline_task_main_args(_encode_spec(source_dest_mappings_path=Path("source-dest-mappings.json")))

    assert args[-4:] == [
        "--source-dest-mappings",
        "source-dest-mappings.json",
        "--dest-schema-description",
        "encoded-schema-description",
    ]
    assert "--source" not in args
    assert "--dest" not in args


@pytest.mark.parametrize("conflicting_field", ["source_path", "dest_path"])
def test_encode_rejects_source_dest_mappings_with_source_or_dest(conflicting_field: str) -> None:
    with pytest.raises(ValueError, match="cannot be combined"):
        build_offline_task_main_args(
            _encode_spec(
                source_dest_mappings_path=Path("source-dest-mappings.json"),
                **{conflicting_field: Path("input")},
            )
        )


def test_non_encode_task_rejects_source_dest_mappings() -> None:
    with pytest.raises(ValueError, match="only supported for encode"):
        build_offline_task_main_args(
            OfflineTaskSpec(
                task="predict",
                algorithm_jar_path=Path("algorithm.jar"),
                algorithm_definition_arg="ranker",
                metadata_path=Path("metadata"),
                dest_path=Path("predictions"),
                source_dest_mappings_path=Path("source-dest-mappings.json"),
            )
        )
