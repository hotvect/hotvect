#!/usr/bin/env python3
"""Quick test for the new to_zip_archive implementation."""

import os
import sys
import tempfile
import zipfile
from pathlib import Path

# Add the hotvect module to the path
sys.path.insert(0, str(Path(__file__).parent))

from hotvect.utils import to_zip_archive  # noqa: E402


def test_successful_zip():
    """Test that we can create a zip archive successfully."""
    with tempfile.TemporaryDirectory() as tmpdir:
        # Create test files
        test_file1 = os.path.join(tmpdir, "test1.txt")
        test_file2 = os.path.join(tmpdir, "test2.txt")
        subdir = os.path.join(tmpdir, "subdir")
        os.makedirs(subdir)
        test_file3 = os.path.join(subdir, "test3.txt")

        with open(test_file1, "w") as f:
            f.write("Test content 1")
        with open(test_file2, "w") as f:
            f.write("Test content 2")
        with open(test_file3, "w") as f:
            f.write("Test content 3")

        # Create zip archive
        zip_path = os.path.join(tmpdir, "test.zip")
        to_archive = [
            (test_file1, "file1.txt"),
            (test_file2, "subpath/file2.txt"),
            (test_file3, "another/path/file3.txt"),
        ]

        to_zip_archive(to_archive, zip_path)

        # Verify the archive exists
        assert os.path.exists(zip_path), "Zip file was not created"

        # Verify contents
        with zipfile.ZipFile(zip_path, "r") as zipf:
            names = zipf.namelist()
            print(f"Archive contains: {names}")
            assert "file1.txt" in names, "file1.txt not in archive"
            assert "subpath/file2.txt" in names, "subpath/file2.txt not in archive"
            assert "another/path/file3.txt" in names, "another/path/file3.txt not in archive"

            # Verify content
            content1 = zipf.read("file1.txt").decode("utf-8")
            assert content1 == "Test content 1", "file1.txt has wrong content"

        print("✓ Successful zip test passed")


def test_missing_file():
    """Test that we fail when a source file doesn't exist."""
    with tempfile.TemporaryDirectory() as tmpdir:
        # Create only one file
        test_file1 = os.path.join(tmpdir, "test1.txt")
        with open(test_file1, "w") as f:
            f.write("Test content")

        # Try to create archive with missing file
        zip_path = os.path.join(tmpdir, "test.zip")
        to_archive = [
            (test_file1, "file1.txt"),
            (os.path.join(tmpdir, "nonexistent.txt"), "file2.txt"),
        ]

        try:
            to_zip_archive(to_archive, zip_path)
            print("✗ Missing file test FAILED - no exception was raised")
            sys.exit(1)
        except FileNotFoundError as e:
            assert "nonexistent.txt" in str(e), f"Wrong error message: {e}"
            print("✓ Missing file test passed - correctly raised FileNotFoundError")


if __name__ == "__main__":
    print("Testing to_zip_archive implementation...")
    print()
    test_successful_zip()
    test_missing_file()
    print()
    print("All tests passed!")
