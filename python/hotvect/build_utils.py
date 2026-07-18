"""Build utilities for cloning git repos and building Maven JARs."""

import logging
import re
import shutil
from dataclasses import dataclass
from pathlib import Path
from typing import TextIO
from xml.etree import ElementTree

from hotvect.utils import capture_output, get_immediate_subdirectories, stream_output

logger = logging.getLogger(__name__)

_IGNORED_JAR_SUFFIXES = ("-sources.jar", "-javadoc.jar", "-test.jar", "-tests.jar")


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


def select_algorithm_jar(target_dir: Path, artifact: MavenArtifact) -> Path:
    artifact_prefix = f"{artifact.artifact_id}-{artifact.version}"
    matched_jars = sorted(path for path in target_dir.glob(f"{artifact_prefix}*.jar") if path.is_file())
    runtime_jars = [path for path in matched_jars if not path.name.endswith(_IGNORED_JAR_SUFFIXES)]

    shaded_jar = target_dir / f"{artifact_prefix}-shaded.jar"
    if shaded_jar in runtime_jars:
        return shaded_jar

    exact_jar = target_dir / f"{artifact_prefix}.jar"
    if exact_jar in runtime_jars and len(runtime_jars) == 1:
        return exact_jar

    if not runtime_jars:
        raise ValueError(
            f"No algorithm JAR file found in {target_dir} for {artifact.artifact_id}:{artifact.version}. "
            f"Matched non-runtime jars: {[path.name for path in matched_jars]}"
        )

    raise ValueError(
        f"Could not choose one algorithm JAR in {target_dir} for {artifact.artifact_id}:{artifact.version}. "
        f"Found runtime candidates: {[path.name for path in runtime_jars]}. "
        "Use the Maven shade classifier 'shaded' for attached fat jars."
    )


def clone_and_build_algorithm_jar(
    repo_url: str,
    git_reference: str,
    work_dir: Path,
    copy_jar_to: Path | None = None,
    *,
    progress_stream: TextIO,
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

    Git and Maven output is written to progress_stream while workflow messages are logged.

    Args:
        repo_url: Git repository URL (e.g., "https://github.com/org/repo.git")
        git_reference: Git reference to checkout (branch name, tag, or commit SHA)
        work_dir: Working directory where repo will be cloned (must exist)
        copy_jar_to: Optional directory to copy the JAR file. If None, JAR stays in target/.
        progress_stream: Stream receiving live Git and Maven output.

    Returns:
        BuildAlgorithmResult with all extracted metadata and paths

    Raises:
        ValueError: If JAR file not found or multiple JARs found
        subprocess.CalledProcessError: If git or maven commands fail

    Example:
        >>> from io import StringIO
        >>> progress_stream = StringIO()
        >>> result = clone_and_build_algorithm_jar(
        ...     repo_url="https://github.com/org/algo.git",
        ...     git_reference="v77.0.0",
        ...     work_dir=Path("/tmp/build"),
        ...     copy_jar_to=Path("/tmp/jars"),
        ...     progress_stream=progress_stream,
        ... )
        >>> print(f"Built {result.algorithm_name} v{result.algorithm_version}")
        >>> print(f"JAR at: {result.algorithm_jar_path}")
    """
    # Create source directory for cloning
    source_path = work_dir / "algo_source"
    source_path.mkdir(parents=True, exist_ok=True)

    # Clone repository
    logger.info(f"  Cloning {repo_url}...")
    stream_output(["git", "clone", repo_url], display_fun=progress_stream.write, cwd=str(source_path))

    # Find cloned directory (should be exactly one)
    cloned_dirs = get_immediate_subdirectories(source_path)
    if len(cloned_dirs) != 1:
        raise ValueError(f"Expected exactly one cloned directory, found: {cloned_dirs}")
    cloned_path = Path(next(iter(cloned_dirs)))

    # Checkout specific git reference
    logger.info(f"  Checking out {git_reference}...")
    stream_output(["git", "fetch", "--all", "--tags"], display_fun=progress_stream.write, cwd=str(cloned_path))
    stream_output(["git", "checkout", git_reference], display_fun=progress_stream.write, cwd=str(cloned_path))
    stream_output(["git", "clean", "-df"], display_fun=progress_stream.write, cwd=str(cloned_path))

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
        display_fun=progress_stream.write,
        cwd=str(cloned_path),
    )

    # Parse pom.xml to extract artifact metadata
    pom_path = cloned_path / "pom.xml"
    artifact = parse_pom_xml(pom_path)

    jar_path = select_algorithm_jar(cloned_path / "target", artifact)

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
