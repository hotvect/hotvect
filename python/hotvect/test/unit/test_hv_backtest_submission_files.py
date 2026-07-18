import datetime
import importlib.util
import json
import sys
from importlib.machinery import SourceFileLoader
from pathlib import Path

from hotvect.backtest import BacktestIterationResult, BacktestResult


def _load_hv_module():
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "bin" / "hv"
        if candidate.exists():
            loader = SourceFileLoader("hv_cli_backtest_manifest", str(candidate))
            spec = importlib.util.spec_from_loader(loader.name, loader)
            assert spec is not None
            module = importlib.util.module_from_spec(spec)
            loader.exec_module(module)
            return module
    raise FileNotFoundError("Could not locate bin/hv relative to test file")


def test_hv_backtest_remote_writes_submission_manifest_and_status_in_unique_metadata_dirs(monkeypatch, tmp_path: Path):
    hv = _load_hv_module()

    output_dir = tmp_path / "out"
    scratch_dir = tmp_path / "scratch"
    monkeypatch.setattr("hotvect.extra.config.try_load_config", lambda: {})
    monkeypatch.setattr(
        "hotvect.backtest.run_backtest_on_git_references",
        lambda **_kwargs: [
            BacktestResult(
                algo_git_reference="candidate-a",
                backtest_iteration_results=[
                    BacktestIterationResult(
                        parameter_version="pv1",
                        test_data_time="2026-04-01",
                        result={
                            "training_job_name": "job-a",
                            "training_job_arn": "arn:aws:sagemaker:eu-central-1:123:training-job/job-a",
                            "submission_status": "submitted",
                            "s3_uri_result_file": "s3://bucket/out/job-a/pv1/result.json",
                            "s3_uri_metadata": "s3://bucket/out/job-a/pv1/metadata",
                        },
                    )
                ],
            )
        ],
    )

    class FakeDateTime:
        _values = iter(
            [
                datetime.datetime(2026, 4, 1, 12, 0, 0, tzinfo=datetime.UTC),
                datetime.datetime(2026, 4, 1, 12, 0, 1, tzinfo=datetime.UTC),
            ]
        )

        @staticmethod
        def now(_tz):
            return next(FakeDateTime._values)

    monkeypatch.setattr(hv, "datetime", FakeDateTime)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "hv",
            "backtest",
            "--git-reference",
            "candidate-a",
            "--algo-repo-url",
            "https://example.invalid/repo.git",
            "--output-base-dir",
            str(output_dir),
            "--scratch-dir",
            str(scratch_dir),
            "--last-test-time",
            "2026-04-01",
            "--sagemaker",
            "--sagemaker-job-prefix",
            "ml-exp-test",
            "--role-arn",
            "arn:aws:iam::123456789012:role/SageMakerExecutionRole",
            "--s3-output-base",
            "s3://bucket/prefix",
            "--instance-type",
            "ml.m5.large",
        ],
    )

    hv.main()
    hv.main()

    submission_root = output_dir / "meta" / "_backtest_submissions"
    run_dirs = sorted(path for path in submission_root.iterdir() if path.is_dir())
    assert [path.name for path in run_dirs] == ["20260401T120000.000000Z", "20260401T120001.000000Z"]

    assert not (output_dir / "backtest_submission_manifest.json").exists()
    assert not (output_dir / "backtest_submission_status.json").exists()

    for run_dir in run_dirs:
        manifest = json.loads((run_dir / "backtest_submission_manifest.json").read_text())
        status = json.loads((run_dir / "backtest_submission_status.json").read_text())

        assert manifest["job_count"] == 1
        assert manifest["jobs"][0]["training_job_name"] == "job-a"
        assert manifest["jobs"][0]["algo_git_reference"] == "candidate-a"

        assert status["job_count"] == 1
        assert status["jobs"][0]["training_job_name"] == "job-a"
        assert status["jobs"][0]["submission_status"] == "submitted"
