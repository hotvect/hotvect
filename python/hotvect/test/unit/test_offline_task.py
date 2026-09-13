from pathlib import Path

import pytest

from hotvect.offline_task import (
    DirectAlgorithmSource,
    EmsSnapshotSource,
    FixedCompositionSource,
    OfflineTaskSpec,
    build_offline_task_main_args,
    resolve_offline_algorithm_source,
)


def _encode_spec(**overrides) -> OfflineTaskSpec:
    values = {
        "task": "encode",
        "metadata_path": Path("metadata"),
        "algorithm_source": DirectAlgorithmSource(Path("algorithm.jar"), "ranker"),
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
                metadata_path=Path("metadata"),
                algorithm_source=DirectAlgorithmSource(Path("algorithm.jar"), "ranker"),
                dest_path=Path("predictions"),
                source_dest_mappings_path=Path("source-dest-mappings.json"),
            )
        )


def test_ems_predict_builds_synthetic_state_arguments_without_local_algorithm() -> None:
    args = build_offline_task_main_args(
        OfflineTaskSpec(
            task="predict",
            metadata_path=Path("metadata"),
            algorithm_source=EmsSnapshotSource(
                root_slot="product-ranking",
                state_path=Path("synthetic-ems.json"),
                assignment_key_json_pointer="/shared/user_id",
                domain_model_jars=(Path("domain-a.jar"), Path("domain-b.jar")),
            ),
            source_path=Path("requests.jsonl"),
            dest_path=Path("predictions"),
        )
    )

    assert args[:12] == [
        "com.hotvect.offlineutils.commandline.Main",
        "predict",
        "--ems-slot",
        "product-ranking",
        "--ems-state",
        "synthetic-ems.json",
        "--assignment-key-json-pointer",
        "/shared/user_id",
        "--domain-model-jar",
        "domain-a.jar",
        "--domain-model-jar",
        "domain-b.jar",
    ]
    assert "--algorithm-jar" not in args
    assert "--algorithm-definition" not in args


def test_fixed_composition_predict_builds_arguments_without_assignment_or_local_root() -> None:
    args = build_offline_task_main_args(
        OfflineTaskSpec(
            task="predict",
            metadata_path=Path("metadata"),
            algorithm_source=FixedCompositionSource(
                Path("composition.json"),
                (Path("domain-a.jar"), Path("domain-b.jar")),
            ),
            source_path=Path("requests.jsonl"),
            dest_path=Path("predictions"),
        )
    )

    assert args[:8] == [
        "com.hotvect.offlineutils.commandline.Main",
        "predict",
        "--composition",
        "composition.json",
        "--domain-model-jar",
        "domain-a.jar",
        "--domain-model-jar",
        "domain-b.jar",
    ]
    assert "--ems-slot" not in args
    assert "--assignment-key-json-pointer" not in args
    assert "--algorithm-jar" not in args
    assert "--algorithm-definition" not in args


def test_ems_performance_test_builds_arguments_without_local_algorithm() -> None:
    args = build_offline_task_main_args(
        OfflineTaskSpec(
            task="performance-test",
            metadata_path=Path("metadata"),
            algorithm_source=EmsSnapshotSource(
                "product-ranking",
                Path("synthetic-ems.json"),
                "/shared/user_id",
            ),
            source_path=Path("requests.jsonl"),
        )
    )

    assert args[:8] == [
        "com.hotvect.offlineutils.commandline.Main",
        "performance-test",
        "--ems-slot",
        "product-ranking",
        "--ems-state",
        "synthetic-ems.json",
        "--assignment-key-json-pointer",
        "/shared/user_id",
    ]
    assert "--algorithm-jar" not in args
    assert "--algorithm-definition" not in args
    assert "--parameters" not in args


def test_fixed_composition_performance_test_builds_arguments_without_local_algorithm() -> None:
    args = build_offline_task_main_args(
        OfflineTaskSpec(
            task="performance-test",
            metadata_path=Path("metadata"),
            algorithm_source=FixedCompositionSource(Path("composition.json")),
            source_path=Path("requests.jsonl"),
        )
    )

    assert args[:4] == [
        "com.hotvect.offlineutils.commandline.Main",
        "performance-test",
        "--composition",
        "composition.json",
    ]
    assert "--algorithm-jar" not in args
    assert "--algorithm-definition" not in args
    assert "--parameters" not in args


