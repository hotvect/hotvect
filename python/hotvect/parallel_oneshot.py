import hashlib
import logging
import time
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

import boto3
from botocore.exceptions import ClientError

from hotvect.s3_utils import download_json_from_s3 as _shared_download_json_from_s3
from hotvect.s3_utils import join_s3_uri as s3_join
from hotvect.s3_utils import normalize_s3_prefix_uri
from hotvect.s3_utils import require_s3_uri as _require_s3_uri
from hotvect.s3_utils import upload_json_to_s3 as _shared_upload_json_to_s3
from hotvect.sagemaker import JOB_STATUS_POLLING_WAIT_IN_SECS
from hotvect.sagemaker_contracts import expected_output_descriptor
from hotvect.utils import get_boto_session_after_assuming_role

logger = logging.getLogger(__name__)

PARALLEL_ONESHOT_RUNS_DIRNAME = "_parallel_oneshot_runs"
PARALLEL_ONESHOT_MANIFEST_FILENAME = "parallel_oneshot_manifest.json"
PARALLEL_ONESHOT_SUBMISSION_STATE_FILENAME = "parallel_oneshot_submission_state.json"
PARALLEL_ONESHOT_STATUS_FILENAME = "parallel_oneshot_status.json"
PARALLEL_ONESHOT_SUBMISSION_FILENAME = "_SUBMISSION.json"
PARALLEL_ONESHOT_SUCCESS_FILENAME = "_SUCCESS"
PARALLEL_ONESHOT_SCHEMA_VERSION = 1


@dataclass(frozen=True)
class S3ObjectRef:
    key: str
    size: int


@dataclass
class ParallelShardPlan:
    index: int
    input_s3_uri: str
    source_object_count: int
    source_total_bytes: int
    source_keys: list[str]
    expected_output: dict[str, Any]
    training_job_name: str | None = None
    s3_uri_result_file: str | None = None
    s3_uri_metadata: str | None = None
    task_output_s3_uri: str | None = None
    sagemaker_output_s3_path: str | None = None
    hyperparameter_slug: str | None = None


def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def parallel_submission_pointer_uri(dest_path: str) -> str:
    return s3_join(dest_path, PARALLEL_ONESHOT_SUBMISSION_FILENAME)


def parallel_success_marker_uri(dest_path: str) -> str:
    return s3_join(dest_path, PARALLEL_ONESHOT_SUCCESS_FILENAME)


def managed_run_base_uri(s3_output_base: str, run_id: str) -> str:
    return s3_join(s3_output_base, PARALLEL_ONESHOT_RUNS_DIRNAME, run_id)


def managed_manifest_uri(s3_output_base: str, run_id: str) -> str:
    return s3_join(managed_run_base_uri(s3_output_base, run_id), PARALLEL_ONESHOT_MANIFEST_FILENAME)


def managed_submission_state_uri(s3_output_base: str, run_id: str) -> str:
    return s3_join(managed_run_base_uri(s3_output_base, run_id), PARALLEL_ONESHOT_SUBMISSION_STATE_FILENAME)


def managed_status_uri(s3_output_base: str, run_id: str) -> str:
    return s3_join(managed_run_base_uri(s3_output_base, run_id), PARALLEL_ONESHOT_STATUS_FILENAME)


def stable_shard_index(key: str, job_parallelism: int) -> int:
    digest = hashlib.sha256(key.encode("utf-8")).hexdigest()
    return int(digest, 16) % job_parallelism


def create_session(role_arn_to_assume: str | None):
    return get_boto_session_after_assuming_role(role_arn_to_assume) if role_arn_to_assume else boto3.Session()


def put_json_to_s3(s3_client, uri: str, payload: dict[str, Any]) -> None:
    _shared_upload_json_to_s3(payload, uri, s3_client, fail_fast=True)


def put_text_to_s3(s3_client, uri: str, body: str = "") -> None:
    bucket, key = _require_s3_uri(uri)
    s3_client.put_object(Bucket=bucket, Key=key, Body=body.encode("utf-8"))


def get_json_from_s3(s3_client, uri: str) -> dict[str, Any]:
    return _shared_download_json_from_s3(uri, s3_client)


def s3_object_exists(s3_client, uri: str) -> bool:
    bucket, key = _require_s3_uri(uri)
    try:
        s3_client.head_object(Bucket=bucket, Key=key)
        return True
    except ClientError as exc:
        if exc.response.get("Error", {}).get("Code") in {"404", "NoSuchKey", "NotFound"}:
            return False
        raise


