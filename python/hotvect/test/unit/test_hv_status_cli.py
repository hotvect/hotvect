import importlib.util
import json
import sys
from importlib.machinery import SourceFileLoader
from pathlib import Path
from types import ModuleType


def _load_hv_module(monkeypatch):
    fake_hotvectjar = ModuleType("hotvect.hotvectjar")
    fake_hotvectjar.HOTVECT_JAR_PATH = Path("/tmp/offline.jar")
    fake_hotvectjar.HOTVECT_ALGORITHM_SERVE_JAR_PATH = Path("/tmp/serve.jar")
    fake_hotvectjar.HOTVECT_ALGORITHM_DEMO_JAR_PATH = Path("/tmp/demo.jar")
    monkeypatch.setitem(sys.modules, "hotvect.hotvectjar", fake_hotvectjar)
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "bin" / "hv"
        if candidate.exists():
            loader = SourceFileLoader("hv_cli_status", str(candidate))
            spec = importlib.util.spec_from_loader(loader.name, loader)
            assert spec is not None
            module = importlib.util.module_from_spec(spec)
            loader.exec_module(module)
            return module
    raise FileNotFoundError("Could not locate bin/hv relative to test file")


def _completed_status(job_name: str) -> dict:
    return {
        "training_job_name": job_name,
        "training_job_status": "Completed",
        "secondary_status": "Completed",
        "creation_time": "2000-07-23T09:00:00+00:00",
        "training_start_time": "2000-07-23T09:01:00+00:00",
        "training_end_time": "2000-07-23T09:10:00+00:00",
        "failure_reason": None,
        "progress": {
            "event_type": "end",
            "stage": "predict",
            "updated_at": "2000-07-23T09:10:00Z",
            "records_processed": 20,
            "throughput": 10.0,
        },
        "progress_log_timestamp_ms": 2,
    }


def test_hv_status_sagemaker_json(monkeypatch, capsys):
    hv = _load_hv_module(monkeypatch)

    class FakeReader:
        def __init__(self, *, session, region_name=None):
            assert session == "session"
            assert region_name == "eu-central-1"

        def read_job(self, job_name, *, progress_start_time_ms=None):
            assert progress_start_time_ms is None
            return _completed_status(job_name)

    monkeypatch.setattr(hv, "create_session", lambda role: "session")
    monkeypatch.setattr(hv, "SageMakerStatusReader", FakeReader)

    assert (
        hv.main(
            [
                "status",
                "sagemaker",
                "--job-name",
                "job-a",
                "--region",
                "eu-central-1",
                "--output",
                "json",
            ]
        )
        == 0
    )
    payload = json.loads(capsys.readouterr().out)
    assert payload["jobs"][0]["training_job_name"] == "job-a"
    assert payload["jobs"][0]["progress"]["records_processed"] == 20


def test_hv_status_backtest_reads_submission(monkeypatch, tmp_path, capsys):
    hv = _load_hv_module(monkeypatch)
    submission = tmp_path / "backtest_submission_manifest.json"
    submission.write_text(
        json.dumps({"jobs": [{"training_job_name": "job-a"}, {"training_job_name": "job-b"}]}),
        encoding="utf-8",
    )

    class FakeReader:
        def __init__(self, *, session, region_name=None):
            pass

        def read_job(self, job_name, *, progress_start_time_ms=None):
            return _completed_status(job_name)

    monkeypatch.setattr(hv, "create_session", lambda role: "session")
    monkeypatch.setattr(hv, "SageMakerStatusReader", FakeReader)

    assert hv.main(["status", "backtest", "--submission", str(submission)]) == 0
    output = capsys.readouterr().out
    assert "Completed  job-a  stage=predict" in output
    assert "Completed  job-b  stage=predict" in output
