"""Tests for hv-ext metrics subcommands."""

import argparse
import json
import os
import re
import tempfile
import unittest
from io import StringIO
from pathlib import Path
from unittest.mock import patch

import pandas as pd

from hotvect.extra.commands.metrics import (
    AlgorithmSpecification,
    MetricsCommand,
    _build_cache_usage_lines,
    _build_header_lines,
    _build_plot_dataset,
    _build_provenance_lines,
    _build_specification_lines,
    _build_timing_breakdown_dataset,
    _build_timing_plot_dataset,
    _build_timing_summary_lines,
    _cache_usage_from_result,
    _chunked_metrics,
    _collect_relative_metric_frames,
    _common_benchmark_specification,
    _common_benchmark_specification_result,
    _common_evaluation_specification,
    _compact_metric_label,
    _copy_result_metadata,
    _extract_timing_breakdown,
    _extract_timing_metrics,
    _git_commit_from_git_describe,
    _has_cache_hits,
    _has_production_training_breakdown_signal,
    _is_timing_stage_skipped,
    _mean_ci_95,
    _parse_relative_baseline,
    _parse_treatment_descriptions,
    _production_training_timing_components,
    _relative_frame,
    _report_hotvect_version,
    _source_sagemaker_job_from_path,
    _validate_plot_descriptions,
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

    def test_parse_and_validate_plot_descriptions(self):
        descriptions = _parse_treatment_descriptions(["82.2.34=Candidate using ZMCLIP query encoder"])
        self.assertEqual(descriptions, {"82.2.34": "Candidate using ZMCLIP query encoder"})

        baseline_description = _validate_plot_descriptions(
            baseline_description="LMDB example-feature-state control",
            treatment_descriptions=descriptions,
            versions=["81.0.8", "82.2.34"],
            baseline=_parse_relative_baseline("81.0.8"),
        )
        self.assertEqual(baseline_description, "LMDB example-feature-state control")

        with self.assertRaisesRegex(ValueError, "VERSION=TEXT"):
            _parse_treatment_descriptions(["82.2.34:missing-equals"])
        with self.assertRaisesRegex(ValueError, "not plotted"):
            _validate_plot_descriptions(
                baseline_description=None,
                treatment_descriptions={"83.0.0": "unknown"},
                versions=["81.0.8", "82.2.34"],
                baseline=_parse_relative_baseline("81.0.8"),
            )
        with self.assertRaisesRegex(ValueError, "use --baseline-description"):
            _validate_plot_descriptions(
                baseline_description=None,
                treatment_descriptions={"81.0.8": "wrong flag"},
                versions=["81.0.8", "82.2.34"],
                baseline=_parse_relative_baseline("81.0.8"),
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

    def test_relative_frame_uses_difference_for_auc_metrics(self):
        df = pd.DataFrame(
            [
                {"version": "online", "test_date": "2026-01-01", "roc_auc": 0.70},
                {"version": "81.0.8", "test_date": "2026-01-01", "roc_auc": 0.69},
                {"version": "82.2.34", "test_date": "2026-01-01", "roc_auc": 0.72},
                {"version": "online", "test_date": "2026-01-02", "roc_auc": 0.74},
                {"version": "81.0.8", "test_date": "2026-01-02", "roc_auc": 0.73},
                {"version": "82.2.34", "test_date": "2026-01-02", "roc_auc": 0.75},
            ]
        )
        df["version"] = pd.Categorical(df["version"], categories=["online", "81.0.8", "82.2.34"], ordered=True)

        rel_df = _relative_frame(
            df,
            "roc_auc",
            ["online", "81.0.8", "82.2.34"],
            "online",
            include_baseline=True,
        )

        self.assertEqual(rel_df["version"].tolist(), ["online", "online", "81.0.8", "81.0.8", "82.2.34", "82.2.34"])
        self.assertEqual(
            rel_df["roc_auc"].tolist(),
            [0.0, 0.0, 0.69 - 0.70, 0.73 - 0.74, 0.72 - 0.70, 0.75 - 0.74],
        )

    def test_relative_frame_uses_ratio_for_map_ndcg_and_system_metrics(self):
        df = pd.DataFrame(
            [
                {"version": "64.4.0", "test_date": "2026-01-01", "map_at_50": 0.007, "ndcg_at_50": 0.02, "p99": 10.0},
                {"version": "77.1.0", "test_date": "2026-01-01", "map_at_50": 0.008, "ndcg_at_50": 0.03, "p99": 15.0},
            ]
        )
        df["version"] = pd.Categorical(df["version"], categories=["64.4.0", "77.1.0"], ordered=True)

        for metric, expected in {
            "map_at_50": [1.0, 0.008 / 0.007],
            "ndcg_at_50": [1.0, 0.03 / 0.02],
            "p99": [1.0, 1.5],
        }.items():
            rel_df = _relative_frame(
                df,
                metric,
                ["64.4.0", "77.1.0"],
                "64.4.0",
                include_baseline=True,
            )
            self.assertEqual(rel_df["version"].tolist(), ["64.4.0", "77.1.0"])
            self.assertEqual(rel_df[metric].tolist(), expected)

    def test_collect_relative_metric_frames_excludes_uncalibrated_mean_score(self):
        df = pd.DataFrame(
            [
                {"version": "81.0.8", "test_date": "2026-01-01", "roc_auc": 0.8, "mean_score": -0.1},
                {"version": "82.2.34", "test_date": "2026-01-01", "roc_auc": 0.82, "mean_score": -0.2},
            ]
        )
        df["version"] = pd.Categorical(df["version"], categories=["81.0.8", "82.2.34"], ordered=True)

        relative_point_metrics, relative_time_metrics, _, _ = _collect_relative_metric_frames(
            df=df,
            metrics=["roc_auc", "mean_score"],
            versions=["81.0.8", "82.2.34"],
            baseline_version="81.0.8",
        )

        self.assertEqual(relative_point_metrics, ["roc_auc"])
        self.assertEqual(relative_time_metrics, ["roc_auc"])

    def test_compact_metric_label_keeps_plot_labels_readable(self):
        metric = (
            "dependencies.example-ranking-algorithm-engagement-model."
            "dependencies.example-text-encoder.package_predict_params"
        )

        compact = _compact_metric_label(metric)

        self.assertLessEqual(len(compact), 52)
        self.assertEqual(compact, "...example-text-encoder.package_predict_params")

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

    def test_build_plot_dataset_places_version_baseline_first(self):
        records = [
            {
                "algorithm_id": "algo@82.2.34",
                "test_date": "2026-01-01",
                "roc_auc": 0.82,
            },
            {
                "algorithm_id": "algo@81.0.8",
                "test_date": "2026-01-01",
                "roc_auc": 0.81,
            },
        ]

        dataset = _build_plot_dataset(
            records=records,
            metrics=["roc_auc"],
            explicit_versions=["82.2.34", "81.0.8"],
            relative_baseline="81.0.8",
        )

        self.assertEqual(dataset.versions, ["81.0.8", "82.2.34"])
        self.assertEqual(list(dataset.df["version"].cat.categories), ["81.0.8", "82.2.34"])

    def test_extract_timing_metrics_expands_dependency_stages(self):
        result = {
            "timing_info_sec": {
                "total_time": 13.0,
                "prepare_dependencies": 2.0,
                "train": 10.0,
            },
            "dependencies": {
                "child-algo": {
                    "timing_info_sec": {
                        "total_time": 8.0,
                        "encode": 3.0,
                        "train": 4.0,
                    },
                    "dependencies": {
                        "grandchild": {
                            "timing_info_sec": {
                                "total_time": 1.5,
                                "predict": 0.5,
                            }
                        }
                    },
                },
                "sibling-algo": {
                    "timing_info_sec": {
                        "total_time": 6.0,
                        "train": 5.0,
                    },
                },
            },
        }

        metrics = _extract_timing_metrics(result)

        self.assertEqual(metrics["total_pipeline"], 13.0)
        self.assertNotIn("prepare_dependencies", metrics)
        self.assertEqual(metrics["train"], 10.0)
        self.assertEqual(metrics["dependencies.child-algo.total_pipeline"], 8.0)
        self.assertEqual(metrics["dependencies.child-algo.train"], 4.0)
        self.assertEqual(metrics["dependencies.child-algo.dependencies.grandchild.total_pipeline"], 1.5)
        self.assertEqual(metrics["dependencies.child-algo.dependencies.grandchild.predict"], 0.5)
        self.assertEqual(metrics["dependencies.sibling-algo.total_pipeline"], 6.0)
        self.assertEqual(metrics["dependencies.sibling-algo.train"], 5.0)

    def test_extract_timing_metrics_omits_skipped_stage_bookkeeping(self):
        result = {
            "timing_info_sec": {
                "package_predict_params": 0.003,
                "predict": 10.0,
            },
            "package_predict_params": {
                "skipped": "Because cache was available",
            },
        }

        self.assertTrue(_is_timing_stage_skipped(result, "package_predict_params"))
        self.assertFalse(_is_timing_stage_skipped(result, "predict"))

        metrics = _extract_timing_metrics(result)

        self.assertEqual(metrics, {"predict": 10.0})

    def test_copy_result_metadata_preserves_skipped_timing_stage_for_reports(self):
        record = {
            "algorithm_id": "algo@81.0.8",
            "test_date": "2026-01-01",
        }
        _copy_result_metadata(
            record,
            {
                "timing_info_sec": {
                    "package_predict_params": 0.003,
                    "predict": 10.0,
                },
                "package_predict_params": {
                    "skipped": "Because cache was available",
                    "sources": ["s3://bucket/params.zip"],
                },
            },
        )

        dataset = _build_timing_plot_dataset([record], ["81.0.8"])

        self.assertIsNotNone(dataset)
        assert dataset is not None
        self.assertEqual(dataset.metrics, ["predict"])

    def test_extract_timing_breakdown_expands_dependency_components(self):
        result = {
            "algorithm_id": "algo@81.0.8",
            "test_date": "2026-01-01",
            "timing_info_sec": {
                "total_time": 18.0,
                "prepare_dependencies": 10.0,
                "predict": 5.0,
                "evaluate": 3.0,
            },
            "dependencies": {
                "child-algo": {
                    "timing_info_sec": {
                        "total_time": 4.0,
                        "prepare_dependencies": 1.0,
                        "train": 2.0,
                    },
                    "dependencies": {
                        "grandchild-algo": {
                            "timing_info_sec": {
                                "total_time": 1.0,
                                "predict": 0.5,
                            }
                        }
                    },
                },
                "sibling-algo": {
                    "timing_info_sec": {
                        "total_time": 2.0,
                        "prepare_dependencies": 0.5,
                        "train": 1.5,
                    },
                },
            },
        }

        breakdown = _extract_timing_breakdown(result)

        self.assertNotIn("prepare_dependencies", breakdown)
        self.assertEqual(breakdown["dependency_overhead"], 4.5)
        self.assertEqual(breakdown["predict"], 5.0)
        self.assertEqual(breakdown["evaluate"], 3.0)
        self.assertEqual(breakdown["dependencies.child-algo.train"], 2.0)
        self.assertEqual(breakdown["dependencies.child-algo.unaccounted"], 1.0)
        self.assertEqual(breakdown["dependencies.child-algo.dependencies.grandchild-algo.predict"], 0.5)
        self.assertEqual(breakdown["dependencies.child-algo.dependencies.grandchild-algo.unaccounted"], 0.5)
        self.assertNotIn("dependencies.sibling-algo.prepare_dependencies", breakdown)
        self.assertEqual(breakdown["dependencies.sibling-algo.train"], 1.5)
        self.assertNotIn("dependencies.sibling-algo.unaccounted", breakdown)
        self.assertAlmostEqual(sum(breakdown.values()), 18.0)

        dataset = _build_timing_breakdown_dataset([result], ["81.0.8"])
        self.assertIsNotNone(dataset)
        assert dataset is not None
        self.assertIn("dependencies.child-algo.train", dataset.components)
        self.assertIn("dependencies.child-algo.dependencies.grandchild-algo.predict", dataset.components)
        self.assertIn("dependencies.sibling-algo.train", dataset.components)

    def test_extract_timing_breakdown_does_not_emit_opaque_prepare_dependencies(self):
        result = {
            "algorithm_id": "algo@81.0.8",
            "test_date": "2026-01-01",
            "timing_info_sec": {
                "prepare_dependencies": 2.0,
                "package_predict_params": 3.0,
            },
        }

        breakdown = _extract_timing_breakdown(result)

        self.assertEqual(breakdown, {"package_predict_params": 3.0})

    def test_extract_timing_breakdown_keeps_dependency_overhead_when_dependency_total_is_zero(self):
        result = {
            "algorithm_id": "algo@81.0.8",
            "test_date": "2026-01-01",
            "timing_info_sec": {
                "total_time": 10.0,
                "prepare_dependencies": 5.0,
                "train": 5.0,
            },
            "dependencies": {
                "child-algo": {
                    "timing_info_sec": {
                        "total_time": 0.0,
                    },
                }
            },
        }

        breakdown = _extract_timing_breakdown(result)

        self.assertEqual(
            breakdown,
            {
                "dependency_overhead": 5.0,
                "train": 5.0,
            },
        )

    def test_extract_timing_breakdown_omits_skipped_stage_bookkeeping(self):
        result = {
            "algorithm_id": "algo@81.0.8",
            "test_date": "2026-01-01",
            "timing_info_sec": {
                "total_time": 10.003,
                "package_predict_params": 0.003,
                "train": 10.0,
            },
            "package_predict_params": {
                "skipped": "Because cache was available",
            },
        }

        breakdown = _extract_timing_breakdown(result)

        self.assertEqual(breakdown, {"train": 10.0})

    def test_production_training_timing_components_exclude_backtest_only_stages(self):
        self.assertEqual(
            _production_training_timing_components(
                [
                    "prepare_dependencies",
                    "package_predict_params",
                    "predict",
                    "evaluate",
                    "performance_test",
                    "dependencies.catalog.prepare_dependencies",
                    "dependencies.catalog.dependency_overhead",
                    "dependencies.catalog.encode",
                    "dependencies.catalog.predict",
                    "dependencies.catalog.unaccounted",
                ]
            ),
            [
                "package_predict_params",
                "dependencies.catalog.dependency_overhead",
                "dependencies.catalog.encode",
                "dependencies.catalog.unaccounted",
            ],
        )

    def test_production_training_breakdown_signal_rejects_top_level_package_only(self):
        self.assertFalse(_has_production_training_breakdown_signal(["package_predict_params"]))
        self.assertTrue(
            _has_production_training_breakdown_signal(
                ["package_predict_params", "dependencies.catalog.encode_parameter"]
            )
        )
        self.assertTrue(_has_production_training_breakdown_signal(["dependencies.catalog.package_predict_params"]))

    def test_mean_ci_95_uses_precise_student_t_quantile_for_small_samples(self):
        mean, ci = _mean_ci_95([1.0, 2.0])

        self.assertEqual(mean, 1.5)
        self.assertIsNotNone(ci)
        assert ci is not None
        low, high = ci
        self.assertEqual(low, 0.0)
        self.assertAlmostEqual(high, 7.853102368216048, places=4)

    def test_build_timing_plot_dataset_and_summary(self):
        records = [
            {
                "algorithm_id": "algo@81.0.8",
                "test_date": "2026-01-01",
                "timing_info_sec": {"total_time": 12.5, "predict": 10.0, "evaluate": 2.0, "performance_test": 0.0},
            },
            {
                "algorithm_id": "algo@81.0.8",
                "test_date": "2026-01-02",
                "timing_info_sec": {"total_time": 13.5, "predict": 11.0, "evaluate": 2.0, "performance_test": 0.0},
            },
            {
                "algorithm_id": "algo@82.2.34",
                "test_date": "2026-01-01",
                "timing_info_sec": {"total_time": 18.75, "predict": 15.0, "evaluate": 3.0, "performance_test": 0.0},
            },
            {
                "algorithm_id": "algo@82.2.34",
                "test_date": "2026-01-02",
                "timing_info_sec": {"total_time": 19.25, "predict": 16.0, "evaluate": 3.0, "performance_test": 0.0},
            },
        ]

        dataset = _build_timing_plot_dataset(records, ["81.0.8", "82.2.34"])
        self.assertIsNotNone(dataset)
        assert dataset is not None
        self.assertEqual(dataset.metrics, ["total_pipeline", "predict", "evaluate"])
        self.assertNotIn("performance_test", dataset.metrics)
        self.assertEqual(list(dataset.df["version"].cat.categories), ["81.0.8", "82.2.34"])

        lines = _build_timing_summary_lines(dataset, ["81.0.8", "82.2.34"], "81.0.8")
        joined = "\n".join(lines)
        self.assertIn("Pipeline Performance Summary", joined)
        self.assertIn("total_pipeline", joined)
        self.assertIn("95% CI", joined)
        self.assertIn("mean 1.46x baseline", joined)
        self.assertIn("production training path", joined)
        self.assertIn("excludes offline-only", joined)

    def test_build_cache_usage_lines_calls_out_observed_cache_hits(self):
        usage_one = _cache_usage_from_result(
            {
                "algorithm_definition": {
                    "hotvect_execution_parameters": {
                        "cache_base_dir": "s3://bucket/cache",
                        "cache_scope": "hyperparam",
                    }
                },
                "package_predict_params": {
                    "skipped": "Because cache was available",
                    "sources": ["s3://bucket/cache/algo-2026-01-01-abc123de.parameters.zip"],
                },
            }
        )
        usage_two = _cache_usage_from_result(
            {
                "algorithm_definition": {
                    "hotvect_execution_parameters": {
                        "cache_base_dir": "s3://bucket/cache",
                        "cache_scope": "hyperparam",
                    }
                },
                "package_predict_params": {
                    "skipped": "Because cache was available",
                    "sources": ["s3://bucket/cache/algo-2026-01-02-fed987ab.parameters.zip"],
                },
            }
        )

        lines = _build_cache_usage_lines(
            [
                {"algorithm_id": "algo@1", "test_date": "2026-01-01", "cache_usage": usage_one},
                {"algorithm_id": "algo@1", "test_date": "2026-01-02", "cache_usage": usage_two},
            ]
        )

        joined = "\n".join(lines)
        self.assertIn("Cache Usage", joined)
        self.assertIn("Cache Config:", joined)
        self.assertIn("cache_base_dir=s3://bucket/cache", joined)
        self.assertIn("cache_scope=hyperparam", joined)
        self.assertIn("Cache Used:", joined)
        self.assertIn("yes", joined)
        self.assertIn("package_predict_params (2/2 runs)", joined)
        self.assertIn("Cache Root:", joined)
        self.assertIn("s3://bucket/cache", joined)
        self.assertIn("algo-<date>-<hash>.parameters.zip", joined)

    def test_has_cache_hits_detects_cached_stage_usage(self):
        self.assertFalse(_has_cache_hits([{"cache_usage": {"configuration": {"cache_scope": "hyperparam"}}}]))
        self.assertTrue(
            _has_cache_hits(
                [
                    {
                        "cache_usage": {
                            "hits": [
                                {
                                    "stage": "package_predict_params",
                                    "skipped": "Because cache was available",
                                }
                            ]
                        }
                    }
                ]
            )
        )

    def test_build_plot_dataset_rejects_inconsistent_online_baseline_values(self):
        records = [
            {
                "algorithm_id": "algo@82.2.34",
                "test_date": "2026-01-01",
                "roc_auc": 0.82,
                "algorithm.roc_auc": 0.81,
            },
            {
                "algorithm_id": "algo@83.0.0",
                "test_date": "2026-01-01",
                "roc_auc": 0.83,
                "algorithm.roc_auc": 0.80,
            },
        ]

        with self.assertRaisesRegex(ValueError, "inconsistent values"):
            _build_plot_dataset(
                records=records,
                metrics=["roc_auc"],
                explicit_versions=["82.2.34", "83.0.0"],
                relative_baseline="online:algorithm",
            )

    def test_report_hotvect_version_prefers_local_pyproject(self):
        pyproject_path = Path(__file__).resolve().parents[4] / "pyproject.toml"
        match = re.search(r'^version = "([^"]+)"$', pyproject_path.read_text(), re.MULTILINE)
        self.assertIsNotNone(match)
        self.assertEqual(_report_hotvect_version(), match.group(1))

    def test_build_header_lines_uses_report_cover_layout(self):
        lines = _build_header_lines(
            source_root="/tmp/results",
            source_files=12,
            treatment_algorithm_ids=["anchored-article-topk@8.0.0"],
            baseline_label="online:algorithm",
            baseline_description="Current production online scorer.",
            treatment_descriptions={"8.0.0": "Candidate with article retrieval changes."},
            from_last_test_date="2026-03-19",
            to_last_test_date="2026-03-25",
            n_dates=6,
            date_min="2026-03-19",
            date_max="2026-03-25",
            metrics=["roc_auc", "ndcg_at_10", "mean_throughput", "p95", "p99"],
        )

        joined = "\n".join(lines)
        self.assertIn("Subject of Evaluation", joined)
        self.assertIn("Treatment:", joined)
        self.assertIn("anchored-article-topk@8.0.0", joined)
        self.assertIn("Baseline:", joined)
        self.assertIn("online:algorithm", joined)
        self.assertIn("Baseline Desc.:", joined)
        self.assertIn("Current production online scorer.", joined)
        self.assertIn("Treatment Desc.:", joined)
        self.assertIn("Candidate with article retrieval changes.", joined)
        self.assertIn("Execution Parameters", joined)
        self.assertIn("Test Date Range:", joined)
        self.assertIn("2026-03-19 .. 2026-03-25", joined)
        self.assertIn("Common Days:", joined)
        self.assertIn("6 (2026-03-19 .. 2026-03-25)", joined)
        self.assertIn("Source Data", joined)
        self.assertIn("Files:", joined)
        self.assertIn("12 result.json", joined)
        self.assertIn("Metrics:", joined)
        self.assertIn("roc_auc, ndcg_at_10, mean_throughput, p95, p99", joined)

    def test_build_specification_lines_includes_eval_spec_and_treatment_commit(self):
        self.assertEqual(_git_commit_from_git_describe("7.3.11-4-gc5709d7"), "c5709d7")
        self.assertIsNone(_git_commit_from_git_describe("7.3.12"))

        lines = _build_specification_lines(
            generated_at="2026-03-27 15:32 CET",
            generated_with="hotvect 10.13.7",
            evaluation_specification={
                "evaluation_function": {
                    "name": "standard_evaluation",
                    "arguments": {"missing_reward_policy": "zero"},
                },
                "evaluation_policy": {"missing_reward_policy": "zero"},
                "test_decoder_parameters": {"where_shared_equals": {"slot": "example-slot"}},
            },
            benchmark_specification={
                "performance_test": {"enabled": True, "samples": 200000},
                "performance_data": {"data_prefix": "perf-input", "number_of_days": 1, "lag_days": 0},
                "requested": {"samples": 200000, "sample_pool_size": 25000},
                "input": {"kind": "performance_data_spec"},
                "contract": {"instance_type": "ml.c7i.4xlarge", "max_threads": 2},
            },
            treatment_specs=[
                AlgorithmSpecification(
                    algorithm_id="anchored-article-topk@9.0.0",
                    hotvect_version="9.35.0",
                    git_describe="7.3.11-4-gc5709d7",
                    git_commit="c5709d7",
                    algorithm_parameters={"enable_rerank": True},
                )
            ],
        )

        joined = "\n".join(lines)
        self.assertIn("Report Generation", joined)
        self.assertIn("Generated At:", joined)
        self.assertIn("hotvect 10.13.7", joined)
        self.assertIn("Evaluation Specification", joined)
        self.assertIn("Function:", joined)
        self.assertIn("standard_evaluation", joined)
        self.assertIn("Arguments:", joined)
        self.assertIn("missing_reward_policy=zero", joined)
        self.assertIn("Policy:", joined)
        self.assertIn("Test Decoder:", joined)
        self.assertIn("where_shared_equals.slot=example-slot", joined)
        self.assertIn("Benchmark Specification", joined)
        self.assertIn("Performance Test:", joined)
        self.assertIn("enabled=true", joined)
        self.assertIn("samples=200000", joined)
        self.assertIn("Performance Data:", joined)
        self.assertIn("data_prefix=perf-input", joined)
        self.assertIn("Requested:", joined)
        self.assertIn("sample_pool_size=25000", joined)
        self.assertIn("Input:", joined)
        self.assertIn("kind=performance_data_spec", joined)
        self.assertIn("Contract:", joined)
        self.assertIn("instance_type=ml.c7i.4xlarge", joined)
        self.assertNotIn("Baseline Artifact", joined)
        self.assertIn("Hotvect Version:", joined)
        self.assertIn("Git Describe:", joined)
        self.assertIn("Git Commit:", joined)
        self.assertIn("Treatment Specification", joined)
        self.assertIn("anchored-article-topk@9.0.0", joined)
        self.assertIn("Algorithm Params:", joined)
        self.assertIn("enable_rerank=true", joined)
        self.assertIn("c5709d7", joined)

    def test_metrics_plot_paginates_large_specification(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            versions = [f"variant-{index:02d}" for index in range(12)]
            result_files = []
            for index, version in enumerate(versions):
                result_file = temp_path / f"{version}.result.json"
                result_file.write_text(
                    json.dumps(
                        {
                            "algorithm_id": f"algo@{version}",
                            "test_data_time": "2026-01-01",
                            "algorithm_definition": {
                                "algorithm_parameters": {
                                    "instance_type": "ml.r7i.4xlarge",
                                    "max_threads": index + 1,
                                    "target_rps": 10,
                                },
                            },
                            "evaluate": {"ndcg_at_10": 0.1 + index / 1000},
                        }
                    )
                )
                result_files.append(str(result_file))

            out = temp_path / "metrics.pdf"
            with (
                patch(
                    "sys.argv",
                    [
                        "hv-ext",
                        "metrics",
                        "plot",
                        "--out",
                        str(out),
                        "--versions",
                        *versions,
                        "--relative-baseline",
                        versions[0],
                        "--metrics",
                        "ndcg_at_10",
                        "--result-files",
                        *result_files,
                    ],
                ),
            ):
                from hotvect.extra.cli import main

                main()

            page_count = len(re.findall(rb"/Type\s*/Page\b", out.read_bytes()))
            self.assertGreaterEqual(page_count, 7)

    @staticmethod
    def _fake_pointplot(*, x, y, data, ax, **_kwargs):
        grouped = data.groupby(x, observed=False)[y].mean()
        ax.plot(range(len(grouped)), grouped.to_list(), marker="o")
        ax.set_xticks(range(len(grouped)))
        ax.set_xticklabels([str(value) for value in grouped.index])
        return ax

    @staticmethod
    def _fake_lineplot(*, x, y, hue, data, ax, **_kwargs):
        for value, frame in data.groupby(hue, observed=False):
            ax.plot(frame[x], frame[y], marker="o", label=str(value))
        ax.legend()
        return ax

    def test_common_evaluation_specification_rejects_mixed_specs(self):
        records = [
            {
                "algorithm_id": "algo@1",
                "algorithm_definition": {
                    "hotvect_execution_parameters": {
                        "evaluation_function": {
                            "name": "standard_evaluation",
                            "arguments": {"missing_reward_policy": "zero"},
                        }
                    },
                    "test_decoder_parameters": {"where_shared_equals": {"slot": "example-slot"}},
                },
                "evaluation_policy": {"missing_reward_policy": "zero"},
            },
            {
                "algorithm_id": "algo@2",
                "algorithm_definition": {
                    "hotvect_execution_parameters": {
                        "evaluation_function": {
                            "name": "standard_evaluation",
                            "arguments": {"missing_reward_policy": "error"},
                        }
                    },
                    "test_decoder_parameters": {"where_shared_equals": {"slot": "example-slot"}},
                },
                "evaluation_policy": {"missing_reward_policy": "error"},
            },
        ]

        with self.assertRaisesRegex(ValueError, "evaluation specification differs"):
            _common_evaluation_specification(records)

    def test_common_benchmark_specification_rejects_mixed_specs(self):
        records = [
            {
                "algorithm_id": "algo@1",
                "algorithm_definition": {
                    "hotvect_execution_parameters": {
                        "performance-test": {"enabled": True, "samples": 100000},
                    },
                },
            },
            {
                "algorithm_id": "algo@2",
                "algorithm_definition": {
                    "hotvect_execution_parameters": {
                        "performance-test": {"enabled": True, "samples": 200000},
                    },
                },
            },
        ]

        with self.assertRaisesRegex(ValueError, "benchmark specification differs"):
            _common_benchmark_specification(records)

    def test_common_benchmark_specification_result_reports_mixed_specs(self):
        records = [
            {
                "algorithm_id": "algo@1",
                "algorithm_definition": {
                    "hotvect_execution_parameters": {
                        "performance-test": {"enabled": True, "samples": 100000},
                    },
                },
            },
            {
                "algorithm_id": "algo@2",
                "algorithm_definition": {
                    "hotvect_execution_parameters": {
                        "performance-test": {"enabled": True, "samples": 200000},
                    },
                },
            },
        ]

        result = _common_benchmark_specification_result(records)

        self.assertEqual(result.specification, {})
        self.assertIsNotNone(result.mismatch_summary)
        assert result.mismatch_summary is not None
        self.assertIn("100000", result.mismatch_summary)
        self.assertIn("200000", result.mismatch_summary)

    def test_common_benchmark_specification_result_rejects_empty_records(self):
        with self.assertRaisesRegex(ValueError, "No records available for benchmark specification comparison"):
            _common_benchmark_specification_result([])

    @patch("sys.stderr", new_callable=StringIO)
    def test_metrics_plot_warns_and_filters_system_metrics_when_benchmark_specs_differ(self, mock_stderr):
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            result_files = []
            for version, samples in (("81.0.8", 100000), ("82.2.34", 200000)):
                result_file = temp_path / f"{version}.result.json"
                result_file.write_text(
                    json.dumps(
                        {
                            "algorithm_id": f"algo@{version}",
                            "test_data_time": "2026-01-01",
                            "algorithm_definition": {
                                "hotvect_execution_parameters": {
                                    "performance-test": {"enabled": True, "samples": samples},
                                },
                            },
                            "evaluate": {"ndcg_at_10": 0.1},
                            "performance_test": {
                                "max_memory_usage": 1000.0,
                                "mean_throughput": 100.0,
                                "response_time_metrics": {
                                    "mean_throughput": {"mean": 100.0},
                                    "mean": {"mean": 10.0},
                                    "p50": {"mean": 8.0},
                                    "p75": {"mean": 12.0},
                                    "p95": {"mean": 20.0},
                                    "p99": {"mean": 30.0},
                                    "p999": {"mean": 50.0},
                                },
                            },
                            "timing_info_sec": {"total_time": 10.0 + samples / 100000.0, "predict": 8.0},
                        }
                    )
                )
                result_files.append(str(result_file))

            out = temp_path / "metrics.pdf"
            table_out = temp_path / "metrics.json"
            with patch(
                "sys.argv",
                [
                    "hv-ext",
                    "metrics",
                    "plot",
                    "--out",
                    str(out),
                    "--table-out",
                    str(table_out),
                    "--versions",
                    "81.0.8",
                    "82.2.34",
                    "--relative-baseline",
                    "81.0.8",
                    "--metrics",
                    "ndcg_at_10",
                    "p95",
                    "--result-files",
                    *result_files,
                ],
            ):
                from hotvect.extra.cli import main

                main()

            self.assertTrue(out.exists())
            self.assertIn("Warning: Performance-test latency/throughput comparisons", mock_stderr.getvalue())
            rows = json.loads(table_out.read_text())
            self.assertTrue(rows)
            for row in rows:
                self.assertIn("ndcg_at_10", row)
                self.assertNotIn("p95", row)
            page_count = len(re.findall(rb"/Type\s*/Page\b", out.read_bytes()))
            self.assertGreater(page_count, 8)

    def test_build_provenance_lines_includes_source_metadata(self):
        self.assertEqual(
            _source_sagemaker_job_from_path("s3://bucket/base/ml-job-2026-01-05/algo@1.0.0/result.json"),
            "ml-job-2026-01-05",
        )
        self.assertIsNone(
            _source_sagemaker_job_from_path("/tmp/results/meta/algo@1.0.0/last_test_date_2026-01-05/result.json")
        )

        lines = _build_provenance_lines(
            [
                {
                    "algorithm_id": "algo@1.0.0",
                    "test_date": "2026-01-05",
                    "source_result_file": "/tmp/downloaded/ml-job-2026-01-05/algo@1.0.0/result.json",
                    "source_result_uri": "s3://bucket/base/ml-job-2026-01-05/algo@1.0.0/result.json",
                    "source_sagemaker_job_name": "ml-job-2026-01-05",
                    "parameter_version": "last_test_date_2026-01-05",
                    "source_parameter_uri": (
                        "s3://bucket/params/last_test_date_2026-01-05/"
                        "algo-2026-01-05-abc123de-catalog-fed987ab.parameters.zip"
                    ),
                },
                {
                    "algorithm_id": "algo@1.0.1",
                    "test_date": "2026-01-06",
                    "source_result_file": "/tmp/downloaded/ml-job-2026-01-06/algo@1.0.1/result.json",
                    "source_result_uri": "s3://bucket/base/ml-job-2026-01-06/algo@1.0.1/result.json",
                    "source_sagemaker_job_name": "ml-job-2026-01-06",
                    "parameter_version": "last_test_date_2026-01-06",
                    "source_parameter_uri": (
                        "s3://bucket/params/last_test_date_2026-01-06/"
                        "algo-2026-01-06-fed987ab-catalog-abc123de.parameters.zip"
                    ),
                },
            ]
        )

        joined = "\n".join(lines)
        self.assertIn("Source Summary", joined)
        self.assertIn("Runs:", joined)
        self.assertIn("2", joined)
        self.assertIn("Result Root:", joined)
        self.assertIn("s3://bucket/base", joined)
        self.assertNotIn("/tmp/downloaded", joined)
        self.assertIn("Result Pattern:", joined)
        self.assertIn("<sagemaker_job>/<algorithm_id>/result.json", joined)
        self.assertIn("Parameter Root:", joined)
        self.assertIn("s3://bucket/params", joined)
        self.assertIn("Param Pattern:", joined)
        self.assertIn("<parameter_version>/algo-<test_date>-<hash>-catalog-<hash>.parameters.zip", joined)
        self.assertIn("SageMaker Jobs:", joined)
        self.assertIn("ml-job-2026-01-05", joined)
        self.assertIn("algo@1.0.0", joined)

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

    @patch("sys.stdout", new_callable=StringIO)
    def test_compare_system_single_day_one_shot_result_format(self, mock_stdout):
        control = {
            "task": "performance-test",
            "task_metadata": {
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
            "task": "performance-test",
            "task_metadata": {
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
            self.assertAlmostEqual(payload["metrics"]["p99"]["absolute_change"], -5.0, places=10)
        finally:
            os.unlink(control_path)
            os.unlink(treatment_path)


if __name__ == "__main__":
    unittest.main()
