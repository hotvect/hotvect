"""Tests for list-available-results command."""

import json
import tempfile
import unittest
from datetime import date, timedelta
from pathlib import Path
from unittest.mock import MagicMock, patch

from hotvect.extra.commands.inventory_evaluations import ListAvailableResultsCommand


def _write_result_json(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2))


class TestListAvailableResultsCommand(unittest.TestCase):
    def setUp(self) -> None:
        self.command = ListAvailableResultsCommand()

    def test_lists_runs_and_content_statuses(self):
        algo_a = "algo@1.0.0"
        algo_b = "algo@2.0.0"
        day1 = date(2026, 1, 10).isoformat()
        day2 = (date(2026, 1, 10) + timedelta(days=1)).isoformat()

        with tempfile.TemporaryDirectory() as td:
            meta_dir = Path(td)

            # algo_a/day1: evaluate present, performance_test skipped
            _write_result_json(
                meta_dir / algo_a / f"last_test_date_{day1}" / "result.json",
                {
                    "algorithm_id": algo_a,
                    "test_data_time": day1,
                    "parameter_version": "last_test_date_" + day1,
                    "algorithm_definition": {"hyperparameter_version": None},
                    "evaluate": {"roc_auc": {"mean": 0.8}},
                    "performance_test": {"skipped": "disabled"},
                },
            )

            # algo_a/day2: invalid json
            bad_path = meta_dir / algo_a / f"last_test_date_{day2}" / "result.json"
            bad_path.parent.mkdir(parents=True, exist_ok=True)
            bad_path.write_text("{not json")

            # algo_b/day1: result.json missing
            (meta_dir / algo_b / f"last_test_date_{day1}").mkdir(parents=True, exist_ok=True)

            args = MagicMock()
            args.meta_dir = str(meta_dir)
            args.from_date = None
            args.to_date = None
            args.algorithm_name = ""
            args.algorithm_name_regex = ""
            args.algorithm_version_regex = ""
            args.output = None

            with patch("builtins.print") as mock_print:
                self.command.execute(args)

            out = str(mock_print.call_args_list[0][0][0])
            result = json.loads(out)

            self.assertEqual(result["meta_dir"], str(meta_dir))
            self.assertCountEqual(result["algorithms"], [algo_a, algo_b])
            self.assertCountEqual(result["dates"], [day1, day2])
            runs = {(r["algorithm_id"], r["test_date"]): r for r in result["runs"]}
            a1 = runs[(algo_a, day1)]
            self.assertEqual(a1["contents"]["quality"]["status"], "present")
            self.assertEqual(a1["contents"]["system_performance"]["status"], "skipped")
            self.assertEqual(a1["errors"], [])
            self.assertEqual(a1["hyperparameter_version"], None)
            self.assertEqual(a1["parameter_version"], "last_test_date_" + day1)
            self.assertEqual(a1["test_data_time"], day1)

            self.assertEqual(runs[(algo_a, day2)]["contents"]["quality"]["status"], "invalid")
            self.assertEqual(runs[(algo_b, day1)]["contents"]["quality"]["status"], "missing")

    def test_meta_dir_defaults_from_config(self):
        algo = "algo@1.0.0"
        day1 = date(2026, 1, 10).isoformat()

        with tempfile.TemporaryDirectory() as td:
            base = Path(td)
            output_base = base / "output"
            meta_dir = output_base / "meta"
            _write_result_json(
                meta_dir / algo / f"last_test_date_{day1}" / "result.json",
                {
                    "algorithm_id": algo,
                    "test_data_time": day1,
                    "parameter_version": "last_test_date_" + day1,
                    "algorithm_definition": {"hyperparameter_version": None},
                    "evaluate": {"roc_auc": {"mean": 0.8}},
                },
            )

            cfg_path = base / "config.json"
            cfg_path.write_text(
                json.dumps(
                    {
                        "directories": {
                            "output_base_dir": str(output_base),
                            "data_base_dir": str(base / "data"),
                            "scratch_dir": str(base / "scratch"),
                        }
                    }
                )
            )

            args = MagicMock()
            args.meta_dir = ""
            args.from_date = None
            args.to_date = None
            args.algorithm_name = ""
            args.algorithm_name_regex = ""
            args.algorithm_version_regex = ""
            args.output = None

            from hotvect.extra import config as hv_config

            with patch.object(hv_config, "DEFAULT_CONFIG_PATH", cfg_path):
                with patch("builtins.print") as mock_print:
                    self.command.execute(args)

            out = str(mock_print.call_args_list[0][0][0])
            result = json.loads(out)
            self.assertEqual(result["meta_dir"], str(meta_dir))
            self.assertEqual(result["runs"][0]["algorithm_id"], algo)

    def test_optional_date_filter(self):
        algo = "algo@1.0.0"
        day1 = date(2026, 1, 10).isoformat()
        day2 = (date(2026, 1, 10) + timedelta(days=1)).isoformat()

        with tempfile.TemporaryDirectory() as td:
            meta_dir = Path(td)
            _write_result_json(
                meta_dir / algo / f"last_test_date_{day1}" / "result.json",
                {
                    "algorithm_id": algo,
                    "test_data_time": day1,
                    "parameter_version": "last_test_date_" + day1,
                    "algorithm_definition": {"hyperparameter_version": None},
                    "evaluate": {"roc_auc": {"mean": 0.8}},
                },
            )
            _write_result_json(
                meta_dir / algo / f"last_test_date_{day2}" / "result.json",
                {
                    "algorithm_id": algo,
                    "test_data_time": day2,
                    "parameter_version": "last_test_date_" + day2,
                    "algorithm_definition": {"hyperparameter_version": None},
                    "evaluate": {"roc_auc": {"mean": 0.81}},
                },
            )

            # from/to as a 1-day range
            args = MagicMock()
            args.meta_dir = str(meta_dir)
            args.from_date = day1
            args.to_date = day1
            args.algorithm_name = ""
            args.algorithm_name_regex = ""
            args.algorithm_version_regex = ""
            args.output = None

            with patch("builtins.print") as mock_print:
                self.command.execute(args)

            out = str(mock_print.call_args_list[0][0][0])
            result = json.loads(out)
            self.assertEqual(result["dates"], [day1])
            self.assertIn((algo, day1), {(r["algorithm_id"], r["test_date"]) for r in result["runs"]})
            self.assertNotIn((algo, day2), {(r["algorithm_id"], r["test_date"]) for r in result["runs"]})

            # from-date only: include on/after day2 (so only day2)
            args = MagicMock()
            args.meta_dir = str(meta_dir)
            args.from_date = day2
            args.to_date = None
            args.algorithm_name = ""
            args.algorithm_name_regex = ""
            args.algorithm_version_regex = ""
            args.output = None

            with patch("builtins.print") as mock_print:
                self.command.execute(args)

            out = str(mock_print.call_args_list[0][0][0])
            result = json.loads(out)
            self.assertEqual(result["dates"], [day2])
            self.assertIn((algo, day2), {(r["algorithm_id"], r["test_date"]) for r in result["runs"]})
            self.assertNotIn((algo, day1), {(r["algorithm_id"], r["test_date"]) for r in result["runs"]})

            # to-date only: include on/before day1 (so only day1)
            args = MagicMock()
            args.meta_dir = str(meta_dir)
            args.from_date = None
            args.to_date = day1
            args.algorithm_name = ""
            args.algorithm_name_regex = ""
            args.algorithm_version_regex = ""
            args.output = None

            with patch("builtins.print") as mock_print:
                self.command.execute(args)

            out = str(mock_print.call_args_list[0][0][0])
            result = json.loads(out)
            self.assertEqual(result["dates"], [day1])
            self.assertIn((algo, day1), {(r["algorithm_id"], r["test_date"]) for r in result["runs"]})
            self.assertNotIn((algo, day2), {(r["algorithm_id"], r["test_date"]) for r in result["runs"]})

    def test_optional_algorithm_name_filter(self):
        algo_a = "algo@1.0.0"
        other = "other@1.0.0"
        day1 = date(2026, 1, 10).isoformat()

        with tempfile.TemporaryDirectory() as td:
            meta_dir = Path(td)
            _write_result_json(
                meta_dir / algo_a / f"last_test_date_{day1}" / "result.json",
                {
                    "algorithm_id": algo_a,
                    "test_data_time": day1,
                    "parameter_version": "last_test_date_" + day1,
                    "algorithm_definition": {"hyperparameter_version": None},
                    "evaluate": {"roc_auc": {"mean": 0.8}},
                },
            )
            _write_result_json(
                meta_dir / other / f"last_test_date_{day1}" / "result.json",
                {
                    "algorithm_id": other,
                    "test_data_time": day1,
                    "parameter_version": "last_test_date_" + day1,
                    "algorithm_definition": {"hyperparameter_version": None},
                    "evaluate": {"roc_auc": {"mean": 0.7}},
                },
            )

            args = MagicMock()
            args.meta_dir = str(meta_dir)
            args.from_date = None
            args.to_date = None
            args.algorithm_name = "algo"
            args.algorithm_name_regex = ""
            args.algorithm_version_regex = ""
            args.output = None

            with patch("builtins.print") as mock_print:
                self.command.execute(args)

            out = str(mock_print.call_args_list[0][0][0])
            result = json.loads(out)
            self.assertEqual(result["algorithms"], [algo_a])

    def test_optional_algorithm_regex_filters(self):
        algo_a = "algo@1.0.0"
        algo_b = "algo@2.0.0"
        other = "other@1.0.0"
        day1 = date(2026, 1, 10).isoformat()

        with tempfile.TemporaryDirectory() as td:
            meta_dir = Path(td)
            for algo_id in (algo_a, algo_b, other):
                _write_result_json(
                    meta_dir / algo_id / f"last_test_date_{day1}" / "result.json",
                    {
                        "algorithm_id": algo_id,
                        "test_data_time": day1,
                        "parameter_version": "last_test_date_" + day1,
                        "algorithm_definition": {"hyperparameter_version": None},
                        "evaluate": {"roc_auc": {"mean": 0.8}},
                    },
                )

            args = MagicMock()
            args.meta_dir = str(meta_dir)
            args.from_date = None
            args.to_date = None
            args.algorithm_name = ""
            args.algorithm_name_regex = r"algo"
            args.algorithm_version_regex = r"2\..*"
            args.output = None

            with patch("builtins.print") as mock_print:
                self.command.execute(args)

            out = str(mock_print.call_args_list[0][0][0])
            result = json.loads(out)
            self.assertEqual(result["algorithms"], [algo_b])


if __name__ == "__main__":
    unittest.main()
