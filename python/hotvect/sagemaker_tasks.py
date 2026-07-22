import gzip
import json
import logging
import os
import shutil
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import boto3
from mypy_boto3_s3 import S3Client

import hotvect.hotvectjar
from hotvect.algorithm_definition_overrides import parse_effective_algorithm_definition_payload
from hotvect.benchmark_contract import BENCHMARK_CONTRACT_KEY, build_benchmark_contract
from hotvect.offline_task import OfflineTaskSpec, build_offline_task_main_args
from hotvect.parallel_oneshot import stable_shard_index
from hotvect.s3_utils import download_json_from_s3 as _shared_download_json_from_s3
from hotvect.s3_utils import download_s3_file as _download_s3_file
from hotvect.s3_utils import join_s3_uri
from hotvect.s3_utils import upload_directory_to_s3 as _shared_upload_directory_to_s3
from hotvect.s3_utils import upload_file_to_s3 as _shared_upload_file_to_s3
from hotvect.sagemaker_contracts import (
    OneShotSagemakerHyperparameters,
    expected_output_descriptor,
    parallel_text_output_uri,
    resolve_evaluate_source_path,
    task_text_output_paths,
)
from hotvect.utils import runshell

logger = logging.getLogger(__name__)

# Keep these names stable for tests that monkeypatch the module-level helpers.
_upload_file_to_s3 = _shared_upload_file_to_s3
_upload_directory_to_s3 = _shared_upload_directory_to_s3
_download_json_from_s3 = _shared_download_json_from_s3


@dataclass(frozen=True)
class OneShotTaskRequest:
    task: str
    source_dir: Path
    metadata_dir: Path
    output_dir: Path
    task_output_s3_uri: str | None
    samples: int | None
    target_rps: float | None
    target_throughput_fraction: float | None
    algorithm_definition: dict[str, Any]
    workload_mode: str | None = None
    ordered: bool = False
    unordered: bool = False
    writer_num_shards: int | None = None
    include_feature_store_responses: bool = False
    max_threads: int | None = None
    log_features: bool = False
    sample_pool_size: int | None = None
    parallel_worker_count: int | None = None
    parallel_worker_index: int | None = None
    compression: str = "none"


def _write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, sort_keys=True))


def _gzip_file(source: Path) -> Path:
    fd, target = tempfile.mkstemp(suffix=source.suffix + ".gz")
    os.close(fd)
    target_path = Path(target)
    with open(source, "rb") as fin, gzip.open(target_path, "wb") as fout:
        shutil.copyfileobj(fin, fout)
    return target_path


def _upload_text_outputs_to_s3(local_outputs: list[Path], s3_target_prefix: str, s3_client: S3Client) -> None:
    for local_output in local_outputs:
        _upload_file_to_s3(
            str(local_output),
            join_s3_uri(s3_target_prefix, local_output.name),
            s3_client,
            fail_fast=True,
        )


def _iter_parallel_source_files(source_dir: Path) -> list[Path]:
    """List shardable source files for parallel execution.

    We skip underscore-prefixed path segments to mirror the S3-side planner, which
    ignores control files such as `_SUCCESS` and `_temporary/...`.
    """

    files: list[Path] = []
    for path in sorted(source_dir.rglob("*")):
        if not path.is_file():
            continue
        rel_parts = path.relative_to(source_dir).parts
        if any(part.startswith("_") for part in rel_parts):
            continue
        files.append(path)
    return files


def _prepare_parallel_source_subset(req: OneShotTaskRequest, work_dir: Path) -> tuple[Path, list[Path]]:
    """Build the worker-local source view for a parallel one-shot task.

    The returned directory is either the original source tree (non-parallel runs) or a
    symlinked subset containing only the files assigned to the current worker.
    """

    worker_count = req.parallel_worker_count
    worker_index = req.parallel_worker_index
    if worker_count is None or worker_index is None:
        return req.source_dir, _iter_parallel_source_files(req.source_dir)

    source_files = _iter_parallel_source_files(req.source_dir)
    if not source_files:
        raise ValueError(f"No source files found under {req.source_dir} for task {req.task}")

    filtered_source_dir = work_dir / f"parallel_source_{worker_index:05d}"
    filtered_source_dir.mkdir(parents=True, exist_ok=True)
    assigned_rel_paths: list[Path] = []
    for source_file in source_files:
        relative_path = source_file.relative_to(req.source_dir)
        if stable_shard_index(relative_path.as_posix(), worker_count) != worker_index:
            continue
        # Symlink instead of copying so workers get isolated views without duplicating
        # potentially large mounted source data.
        target = filtered_source_dir / relative_path
        target.parent.mkdir(parents=True, exist_ok=True)
        os.symlink(source_file, target)
        assigned_rel_paths.append(relative_path)
    return filtered_source_dir, assigned_rel_paths


