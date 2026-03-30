"""Tests for hv-ext results download command."""

import io
import json
import tarfile
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

from hotvect.extra.commands.results import ResultsCommand, ResultsDownloadCommand


class TestResultsDownloadCommand(unittest.TestCase):
    @patch("hotvect.backtest.SageMakerBacktestResultsDownloader")
    def test_download_result_json_only(self, mock_downloader_cls):
        # Arrange: fake S3 downloader + client that writes the dest file.
        s3_client = MagicMock()

        def _download_file(bucket, key, filename):
            Path(filename).write_text(f"bucket={bucket} key={key}")

        s3_client.download_file.side_effect = _download_file

        component = SimpleNamespace(
            backtest_test_date="2026-02-15",
            algorithm_name="algo",
            algorithm_version="74.4.0",
            hyperparameter="ordered",
            training_job="ml-exp-x",
            execution_date=datetime(2026, 2, 25, 0, 0, 1, tzinfo=timezone.utc),
            key="prefix/ml-exp-x-2026-02-15/algo@74.4.0-ordered/result.json",
        )

        downloader = MagicMock()
        downloader._s3_source_bucket = "bucket"
        downloader._s3_client = s3_client
        downloader._find_relevant_executions.return_value = {("k",): component}
        mock_downloader_cls.return_value = downloader

        with tempfile.TemporaryDirectory() as td:
            dest = Path(td)
            args = SimpleNamespace(
                results_command="download",
                s3_prefix="s3://bucket/prefix/",
                dest_base_dir=str(dest),
                from_date="2026-02-15",
                to_date="2026-02-15",
                algorithm_name_regex="algo",
                algorithm_version_regex=r"74\.4\..*",
                job_name_regex="ml-exp-.*",
                role_arn="",
                include_metadata=False,
                include_output_data=False,
                no_skip_existing=False,
            )

            with patch("builtins.print") as mock_print:
                ResultsCommand().execute(args)

            payload = json.loads(str(mock_print.call_args_list[0][0][0]))
            self.assertEqual(payload["downloaded"]["result_json"]["count"], 1)

            expected = dest / "meta" / "algo@74.4.0-ordered" / "last_test_date_2026-02-15" / "result.json"
            self.assertTrue(expected.exists())

    @patch("hotvect.backtest.SageMakerBacktestResultsDownloader")
    def test_skip_existing(self, mock_downloader_cls):
        s3_client = MagicMock()

        def _download_file(bucket, key, filename):
            Path(filename).write_text("downloaded")

        s3_client.download_file.side_effect = _download_file

        component = SimpleNamespace(
            backtest_test_date="2026-02-15",
            algorithm_name="algo",
            algorithm_version="74.4.0",
            hyperparameter="ordered",
            training_job="ml-exp-x",
            execution_date=datetime(2026, 2, 25, 0, 0, 1, tzinfo=timezone.utc),
            key="prefix/ml-exp-x-2026-02-15/algo@74.4.0-ordered/result.json",
        )

        downloader = MagicMock()
        downloader._s3_source_bucket = "bucket"
        downloader._s3_client = s3_client
        downloader._find_relevant_executions.return_value = {("k",): component}
        mock_downloader_cls.return_value = downloader

        with tempfile.TemporaryDirectory() as td:
            dest = Path(td)
            existing = dest / "meta" / "algo@74.4.0-ordered" / "last_test_date_2026-02-15" / "result.json"
            existing.parent.mkdir(parents=True, exist_ok=True)
            existing.write_text("existing")

            args = SimpleNamespace(
                results_command="download",
                s3_prefix="s3://bucket/prefix/",
                dest_base_dir=str(dest),
                from_date="2026-02-15",
                to_date="2026-02-15",
                algorithm_name_regex="algo",
                algorithm_version_regex=r"74\.4\..*",
                job_name_regex="ml-exp-.*",
                role_arn="",
                include_metadata=False,
                include_output_data=False,
                no_skip_existing=False,
            )

            with patch("builtins.print") as mock_print:
                ResultsCommand().execute(args)

            payload = json.loads(str(mock_print.call_args_list[0][0][0]))
            self.assertEqual(payload["downloaded"]["result_json"]["count"], 0)
            self.assertEqual(existing.read_text(), "existing")

    @patch("hotvect.backtest.SageMakerBacktestResultsDownloader")
    def test_download_rejects_algorithm_id_path_traversal(self, mock_downloader_cls):
        downloader = MagicMock()
        downloader._s3_source_bucket = "bucket"
        downloader._s3_client = MagicMock()
        downloader._find_relevant_executions.return_value = {
            ("k",): SimpleNamespace(
                backtest_test_date="2026-02-15",
                algorithm_name="../../evil",
                algorithm_version="74.4.0",
                hyperparameter=None,
                training_job="ml-exp-x",
                execution_date=datetime(2026, 2, 25, 0, 0, 1, tzinfo=timezone.utc),
                key="prefix/ml-exp-x-2026-02-15/../../evil@74.4.0/result.json",
            )
        }
        mock_downloader_cls.return_value = downloader

        with tempfile.TemporaryDirectory() as td:
            args = SimpleNamespace(
                results_command="download",
                s3_prefix="s3://bucket/prefix/",
                dest_base_dir=td,
                from_date="2026-02-15",
                to_date="2026-02-15",
                algorithm_name_regex=".*",
                algorithm_version_regex=".*",
                job_name_regex="",
                role_arn="",
                include_metadata=False,
                include_output_data=False,
                no_skip_existing=False,
            )
            with self.assertRaises(ValueError, msg="Expected unsafe algorithm_id to be rejected"):
                ResultsCommand().execute(args)

    def test_download_and_extract_tar_rejects_path_traversal(self):
        def _tar_payload_with_traversal() -> bytes:
            payload = io.BytesIO()
            with tarfile.open(fileobj=payload, mode="w:gz") as tar:
                data = b"bad"
                info = tarfile.TarInfo(name="../escaped.txt")
                info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
            return payload.getvalue()

        payload = _tar_payload_with_traversal()
        s3_client = MagicMock()

        def _download_fileobj(_bucket, _key, fileobj):
            fileobj.write(payload)

        s3_client.download_fileobj.side_effect = _download_fileobj

        with tempfile.TemporaryDirectory() as td:
            dest = Path(td) / "dest"
            with self.assertRaises(ValueError):
                ResultsDownloadCommand._download_and_extract_tar(
                    s3_client=s3_client,
                    bucket="bucket",
                    key="prefix/output/output.tar.gz",
                    dest_dir=dest,
                )
            self.assertFalse((Path(td) / "escaped.txt").exists())


if __name__ == "__main__":
    unittest.main()
