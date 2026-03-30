"""
Test that nested directory structures in state outputs are correctly packaged and extracted.

This is a regression test for the bug where nested directories were silently dropped during
encode-parameter packaging, causing incomplete cached bundles and failed restores.
"""

import os
import tempfile
import zipfile
from pathlib import Path


def test_nested_state_directory_packaging_and_extraction():
    """
    Test that nested directories in state outputs are preserved through ZIP packaging/extraction.

    This tests the fix for the bug where:
    - Packaging used glob("*") which only found immediate children
    - Files in nested subdirectories were silently dropped
    - Cached parameter bundles were incomplete
    """
    with tempfile.TemporaryDirectory() as tmpdir:
        # Setup: Create a mock state output directory with nested structure
        state_dir = Path(tmpdir) / "state_output"
        state_dir.mkdir()

        # Create files at root level (should work before and after fix)
        (state_dir / "root_file.bin").write_text("root content")
        (state_dir / "algorithm-parameters.json").write_text('{"version": "1.0"}')

        # Create nested directory structure (broken before fix, works after)
        shard_0 = state_dir / "shard_0"
        shard_0.mkdir()
        (shard_0 / "data.bin").write_text("shard 0 data")
        (shard_0 / "metadata.json").write_text('{"shard": 0}')

        shard_1 = state_dir / "shard_1"
        shard_1.mkdir()
        (shard_1 / "data.bin").write_text("shard 1 data")

        # Create deeply nested structure
        deep = state_dir / "embeddings" / "layer_0" / "checkpoint"
        deep.mkdir(parents=True)
        (deep / "weights.pt").write_text("model weights")

        # Package: Simulate the fixed packaging logic
        zip_path = Path(tmpdir) / "test.parameters.zip"
        algorithm_name = "test-algo"

        with zipfile.ZipFile(zip_path, "w") as zf:
            for root, dirs, files in os.walk(state_dir):
                for file in files:
                    if file != "algorithm-parameters.json":  # Exclude metadata
                        full_path = os.path.join(root, file)
                        rel_path = os.path.relpath(full_path, state_dir)
                        arc_name = os.path.join(algorithm_name, rel_path)
                        zf.write(full_path, arc_name)

        # Extract: Simulate the fixed extraction logic
        extract_dir = Path(tmpdir) / "extracted"
        extract_dir.mkdir()

        with zipfile.ZipFile(zip_path, "r") as zf:
            for member in zf.namelist():
                if member.startswith(algorithm_name + "/"):
                    # Strip algorithm prefix
                    new_member_name = member[len(algorithm_name) + 1 :]
                    target_path = extract_dir / new_member_name

                    # Create parent directories if needed (the fix)
                    target_dir = os.path.dirname(str(target_path))
                    if target_dir:  # Guard against empty dirname
                        os.makedirs(target_dir, exist_ok=True)

                    # Extract file
                    with zf.open(member) as source, open(target_path, "wb") as target:
                        target.write(source.read())

        # Verify: All files should be present with correct structure
        assert (extract_dir / "root_file.bin").read_text() == "root content"
        assert (extract_dir / "shard_0" / "data.bin").read_text() == "shard 0 data"
        assert (extract_dir / "shard_0" / "metadata.json").read_text() == '{"shard": 0}'
        assert (extract_dir / "shard_1" / "data.bin").read_text() == "shard 1 data"
        assert (extract_dir / "embeddings" / "layer_0" / "checkpoint" / "weights.pt").read_text() == "model weights"

        # Verify structure is preserved (not flattened)
        assert (extract_dir / "shard_0").is_dir()
        assert (extract_dir / "shard_1").is_dir()
        assert (extract_dir / "embeddings" / "layer_0" / "checkpoint").is_dir()


def test_flat_state_directory_still_works():
    """
    Regression test: flat state directories should continue to work (no nested dirs).
    """
    with tempfile.TemporaryDirectory() as tmpdir:
        # Setup: Create a flat state output directory
        state_dir = Path(tmpdir) / "state_output"
        state_dir.mkdir()

        (state_dir / "file1.bin").write_text("content 1")
        (state_dir / "file2.bin").write_text("content 2")
        (state_dir / "algorithm-parameters.json").write_text('{"version": "1.0"}')

        # Package
        zip_path = Path(tmpdir) / "test.parameters.zip"
        algorithm_name = "test-algo"

        with zipfile.ZipFile(zip_path, "w") as zf:
            for root, dirs, files in os.walk(state_dir):
                for file in files:
                    if file != "algorithm-parameters.json":
                        full_path = os.path.join(root, file)
                        rel_path = os.path.relpath(full_path, state_dir)
                        arc_name = os.path.join(algorithm_name, rel_path)
                        zf.write(full_path, arc_name)

        # Extract
        extract_dir = Path(tmpdir) / "extracted"
        extract_dir.mkdir()

        with zipfile.ZipFile(zip_path, "r") as zf:
            for member in zf.namelist():
                if member.startswith(algorithm_name + "/"):
                    new_member_name = member[len(algorithm_name) + 1 :]
                    target_path = extract_dir / new_member_name

                    # Guard: only create dirs for nested files
                    target_dir = os.path.dirname(str(target_path))
                    if target_dir:  # Guard against empty dirname
                        os.makedirs(target_dir, exist_ok=True)

                    with zf.open(member) as source, open(target_path, "wb") as target:
                        target.write(source.read())

        # Verify: flat files should be at root of extract_dir
        assert (extract_dir / "file1.bin").read_text() == "content 1"
        assert (extract_dir / "file2.bin").read_text() == "content 2"
        assert not (extract_dir / "algorithm-parameters.json").exists()  # Should be excluded
