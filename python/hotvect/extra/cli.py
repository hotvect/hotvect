"""Main CLI entry point for hv-ext command."""

import argparse
import logging
import sys

from hotvect.extra.commands import (
    CatBoostConvertCommand,
    CompareEquivalenceCommand,
    ConfigCommand,
    DataDependencyCommand,
    JsonlCompareCommand,
    MetricsCommand,
    ResultsCommand,
    ShowDataDependencyCommand,
)


def main():
    """Main entry point for hv-ext CLI."""
    # Configure logging to show INFO messages from build utilities
    # This makes git clone and Maven build progress visible to users
    logging.basicConfig(
        level=logging.INFO,
        format="%(message)s",  # Simple format without timestamps for CLI
    )

    parser = argparse.ArgumentParser(
        prog="hv-ext",
        description="Extended utilities for hotvect ML operations",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Metrics utilities (quality/system)
  hv-ext metrics compare-quality control.json treatment.json
  hv-ext metrics compare-system control.json treatment.json

  # Convert CatBoost TSV to JSONL
  hv-ext catboost-convert -s model.schema -e model.tsv -o model.jsonl

  # Compare JSONL files
  hv-ext compare-jsonl file1.jsonl file2.jsonl
  hv-ext compare-jsonl file1.jsonl file2.jsonl -c renamings.json

  # Compare predict score/rank equivalence
  hv-ext compare-equivalence baseline.predict.jsonl treatment.predict.jsonl
  hv-ext compare-equivalence baseline.predict.jsonl treatment.predict.jsonl --allow-non-deterministic-tie-breaking

  # List/download result.json runs (local meta dir or s3:// prefix)
  hv-ext results ls s3://bucket/path/ --from-date "2026-02-15" --to-date "2026-02-15" --algorithm-name-regex "example-algorithm" --algorithm-version-regex "74\\.4\\..*" --job-name-regex "ml-exp-.*"
  hv-ext results download s3://bucket/path/ --dest-base-dir "./results" --from-date "2026-02-15" --to-date "2026-02-15" --algorithm-name-regex "example-algorithm" --algorithm-version-regex "74\\.4\\..*" --job-name-regex "ml-exp-.*" --include-metadata

  # Show or download data dependencies (default: list as JSON)
  hv-ext data-dependency --repo-url https://github.com/user/algorithm.git --git-reference v77.0.0 --s3-base-dir s3://bucket/data/ --local-data-dir ./data --scratch-dir ./temp --last-test-time 2025-04-30
  hv-ext data-dependency --download-all --repo-url https://github.com/user/algorithm.git --git-reference v77.0.0 --s3-base-dir s3://bucket/data/ --local-data-dir ./data --scratch-dir ./temp --last-test-time 2025-04-30
  hv-ext data-dependency --download training_data --repo-url https://github.com/user/algorithm.git --git-reference v77.0.0 --s3-base-dir s3://bucket/data/ --local-data-dir ./data --scratch-dir ./temp --last-test-time 2025-04-30 --sample-ratio 0.1

  # Show data dependencies for SageMaker InputDataConfig construction
  hv-ext show-data-dependency --repo-url https://github.com/user/algorithm.git --git-reference v77.0.0 --scratch-dir ./temp --last-test-time 2025-04-30
  hv-ext show-data-dependency --repo-url https://github.com/user/algorithm.git --git-reference v77.0.0 --git-reference v64.4.0 --scratch-dir ./temp --last-test-time 2025-04-30 > dependencies.json

Use 'hv-ext <command> -h' to see help for each command.
        """,
    )

    subparsers = parser.add_subparsers(dest="command", help="Available subcommands", metavar="<command>")

    # Register commands
    MetricsCommand.register_parser(subparsers)
    CatBoostConvertCommand.register_parser(subparsers)
    ConfigCommand.register_parser(subparsers)
    JsonlCompareCommand.register_parser(subparsers)
    CompareEquivalenceCommand.register_parser(subparsers)
    DataDependencyCommand.register_parser(subparsers)
    ShowDataDependencyCommand.register_parser(subparsers)
    ResultsCommand.register_parser(subparsers)

    # Parse arguments
    if len(sys.argv) == 1:
        parser.print_help()
        sys.exit(0)

    args = parser.parse_args()

    # Execute command
    if args.command == "metrics":
        MetricsCommand().execute(args)
    elif args.command == "catboost-convert":
        CatBoostConvertCommand().execute(args)
    elif args.command == "config":
        ConfigCommand().execute(args)
    elif args.command == "compare-jsonl":
        JsonlCompareCommand().execute(args)
    elif args.command == "compare-equivalence":
        CompareEquivalenceCommand().execute(args)
    elif args.command == "data-dependency":
        DataDependencyCommand().execute(args)
    elif args.command == "show-data-dependency":
        ShowDataDependencyCommand().execute(args)
    elif args.command == "results":
        ResultsCommand().execute(args)
    else:
        parser.print_help()
        sys.exit(1)


if __name__ == "__main__":
    main()