def _upload_parallel_public_output(req: OneShotTaskRequest, s3_client: S3Client) -> None:
    if req.task_output_s3_uri is None or req.parallel_worker_index is None:
        return

    if req.task in {"predict", "audit"}:
        local_outputs = task_text_output_paths(req.task, req.output_dir)
        temp_paths: list[Path] = []
        try:
            for local_shard_index, local_output in enumerate(local_outputs):
                upload_path = local_output
                if req.compression == "gzip":
                    upload_path = _gzip_file(local_output)
                    temp_paths.append(upload_path)
                _upload_file_to_s3(
                    str(upload_path),
                    parallel_text_output_uri(
                        req.task_output_s3_uri, req.parallel_worker_index, local_shard_index, req.compression
                    ),
                    s3_client,
                    fail_fast=True,
                )
        finally:
            for temp_path in temp_paths:
                temp_path.unlink(missing_ok=True)
        return

    if req.task == "encode":
        expected_output = expected_output_descriptor(
            req.task,
            req.task_output_s3_uri,
            req.parallel_worker_index,
            req.compression,
        )
        shard_base = expected_output["schema_uri"].rsplit("/", 1)[0]
        _upload_directory_to_s3(str(req.output_dir / "encoded"), shard_base, s3_client, fail_fast=True)
        _upload_file_to_s3(
            str(req.output_dir / "encoded-schema-description"),
            expected_output["schema_uri"],
            s3_client,
            fail_fast=True,
        )
        return

    raise ValueError(f"Parallel public output upload is not supported for task {req.task}")


def _upload_single_public_output(req: OneShotTaskRequest, s3_client: S3Client) -> None:
    if req.task_output_s3_uri is None or req.parallel_worker_index is not None:
        return

    if req.task in {"predict", "audit"}:
        _upload_text_outputs_to_s3(
            task_text_output_paths(req.task, req.output_dir),
            req.task_output_s3_uri,
            s3_client,
        )
        return

    if req.task == "encode":
        _upload_directory_to_s3(str(req.output_dir / "encoded"), req.task_output_s3_uri, s3_client, fail_fast=True)
        _upload_file_to_s3(
            str(req.output_dir / "encoded-schema-description"),
            join_s3_uri(req.task_output_s3_uri, "encoded-schema-description"),
            s3_client,
            fail_fast=True,
        )
        return

    if req.task == "evaluate":
        _upload_file_to_s3(
            str(req.output_dir / "evaluation.json"),
            join_s3_uri(req.task_output_s3_uri, "evaluation.json"),
            s3_client,
            fail_fast=True,
        )
        return

    if req.task == "performance-test":
        if req.output_dir.exists():
            _upload_directory_to_s3(str(req.output_dir), req.task_output_s3_uri, s3_client, fail_fast=True)
        return

    raise ValueError(f"Single-job public output upload is not supported for task {req.task}")


