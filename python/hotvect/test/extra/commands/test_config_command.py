"""Tests for hv-ext config command."""

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from hotvect.extra import config as hv_config
from hotvect.extra.commands.config_cmd import ConfigCommand


class TestConfigCommand(unittest.TestCase):
    def setUp(self) -> None:
        self.command = ConfigCommand()

    def test_show_missing_raises(self):
        args = MagicMock()
        args.subcommand = "show"

        with patch.object(hv_config, "DEFAULT_CONFIG_PATH", Path("/path/does/not/exist.json")):
            with self.assertRaises(FileNotFoundError):
                self.command.execute(args)

    def test_init_writes_config(self):
        with tempfile.TemporaryDirectory() as td:
            cfg_path = Path(td) / "config.json"
            args = MagicMock()
            args.subcommand = "init"
            args.data_base_dir = str(Path(td) / "data")
            args.output_base_dir = str(Path(td) / "output")
            args.scratch_dir = str(Path(td) / "scratch")

            with patch.object(hv_config, "DEFAULT_CONFIG_PATH", cfg_path):
                with patch("builtins.print") as mock_print:
                    self.command.execute(args)

            out = json.loads(str(mock_print.call_args_list[0][0][0]))
            self.assertEqual(out["ok"], True)
            self.assertTrue(cfg_path.exists())

    def test_init_partial_args_raises(self):
        with tempfile.TemporaryDirectory() as td:
            cfg_path = Path(td) / "config.json"
            args = MagicMock()
            args.subcommand = "init"
            args.data_base_dir = str(Path(td) / "data")
            args.output_base_dir = ""
            args.scratch_dir = ""

            with patch.object(hv_config, "DEFAULT_CONFIG_PATH", cfg_path):
                with self.assertRaises(ValueError):
                    self.command.execute(args)

    def test_init_interactive_prompts(self):
        with tempfile.TemporaryDirectory() as td:
            cfg_path = Path(td) / "config.json"
            args = MagicMock()
            args.subcommand = "init"
            args.data_base_dir = ""
            args.output_base_dir = ""
            args.scratch_dir = ""

            with patch.object(hv_config, "DEFAULT_CONFIG_PATH", cfg_path):
                with patch(
                    "builtins.input",
                    side_effect=[str(Path(td) / "data"), str(Path(td) / "output"), str(Path(td) / "scratch")],
                ):
                    with patch("builtins.print") as mock_print:
                        self.command.execute(args)

            out = json.loads(str(mock_print.call_args_list[0][0][0]))
            self.assertEqual(out["ok"], True)
            self.assertTrue(cfg_path.exists())


if __name__ == "__main__":
    unittest.main()
