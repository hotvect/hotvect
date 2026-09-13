"""Simple module runner for built-in evaluation criteria."""

from __future__ import annotations

import argparse
import json
import sys

from hotvect.evaluation_criteria.builtin import list_builtin_criteria_ids, load_builtin_policy


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m hotvect.evaluation_criteria")
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("list", help="List built-in criteria ids.")

    describe_parser = subparsers.add_parser("describe", help="Print manifest for one built-in criteria package.")
    describe_parser.add_argument("criteria_id")

    evaluate_parser = subparsers.add_parser(
        "evaluate", help="Evaluate one built-in criteria package against a JSON payload from stdin."
    )
    evaluate_parser.add_argument("criteria_id")
    evaluate_parser.add_argument("--pretty", action="store_true", help="Pretty-print JSON output.")

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)

    if args.command == "list":
        json.dump({"criteria": list_builtin_criteria_ids()}, sys.stdout, indent=2)
        sys.stdout.write("\n")
        return 0

    policy = load_builtin_policy(args.criteria_id)

    if args.command == "describe":
        json.dump(policy.manifest.to_dict(), sys.stdout, indent=2)
        sys.stdout.write("\n")
        return 0

    payload = json.load(sys.stdin)
    result = policy.evaluate(payload)
    indent = 2 if args.pretty else None
    json.dump(result, sys.stdout, indent=indent, sort_keys=bool(indent))
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
