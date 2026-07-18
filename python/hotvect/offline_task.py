from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class OfflineTaskSpec:
    task: str
    algorithm_jar_path: Path
    algorithm_definition_arg: str
    metadata_path: Path
    source_path: Path | None = None
    dest_path: Path | None = None
    parameter_path: Path | None = None
    dest_schema_description_path: Path | None = None
    samples: int | None = None
    sample_pool_size: int | None = None
    max_threads: int | None = None
    ordered: bool = False
    unordered: bool = False
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


def build_offline_task_main_args(spec: OfflineTaskSpec) -> list[str]:
    args = [
        "com.hotvect.offlineutils.commandline.Main",
        spec.task,
        "--algorithm-jar",
        str(spec.algorithm_jar_path),
        "--algorithm-definition",
        spec.algorithm_definition_arg,
        "--metadata-path",
        str(spec.metadata_path),
    ]

    if spec.task in _TASKS_WITH_DEST:
        if spec.dest_path is None:
            raise ValueError(f"Task {spec.task} requires dest_path")
        args.extend(["--dest", str(spec.dest_path)])

    if spec.max_threads is not None and spec.max_threads > 0 and spec.task in _TASKS_WITH_MAX_THREADS:
        args.extend(["--max-threads", str(spec.max_threads)])

    if spec.source_path is not None and spec.task in _TASKS_WITH_SOURCE:
        args.extend(["--source", str(spec.source_path)])

    if spec.ordered:
        args.append("--ordered")

    if spec.unordered:
        args.append("--unordered")

    if spec.writer_num_shards is not None and spec.task in {"encode", "predict", "audit"}:
        args.extend(["--writer-num-shards", str(spec.writer_num_shards)])

    if spec.task == "encode":
        if spec.dest_schema_description_path is None:
            raise ValueError("Task encode requires dest_schema_description_path")
        args.extend(["--dest-schema-description", str(spec.dest_schema_description_path)])

    if spec.parameter_path is not None and spec.task in _TASKS_WITH_PARAMETERS:
        args.extend(["--parameters", str(spec.parameter_path)])

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
