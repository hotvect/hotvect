"""Tests for hv-ext results ls command."""

import json
import tempfile
import unittest
from datetime import date, datetime, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

from hotvect.extra.commands.results import ResultsCommand


class TestResultsLsCommand(unittest.TestCase):
    def setUp(self) -> None:
        self.command = ResultsCommand()

    def test_local_results_ls(self):
        day = date(2026, 2, 15).isoformat()
        algo_dir = "algo@74.4.5-ordered"

        with tempfile.TemporaryDirectory() as td:
            meta_dir = Path(td)
            run_dir = meta_dir / algo_dir / f"last_test_date_{day}"
            run_dir.mkdir(parents=True, exist_ok=True)
            (run_dir / "result.json").write_text("{}")

            args = SimpleNamespace(
                results_command="ls",
                location=str(meta_dir),
                from_date=day,
                to_date=day,
                algorithm_name_regex="algo",
                algorithm_version_regex=r"74\.4\.5",
                job_name_regex="",
                role_arn="",
            )

            with patch("builtins.print") as mock_print:
                self.command.execute(args)

            payload = json.loads(str(mock_print.call_args_list[0][0][0]))
            self.assertEqual(payload["location"], str(meta_dir))
            self.assertEqual(payload["filters"]["from_date"], day)
            self.assertEqual(payload["filters"]["to_date"], day)
            self.assertEqual(len(payload["runs"]), 1)
            run = payload["runs"][0]
            self.assertEqual(run["test_date"], day)
            self.assertEqual(run["algorithm_id"], algo_dir)
            self.assertEqual(run["algorithm_name"], "algo")
            self.assertEqual(run["algorithm_version"], "74.4.5")
            self.assertEqual(run["hyperparameter"], "ordered")
            self.assertTrue(run["result_json"]["path_or_key"].endswith("result.json"))

    @patch("hotvect.backtest.SageMakerBacktestResultsDownloader")
    def test_s3_results_ls_latest_only(self, mock_downloader_cls):
        # Two keys for the same (test_date, algorithm_id) should be deduped to latest by LastModified.
        # Downloader already returns latest-only in _find_relevant_executions().
        c1 = SimpleNamespace(
            backtest_test_date="2026-02-15",
            algorithm_name="algo",
            algorithm_version="74.4.0",
            hyperparameter="ordered",
            training_job="ml-exp-a",
            execution_date=datetime(2026, 2, 25, 0, 0, 1, tzinfo=timezone.utc),
            key="prefix/ml-exp-a-2026-02-15/algo@74.4.0-ordered/result.json",
        )

        downloader = MagicMock()
        downloader._s3_source_bucket = "bucket"
        downloader._find_relevant_executions.return_value = {
            ("2026-02-15", "algo", "74.4.0", "ordered"): c1,
        }
        mock_downloader_cls.return_value = downloader

        args = SimpleNamespace(
            results_command="ls",
            location="s3://bucket/prefix/",
            from_date="2026-02-15",
            to_date="2026-02-15",
            algorithm_name_regex="algo",
            algorithm_version_regex=r"74\\.4\\..*",
            job_name_regex="ml-exp-.*",
            role_arn="",
        )

        with patch("builtins.print") as mock_print:
            ResultsCommand().execute(args)

        payload = json.loads(str(mock_print.call_args_list[0][0][0]))
        self.assertEqual(payload["location"], "s3://bucket/prefix/")
        self.assertEqual(len(payload["runs"]), 1)
        run = payload["runs"][0]
        self.assertEqual(run["algorithm_id"], "algo@74.4.0-ordered")
        self.assertEqual(run["job_name"], "ml-exp-a")
        self.assertTrue(run["result_json"]["path_or_key"].startswith("s3://bucket/"))
        self.assertEqual(run["result_json"]["last_modified"], "2026-02-25T00:00:01Z")


if __name__ == "__main__":
    unittest.main()
