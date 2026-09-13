"""Tests for show-data-dependency command."""

import argparse
import unittest
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest.mock import patch

from hotvect.extra.commands.download_data_dependency import DataDependencyCommand
from hotvect.extra.commands.show_data_dependency import ShowDataDependencyCommand


class TestShowDataDependencyCommand(unittest.TestCase):
    def setUp(self):
        self.command = ShowDataDependencyCommand()

    def _parse_args(self, *extra_args):
        parser = argparse.ArgumentParser()
        subparsers = parser.add_subparsers(dest="command")
        ShowDataDependencyCommand.register_parser(subparsers)
        return parser.parse_args(
            [
                "show-data-dependency",
                "--repo-url",
                "https://github.com/company/example-algorithm.git",
                "--git-reference",
                "v77.0.0",
                "--scratch-dir",
                "./scratch",
                "--last-test-time",
                "2026-01-03",
                *extra_args,
            ]
        )

    def test_register_parser_defaults_target_to_evaluate(self):
        args = self._parse_args()

        self.assertEqual(args.target, "evaluate")

    def test_register_parser_accepts_predict_target(self):
        args = self._parse_args("--target", "predict")

        self.assertEqual(args.target, "predict")

    def test_execute_rejects_ambiguous_override_count(self):
        args = self._parse_args(
            "--git-reference",
            "v78.0.0",
            "--algorithm-override",
            "override.json",
        )

        with self.assertRaisesRegex(ValueError, "once for each --git-reference"):
            self.command.execute(args)

    def test_execute_threads_target_to_shared_dependency_discovery(self):
        with TemporaryDirectory() as scratch_dir:
            args = self._parse_args("--target", "predict")
            args.scratch_dir = scratch_dir

            with (
                patch.object(
                    DataDependencyCommand, "_get_data_dependencies", return_value=("algo", "1.2.3", [])
                ) as get,
                patch("builtins.print"),
            ):
                self.command.execute(args)

        self.assertEqual(get.call_args.args[4], "predict")

    @patch("hotvect.extra.commands.show_data_dependency.resolve_data_dependency_s3_uri")
    def test_execute_fails_when_a_production_s3_uri_cannot_be_resolved(self, mock_resolve_s3_uri):
        dependencies = [
            SimpleNamespace(
                data_prefix="training_data",
                data_dates=[],
                data_type="input",
                additional_properties={},
            )
        ]
        mock_resolve_s3_uri.side_effect = ValueError("production URI is missing")

        with TemporaryDirectory() as scratch_dir, patch.object(
            DataDependencyCommand,
            "_get_data_dependencies",
            return_value=("algo", "1.2.3", dependencies),
        ):
            args = self._parse_args()
            args.scratch_dir = scratch_dir

            with self.assertRaisesRegex(ValueError, "production URI is missing"):
                self.command.execute(args)


if __name__ == "__main__":
    unittest.main()
