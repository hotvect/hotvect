"""Show data dependency command for hv-extra CLI."""

import glob
import json
import logging
import os
import re
import sys
import tempfile
from datetime import date
from pathlib import Path
from xml.etree import ElementTree

from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext
from hotvect.utils import get_immediate_subdirectories, runshell

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

            # Sanitize git ref for use in directory name (replace / with _)
            safe_ref = git_ref.replace("/", "_").replace("\\", "_")

            # Create temp directory under user-provided scratch dir
            scratch_base = Path(args.scratch_dir)
            scratch_base.mkdir(parents=True, exist_ok=True)

            with tempfile.TemporaryDirectory(prefix=f"show-dep-{safe_ref}-", dir=args.scratch_dir) as temp_dir:
                temp_path = Path(temp_dir)

                # Clone repository
                algo_source_path = temp_path / "algo_source"
                algo_source_path.mkdir()
                logger.info(f"  Cloning {args.repo_url}...")
                runshell(f"cd {algo_source_path} && git clone {args.repo_url}", shell=True)

                # Find cloned directory
                cloned_dirs = get_immediate_subdirectories(algo_source_path)
                if len(cloned_dirs) != 1:
                    raise ValueError(f"Expected one directory after clone, got: {cloned_dirs}")
                cloned_path = next(iter(cloned_dirs))

                # Checkout git reference
                logger.info(f"  Checking out {git_ref}...")
                runshell(
                    f"cd {cloned_path} && git fetch --all --tags && git checkout {git_ref} && git clean -df",
                    shell=True,
                )

                # Build JAR
                logger.info("  Building JAR...")
                runshell(f"cd {cloned_path} && mvn clean package -DskipTests -B", shell=True)

                # Extract algorithm metadata from pom.xml
                pom_path = cloned_path / "pom.xml"
                xml_root = ElementTree.parse(pom_path).getroot()
                ns = re.match(r"{.*}", xml_root.tag).group(0)
                algorithm_name = xml_root.find(ns + "artifactId").text.strip()
                algorithm_version = xml_root.find(ns + "version").text.strip()

                # Find JAR file
                algorithm_jars = [
                    file
                    for file in glob.glob(str(cloned_path / "target" / f"{algorithm_name}-{algorithm_version}*.jar"))
                    if os.path.isfile(file)
                ]
                if len(algorithm_jars) != 1:
                    raise ValueError(f"Expected one JAR, found: {algorithm_jars}")
                algorithm_jar = Path(algorithm_jars[0])

                # Create AlgorithmPipeline to extract dependencies
                context = AlgorithmPipelineContext(
                    algorithm_jar_path=algorithm_jar,
                    state_soruce_base_path=temp_path,
                    data_base_path=temp_path,
                    metadata_base_path=temp_path / "metadata",
                    output_base_path=temp_path / "output",
                    jvm_options=["-Xmx8g"],
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
                    dep_dict = {
                        "data_prefix": dep.data_prefix,
                        "data_dates": [d.isoformat() for d in dep.data_dates],
                        "data_type": dep.data_type,
                        "additional_properties": dep.additional_properties,
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
