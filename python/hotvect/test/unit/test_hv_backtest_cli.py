import importlib.util
import json
import logging
import sys
from importlib.machinery import SourceFileLoader
from pathlib import Path

import pytest

from hotvect.backtest import BacktestResult


def _load_hv_module():
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "bin" / "hv"
        if candidate.exists():
            loader = SourceFileLoader("hv_cli_backtest", str(candidate))
            spec = importlib.util.spec_from_loader(loader.name, loader)
            assert spec is not None
            module = importlib.util.module_from_spec(spec)
            loader.exec_module(module)
            return module
    raise FileNotFoundError("Could not locate bin/hv relative to test file")


def test_hv_backtest_exits_nonzero_when_remote_submission_returns_errors(monkeypatch, tmp_path: Path, caplog):
    hv = _load_hv_module()

    monkeypatch.setattr("hotvect.extra.config.try_load_config", lambda: {})
    monkeypatch.setattr(
        "hotvect.backtest.run_backtest_on_git_references",
        lambda **_kwargs: [
            BacktestResult(algo_git_reference="candidate-a", error="ExpiredToken: token expired"),
        ],
    )

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
            str(tmp_path / "out"),
            "--scratch-dir",
            str(tmp_path / "scratch"),
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

    with caplog.at_level(logging.INFO):
        with pytest.raises(SystemExit) as excinfo:
            hv.main()

    assert excinfo.value.code == 1
    assert "ExpiredToken: token expired" in caplog.text
    assert "SageMaker jobs submitted successfully!" not in caplog.text


def test_hv_backtest_remote_success_keeps_zero_exit(monkeypatch, tmp_path: Path, caplog):
    hv = _load_hv_module()

    monkeypatch.setattr("hotvect.extra.config.try_load_config", lambda: {})
    monkeypatch.setattr(
        "hotvect.backtest.run_backtest_on_git_references",
        lambda **_kwargs: [
            BacktestResult(
                algo_git_reference="candidate-a",
                backtest_iteration_results=[],
            ),
        ],
    )

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
            str(tmp_path / "out"),
            "--scratch-dir",
            str(tmp_path / "scratch"),
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

    with caplog.at_level(logging.INFO):
        hv.main()

    assert "SageMaker jobs submitted successfully!" in caplog.text
    assert "Backtest execution completed" in caplog.text


def test_hv_backtest_with_explicit_directories_does_not_load_config(monkeypatch, tmp_path: Path):
    hv = _load_hv_module()

    def fail_if_config_is_loaded():
        pytest.fail("config should not be loaded when all backtest directories are explicit")

    monkeypatch.setattr("hotvect.extra.config.load_config", fail_if_config_is_loaded)
    monkeypatch.setattr("hotvect.extra.config.try_load_config", fail_if_config_is_loaded)
    monkeypatch.setattr(
        "hotvect.backtest.run_backtest_on_git_references",
        lambda **_kwargs: [],
    )

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
            "--data-base-dir",
            str(tmp_path / "data"),
            "--output-base-dir",
            str(tmp_path / "out"),
            "--scratch-dir",
            str(tmp_path / "scratch"),
            "--last-test-time",
            "2026-04-01",
        ],
    )

    hv.main()


def test_hv_backtest_records_algorithm_override_metadata(monkeypatch, tmp_path: Path):
    hv = _load_hv_module()
    captured = {}
    override_path = tmp_path / "candidate.override.json"
    override = {
        "hotvect_execution_parameters": {
            "with_parameter": "s3://bucket/params.zip",
        }
    }
    override_path.write_text(json.dumps(override))

    def fake_run_backtest_on_git_references(**kwargs):
        captured.update(kwargs)
        return [
            BacktestResult(
                algo_git_reference="candidate-a",
                backtest_iteration_results=[],
            ),
        ]

    monkeypatch.setattr("hotvect.extra.config.load_config", lambda: {})
    monkeypatch.setattr("hotvect.backtest.run_backtest_on_git_references", fake_run_backtest_on_git_references)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "hv",
            "backtest",
            "--git-reference",
            "candidate-a",
            "--algorithm-override",
            str(override_path),
            "--algorithm-override-reason",
            "Use date-aligned production parameters",
            "--algo-repo-url",
            "https://example.invalid/repo.git",
            "--data-base-dir",
            str(tmp_path / "data"),
            "--output-base-dir",
            str(tmp_path / "out"),
            "--scratch-dir",
            str(tmp_path / "scratch"),
            "--last-test-time",
            "2000-04-01",
        ],
    )

    hv.main()

    assert captured["algo_git_references"] == [
        (
            "candidate-a",
            override,
            {
                "supplied": True,
                "fields": ["hotvect_execution_parameters.with_parameter"],
                "files": [str(override_path.resolve())],
                "reasons": ["Use date-aligned production parameters"],
            },
        )
    ]


def test_hv_backtest_rejects_override_reason_without_override(monkeypatch, tmp_path: Path, capsys):
    hv = _load_hv_module()
    monkeypatch.setattr("hotvect.extra.config.load_config", lambda: {})
    monkeypatch.setattr("hotvect.extra.config.try_load_config", lambda: {})
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "hv",
            "backtest",
            "--git-reference",
            "candidate-a",
            "--algorithm-override-reason",
            "Use date-aligned production parameters",
            "--algo-repo-url",
            "https://example.invalid/repo.git",
            "--data-base-dir",
            str(tmp_path / "data"),
            "--output-base-dir",
            str(tmp_path / "out"),
            "--scratch-dir",
            str(tmp_path / "scratch"),
            "--last-test-time",
            "2000-04-01",
        ],
    )

    with pytest.raises(SystemExit) as excinfo:
        hv.main()

    assert excinfo.value.code == 2
    assert "--algorithm-override-reason requires --algorithm-override" in capsys.readouterr().err
