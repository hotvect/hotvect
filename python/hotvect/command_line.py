import argparse
import logging
import os
import sys
import tempfile
from pathlib import Path
from typing import List

import hotvect.hotvectjar
from hotvect.utils import execute_command_with_live_output

logger = logging.getLogger(__name__)


def run_performance_test(
    algorithm_jar: Path,
    algorithm_name: str,
    metadata_path: Path,
    source_path: Path,
    parameter: Path,
    samples: int,
    java_args: List[str],
):
    # Performance tests don't write useful things to dest
    temp_dest_file = tempfile.NamedTemporaryFile(delete=False).name

    try:
        cmd = ["java"]
        cmd.extend(java_args)
        if "-Xmx" not in java_args:
            cmd.append("-Xmx32g")
        if "-XX:+ExitOnOutOfMemoryError" not in java_args:
            cmd.append("-XX:+ExitOnOutOfMemoryError")
        if "-cp" not in java_args:
            cmd.extend(["-cp", str(hotvect.hotvectjar.HOTVECT_JAR_PATH)])

        cmd.extend(
            [
                "com.hotvect.offlineutils.commandline.Main",
                "--algorithm-jar",
                str(algorithm_jar),
                "--algorithm-definition",
                algorithm_name,
                "--meta-data",
                str(metadata_path),
                "--performance-test",
                "--source",
                str(source_path),
                "--dest",
                str(temp_dest_file),
            ]
        )
        if parameter is not None:
            cmd.extend(["--parameters", str(parameter)])
        cmd.extend(["--samples", str(samples)])
        cmd_str = " ".join(cmd)
        rc = execute_command_with_live_output(cmd_str, sys.stdout.write)

    finally:
        # Ensure the temporary file is deleted
        os.unlink(temp_dest_file)
    sys.exit(rc)


def get_arg_parser(
    description: str, require_parameter_file: bool, require_destination_file: bool
) -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument("--algorithm_jar", required=True, help="Path to the algorithm JAR file")
    parser.add_argument("--algorithm_name", required=True, help="Name of the algorithm")
    parser.add_argument("--metadata_path", required=True, help="Path to the metadata JSON file")
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
    parser.add_argument("--metadata_path", required=True, help="Path to the metadata JSON file")
    parser.add_argument("--source_path", required=True, help="Path to the source data")
    parser.add_argument("--parameter", required=True, help="Path to the parameters file", default=None)
    parser.add_argument("--samples", type=int, default=-1, help="Number of samples to process")

    args, java_args = parser.parse_known_args()
    run_performance_test(
        Path(args.algorithm_jar),
        args.algorithm_name,
        Path(args.metadata_path),
        Path(args.source_path),
        Path(args.parameter) if args.parameter else None,
        args.samples,
        java_args,
    )


if __name__ == "__main__":
    main()
