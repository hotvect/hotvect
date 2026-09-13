from __future__ import annotations

import json
import re
import time
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any

STATUS_LOG_PREFIX = "HOTVECT_STATUS "
SAGEMAKER_LOG_GROUP = "/aws/sagemaker/TrainingJobs"
TERMINAL_JOB_STATUSES = frozenset({"Completed", "Failed", "Stopped"})
FAILED_JOB_STATUSES = frozenset({"Failed", "Stopped"})
PROGRESS_EVENT_TYPES = frozenset({"begin", "progress", "end", "failure"})
SAGEMAKER_JOB_NAME_PATTERN = re.compile(r"[A-Za-z0-9](?:-*[A-Za-z0-9])*")


def parse_status_log_message(message: str) -> dict[str, Any] | None:
    if not message.startswith(STATUS_LOG_PREFIX):
        return None

    payload = json.loads(message.removeprefix(STATUS_LOG_PREFIX))
    if not isinstance(payload, dict):
        raise ValueError("HOTVECT_STATUS payload must be a JSON object")

    event_type = payload.get("event_type")
    if event_type not in PROGRESS_EVENT_TYPES:
        raise ValueError(f"HOTVECT_STATUS event_type must be one of {sorted(PROGRESS_EVENT_TYPES)}, got {event_type!r}")
    if not isinstance(payload.get("stage"), str) or not payload["stage"]:
        raise ValueError("HOTVECT_STATUS stage must be a non-empty string")
    if not isinstance(payload.get("updated_at"), str) or not payload["updated_at"]:
        raise ValueError("HOTVECT_STATUS updated_at must be a non-empty string")
    return payload


def load_backtest_submission_job_names(submission_path: str | Path) -> list[str]:
    path = Path(submission_path)
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"Backtest submission must contain a JSON object: {path}")

    jobs = payload.get("jobs")
    if not isinstance(jobs, list) or not jobs:
        raise ValueError(f"Backtest submission jobs must be a non-empty array: {path}")

    job_names: list[str] = []
    for index, job in enumerate(jobs):
        if not isinstance(job, dict):
            raise ValueError(f"Backtest submission jobs[{index}] must be an object: {path}")
        job_name = job.get("training_job_name")
        if not isinstance(job_name, str) or not job_name:
            raise ValueError(f"Backtest submission jobs[{index}].training_job_name must be a non-empty string: {path}")
        job_names.append(job_name)

    if len(set(job_names)) != len(job_names):
        raise ValueError(f"Backtest submission contains duplicate training_job_name values: {path}")
    return job_names


def _iso_timestamp(value: Any) -> str | None:
    if value is None:
        return None
    if not isinstance(value, datetime):
        raise ValueError(f"Expected datetime from SageMaker, got {type(value).__name__}")
    return value.astimezone(timezone.utc).isoformat()


def _millis(value: datetime) -> int:
    return int(value.timestamp() * 1000)


def _validate_job_name(job_name: str) -> None:
    if len(job_name) > 63 or SAGEMAKER_JOB_NAME_PATTERN.fullmatch(job_name) is None:
        raise ValueError(f"Invalid SageMaker training job name: {job_name!r}")