def prefix_has_objects(s3_client, prefix_uri: str) -> bool:
    bucket, key_prefix = _require_s3_uri(prefix_uri)
    response = s3_client.list_objects_v2(Bucket=bucket, Prefix=key_prefix.rstrip("/") + "/", MaxKeys=1)
    return bool(response.get("Contents"))


def list_source_objects(s3_client, source_s3_uri: str) -> list[S3ObjectRef]:
    normalized_source_s3_uri = normalize_s3_prefix_uri(source_s3_uri)
    bucket, key_prefix = _require_s3_uri(normalized_source_s3_uri)
    paginator = s3_client.get_paginator("list_objects_v2")
    objects: list[S3ObjectRef] = []
    for page in paginator.paginate(Bucket=bucket, Prefix=key_prefix):
        for entry in page.get("Contents", []):
            key = entry["Key"]
            if key.endswith("/"):
                continue
            relative_key = _relative_source_key(normalized_source_s3_uri, key)
            relative_parts = [part for part in relative_key.split("/") if part]
            if not relative_parts or any(part.startswith("_") for part in relative_parts):
                continue
            objects.append(S3ObjectRef(key=key, size=int(entry.get("Size", 0))))
    if not objects:
        raise ValueError(f"No source objects found under {normalized_source_s3_uri}")
    return sorted(objects, key=lambda item: item.key)


def _relative_source_key(source_s3_uri: str, object_key: str) -> str:
    _, source_key = _require_s3_uri(source_s3_uri)
    normalized_source_key = source_key.rstrip("/")
    if not normalized_source_key:
        return object_key
    prefix = normalized_source_key + "/"
    if object_key.startswith(prefix):
        return object_key[len(prefix) :]
    raise ValueError(f"Object key {object_key!r} does not live under source prefix {source_s3_uri!r}")


def _group_parallel_shard_objects(
    source_objects: Iterable[S3ObjectRef], job_parallelism: int, source_s3_uri: str
) -> dict[int, list[S3ObjectRef]]:
    if job_parallelism <= 1:
        raise ValueError(f"job_parallelism must be > 1, got {job_parallelism}")
    shard_buckets: list[list[S3ObjectRef]] = [[] for _ in range(job_parallelism)]
    for obj in source_objects:
        shard_key = _relative_source_key(source_s3_uri, obj.key)
        shard_buckets[stable_shard_index(shard_key, job_parallelism)].append(obj)
    return {idx: bucket for idx, bucket in enumerate(shard_buckets) if bucket}


def build_parallel_shard_groups(
    source_objects: Iterable[S3ObjectRef], job_parallelism: int, source_s3_uri: str
) -> list[list[S3ObjectRef]]:
    return list(_group_parallel_shard_objects(source_objects, job_parallelism, source_s3_uri).values())


def build_parallel_shard_plans(
    *,
    task: str,
    source_s3_uri: str,
    dest_path: str,
    compression: str,
    job_parallelism: int,
    source_objects: Iterable[S3ObjectRef],
) -> list[ParallelShardPlan]:
    if job_parallelism <= 1:
        raise ValueError(f"job_parallelism must be > 1, got {job_parallelism}")
    normalized_source_s3_uri = normalize_s3_prefix_uri(source_s3_uri)
    source_object_list = list(source_objects)
    if not source_object_list:
        raise ValueError(f"No source objects found under {normalized_source_s3_uri}")
    shard_groups = _group_parallel_shard_objects(source_object_list, job_parallelism, normalized_source_s3_uri)
    shard_indices = sorted(shard_groups)

    plans: list[ParallelShardPlan] = []
    for shard_index in shard_indices:
        shard_objects = shard_groups[shard_index]
        plans.append(
            ParallelShardPlan(
                index=shard_index,
                input_s3_uri=normalized_source_s3_uri,
                source_object_count=len(shard_objects),
                source_total_bytes=sum(obj.size for obj in shard_objects),
                source_keys=[obj.key for obj in shard_objects],
                expected_output=expected_output_descriptor(task, dest_path, shard_index, compression),
            )
        )
    return plans


