"""Shared CLI command wrappers for evaluation criteria."""

from __future__ import annotations

import json
from typing import Any

from hotvect.evaluation_criteria import list_builtin_criteria_ids
from hotvect.evaluation_criteria.cli_support import load_payload_from_cli, load_policy_or_exit


class CriteriaCommandMixin:
    """Top-level criteria command wrapper shared by hv-qa and hv-ext."""

    prog_name = "hv"

    @classmethod
    def register_parser(cls, subparsers, *, include_evaluate: bool = True):
        parser = subparsers.add_parser(
            "criteria",
            help="Evaluation criteria utilities (JSON output only)",
        )
        criteria_subparsers = parser.add_subparsers(dest="criteria_command", metavar="<criteria-command>")
        CriteriaListCommand.register_parser(criteria_subparsers)
        CriteriaDescribeCommand.register_parser(criteria_subparsers)
        if include_evaluate:
            CriteriaEvaluateCommand.register_parser(criteria_subparsers)
        return parser

    def execute(self, args):
        if args.criteria_command == "list":
            CriteriaListCommand().execute(args)
        elif args.criteria_command == "describe":
            CriteriaDescribeCommand().execute(args)
        elif args.criteria_command == "evaluate":
            CriteriaEvaluateCommand().execute(args)
        else:
            raise SystemExit(f"Missing criteria subcommand. Use `{self.prog_name} criteria -h`.")


class CriteriaListCommand:
    """List built-in evaluation criteria ids."""

    @classmethod
    def register_parser(cls, subparsers):
        return subparsers.add_parser(
            "list",
            help="List built-in criteria ids",
        )

    def execute(self, args: Any) -> None:
        del args
        print(json.dumps({"criteria": list_builtin_criteria_ids()}, indent=2))


class CriteriaDescribeCommand:
    """Describe one built-in evaluation criteria package."""

    @classmethod
    def register_parser(cls, subparsers):
        parser = subparsers.add_parser(
            "describe",
            help="Print the manifest for one built-in criteria package",
        )
        parser.add_argument("criteria_id", help="Built-in criteria id")
        return parser

    def execute(self, args: Any) -> None:
        policy = load_policy_or_exit(args.criteria_id)
        print(json.dumps(policy.manifest.to_dict(), indent=2))


class CriteriaEvaluateCommand:
    """Evaluate one built-in criteria package against a JSON payload."""

    @classmethod
    def register_parser(cls, subparsers):
        parser = subparsers.add_parser(
            "evaluate",
            help="Evaluate one built-in criteria package against a JSON payload",
        )
        parser.add_argument("criteria_id", help="Built-in criteria id")
        parser.add_argument(
            "--payload-file",
            default="",
            help="Read the criteria payload JSON from a file instead of stdin",
        )
        parser.add_argument("--pretty", action="store_true", help="Pretty-print JSON output")
        return parser

    def execute(self, args: Any) -> None:
        policy = load_policy_or_exit(args.criteria_id)
        payload = load_payload_from_cli((args.payload_file or "").strip() or None)
        result = policy.evaluate(payload)
        indent = 2 if args.pretty else None
        print(json.dumps(result, indent=indent, sort_keys=bool(indent)))
