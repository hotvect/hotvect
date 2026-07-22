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
            args.force = False
            args.data_base_dir = str(Path(td) / "data")
            args.output_base_dir = str(Path(td) / "output")
            args.scratch_dir = str(Path(td) / "scratch")
            args.experiment_management_url = "https://ems.example.com"
            args.experiment_management_token_provider_command = "echo tok"
            args.experiment_management_token_provider_ttl_ms = 2000
            args.experiment_management_connect_timeout_seconds = 6.5
            args.experiment_management_read_timeout_seconds = 18.0
            args.experiment_management_online_results_slot = [
                "slot-a=s3://bucket-a/results/",
                "slot-b=s3://bucket-b/results/",
            ]

            with patch.object(hv_config, "DEFAULT_CONFIG_PATH", cfg_path):
                with patch("builtins.print") as mock_print:
                    self.command.execute(args)

            out = json.loads(str(mock_print.call_args_list[0][0][0]))
            self.assertEqual(out["ok"], True)
            self.assertTrue(cfg_path.exists())
            self.assertEqual(out["config"]["experiment_management"]["connect_timeout_seconds"], 6.5)
            self.assertEqual(out["config"]["experiment_management"]["read_timeout_seconds"], 18.0)
            self.assertEqual(
                out["config"]["experiment_management"]["online_results"]["slots"]["slot-a"]["s3_base_prefix"],
                "s3://bucket-a/results/",
            )

    def test_init_partial_args_raises(self):
        with tempfile.TemporaryDirectory() as td:
            cfg_path = Path(td) / "config.json"
            args = MagicMock()
            args.subcommand = "init"
            args.force = False
            args.data_base_dir = str(Path(td) / "data")
            args.output_base_dir = ""
            args.scratch_dir = ""
            args.experiment_management_url = ""
            args.experiment_management_token_provider_command = ""
            args.experiment_management_online_results_slot = []

            with patch.object(hv_config, "DEFAULT_CONFIG_PATH", cfg_path):
                with self.assertRaises(ValueError):
                    self.command.execute(args)

    def test_init_interactive_prompts(self):
        with tempfile.TemporaryDirectory() as td:
            cfg_path = Path(td) / "config.json"
            args = MagicMock()
            args.subcommand = "init"
            args.force = False
            args.data_base_dir = ""
            args.output_base_dir = ""
            args.scratch_dir = ""
            args.experiment_management_url = ""
            args.experiment_management_token_provider_command = ""
            args.experiment_management_token_provider_ttl_ms = 3600_000
            args.experiment_management_connect_timeout_seconds = 5.0
            args.experiment_management_read_timeout_seconds = 15.0
            args.experiment_management_online_results_slot = []

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

    def test_init_rejects_invalid_online_results_slot_mapping(self):
        with tempfile.TemporaryDirectory() as td:
            cfg_path = Path(td) / "config.json"
            args = MagicMock()
            args.subcommand = "init"
            args.force = False
            args.data_base_dir = str(Path(td) / "data")
            args.output_base_dir = str(Path(td) / "output")
            args.scratch_dir = str(Path(td) / "scratch")
            args.experiment_management_url = "https://ems.example.com"
            args.experiment_management_token_provider_command = "echo tok"
            args.experiment_management_token_provider_ttl_ms = 2000
            args.experiment_management_connect_timeout_seconds = 6.5
            args.experiment_management_read_timeout_seconds = 18.0
            args.experiment_management_online_results_slot = ["slot-a=not-s3"]

            with patch.object(hv_config, "DEFAULT_CONFIG_PATH", cfg_path):
                with self.assertRaises(ValueError):
                    self.command.execute(args)

    def test_init_rejects_online_results_slot_mapping_without_experiment_management_config(self):
        with tempfile.TemporaryDirectory() as td:
            cfg_path = Path(td) / "config.json"
            args = MagicMock()
            args.subcommand = "init"
            args.force = False
            args.data_base_dir = str(Path(td) / "data")
            args.output_base_dir = str(Path(td) / "output")
            args.scratch_dir = str(Path(td) / "scratch")
            args.experiment_management_url = ""
            args.experiment_management_token_provider_command = ""
            args.experiment_management_token_provider_ttl_ms = 3600_000
            args.experiment_management_connect_timeout_seconds = 5.0
            args.experiment_management_read_timeout_seconds = 15.0
            args.experiment_management_online_results_slot = ["slot-a=s3://bucket-a/results/"]

            with patch.object(hv_config, "DEFAULT_CONFIG_PATH", cfg_path):
                with self.assertRaises(ValueError):
                    self.command.execute(args)

    def test_init_existing_config_requires_force_and_preserves_file(self):
        with tempfile.TemporaryDirectory() as td:
            cfg_path = Path(td) / "config.json"
            original = {
                "directories": {
                    "data_base_dir": "/old/data",
                    "output_base_dir": "/old/output",
                    "scratch_dir": "/old/scratch",
                },
                "sagemaker": {"sagemaker_config_template": "/old/template.json"},
            }
            original_text = json.dumps(original)
            cfg_path.write_text(original_text)
            args = MagicMock()
            args.subcommand = "init"
            args.force = False

            with patch.object(hv_config, "DEFAULT_CONFIG_PATH", cfg_path):
                with self.assertRaisesRegex(FileExistsError, "use --force"):
                    self.command.execute(args)

            self.assertEqual(cfg_path.read_text(), original_text)

    def test_init_force_replaces_existing_config(self):
        with tempfile.TemporaryDirectory() as td:
            cfg_path = Path(td) / "config.json"
            cfg_path.write_text(json.dumps({"sagemaker": {"sagemaker_config_template": "/old/template.json"}}))
            args = MagicMock()
            args.subcommand = "init"
            args.force = True
            args.data_base_dir = str(Path(td) / "data")
            args.output_base_dir = str(Path(td) / "output")
            args.scratch_dir = str(Path(td) / "scratch")
            args.experiment_management_url = ""
            args.experiment_management_token_provider_command = ""
            args.experiment_management_token_provider_ttl_ms = 3600_000
            args.experiment_management_connect_timeout_seconds = 5.0
            args.experiment_management_read_timeout_seconds = 15.0
            args.experiment_management_online_results_slot = []

            with patch.object(hv_config, "DEFAULT_CONFIG_PATH", cfg_path), patch("builtins.print"):
                self.command.execute(args)

            self.assertEqual(
                json.loads(cfg_path.read_text()),
                {
                    "directories": {
                        "data_base_dir": str(Path(td) / "data"),
                        "output_base_dir": str(Path(td) / "output"),
                        "scratch_dir": str(Path(td) / "scratch"),
                    }
                },
            )


if __name__ == "__main__":
    unittest.main()
