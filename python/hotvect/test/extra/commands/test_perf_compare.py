"""Tests for perf-compare command."""

import argparse
import json
import os
import tempfile
import unittest
from unittest.mock import MagicMock, patch

from hotvect.extra.commands.perf_compare import PerfCompareCommand


class TestPerfCompareCommand(unittest.TestCase):
    """Test cases for PerfCompareCommand."""

    def setUp(self):
        """Set up test fixtures."""
        self.command = PerfCompareCommand()

        # Sample performance data
        self.baseline_data = {
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

        self.treatment_data = {
            "max_memory_usage": 900.0,  # 10% better
            "mean_throughput": 550.0,  # 10% better
            "response_time_metrics": {
                "mean_throughput": {"mean": 520.0},  # ~8.3% better
                "mean": {"mean": 9.0},  # 10% better
                "p50": {"mean": 7.5},  # 6.25% better
                "p75": {"mean": 11.0},  # ~8.3% better
                "p95": {"mean": 18.0},  # 10% better
                "p99": {"mean": 25.0},  # ~16.7% better
                "p999": {"mean": 45.0},  # 10% better
            },
        }

    def test_register_parser(self):
        """Test that register_parser creates proper argument parser."""
        main_parser = argparse.ArgumentParser()
        subparsers = main_parser.add_subparsers(dest="command")

        PerfCompareCommand.register_parser(subparsers)

        # Test required arguments
        args = main_parser.parse_args(["perf-compare", "baseline.json", "treatment.json"])
        self.assertEqual(args.command, "perf-compare")
        self.assertEqual(args.baseline_file, "baseline.json")
        self.assertEqual(args.treatment_file, "treatment.json")
        self.assertEqual(args.format, "json")  # default
        self.assertIsNone(args.output)  # default

        # Test optional arguments
        args = main_parser.parse_args(
            [
                "perf-compare",
                "baseline.json",
                "treatment.json",
                "--output",
                "report.txt",
                "--format",
                "table",
                "--metrics",
                "memory,throughput",
            ]
        )
        self.assertEqual(args.output, "report.txt")
        self.assertEqual(args.format, "table")
        self.assertEqual(args.metrics, "memory,throughput")

    def test_compute_percentage_change(self):
        """Test percentage change calculation."""
        # Normal case
        result = self.command._compute_percentage_change(100, 110)
        self.assertEqual(result, 10.0)

        # Decrease
        result = self.command._compute_percentage_change(100, 90)
        self.assertEqual(result, -10.0)

        # Zero baseline
        result = self.command._compute_percentage_change(0, 50)
        self.assertEqual(result, float("inf"))

        # Same values
        result = self.command._compute_percentage_change(100, 100)
        self.assertEqual(result, 0.0)

    def test_compare_performance(self):
        """Test performance comparison logic."""
        results = self.command._compare_performance(
            self.baseline_data, self.treatment_data, "baseline.json", "treatment.json"
        )

        # Check structure
        self.assertIn("baseline_file", results)
        self.assertIn("treatment_file", results)
        self.assertIn("max_memory_usage", results)
        self.assertIn("mean_throughput", results)
        self.assertIn("response_time_metrics", results)

        # Check memory usage (lower is better)
        memory = results["max_memory_usage"]
        self.assertEqual(memory["baseline"], 1000.0)
        self.assertEqual(memory["treatment"], 900.0)
        self.assertEqual(memory["percentage_change"], -10.0)
        self.assertEqual(memory["result"], "better")

        # Check throughput (higher is better)
        throughput = results["mean_throughput"]
        self.assertEqual(throughput["baseline"], 500.0)
        self.assertEqual(throughput["treatment"], 550.0)
        self.assertEqual(throughput["percentage_change"], 10.0)
        self.assertEqual(throughput["result"], "better")

        # Check response time metrics (lower is better)
        rt_mean = results["response_time_metrics"]["mean"]
        self.assertEqual(rt_mean["baseline"], 10.0)
        self.assertEqual(rt_mean["treatment"], 9.0)
        self.assertEqual(rt_mean["percentage_change"], -10.0)
        self.assertEqual(rt_mean["result"], "better")

    def test_format_table(self):
        """Test table formatting."""
        results = self.command._compare_performance(
            self.baseline_data, self.treatment_data, "baseline.json", "treatment.json"
        )

        table_output = self.command._format_table(results)

        # Check that table contains expected elements
        self.assertIn("Performance Comparison Results", table_output)
        self.assertIn("baseline.json", table_output)
        self.assertIn("treatment.json", table_output)
        self.assertIn("Memory Usage", table_output)
        self.assertIn("Throughput", table_output)
        self.assertIn("Response mean", table_output)
        self.assertIn("better", table_output)

    def test_format_summary(self):
        """Test summary formatting."""
        results = self.command._compare_performance(
            self.baseline_data, self.treatment_data, "baseline.json", "treatment.json"
        )

        summary_output = self.command._format_summary(results)

        # Check that summary contains expected elements
        self.assertIn("Performance Summary", summary_output)
        self.assertIn("baseline.json vs treatment.json", summary_output)
        self.assertIn("metrics improved", summary_output)
        self.assertIn("Significant changes", summary_output)

    @patch("builtins.print")
    def test_execute_success_json_output(self, mock_print):
        """Test successful execution with JSON output to stdout."""
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as baseline_file:
            json.dump(self.baseline_data, baseline_file)
            baseline_path = baseline_file.name

        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as treatment_file:
            json.dump(self.treatment_data, treatment_file)
            treatment_path = treatment_file.name

        try:
            # Create mock args
            args = MagicMock()
            args.baseline_file = baseline_path
            args.treatment_file = treatment_path
            args.output = None
            args.format = "json"
            args.metrics = None

            # Execute command
            self.command.execute(args)

            # Check that print was called with JSON output
            mock_print.assert_called_once()
            printed_output = mock_print.call_args[0][0]

            # Verify it's valid JSON
            result = json.loads(printed_output)
            self.assertIn("baseline_file", result)
            self.assertIn("treatment_file", result)

        finally:
            # Clean up temp files
            os.unlink(baseline_path)
            os.unlink(treatment_path)

    @patch("builtins.print")
    def test_execute_success_table_output(self, mock_print):
        """Test successful execution with table output."""
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as baseline_file:
            json.dump(self.baseline_data, baseline_file)
            baseline_path = baseline_file.name

        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as treatment_file:
            json.dump(self.treatment_data, treatment_file)
            treatment_path = treatment_file.name

        try:
            # Create mock args
            args = MagicMock()
            args.baseline_file = baseline_path
            args.treatment_file = treatment_path
            args.output = None
            args.format = "table"
            args.metrics = None

            # Execute command
            self.command.execute(args)

            # Check that print was called with table output
            mock_print.assert_called_once()
            printed_output = mock_print.call_args[0][0]

            # Verify it contains table elements
            self.assertIn("Performance Comparison Results", printed_output)
            self.assertIn("Memory Usage", printed_output)

        finally:
            # Clean up temp files
            os.unlink(baseline_path)
            os.unlink(treatment_path)

    @patch("builtins.print")
    def test_execute_with_output_file(self, mock_print):
        """Test execution with output file."""
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as baseline_file:
            json.dump(self.baseline_data, baseline_file)
            baseline_path = baseline_file.name

        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as treatment_file:
            json.dump(self.treatment_data, treatment_file)
            treatment_path = treatment_file.name

        with tempfile.NamedTemporaryFile(mode="w", suffix=".txt", delete=False) as output_file:
            output_path = output_file.name

        try:
            # Create mock args
            args = MagicMock()
            args.baseline_file = baseline_path
            args.treatment_file = treatment_path
            args.output = output_path
            args.format = "json"
            args.metrics = None

            # Execute command
            self.command.execute(args)

            # Check that file was written
            with open(output_path, "r") as f:
                content = f.read()
                result = json.loads(content)
                self.assertIn("baseline_file", result)

            # Check that success message was printed
            mock_print.assert_called_once()
            self.assertTrue(mock_print.call_args[0][0].startswith("Results written to"))

        finally:
            # Clean up temp files
            os.unlink(baseline_path)
            os.unlink(treatment_path)
            os.unlink(output_path)

    @patch("sys.exit")
    @patch("builtins.print")
    def test_execute_file_not_found(self, mock_print, mock_exit):
        """Test execution with non-existent file."""
        args = MagicMock()
        args.baseline_file = "nonexistent_baseline.json"
        args.treatment_file = "nonexistent_treatment.json"
        args.output = None
        args.format = "json"
        args.metrics = None

        self.command.execute(args)

        # Check that error was printed and exit was called
        mock_print.assert_called_once()
        error_message = mock_print.call_args[0][0]
        self.assertIn("Error: File not found", error_message)
        mock_exit.assert_called_once_with(1)

    @patch("sys.exit")
    @patch("builtins.print")
    def test_execute_invalid_json(self, mock_print, mock_exit):
        """Test execution with invalid JSON file."""
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as invalid_file:
            invalid_file.write("invalid json content")
            invalid_path = invalid_file.name

        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as treatment_file:
            json.dump(self.treatment_data, treatment_file)
            treatment_path = treatment_file.name

        try:
            args = MagicMock()
            args.baseline_file = invalid_path
            args.treatment_file = treatment_path
            args.output = None
            args.format = "json"
            args.metrics = None

            self.command.execute(args)

            # Check that error was printed and exit was called
            mock_print.assert_called_once()
            error_message = mock_print.call_args[0][0]
            self.assertIn("Error: Invalid JSON file", error_message)
            mock_exit.assert_called_once_with(1)

        finally:
            # Clean up temp files
            os.unlink(invalid_path)
            os.unlink(treatment_path)


if __name__ == "__main__":
    unittest.main()
