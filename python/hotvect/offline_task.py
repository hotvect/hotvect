from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class DirectAlgorithmSource:
    algorithm_jar_path: Path
    algorithm_definition_arg: str
    parameter_path: Path | None = None
    domain_model_jars: tuple[Path, ...] = ()


@dataclass(frozen=True)
class FixedCompositionSource:
    composition_path: Path
    domain_model_jars: tuple[Path, ...] = ()


@dataclass(frozen=True)
class EmsSnapshotSource:
    root_slot: str
    state_path: Path
    assignment_key_json_pointer: str
    domain_model_jars: tuple[Path, ...] = ()


OfflineAlgorithmSource = DirectAlgorithmSource | FixedCompositionSource | EmsSnapshotSource


@dataclass(frozen=True)
class OfflineTaskSpec:
    task: str
    metadata_path: Path
    algorithm_source: OfflineAlgorithmSource
    source_path: Path | None = None
    dest_path: Path | None = None
    source_dest_mappings_path: Path | None = None
    dest_schema_description_path: Path | None = None
    samples: int | None = None
    sample_pool_size: int | None = None
    max_threads: int | None = None
    ordered: bool = False
    unordered: bool = False
    require_unordered_output: bool = False
    writer_num_shards: int | None = None
    include_feature_store_responses: bool = False
    target_rps: float | None = None
    target_throughput_fraction: float | None = None
    workload_mode: str | None = None
    log_features: bool = False


_TASKS_WITH_DEST = {"encode", "predict", "audit", "generate-state"}
_TASKS_WITH_SOURCE = {"encode", "predict", "audit", "performance-test", "generate-state"}
_TASKS_WITH_PARAMETERS = {"encode", "predict", "audit", "performance-test"}
_TASKS_WITH_SAMPLES = {"encode", "predict", "audit", "performance-test"}
_TASKS_WITH_MAX_THREADS = {"encode", "predict", "audit", "performance-test"}
_TASKS_WITH_COMPOSED_RUNTIME = {"predict", "performance-test"}


def validate_offline_task_inputs(
    *,
    task: str,
    source_path: Path | None,
    dest_path: Path | None,
    source_dest_mappings_path: Path | None,
) -> None:
    if source_dest_mappings_path is not None and task != "encode":
        raise ValueError("--source-dest-mappings is only supported for encode")
    if source_dest_mappings_path is not None and (source_path is not None or dest_path is not None):
        raise ValueError("--source-dest-mappings cannot be combined with --source-path or --dest-path")


def resolve_offline_algorithm_source(
    *,
    task: str,
    algorithm_jar_path: Path | None,
    algorithm_definition_arg: str | None,
    parameter_path: Path | None,
    composition_path: Path | None,
    ems_slot: str | None,
    ems_state_path: Path | None,
    assignment_key_json_pointer: str | None,
    domain_model_jars: tuple[Path, ...],
    algorithm_override_used: bool = False,
) -> OfflineAlgorithmSource:
    if composition_path is not None:
        if ems_slot is not None:
            raise ValueError("--composition and --ems-slot are mutually exclusive")
        if task not in _TASKS_WITH_COMPOSED_RUNTIME:
            raise ValueError("Fixed composition is supported only for predict and performance-test")
        if algorithm_jar_path is not None or algorithm_definition_arg is not None or algorithm_override_used:
            raise ValueError(
                "--composition cannot be combined with --algorithm-jar, --algorithm-name, or --algorithm-override"
            )
        if parameter_path is not None:
            raise ValueError(
                "--composition cannot be combined with --parameter-path; it selects parameters in its composition document"
            )
        if ems_state_path is not None or assignment_key_json_pointer is not None:
            raise ValueError("--composition cannot be combined with EMS composition options")
        return FixedCompositionSource(composition_path, domain_model_jars)

    if ems_slot is not None:
        if task not in _TASKS_WITH_COMPOSED_RUNTIME:
            raise ValueError("EMS composition is supported only for predict and performance-test")
        if algorithm_jar_path is not None or algorithm_definition_arg is not None or algorithm_override_used:
            raise ValueError(
                "--ems-slot cannot be combined with --algorithm-jar, --algorithm-name, or --algorithm-override"
            )
        if parameter_path is not None:
            raise ValueError(
                "--ems-slot cannot be combined with --parameter-path; EMS composition obtains parameters from EMS"
            )
        if ems_state_path is None:
            raise ValueError("--ems-state is required with --ems-slot")
        if assignment_key_json_pointer is None:
            raise ValueError("--assignment-key-json-pointer is required with --ems-slot")
        if not assignment_key_json_pointer.startswith("/"):
            raise ValueError("--assignment-key-json-pointer must be an RFC 6901 JSON Pointer")
        return EmsSnapshotSource(
            ems_slot,
            ems_state_path,
            assignment_key_json_pointer,
            domain_model_jars,
        )

    if ems_state_path is not None or assignment_key_json_pointer is not None:
        raise ValueError("--ems-state and --assignment-key-json-pointer require a composed source")
    if algorithm_jar_path is None:
        raise ValueError("--algorithm-jar is required")
    if algorithm_definition_arg is None:
        raise ValueError("--algorithm-name is required")
    if domain_model_jars and task not in _TASKS_WITH_COMPOSED_RUNTIME:
        raise ValueError("--domain-model-jar is supported only for predict and performance-test")
    return DirectAlgorithmSource(
        algorithm_jar_path,
        algorithm_definition_arg,
        parameter_path,
        domain_model_jars,
    )


