"""Tests for hv-ext metrics subcommands."""

import argparse
import json
import os
import tempfile
import unittest
from io import StringIO
from unittest.mock import patch

import pandas as pd

from hotvect.extra.commands.metrics import (
    MetricsCommand,
    _build_plot_dataset,
    _chunked_metrics,
    _parse_relative_baseline,
    _relative_frame,
)


class TestMetricsCommand(unittest.TestCase):
    def test_chunked_metrics_groups_four_per_page(self):
        metrics = [f"metric_{i}" for i in range(7)]
        self.assertEqual(
            _chunked_metrics(metrics),
            [metrics[:4], metrics[4:]],
        )

    def test_parse_relative_baseline_online(self):
        online = _parse_relative_baseline("online:algorithm")
        self.assertEqual(
            (online.kind, online.value, online.display_name, online.version_selector),
            ("online", "algorithm", "online:algorithm", "online"),
        )

        version = _parse_relative_baseline("81.0.8")
        self.assertEqual(
            (version.kind, version.value, version.display_name, version.version_selector),
            ("version", "81.0.8", "81.0.8", "81.0.8"),
        )

    def test_relative_frame_can_exclude_baseline_for_timeseries(self):
        df = pd.DataFrame(
            [
                {"version": "81.0.8", "test_date": "2026-01-01", "p99": 10.0},
                {"version": "82.2.34", "test_date": "2026-01-01", "p99": 20.0},
                {"version": "81.0.8", "test_date": "2026-01-02", "p99": 5.0},
                {"version": "82.2.34", "test_date": "2026-01-02", "p99": 15.0},
            ]
        )
        df["version"] = pd.Categorical(df["version"], categories=["81.0.8", "82.2.34"], ordered=True)

        rel_df = _relative_frame(
            df,
            "p99",
            ["81.0.8", "82.2.34"],
            "81.0.8",
            include_baseline=False,
        )

        self.assertEqual(rel_df["version"].tolist(), ["82.2.34", "82.2.34"])
        self.assertEqual(rel_df["p99"].tolist(), [2.0, 3.0])

    def test_relative_frame_can_use_online_baseline(self):
        df = pd.DataFrame(
            [
                {"version": "online", "test_date": "2026-01-01", "map_at_50": 0.007},
                {"version": "81.0.8", "test_date": "2026-01-01", "map_at_50": 0.006},
                {"version": "82.2.34", "test_date": "2026-01-01", "map_at_50": 0.008},
                {"version": "online", "test_date": "2026-01-02", "map_at_50": 0.010},
                {"version": "81.0.8", "test_date": "2026-01-02", "map_at_50": 0.009},
                {"version": "82.2.34", "test_date": "2026-01-02", "map_at_50": 0.012},
            ]
        )
        df["version"] = pd.Categorical(df["version"], categories=["online", "81.0.8", "82.2.34"], ordered=True)

        rel_df = _relative_frame(
            df,
            "map_at_50",
            ["online", "81.0.8", "82.2.34"],
            "online",
            include_baseline=True,
        )

        self.assertEqual(rel_df["version"].tolist(), ["online", "online", "81.0.8", "81.0.8", "82.2.34", "82.2.34"])
        self.assertEqual(
            rel_df["map_at_50"].tolist(),
            [1.0, 1.0, 0.006 / 0.007, 0.009 / 0.010, 0.008 / 0.007, 0.012 / 0.010],
        )

    def test_build_plot_dataset_synthesizes_online_control_rows(self):
        records = [
            {
                "algorithm_id": "algo@82.2.34",
                "test_date": "2026-01-01",
                "roc_auc": 0.82,
                "algorithm.roc_auc": 0.81,
                "mean_score": 2.0,
                "algorithm.mean_score": 1.0,
            },
            {
                "algorithm_id": "algo@82.2.34",
                "test_date": "2026-01-02",
                "roc_auc": 0.83,
                "algorithm.roc_auc": 0.82,
                "mean_score": 2.2,
                "algorithm.mean_score": 1.1,
            },
        ]

        dataset = _build_plot_dataset(
            records=records,
            metrics=["roc_auc", "mean_score"],
            explicit_versions=["82.2.34"],
            relative_baseline="online:algorithm",
        )

        self.assertEqual(dataset.versions, ["online", "82.2.34"])
        self.assertEqual(dataset.baseline.version_selector, "online")
        self.assertEqual(sorted({row["version"] for row in dataset.table_rows}), ["82.2.34", "online"])
        online_rows = [row for row in dataset.table_rows if row["version"] == "online"]
        self.assertEqual(len(online_rows), 2)
        self.assertEqual(online_rows[0]["roc_auc"], 0.81)

    def test_register_parser(self):
        main_parser = argparse.ArgumentParser()
        subparsers = main_parser.add_subparsers(dest="command")
        MetricsCommand.register_parser(subparsers)

        args = main_parser.parse_args(["metrics", "compare-quality", "control.json", "treatment.json"])
        self.assertEqual(args.command, "metrics")
        self.assertEqual(args.metrics_command, "compare-quality")
        self.assertEqual(args.control_file, "control.json")
        self.assertEqual(args.treatment_file, "treatment.json")

        plot_args = main_parser.parse_args(["metrics", "plot", "--relative-baseline", "online:algorithm"])
        self.assertEqual(plot_args.command, "metrics")
        self.assertEqual(plot_args.metrics_command, "plot")
        self.assertEqual(plot_args.relative_baseline, "online:algorithm")

    @patch("sys.stdout", new_callable=StringIO)
    def test_compare_quality_single_day(self, mock_stdout):
        control = {
            "algorithm_id": "algo@1.0.0",
            "test_data_time": "2026-01-05",
            "evaluate": {
                "ndcg_at_10": 0.1,
                "roc_auc": {"mean": 0.8},
                "online": {"algorithm": {"roc_auc": {"mean": 0.81}}},
            },
        }
        treatment = {
            "algorithm_id": "algo@1.0.1",
            "test_data_time": "2026-01-05",
            "evaluate": {
                "ndcg_at_10": 0.11,
                "roc_auc": {"mean": 0.82},
                "online": {"algorithm": {"roc_auc": {"mean": 0.81}}},
            },
        }

        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as control_file:
            json.dump(control, control_file)
            control_path = control_file.name
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as treatment_file:
            json.dump(treatment, treatment_file)
            treatment_path = treatment_file.name

        try:
            with patch("sys.argv", ["hv-ext", "metrics", "compare-quality", control_path, treatment_path]):
                from hotvect.extra.cli import main

                main()

            payload = json.loads(mock_stdout.getvalue())
            self.assertIn("metrics", payload)
            self.assertIn("ndcg_at_10", payload["metrics"])
            self.assertIn("roc_auc", payload["metrics"])
            self.assertAlmostEqual(payload["metrics"]["roc_auc"]["absolute_change"], 0.02, places=10)
        finally:
            os.unlink(control_path)
            os.unlink(treatment_path)

    @patch("sys.stdout", new_callable=StringIO)
    def test_compare_system_single_day(self, mock_stdout):
        control = {
            "algorithm_id": "algo@1.0.0",
            "test_data_time": "2026-01-05",
            "performance_test": {
                "max_memory_usage": 1000.0,
                "response_time_metrics": {
                    "mean_throughput": {"mean": 480.0},
                    "mean": {"mean": 10.0},
                    "p50": {"mean": 8.0},
                    "p75": {"mean": 12.0},
                    "p95": {"mean": 20.0},
                    "p99": {"mean": 30.0},
                    "p999": {"mean": 50.0},
                },
            },
        }
        treatment = {
            "algorithm_id": "algo@1.0.1",
            "test_data_time": "2026-01-05",
            "performance_test": {
                "max_memory_usage": 900.0,
                "response_time_metrics": {
                    "mean_throughput": {"mean": 520.0},
                    "mean": {"mean": 9.0},
                    "p50": {"mean": 7.5},
                    "p75": {"mean": 11.0},
                    "p95": {"mean": 18.0},
                    "p99": {"mean": 25.0},
                    "p999": {"mean": 45.0},
                },
            },
        }

        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as control_file:
            json.dump(control, control_file)
            control_path = control_file.name
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as treatment_file:
            json.dump(treatment, treatment_file)
            treatment_path = treatment_file.name

        try:
            with patch("sys.argv", ["hv-ext", "metrics", "compare-system", control_path, treatment_path]):
                from hotvect.extra.cli import main

                main()

            payload = json.loads(mock_stdout.getvalue())
            self.assertIn("metrics", payload)
            self.assertIn("max_memory_usage", payload["metrics"])
            self.assertAlmostEqual(payload["metrics"]["max_memory_usage"]["absolute_change"], -100.0, places=10)
        finally:
            os.unlink(control_path)
            os.unlink(treatment_path)


if __name__ == "__main__":
    unittest.main()
