"""Main CLI entry point for hv-ext command."""

import argparse
import sys

from hotvect.extra.commands import (
    CatBoostConvertCommand,
    DownloadDataDependencyCommand,
    DownloadResultsCommand,
    EvalCompareCommand,
    JsonlCompareCommand,
    PerfCompareCommand,
    ShowDataDependencyCommand,
)


def main():
    """Main entry point for hv-ext CLI."""
    parser = argparse.ArgumentParser(
        prog="hv-ext",
        description="Extended utilities for hotvect ML operations",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Compare performance test results
  hv-ext perf-compare baseline.json treatment.json
  hv-ext perf-compare baseline.json treatment.json --format table --output report.txt

  # Convert CatBoost TSV to JSON
  hv-ext catboost-convert -s model.schema -e model.tsv -o model.json
  hv-ext catboost-convert --schema-file model.schema --encoded-file model.tsv --output model.jsonl --format jsonl

  # Compare JSONL files
  hv-ext compare-jsonl file1.jsonl file2.jsonl
  hv-ext compare-jsonl file1.jsonl file2.jsonl -c renamings.json

  # Download SageMaker backtest results
  hv-ext download-results --s3-base-prefix "s3://bucket/path/" --dest-base-dir "./results" --from-date "2025-06-01" --to-date "2025-06-15"
  hv-ext download-results --s3-base-prefix "s3://bucket/path/" --dest-base-dir "/local/results" --from-date "2025-06-01" --to-date "2025-06-15" --role-arn "arn:aws:iam::123456789012:role/example-role" --include-metadata

  # Compare ML evaluation results (supports both simple JSON and backtest result.json formats)
  hv-ext compare-evaluations baseline_eval.json treatment_eval.json
  hv-ext compare-evaluations baseline_results/result.json treatment_results/result.json -o comparison.json

  # Download data dependencies for local train/backtest operations
  hv-ext download-data-dependency --repo-url https://github.com/user/algorithm.git --git-reference v77.0.0 --s3-base-dir s3://bucket/data/ --local-data-dir ./data --scratch-dir ./temp --last-test-time 2025-04-30
  hv-ext download-data-dependency --repo-url https://github.com/user/algorithm.git --git-reference v77.0.0 --s3-base-dir s3://bucket/data/ --local-data-dir ./data --scratch-dir ./temp --last-test-time 2025-04-30 --sample-ratio 0.1

  # Show data dependencies for SageMaker InputDataConfig construction
  hv-ext show-data-dependency --repo-url https://github.com/user/algorithm.git --git-reference v77.0.0 --scratch-dir ./temp --last-test-time 2025-04-30
  hv-ext show-data-dependency --repo-url https://github.com/user/algorithm.git --git-reference v77.0.0 --git-reference v64.4.0 --scratch-dir ./temp --last-test-time 2025-04-30 -o dependencies.json

Use 'hv-ext <command> -h' to see help for each command.
        """,
    )

    subparsers = parser.add_subparsers(dest="command", help="Available subcommands", metavar="<command>")

    # Register commands
    PerfCompareCommand.register_parser(subparsers)
    CatBoostConvertCommand.register_parser(subparsers)
    JsonlCompareCommand.register_parser(subparsers)
    DownloadResultsCommand.register_parser(subparsers)
    EvalCompareCommand.register_parser(subparsers)
    DownloadDataDependencyCommand.register_parser(subparsers)
    ShowDataDependencyCommand.register_parser(subparsers)

    # Parse arguments
    if len(sys.argv) == 1:
        parser.print_help()
        sys.exit(0)

    args = parser.parse_args()

    # Execute command
    if args.command == "perf-compare":
        PerfCompareCommand().execute(args)
    elif args.command == "catboost-convert":
        CatBoostConvertCommand().execute(args)
    elif args.command == "compare-jsonl":
        JsonlCompareCommand().execute(args)
    elif args.command == "download-results":
        DownloadResultsCommand().execute(args)
    elif args.command == "compare-evaluations":
        EvalCompareCommand().execute(args)
    elif args.command == "download-data-dependency":
        DownloadDataDependencyCommand().execute(args)
    elif args.command == "show-data-dependency":
        ShowDataDependencyCommand().execute(args)
    else:
        parser.print_help()
        sys.exit(1)


if __name__ == "__main__":
    main()
