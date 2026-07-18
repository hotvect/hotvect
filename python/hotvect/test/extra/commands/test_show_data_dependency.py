"""Tests for show-data-dependency command."""

import argparse
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest.mock import patch

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

    @patch("hotvect.extra.commands.show_data_dependency.AlgorithmPipeline")
    @patch("hotvect.extra.commands.show_data_dependency.clone_and_build_algorithm_jar")
    def test_execute_threads_target_to_pipeline(self, mock_clone, mock_pipeline_cls):
        mock_clone.return_value = SimpleNamespace(
            algorithm_name="algo",
            algorithm_version="1.2.3",
            algorithm_jar_path=Path("/tmp/algo.jar"),
        )
        mock_pipeline = mock_pipeline_cls.return_value
        mock_pipeline.data_dependencies.return_value = []

        with TemporaryDirectory() as scratch_dir:
            args = self._parse_args("--target", "predict")
            args.scratch_dir = scratch_dir

            with patch("builtins.print"):
                self.command.execute(args)

        mock_pipeline.data_dependencies.assert_called_once_with(target="predict")
        self.assertIs(mock_clone.call_args.kwargs["progress_stream"], sys.stderr)


if __name__ == "__main__":
    unittest.main()
