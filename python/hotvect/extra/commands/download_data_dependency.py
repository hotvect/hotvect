"""Data dependency command for hv-ext CLI."""

import argparse
import json
import logging
import os
import random
import shutil
import sys
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date
from pathlib import Path
from typing import NamedTuple
from urllib.parse import urlparse

import boto3
from botocore.config import Config
from mypy_boto3_s3 import S3Client

from hotvect.build_utils import clone_and_build_algorithm_jar
from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext, DataDependency
from hotvect.utils import (
    build_s3_date_path,
    format_s3_uri,
    get_boto_session_after_assuming_role,
    parse_s3_uri,
    resolve_data_dependency_s3_uri,
    sanitize_path_component,
)

from .base import BaseCommand

logger = logging.getLogger(__name__)

DEFAULT_MAX_CONCURRENT_DOWNLOADS = 8
SAMPLE_SEED = 42  # Fixed seed for deterministic sampling
DEFAULT_DOWNLOAD_MAX_RETRIES = 3
DEFAULT_DOWNLOAD_RETRY_BASE_BACKOFF_SECONDS = 0.2

DownloadPlanItem = tuple[
    str, str, str, str, list[str], bool
]  # (bucket, data_prefix, date_str, s3_date_prefix, files, is_sampled)


def _deterministic_sample(files: list[str], sample_ratio: float) -> list[str]:
    """
    Deterministically sample files using sorted order and fixed seed.

    Args:
        files: List of file paths
        sample_ratio: Sampling ratio (0 < ratio <= 1)

    Returns:
        Sampled list of files (same output for same inputs)
    """
    if sample_ratio >= 1.0:
        return files

    # Sort for deterministic order
    sorted_files = sorted(files)

    # Calculate sample size
    sample_count = max(1, int(len(sorted_files) * sample_ratio))

    # Shuffle with fixed seed and take first N
    rng = random.Random(SAMPLE_SEED)
    shuffled = sorted_files.copy()
    rng.shuffle(shuffled)

    return shuffled[:sample_count]


class MissingData(NamedTuple):
    """Details about missing data locations detected during planning."""

    data_prefix: str
    date_str: str
    s3_path: str
    local_path: str
    reason: str


