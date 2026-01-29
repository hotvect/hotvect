"""JSONL comparison command for hv-extra CLI."""

import json
import os
import sys

from hotvect.json_utils import find_difference_in_files

from .base import BaseCommand


class JsonlCompareCommand(BaseCommand):
    """Compare two JSONL files and identify differences between them."""

    @classmethod
    def register_parser(cls, subparsers):
        """Register the compare-jsonl command parser."""
        parser = subparsers.add_parser(
            "compare-jsonl", help="Compare two JSONL files and identify differences between them"
        )
        parser.add_argument("file1", help="Path to the first JSONL file to compare")
        parser.add_argument("file2", help="Path to the second JSONL file to compare")
        parser.add_argument(
            "-o",
            "--output",
            default=".",
            help="Output directory where comparison result files will be stored (default: current directory)",
        )
        parser.add_argument(
            "-c", "--config", help="Path to JSON configuration file for field renaming and comparison rules (optional)"
        )
        return parser

    def execute(self, args):
        """Execute the JSONL comparison."""
        try:
            # Validate input files exist
            if not os.path.exists(args.file1):
                print(f"Error: File not found - {args.file1}", file=sys.stderr)
                sys.exit(1)

            if not os.path.exists(args.file2):
                print(f"Error: File not found - {args.file2}", file=sys.stderr)
                sys.exit(1)

            # Validate config file if provided
            if args.config and not os.path.exists(args.config):
                print(f"Error: Config file not found - {args.config}", file=sys.stderr)
                sys.exit(1)

            # Perform comparison
            result = find_difference_in_files(args.file1, args.file2, args.output, args.config)

            # Output results as JSON
            print(result)

        except json.JSONDecodeError as e:
            print(f"Error: Invalid JSON in file - {e}", file=sys.stderr)
            sys.exit(1)
        except Exception as e:
            print(f"Error: {e}", file=sys.stderr)
            sys.exit(1)
