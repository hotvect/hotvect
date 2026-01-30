"""Download results command for hv-extra CLI."""

import sys
from datetime import date
from pathlib import Path

from hotvect.backtest import SageMakerBacktestResultsDownloader

from .base import BaseCommand


class DownloadResultsCommand(BaseCommand):
    """Download SageMaker backtest results from S3 to local directory."""

    @classmethod
    def register_parser(cls, subparsers):
        """Register the download-results command parser."""
        parser = subparsers.add_parser(
            "download-results", help="Download SageMaker backtest results from S3 to local directory"
        )

        # Required arguments
        parser.add_argument(
            "--s3-base-prefix",
            required=True,
            help='S3 base prefix where backtest results are stored (e.g., "s3://bucket/path/")',
        )
        parser.add_argument(
            "--dest-base-dir", required=True, help="Local destination directory where results will be downloaded"
        )
        parser.add_argument(
            "--from-date", required=True, help="Start date for results to download in YYYY-MM-DD format (inclusive)"
        )
        parser.add_argument(
            "--to-date", required=True, help="End date for results to download in YYYY-MM-DD format (inclusive)"
        )

        # Optional arguments
        parser.add_argument("--role-arn", help="AWS role ARN to assume for S3 access (optional)")
        parser.add_argument(
            "--include-metadata", action="store_true", help="Include metadata files in download (default: False)"
        )
        parser.add_argument(
            "--include-output-data", action="store_true", help="Include output data files in download (default: False)"
        )
        parser.add_argument(
            "--no-skip-existing",
            action="store_true",
            help="Re-download files even if they already exist locally (default: skip existing)",
        )

        return parser

    def execute(self, args):
        """Execute the download results operation."""
        try:
            # Parse dates
            try:
                from_date = date.fromisoformat(args.from_date)
                to_date = date.fromisoformat(args.to_date)
            except ValueError as e:
                print(f"Error: Invalid date format - {e}", file=sys.stderr)
                print("Please use YYYY-MM-DD format (e.g., '2025-06-01')", file=sys.stderr)
                sys.exit(1)

            # Validate date range
            if from_date > to_date:
                print("Error: Start date must be before or equal to end date", file=sys.stderr)
                sys.exit(1)

            # Create destination directory if it doesn't exist
            dest_path = Path(args.dest_base_dir)
            dest_path.mkdir(parents=True, exist_ok=True)

            print("Downloading SageMaker backtest results...")
            print(f"  S3 source: {args.s3_base_prefix}")
            print(f"  Local destination: {args.dest_base_dir}")
            print(f"  Date range: {from_date} to {to_date}")
            if args.role_arn:
                print(f"  Role ARN: {args.role_arn}")
            print(f"  Include metadata: {args.include_metadata}")
            print(f"  Include output data: {args.include_output_data}")
            print(f"  Skip existing: {not args.no_skip_existing}")
            print()

            # Create downloader
            downloader = SageMakerBacktestResultsDownloader(
                s3_base_prefix=args.s3_base_prefix,
                dest_base_dir=args.dest_base_dir,
                include_metadata=args.include_metadata,
                skip_data_if_already_present=not args.no_skip_existing,
                include_output_data=args.include_output_data,
                from_including_test_date=from_date,
                to_including_test_date=to_date,
                role_arn_to_assume=args.role_arn,
            )

            # Execute download
            downloader.download()

            print("Download completed successfully!")

        except Exception as e:
            print(f"Error during download: {e}", file=sys.stderr)
            sys.exit(1)
