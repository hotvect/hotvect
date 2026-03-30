import argparse
import logging
import subprocess
import sys
from pathlib import Path
from typing import List

import hotvect.hotvectjar
from hotvect.utils import stream_output

logger = logging.getLogger(__name__)


def run_performance_test(
    algorithm_jar: Path,
    algorithm_name: str,
    metadata_dir: Path,
    source_path: Path,
    parameter: Path,
    samples: int,
    java_args: List[str],
):
    cmd = ["java"]
    cmd.extend(java_args)
    if not any(arg.startswith("-Xmx") or arg.startswith("-XX:MaxRAMPercentage") for arg in java_args):
        cmd.append("-XX:MaxRAMPercentage=80")
    if "-XX:+ExitOnOutOfMemoryError" not in java_args:
        cmd.append("-XX:+ExitOnOutOfMemoryError")
    if "-cp" not in java_args:
        cmd.extend(["-cp", str(hotvect.hotvectjar.HOTVECT_JAR_PATH)])

    cmd.extend(
        [
            "com.hotvect.offlineutils.commandline.Main",
            "performance-test",
            "--algorithm-jar",
            str(algorithm_jar),
            "--algorithm-definition",
            algorithm_name,
            "--metadata-path",
            str(metadata_dir),
            "--source",
            str(source_path),
        ]
    )
    if parameter is not None:
        cmd.extend(["--parameters", str(parameter)])
    cmd.extend(["--samples", str(samples)])

    try:
        rc = stream_output(cmd, sys.stdout.write)
    except subprocess.CalledProcessError as e:
        # Command failed - print error and exit with the underlying command's return code
        # Note: stdout/stderr already streamed live, this just confirms the failure
        print(f"Command failed with exit code {e.returncode}", file=sys.stderr)
        rc = e.returncode
    sys.exit(rc)


def get_arg_parser(
    description: str, require_parameter_file: bool, require_destination_file: bool
) -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument("--algorithm_jar", required=True, help="Path to the algorithm JAR file")
    parser.add_argument("--algorithm_name", required=True, help="Name of the algorithm")
    parser.add_argument(
        "--metadata-path", required=True, help="Path to the metadata directory (Java writes metadata.json inside)"
    )
    parser.add_argument("--source_path", required=True, help="Path to the source data")
    parser.add_argument(
        "--parameter", required=require_parameter_file, help="Path to the parameters file", default=None
    )
    parser.add_argument(
        "--dest_path", required=require_destination_file, help="Path to the destination file", default=None
    )
    parser.add_argument("--samples", type=int, default=-1, help="Number of samples to process")
    return parser


def main():
    parser = argparse.ArgumentParser(description="Run performance test")
    parser.add_argument("--algorithm_jar", required=True, help="Path to the algorithm JAR file")
    parser.add_argument("--algorithm_name", required=True, help="Name of the algorithm")
    parser.add_argument(
        "--metadata-path", required=True, help="Path to the metadata directory (Java writes metadata.json inside)"
    )
    parser.add_argument("--source_path", required=True, help="Path to the source data")
    parser.add_argument("--parameter", required=True, help="Path to the parameters file", default=None)
    parser.add_argument("--samples", type=int, default=-1, help="Number of samples to process")

    args, java_args = parser.parse_known_args()
    metadata_dir = Path(args.metadata_path)
    metadata_dir.mkdir(parents=True, exist_ok=True)
    run_performance_test(
        Path(args.algorithm_jar),
        args.algorithm_name,
        metadata_dir,
        Path(args.source_path),
        Path(args.parameter) if args.parameter else None,
        args.samples,
        java_args,
    )


if __name__ == "__main__":
    main()
