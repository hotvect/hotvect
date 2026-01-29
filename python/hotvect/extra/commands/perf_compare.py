"""Performance comparison command for hv-extra CLI."""

import json
import sys
from typing import Any, Dict

from .base import BaseCommand


class PerfCompareCommand(BaseCommand):
    """Compare performance test results between baseline and treatment files."""

    @classmethod
    def register_parser(cls, subparsers):
        """Register the perf-compare command parser."""
        parser = subparsers.add_parser("perf-compare", help="Compare performance test results between two JSON files")
        parser.add_argument("baseline_file", help="Path to baseline performance results JSON file")
        parser.add_argument("treatment_file", help="Path to treatment performance results JSON file")
        parser.add_argument("--output", "-o", help="Output file path (default: stdout)", default=None)
        parser.add_argument(
            "--format", choices=["json", "table", "summary"], default="json", help="Output format (default: json)"
        )
        parser.add_argument("--metrics", help="Specific metrics to compare (comma-separated)", default=None)
        return parser

    def execute(self, args):
        """Execute the performance comparison."""
        try:
            # Load the JSON files
            with open(args.baseline_file, "r") as f:
                baseline_data = json.load(f)

            with open(args.treatment_file, "r") as f:
                treatment_data = json.load(f)

            # Perform comparison
            results = self._compare_performance(
                baseline_data, treatment_data, args.baseline_file, args.treatment_file, args.metrics
            )

            # Format and output results
            if args.format == "json":
                output = json.dumps(results, indent=2)
            elif args.format == "table":
                output = self._format_table(results)
            else:  # summary
                output = self._format_summary(results)

            if args.output:
                with open(args.output, "w") as f:
                    f.write(output)
                print(f"Results written to {args.output}")
            else:
                print(output)

        except FileNotFoundError as e:
            print(f"Error: File not found - {e}", file=sys.stderr)
            sys.exit(1)
        except json.JSONDecodeError as e:
            print(f"Error: Invalid JSON file - {e}", file=sys.stderr)
            sys.exit(1)
        except Exception as e:
            print(f"Error: {e}", file=sys.stderr)
            sys.exit(1)

    def _compute_percentage_change(self, old: float, new: float) -> float:
        """Compute percentage change from old to new value."""
        diff = new - old
        if old != 0:
            return (diff / old) * 100
        else:
            return float("inf")

    def _compare_performance(
        self,
        baseline_data: Dict[str, Any],
        treatment_data: Dict[str, Any],
        baseline_file: str,
        treatment_file: str,
        specific_metrics: str = None,
    ) -> Dict[str, Any]:
        """Compare performance metrics between baseline and treatment."""

        results = {
            "baseline_file": baseline_file,
            "treatment_file": treatment_file,
            "max_memory_usage": {},
            "mean_throughput": {},
            "response_time_mean_throughput": {},
            "response_time_metrics": {},
        }

        # Compare max_memory_usage (lower is better)
        memory_baseline = baseline_data["max_memory_usage"]
        memory_treatment = treatment_data["max_memory_usage"]
        memory_change = self._compute_percentage_change(memory_baseline, memory_treatment)
        memory_result = "better" if memory_change < 0 else "worse"

        results["max_memory_usage"] = {
            "baseline": memory_baseline,
            "treatment": memory_treatment,
            "percentage_change": memory_change,
            "result": memory_result,
        }

        # Compare mean_throughput (higher is better)
        throughput_baseline = baseline_data["mean_throughput"]
        throughput_treatment = treatment_data["mean_throughput"]
        throughput_change = self._compute_percentage_change(throughput_baseline, throughput_treatment)
        throughput_result = "better" if throughput_change > 0 else "worse"

        results["mean_throughput"] = {
            "baseline": throughput_baseline,
            "treatment": throughput_treatment,
            "percentage_change": throughput_change,
            "result": throughput_result,
        }

        # Compare mean_throughput in response_time_metrics (higher is better)
        rt_mean_throughput_baseline = baseline_data["response_time_metrics"]["mean_throughput"]["mean"]
        rt_mean_throughput_treatment = treatment_data["response_time_metrics"]["mean_throughput"]["mean"]
        rt_throughput_change = self._compute_percentage_change(
            rt_mean_throughput_baseline, rt_mean_throughput_treatment
        )
        rt_throughput_result = "better" if rt_throughput_change > 0 else "worse"

        results["response_time_mean_throughput"] = {
            "baseline": rt_mean_throughput_baseline,
            "treatment": rt_mean_throughput_treatment,
            "percentage_change": rt_throughput_change,
            "result": rt_throughput_result,
        }

        # Compare response time metrics (lower is better)
        response_time_metrics = ["mean", "p50", "p75", "p95", "p99", "p999"]
        for metric in response_time_metrics:
            rt_baseline = baseline_data["response_time_metrics"][metric]["mean"]
            rt_treatment = treatment_data["response_time_metrics"][metric]["mean"]
            rt_change = self._compute_percentage_change(rt_baseline, rt_treatment)
            rt_result = "better" if rt_change < 0 else "worse"

            results["response_time_metrics"][metric] = {
                "baseline": rt_baseline,
                "treatment": rt_treatment,
                "percentage_change": rt_change,
                "result": rt_result,
            }

        return results

    def _format_table(self, results: Dict[str, Any]) -> str:
        """Format results as a table."""
        output = []
        output.append("Performance Comparison Results")
        output.append("=" * 50)
        output.append(f"Baseline: {results['baseline_file']}")
        output.append(f"Treatment: {results['treatment_file']}")
        output.append("")

        output.append(
            "Metric".ljust(25) + "Baseline".ljust(15) + "Treatment".ljust(15) + "Change %".ljust(12) + "Result"
        )
        output.append("-" * 80)

        # Memory usage
        mem = results["max_memory_usage"]
        output.append(
            f"{'Memory Usage'.ljust(25)}{mem['baseline']:<15.2f}{mem['treatment']:<15.2f}{mem['percentage_change']:<12.2f}{mem['result']}"
        )

        # Throughput
        thr = results["mean_throughput"]
        output.append(
            f"{'Throughput'.ljust(25)}{thr['baseline']:<15.2f}{thr['treatment']:<15.2f}{thr['percentage_change']:<12.2f}{thr['result']}"
        )

        # Response time metrics
        for metric_name, data in results["response_time_metrics"].items():
            display_name = f"Response {metric_name}"
            output.append(
                f"{display_name.ljust(25)}{data['baseline']:<15.2f}{data['treatment']:<15.2f}{data['percentage_change']:<12.2f}{data['result']}"
            )

        return "\n".join(output)

    def _format_summary(self, results: Dict[str, Any]) -> str:
        """Format results as a summary."""
        output = []
        output.append(f"Performance Summary: {results['baseline_file']} vs {results['treatment_file']}")
        output.append("")

        better_count = 0
        worse_count = 0

        all_metrics = [
            results["max_memory_usage"],
            results["mean_throughput"],
            results["response_time_mean_throughput"],
        ]
        all_metrics.extend(results["response_time_metrics"].values())

        for metric in all_metrics:
            if metric["result"] == "better":
                better_count += 1
            else:
                worse_count += 1

        output.append(f"Overall: {better_count} metrics improved, {worse_count} metrics worsened")

        # Highlight significant changes
        significant_changes = []
        for name, metric in [("Memory", results["max_memory_usage"]), ("Throughput", results["mean_throughput"])]:
            if abs(metric["percentage_change"]) > 5:  # >5% change
                significant_changes.append(f"  {name}: {metric['percentage_change']:+.1f}% ({metric['result']})")

        if significant_changes:
            output.append("\nSignificant changes (>5%):")
            output.extend(significant_changes)

        return "\n".join(output)
