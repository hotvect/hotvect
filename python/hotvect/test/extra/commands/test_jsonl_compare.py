"""Tests for compare-jsonl command."""

import argparse
import json
import os
import shutil
import tempfile
import unittest
from unittest.mock import MagicMock, patch

from hotvect.extra.commands.jsonl_compare import JsonlCompareCommand


class TestJsonlCompareCommand(unittest.TestCase):
    """Test cases for JsonlCompareCommand."""

    def setUp(self):
        """Set up test fixtures."""
        self.command = JsonlCompareCommand()

        # Sample JSONL data - identical files
        self.identical_data = [{"id": 1, "name": "test1", "value": 10.5}, {"id": 2, "name": "test2", "value": 20.0}]

        # Sample JSONL data - different files
        self.file1_data = [{"id": 1, "name": "test1", "value": 10.5}, {"id": 2, "name": "test2", "value": 20.0}]

        self.file2_data = [
            {"id": 1, "name": "test1", "value": 10.5},
            {"id": 2, "name": "changed", "value": 25.0},  # Different values
        ]

        # Sample renaming config
        self.rename_config = {"rename": {"old_field": "new_field", "legacy_name": "current_name"}}

    def test_register_parser(self):
        """Test that register_parser creates proper argument parser."""
        main_parser = argparse.ArgumentParser()
        subparsers = main_parser.add_subparsers(dest="command")

        JsonlCompareCommand.register_parser(subparsers)

        # Test required arguments
        args = main_parser.parse_args(["compare-jsonl", "file1.jsonl", "file2.jsonl"])
        self.assertEqual(args.command, "compare-jsonl")
        self.assertEqual(args.file1, "file1.jsonl")
        self.assertEqual(args.file2, "file2.jsonl")
        self.assertEqual(args.output, ".")  # default
        self.assertIsNone(args.config)  # default

        # Test optional arguments
        args = main_parser.parse_args(
            [
                "compare-jsonl",
                "file1.jsonl",
                "file2.jsonl",
                "-o",
                "output_dir",
                "-c",
                "config.json",
            ]
        )
        self.assertEqual(args.output, "output_dir")
        self.assertEqual(args.config, "config.json")

    def _create_jsonl_file(self, data, suffix=".jsonl"):
        """Helper to create temporary JSONL file."""
        temp_file = tempfile.NamedTemporaryFile(mode="w", suffix=suffix, delete=False)
        for item in data:
            temp_file.write(json.dumps(item) + "\n")
        temp_file.close()
        return temp_file.name

    @patch("builtins.print")
    def test_execute_identical_files(self, mock_print):
        """Test execution with identical files."""
        file1_path = self._create_jsonl_file(self.identical_data)
        file2_path = self._create_jsonl_file(self.identical_data)
        temp_output_dir = tempfile.mkdtemp()

        try:
            args = MagicMock()
            args.file1 = file1_path
            args.file2 = file2_path
            args.output = temp_output_dir
            args.config = None

            self.command.execute(args)

            # Check that JSON result was printed
            mock_print.assert_called_once()
            output = mock_print.call_args[0][0]
            result = json.loads(output)
            self.assertEqual(result["message"], "The two files are identical")

        finally:
            os.unlink(file1_path)
            os.unlink(file2_path)
            shutil.rmtree(temp_output_dir, ignore_errors=True)

    @patch("builtins.print")
    def test_execute_different_files(self, mock_print):
        """Test execution with different files."""
        file1_path = self._create_jsonl_file(self.file1_data)
        file2_path = self._create_jsonl_file(self.file2_data)
        temp_output_dir = tempfile.mkdtemp()

        try:
            args = MagicMock()
            args.file1 = file1_path
            args.file2 = file2_path
            args.output = temp_output_dir
            args.config = None

            self.command.execute(args)

            # Check that JSON result was printed
            mock_print.assert_called_once()
            output = mock_print.call_args[0][0]
            result = json.loads(output)
            self.assertEqual(result["message"], "Differences found")
            self.assertEqual(result["processed_lines"], 2)
            self.assertEqual(result["identical_lines"], 1)
            self.assertEqual(result["different_lines"], 1)
            self.assertIn("fields_with_difference", result)

        finally:
            os.unlink(file1_path)
            os.unlink(file2_path)
            shutil.rmtree(temp_output_dir, ignore_errors=True)

    @patch("builtins.print")
    def test_execute_with_config_file(self, mock_print):
        """Test execution with configuration file."""
        file1_path = self._create_jsonl_file(self.file1_data)
        file2_path = self._create_jsonl_file(self.file2_data)
        temp_output_dir = tempfile.mkdtemp()

        # Create config file
        config_file = tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False)
        json.dump(self.rename_config, config_file)
        config_file.close()

        try:
            args = MagicMock()
            args.file1 = file1_path
            args.file2 = file2_path
            args.output = temp_output_dir
            args.config = config_file.name

            self.command.execute(args)

            # Check that execution completed without error
            mock_print.assert_called_once()
            output = mock_print.call_args[0][0]
            result = json.loads(output)
            self.assertIn("message", result)

        finally:
            os.unlink(file1_path)
            os.unlink(file2_path)
            os.unlink(config_file.name)
            shutil.rmtree(temp_output_dir, ignore_errors=True)

    @patch("sys.exit")
    @patch("builtins.print")
    def test_execute_file1_not_found(self, mock_print, mock_exit):
        """Test execution with non-existent first file."""
        file2_path = self._create_jsonl_file(self.file2_data)
        mock_exit.side_effect = SystemExit(1)

        try:
            args = MagicMock()
            args.file1 = "nonexistent_file1.jsonl"
            args.file2 = file2_path
            args.output = "."
            args.config = None

            with self.assertRaises(SystemExit):
                self.command.execute(args)

            # Check that error was printed and exit was called
            mock_print.assert_called_once()
            error_message = mock_print.call_args[0][0]
            self.assertIn("Error: File not found", error_message)
            self.assertIn("nonexistent_file1.jsonl", error_message)
            mock_exit.assert_called_once_with(1)

        finally:
            os.unlink(file2_path)

    @patch("sys.exit")
    @patch("builtins.print")
    def test_execute_file2_not_found(self, mock_print, mock_exit):
        """Test execution with non-existent second file."""
        file1_path = self._create_jsonl_file(self.file1_data)
        mock_exit.side_effect = SystemExit(1)

        try:
            args = MagicMock()
            args.file1 = file1_path
            args.file2 = "nonexistent_file2.jsonl"
            args.output = "."
            args.config = None

            with self.assertRaises(SystemExit):
                self.command.execute(args)

            # Check that error was printed and exit was called
            mock_print.assert_called_once()
            error_message = mock_print.call_args[0][0]
            self.assertIn("Error: File not found", error_message)
            self.assertIn("nonexistent_file2.jsonl", error_message)
            mock_exit.assert_called_once_with(1)

        finally:
            os.unlink(file1_path)

    @patch("sys.exit")
    @patch("builtins.print")
    def test_execute_config_file_not_found(self, mock_print, mock_exit):
        """Test execution with non-existent config file."""
        file1_path = self._create_jsonl_file(self.file1_data)
        file2_path = self._create_jsonl_file(self.file2_data)
        mock_exit.side_effect = SystemExit(1)

        try:
            args = MagicMock()
            args.file1 = file1_path
            args.file2 = file2_path
            args.output = "."
            args.config = "nonexistent_config.json"

            with self.assertRaises(SystemExit):
                self.command.execute(args)

            # Check that error was printed and exit was called
            mock_print.assert_called_once()
            error_message = mock_print.call_args[0][0]
            self.assertIn("Error: Config file not found", error_message)
            mock_exit.assert_called_once_with(1)

        finally:
            os.unlink(file1_path)
            os.unlink(file2_path)

    @patch("sys.exit")
    @patch("builtins.print")
    def test_execute_invalid_json(self, mock_print, mock_exit):
        """Test execution with invalid JSON file."""
        # Create file with invalid JSON
        invalid_file = tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl", delete=False)
        invalid_file.write("invalid json content\n")
        invalid_file.close()

        file2_path = self._create_jsonl_file(self.file2_data)
        mock_exit.side_effect = SystemExit(1)

        try:
            args = MagicMock()
            args.file1 = invalid_file.name
            args.file2 = file2_path
            args.output = "."
            args.config = None

            with self.assertRaises(SystemExit):
                self.command.execute(args)

            # Check that error was printed and exit was called
            mock_print.assert_called_once()
            error_message = mock_print.call_args[0][0]
            self.assertIn("Error: Invalid JSON", error_message)
            mock_exit.assert_called_once_with(1)

        finally:
            os.unlink(invalid_file.name)
            os.unlink(file2_path)

    @patch("builtins.print")
    def test_execute_file1_longer_than_file2(self, mock_print):
        """Test that file length mismatch is detected when file1 has extra lines."""
        file1_data = [
            {"id": 1, "name": "test1", "value": 10.5},
            {"id": 2, "name": "test2", "value": 20.0},
            {"id": 3, "name": "test3", "value": 30.0},
            {"id": 4, "name": "test4", "value": 40.0},
        ]
        file2_data = [
            {"id": 1, "name": "test1", "value": 10.5},
            {"id": 2, "name": "test2", "value": 20.0},
        ]

        file1_path = self._create_jsonl_file(file1_data)
        file2_path = self._create_jsonl_file(file2_data)
        temp_output_dir = tempfile.mkdtemp()

        try:
            args = MagicMock()
            args.file1 = file1_path
            args.file2 = file2_path
            args.output = temp_output_dir
            args.config = None

            self.command.execute(args)

            mock_print.assert_called_once()
            output = mock_print.call_args[0][0]
            result = json.loads(output)
            self.assertEqual(result["message"], "Differences found")
            self.assertEqual(result["processed_lines"], 2)
            self.assertEqual(result["file1_total_lines"], 4)
            self.assertEqual(result["file2_total_lines"], 2)
            self.assertIn("__file_length_mismatch__", result["fields_with_difference"])

        finally:
            os.unlink(file1_path)
            os.unlink(file2_path)
            shutil.rmtree(temp_output_dir, ignore_errors=True)

    @patch("builtins.print")
    def test_execute_file2_longer_than_file1(self, mock_print):
        """Test that file length mismatch is detected when file2 has extra lines."""
        file1_data = [
            {"id": 1, "name": "test1", "value": 10.5},
            {"id": 2, "name": "test2", "value": 20.0},
        ]
        file2_data = [
            {"id": 1, "name": "test1", "value": 10.5},
            {"id": 2, "name": "test2", "value": 20.0},
            {"id": 3, "name": "test3", "value": 30.0},
            {"id": 4, "name": "test4", "value": 40.0},
            {"id": 5, "name": "test5", "value": 50.0},
        ]

        file1_path = self._create_jsonl_file(file1_data)
        file2_path = self._create_jsonl_file(file2_data)
        temp_output_dir = tempfile.mkdtemp()

        try:
            args = MagicMock()
            args.file1 = file1_path
            args.file2 = file2_path
            args.output = temp_output_dir
            args.config = None

            self.command.execute(args)

            mock_print.assert_called_once()
            output = mock_print.call_args[0][0]
            result = json.loads(output)
            self.assertEqual(result["message"], "Differences found")
            self.assertEqual(result["processed_lines"], 2)
            self.assertEqual(result["file1_total_lines"], 2)
            self.assertEqual(result["file2_total_lines"], 5)
            self.assertIn("__file_length_mismatch__", result["fields_with_difference"])

        finally:
            os.unlink(file1_path)
            os.unlink(file2_path)
            shutil.rmtree(temp_output_dir, ignore_errors=True)

    @patch("builtins.print")
    def test_execute_empty_file_vs_nonempty(self, mock_print):
        """Test that file length mismatch is detected when comparing empty to non-empty file."""
        file1_data = []
        file2_data = [
            {"id": 1, "name": "test1", "value": 10.5},
        ]

        file1_path = self._create_jsonl_file(file1_data)
        file2_path = self._create_jsonl_file(file2_data)
        temp_output_dir = tempfile.mkdtemp()

        try:
            args = MagicMock()
            args.file1 = file1_path
            args.file2 = file2_path
            args.output = temp_output_dir
            args.config = None

            self.command.execute(args)

            mock_print.assert_called_once()
            output = mock_print.call_args[0][0]
            result = json.loads(output)
            self.assertEqual(result["message"], "Differences found")
            self.assertEqual(result["processed_lines"], 0)
            self.assertEqual(result["file1_total_lines"], 0)
            self.assertEqual(result["file2_total_lines"], 1)
            self.assertIn("__file_length_mismatch__", result["fields_with_difference"])

        finally:
            os.unlink(file1_path)
            os.unlink(file2_path)
            shutil.rmtree(temp_output_dir, ignore_errors=True)

    @patch("builtins.print")
    def test_execute_length_mismatch_with_content_differences(self, mock_print):
        """Test that file length mismatch is detected along with content differences."""
        file1_data = [
            {"id": 1, "name": "test1", "value": 10.5},
            {"id": 2, "name": "test2", "value": 20.0},
        ]
        file2_data = [
            {"id": 1, "name": "test1", "value": 10.5},
            {"id": 2, "name": "changed", "value": 25.0},
            {"id": 3, "name": "test3", "value": 30.0},
        ]

        file1_path = self._create_jsonl_file(file1_data)
        file2_path = self._create_jsonl_file(file2_data)
        temp_output_dir = tempfile.mkdtemp()

        try:
            args = MagicMock()
            args.file1 = file1_path
            args.file2 = file2_path
            args.output = temp_output_dir
            args.config = None

            self.command.execute(args)

            mock_print.assert_called_once()
            output = mock_print.call_args[0][0]
            result = json.loads(output)
            self.assertEqual(result["message"], "Differences found")
            self.assertEqual(result["processed_lines"], 2)
            self.assertEqual(result["file1_total_lines"], 2)
            self.assertEqual(result["file2_total_lines"], 3)
            # Should have both content differences and file length mismatch
            self.assertIn("__file_length_mismatch__", result["fields_with_difference"])
            self.assertGreater(len(result["fields_with_difference"]), 1)

        finally:
            os.unlink(file1_path)
            os.unlink(file2_path)
            shutil.rmtree(temp_output_dir, ignore_errors=True)

    @patch("builtins.print")
    def test_execute_nested_array_length_mismatch_file1_longer(self, mock_print):
        """Test that nested array length mismatches are detected when file1 has longer arrays."""
        file1_data = [{"id": "x", "items": [{"a": 1}, {"b": 2}, {"c": 3}]}]
        file2_data = [{"id": "x", "items": [{"a": 1}]}]

        file1_path = self._create_jsonl_file(file1_data)
        file2_path = self._create_jsonl_file(file2_data)
        temp_output_dir = tempfile.mkdtemp()

        try:
            args = MagicMock()
            args.file1 = file1_path
            args.file2 = file2_path
            args.output = temp_output_dir
            args.config = None

            self.command.execute(args)

            mock_print.assert_called_once()
            output = mock_print.call_args[0][0]
            result = json.loads(output)
            self.assertEqual(result["message"], "Differences found")
            self.assertIn("items", result["fields_with_difference"])

        finally:
            os.unlink(file1_path)
            os.unlink(file2_path)
            shutil.rmtree(temp_output_dir, ignore_errors=True)

    @patch("builtins.print")
    def test_execute_nested_array_length_mismatch_file2_longer(self, mock_print):
        """Test that nested array length mismatches are detected when file2 has longer arrays."""
        file1_data = [{"id": "y", "results": [{"score": 0.5}]}]
        file2_data = [{"id": "y", "results": [{"score": 0.5}, {"score": 0.8}, {"score": 0.9}]}]

        file1_path = self._create_jsonl_file(file1_data)
        file2_path = self._create_jsonl_file(file2_data)
        temp_output_dir = tempfile.mkdtemp()

        try:
            args = MagicMock()
            args.file1 = file1_path
            args.file2 = file2_path
            args.output = temp_output_dir
            args.config = None

            self.command.execute(args)

            mock_print.assert_called_once()
            output = mock_print.call_args[0][0]
            result = json.loads(output)
            self.assertEqual(result["message"], "Differences found")
            self.assertIn("results", result["fields_with_difference"])

        finally:
            os.unlink(file1_path)
            os.unlink(file2_path)
            shutil.rmtree(temp_output_dir, ignore_errors=True)

    @patch("builtins.print")
    def test_execute_nested_empty_vs_nonempty_array(self, mock_print):
        """Test that empty vs non-empty nested arrays are detected as different."""
        file1_data = [{"id": "z", "tags": []}]
        file2_data = [{"id": "z", "tags": [{"name": "tag1"}, {"name": "tag2"}]}]

        file1_path = self._create_jsonl_file(file1_data)
        file2_path = self._create_jsonl_file(file2_data)
        temp_output_dir = tempfile.mkdtemp()

        try:
            args = MagicMock()
            args.file1 = file1_path
            args.file2 = file2_path
            args.output = temp_output_dir
            args.config = None

            self.command.execute(args)

            mock_print.assert_called_once()
            output = mock_print.call_args[0][0]
            result = json.loads(output)
            self.assertEqual(result["message"], "Differences found")
            self.assertIn("tags", result["fields_with_difference"])

        finally:
            os.unlink(file1_path)
            os.unlink(file2_path)
            shutil.rmtree(temp_output_dir, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
