"""Download data dependency command for hv-extra CLI."""

import glob
import json
import logging
import os
import random
import re
import shutil
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date
from pathlib import Path
from typing import List, Tuple
from urllib.parse import urlparse
from xml.etree import ElementTree

import boto3
from mypy_boto3_s3 import S3Client

from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext, DataDependency
from hotvect.utils import get_boto_session_after_assuming_role, runshell

from .base import BaseCommand

logger = logging.getLogger(__name__)


class DownloadDataDependencyCommand(BaseCommand):
    """Download data dependencies for local train/backtest operations."""

    @classmethod
    def register_parser(cls, subparsers):
        """Register the download-data-dependency command parser."""
        parser = subparsers.add_parser(
            "download-data-dependency",
            help="Download data dependencies required for local train/backtest operations",
        )

        # Required arguments
        parser.add_argument("--repo-url", required=True, help="Git repository URL for the algorithm (required)")
        parser.add_argument(
            "--git-reference",
            action="append",
            dest="git_references",
            required=True,
            help="Git reference (branch/commit) to analyze for data dependencies. Can be specified multiple times (required)",
        )
        parser.add_argument(
            "--s3-base-dir",
            required=True,
            help='S3 base directory where training data is stored, e.g., "s3://bucket/path/" (required)',
        )
        parser.add_argument(
            "--local-data-dir",
            required=True,
            help="Local directory where data will be downloaded (required)",
        )
        parser.add_argument("--scratch-dir", required=True, help="Directory for temporary JAR builds (required)")
        parser.add_argument("--last-test-time", required=True, help="Last test time in YYYY-MM-DD format (required)")

        # Optional arguments
        parser.add_argument(
            "--number-of-runs",
            type=int,
            default=1,
            help="Number of runs (affects data requirements, default: 1)",
        )
        parser.add_argument(
            "--algorithm-override",
            help="Path to JSON file containing algorithm configuration overrides (optional)",
        )
        parser.add_argument("--role-arn", help="AWS role ARN to assume for S3 access (optional)")
        parser.add_argument(
            "--sample-ratio",
            type=float,
            help="Fraction of files to download per date directory (e.g., 0.1 = 10%%, 0.05 = 5%%) (optional)",
        )
        parser.add_argument(
            "--no-skip-if-present",
            action="store_true",
            help="Re-download data even if dt directories already exist locally (default: skip existing)",
        )
        parser.add_argument(
            "--max-parallel-dates",
            type=int,
            default=5,
            help="Maximum number of date directories to download in parallel (default: 5)",
        )
        parser.add_argument(
            "--max-parallel-files",
            type=int,
            default=10,
            help="Maximum number of files to download in parallel per date directory (default: 10)",
        )

        return parser

    def execute(self, args):
        """Execute the download data dependency operation."""
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

            print("Analyzing data dependencies...")
            print(f"  Git references: {args.git_references}")
            print(f"  S3 source: {args.s3_base_dir}")
            print(f"  Local destination: {args.local_data_dir}")
            print(f"  Last test time: {last_test_time}")
            if args.sample_ratio:
                print(f"  Sampling: {args.sample_ratio*100:.1f}% of files per date directory")
            if args.role_arn:
                print(f"  Role ARN: {args.role_arn}")
            print()

            # Parse S3 location
            s3_uri_parsed = urlparse(args.s3_base_dir)
            s3_bucket = s3_uri_parsed.netloc
            s3_prefix = s3_uri_parsed.path.strip("/")

            # Setup S3 client
            session = get_boto_session_after_assuming_role(args.role_arn) if args.role_arn else boto3.Session()
            s3_client = session.client("s3")

            # Get all data dependencies from all git references
            all_dependencies = self._get_all_data_dependencies(
                args.repo_url,
                args.git_references,
                args.scratch_dir,
                last_test_time,
                args.number_of_runs,
                args.algorithm_override,
            )

            print(f"Found {len(all_dependencies)} unique data dependencies:")
            for dep in all_dependencies:
                print(f"  - {dep.data_prefix}: {len(dep.data_dates)} dates ({dep.data_type})")
            print()

            # Plan downloads
            download_plan = self._create_download_plan(
                all_dependencies,
                local_data_path,
                s3_client,
                s3_bucket,
                s3_prefix,
                args.sample_ratio,
                not args.no_skip_if_present,
            )

            if not download_plan:
                print("No data to download - all required data already exists locally.")
                return

            # Execute downloads in parallel
            self._execute_downloads(
                download_plan,
                s3_client,
                s3_bucket,
                args.local_data_dir,
                args.max_parallel_dates,
                args.max_parallel_files,
            )

            print("\nDownload completed successfully!")

        except Exception as e:
            print(f"Error during download: {e}", file=sys.stderr)
            sys.exit(1)

    def _get_all_data_dependencies(
        self,
        repo_url: str,
        git_references: List[str],
        scratch_dir: str,
        last_test_time: date,
        number_of_runs: int,
        algorithm_override: str,
    ) -> List[DataDependency]:
        """Get all unique data dependencies from all git references."""
        all_deps = []
        seen_deps = set()

        for git_ref in git_references:
            print(f"Analyzing dependencies for {git_ref}...")

            # Create temporary directory for this git reference
            with tempfile.TemporaryDirectory(prefix=f"git-{git_ref}-") as temp_dir:
                temp_path = Path(temp_dir)

                # Clone and build algorithm JAR
                algo_source_path = temp_path / "algo_source"
                algo_source_path.mkdir()

                print(f"  Cloning {repo_url}...")
                runshell(f"cd {algo_source_path} && git clone {repo_url}", shell=True)

                # Find cloned directory
                cloned_dirs = [d for d in algo_source_path.iterdir() if d.is_dir()]
                if len(cloned_dirs) != 1:
                    raise ValueError(f"Expected exactly one cloned directory, found: {cloned_dirs}")
                cloned_path = cloned_dirs[0]

                # Checkout specific reference
                print(f"  Checking out {git_ref}...")
                runshell(
                    f"cd {cloned_path} && git fetch --all --tags && git checkout {git_ref} && git clean -df",
                    shell=True,
                )

                # Build JAR
                print(f"  Building JAR for {git_ref}...")
                runshell(f"cd {cloned_path} && mvn clean package -DskipTests -B", shell=True)

                # Extract algorithm name and version from pom.xml (following backtest pattern)
                pom_path = cloned_path / "pom.xml"
                xml_root = ElementTree.parse(pom_path).getroot()
                ns = re.match(r"{.*}", xml_root.tag).group(0)
                algorithm_name = xml_root.find(ns + "artifactId").text.strip()
                algorithm_version = xml_root.find(ns + "version").text.strip()

                # Find the specific JAR file (following backtest pattern)
                algorithm_jars = [
                    file
                    for file in glob.glob(
                        os.path.join(
                            cloned_path,
                            "target",
                            f"{algorithm_name}-{algorithm_version}*.jar",
                        )
                    )
                    if os.path.isfile(file)
                ]
                if len(algorithm_jars) != 1:
                    raise ValueError(f"Algorithm JAR not found or there are more than one! {algorithm_jars}")
                algorithm_jar = Path(algorithm_jars[0])

                # Create algorithm pipeline context
                context = AlgorithmPipelineContext(
                    algorithm_jar_path=algorithm_jar,
                    state_source_base_path=Path(scratch_dir),
                    data_base_path=Path(scratch_dir),
                    metadata_base_path=Path(scratch_dir) / "metadata",
                    output_base_path=Path(scratch_dir) / "output",
                    jvm_options=["-Xmx8g"],
                    max_threads=1,
                )

                # Parse algorithm override if provided
                algorithm_definition = algorithm_name  # Use extracted algorithm name from pom.xml
                if algorithm_override:
                    with open(algorithm_override, "r") as f:
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
                dependencies = pipeline.data_dependencies()
                print(f"  Found {len(dependencies)} dependencies for {git_ref}")

                # Add unique dependencies
                for dep in dependencies:
                    # Create a key for deduplication
                    dep_key = (dep.data_prefix, tuple(sorted(dep.data_dates)), dep.data_type)
                    if dep_key not in seen_deps:
                        seen_deps.add(dep_key)
                        all_deps.append(dep)

        return all_deps

    def _create_download_plan(
        self,
        dependencies: List[DataDependency],
        local_data_path: Path,
        s3_client: S3Client,
        s3_bucket: str,
        s3_prefix: str,
        sample_ratio: float,
        skip_if_present: bool,
    ) -> List[Tuple[str, str, List[str], bool]]:
        """
        Create download plan.
        Returns list of (data_prefix, date_str, files_to_download, is_sampled).
        """
        download_plan = []

        print("Checking existing data and planning downloads...")

        for dep in dependencies:
            for dep_date in dep.data_dates:
                date_str = dep_date.strftime("%Y-%m-%d")
                local_date_dir = local_data_path / dep.data_prefix / f"dt={date_str}"

                # Check if directory exists and skip if requested
                if skip_if_present and local_date_dir.exists():
                    print(f"  ✓ Skipping dt={date_str} for {dep.data_prefix} - directory exists")
                    continue

                # List files in S3 for this date
                s3_date_prefix = f"{s3_prefix}/{dep.data_prefix}/dt={date_str}/"
                json_files = self._list_s3_part_files(s3_client, s3_bucket, s3_date_prefix)

                if not json_files:
                    print(f"  ⚠ No JSON files found for dt={date_str} in {dep.data_prefix}")
                    continue

                # Apply sampling if requested
                files_to_download = json_files
                is_sampled = False
                if sample_ratio and sample_ratio < 1.0:
                    sample_count = max(1, int(len(json_files) * sample_ratio))
                    files_to_download = random.sample(json_files, sample_count)
                    is_sampled = True
                    print(
                        f"  ↓ Will download dt={date_str} for {dep.data_prefix} - sampling {len(files_to_download)} of {len(json_files)} files ({sample_ratio*100:.1f}%)"
                    )
                else:
                    print(f"  ↓ Will download dt={date_str} for {dep.data_prefix} - {len(files_to_download)} files")

                download_plan.append((dep.data_prefix, date_str, files_to_download, is_sampled))

        return download_plan

    def _list_s3_part_files(self, s3_client: S3Client, bucket: str, prefix: str) -> List[str]:
        """List all files containing 'json' in their name in S3 prefix."""
        json_files = []
        paginator = s3_client.get_paginator("list_objects_v2")

        try:
            page_iterator = paginator.paginate(Bucket=bucket, Prefix=prefix)
            for page in page_iterator:
                for obj in page.get("Contents", []):
                    key = obj["Key"]
                    filename = key.split("/")[-1]
                    if "json" in filename.lower():
                        json_files.append(key)
        except Exception as e:
            logger.warning(f"Failed to list S3 objects for {prefix}: {e}")

        return json_files

    def _execute_downloads(
        self,
        download_plan: List[Tuple[str, str, List[str], bool]],
        s3_client: S3Client,
        s3_bucket: str,
        local_data_dir: str,
        max_parallel_dates: int,
        max_parallel_files: int,
    ):
        """Execute downloads in parallel."""
        print(f"\nDownloading {len(download_plan)} date directories in parallel...")

        total_files = sum(len(files) for _, _, files, _ in download_plan)
        completed_files = 0

        def download_date_directory(plan_item):
            data_prefix, date_str, files_to_download, is_sampled = plan_item
            local_date_dir = Path(local_data_dir) / data_prefix / f"dt={date_str}"

            # Remove existing directory if it exists (for re-download)
            if local_date_dir.exists():
                shutil.rmtree(local_date_dir)

            # Create directory
            local_date_dir.mkdir(parents=True, exist_ok=True)

            # Download files in parallel within this date directory
            def download_file(s3_key):
                filename = s3_key.split("/")[-1]
                local_file_path = local_date_dir / filename
                try:
                    s3_client.download_file(s3_bucket, s3_key, str(local_file_path))
                    return True
                except Exception as e:
                    logger.error(f"Failed to download {s3_key}: {e}")
                    return False

            # Download files for this date directory
            successful_downloads = 0
            with ThreadPoolExecutor(max_workers=max_parallel_files) as file_executor:
                file_futures = {file_executor.submit(download_file, s3_key): s3_key for s3_key in files_to_download}

                for future in as_completed(file_futures):
                    if future.result():
                        successful_downloads += 1

            return data_prefix, date_str, successful_downloads, len(files_to_download)

        # Execute date directory downloads in parallel
        with ThreadPoolExecutor(max_workers=max_parallel_dates) as executor:
            futures = {executor.submit(download_date_directory, plan_item): plan_item for plan_item in download_plan}

            for future in as_completed(futures):
                data_prefix, date_str, successful, total = future.result()
                completed_files += successful

                if successful == total:
                    status = "✓"
                elif successful > 0:
                    status = "⚠"
                else:
                    status = "✗"

                print(f"  {status} dt={date_str} for {data_prefix}: {successful}/{total} files")

        print(f"\nCompleted: {completed_files}/{total_files} files downloaded successfully")