def build_parallel_worker_plans(
    *,
    task: str,
    source_s3_uri: str,
    dest_path: str,
    compression: str,
    job_parallelism: int,
) -> list[ParallelShardPlan]:
    if job_parallelism <= 1:
        raise ValueError(f"job_parallelism must be > 1, got {job_parallelism}")
    normalized_source_s3_uri = normalize_s3_prefix_uri(source_s3_uri)
    return [
        ParallelShardPlan(
            index=worker_index,
            input_s3_uri=normalized_source_s3_uri,
            source_object_count=0,
            source_total_bytes=0,
            source_keys=[],
            expected_output=expected_output_descriptor(task, dest_path, worker_index, compression),
        )
        for worker_index in range(job_parallelism)
    ]


def build_submission_pointer(
    *, task: str, run_id: str, manifest_s3_uri: str, submission_state_s3_uri: str, dest_path: str
) -> dict[str, Any]:
    return {
        "schema_version": PARALLEL_ONESHOT_SCHEMA_VERSION,
        "task": task,
        "run_id": run_id,
        "created_at": _utc_now_iso(),
        "manifest_s3_uri": manifest_s3_uri,
        "submission_state_s3_uri": submission_state_s3_uri,
        "dest_path": dest_path,
    }


def build_parallel_manifest(
    *,
    run_id: str,
    task: str,
    source_s3_uri: str,
    dest_path: str,
    s3_output_base: str,
    compression: str,
    job_parallelism_requested: int,
    shards: list[ParallelShardPlan],
    verify_command: str,
) -> dict[str, Any]:
    return {
        "schema_version": PARALLEL_ONESHOT_SCHEMA_VERSION,
        "task": task,
        "created_at": _utc_now_iso(),
        "run_id": run_id,
        "source_s3_uri": source_s3_uri,
        "dest_path": dest_path,
        "s3_output_base": s3_output_base,
        "compression": compression,
        "job_parallelism_requested": job_parallelism_requested,
        "job_parallelism_actual": len(shards),
        "verify_command": verify_command,
        "shards": [
            {
                "index": shard.index,
                "input_s3_uri": shard.input_s3_uri,
                "source_object_count": shard.source_object_count,
                "source_total_bytes": shard.source_total_bytes,
                "source_keys": shard.source_keys,
                "expected_output": shard.expected_output,
            }
            for shard in shards
        ],
    }


def build_parallel_submission_state(
    *, run_id: str, task: str, dest_path: str, shards: list[ParallelShardPlan]
) -> dict[str, Any]:
    return {
        "schema_version": PARALLEL_ONESHOT_SCHEMA_VERSION,
        "task": task,
        "run_id": run_id,
        "dest_path": dest_path,
        "updated_at": _utc_now_iso(),
        "shards": [
            {
                "index": shard.index,
                "training_job_name": shard.training_job_name,
                "s3_uri_result_file": shard.s3_uri_result_file,
                "s3_uri_metadata": shard.s3_uri_metadata,
                "task_output_s3_uri": shard.task_output_s3_uri,
                "sagemaker_output_s3_path": shard.sagemaker_output_s3_path,
                "hyperparameter_slug": shard.hyperparameter_slug,
            }
            for shard in shards
        ],
    }


def _load_submission_pointer_for_dest(*, s3_client, dest_path: str) -> dict[str, Any]:
    return get_json_from_s3(s3_client, parallel_submission_pointer_uri(dest_path))


def _validate_managed_uri(*, uri: str, s3_output_base: str, resource_name: str) -> None:
    if not uri.startswith(s3_output_base.rstrip("/") + "/"):
        raise ValueError(
            f"Stored {resource_name} {uri} does not live under the requested --s3-output-base {s3_output_base}"
        )


def _validate_submission_state_against_manifest(*, manifest: dict[str, Any], submission_state: dict[str, Any]) -> None:
    for field in ("run_id", "task", "dest_path"):
        if submission_state.get(field) != manifest.get(field):
            raise ValueError(
                f"Submission state {field} {submission_state.get(field)!r} does not match manifest {field} {manifest.get(field)!r}"
            )
    manifest_shard_indices = [shard["index"] for shard in manifest["shards"]]
    submission_state_shard_indices = [shard["index"] for shard in submission_state["shards"]]
    if submission_state_shard_indices != manifest_shard_indices:
        raise ValueError(
            "Submission state shard indices do not match manifest shard indices: "
            f"{submission_state_shard_indices} != {manifest_shard_indices}"
        )


