"""hv-ext config command."""

from __future__ import annotations

import json
from pathlib import Path

from hotvect.extra import config as hv_config

from .base import BaseCommand


class ConfigCommand(BaseCommand):
    @classmethod
    def register_parser(cls, subparsers):
        parser = subparsers.add_parser("config", help="Show or initialize ~/.hotvect/config.json (JSON output only)")
        sub = parser.add_subparsers(dest="subcommand", required=True, metavar="<subcommand>")

        sub.add_parser("show", help="Print the effective config JSON")

        init = sub.add_parser("init", help="Write a minimal config JSON")
        init.add_argument(
            "--data-base-dir",
            default="",
            help="Base directory for local data (if omitted, prompts interactively)",
        )
        init.add_argument(
            "--output-base-dir",
            default="",
            help="Base directory for local outputs (if omitted, prompts interactively)",
        )
        init.add_argument(
            "--scratch-dir",
            default="",
            help="Base directory for scratch/build artifacts (if omitted, prompts interactively)",
        )
        init.add_argument(
            "--experiment-management-url",
            default="",
            help="Base URL for experiment-management service (optional).",
        )
        init.add_argument(
            "--experiment-management-token-provider-command",
            default="",
            help="Command that prints a bearer token (optional).",
        )
        init.add_argument(
            "--experiment-management-token-provider-ttl-ms",
            type=int,
            default=3600_000,
            help="Token cache TTL in ms for the token provider command (default: 3600000).",
        )
        return parser

    def execute(self, args):
        if args.subcommand == "show":
            cfg = hv_config.load_config()
            out = {"ok": True, "config_path": str(hv_config.get_config_path()), "config": cfg}
            print(json.dumps(out, indent=2))
            return

        if args.subcommand == "init":
            data_base_dir = (args.data_base_dir or "").strip()
            output_base_dir = (args.output_base_dir or "").strip()
            scratch_dir = (args.scratch_dir or "").strip()
            raw_experiment_management_url = getattr(args, "experiment_management_url", "")
            experiment_management_url = (
                raw_experiment_management_url.strip() if isinstance(raw_experiment_management_url, str) else ""
            )

            raw_experiment_management_token_provider_command = getattr(
                args, "experiment_management_token_provider_command", ""
            )
            experiment_management_token_provider_command = (
                raw_experiment_management_token_provider_command.strip()
                if isinstance(raw_experiment_management_token_provider_command, str)
                else ""
            )

            raw_experiment_management_token_provider_ttl_ms = getattr(
                args, "experiment_management_token_provider_ttl_ms", 3600_000
            )
            try:
                experiment_management_token_provider_ttl_ms = int(raw_experiment_management_token_provider_ttl_ms)
            except (TypeError, ValueError):
                experiment_management_token_provider_ttl_ms = 3600_000

            any_cli = bool(data_base_dir or output_base_dir or scratch_dir)
            if any_cli:
                if not (data_base_dir and output_base_dir and scratch_dir):
                    raise ValueError(
                        "Provide all of --data-base-dir, --output-base-dir, and --scratch-dir together, "
                        "or omit all three to use interactive prompts."
                    )
            else:
                data_base_dir = input("data_base_dir: ").strip()
                output_base_dir = input("output_base_dir: ").strip()
                scratch_dir = input("scratch_dir: ").strip()
                if not (data_base_dir and output_base_dir and scratch_dir):
                    raise ValueError("All directories must be provided (empty value not allowed).")

            cfg = {
                "directories": {
                    "data_base_dir": str(Path(data_base_dir).expanduser()),
                    "output_base_dir": str(Path(output_base_dir).expanduser()),
                    "scratch_dir": str(Path(scratch_dir).expanduser()),
                }
            }
            if experiment_management_url or experiment_management_token_provider_command:
                if not (experiment_management_url and experiment_management_token_provider_command):
                    raise ValueError(
                        "Provide both --experiment-management-url and --experiment-management-token-provider-command, "
                        "or omit both."
                    )
                if experiment_management_token_provider_ttl_ms <= 0:
                    raise ValueError("--experiment-management-token-provider-ttl-ms must be > 0")
                cfg["experiment_management"] = {
                    "url": experiment_management_url,
                    "token_provider_command": experiment_management_token_provider_command,
                    "token_provider_ttl_ms": int(experiment_management_token_provider_ttl_ms),
                }
            path = hv_config.write_config(config=cfg)
            out = {"ok": True, "config_path": str(path), "config": cfg}
            print(json.dumps(out, indent=2))
            return

        raise ValueError(f"Unknown subcommand: {args.subcommand}")
