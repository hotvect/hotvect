"""Integration tests for hv-ext CLI."""

import json
import os
import tempfile
import unittest
from io import StringIO
from unittest.mock import patch

from hotvect.extra.cli import main


class TestHvExtCLI(unittest.TestCase):
    """Integration test cases for hv-ext CLI."""

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

    @patch("sys.argv", ["hv-ext"])
    @patch("sys.exit")
    @patch("sys.stdout", new_callable=StringIO)
    def test_no_arguments_shows_help(self, mock_stdout, mock_exit):
        """Test that running hv-ext with no arguments shows help."""
        main()

        # Check that help was displayed
        output = mock_stdout.getvalue()
        self.assertIn("Extended utilities for hotvect ML operations", output)
        self.assertIn("metrics", output)
        self.assertIn("catboost-convert", output)

        # Check that exit was called with 0 (first call should be successful)
        self.assertTrue(mock_exit.called)
        self.assertEqual(mock_exit.call_args_list[0][0][0], 0)

    @patch("sys.argv", ["hv-ext", "--help"])
    @patch("sys.exit")
    @patch("sys.stdout", new_callable=StringIO)
    def test_help_flag(self, mock_stdout, mock_exit):
        """Test that --help flag shows help."""
        main()

        # Check that help was displayed
        output = mock_stdout.getvalue()
        self.assertIn("Extended utilities for hotvect ML operations", output)
        self.assertIn("Examples:", output)

    @patch("sys.argv", ["hv-ext", "metrics", "--help"])
    @patch("sys.stdout", new_callable=StringIO)
    def test_metrics_help(self, mock_stdout):
        """Test that hv-ext metrics --help works."""
        with self.assertRaises(SystemExit):
            main()

        output = mock_stdout.getvalue()
        self.assertIn("compare-quality", output)
        self.assertIn("compare-system", output)

    @patch("sys.argv", ["hv-ext", "catboost-convert", "--help"])
    @patch("sys.exit")
    @patch("sys.stdout", new_callable=StringIO)
    def test_catboost_convert_help(self, mock_stdout, mock_exit):
        """Test that catboost-convert --help works."""
        main()

        output = mock_stdout.getvalue()
        self.assertIn("--schema-file", output)
        self.assertIn("--encoded-file", output)
        self.assertIn("--output", output)

    @patch("sys.argv", ["hv-ext", "compare-equivalence", "--help"])
    @patch("sys.stdout", new_callable=StringIO)
    def test_compare_equivalence_help(self, mock_stdout):
        """Test that compare-equivalence --help works."""
        with self.assertRaises(SystemExit):
            main()

        output = mock_stdout.getvalue()
        self.assertIn("--score-eps", output)
        self.assertIn("--allow-non-deterministic-tie-breaking", output)

    @patch("sys.stdout", new_callable=StringIO)
    def test_perf_compare_integration(self, mock_stdout):
        """Test hv-ext metrics compare-system workflow integration."""
        # Create temporary files with test data
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as baseline_file:
            json.dump(self.baseline_perf, baseline_file)
            baseline_path = baseline_file.name

        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as treatment_file:
            json.dump(self.treatment_perf, treatment_file)
            treatment_path = treatment_file.name

        try:
            # Test JSON output
            with patch("sys.argv", ["hv-ext", "metrics", "compare-system", baseline_path, treatment_path]):
                main()

            output = mock_stdout.getvalue()
            result = json.loads(output)

            # Verify structure and some key results
            self.assertIn("control", result)
            self.assertIn("treatment", result)
            self.assertIn("metrics", result)

            # Memory should be better (lower)
            self.assertLess(result["metrics"]["max_memory_usage"]["percent_change"], 0)
            # Throughput should be better (higher)
            self.assertGreater(result["metrics"]["mean_throughput"]["percent_change"], 0)

        finally:
            # Clean up
            os.unlink(baseline_path)
            os.unlink(treatment_path)

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

        with tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl", delete=False) as output_file:
            output_path = output_file.name

        try:
            with patch(
                "sys.argv",
                [
                    "hv-ext",
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
                lines = f.readlines()

            self.assertEqual(len(lines), 3)  # 3 data rows

            first = json.loads(lines[0].strip())
            self.assertIn("feature_0", first)
            self.assertIn("category_feature", first)
            self.assertEqual(first["feature_0"], 1.5)
            self.assertEqual(first["category_feature"], "category_a")
            self.assertEqual(first["text_feature"], ["hello", "world"])
            self.assertEqual(first["vector_feature"], [1.0, 2.0, 3.0])

            # Check success message
            mock_print.assert_called_once()
            self.assertIn("Successfully converted 3 records", mock_print.call_args[0][0])

        finally:
            for path in [schema_path, encoded_path, output_path]:
                if os.path.exists(path):
                    os.unlink(path)

    @patch("builtins.print")
    def test_catboost_convert_rejects_format_flag(self, mock_print):
        """Test catboost-convert rejects deprecated --format flag."""
        with tempfile.NamedTemporaryFile(mode="w", suffix=".txt", delete=False) as schema_file:
            schema_file.write(self.schema_content)
            schema_path = schema_file.name

        with tempfile.NamedTemporaryFile(mode="w", suffix=".tsv", delete=False) as encoded_file:
            encoded_file.write(self.encoded_content)
            encoded_path = encoded_file.name

        with tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl", delete=False) as output_file:
            output_path = output_file.name

        try:
            with (
                patch(
                    "sys.argv",
                    [
                        "hv-ext",
                        "catboost-convert",
                        "-s",
                        schema_path,
                        "-e",
                        encoded_path,
                        "-o",
                        output_path,
                        "--format",
                    ],
                ),
                patch("sys.stderr", new_callable=StringIO) as mock_stderr,
            ):
                with self.assertRaises(SystemExit) as exc_info:
                    main()

            self.assertEqual(exc_info.exception.code, 2)
            self.assertIn("unrecognized arguments", mock_stderr.getvalue())

        finally:
            for path in [schema_path, encoded_path, output_path]:
                if os.path.exists(path):
                    os.unlink(path)

    @patch("sys.argv", ["hv-ext", "invalid-command"])
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

    @patch("sys.exit")
    @patch("builtins.print")
    def test_file_not_found_error(self, mock_print, mock_exit):
        """Test error handling for non-existent files."""
        with patch("sys.argv", ["hv-ext", "metrics", "compare-system", "nonexistent1.json", "nonexistent2.json"]):
            main()

        # Should print error message
        mock_print.assert_called()
        error_message = mock_print.call_args[0][0]
        self.assertIn("Error:", error_message)

        # Should exit with error code
        mock_exit.assert_called_once_with(1)

    @patch(
        "sys.argv",
        [
            "hv-ext",
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