def verify_parallel_manifest(
    *, s3_client, sagemaker_client, manifest: dict[str, Any], submission_state: dict[str, Any], write_success: bool
) -> dict[str, Any]:
    """Treat completion as a SageMaker job-status question, not an output-probing question."""
    _validate_submission_state_against_manifest(manifest=manifest, submission_state=submission_state)
    shard_reports: list[dict[str, Any]] = []
    failed_jobs: list[str] = []
    running_jobs: list[str] = []
    unsubmitted_shards: list[int] = []

    for shard in submission_state["shards"]:
        shard_index = shard["index"]
        job_name = shard["training_job_name"]
        if job_name is None:
            job_status = "Unsubmitted"
            unsubmitted_shards.append(shard_index)
        else:
            job_description = sagemaker_client.describe_training_job(TrainingJobName=job_name)
            job_status = job_description["TrainingJobStatus"]
            if job_status == "Completed":
                pass
            elif job_status in {"InProgress", "Stopping", "Pending"}:
                running_jobs.append(job_name)
            else:
                failed_jobs.append(job_name)

        shard_reports.append(
            {
                "index": shard_index,
                "training_job_name": job_name,
                "job_status": job_status,
            }
        )

    complete = not failed_jobs and not running_jobs and not unsubmitted_shards
    success_uri = parallel_success_marker_uri(manifest["dest_path"])
    success_written = False
    if complete and write_success and not s3_object_exists(s3_client, success_uri):
        put_text_to_s3(s3_client, success_uri)
        success_written = True

    return {
        "checked_at": _utc_now_iso(),
        "run_id": manifest["run_id"],
        "task": manifest["task"],
        "dest_path": manifest["dest_path"],
        "complete": complete,
        "success_marker_uri": success_uri,
        "success_marker_exists": s3_object_exists(s3_client, success_uri),
        "success_written": success_written,
        "failed_jobs": failed_jobs,
        "running_jobs": running_jobs,
        "unsubmitted_shards": unsubmitted_shards,
        "shards": shard_reports,
    }


def wait_for_parallel_manifest(
    *,
    s3_client,
    sagemaker_client,
    manifest: dict[str, Any],
    submission_state: dict[str, Any],
    status_s3_uri: str,
) -> dict[str, Any]:
    while True:
        result = verify_parallel_manifest(
            s3_client=s3_client,
            sagemaker_client=sagemaker_client,
            manifest=manifest,
            submission_state=submission_state,
            write_success=True,
        )
        put_json_to_s3(s3_client, status_s3_uri, result)
        if result["complete"]:
            return result
        if result["unsubmitted_shards"]:
            return result
        if result["failed_jobs"]:
            return result
        logger.info(
            "Parallel one-shot run %s still in progress. Running jobs: %s. Checking again in %s seconds.",
            manifest["run_id"],
            result["running_jobs"],
            JOB_STATUS_POLLING_WAIT_IN_SECS,
        )
        time.sleep(JOB_STATUS_POLLING_WAIT_IN_SECS)


def load_parallel_manifest_for_dest(*, s3_client, dest_path: str, s3_output_base: str) -> dict[str, Any]:
    submission_pointer = _load_submission_pointer_for_dest(s3_client=s3_client, dest_path=dest_path)
    manifest_s3_uri = submission_pointer["manifest_s3_uri"]
    _validate_managed_uri(uri=manifest_s3_uri, s3_output_base=s3_output_base, resource_name="manifest")
    return get_json_from_s3(s3_client, manifest_s3_uri)


def load_parallel_submission_state_for_dest(*, s3_client, dest_path: str, s3_output_base: str) -> dict[str, Any]:
    submission_pointer = _load_submission_pointer_for_dest(s3_client=s3_client, dest_path=dest_path)
    submission_state_s3_uri = submission_pointer["submission_state_s3_uri"]
    _validate_managed_uri(
        uri=submission_state_s3_uri,
        s3_output_base=s3_output_base,
        resource_name="submission state",
    )
    return get_json_from_s3(s3_client, submission_state_s3_uri)


def validate_parallel_dest_path_is_empty(*, s3_client, dest_path: str) -> None:
    if prefix_has_objects(s3_client, dest_path):
        raise ValueError(
            f"Destination already contains objects under {dest_path}. "
            "Parallel one-shot v1 requires a fresh output prefix."
        )
    for uri in (
        parallel_submission_pointer_uri(dest_path),
        parallel_success_marker_uri(dest_path),
    ):
        if s3_object_exists(s3_client, uri):
            raise ValueError(f"Destination already contains Hotvect marker file: {uri}")