@pytest.mark.parametrize("task", ["predict", "performance-test"])
def test_direct_source_retains_domain_model_jars_and_builds_arguments(task: str) -> None:
    domain_model_jars = (Path("domain-a.jar"), Path("domain-b.jar"))
    source = resolve_offline_algorithm_source(
        task=task,
        algorithm_jar_path=Path("algorithm.jar"),
        algorithm_definition_arg="ranker",
        parameter_path=None,
        composition_path=None,
        ems_slot=None,
        ems_state_path=None,
        assignment_key_json_pointer=None,
        domain_model_jars=domain_model_jars,
    )

    assert source == DirectAlgorithmSource(
        Path("algorithm.jar"),
        "ranker",
        domain_model_jars=domain_model_jars,
    )

    args = build_offline_task_main_args(
        OfflineTaskSpec(
            task=task,
            metadata_path=Path("metadata"),
            algorithm_source=source,
            source_path=Path("requests.jsonl"),
            dest_path=Path("predictions") if task == "predict" else None,
        )
    )
    assert args[args.index("--algorithm-jar") : args.index("--metadata-path")] == [
        "--algorithm-jar",
        "algorithm.jar",
        "--algorithm-definition",
        "ranker",
        "--domain-model-jar",
        "domain-a.jar",
        "--domain-model-jar",
        "domain-b.jar",
    ]


def test_direct_domain_model_jars_are_rejected_for_unsupported_tasks() -> None:
    with pytest.raises(ValueError, match="only for predict and performance-test"):
        resolve_offline_algorithm_source(
            task="audit",
            algorithm_jar_path=Path("algorithm.jar"),
            algorithm_definition_arg="ranker",
            parameter_path=None,
            composition_path=None,
            ems_slot=None,
            ems_state_path=None,
            assignment_key_json_pointer=None,
            domain_model_jars=(Path("domain.jar"),),
        )


@pytest.mark.parametrize(
    "overrides, message",
    [
        ({"assignment_key_json_pointer": None}, "assignment-key-json-pointer is required"),
        ({"assignment_key_json_pointer": "customer/id"}, "RFC 6901"),
        ({"ems_state_path": None}, "ems-state is required"),
        ({"algorithm_jar_path": Path("algorithm.jar")}, "cannot be combined"),
        ({"parameter_path": Path("parameters.zip")}, "obtains parameters"),
    ],
)
def test_ems_predict_rejects_conflicting_or_incomplete_sources(overrides: dict, message: str) -> None:
    values = {
        "task": "predict",
        "algorithm_jar_path": None,
        "algorithm_definition_arg": None,
        "parameter_path": None,
        "composition_path": None,
        "ems_slot": "product-ranking",
        "ems_state_path": Path("synthetic-ems.json"),
        "assignment_key_json_pointer": "/customer/id",
        "domain_model_jars": (),
    }
    values.update(overrides)

    with pytest.raises(ValueError, match=message):
        resolve_offline_algorithm_source(**values)


@pytest.mark.parametrize(
    "overrides, message",
    [
        ({"task": "audit"}, "only for predict"),
        ({"algorithm_jar_path": Path("algorithm.jar")}, "cannot be combined"),
        ({"parameter_path": Path("parameters.zip")}, "selects parameters"),
        ({"ems_slot": "product-ranking"}, "mutually exclusive"),
        ({"ems_state_path": Path("state.json")}, "EMS composition options"),
        ({"assignment_key_json_pointer": "/customer/id"}, "EMS composition options"),
    ],
)
def test_fixed_composition_predict_rejects_conflicting_sources(overrides: dict, message: str) -> None:
    values = {
        "task": "predict",
        "algorithm_jar_path": None,
        "algorithm_definition_arg": None,
        "parameter_path": None,
        "composition_path": Path("composition.json"),
        "ems_slot": None,
        "ems_state_path": None,
        "assignment_key_json_pointer": None,
        "domain_model_jars": (),
    }
    values.update(overrides)

    with pytest.raises(ValueError, match=message):
        resolve_offline_algorithm_source(**values)
