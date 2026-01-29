"""Integration tests for hv-extra CLI."""

import json
import os
import tempfile
import unittest
from io import StringIO
from unittest.mock import patch

from hotvect.extra.cli import main


class TestHvExtraCLI(unittest.TestCase):
    """Integration test cases for hv-extra CLI."""

    def setUp(self):
        """Set up test fixtures."""
        # Sample performance data
        self.baseline_perf = {
            "max_memory_usage": 1000.0,
            "mean_throughput": 500.0,
            "response_time_metrics": {
                "mean_throughput": {"mean": 480.0},
                "mean": {"mean": 10.0},
                "p50": {"mean": 8.0},
                "p75": {"mean": 12.0},
                "p95": {"mean": 20.0},
                "p99": {"mean": 30.0},
                "p999": {"mean": 50.0},
            },
        }

        self.treatment_perf = {
            "max_memory_usage": 900.0,
            "mean_throughput": 550.0,
            "response_time_metrics": {
                "mean_throughput": {"mean": 520.0},
                "mean": {"mean": 9.0},
                "p50": {"mean": 7.5},
                "p75": {"mean": 11.0},
                "p95": {"mean": 18.0},
                "p99": {"mean": 25.0},
                "p999": {"mean": 45.0},
            },
        }

        # Sample schema and data
        self.schema_content = """0	Num	feature_0
1	Categ	category_feature
2	Text	text_feature
3	NumVector	vector_feature"""

        self.encoded_content = """1.5	category_a	hello world	1.0;2.0;3.0
2.0	category_b	foo bar baz	4.0;5.0
0.5	category_a	single	NaN"""

    @patch("sys.argv", ["hv-extra"])
    @patch("sys.exit")
    @patch("sys.stdout", new_callable=StringIO)
    def test_no_arguments_shows_help(self, mock_stdout, mock_exit):
        """Test that running hv-extra with no arguments shows help."""
        main()

        # Check that help was displayed
        output = mock_stdout.getvalue()
        self.assertIn("Extended utilities for hotvect ML operations", output)
        self.assertIn("perf-compare", output)
        self.assertIn("catboost-convert", output)

        # Check that exit was called with 0 (first call should be successful)
        self.assertTrue(mock_exit.called)
        self.assertEqual(mock_exit.call_args_list[0][0][0], 0)

    @patch("sys.argv", ["hv-extra", "--help"])
    @patch("sys.exit")
    @patch("sys.stdout", new_callable=StringIO)
    def test_help_flag(self, mock_stdout, mock_exit):
        """Test that --help flag shows help."""
        main()

        # Check that help was displayed
        output = mock_stdout.getvalue()
        self.assertIn("Extended utilities for hotvect ML operations", output)
        self.assertIn("Examples:", output)

    @patch("sys.argv", ["hv-extra", "perf-compare", "--help"])
    @patch("sys.exit")
    @patch("sys.stdout", new_callable=StringIO)
    def test_perf_compare_help(self, mock_stdout, mock_exit):
        """Test that perf-compare --help works."""
        main()

        output = mock_stdout.getvalue()
        self.assertIn("baseline_file", output)
        self.assertIn("treatment_file", output)
        self.assertIn("--format", output)

    @patch("sys.argv", ["hv-extra", "catboost-convert", "--help"])
    @patch("sys.exit")
    @patch("sys.stdout", new_callable=StringIO)
    def test_catboost_convert_help(self, mock_stdout, mock_exit):
        """Test that catboost-convert --help works."""
        main()

        output = mock_stdout.getvalue()
        self.assertIn("--schema-file", output)
        self.assertIn("--encoded-file", output)
        self.assertIn("--output", output)

    @patch("sys.stdout", new_callable=StringIO)
    def test_perf_compare_integration(self, mock_stdout):
        """Test complete perf-compare workflow integration."""
        # Create temporary files with test data
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as baseline_file:
            json.dump(self.baseline_perf, baseline_file)
            baseline_path = baseline_file.name

        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as treatment_file:
            json.dump(self.treatment_perf, treatment_file)
            treatment_path = treatment_file.name

        try:
            # Test JSON output
            with patch("sys.argv", ["hv-extra", "perf-compare", baseline_path, treatment_path]):
                main()

            output = mock_stdout.getvalue()
            result = json.loads(output)

            # Verify structure and some key results
            self.assertIn("baseline_file", result)
            self.assertIn("treatment_file", result)
            self.assertIn("max_memory_usage", result)

            # Memory should be better (lower)
            self.assertEqual(result["max_memory_usage"]["result"], "better")
            # Throughput should be better (higher)
            self.assertEqual(result["mean_throughput"]["result"], "better")

        finally:
            # Clean up
            os.unlink(baseline_path)
            os.unlink(treatment_path)

    @patch("sys.stdout", new_callable=StringIO)
    def test_perf_compare_table_format(self, mock_stdout):
        """Test perf-compare with table format."""
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as baseline_file:
            json.dump(self.baseline_perf, baseline_file)
            baseline_path = baseline_file.name

        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as treatment_file:
            json.dump(self.treatment_perf, treatment_file)
            treatment_path = treatment_file.name

        try:
            with patch("sys.argv", ["hv-extra", "perf-compare", baseline_path, treatment_path, "--format", "table"]):
                main()

            output = mock_stdout.getvalue()

            # Verify table format elements
            self.assertIn("Performance Comparison Results", output)
            self.assertIn("Memory Usage", output)
            self.assertIn("Throughput", output)
            self.assertIn("better", output)

        finally:
            os.unlink(baseline_path)
            os.unlink(treatment_path)

    @patch("builtins.print")
    def test_perf_compare_output_file(self, mock_print):
        """Test perf-compare with output file."""
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as baseline_file:
            json.dump(self.baseline_perf, baseline_file)
            baseline_path = baseline_file.name

        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as treatment_file:
            json.dump(self.treatment_perf, treatment_file)
            treatment_path = treatment_file.name

        with tempfile.NamedTemporaryFile(mode="w", suffix=".txt", delete=False) as output_file:
            output_path = output_file.name

        try:
            with patch(
                "sys.argv", ["hv-extra", "perf-compare", baseline_path, treatment_path, "--output", output_path]
            ):
                main()

            # Check that file was written
            with open(output_path, "r") as f:
                result = json.loads(f.read())
                self.assertIn("baseline_file", result)

            # Check success message
            mock_print.assert_called_once()
            self.assertIn("Results written to", mock_print.call_args[0][0])

        finally:
            for path in [baseline_path, treatment_path, output_path]:
                if os.path.exists(path):
                    os.unlink(path)

    @patch("builtins.print")
    def test_catboost_convert_integration(self, mock_print):
        """Test complete catboost-convert workflow integration."""
        # Create temporary files with test data
        with tempfile.NamedTemporaryFile(mode="w", suffix=".txt", delete=False) as schema_file:
            schema_file.write(self.schema_content)
            schema_path = schema_file.name

        with tempfile.NamedTemporaryFile(mode="w", suffix=".tsv", delete=False) as encoded_file:
            encoded_file.write(self.encoded_content)
            encoded_path = encoded_file.name

        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as output_file:
            output_path = output_file.name

        try:
            with patch(
                "sys.argv",
                [
                    "hv-extra",
                    "catboost-convert",
                    "--schema-file",
                    schema_path,
                    "--encoded-file",
                    encoded_path,
                    "--output",
                    output_path,
                ],
            ):
                main()

            # Check that output file was created with correct data
            with open(output_path, "r") as f:
                result = json.load(f)

            self.assertEqual(len(result), 3)  # 3 data rows
            self.assertIn("feature_0", result[0])
            self.assertIn("category_feature", result[0])
            self.assertEqual(result[0]["feature_0"], 1.5)
            self.assertEqual(result[0]["category_feature"], "category_a")
            self.assertEqual(result[0]["text_feature"], ["hello", "world"])
            self.assertEqual(result[0]["vector_feature"], [1.0, 2.0, 3.0])

            # Check success message
            mock_print.assert_called_once()
            self.assertIn("Successfully converted 3 records", mock_print.call_args[0][0])

        finally:
            for path in [schema_path, encoded_path, output_path]:
                if os.path.exists(path):
                    os.unlink(path)

    @patch("builtins.print")
    def test_catboost_convert_jsonl_format(self, mock_print):
        """Test catboost-convert with JSONL format."""
        with tempfile.NamedTemporaryFile(mode="w", suffix=".txt", delete=False) as schema_file:
            schema_file.write(self.schema_content)
            schema_path = schema_file.name

        with tempfile.NamedTemporaryFile(mode="w", suffix=".tsv", delete=False) as encoded_file:
            encoded_file.write(self.encoded_content)
            encoded_path = encoded_file.name

        with tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl", delete=False) as output_file:
            output_path = output_file.name

        try:
            with patch(
                "sys.argv",
                [
                    "hv-extra",
                    "catboost-convert",
                    "-s",
                    schema_path,
                    "-e",
                    encoded_path,
                    "-o",
                    output_path,
                    "--format",
                    "jsonl",
                ],
            ):
                main()

            # Check JSONL format
            with open(output_path, "r") as f:
                lines = f.readlines()

            self.assertEqual(len(lines), 3)
            # Each line should be valid JSON
            for line in lines:
                data = json.loads(line.strip())
                self.assertIn("feature_0", data)

            # Check first record
            first_record = json.loads(lines[0].strip())
            self.assertEqual(first_record["feature_0"], 1.5)

        finally:
            for path in [schema_path, encoded_path, output_path]:
                if os.path.exists(path):
                    os.unlink(path)

    @patch("sys.argv", ["hv-extra", "invalid-command"])
    @patch("sys.exit")
    @patch("sys.stdout", new_callable=StringIO)
    def test_invalid_command(self, mock_stdout, mock_exit):
        """Test that invalid command shows help and exits with error."""
        main()

        # Should show help message since we handle invalid commands in CLI
        output = mock_stdout.getvalue()
        self.assertIn("Extended utilities for hotvect ML operations", output)

        # Should exit with error code 1 (our custom handling)
        self.assertTrue(mock_exit.called)
        self.assertEqual(mock_exit.call_args_list[-1][0][0], 1)

    @patch("sys.argv", ["hv-extra", "perf-compare", "nonexistent1.json", "nonexistent2.json"])
    @patch("sys.exit")
    @patch("builtins.print")
    def test_file_not_found_error(self, mock_print, mock_exit):
        """Test error handling for non-existent files."""
        main()

        # Should print error message
        mock_print.assert_called()
        error_message = mock_print.call_args[0][0]
        self.assertIn("Error: File not found", error_message)

        # Should exit with error code
        mock_exit.assert_called_once_with(1)

    @patch(
        "sys.argv",
        [
            "hv-extra",
            "catboost-convert",
            "--schema-file",
            "schema.txt",
            "--encoded-file",
            "data.tsv",
            "--output",
            "out.json",
        ],
    )
    @patch("sys.exit")
    @patch("builtins.print")
    def test_catboost_file_not_found_error(self, mock_print, mock_exit):
        """Test error handling for catboost-convert with non-existent files."""
        main()

        # Should print error message
        mock_print.assert_called()
        error_message = mock_print.call_args[0][0]
        self.assertIn("Error: File not found", error_message)

        # Should exit with error code
        mock_exit.assert_called_once_with(1)


if __name__ == "__main__":
    unittest.main()
