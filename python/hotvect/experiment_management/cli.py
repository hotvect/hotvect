"""Main CLI entry point for hv-exp command."""

from __future__ import annotations

import argparse
import logging
import sys

from hotvect.experiment_management.commands import ExperimentCommand


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(message)s")

    parser = argparse.ArgumentParser(
        prog="hv-exp",
        description="Experiment-management (EMS) utilities for hotvect",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  hv-exp slot list
  hv-exp slot get --slot-name my-slot
  hv-exp experiment list --slot-name my-slot
  hv-exp algorithm list-active
  hv-exp algorithm list-in-use
        """,
    )

    ExperimentCommand.register_parser(parser)

    if len(sys.argv) == 1:
        parser.print_help()
        sys.exit(0)

    args = parser.parse_args()
    ExperimentCommand().execute(args)


if __name__ == "__main__":
    main()
