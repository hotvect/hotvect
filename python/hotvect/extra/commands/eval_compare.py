"""Evaluation comparison command for hv-extra CLI."""

import json
from pathlib import Path

from .base import BaseCommand


class EvalCompareCommand(BaseCommand):
    """Compare ML evaluation results between two JSON files."""

    @classmethod
    def register_parser(cls, subparsers):
        """Register the compare-evaluations command parser."""
        parser = subparsers.add_parser(
            "compare-evaluations",
            help="Compare ML evaluation results between two JSON files (supports backtest result.json format)",
        )
        parser.add_argument(
            "baseline_file", help="Path to the baseline evaluation results JSON file (supports backtest result.json)"
        )
        parser.add_argument(
            "treatment_file", help="Path to the treatment evaluation results JSON file (supports backtest result.json)"
        )
        parser.add_argument(
            "-o",
            "--output",
            help="Output file path for comparison results (optional, prints to stdout if not specified)",
        )
        return parser

    def execute(self, args):
        """Execute the evaluation comparison."""
        # Load evaluation files
        baseline_data = self._load_evaluation_file(args.baseline_file)
        treatment_data = self._load_evaluation_file(args.treatment_file)

        # Add file path info for result display
        baseline_data["_file_path"] = args.baseline_file
        treatment_data["_file_path"] = args.treatment_file

        # Perform comparison
        comparison_result = self._compare_evaluations(baseline_data, treatment_data)

        # Format as JSON
        output = json.dumps(comparison_result, indent=2)

        # Write output
        if args.output:
            with open(args.output, "w") as f:
                f.write(output)
            print(f"Comparison results saved to {args.output}")
        else:
            print(output)

    def _load_evaluation_file(self, file_path):
        """Load and validate backtest result.json file."""
        if not Path(file_path).exists():
            raise FileNotFoundError(f"Evaluation file not found: {file_path}")

        try:
            with open(file_path, "r") as f:
                data = json.load(f)

            # Handle both backtest result.json format and direct evaluation metadata format
            if "evaluate" in data:
                # Extract metrics from backtest result.json format
                metrics = self._extract_metrics_from_nested(data["evaluate"])
            else:
                # Direct evaluation metadata format
                metrics = self._extract_metrics_from_nested(data)
            return metrics

        except json.JSONDecodeError as e:
            raise ValueError(f"Invalid JSON in file {file_path}: {e}")

    def _extract_metrics_from_nested(self, obj, prefix=""):
        """Extract flat metrics from nested structure."""
        metrics = {}
        if isinstance(obj, dict):
            for key, value in obj.items():
                full_key = f"{prefix}.{key}" if prefix else key
                if isinstance(value, (int, float)):
                    metrics[full_key] = value
                elif isinstance(value, dict):
                    if "mean" in value and isinstance(value["mean"], (int, float)):
                        # Extract .mean values (like roc_auc.mean)
                        metrics[full_key] = value["mean"]
                    else:
                        # Recursively extract nested metrics
                        metrics.update(self._extract_metrics_from_nested(value, full_key))
        return metrics

    def _compare_evaluations(self, baseline, treatment):
        """Compare evaluation metrics between baseline and treatment."""
        # Find all common numeric metrics between both files
        baseline_metrics = set(baseline.keys())
        treatment_metrics = set(treatment.keys())
        available_metrics = baseline_metrics.intersection(treatment_metrics)

        # Compare all available numeric metrics
        metrics_to_compare = []
        for key in available_metrics:
            if isinstance(baseline[key], (int, float)):
                metrics_to_compare.append(key)

        comparison_result = {
            "baseline_file": baseline.get("_file_path", "baseline"),
            "treatment_file": treatment.get("_file_path", "treatment"),
            "metric_comparisons": {},
        }

        for metric in metrics_to_compare:
            baseline_val = baseline[metric]
            treatment_val = treatment[metric]

            # Calculate absolute change
            absolute_change = treatment_val - baseline_val

            metric_comparison = {
                "baseline": baseline_val,
                "treatment": treatment_val,
                "absolute_change": absolute_change,
            }

            comparison_result["metric_comparisons"][metric] = metric_comparison

        return comparison_result
