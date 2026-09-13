import json
from datetime import datetime, timezone

import pytest

from hotvect.status import (
    STATUS_LOG_PREFIX,
    SageMakerStatusReader,
    load_backtest_submission_job_names,
    parse_status_log_message,
)


def _event(event_type: str, *, records_processed: int) -> dict:
    return {
        "event_type": event_type,
        "stage": "predict",
        "updated_at": "2000-07-23T09:12:30Z",
        "records_processed": records_processed,
        "throughput": 12.5,
    }


def test_parse_status_log_message_requires_tagged_valid_event():
    assert parse_status_log_message("ordinary output") is None
    assert (
        parse_status_log_message(STATUS_LOG_PREFIX + json.dumps(_event("progress", records_processed=4)))[
            "records_processed"
        ]
        == 4
    )

    with pytest.raises(ValueError, match="event_type"):
        parse_status_log_message(
            STATUS_LOG_PREFIX + json.dumps({"event_type": "unknown", "stage": "predict", "updated_at": "now"})
        )


def test_load_backtest_submission_job_names_is_strict(tmp_path):
    submission = tmp_path / "backtest_submission_manifest.json"
    submission.write_text(
        json.dumps({"jobs": [{"training_job_name": "job-a"}, {"training_job_name": "job-b"}]}),
        encoding="utf-8",
    )
    assert load_backtest_submission_job_names(submission) == ["job-a", "job-b"]

    submission.write_text(json.dumps({"jobs": [{"training_job_name": "job-a"}] * 2}), encoding="utf-8")
    with pytest.raises(ValueError, match="duplicate"):
        load_backtest_submission_job_names(submission)


def test_sagemaker_status_reader_combines_job_and_latest_progress():
    class FakeSageMakerClient:
        def describe_training_job(self, *, TrainingJobName):
            assert TrainingJobName == "job-a"
            return {
                "TrainingJobStatus": "InProgress",
                "SecondaryStatus": "Training",
                "CreationTime": datetime(2000, 7, 23, 9, 0, tzinfo=timezone.utc),
                "TrainingStartTime": datetime(2000, 7, 23, 9, 1, tzinfo=timezone.utc),
            }

    class FakeLogsClient:
        def __init__(self):
            self.start_requests = []

        def start_query(self, **request):
            self.start_requests.append(request)
            return {"queryId": "query-1"}

        def get_query_results(self, *, queryId):
            assert queryId == "query-1"
            return {
                "status": "Complete",
                "results": [
                    [
                        {"field": "timestamp_ms", "value": "9.6434355E11"},
                        {
                            "field": "@message",
                            "value": STATUS_LOG_PREFIX + json.dumps(_event("progress", records_processed=8)),
                        },
                        {"field": "@ptr", "value": "pointer"},
                    ]
                ],
            }

    logs_client = FakeLogsClient()

    class FakeSession:
        def client(self, service_name, region_name=None):
            assert region_name == "eu-central-1"
            return FakeSageMakerClient() if service_name == "sagemaker" else logs_client

    status = SageMakerStatusReader(session=FakeSession(), region_name="eu-central-1").read_job("job-a")

    assert status["training_job_status"] == "InProgress"
    assert status["progress"]["records_processed"] == 8
    assert status["progress_log_timestamp_ms"] == 964343550000
    assert logs_client.start_requests[0]["logGroupName"] == "/aws/sagemaker/TrainingJobs"
    assert "@logStream like /^job-a\\//" in logs_client.start_requests[0]["queryString"]
    assert "@message like /^HOTVECT_STATUS /" in logs_client.start_requests[0]["queryString"]


def test_sagemaker_status_reader_rejects_invalid_job_name():
    class FakeSession:
        def client(self, service_name, region_name=None):
            return object()

    reader = SageMakerStatusReader(session=FakeSession())
    with pytest.raises(ValueError, match="Invalid SageMaker training job name"):
        reader.read_job("job/name")
