"""hv-ext config command."""

from __future__ import annotations

import json
from pathlib import Path

from hotvect.extra import config as hv_config

from .base import BaseCommand


def _parse_online_results_slot_mappings(raw_values) -> dict[str, dict[str, str]]:
    mappings = {}
    for raw_value in raw_values or []:
        if not isinstance(raw_value, str):
            raise ValueError(
                "Each --experiment-management-online-results-slot value must look like <slot>=s3://bucket/path/."
            )
        slot_name, separator, s3_base_prefix = raw_value.partition("=")
        slot_name = slot_name.strip()
        s3_base_prefix = s3_base_prefix.strip()
        if separator != "=" or not slot_name or not s3_base_prefix:
            raise ValueError(
                "Each --experiment-management-online-results-slot value must look like <slot>=s3://bucket/path/."
            )
        if not s3_base_prefix.startswith("s3://"):
            raise ValueError(
                "Each --experiment-management-online-results-slot value must use an s3:// prefix, "
                "for example <slot>=s3://bucket/path/."
            )
        if slot_name in mappings:
            raise ValueError(f"Duplicate online-results slot mapping for {slot_name!r}.")
        mappings[slot_name] = {"s3_base_prefix": s3_base_prefix}
    return mappings


class ConfigCommand(BaseCommand):
    @classmethod
    def register_parser(cls, subparsers):
        parser = subparsers.add_parser("config", help="Show or initialize ~/.hotvect/config.json (JSON output only)")
        sub = parser.add_subparsers(dest="subcommand", required=True, metavar="<subcommand>")

        sub.add_parser("show", help="Print the effective config JSON")

        init = sub.add_parser("init", help="Create a minimal config JSON; refuse to replace an existing file")
        init.add_argument(
            "--force",
            action="store_true",
            help="Replace an existing config instead of refusing to overwrite it.",
        )
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
        init.add_argument(
            "--experiment-management-connect-timeout-seconds",
            type=float,
            default=5.0,
            help="Connect timeout in seconds for EMS requests (default: 5.0).",
        )
        init.add_argument(
            "--experiment-management-read-timeout-seconds",
            type=float,
            default=15.0,
            help="Read timeout in seconds for EMS requests (default: 15.0).",
        )
        init.add_argument(
            "--experiment-management-online-results-slot",
            action="append",
            default=[],
            help="Add an online-results slot mapping as <slot>=s3://bucket/path/ (repeatable; requires EMS config).",
        )
        return parser

    def execute(self, args):
        if args.subcommand == "show":
            cfg = hv_config.load_config()
            out = {"ok": True, "config_path": str(hv_config.get_config_path()), "config": cfg}
            print(json.dumps(out, indent=2))
            return

        if args.subcommand == "init":
            config_path = hv_config.get_config_path()
            if config_path.exists() and not args.force:
                raise FileExistsError(
                    f"Config already exists: {config_path}. Refusing to replace it; use --force to overwrite it."
                )

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
            raw_experiment_management_connect_timeout_seconds = getattr(
                args, "experiment_management_connect_timeout_seconds", 5.0
            )
            try:
                experiment_management_connect_timeout_seconds = float(raw_experiment_management_connect_timeout_seconds)
            except (TypeError, ValueError):
                experiment_management_connect_timeout_seconds = 5.0
            raw_experiment_management_read_timeout_seconds = getattr(
                args, "experiment_management_read_timeout_seconds", 15.0
            )
            try:
                experiment_management_read_timeout_seconds = float(raw_experiment_management_read_timeout_seconds)
            except (TypeError, ValueError):
                experiment_management_read_timeout_seconds = 15.0
            online_results_slots = _parse_online_results_slot_mappings(
                getattr(args, "experiment_management_online_results_slot", [])
            )

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
            if online_results_slots and not (
                experiment_management_url and experiment_management_token_provider_command
            ):
                raise ValueError(
                    "Provide --experiment-management-url and --experiment-management-token-provider-command "
                    "when using --experiment-management-online-results-slot."
                )
            if experiment_management_url or experiment_management_token_provider_command:
                if not (experiment_management_url and experiment_management_token_provider_command):
                    raise ValueError(
                        "Provide both --experiment-management-url and --experiment-management-token-provider-command, "
                        "or omit both."
                    )
                if experiment_management_token_provider_ttl_ms <= 0:
                    raise ValueError("--experiment-management-token-provider-ttl-ms must be > 0")
                if experiment_management_connect_timeout_seconds <= 0:
                    raise ValueError("--experiment-management-connect-timeout-seconds must be > 0")
                if experiment_management_read_timeout_seconds <= 0:
                    raise ValueError("--experiment-management-read-timeout-seconds must be > 0")
                cfg["experiment_management"] = {
                    "url": experiment_management_url,
                    "token_provider_command": experiment_management_token_provider_command,
                    "token_provider_ttl_ms": int(experiment_management_token_provider_ttl_ms),
                    "connect_timeout_seconds": float(experiment_management_connect_timeout_seconds),
                    "read_timeout_seconds": float(experiment_management_read_timeout_seconds),
                }
                if online_results_slots:
                    cfg["experiment_management"]["online_results"] = {"slots": online_results_slots}
            path = hv_config.write_config(config=cfg)
            out = {"ok": True, "config_path": str(path), "config": cfg}
            print(json.dumps(out, indent=2))
            return

        raise ValueError(f"Unknown subcommand: {args.subcommand}")
