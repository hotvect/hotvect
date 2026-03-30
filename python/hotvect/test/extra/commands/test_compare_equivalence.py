"""Tests for compare-equivalence command."""

import argparse
import json
import os
import tempfile
import unittest
from unittest.mock import MagicMock, patch

from hotvect.extra.commands.compare_equivalence import (
    CompareEquivalenceCommand,
    EquivalenceInputError,
    compare_predict_jsonl,
)


class TestCompareEquivalenceCommand(unittest.TestCase):
    def setUp(self):
        self.command = CompareEquivalenceCommand()

    def _write_jsonl(self, rows):
        tmp = tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl", delete=False)
        for row in rows:
            tmp.write(json.dumps(row) + "\n")
        tmp.close()
        return tmp.name

    def test_register_parser(self):
        parser = argparse.ArgumentParser()
        subparsers = parser.add_subparsers(dest="command")
        CompareEquivalenceCommand.register_parser(subparsers)

        args = parser.parse_args(["compare-equivalence", "a.jsonl", "b.jsonl"])
        self.assertEqual(args.command, "compare-equivalence")
        self.assertEqual(args.baseline_file, "a.jsonl")
        self.assertEqual(args.treatment_file, "b.jsonl")
        self.assertAlmostEqual(args.score_eps, 1e-6)
        self.assertFalse(args.allow_non_deterministic_tie_breaking)
        self.assertIsNone(args.output)

    def test_compare_exact_parity_passes(self):
        baseline = [
            {
                "example_id": "e-1",
                "result": [
                    {"score": 0.9, "additional_properties": {"action_id": "a"}},
                    {"score": 0.8, "action_id": "b"},
                ],
            }
        ]
        treatment = [
            {
                "example_id": "e-1",
                "result": [
                    {"score": 0.9, "additional_properties": {"action_id": "a"}},
                    {"score": 0.8, "action_id": "b"},
                ],
            }
        ]

        baseline_path = self._write_jsonl(baseline)
        treatment_path = self._write_jsonl(treatment)
        try:
            result = compare_predict_jsonl(
                baseline_path, treatment_path, score_eps=1e-6, allow_non_deterministic_tie_breaking=False
            )
            self.assertEqual(result["status"], "passed")
            self.assertTrue(result["score"]["passed"])
            self.assertTrue(result["rank"]["passed"])
            self.assertEqual(result["processed_lines"], 1)
            self.assertEqual(result["mismatches"], [])
        finally:
            os.unlink(baseline_path)
            os.unlink(treatment_path)

    def test_compare_score_eps_boundary_passes(self):
        baseline = [{"example_id": "e-1", "result": [{"score": 1.0, "action_id": "a"}]}]
        treatment = [{"example_id": "e-1", "result": [{"score": 1.000001, "action_id": "a"}]}]

        baseline_path = self._write_jsonl(baseline)
        treatment_path = self._write_jsonl(treatment)
        try:
            result = compare_predict_jsonl(
                baseline_path, treatment_path, score_eps=1e-6, allow_non_deterministic_tie_breaking=False
            )
            self.assertEqual(result["status"], "passed")
            self.assertTrue(result["score"]["passed"])
            self.assertEqual(result["score"]["mismatch_count"], 0)
        finally:
            os.unlink(baseline_path)
            os.unlink(treatment_path)

    def test_compare_strict_rank_mismatch_fails(self):
        baseline = [
            {"example_id": "e-1", "result": [{"score": 2.0, "action_id": "a"}, {"score": 1.0, "action_id": "b"}]}
        ]
        treatment = [
            {"example_id": "e-1", "result": [{"score": 1.0, "action_id": "b"}, {"score": 2.0, "action_id": "a"}]}
        ]

        baseline_path = self._write_jsonl(baseline)
        treatment_path = self._write_jsonl(treatment)
        try:
            result = compare_predict_jsonl(
                baseline_path, treatment_path, score_eps=1e-6, allow_non_deterministic_tie_breaking=False
            )
            self.assertEqual(result["status"], "failed")
            self.assertTrue(result["score"]["passed"])
            self.assertFalse(result["rank"]["passed"])
            self.assertEqual(result["rank"]["mismatch_count"], 1)
            self.assertEqual(result["mismatches"][0]["type"], "rank_order_mismatch")
        finally:
            os.unlink(baseline_path)
            os.unlink(treatment_path)

    def test_compare_tie_breaking_allows_permutation(self):
        baseline = [
            {
                "example_id": "e-1",
                "result": [
                    {"score": 1.0, "action_id": "a"},
                    {"score": 1.0, "action_id": "b"},
                    {"score": 0.5, "action_id": "c"},
                ],
            }
        ]
        treatment = [
            {
                "example_id": "e-1",
                "result": [
                    {"score": 1.0, "action_id": "b"},
                    {"score": 1.0, "action_id": "a"},
                    {"score": 0.5, "action_id": "c"},
                ],
            }
        ]

        baseline_path = self._write_jsonl(baseline)
        treatment_path = self._write_jsonl(treatment)
        try:
            result = compare_predict_jsonl(
                baseline_path, treatment_path, score_eps=1e-6, allow_non_deterministic_tie_breaking=True
            )
            self.assertEqual(result["status"], "passed")
            self.assertTrue(result["rank"]["passed"])
        finally:
            os.unlink(baseline_path)
            os.unlink(treatment_path)

    def test_compare_tie_breaking_rejects_cross_group_swap(self):
        baseline = [
            {
                "example_id": "e-1",
                "result": [
                    {"score": 1.0, "action_id": "a"},
                    {"score": 1.0, "action_id": "b"},
                    {"score": 0.5, "action_id": "c"},
                ],
            }
        ]
        treatment = [
            {
                "example_id": "e-1",
                "result": [
                    {"score": 0.5, "action_id": "c"},
                    {"score": 1.0, "action_id": "a"},
                    {"score": 1.0, "action_id": "b"},
                ],
            }
        ]

        baseline_path = self._write_jsonl(baseline)
        treatment_path = self._write_jsonl(treatment)
        try:
            result = compare_predict_jsonl(
                baseline_path, treatment_path, score_eps=1e-6, allow_non_deterministic_tie_breaking=True
            )
            self.assertEqual(result["status"], "failed")
            self.assertFalse(result["rank"]["passed"])
            self.assertEqual(result["rank"]["mismatch_count"], 1)
        finally:
            os.unlink(baseline_path)
            os.unlink(treatment_path)

    def test_compare_line_count_mismatch(self):
        baseline = [{"example_id": "e-1", "result": [{"score": 1.0, "action_id": "a"}]}]
        treatment = []

        baseline_path = self._write_jsonl(baseline)
        treatment_path = self._write_jsonl(treatment)
        try:
            result = compare_predict_jsonl(
                baseline_path, treatment_path, score_eps=1e-6, allow_non_deterministic_tie_breaking=False
            )
            self.assertEqual(result["status"], "failed")
            self.assertTrue(result["line_count_mismatch"])
            self.assertEqual(result["processed_lines"], 0)
            self.assertEqual(result["mismatches"][0]["type"], "line_count_mismatch")
        finally:
            os.unlink(baseline_path)
            os.unlink(treatment_path)

    def test_compare_example_id_mismatch(self):
        baseline = [{"example_id": "e-1", "result": [{"score": 1.0, "action_id": "a"}]}]
        treatment = [{"example_id": "e-2", "result": [{"score": 1.0, "action_id": "a"}]}]

        baseline_path = self._write_jsonl(baseline)
        treatment_path = self._write_jsonl(treatment)
        try:
            result = compare_predict_jsonl(
                baseline_path, treatment_path, score_eps=1e-6, allow_non_deterministic_tie_breaking=False
            )
            self.assertEqual(result["status"], "failed")
            self.assertEqual(result["score"]["mismatch_count"], 0)
            self.assertEqual(result["rank"]["mismatch_count"], 0)
            self.assertEqual(result["mismatches"][0]["type"], "example_id_mismatch")
        finally:
            os.unlink(baseline_path)
            os.unlink(treatment_path)

    def test_compare_missing_example_id_raises(self):
        baseline = [{"result": [{"score": 1.0, "action_id": "a"}]}]
        treatment = [{"example_id": "e-1", "result": [{"score": 1.0, "action_id": "a"}]}]

        baseline_path = self._write_jsonl(baseline)
        treatment_path = self._write_jsonl(treatment)
        try:
            with self.assertRaises(EquivalenceInputError):
                compare_predict_jsonl(
                    baseline_path,
                    treatment_path,
                    score_eps=1e-6,
                    allow_non_deterministic_tie_breaking=False,
                )
        finally:
            os.unlink(baseline_path)
            os.unlink(treatment_path)

    @patch("builtins.print")
    @patch("sys.exit")
    def test_execute_failure_exits_with_one(self, mock_exit, mock_print):
        baseline = [
            {"example_id": "e-1", "result": [{"score": 2.0, "action_id": "a"}, {"score": 1.0, "action_id": "b"}]}
        ]
        treatment = [
            {"example_id": "e-1", "result": [{"score": 1.0, "action_id": "b"}, {"score": 2.0, "action_id": "a"}]}
        ]
        baseline_path = self._write_jsonl(baseline)
        treatment_path = self._write_jsonl(treatment)
        mock_exit.side_effect = SystemExit(1)

        try:
            args = MagicMock()
            args.baseline_file = baseline_path
            args.treatment_file = treatment_path
            args.score_eps = 1e-6
            args.allow_non_deterministic_tie_breaking = False
            args.output = None

            with self.assertRaises(SystemExit):
                self.command.execute(args)

            result = json.loads(mock_print.call_args[0][0])
            self.assertEqual(result["status"], "failed")
            mock_exit.assert_called_once_with(1)
        finally:
            os.unlink(baseline_path)
            os.unlink(treatment_path)

    @patch("builtins.print")
    @patch("sys.exit")
    def test_execute_invalid_input_exits_with_two(self, mock_exit, mock_print):
        mock_exit.side_effect = SystemExit(2)
        args = MagicMock()
        args.baseline_file = "missing-baseline.jsonl"
        args.treatment_file = "missing-treatment.jsonl"
        args.score_eps = 1e-6
        args.allow_non_deterministic_tie_breaking = False
        args.output = None

        with self.assertRaises(SystemExit):
            self.command.execute(args)

        result = json.loads(mock_print.call_args[0][0])
        self.assertEqual(result["status"], "error")
        self.assertEqual(result["error_code"], "invalid_input")
        mock_exit.assert_called_once_with(2)

    @patch("builtins.print")
    @patch("sys.exit")
    def test_execute_success_no_exit(self, mock_exit, mock_print):
        baseline = [{"example_id": "e-1", "result": [{"score": 1.0, "action_id": "a"}]}]
        treatment = [{"example_id": "e-1", "result": [{"score": 1.0, "action_id": "a"}]}]
        baseline_path = self._write_jsonl(baseline)
        treatment_path = self._write_jsonl(treatment)

        try:
            args = MagicMock()
            args.baseline_file = baseline_path
            args.treatment_file = treatment_path
            args.score_eps = 1e-6
            args.allow_non_deterministic_tie_breaking = False
            args.output = None

            self.command.execute(args)

            result = json.loads(mock_print.call_args[0][0])
            self.assertEqual(result["status"], "passed")
            mock_exit.assert_not_called()
        finally:
            os.unlink(baseline_path)
            os.unlink(treatment_path)


if __name__ == "__main__":
    unittest.main()