class DataDependencyCommand(BaseCommand):
    """Show or download data dependencies for algorithm training/backtesting."""

    @staticmethod
    def _relative_key_under_prefix(s3_key: str, s3_date_prefix: str) -> str:
        """Return object key path relative to date prefix."""
        normalized_key = s3_key.lstrip("/")
        normalized_prefix = s3_date_prefix.lstrip("/").rstrip("/") + "/"
        if not normalized_key.startswith(normalized_prefix):
            raise ValueError(f"S3 key {s3_key!r} does not start with expected prefix {s3_date_prefix!r}")
        return normalized_key[len(normalized_prefix) :]

    @staticmethod
    def _local_file_relative_paths(base_dir: Path) -> set[str]:
        """Collect file paths under a directory as POSIX relative paths."""
        if not base_dir.exists():
            return set()
        return {path.relative_to(base_dir).as_posix() for path in base_dir.rglob("*") if path.is_file()}

    @classmethod
    def register_parser(cls, subparsers):
        """Register the data-dependency command parser."""
        parser = subparsers.add_parser(
            "data-dependency",
            help="Show or download data dependencies for algorithm training/backtesting",
            epilog="""
Default behavior: List all data dependencies as JSON (safe, no download)

To download data, use --download-all or --download <name> flags explicitly.

List mode performs full S3 enumeration to compute sizes and check local status,
so AWS credentials are required even when not downloading.

Examples:
  # List all dependencies (default, outputs JSON to stdout)
  hv-ext data-dependency \\
    --repo-url https://github.com/company/example-algorithm.git \\
    --git-reference v77.0.0 \\
    --s3-base-dir s3://bucket/tables \\
    --local-data-dir ./data \\
    --scratch-dir ./scratch \\
    --last-test-time 2025-08-09

  # List prediction dependencies instead of evaluation dependencies
  hv-ext data-dependency --target predict (same arguments as above)

  # Download all dependencies
  hv-ext data-dependency --download-all (same arguments as above)

  # Download specific dependency
  hv-ext data-dependency --download example_training_data_attribution (same arguments)

  # Download with 5%% sampling
  hv-ext data-dependency --download-all --sample-ratio 0.05 (same arguments)

  # Pipe JSON to jq for analysis
  hv-ext data-dependency ... | jq '.summary.total_size_human'

Output:
  - stdout: JSON output only (safe to pipe)
  - stderr: Human-readable progress logs
            """,
            formatter_class=argparse.RawDescriptionHelpFormatter,
        )

        # Download control (mutually exclusive)
        download_group = parser.add_mutually_exclusive_group()
        download_group.add_argument(
            "--download-all",
            action="store_true",
            help="Download all data dependencies",
        )
        download_group.add_argument(
            "--download",
            action="append",
            dest="download_dependencies",
            metavar="NAME",
            help="Download specific dependency by data_prefix name. Can be specified multiple times.",
        )

        # Required arguments
        parser.add_argument("--repo-url", required=True, help="Git repository URL containing the algorithm")
        parser.add_argument(
            "--git-reference",
            required=True,
            help="Git reference (branch/tag/commit) to analyze",
        )
        parser.add_argument(
            "--s3-base-dir",
            required=True,
            help='S3 base directory containing data tables (e.g., "s3://bucket/path/")',
        )
        parser.add_argument(
            "--local-data-dir",
            required=True,
            help="Local directory where data will be stored",
        )
        parser.add_argument(
            "--scratch-dir", required=True, help="Scratch directory for temporary files (algorithm builds)"
        )
        parser.add_argument("--last-test-time", required=True, help="Last test time in YYYY-MM-DD format")
        parser.add_argument(
            "--target",
            choices=["parameters", "predict", "evaluate"],
            default="evaluate",
            help=(
                "Dependency target to analyze: 'evaluate' uses test_data_spec, "
                "'predict' uses prediction_spec, and 'parameters' only includes "
                "dependencies needed to prepare parameters (default: evaluate)"
            ),
        )

        # Optional arguments
        parser.add_argument(
            "--algorithm-override",
            help="Path to algorithm override JSON file",
        )
        parser.add_argument("--role-arn", help="AWS role ARN to assume for S3 access")
        parser.add_argument(
            "--sample-ratio",
            type=float,
            help="Sample ratio for downloading (0.0-1.0, e.g., 0.01 for 1%%)",
        )
        parser.add_argument(
            "--max-parallel-downloads",
            type=int,
            default=DEFAULT_MAX_CONCURRENT_DOWNLOADS,
            help=f"Maximum number of parallel downloads (default: {DEFAULT_MAX_CONCURRENT_DOWNLOADS})",
        )

        return parser

    def execute(self, args):
        """Execute the data dependency operation."""
        try:
            # Parse dates
            try:
                last_test_time = date.fromisoformat(args.last_test_time)
            except ValueError as e:
                print(f"Error: Invalid date format - {e}", file=sys.stderr)
                print("Please use YYYY-MM-DD format (e.g., '2025-04-30')", file=sys.stderr)
                sys.exit(1)

            # Validate sample ratio
            if args.sample_ratio is not None:
                if not 0 < args.sample_ratio <= 1:
                    print("Error: --sample-ratio must be between 0 and 1", file=sys.stderr)
                    sys.exit(1)

            # Create directories
            local_data_path = Path(args.local_data_dir)
            local_data_path.mkdir(parents=True, exist_ok=True)
            scratch_path = Path(args.scratch_dir)
            scratch_path.mkdir(parents=True, exist_ok=True)

            # Log to stderr (human-readable progress)
            print("Analyzing data dependencies...", file=sys.stderr)
            print(f"  Git reference: {args.git_reference}", file=sys.stderr)
            print(f"  S3 source: {args.s3_base_dir}", file=sys.stderr)
            print(f"  Local destination: {args.local_data_dir}", file=sys.stderr)
            print(f"  Last test time: {last_test_time}", file=sys.stderr)
            print(f"  Target: {args.target}", file=sys.stderr)
            if args.sample_ratio:
                print(f"  Sampling: {args.sample_ratio*100:.1f}% of files per date directory", file=sys.stderr)
            if args.role_arn:
                print(f"  Role ARN: {args.role_arn}", file=sys.stderr)
            print(file=sys.stderr)

            # Parse S3 location
            s3_uri_parsed = urlparse(args.s3_base_dir)
            s3_bucket = s3_uri_parsed.netloc
            s3_prefix = s3_uri_parsed.path.strip("/")

            # Setup S3 client
            logging.getLogger("urllib3.connectionpool").setLevel(logging.ERROR)
            max_downloads = max(1, args.max_parallel_downloads)
            pool_size = max_downloads * 2 + 10
            session = get_boto_session_after_assuming_role(args.role_arn) if args.role_arn else boto3.Session()
            s3_client = session.client("s3", config=Config(max_pool_connections=pool_size))

            # Get data dependencies for single git reference
            algorithm_name, algorithm_version, dependencies = self._get_data_dependencies(
                args.repo_url,
                args.git_reference,
                args.scratch_dir,
                last_test_time,
                args.target,
                args.algorithm_override,
            )

            # Determine download mode
            download_mode = self._get_download_mode(args)

            # If list mode, output JSON and exit
            if download_mode == "list":
                self._output_dependency_info_json(
                    algorithm_name,
                    algorithm_version,
                    dependencies,
                    args,
                    local_data_path,
                    s3_client,
                    s3_bucket,
                    s3_prefix,
                )
                return

            # Filter dependencies based on download mode
            if download_mode == "all":
                deps_to_download = dependencies
            else:
                # download_mode is list of names
                deps_to_download = self._filter_dependencies(dependencies, download_mode)
                if not deps_to_download:
                    error_msg = {"error": "No matching dependencies found", "requested": download_mode}
                    print(json.dumps(error_msg), file=sys.stderr)
                    sys.exit(1)

            # Create download plan (always use smart resume)
            download_plan, missing_data, skipped_existing = self._create_download_plan(
                deps_to_download,
                local_data_path,
                s3_client,
                s3_bucket,
                s3_prefix,
                args.sample_ratio,
                skip_if_present=True,
            )

            if missing_data:
                print("\nMissing data detected in S3:", file=sys.stderr)
                for entry in missing_data:
                    print(f"  ✗ {entry.data_prefix} dt={entry.date_str}", file=sys.stderr)
                    print(f"      S3 path: {entry.s3_path}", file=sys.stderr)
                    print(f"      Local path: {entry.local_path}", file=sys.stderr)
                    print(f"      Reason: {entry.reason}", file=sys.stderr)
                print("\nFATAL: Required data missing from S3. Cannot proceed with download.", file=sys.stderr)
                sys.exit(1)

            if not download_plan:
                if skipped_existing > 0:
                    print(
                        f"No data to download - all {skipped_existing} required date directories already exist locally.",
                        file=sys.stderr,
                    )
                else:
                    print("No data to download.", file=sys.stderr)
                # Output success JSON
                result = {"status": "success", "message": "All data already present locally"}
                print(json.dumps(result, indent=2))
                return

            # Execute downloads
            self._execute_downloads(
                download_plan,
                s3_client,
                args.local_data_dir,
                args.scratch_dir,
                args.max_parallel_downloads,
            )

            print("\nDownload completed successfully!", file=sys.stderr)

        except Exception as e:
            print(f"Error: {e}", file=sys.stderr)
            sys.exit(1)

    def _get_data_dependencies(
        self,
        repo_url: str,
        git_reference: str,
        scratch_dir: str,
        last_test_time: date,
        target: str,
        algorithm_override: str,
    ) -> tuple[str, str, list[DataDependency]]:
        """
        Get data dependencies for a single algorithm git reference.

        Returns:
            (algorithm_name, algorithm_version, dependencies)
        """
        print(f"Analyzing dependencies for {git_reference}...", file=sys.stderr)

        scratch_base = Path(scratch_dir)
        scratch_base.mkdir(parents=True, exist_ok=True)

        safe_ref = sanitize_path_component(git_reference)

        # Create temporary directory for this git reference (under user-provided scratch dir)
        with tempfile.TemporaryDirectory(prefix=f"git-{safe_ref}-", dir=str(scratch_base)) as temp_dir:
            temp_path = Path(temp_dir)

            # Clone and build algorithm JAR
            result = clone_and_build_algorithm_jar(
                repo_url=repo_url,
                git_reference=git_reference,
                work_dir=temp_path,
                copy_jar_to=None,  # Keep JAR in temp directory
                progress_stream=sys.stderr,
            )

            algorithm_name = result.algorithm_name
            algorithm_version = result.algorithm_version
            algorithm_jar = result.algorithm_jar_path

            # Create algorithm pipeline context
            context = AlgorithmPipelineContext(
                algorithm_jar_path=algorithm_jar,
                state_source_base_path=Path(scratch_dir),
                data_base_path=Path(scratch_dir),
                metadata_base_path=Path(scratch_dir) / "metadata",
                output_base_path=Path(scratch_dir) / "output",
                jvm_options=["-XX:MaxRAMPercentage=80"],
                max_threads=1,
            )

            # Parse algorithm override if provided
            algorithm_definition = algorithm_name
            if algorithm_override:
                with open(algorithm_override) as f:
                    override_config = json.load(f)
                algorithm_definition = (algorithm_name, override_config)

            # Create pipeline and get dependencies
            pipeline = AlgorithmPipeline(
                algorithm_pipeline_context=context,
                algorithm_definition=algorithm_definition,
                last_test_time=last_test_time,
                evaluation_func=None,
            )

            # Get data dependencies
            dependencies = pipeline.data_dependencies(target=target)
            print(f"  Found {len(dependencies)} dependencies for {git_reference}", file=sys.stderr)

            return algorithm_name, algorithm_version, dependencies

    def _get_download_mode(self, args):
        """
        Determine download mode based on flags.

        Returns:
            "list" - just show dependencies (default)
            "all" - download all dependencies
            ["dep1", "dep2"] - download specific dependencies
        """
        if args.download_all:
            return "all"
        elif args.download_dependencies:
            return args.download_dependencies
        else:
            return "list"

    def _filter_dependencies(self, dependencies: list[DataDependency], names: list[str]) -> list[DataDependency]:
        """Filter dependencies by data_prefix names."""
        filtered = [d for d in dependencies if d.data_prefix in names]

        # Warn about missing names
        found_names = {d.data_prefix for d in filtered}
        for name in names:
            if name not in found_names:
                logger.warning(f"Dependency '{name}' not found in algorithm definition")

        return filtered

    def _output_dependency_info_json(
        self,
        algorithm_name: str,
        algorithm_version: str,
        dependencies: list[DataDependency],
        args,
        local_data_path: Path,
        s3_client: S3Client,
        s3_bucket: str,
        s3_prefix: str,
    ):
        """Output dependency information as JSON to stdout, progress to stderr."""
        print("Enumerating S3 files and checking local status...", file=sys.stderr)

        dep_info_list = []
        total_dates = 0
        total_size_bytes = 0
        total_files = 0
        complete_count = 0
        partial_count = 0
        missing_count = 0

        for dep in dependencies:
            # Skip non-partitioned dependencies (state files without dates)
            if not dep.data_dates:
                print(f"Skipping non-partitioned dependency: {dep.data_prefix}", file=sys.stderr)
                continue

            sorted_dates = sorted(dep.data_dates)
            data_dates_str = [d.strftime("%Y-%m-%d") for d in sorted_dates]

            # Resolve S3 URI and normalize to always include data_prefix
            resolved_uri = resolve_data_dependency_s3_uri(
                dep, environment="production", default_s3_base=args.s3_base_dir
            )
            if resolved_uri:
                # resolved_uri already includes full path with data_prefix
                dep_s3_bucket, dep_s3_base_prefix = parse_s3_uri(resolved_uri)
            else:
                # Fallback: manually append data_prefix to base
                dep_s3_bucket = s3_bucket
                dep_s3_base_prefix = s3_prefix.rstrip("/") + "/" + dep.data_prefix

            s3_uri = format_s3_uri(dep_s3_bucket, dep_s3_base_prefix)
            local_path = str(local_data_path / dep.data_prefix)

            # Enumerate files across all dates
            dep_total_files = 0
            dep_total_bytes = 0
            dates_present = 0
            dates_missing = 0

            for dep_date in sorted_dates:
                date_str = dep_date.strftime("%Y-%m-%d")
                # dep_s3_base_prefix already includes data_prefix
                s3_date_prefix = build_s3_date_path(dep_s3_base_prefix, date_str=date_str)
                local_date_dir = local_data_path / dep.data_prefix / f"dt={date_str}"

                # List S3 files
                files, date_bytes = self._list_s3_files(s3_client, dep_s3_bucket, s3_date_prefix)
                dep_total_files += len(files)
                dep_total_bytes += date_bytes

                # Check local status
                if local_date_dir.exists():
                    local_files = self._local_file_relative_paths(local_date_dir)
                    target_rel_paths = {self._relative_key_under_prefix(f, s3_date_prefix) for f in files}
                    if local_files == target_rel_paths:
                        dates_present += 1
                    else:
                        dates_missing += 1
                else:
                    dates_missing += 1

            # Determine status
            if dates_present == len(sorted_dates):
                status = "complete"
                complete_count += 1
            elif dates_present > 0:
                status = "partial"
                partial_count += 1
            else:
                status = "missing"
                missing_count += 1

            # Estimate files per date (average)
            files_per_date = dep_total_files // len(sorted_dates) if sorted_dates else 0

            dep_info = {
                "data_prefix": dep.data_prefix,
                "data_type": dep.data_type,
                "data_dates": data_dates_str,
                "num_dates": len(sorted_dates),
                "s3_uri": s3_uri,
                "local_path": local_path,
                "estimated_size_bytes": dep_total_bytes,
                "estimated_size_human": self._format_bytes(dep_total_bytes),
                "files_per_date": files_per_date,
                "total_files": dep_total_files,
                "status": status,
                "dates_present": dates_present,
                "dates_missing": dates_missing,
            }

            dep_info_list.append(dep_info)
            total_dates += len(sorted_dates)
            total_size_bytes += dep_total_bytes
            total_files += dep_total_files

        output = {
            "algorithm_name": algorithm_name,
            "algorithm_version": algorithm_version,
            "git_reference": args.git_reference,
            "dependencies": dep_info_list,
            "summary": {
                "total_dependencies": len(dependencies),
                "total_dates": total_dates,
                "total_size_bytes": total_size_bytes,
                "total_size_human": self._format_bytes(total_size_bytes),
                "total_files": total_files,
                "complete_dependencies": complete_count,
                "partial_dependencies": partial_count,
                "missing_dependencies": missing_count,
            },
        }

        # Output JSON to stdout
        print(json.dumps(output, indent=2))

    def _create_download_plan(
        self,
        dependencies: list[DataDependency],
        local_data_path: Path,
        s3_client: S3Client,
        s3_bucket: str,
        s3_prefix: str,
        sample_ratio: float | None,
        skip_if_present: bool,
    ) -> tuple[list[DownloadPlanItem], list[MissingData], int]:
        """
        Create download plan.
        Returns (download_plan, missing_data, skipped_existing_count) where:
        - download_plan: list of (data_prefix, date_str, files_to_download, is_sampled)
        - missing_data: details for any dates that were missing entirely in S3
        - skipped_existing_count: number of directories skipped because they already exist
        """
        download_plan: list[DownloadPlanItem] = []
        missing_data: list[MissingData] = []
        skipped_existing = 0

        print("Checking existing data and planning downloads...", file=sys.stderr)

        for dep in dependencies:
            # Skip non-partitioned dependencies (state files without dates)
            if not dep.data_dates:
                print(f"Skipping non-partitioned dependency: {dep.data_prefix}", file=sys.stderr)
                continue

            # Resolve S3 URI and normalize to always include data_prefix
            resolved_uri = resolve_data_dependency_s3_uri(
                dep, environment="production", default_s3_base=f"s3://{s3_bucket}/{s3_prefix}"
            )
            if resolved_uri:
                # resolved_uri already includes full path with data_prefix
                dep_s3_bucket, dep_s3_base_prefix = parse_s3_uri(resolved_uri)
            else:
                # Fallback: manually append data_prefix to base
                dep_s3_bucket = s3_bucket
                dep_s3_base_prefix = s3_prefix.rstrip("/") + "/" + dep.data_prefix

            for dep_date in sorted(dep.data_dates):
                date_str = dep_date.strftime("%Y-%m-%d")
                local_date_dir = local_data_path / dep.data_prefix / f"dt={date_str}"
                # dep_s3_base_prefix already includes data_prefix
                s3_date_prefix = build_s3_date_path(dep_s3_base_prefix, date_str=date_str)
                s3_uri = format_s3_uri(dep_s3_bucket, s3_date_prefix)

                print(f"  > {dep.data_prefix} dt={date_str}", file=sys.stderr)
                print(f"      S3 path: {s3_uri}", file=sys.stderr)
                print(f"      Local path: {local_date_dir}", file=sys.stderr)

                # List files in S3 for this date
                files, total_bytes = self._list_s3_files(s3_client, dep_s3_bucket, s3_date_prefix)

                if not files:
                    print("      ✗ No files found at this location", file=sys.stderr)
                    missing_data.append(
                        MissingData(
                            data_prefix=dep.data_prefix,
                            date_str=date_str,
                            s3_path=s3_uri,
                            local_path=str(local_date_dir),
                            reason="No files found in S3",
                        )
                    )
                    continue

                availability_summary = f"{len(files)} files, {self._format_bytes(total_bytes)}"
                print(f"      ↓ Available: {availability_summary}", file=sys.stderr)

                # Determine target files (with sampling if requested)
                is_sampled = False
                if sample_ratio is not None and sample_ratio < 1.0:
                    target_files = _deterministic_sample(files, sample_ratio)
                    is_sampled = True
                else:
                    target_files = files

                # Determine what to download
                is_resuming = False
                if skip_if_present and local_date_dir.exists():
                    # Check completeness by comparing file lists
                    local_files = self._local_file_relative_paths(local_date_dir)
                    target_rel_paths = {self._relative_key_under_prefix(f, s3_date_prefix) for f in target_files}

                    if local_files == target_rel_paths:
                        # Complete - skip entirely
                        print(f"      ✓ Complete - {len(local_files)} files already downloaded", file=sys.stderr)
                        skipped_existing += 1
                        continue
                    elif local_files < target_rel_paths:
                        # Missing some files - resume
                        missing = target_rel_paths - local_files
                        print(
                            f"      ↻ Resuming - {len(missing)} of {len(target_files)} files still needed",
                            file=sys.stderr,
                        )
                        files_to_download = [
                            f for f in target_files if self._relative_key_under_prefix(f, s3_date_prefix) in missing
                        ]
                        is_resuming = True
                    else:
                        # Has extra files - re-download all targets
                        extra = local_files - target_rel_paths
                        print(
                            f"      ⚠ Local has {len(extra)} extra files not in target - will re-download all {len(target_files)} target files",
                            file=sys.stderr,
                        )
                        files_to_download = target_files
                else:
                    # Fresh download
                    files_to_download = target_files

                # Report plan
                if not is_resuming:
                    if is_sampled:
                        print(
                            f"      ↓ Sampling {len(files_to_download)} files ({sample_ratio*100:.1f}% of {len(files)})",
                            file=sys.stderr,
                        )
                    else:
                        print("      ↓ Full download planned for this date", file=sys.stderr)

                download_plan.append(
                    (dep_s3_bucket, dep.data_prefix, date_str, s3_date_prefix, files_to_download, is_sampled)
                )

        return download_plan, missing_data, skipped_existing

    def _list_s3_files(self, s3_client: S3Client, bucket: str, prefix: str) -> tuple[list[str], int]:
        """List all files in the given S3 prefix and total their size."""
        files: list[str] = []
        total_bytes = 0
        paginator = s3_client.get_paginator("list_objects_v2")

        # Let exceptions bubble up - fail fast on S3 errors
        normalized_prefix = prefix.lstrip("/")
        page_iterator = paginator.paginate(Bucket=bucket, Prefix=normalized_prefix)
        for page in page_iterator:
            for obj in page.get("Contents", []):
                key = obj["Key"]
                files.append(key)
                total_bytes += int(obj.get("Size", 0))

        files.sort()
        return files, total_bytes

    def _format_bytes(self, num_bytes: int) -> str:
        """Format byte counts for logs."""
        if num_bytes <= 0:
            return "0 B"

        units = ["B", "KB", "MB", "GB", "TB"]
        value = float(num_bytes)
        unit_index = 0

        while value >= 1024 and unit_index < len(units) - 1:
            value /= 1024
            unit_index += 1

        if unit_index == 0:
            return f"{int(value)} {units[unit_index]}"
        return f"{value:.1f} {units[unit_index]}"

    def _execute_downloads(
        self,
        download_plan: list[DownloadPlanItem],
        s3_client: S3Client,
        local_data_dir: str,
        scratch_dir: str,
        max_parallel_downloads: int,
    ):
        """Execute downloads with a single global concurrency cap."""

        max_workers = max(1, max_parallel_downloads)

        total_files = sum(len(files) for _, _, _, _, files, _ in download_plan)
        completed_files = 0
        completed_bytes = 0
        start_time = time.monotonic()

        print(
            f"\nDownloading {total_files} files (from {len(download_plan)} date directories) "
            f"with up to {max_workers} concurrent downloads...",
            file=sys.stderr,
        )

        # Create unique temp directory in scratch for downloads (prevents concurrent run conflicts)
        scratch_path = Path(scratch_dir)
        scratch_path.mkdir(parents=True, exist_ok=True)
        temp_download_dir = Path(tempfile.mkdtemp(dir=scratch_path, prefix="temp_downloads_"))

        try:
            per_date_stats = {}
            download_tasks = []

            for s3_bucket, data_prefix, date_str, s3_date_prefix, files_to_download, _is_sampled in download_plan:
                local_date_dir = Path(local_data_dir) / data_prefix / f"dt={date_str}"

                # Create directory if needed (don't delete existing for resume support)
                local_date_dir.mkdir(parents=True, exist_ok=True)

                per_date_stats[(data_prefix, date_str)] = {
                    "successful": 0,
                    "total": len(files_to_download),
                }

                for s3_key in files_to_download:
                    rel_path = self._relative_key_under_prefix(s3_key, s3_date_prefix)
                    local_file_path = local_date_dir / rel_path
                    download_tasks.append((s3_bucket, s3_key, local_file_path, data_prefix, date_str))

            def download_file(task):
                s3_bucket, s3_key, local_file_path, data_prefix, date_str = task
                local_file_path.parent.mkdir(parents=True, exist_ok=True)
                # Write to scratch dir temp file first, then atomically rename on success
                tmp_fd, tmp_path = tempfile.mkstemp(dir=temp_download_dir, suffix=".tmp")
                os.close(tmp_fd)
                try:
                    for attempt in range(1, DEFAULT_DOWNLOAD_MAX_RETRIES + 1):
                        try:
                            with open(tmp_path, "wb") as f:
                                s3_client.download_fileobj(s3_bucket, s3_key, f)
                            os.replace(tmp_path, local_file_path)
                            return data_prefix, date_str, True, local_file_path.stat().st_size
                        except Exception as e:
                            if attempt < DEFAULT_DOWNLOAD_MAX_RETRIES:
                                delay_seconds = DEFAULT_DOWNLOAD_RETRY_BASE_BACKOFF_SECONDS * (2 ** (attempt - 1))
                                logger.warning(
                                    f"Failed to download {s3_key} on attempt {attempt}/{DEFAULT_DOWNLOAD_MAX_RETRIES}: {e}. "
                                    f"Retrying in {delay_seconds:.1f}s"
                                )
                                time.sleep(delay_seconds)
                                continue
                            logger.error(
                                f"Failed to download {s3_key} after {DEFAULT_DOWNLOAD_MAX_RETRIES} attempts: {e}"
                            )
                            return data_prefix, date_str, False, 0
                finally:
                    # Clean up partial temp file
                    if os.path.exists(tmp_path):
                        try:
                            os.unlink(tmp_path)
                        except OSError:
                            pass

            failed_downloads = []

            with ThreadPoolExecutor(max_workers=max_workers) as executor:
                futures = [executor.submit(download_file, task) for task in download_tasks]

                for future in as_completed(futures):
                    data_prefix, date_str, ok, num_bytes = future.result()
                    if ok:
                        per_date_stats[(data_prefix, date_str)]["successful"] += 1
                        completed_files += 1
                        completed_bytes += num_bytes

                        # Log progress every 2 files or on completion
                        if completed_files % 2 == 0 or completed_files == total_files:
                            elapsed = max(0.001, time.monotonic() - start_time)
                            mbps = (completed_bytes / (1024 * 1024)) / elapsed
                            print(
                                f"  Progress: {completed_files}/{total_files} files downloaded ({completed_files*100//total_files}%) "
                                f"at {mbps:.1f} MB/s",
                                file=sys.stderr,
                            )
                    else:
                        failed_downloads.append((data_prefix, date_str))
        finally:
            # Always clean up temp download directory
            if temp_download_dir.exists():
                shutil.rmtree(temp_download_dir)

        if failed_downloads:
            print(f"\nFATAL: {len(failed_downloads)} file download(s) failed", file=sys.stderr)
            for data_prefix, date_str in failed_downloads:
                successful = per_date_stats[(data_prefix, date_str)]["successful"]
                total = per_date_stats[(data_prefix, date_str)]["total"]
                failed = total - successful
                print(f"  dt={date_str} for {data_prefix}: {failed}/{total} files failed", file=sys.stderr)
            sys.exit(1)

        print(f"\nCompleted: {completed_files}/{total_files} files downloaded successfully", file=sys.stderr)
