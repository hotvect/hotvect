"""Legacy multi-revision data-dependency inspection command."""

import argparse
import json
import logging
from datetime import date

from hotvect.utils import resolve_data_dependency_s3_uri

from .base import BaseCommand
from .download_data_dependency import DataDependencyCommand

logger = logging.getLogger(__name__)


class ShowDataDependencyCommand(BaseCommand):
    """Show algorithm data dependencies for SageMaker InputDataConfig construction."""

    @classmethod
    def register_parser(cls, subparsers):
        """Register the show-data-dependency command parser."""
        parser = subparsers.add_parser(
            "show-data-dependency",
            help="Show algorithm data dependencies as JSON for SageMaker InputDataConfig construction",
            epilog="""
Examples:
  # Show evaluation dependencies (default)
  hv-ext show-data-dependency --repo-url https://github.com/user/algorithm.git --git-reference v77.0.0 --scratch-dir ./temp --last-test-time 2025-04-30

  # Show prediction dependencies for local predict workflows
  hv-ext show-data-dependency --target predict --repo-url https://github.com/user/algorithm.git --git-reference v77.0.0 --scratch-dir ./temp --last-test-time 2025-04-30
            """,
            formatter_class=argparse.RawDescriptionHelpFormatter,
        )

        cls.add_arguments(parser)
        return parser

    @staticmethod
    def add_arguments(parser):
        parser.add_argument("--repo-url", required=True, help="Git repository URL for the algorithm (required)")
        parser.add_argument(
            "--git-reference",
            action="append",
            dest="git_references",
            required=True,
            help="Git reference (branch/commit) to analyze for data dependencies. Can be specified multiple times (required)",
        )
        parser.add_argument("--scratch-dir", required=True, help="Directory for temporary JAR builds (required)")
        parser.add_argument("--last-test-time", required=True, help="Last test time in YYYY-MM-DD format (required)")
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
            action="append",
            dest="algorithm_overrides",
            help="Path to JSON file containing an override for the corresponding --git-reference; repeat for each reference",
        )
        parser.add_argument(
            "--output",
            "-o",
            help="Output file path (default: stdout)",
        )

    def execute(self, args):
        """Execute the show-data-dependency command."""
        git_references: list[tuple[str, dict | None]] = []
        overrides = args.algorithm_overrides or []

        if overrides and len(overrides) != len(args.git_references):
            raise ValueError(
                "--algorithm-override must be provided once for each --git-reference when overrides are used"
            )

        for index, git_ref in enumerate(args.git_references):
            override_path = overrides[index] if overrides else None
            override = None
            if override_path:
                with open(override_path) as f:
                    override = json.load(f)
                logger.info(f"Applied algorithm override from {override_path} to git reference {git_ref}")
            else:
                logger.info(f"No algorithm override applied to git reference {git_ref}")
            git_references.append((git_ref, override))

        # Parse last test time
        last_test_time = date.fromisoformat(args.last_test_time)

        logger.info(f"Analyzing {len(git_references)} git references for data dependencies (target={args.target})")

        all_dependencies_by_ref = {}
        dependency_command = DataDependencyCommand()

        for git_ref, override in git_references:
            logger.info(f"Analyzing dependencies for {git_ref}...")
            algorithm_name, algorithm_version, dependencies = dependency_command._get_data_dependencies(
                args.repo_url,
                git_ref,
                args.scratch_dir,
                last_test_time,
                args.target,
                override,
            )
            deps_list = []
            for dep in dependencies:
                resolved_s3_uri = resolve_data_dependency_s3_uri(dep, environment="production")
                if resolved_s3_uri is None:
                    raise ValueError(f"Could not resolve production S3 URI for dependency: {dep.data_prefix}")
                deps_list.append(
                    {
                        "data_prefix": dep.data_prefix,
                        "data_dates": [d.isoformat() for d in dep.data_dates],
                        "data_type": dep.data_type,
                        "additional_properties": dep.additional_properties,
                        "resolved_s3_uri_production": resolved_s3_uri,
                    }
                )
            all_dependencies_by_ref[git_ref] = {
                "algorithm_name": algorithm_name,
                "algorithm_version": algorithm_version,
                "dependencies": deps_list,
            }

        # Construct output JSON
        output = {
            "repo_url": args.repo_url,
            "last_test_time": args.last_test_time,
            "git_references": all_dependencies_by_ref,
        }

        # Output to file or stdout
        output_json = json.dumps(output, indent=2)
        if args.output:
            with open(args.output, "w") as f:
                f.write(output_json)
            logger.info(f"Dependency information written to {args.output}")
        else:
            print(output_json)

        logger.info("Dependency analysis completed")
