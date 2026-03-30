"""Unit tests for build_utils module."""

from pathlib import Path
from unittest.mock import MagicMock, patch

from hotvect.build_utils import clone_and_build_algorithm_jar, parse_pom_xml


def test_parse_pom_xml(tmp_path):
    """Test parsing pom.xml file."""
    pom_content = """<?xml version="1.0" encoding="UTF-8"?>
    <project xmlns="http://maven.apache.org/POM/4.0.0">
        <artifactId>test-algorithm</artifactId>
        <version>1.0.0</version>
    </project>
    """
    pom_file = tmp_path / "pom.xml"
    pom_file.write_text(pom_content)

    artifact = parse_pom_xml(pom_file)
    assert artifact.artifact_id == "test-algorithm"
    assert artifact.version == "1.0.0"


@patch("hotvect.build_utils.capture_output")
@patch("hotvect.build_utils.stream_output")
@patch("hotvect.build_utils.get_immediate_subdirectories")
@patch("hotvect.build_utils.ElementTree.parse")
@patch("hotvect.build_utils.glob.glob")
def test_clone_and_build_algorithm_jar(
    mock_glob, mock_parse, mock_get_dirs, mock_stream_output, mock_capture_output, tmp_path
):
    """Test complete clone and build workflow."""
    # Setup mocks
    cloned_path = tmp_path / "algo_source" / "my-repo"
    cloned_path.mkdir(parents=True)
    mock_get_dirs.return_value = [cloned_path]
    mock_capture_output.return_value = {"stdout": "abc123def456"}

    # Mock POM parsing
    mock_xml = MagicMock()
    mock_xml.tag = "{http://maven.apache.org/POM/4.0.0}project"
    mock_artifactId = MagicMock()
    mock_artifactId.text = "test-algo"
    mock_version = MagicMock()
    mock_version.text = "2.0.0"
    mock_xml.find.side_effect = [mock_artifactId, mock_version]
    mock_parse.return_value.getroot.return_value = mock_xml

    # Mock JAR file
    jar_file = cloned_path / "target" / "test-algo-2.0.0.jar"
    jar_file.parent.mkdir(parents=True)
    jar_file.touch()
    mock_glob.return_value = [str(jar_file)]

    # Execute
    result = clone_and_build_algorithm_jar(
        repo_url="https://github.com/test/repo.git",
        git_reference="v2.0.0",
        work_dir=tmp_path,
        copy_jar_to=None,
    )

    # Verify
    assert result.algorithm_name == "test-algo"
    assert result.algorithm_version == "2.0.0"
    assert result.git_commit_hash == "abc123def456"
    assert "test-algo-2.0.0.jar" in str(result.algorithm_jar_path)

    # Verify git commands were called
    assert mock_stream_output.call_count >= 3  # clone, checkout, mvn
    mock_capture_output.assert_called_once()  # git rev-parse


@patch("hotvect.build_utils.capture_output")
@patch("hotvect.build_utils.stream_output")
@patch("hotvect.build_utils.get_immediate_subdirectories")
@patch("hotvect.build_utils.ElementTree.parse")
@patch("hotvect.build_utils.glob.glob")
def test_clone_and_build_handles_string_paths(
    mock_glob, mock_parse, mock_get_dirs, mock_stream_output, mock_capture_output, tmp_path
):
    """Test that function handles string paths from get_immediate_subdirectories."""
    # Setup: get_immediate_subdirectories returns strings, not Path objects
    cloned_path_str = str(tmp_path / "algo_source" / "my-repo")
    Path(cloned_path_str).mkdir(parents=True)
    mock_get_dirs.return_value = [cloned_path_str]  # Return STRING, not Path
    mock_capture_output.return_value = {"stdout": "abc123"}

    # Mock POM parsing
    mock_xml = MagicMock()
    mock_xml.tag = "{http://maven.apache.org/POM/4.0.0}project"
    mock_artifactId = MagicMock()
    mock_artifactId.text = "test"
    mock_version = MagicMock()
    mock_version.text = "1.0"
    mock_xml.find.side_effect = [mock_artifactId, mock_version]
    mock_parse.return_value.getroot.return_value = mock_xml

    # Mock JAR file
    jar_file = Path(cloned_path_str) / "target" / "test-1.0.jar"
    jar_file.parent.mkdir(parents=True)
    jar_file.touch()
    mock_glob.return_value = [str(jar_file)]

    # Execute - should not raise TypeError when doing cloned_path / "pom.xml"
    result = clone_and_build_algorithm_jar(
        repo_url="https://github.com/test/repo.git",
        git_reference="v1.0",
        work_dir=tmp_path,
        copy_jar_to=None,
    )

    # Verify cloned_repo_path is a Path object
    assert isinstance(result.cloned_repo_path, Path)
    assert "my-repo" in str(result.cloned_repo_path)
