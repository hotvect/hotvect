"""Main CLI entry point for hv-exp command."""

from __future__ import annotations

import argparse
import logging
import sys

from hotvect.experiment_management.commands import ExperimentCommand


def main(argv: list[str] | None = None, *, prog: str = "hv-exp", warn_legacy: bool = True) -> int:
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    if warn_legacy:
        print("Warning: 'hv-exp' is deprecated; use 'hv ems'.", file=sys.stderr)

    parser = argparse.ArgumentParser(
        prog=prog,
        description="Experiment-management (EMS) utilities for hotvect",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=f"""
Examples:
  {prog} slot list
  {prog} slot get --slot-name my-slot
  {prog} experiment list --slot-name my-slot
  {prog} algorithm list-active
  {prog} algorithm list-in-use
        """,
    )

    ExperimentCommand.register_parser(parser)

    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv:
        parser.print_help()
        return 0

    args = parser.parse_args(argv)
    ExperimentCommand().execute(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
