"""Tests for compare-evaluations command."""

import argparse
import json
import tempfile
import unittest
from unittest.mock import MagicMock, patch

from hotvect.extra.commands.eval_compare import EvalCompareCommand


class TestEvalCompareCommand(unittest.TestCase):
    """Test cases for EvalCompareCommand."""

    def setUp(self):
        """Set up test fixtures."""
        self.command = EvalCompareCommand()

        # Sample evaluation data - baseline
        self.baseline_eval = {
            "auc": 0.8234,
            "precision": 0.7456,
            "recall": 0.6789,
            "f1_score": 0.7102,
            "accuracy": 0.8901,
            "log_loss": 0.3456,
            "matthews_correlation": 0.5678,
            "pr_auc": 0.7234,
            "roc_auc": 0.8234,
            "samples_count": 2000,
        }

        # Sample evaluation data - treatment (improved)
        self.treatment_eval_improved = {
            "auc": 0.8456,
            "precision": 0.7623,
            "recall": 0.6834,
            "f1_score": 0.7215,
            "accuracy": 0.9012,
            "log_loss": 0.3234,
            "matthews_correlation": 0.5834,
            "pr_auc": 0.7456,
            "roc_auc": 0.8456,
            "samples_count": 2000,
        }

        # Sample evaluation data - treatment (degraded)
        self.treatment_eval_degraded = {
            "auc": 0.8012,
            "precision": 0.7234,
            "recall": 0.6543,
            "f1_score": 0.6876,
            "accuracy": 0.8678,
            "log_loss": 0.3678,
            "matthews_correlation": 0.5456,
            "pr_auc": 0.7012,
            "roc_auc": 0.8012,
            "samples_count": 2000,
        }

        # Sample backtest result.json format data
        self.backtest_baseline = {
            "algorithm_id": "my-ranking-algorithm@64.4.0-1day",
            "evaluate": {
                "ndcg_at_10": 0.005176030571598333,
                "roc_auc": {"mean": 0.7355214300336563},
                "pr_auc": {"mean": 0.013091642086447308},
                "mean_score": {"mean": 0.046327661614099534},
                "diversity@5": 0.737151248164464,
                "online": {
                    "algorithm": {
                        "roc_auc": {"mean": 0.840205894430204},
                        "pr_auc": {"mean": 0.04240350762976185},
                    }
                },
            },
        }

        self.backtest_treatment = {
            "algorithm_id": "my-ranking-algorithm@77.0.0-1day",
            "evaluate": {
                "ndcg_at_10": 0.005150867842004659,
                "roc_auc": {"mean": 0.7401002039727855},
                "pr_auc": {"mean": 0.011716919922031476},
                "mean_score": {"mean": 0.0464984767635647},
                "diversity@5": 0.7387254038179147,
                "online": {
                    "algorithm": {
                        "roc_auc": {"mean": 0.840205894430204},
                        "pr_auc": {"mean": 0.04240350762976185},
                    }
                },
            },
        }

    def test_register_parser(self):
        """Test that register_parser creates proper argument parser."""
        main_parser = argparse.ArgumentParser()
        subparsers = main_parser.add_subparsers(dest="command")

        EvalCompareCommand.register_parser(subparsers)

        # Test required arguments
        args = main_parser.parse_args(["compare-evaluations", "baseline.json", "treatment.json"])

        self.assertEqual(args.command, "compare-evaluations")
        self.assertEqual(args.baseline_file, "baseline.json")
        self.assertEqual(args.treatment_file, "treatment.json")
        self.assertIsNone(args.output)  # default

        # Test optional arguments
        args = main_parser.parse_args(
            [
                "compare-evaluations",
                "baseline.json",
                "treatment.json",
                "-o",
                "output.json",
            ]
        )

        self.assertEqual(args.output, "output.json")

    def _create_eval_file(self, data, suffix=".json"):
        """Helper to create temporary backtest result.json file."""
        temp_file = tempfile.NamedTemporaryFile(mode="w", suffix=suffix, delete=False)
        # Wrap flat metrics in backtest result.json format
        backtest_data = {"evaluate": data}
        json.dump(backtest_data, temp_file, indent=2)
        temp_file.close()
        return temp_file.name

    def test_load_evaluation_file_success(self):
        """Test successful loading of evaluation file."""
        eval_file = self._create_eval_file(self.baseline_eval)
        try:
            data = self.command._load_evaluation_file(eval_file)
            # Should extract and flatten metrics from backtest format
            self.assertEqual(data["auc"], self.baseline_eval["auc"])
            self.assertEqual(data["precision"], self.baseline_eval["precision"])
            self.assertEqual(data["roc_auc"], self.baseline_eval["roc_auc"])
            self.assertEqual(data["pr_auc"], self.baseline_eval["pr_auc"])
        finally:
            import os

            os.unlink(eval_file)

    def test_load_evaluation_file_not_found(self):
        """Test loading non-existent evaluation file."""
        with self.assertRaises(FileNotFoundError):
            self.command._load_evaluation_file("nonexistent.json")

    def test_load_evaluation_file_invalid_json(self):
        """Test loading invalid JSON file."""
        temp_file = tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False)
        temp_file.write("invalid json content")
        temp_file.close()

        try:
            with self.assertRaises(ValueError):
                self.command._load_evaluation_file(temp_file.name)
        finally:
            import os

            os.unlink(temp_file.name)

    def test_compare_evaluations_all_metrics(self):
        """Test comparison of all available metrics."""
        result = self.command._compare_evaluations(self.baseline_eval, self.treatment_eval_improved)

        self.assertGreater(len(result["metric_comparisons"]), 0)
        self.assertIn("auc", result["metric_comparisons"])
        self.assertIn("precision", result["metric_comparisons"])
        self.assertIn("log_loss", result["metric_comparisons"])

        # Check AUC comparison
        auc_comparison = result["metric_comparisons"]["auc"]
        self.assertEqual(auc_comparison["baseline"], 0.8234)
        self.assertEqual(auc_comparison["treatment"], 0.8456)
        self.assertEqual(auc_comparison["absolute_change"], 0.8456 - 0.8234)

        # Check log_loss comparison
        log_loss_comparison = result["metric_comparisons"]["log_loss"]
        self.assertEqual(log_loss_comparison["baseline"], 0.3456)
        self.assertEqual(log_loss_comparison["treatment"], 0.3234)
        self.assertEqual(log_loss_comparison["absolute_change"], 0.3234 - 0.3456)

    def test_compare_evaluations_pr_auc_roc_auc_special_handling(self):
        """Test special handling for pr_auc and roc_auc using subtraction."""
        result = self.command._compare_evaluations(self.baseline_eval, self.treatment_eval_improved)

        # Check pr_auc absolute change
        pr_auc_comparison = result["metric_comparisons"]["pr_auc"]
        self.assertEqual(pr_auc_comparison["baseline"], 0.7234)
        self.assertEqual(pr_auc_comparison["treatment"], 0.7456)
        self.assertEqual(pr_auc_comparison["absolute_change"], 0.7456 - 0.7234)

        # Check roc_auc absolute change
        roc_auc_comparison = result["metric_comparisons"]["roc_auc"]
        self.assertEqual(roc_auc_comparison["baseline"], 0.8234)
        self.assertEqual(roc_auc_comparison["treatment"], 0.8456)
        self.assertEqual(roc_auc_comparison["absolute_change"], 0.8456 - 0.8234)

    def test_compare_evaluations_absolute_change(self):
        """Test that all metrics show absolute change."""
        result = self.command._compare_evaluations(self.baseline_eval, self.treatment_eval_improved)

        # Check AUC absolute change
        auc_comparison = result["metric_comparisons"]["auc"]
        self.assertEqual(auc_comparison["absolute_change"], 0.8456 - 0.8234)

        # Check precision absolute change
        precision_comparison = result["metric_comparisons"]["precision"]
        self.assertEqual(precision_comparison["absolute_change"], 0.7623 - 0.7456)

    def test_compare_evaluations_negative_change(self):
        """Test comparison with negative changes."""
        result = self.command._compare_evaluations(self.baseline_eval, self.treatment_eval_degraded)

        self.assertGreater(len(result["metric_comparisons"]), 0)

        # Check AUC negative change
        auc_comparison = result["metric_comparisons"]["auc"]
        self.assertEqual(auc_comparison["absolute_change"], 0.8012 - 0.8234)
        self.assertLess(auc_comparison["absolute_change"], 0)

    def test_extract_metrics_from_backtest_format(self):
        """Test extracting metrics from backtest result.json format."""
        extracted = self.command._extract_metrics_from_nested(self.backtest_baseline["evaluate"])

        # Check that metrics were properly extracted and flattened
        self.assertEqual(extracted["roc_auc"], 0.7355214300336563)  # mean value extracted
        self.assertEqual(extracted["pr_auc"], 0.013091642086447308)  # mean value extracted
        self.assertEqual(extracted["ndcg_at_10"], 0.005176030571598333)  # direct value
        self.assertEqual(extracted["diversity@5"], 0.737151248164464)  # direct value
        self.assertEqual(extracted["online.algorithm.roc_auc"], 0.840205894430204)  # nested mean value
        self.assertEqual(extracted["online.algorithm.pr_auc"], 0.04240350762976185)  # nested mean value

    def test_compare_evaluations_backtest_format(self):
        """Test comparison with backtest result.json format."""
        baseline_metrics = self.command._extract_metrics_from_nested(self.backtest_baseline["evaluate"])
        treatment_metrics = self.command._extract_metrics_from_nested(self.backtest_treatment["evaluate"])

        result = self.command._compare_evaluations(baseline_metrics, treatment_metrics)

        # Check that we found metrics to compare
        self.assertGreater(len(result["metric_comparisons"]), 0)

        # Check that both regular and online AUC metrics are compared
        self.assertIn("roc_auc", result["metric_comparisons"])
        self.assertIn("online.algorithm.roc_auc", result["metric_comparisons"])

        # Check AUC change (regular)
        roc_auc_comparison = result["metric_comparisons"]["roc_auc"]
        expected_change = 0.7401002039727855 - 0.7355214300336563
        self.assertAlmostEqual(roc_auc_comparison["absolute_change"], expected_change, places=10)

        # Check online AUC (should be zero change)
        online_roc_auc_comparison = result["metric_comparisons"]["online.algorithm.roc_auc"]
        self.assertEqual(online_roc_auc_comparison["absolute_change"], 0.0)

    @patch("builtins.print")
    def test_execute_success_json_format(self, mock_print):
        """Test successful execution with JSON output to stdout."""
        baseline_file = self._create_eval_file(self.baseline_eval)
        treatment_file = self._create_eval_file(self.treatment_eval_improved)

        try:
            args = MagicMock()
            args.baseline_file = baseline_file
            args.treatment_file = treatment_file
            args.output = None

            self.command.execute(args)

            # Check that JSON was printed
            self.assertTrue(mock_print.called)
            output = str(mock_print.call_args_list[0][0][0])
            # Should be valid JSON
            result = json.loads(output)
            self.assertIn("metric_comparisons", result)
            self.assertGreater(len(result["metric_comparisons"]), 0)

        finally:
            import os

            os.unlink(baseline_file)
            os.unlink(treatment_file)

    def test_execute_with_output_file(self):
        """Test execution with output file specified."""
        baseline_file = self._create_eval_file(self.baseline_eval)
        treatment_file = self._create_eval_file(self.treatment_eval_improved)
        output_file = tempfile.NamedTemporaryFile(delete=False)
        output_file.close()

        try:
            args = MagicMock()
            args.baseline_file = baseline_file
            args.treatment_file = treatment_file
            args.output = output_file.name

            with patch("builtins.print") as mock_print:
                self.command.execute(args)

            # Check that file was written
            with open(output_file.name, "r") as f:
                content = f.read()
            # Should be valid JSON
            result = json.loads(content)
            self.assertIn("metric_comparisons", result)

            # Check that success message was printed
            mock_print.assert_called_with(f"Comparison results saved to {output_file.name}")

        finally:
            import os

            os.unlink(baseline_file)
            os.unlink(treatment_file)
            os.unlink(output_file.name)

    def test_execute_file_not_found(self):
        """Test execution with non-existent baseline file."""
        treatment_file = self._create_eval_file(self.treatment_eval_improved)

        try:
            args = MagicMock()
            args.baseline_file = "nonexistent.json"
            args.treatment_file = treatment_file
            args.output = None

            # Expect FileNotFoundError to be raised directly
            with self.assertRaises(FileNotFoundError) as cm:
                self.command.execute(args)

            # Verify the error message contains the expected file path
            self.assertIn("nonexistent.json", str(cm.exception))

        finally:
            import os

            os.unlink(treatment_file)


if __name__ == "__main__":
    unittest.main()
