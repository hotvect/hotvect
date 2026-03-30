"""Show data dependency command for hv-ext CLI."""

import json
import logging
import sys
import tempfile
from datetime import date
from pathlib import Path

from hotvect.build_utils import clone_and_build_algorithm_jar
from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext
from hotvect.utils import resolve_data_dependency_s3_uri, sanitize_path_component

from .base import BaseCommand

logger = logging.getLogger(__name__)


class ShowDataDependencyCommand(BaseCommand):
    """Show algorithm data dependencies for SageMaker InputDataConfig construction."""

    @classmethod
    def register_parser(cls, subparsers):
        """Register the show-data-dependency command parser."""
        parser = subparsers.add_parser(
            "show-data-dependency",
            help="Show algorithm data dependencies as JSON for SageMaker InputDataConfig construction",
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
        parser.add_argument("--scratch-dir", required=True, help="Directory for temporary JAR builds (required)")
        parser.add_argument("--last-test-time", required=True, help="Last test time in YYYY-MM-DD format (required)")

        # Optional arguments
        parser.add_argument(
            "--algorithm-override",
            action="append",
            dest="algorithm_overrides",
            help="Path to JSON file containing algorithm configuration overrides. If one override provided, applies to all git references. If multiple overrides provided, applies to git references in order (optional)",
        )
        parser.add_argument(
            "--output",
            "-o",
            help="Output file path (default: stdout)",
        )

        return parser

    def execute(self, args):
        """Execute the show-data-dependency command."""
        # Parse git references and overrides
        git_references = []
        overrides = args.algorithm_overrides or []

        # Smart padding logic
        if len(overrides) == 1 and len(args.git_references) > 1:
            logger.info(
                f"Single algorithm override provided for {len(args.git_references)} git references. "
                f"Applying the same override to all references: {overrides[0]}"
            )
            while len(overrides) < len(args.git_references):
                overrides.append(overrides[0])
        elif len(overrides) > 1 and len(overrides) != len(args.git_references):
            print(
                f"Error: {len(overrides)} algorithm overrides provided but {len(args.git_references)} "
                f"git references specified. Numbers don't match.",
                file=sys.stderr,
            )
            sys.exit(2)
        else:
            while len(overrides) < len(args.git_references):
                overrides.append(None)

        for i, git_ref in enumerate(args.git_references):
            override = None
            if i < len(overrides) and overrides[i]:
                with open(overrides[i], "r") as f:
                    override = json.load(f)
                logger.info(f"Applied algorithm override from {overrides[i]} to git reference {git_ref}")
            else:
                logger.info(f"No algorithm override applied to git reference {git_ref}")
            git_references.append((git_ref, override))

        # Parse last test time
        last_test_time = date.fromisoformat(args.last_test_time)

        logger.info(f"Analyzing {len(git_references)} git references for data dependencies")

        # Collect dependencies from all git references
        all_dependencies_by_ref = {}

        for git_ref, override in git_references:
            logger.info(f"Analyzing dependencies for {git_ref}...")

            safe_ref = sanitize_path_component(git_ref)

            # Create temp directory under user-provided scratch dir
            scratch_base = Path(args.scratch_dir)
            scratch_base.mkdir(parents=True, exist_ok=True)

            with tempfile.TemporaryDirectory(prefix=f"show-dep-{safe_ref}-", dir=args.scratch_dir) as temp_dir:
                temp_path = Path(temp_dir)

                # Clone and build algorithm JAR
                result = clone_and_build_algorithm_jar(
                    repo_url=args.repo_url,
                    git_reference=git_ref,
                    work_dir=temp_path,
                    copy_jar_to=None,  # Keep JAR in temp directory
                )

                algorithm_name = result.algorithm_name
                algorithm_version = result.algorithm_version
                algorithm_jar = result.algorithm_jar_path

                # Create AlgorithmPipeline to extract dependencies
                context = AlgorithmPipelineContext(
                    algorithm_jar_path=algorithm_jar,
                    state_source_base_path=temp_path,
                    data_base_path=temp_path,
                    metadata_base_path=temp_path / "metadata",
                    output_base_path=temp_path / "output",
                    jvm_options=["-XX:MaxRAMPercentage=80"],
                    max_threads=1,
                )

                # Construct algorithm definition (with override if present)
                algorithm_definition = algorithm_name
                if override:
                    algorithm_definition = (algorithm_name, override)

                # Create pipeline and extract dependencies
                pipeline = AlgorithmPipeline(
                    algorithm_pipeline_context=context,
                    algorithm_definition=algorithm_definition,
                    last_test_time=last_test_time,
                    evaluation_func=None,
                )

                dependencies = pipeline.data_dependencies()
                logger.info(f"  Found {len(dependencies)} dependencies")

                # Convert dependencies to JSON-serializable format
                deps_list = []
                for dep in dependencies:
                    # Try to resolve S3 URI for production environment
                    resolved_s3_uri = None
                    try:
                        resolved_s3_uri = resolve_data_dependency_s3_uri(dep, environment="production")
                    except ValueError as e:
                        # Resolution failed - capture error for output
                        resolved_s3_uri = f"ERROR: {str(e)}"

                    dep_dict = {
                        "data_prefix": dep.data_prefix,
                        "data_dates": [d.isoformat() for d in dep.data_dates],
                        "data_type": dep.data_type,
                        "additional_properties": dep.additional_properties,
                        "resolved_s3_uri_production": resolved_s3_uri,  # Show resolved URI for transparency
                    }
                    deps_list.append(dep_dict)

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
