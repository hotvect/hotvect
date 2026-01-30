"""Tests for download-results command."""

import argparse
import tempfile
import unittest
from datetime import date
from unittest.mock import MagicMock, patch

from hotvect.extra.commands.download_results import DownloadResultsCommand


class TestDownloadResultsCommand(unittest.TestCase):
    """Test cases for DownloadResultsCommand."""

    def setUp(self):
        """Set up test fixtures."""
        self.command = DownloadResultsCommand()

    def test_register_parser(self):
        """Test that register_parser creates proper argument parser."""
        main_parser = argparse.ArgumentParser()
        subparsers = main_parser.add_subparsers(dest="command")

        DownloadResultsCommand.register_parser(subparsers)

        # Test required arguments
        args = main_parser.parse_args(
            [
                "download-results",
                "--s3-base-prefix",
                "s3://test-bucket/path/",
                "--dest-base-dir",
                "/tmp/results",
                "--from-date",
                "2025-06-01",
                "--to-date",
                "2025-06-15",
            ]
        )

        self.assertEqual(args.command, "download-results")
        self.assertEqual(args.s3_base_prefix, "s3://test-bucket/path/")
        self.assertEqual(args.dest_base_dir, "/tmp/results")
        self.assertEqual(args.from_date, "2025-06-01")
        self.assertEqual(args.to_date, "2025-06-15")
        self.assertIsNone(args.role_arn)  # default
        self.assertFalse(args.include_metadata)  # default
        self.assertFalse(args.include_output_data)  # default
        self.assertFalse(args.no_skip_existing)  # default

        # Test optional arguments
        args = main_parser.parse_args(
            [
                "download-results",
                "--s3-base-prefix",
                "s3://test-bucket/path/",
                "--dest-base-dir",
                "/tmp/results",
                "--from-date",
                "2025-06-01",
                "--to-date",
                "2025-06-15",
                "--role-arn",
                "arn:aws:iam::123:role/MyRole",
                "--include-metadata",
                "--include-output-data",
                "--no-skip-existing",
            ]
        )

        self.assertEqual(args.role_arn, "arn:aws:iam::123:role/MyRole")
        self.assertTrue(args.include_metadata)
        self.assertTrue(args.include_output_data)
        self.assertTrue(args.no_skip_existing)

    @patch("hotvect.extra.commands.download_results.SageMakerBacktestResultsDownloader")
    @patch("builtins.print")
    def test_execute_success(self, mock_print, mock_downloader_class):
        """Test successful execution of download command."""
        # Mock downloader instance
        mock_downloader = MagicMock()
        mock_downloader_class.return_value = mock_downloader

        # Create temporary directory for test
        with tempfile.TemporaryDirectory() as temp_dir:
            args = MagicMock()
            args.s3_base_prefix = "s3://test-bucket/path/"
            args.dest_base_dir = temp_dir
            args.from_date = "2025-06-01"
            args.to_date = "2025-06-15"
            args.role_arn = "arn:aws:iam::123:role/MyRole"
            args.include_metadata = True
            args.include_output_data = False
            args.no_skip_existing = False

            self.command.execute(args)

            # Verify downloader was created with correct parameters
            mock_downloader_class.assert_called_once_with(
                s3_base_prefix="s3://test-bucket/path/",
                dest_base_dir=temp_dir,
                include_metadata=True,
                skip_data_if_already_present=True,  # not args.no_skip_existing
                include_output_data=False,
                from_including_test_date=date(2025, 6, 1),
                to_including_test_date=date(2025, 6, 15),
                role_arn_to_assume="arn:aws:iam::123:role/MyRole",
            )

            # Verify download was called
            mock_downloader.download.assert_called_once()

            # Verify success message was printed
            success_calls = [call for call in mock_print.call_args_list if "completed successfully" in str(call)]
            self.assertTrue(len(success_calls) > 0)

    @patch("hotvect.extra.commands.download_results.SageMakerBacktestResultsDownloader")
    @patch("builtins.print")
    def test_execute_no_role_arn(self, mock_print, mock_downloader_class):
        """Test execution without role ARN."""
        mock_downloader = MagicMock()
        mock_downloader_class.return_value = mock_downloader

        with tempfile.TemporaryDirectory() as temp_dir:
            args = MagicMock()
            args.s3_base_prefix = "s3://test-bucket/path/"
            args.dest_base_dir = temp_dir
            args.from_date = "2025-06-01"
            args.to_date = "2025-06-15"
            args.role_arn = None
            args.include_metadata = False
            args.include_output_data = True
            args.no_skip_existing = True

            self.command.execute(args)

            # Verify downloader was created with correct parameters
            mock_downloader_class.assert_called_once_with(
                s3_base_prefix="s3://test-bucket/path/",
                dest_base_dir=temp_dir,
                include_metadata=False,
                skip_data_if_already_present=False,  # not args.no_skip_existing
                include_output_data=True,
                from_including_test_date=date(2025, 6, 1),
                to_including_test_date=date(2025, 6, 15),
                role_arn_to_assume=None,
            )

    @patch("sys.exit")
    @patch("builtins.print")
    def test_execute_invalid_from_date(self, mock_print, mock_exit):
        """Test execution with invalid from date format."""
        args = MagicMock()
        args.s3_base_prefix = "s3://test-bucket/path/"
        args.dest_base_dir = "/tmp/results"
        args.from_date = "invalid-date"
        args.to_date = "2025-06-15"
        args.role_arn = None
        args.include_metadata = False
        args.include_output_data = False
        args.no_skip_existing = False

        self.command.execute(args)

        # Verify error message was printed
        error_calls = [call for call in mock_print.call_args_list if "Invalid date format" in str(call)]
        self.assertTrue(len(error_calls) > 0)
        mock_exit.assert_called_with(1)

    @patch("sys.exit")
    @patch("builtins.print")
    def test_execute_invalid_to_date(self, mock_print, mock_exit):
        """Test execution with invalid to date format."""
        args = MagicMock()
        args.s3_base_prefix = "s3://test-bucket/path/"
        args.dest_base_dir = "/tmp/results"
        args.from_date = "2025-06-01"
        args.to_date = "invalid-date"
        args.role_arn = None
        args.include_metadata = False
        args.include_output_data = False
        args.no_skip_existing = False

        self.command.execute(args)

        # Verify error message was printed
        error_calls = [call for call in mock_print.call_args_list if "Invalid date format" in str(call)]
        self.assertTrue(len(error_calls) > 0)
        mock_exit.assert_called_with(1)

    @patch("sys.exit")
    @patch("builtins.print")
    def test_execute_invalid_date_range(self, mock_print, mock_exit):
        """Test execution with start date after end date."""
        args = MagicMock()
        args.s3_base_prefix = "s3://test-bucket/path/"
        args.dest_base_dir = "/tmp/results"
        args.from_date = "2025-06-15"
        args.to_date = "2025-06-01"
        args.role_arn = None
        args.include_metadata = False
        args.include_output_data = False
        args.no_skip_existing = False

        self.command.execute(args)

        # Verify error message was printed
        error_calls = [
            call for call in mock_print.call_args_list if "Start date must be before or equal to end date" in str(call)
        ]
        self.assertTrue(len(error_calls) > 0)
        mock_exit.assert_called_with(1)

    @patch("hotvect.extra.commands.download_results.SageMakerBacktestResultsDownloader")
    @patch("sys.exit")
    @patch("builtins.print")
    def test_execute_downloader_exception(self, mock_print, mock_exit, mock_downloader_class):
        """Test execution when downloader raises an exception."""
        # Mock downloader to raise exception
        mock_downloader = MagicMock()
        mock_downloader.download.side_effect = Exception("S3 connection failed")
        mock_downloader_class.return_value = mock_downloader

        with tempfile.TemporaryDirectory() as temp_dir:
            args = MagicMock()
            args.s3_base_prefix = "s3://test-bucket/path/"
            args.dest_base_dir = temp_dir
            args.from_date = "2025-06-01"
            args.to_date = "2025-06-15"
            args.role_arn = None
            args.include_metadata = False
            args.include_output_data = False
            args.no_skip_existing = False

            self.command.execute(args)

            # Verify error message was printed
            error_calls = [call for call in mock_print.call_args_list if "Error during download" in str(call)]
            self.assertTrue(len(error_calls) > 0)
            mock_exit.assert_called_with(1)

    @patch("hotvect.extra.commands.download_results.SageMakerBacktestResultsDownloader")
    @patch("hotvect.extra.commands.download_results.Path")
    @patch("builtins.print")
    def test_execute_creates_directory(self, mock_print, mock_path_class, mock_downloader_class):
        """Test that destination directory is created if it doesn't exist."""
        # Mock Path and downloader
        mock_path = MagicMock()
        mock_path_class.return_value = mock_path
        mock_downloader = MagicMock()
        mock_downloader_class.return_value = mock_downloader

        args = MagicMock()
        args.s3_base_prefix = "s3://test-bucket/path/"
        args.dest_base_dir = "/nonexistent/results"
        args.from_date = "2025-06-01"
        args.to_date = "2025-06-15"
        args.role_arn = None
        args.include_metadata = False
        args.include_output_data = False
        args.no_skip_existing = False

        self.command.execute(args)

        # Verify Path was created with correct directory
        mock_path_class.assert_called_once_with("/nonexistent/results")

        # Verify mkdir was called
        mock_path.mkdir.assert_called_once_with(parents=True, exist_ok=True)

    def test_date_range_same_day(self):
        """Test that same start and end date is valid."""
        args = MagicMock()
        args.s3_base_prefix = "s3://test-bucket/path/"
        args.dest_base_dir = "/tmp/results"
        args.from_date = "2025-06-01"
        args.to_date = "2025-06-01"  # Same date
        args.role_arn = None
        args.include_metadata = False
        args.include_output_data = False
        args.no_skip_existing = False

        with tempfile.TemporaryDirectory() as temp_dir:
            args.dest_base_dir = temp_dir

            with patch(
                "hotvect.extra.commands.download_results.SageMakerBacktestResultsDownloader"
            ) as mock_downloader_class:
                mock_downloader = MagicMock()
                mock_downloader_class.return_value = mock_downloader

                # Should not raise any exceptions
                self.command.execute(args)

                # Verify downloader was created with same date
                call_args = mock_downloader_class.call_args[1]
                self.assertEqual(call_args["from_including_test_date"], date(2025, 6, 1))
                self.assertEqual(call_args["to_including_test_date"], date(2025, 6, 1))


if __name__ == "__main__":
    unittest.main()
