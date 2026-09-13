"""Main CLI entry point for hv-qa command."""

from __future__ import annotations

import argparse
import logging
import sys

from hotvect.qa.commands import CanonicalCriteriaCommand, CriteriaCommand, EvaluateCommand, QaRunCommand
from hotvect.qa.commands.compare_predictions import ComparePredictionsCommand


def register_canonical_arguments(parser: argparse.ArgumentParser, *, prog: str = "hv qa") -> None:
    """Register the canonical QA namespace on an existing parser."""
    parser.description = "QA orchestration for Hotvect algorithm releases"
    parser.formatter_class = argparse.RawDescriptionHelpFormatter
    parser.epilog = f"""
Examples:
  {prog} candidate start --control baseline-ref --treatment candidate-ref --criteria noninferiority
  {prog} candidate start --prod-default-of-slot-as-control example-slot --treatment candidate-ref --last-test-date 2000-01-08 --days 7
  {prog} candidate resume <run_id> --until multi_day_backtest
  {prog} candidate status <run_id>

  {prog} evaluate --criteria noninferiority --last-test-date 2000-01-08 --days 2 --proof-dir /path/to/proof

  {prog} criteria list
  {prog} criteria describe noninferiority

  {prog} utils compare-predictions baseline.predict/part-00000.jsonl treatment.predict/part-00000.jsonl

    """
    subparsers = parser.add_subparsers(dest="qa_command", required=True, metavar="<command>")
    candidate_parser = subparsers.add_parser(
        "candidate",
        help="Candidate release-validation runs",
        description="Start, resume, or inspect a durable candidate release-validation run.",
    )
    candidate_subparsers = candidate_parser.add_subparsers(
        dest="candidate_command",
        required=True,
        metavar="<candidate-command>",
    )
    QaRunCommand.register_subcommands(candidate_subparsers, command_prefix=f"{prog} candidate")
    EvaluateCommand.register_parser(subparsers, command_prefix=prog)
    CanonicalCriteriaCommand.register_parser(subparsers, include_evaluate=False)
    utils_parser = subparsers.add_parser(
        "utils",
        help="QA diagnostic utilities",
    )
    utils_subparsers = utils_parser.add_subparsers(
        dest="utils_command",
        required=True,
        metavar="<utility>",
    )
    compare_parser = utils_subparsers.add_parser(
        "compare-predictions",
        help="Compare predict JSONL files for score and rank equivalence",
    )
    ComparePredictionsCommand.add_arguments(compare_parser)


def _build_legacy_parser(*, prog: str) -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog=prog,
        description=(
            "QA orchestration for Hotvect algorithm releases. "
            "Deprecated compatibility CLI; use 'hv qa' for new workflows."
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=f"""
Examples:
  {prog} start --control baseline-ref --treatment candidate-ref --criteria noninferiority
  {prog} start --prod-default-of-slot-as-control example-slot --treatment candidate-ref --last-test-date 2000-01-08 --days 7
  {prog} start --control baseline-ref --treatment candidate-ref --criteria exact
  {prog} start --control baseline-ref --treatment candidate-ref --criteria superiority
  {prog} start --control baseline-ref --treatment candidate-ref --until realistic_single_day
  {prog} resume <run_id> --until multi_day_backtest
  {prog} status <run_id>

  {prog} evaluate --criteria noninferiority --last-test-date 2000-01-08 --days 2 --proof-dir /path/to/proof

  {prog} criteria list
  {prog} criteria describe noninferiority
  cat payload.json | {prog} criteria evaluate noninferiority --pretty
        """,
    )
    subparsers = parser.add_subparsers(dest="command", help="Available subcommands", metavar="<command>")
    QaRunCommand.register_subcommands(subparsers, command_prefix=prog)
    EvaluateCommand.register_parser(subparsers, command_prefix=prog)
    CriteriaCommand.register_parser(subparsers)

    return parser


def execute_canonical(args: argparse.Namespace) -> int:
    if args.qa_command == "criteria":
        CanonicalCriteriaCommand().execute(args)
        return 0
    if args.qa_command == "candidate":
        QaRunCommand().execute(args, command=args.candidate_command)
        return 0
    if args.qa_command == "utils":
        ComparePredictionsCommand().execute(args)
        return 0
    if args.qa_command == "evaluate":
        return 0 if EvaluateCommand().execute(args) else 1
    raise ValueError(f"Unknown QA command: {args.qa_command}")


def main(argv: list[str] | None = None, *, prog: str = "hv-qa") -> int:
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    argv = list(sys.argv[1:] if argv is None else argv)
    parser = _build_legacy_parser(prog=prog)
    if not argv:
        parser.print_help()
        return 0
    if "-h" not in argv and "--help" not in argv:
        print(
            "Warning: 'hv-qa' is deprecated; use 'hv qa'. "
            "Candidate commands are now 'hv qa candidate <start|resume|status>'.",
            file=sys.stderr,
        )

    args = parser.parse_args(argv)

    try:
        if args.command == "criteria":
            CriteriaCommand().execute(args)
            return 0
        if args.command in {"start", "resume", "status"}:
            QaRunCommand().execute(args)
            return 0
        if args.command == "evaluate":
            return 0 if EvaluateCommand().execute(args) else 1
    except (FileNotFoundError, ValueError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 2

    parser.print_help()
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