def build_offline_task_main_args(spec: OfflineTaskSpec) -> list[str]:
    validate_offline_task_inputs(
        task=spec.task,
        source_path=spec.source_path,
        dest_path=spec.dest_path,
        source_dest_mappings_path=spec.source_dest_mappings_path,
    )
    mappings_mode = spec.task == "encode" and spec.source_dest_mappings_path is not None

    if isinstance(spec.algorithm_source, (FixedCompositionSource, EmsSnapshotSource)):
        if spec.task not in _TASKS_WITH_COMPOSED_RUNTIME:
            raise ValueError("Composed algorithm sources are supported only for predict and performance-test")
    if (
        isinstance(spec.algorithm_source, DirectAlgorithmSource)
        and spec.algorithm_source.domain_model_jars
        and spec.task not in _TASKS_WITH_COMPOSED_RUNTIME
    ):
        raise ValueError("--domain-model-jar is supported only for predict and performance-test")

    args = ["com.hotvect.offlineutils.commandline.Main", spec.task]
    if isinstance(spec.algorithm_source, FixedCompositionSource):
        args.extend(["--composition", str(spec.algorithm_source.composition_path)])
        for domain_model_jar in spec.algorithm_source.domain_model_jars:
            args.extend(["--domain-model-jar", str(domain_model_jar)])
    elif isinstance(spec.algorithm_source, EmsSnapshotSource):
        args.extend(["--ems-slot", spec.algorithm_source.root_slot])
        args.extend(["--ems-state", str(spec.algorithm_source.state_path)])
        args.extend(["--assignment-key-json-pointer", spec.algorithm_source.assignment_key_json_pointer])
        for domain_model_jar in spec.algorithm_source.domain_model_jars:
            args.extend(["--domain-model-jar", str(domain_model_jar)])
    else:
        args.extend(
            [
                "--algorithm-jar",
                str(spec.algorithm_source.algorithm_jar_path),
                "--algorithm-definition",
                spec.algorithm_source.algorithm_definition_arg,
            ]
        )
        for domain_model_jar in spec.algorithm_source.domain_model_jars:
            args.extend(["--domain-model-jar", str(domain_model_jar)])
    args.extend(["--metadata-path", str(spec.metadata_path)])

    if spec.task in _TASKS_WITH_DEST and not mappings_mode:
        if spec.dest_path is None:
            raise ValueError(f"Task {spec.task} requires dest_path")
        args.extend(["--dest", str(spec.dest_path)])

    if spec.max_threads is not None and spec.max_threads > 0 and spec.task in _TASKS_WITH_MAX_THREADS:
        args.extend(["--max-threads", str(spec.max_threads)])

    if spec.source_path is not None and spec.task in _TASKS_WITH_SOURCE:
        args.extend(["--source", str(spec.source_path)])

    if mappings_mode:
        args.extend(["--source-dest-mappings", str(spec.source_dest_mappings_path)])

    if spec.ordered:
        args.append("--ordered")

    if spec.unordered:
        args.append("--unordered")

    if spec.require_unordered_output:
        args.append("--require-unordered-output")

    if spec.writer_num_shards is not None and spec.task in {"encode", "predict", "audit"}:
        args.extend(["--writer-num-shards", str(spec.writer_num_shards)])

    if spec.task == "encode":
        if spec.dest_schema_description_path is None:
            raise ValueError("Task encode requires dest_schema_description_path")
        args.extend(["--dest-schema-description", str(spec.dest_schema_description_path)])

    if (
        isinstance(spec.algorithm_source, DirectAlgorithmSource)
        and spec.algorithm_source.parameter_path is not None
        and spec.task in _TASKS_WITH_PARAMETERS
    ):
        args.extend(["--parameters", str(spec.algorithm_source.parameter_path)])

    if spec.samples is not None and spec.task in _TASKS_WITH_SAMPLES:
        args.extend(["--samples", str(spec.samples)])

    if spec.sample_pool_size is not None and spec.task == "performance-test":
        args.extend(["--sample-pool-size", str(spec.sample_pool_size)])

    if spec.task == "performance-test" and spec.target_rps is not None:
        args.extend(["--target-rps", str(spec.target_rps)])

    if spec.task == "performance-test" and spec.target_throughput_fraction is not None:
        args.extend(["--target-throughput-fraction", str(spec.target_throughput_fraction)])

    if spec.task == "performance-test" and spec.workload_mode is not None:
        args.extend(["--workload-mode", str(spec.workload_mode)])

    if spec.task == "predict" and spec.log_features:
        args.append("--log-features")

    if spec.task in {"predict", "audit"} and spec.include_feature_store_responses:
        args.append("--include-feature-store-responses")

    return args