def _run_task(
    req: OneShotTaskRequest, *, local_algorithm_jar: Path, local_parameter_zip: Path | None
) -> dict[str, Any]:
    algo_def_path = req.metadata_dir / "algorithm_definition.json"
    _write_json(algo_def_path, req.algorithm_definition)

    task = req.task
    if task not in {"audit", "predict", "encode", "performance-test", "evaluate"}:
        raise ValueError(f"Unsupported hotvect_task: {task}")

    stage_metadata_dir = req.metadata_dir / task
    stage_metadata_dir.mkdir(parents=True, exist_ok=True)
    output_base = req.output_dir
    output_base.mkdir(parents=True, exist_ok=True)

    if task == "evaluate":
        from hotvect.evaluation.evaluation import standard_evaluation

        source_path = resolve_evaluate_source_path(req.source_dir)
        evaluation_result = standard_evaluation(str(source_path))
        dest_path = output_base / "evaluation.json"
        _write_json(dest_path, evaluation_result)
        task_metadata = {
            "destination_file": str(dest_path),
            "evaluated_source_path": str(source_path),
            "result_keys": sorted(evaluation_result.keys()),
        }
        _write_json(stage_metadata_dir / "metadata.json", task_metadata)
        return task_metadata

    active_source_dir, assigned_rel_paths = _prepare_parallel_source_subset(req, stage_metadata_dir)
    if req.parallel_worker_count is not None and req.parallel_worker_index is not None and not assigned_rel_paths:
        task_metadata = {
            "parallel_worker_count": req.parallel_worker_count,
            "parallel_worker_index": req.parallel_worker_index,
            "source_files_assigned": 0,
            "skipped": True,
        }
        _write_json(stage_metadata_dir / "metadata.json", task_metadata)
        return task_metadata

    dest_path = None
    dest_schema_path = None
    if task == "encode":
        dest_path = output_base / "encoded"
        dest_schema_path = output_base / "encoded-schema-description"
    elif task == "predict":
        dest_path = output_base / "prediction"
    elif task == "audit":
        if not local_parameter_zip:
            raise ValueError("audit requires s3_uri_parameter_zip")
        dest_path = output_base / "audit"
    elif task == "performance-test" and not local_parameter_zip:
        raise ValueError("performance-test requires s3_uri_parameter_zip")

    spec = OfflineTaskSpec(
        task=task,
        algorithm_jar_path=local_algorithm_jar,
        algorithm_definition_arg=str(algo_def_path),
        metadata_path=stage_metadata_dir,
        source_path=active_source_dir,
        dest_path=dest_path,
        parameter_path=local_parameter_zip,
        dest_schema_description_path=dest_schema_path,
        samples=req.samples,
        sample_pool_size=req.sample_pool_size,
        max_threads=req.max_threads,
        ordered=req.ordered,
        unordered=req.unordered,
        writer_num_shards=req.writer_num_shards,
        include_feature_store_responses=req.include_feature_store_responses,
        target_rps=req.target_rps,
        target_throughput_fraction=req.target_throughput_fraction,
        workload_mode=req.workload_mode,
        log_features=req.log_features,
    )
    cmd = [
        "java",
        "-cp",
        str(hotvect.hotvectjar.HOTVECT_JAR_PATH),
        *build_offline_task_main_args(spec),
    ]

    logger.info("Running one-shot task: %s", " ".join(cmd))
    runshell(cmd)

    metadata_file = stage_metadata_dir / "metadata.json"
    if metadata_file.exists():
        task_metadata = json.loads(metadata_file.read_text())
        if task == "performance-test" and isinstance(task_metadata, dict):
            task_metadata.setdefault("execution_command", cmd)
        return task_metadata
    return {"warning": "metadata file missing", "metadata_path": str(metadata_file)}


