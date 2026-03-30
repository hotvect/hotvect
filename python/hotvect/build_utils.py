"""Build utilities for cloning git repos and building Maven JARs."""

import glob
import logging
import os
import re
import shutil
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Optional
from xml.etree import ElementTree

from hotvect.utils import capture_output, get_immediate_subdirectories, stream_output

logger = logging.getLogger(__name__)


@dataclass
class MavenArtifact:
    """Maven artifact metadata from pom.xml."""

    artifact_id: str
    version: str


@dataclass
class BuildAlgorithmResult:
    """Result of cloning and building an algorithm JAR."""

    algorithm_name: str
    algorithm_version: str
    algorithm_jar_path: Path
    git_commit_hash: str
    cloned_repo_path: Path


def parse_pom_xml(pom_path: Path) -> MavenArtifact:
    """
    Parse Maven pom.xml to extract artifactId and version.

    Args:
        pom_path: Path to pom.xml file

    Returns:
        MavenArtifact with extracted metadata

    Example:
        >>> artifact = parse_pom_xml(Path("/path/to/pom.xml"))
        >>> print(f"{artifact.artifact_id}:{artifact.version}")
    """
    xml_root = ElementTree.parse(pom_path).getroot()
    ns = re.match(r"{.*}", xml_root.tag).group(0)
    artifact_id = xml_root.find(ns + "artifactId").text.strip()
    version = xml_root.find(ns + "version").text.strip()

    return MavenArtifact(artifact_id=artifact_id, version=version)


def clone_and_build_algorithm_jar(
    repo_url: str,
    git_reference: str,
    work_dir: Path,
    copy_jar_to: Optional[Path] = None,
) -> BuildAlgorithmResult:
    """
    Clone git repository, checkout reference, build JAR, and extract metadata.

    This function handles the complete workflow of:
    1. Cloning a git repository
    2. Checking out a specific git reference (branch, tag, or commit)
    3. Building the project with Maven
    4. Parsing pom.xml to extract artifact metadata
    5. Finding the built JAR file
    6. Optionally copying the JAR to a destination directory

    Progress is logged to logger.info() for CLI output.

    Args:
        repo_url: Git repository URL (e.g., "https://github.com/org/repo.git")
        git_reference: Git reference to checkout (branch name, tag, or commit SHA)
        work_dir: Working directory where repo will be cloned (must exist)
        copy_jar_to: Optional directory to copy the JAR file. If None, JAR stays in target/.

    Returns:
        BuildAlgorithmResult with all extracted metadata and paths

    Raises:
        ValueError: If JAR file not found or multiple JARs found
        subprocess.CalledProcessError: If git or maven commands fail

    Example:
        >>> result = clone_and_build_algorithm_jar(
        ...     repo_url="https://github.com/org/algo.git",
        ...     git_reference="v77.0.0",
        ...     work_dir=Path("/tmp/build"),
        ...     copy_jar_to=Path("/tmp/jars"),
        ... )
        >>> print(f"Built {result.algorithm_name} v{result.algorithm_version}")
        >>> print(f"JAR at: {result.algorithm_jar_path}")
    """
    # Create source directory for cloning
    source_path = work_dir / "algo_source"
    source_path.mkdir(parents=True, exist_ok=True)

    # Clone repository
    logger.info(f"  Cloning {repo_url}...")
    stream_output(["git", "clone", repo_url], display_fun=sys.stdout.write, cwd=str(source_path))

    # Find cloned directory (should be exactly one)
    cloned_dirs = get_immediate_subdirectories(source_path)
    if len(cloned_dirs) != 1:
        raise ValueError(f"Expected exactly one cloned directory, found: {cloned_dirs}")
    cloned_path = Path(next(iter(cloned_dirs)))

    # Checkout specific git reference
    logger.info(f"  Checking out {git_reference}...")
    stream_output(["git", "fetch", "--all", "--tags"], display_fun=sys.stdout.write, cwd=str(cloned_path))
    stream_output(["git", "checkout", git_reference], display_fun=sys.stdout.write, cwd=str(cloned_path))
    stream_output(["git", "clean", "-df"], display_fun=sys.stdout.write, cwd=str(cloned_path))

    # Get git commit hash for this reference
    git_commit_hash = capture_output(
        ["git", "rev-parse", "HEAD"],
        cwd=str(cloned_path),
    )["stdout"].strip()

    # Build project with Maven
    logger.info("  Building JAR...")
    # `-DskipTests` skips running tests but still compiles them.
    # For building the algorithm JAR we don't need test compilation.
    stream_output(
        ["mvn", "clean", "package", "-Dmaven.test.skip=true", "-B"],
        display_fun=sys.stdout.write,
        cwd=str(cloned_path),
    )

    # Parse pom.xml to extract artifact metadata
    pom_path = cloned_path / "pom.xml"
    artifact = parse_pom_xml(pom_path)

    # Find built JAR file in target directory
    jar_pattern = os.path.join(cloned_path, "target", f"{artifact.artifact_id}-{artifact.version}*.jar")
    found_jars = [file for file in glob.glob(jar_pattern) if os.path.isfile(file)]

    if len(found_jars) != 1:
        raise ValueError(f"Expected exactly one JAR file matching pattern '{jar_pattern}', found: {found_jars}")

    jar_path = Path(found_jars[0])

    # Copy JAR to destination if requested
    if copy_jar_to:
        copy_jar_to.mkdir(parents=True, exist_ok=True)
        destination_jar = copy_jar_to / jar_path.name
        shutil.copyfile(jar_path, destination_jar)
        jar_path = destination_jar

    return BuildAlgorithmResult(
        algorithm_name=artifact.artifact_id,
        algorithm_version=artifact.version,
        algorithm_jar_path=jar_path,
        git_commit_hash=git_commit_hash,
        cloned_repo_path=cloned_path,
    )
