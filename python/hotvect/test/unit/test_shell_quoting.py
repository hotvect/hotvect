"""
Tests for shell command quoting to prevent injection vulnerabilities.

This tests the fix for unsafe shell command construction where paths containing
spaces or shell metacharacters would break git/mvn operations.
"""

import shlex
from pathlib import Path
from unittest.mock import MagicMock, patch


def test_shlex_quote_preserves_normal_paths():
    """Verify that shlex.quote doesn't change behavior for normal paths."""
    normal_paths = [
        "/tmp/test",
        "/home/user/workspace",
        "./relative/path",
        "../parent/dir",
        "/path/with-dashes",
        "/path/with_underscores",
        "/path/with.dots",
    ]

    for path in normal_paths:
        quoted = shlex.quote(path)
        # For paths without special characters, shlex.quote returns the path unchanged
        assert quoted == path, f"Normal path {path} should not be changed by shlex.quote"


def test_shlex_quote_handles_spaces():
    """Verify that shlex.quote properly handles paths with spaces."""
    path_with_space = "/path with spaces/dir"
    quoted = shlex.quote(path_with_space)

    # shlex.quote wraps in single quotes
    assert quoted == "'/path with spaces/dir'"

    # Verify round-trip: split should give back original
    assert shlex.split(quoted) == [path_with_space]


def test_shlex_quote_handles_special_chars():
    """Verify that shlex.quote properly handles shell metacharacters."""
    dangerous_paths = [
        "/path/with;semicolon",
        "/path/with$dollar",
        "/path/with`backtick",
        "/path/with'quote",
        '/path/with"doublequote',
        "/path/with&ampersand",
        "/path/with|pipe",
    ]

    for path in dangerous_paths:
        quoted = shlex.quote(path)
        # After quoting, the special character should be safe
        assert quoted != path, f"Path with special chars {path} should be quoted"
        # Verify round-trip
        assert shlex.split(quoted) == [path]


def test_command_construction_with_quoting():
    """Test that command construction with quoting produces correct shell commands."""

    # Normal path - no change in behavior
    normal_path = Path("/tmp/test")
    git_ref = "v1.0.0"
    repo_url = "https://github.com/user/repo.git"

    # Construct command with quoting (the fix)
    cmd = f"cd {shlex.quote(str(normal_path))} && git clone {shlex.quote(repo_url)}"
    expected = "cd /tmp/test && git clone https://github.com/user/repo.git"
    assert cmd == expected, "Normal paths should behave identically with shlex.quote"

    # Path with spaces - now works correctly
    space_path = Path("/tmp/my workspace/test")
    cmd = f"cd {shlex.quote(str(space_path))} && git checkout {shlex.quote(git_ref)}"
    expected = "cd '/tmp/my workspace/test' && git checkout v1.0.0"
    assert cmd == expected


def test_git_reference_quoting():
    """Test that git references are properly quoted (though normally safe)."""
    safe_refs = [
        "main",
        "v1.0.0",
        "feature/my-branch",
        "bugfix-123",
    ]

    for ref in safe_refs:
        quoted = shlex.quote(ref)
        # Normal git refs don't need quoting but shlex.quote is safe
        assert quoted == ref, f"Normal git ref {ref} should not be changed"

    # Even if someone passes a weird ref, it should be safe
    weird_ref = "ref with spaces"  # Invalid but should be safe
    quoted = shlex.quote(weird_ref)
    assert quoted == "'ref with spaces'"


def test_url_quoting():
    """Test that URLs are properly quoted."""
    urls = [
        "https://github.com/user/repo.git",
        "git@github.com:user/repo.git",
        "https://github.com/user/repo-name.git",
    ]

    for url in urls:
        quoted = shlex.quote(url)
        # Normal URLs don't need quoting
        assert quoted == url


@patch("hotvect.utils.subprocess.run")
def test_runshell_with_quoted_paths(mock_run):
    """Verify that runshell works correctly with quoted paths."""
    from hotvect.utils import runshell

    mock_run.return_value = MagicMock(returncode=0, stdout=b"success", stderr=b"")

    # Normal path - should work before and after fix
    path = "/tmp/test"
    cmd = f"cd {shlex.quote(path)} && echo test"
    result = runshell(cmd, shell=True)

    assert result["return_code"] == 0
    mock_run.assert_called_once()

    # Verify the command was passed correctly
    call_args = mock_run.call_args
    assert call_args[1]["shell"] is True
    assert call_args[1]["executable"] == "/bin/bash"


def test_backward_compatibility_with_pathlib():
    """Verify that Path objects are properly converted and quoted."""
    # Path objects need str() conversion
    path = Path("/tmp/test/dir")

    # This is the pattern used in the code
    quoted = shlex.quote(str(path))
    assert quoted == "/tmp/test/dir"

    # With spaces
    space_path = Path("/tmp/my workspace")
    quoted = shlex.quote(str(space_path))
    assert quoted == "'/tmp/my workspace'"