class SageMakerStatusReader:
    def __init__(self, *, session, region_name: str | None = None):
        self._sagemaker_client = session.client("sagemaker", region_name=region_name)
        self._logs_client = session.client("logs", region_name=region_name)

    def read_job(self, job_name: str, *, progress_start_time_ms: int | None = None) -> dict[str, Any]:
        _validate_job_name(job_name)
        description = self._sagemaker_client.describe_training_job(TrainingJobName=job_name)
        creation_time = description.get("CreationTime")
        if not isinstance(creation_time, datetime):
            raise ValueError(f"DescribeTrainingJob response for {job_name!r} is missing CreationTime")

        latest_progress = self._latest_progress_event(
            job_name,
            start_time_ms=progress_start_time_ms if progress_start_time_ms is not None else _millis(creation_time),
        )
        status = description.get("TrainingJobStatus")
        if not isinstance(status, str) or not status:
            raise ValueError(f"DescribeTrainingJob response for {job_name!r} is missing TrainingJobStatus")

        result: dict[str, Any] = {
            "training_job_name": job_name,
            "training_job_status": status,
            "secondary_status": description.get("SecondaryStatus"),
            "creation_time": _iso_timestamp(creation_time),
            "training_start_time": _iso_timestamp(description.get("TrainingStartTime")),
            "training_end_time": _iso_timestamp(description.get("TrainingEndTime")),
            "failure_reason": description.get("FailureReason"),
            "progress": None,
            "progress_log_timestamp_ms": None,
        }
        if latest_progress is not None:
            result["progress"] = latest_progress["payload"]
            result["progress_log_timestamp_ms"] = latest_progress["timestamp"]
        return result

    def _latest_progress_event(self, job_name: str, *, start_time_ms: int) -> dict[str, Any] | None:
        query = (
            "fields toMillis(@timestamp) as timestamp_ms, @message "
            f"| filter @logStream like /^{job_name}\\// and @message like /^{STATUS_LOG_PREFIX}/ "
            "| sort timestamp_ms desc | limit 1"
        )
        response = self._logs_client.start_query(
            logGroupName=SAGEMAKER_LOG_GROUP,
            startTime=start_time_ms // 1000,
            endTime=int(datetime.now(timezone.utc).timestamp()) + 1,
            queryString=query,
        )
        query_id = response.get("queryId")
        if not isinstance(query_id, str) or not query_id:
            raise ValueError("StartQuery response is missing queryId")

        while True:
            response = self._logs_client.get_query_results(queryId=query_id)
            status = response.get("status")
            if status == "Complete":
                return self._parse_query_results(response)
            if status not in {"Scheduled", "Running"}:
                raise RuntimeError(f"CloudWatch Logs Insights query {query_id} ended with status {status!r}")
            time.sleep(0.25)

    @staticmethod
    def _parse_query_results(response: dict[str, Any]) -> dict[str, Any] | None:
        results = response.get("results")
        if not isinstance(results, list):
            raise ValueError("GetQueryResults response is missing results")
        if not results:
            return None
        if len(results) != 1 or not isinstance(results[0], list):
            raise ValueError("Expected exactly one CloudWatch status result")

        fields: dict[str, str] = {}
        for item in results[0]:
            if (
                not isinstance(item, dict)
                or not isinstance(item.get("field"), str)
                or not isinstance(item.get("value"), str)
            ):
                raise ValueError("CloudWatch status result fields require string field and value")
            field = item["field"]
            if field in fields:
                raise ValueError(f"CloudWatch status result contains duplicate field {field!r}")
            fields[field] = item["value"]

        message = fields.get("@message")
        timestamp_value = fields.get("timestamp_ms")
        if message is None or timestamp_value is None:
            raise ValueError("CloudWatch status result requires @message and timestamp_ms")
        try:
            timestamp = Decimal(timestamp_value)
        except InvalidOperation as error:
            raise ValueError(f"CloudWatch status timestamp is not numeric: {timestamp_value!r}") from error
        if not timestamp.is_finite() or timestamp != timestamp.to_integral_value():
            raise ValueError(f"CloudWatch status timestamp is not an integer: {timestamp_value!r}")

        payload = parse_status_log_message(message)
        if payload is None:
            raise ValueError("CloudWatch status result does not start with HOTVECT_STATUS")
        return {"timestamp": int(timestamp), "payload": payload}


def merge_previous_progress(current: dict[str, Any], previous: dict[str, Any] | None) -> dict[str, Any]:
    if current["progress"] is not None or previous is None:
        return current
    current["progress"] = previous["progress"]
    current["progress_log_timestamp_ms"] = previous["progress_log_timestamp_ms"]
    return current


def jobs_are_terminal(jobs: list[dict[str, Any]]) -> bool:
    return all(job["training_job_status"] in TERMINAL_JOB_STATUSES for job in jobs)


def jobs_have_failed(jobs: list[dict[str, Any]]) -> bool:
    return any(job["training_job_status"] in FAILED_JOB_STATUSES for job in jobs)