def run_one_shot_from_sagemaker_env() -> None:
    logging.getLogger().setLevel(logging.INFO)
    try:
        from sagemaker_training.environment import Environment
    except ImportError as e:
        raise ImportError(
            "hotvect.sagemaker_tasks requires optional dependency 'sagemaker-training' "
            "(typically available in SageMaker Linux images)."
        ) from e

    env = Environment()
    hp = env.hyperparameters

    s3_client: S3Client = boto3.client("s3")
    request_hp = OneShotSagemakerHyperparameters.from_hyperparameters(hp)

    task = request_hp.task
    s3_uri_algorithm_jar = request_hp.algorithm_jar_s3_uri
    s3_uri_metadata = request_hp.metadata_s3_uri
    s3_uri_result_file = request_hp.result_file_s3_uri
    s3_uri_parameter_zip = request_hp.parameter_zip_s3_uri
    task_output_s3_uri = request_hp.task_output.s3_uri
    compression = request_hp.task_output.compression
    parallel_worker_count = (
        request_hp.parallel_execution.worker_count if request_hp.parallel_execution is not None else None
    )
    parallel_worker_index = (
        request_hp.parallel_execution.worker_index if request_hp.parallel_execution is not None else None
    )

    algorithm_definition = parse_effective_algorithm_definition_payload(
        _download_json_from_s3(request_hp.algorithm_definition_s3_uri, s3_client)
    )

    source_channel = request_hp.source_channel
    source_dir = Path(env.input_dir) / "data" / source_channel

    metadata_dir = Path(env.output_data_dir) / "meta"
    output_dir = Path(env.output_data_dir) / "out"
    metadata_dir.mkdir(parents=True, exist_ok=True)
    output_dir.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory() as tmpd:
        tmp = Path(tmpd)
        local_algorithm_jar = tmp / Path(urlparse(s3_uri_algorithm_jar).path).name
        _download_s3_file(s3_uri_algorithm_jar, local_algorithm_jar, s3_client)

        local_parameter_zip = None
        if s3_uri_parameter_zip:
            local_parameter_zip = tmp / Path(urlparse(s3_uri_parameter_zip).path).name
            _download_s3_file(s3_uri_parameter_zip, local_parameter_zip, s3_client)

        req = OneShotTaskRequest(
            task=task,
            source_dir=source_dir,
            metadata_dir=metadata_dir,
            output_dir=output_dir,
            task_output_s3_uri=task_output_s3_uri,
            samples=request_hp.samples,
            sample_pool_size=request_hp.sample_pool_size,
            target_rps=request_hp.target_rps,
            target_throughput_fraction=request_hp.target_throughput_fraction,
            workload_mode=request_hp.workload_mode,
            algorithm_definition=algorithm_definition,
            ordered=request_hp.ordered,
            unordered=request_hp.unordered,
            writer_num_shards=request_hp.writer_num_shards,
            include_feature_store_responses=request_hp.include_feature_store_responses,
            max_threads=request_hp.max_threads,
            log_features=request_hp.log_features,
            parallel_worker_count=parallel_worker_count,
            parallel_worker_index=parallel_worker_index,
            compression=compression,
        )

        try:
            task_metadata = _run_task(
                req, local_algorithm_jar=local_algorithm_jar, local_parameter_zip=local_parameter_zip
            )

            _upload_directory_to_s3(str(metadata_dir), s3_uri_metadata, s3_client, fail_fast=True)

            if req.task_output_s3_uri is not None and not task_metadata.get("skipped"):
                if req.parallel_worker_index is not None:
                    _upload_parallel_public_output(req, s3_client)
                else:
                    _upload_single_public_output(req, s3_client)

            result = {
                "task": task,
                "requested_samples": request_hp.samples,
                "requested_sample_pool_size": request_hp.sample_pool_size,
                "requested_target_rps": request_hp.target_rps,
                "requested_target_throughput_fraction": request_hp.target_throughput_fraction,
                "requested_workload_mode": request_hp.workload_mode,
                "sagemaker_training_job_name": getattr(env, "job_name", None),
                "s3_uri_metadata": s3_uri_metadata,
                "s3_uri_result_file": s3_uri_result_file,
                "task_output_s3_uri": req.task_output_s3_uri,
                "task_metadata": task_metadata,
            }
            if task == "performance-test":
                benchmark_contract = _build_one_shot_benchmark_contract(
                    request_hp=request_hp,
                    task_metadata=task_metadata,
                    s3_uri_metadata=s3_uri_metadata,
                    s3_uri_result_file=s3_uri_result_file,
                    task_output_s3_uri=req.task_output_s3_uri,
                )
                if benchmark_contract:
                    result[BENCHMARK_CONTRACT_KEY] = benchmark_contract
            result_path = metadata_dir / "result.oneshot.json"
            _write_json(result_path, result)
            _upload_file_to_s3(str(result_path), s3_uri_result_file, s3_client, fail_fast=True)
            logger.info("One-shot task completed successfully.")
        except Exception as e:
            logger.exception("One-shot SageMaker task failed during execution or upload.")
            _upload_directory_to_s3(str(metadata_dir), s3_uri_metadata, s3_client)
            failure_path = Path(env.output_dir) / "failure"
            failure_path.write_text(str(e))
            raise


def _build_one_shot_benchmark_contract(
    *,
    request_hp: OneShotSagemakerHyperparameters,
    task_metadata: dict[str, Any],
    s3_uri_metadata: str | None,
    s3_uri_result_file: str | None,
    task_output_s3_uri: str | None,
) -> dict[str, Any]:
    output_prefixes = {
        "sagemaker_output": request_hp.sagemaker_output_s3_uri,
        "metadata": s3_uri_metadata,
        "result": s3_uri_result_file,
        "task_output": task_output_s3_uri,
    }
    return build_benchmark_contract(
        parameter_s3_uri=request_hp.parameter_zip_s3_uri,
        source_s3_uri=request_hp.source_s3_uri,
        instance_type=request_hp.instance_type,
        training_image=request_hp.training_image,
        samples=task_metadata.get("samples", request_hp.samples),
        sample_pool_size=task_metadata.get("sample_pool_size", request_hp.sample_pool_size),
        target_rps=task_metadata.get("target_rps", request_hp.target_rps),
        target_throughput_fraction=request_hp.target_throughput_fraction,
        max_threads=request_hp.max_threads,
        workload_mode=task_metadata.get("workload_mode", request_hp.workload_mode),
        execution_command=task_metadata.get("execution_command"),
        output_prefixes=output_prefixes,
    )
