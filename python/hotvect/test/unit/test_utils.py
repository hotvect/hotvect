import hashlib
import io
import os
import pathlib
import tarfile
import tempfile
from datetime import date
from unittest.mock import MagicMock, patch

import hypothesis.strategies as st
import pytest
from hypothesis import given, note

from hotvect import utils
from hotvect.pyhotvect import DataDependency
from hotvect.utils import (
    as_locally_available_content,
    assert_s3_directory_uri_empty,
    build_s3_date_path,
    execute_command_with_live_output,
    format_s3_uri,
    parse_s3_uri,
    resolve_data_dependency_s3_uri,
    safe_extract_tar_archive,
    store_file,
    to_local_paths,
)


@given(
    num_git_ref=st.integers(min_value=1, max_value=12),
    num_runs=st.integers(min_value=1, max_value=30),
    available_physical_cores=st.integers(min_value=1, max_value=64).filter(lambda x: x % 2 == 0),
)
def test_recommend_concurrency(num_git_ref, num_runs, available_physical_cores):
    actual = utils.recommend_concurrency(
        num_git_ref=num_git_ref,
        num_runs=num_runs,
        remote_execution=False,
        available_physical_cores=available_physical_cores,
    )

    # Total number of backtest pipelines
    assert actual.total_backtest_pipelines <= num_git_ref
    assert actual.total_backtest_pipelines <= available_physical_cores
    assert actual.total_backtest_pipelines >= 1
    assert actual.total_backtest_pipelines <= utils.MAX_CONCURRENT_GIT_REF

    if num_git_ref <= available_physical_cores and num_git_ref <= utils.MAX_CONCURRENT_GIT_REF:
        assert actual.total_backtest_pipelines == num_git_ref

    # Number of concurrent backtest iterations
    available_physical_cores_per_backtest_pipeline = float(available_physical_cores) / actual.total_backtest_pipelines
    assert actual.nproc_per_backtest_pipeline <= round(available_physical_cores_per_backtest_pipeline)
    assert actual.nproc_per_backtest_pipeline <= num_runs
    assert actual.nproc_per_backtest_pipeline <= utils.MAX_CONCURRENT_BACKTEST_ITERATION_PER_PIPELINE

    # Number of hotvect threads
    assert actual.threads_per_backtest_process <= round(available_physical_cores_per_backtest_pipeline)
    assert actual.threads_per_backtest_process <= utils.MAX_HOTVECT_TASK_THREADS

    total_cores_used = (
        actual.total_backtest_pipelines * actual.nproc_per_backtest_pipeline * actual.threads_per_backtest_process
    )

    max_possible_core_use = (
        min(num_git_ref, utils.MAX_CONCURRENT_GIT_REF)
        * min(num_runs, utils.MAX_CONCURRENT_BACKTEST_ITERATION_PER_PIPELINE)
        * utils.MAX_HOTVECT_TASK_THREADS
    )

    target_core_use = min(max_possible_core_use, available_physical_cores)

    actual_core_use = (
        actual.total_backtest_pipelines * actual.nproc_per_backtest_pipeline * actual.threads_per_backtest_process
    )
    note(f"Resulting recommendation was: {actual}, locals: {locals()}")

    # Use at least 60% of desired core usage
    assert actual_core_use >= 0.6 * target_core_use


@pytest.mark.parametrize(
    ("num_files", "available_logical_cores", "expected"),
    [
        (0, 48, 1),
        (2, 48, 2),
        (16, 48, 16),
        (100, 48, 32),
        (100, 96, 32),
        (100, 8, 6),
    ],
)
def test_recommend_s3_directory_transfer_workers(num_files, available_logical_cores, expected):
    actual = utils.recommend_s3_directory_transfer_workers(
        num_files=num_files,
        available_logical_cores=available_logical_cores,
    )

    assert actual == expected


def create_dt_directory(base: pathlib.Path, dirname: str) -> str:
    """
    Helper function which creates a directory named 'dirname' under the base path.
    Returns the string path to the created directory.
    """
    d = base / dirname
    d.mkdir()
    return str(d)


def test_valid_dirs(tmp_path):
    d1 = create_dt_directory(tmp_path, "dt=2023-01-01")
    d2 = create_dt_directory(tmp_path, "dt=2023-01-02 00%3A00%3A00")
    d3 = create_dt_directory(tmp_path, "dt=2023-01-03T00%3A00%3A00")
    requested = [date(2023, 1, 1), date(2023, 1, 2), date(2023, 1, 3)]

    result = to_local_paths(str(tmp_path), requested)
    assert sorted(result) == sorted([d1, d2, d3])


def test_missing_date_fail(tmp_path):
    create_dt_directory(tmp_path, "dt=2023-01-01")
    requested = [date(2023, 1, 1), date(2023, 1, 2)]
    with pytest.raises(ValueError, match="dates were not available"):
        to_local_paths(str(tmp_path), requested, fail_if_unavailable=True)


def test_missing_date_no_fail(tmp_path):
    d1 = create_dt_directory(tmp_path, "dt=2023-01-01")
    requested = [date(2023, 1, 1), date(2023, 1, 2)]
    result = to_local_paths(str(tmp_path), requested, fail_if_unavailable=False)
    assert result == [d1]


@given(
    year=st.integers(min_value=2000, max_value=2100),
    month=st.integers(min_value=1, max_value=12),
    day=st.integers(min_value=1, max_value=28),
    sep=st.sampled_from([" ", "T", ""]),
)
def test_regex_with_hypothesis(tmp_path_factory, year, month, day, sep):
    # Create a new unique temp directory for each generated example.
    test_dir = tmp_path_factory.mktemp(f"test-{year}-{month}-{day}")

    dirname = f"dt={year:04d}-{month:02d}-{day:02d}"  # noqa: E231
    if sep:
        # Append a URL-encoded timestamp (e.g., "T00%3A00%3A00" or " 00%3A00%3A00")
        dirname += sep + "00%3A00%3A00"

    created_dir = create_dt_directory(test_dir, dirname)
    requested = [date(year, month, day)]

    # Run the main function and check that we get exactly the directory we created.
    result = to_local_paths(str(test_dir), requested)
    assert result == [created_dir]


# Tests for store_file() function


@patch("hotvect.utils.boto3")
def test_store_file_s3_sample_bucket(mock_boto3):
    """Test S3 path with bucket name starting with 's' - would fail with lstrip()"""
    mock_s3_client = MagicMock()
    mock_boto3.client.return_value = mock_s3_client

    store_file("/local/file.txt", "s3://sample-bucket/path/to/file.txt")

    mock_boto3.client.assert_called_once_with("s3")
    mock_s3_client.upload_file.assert_called_once_with("/local/file.txt", "sample-bucket", "path/to/file.txt")


@patch("hotvect.utils.boto3")
def test_store_file_s3_numbers_bucket(mock_boto3):
    """Test S3 path with bucket name starting with '3' - would fail with lstrip()"""
    mock_s3_client = MagicMock()
    mock_boto3.client.return_value = mock_s3_client

    store_file("/local/file.txt", "s3://333-numbers-bucket/cache/data.zip")

    mock_boto3.client.assert_called_once_with("s3")
    mock_s3_client.upload_file.assert_called_once_with("/local/file.txt", "333-numbers-bucket", "cache/data.zip")


@patch("hotvect.utils.boto3")
def test_store_file_s3_secure_bucket(mock_boto3):
    """Test S3 path with bucket name starting with multiple strippable chars - would fail with lstrip()"""
    mock_s3_client = MagicMock()
    mock_boto3.client.return_value = mock_s3_client

    store_file("/local/file.txt", "s3://secure-store/parameters.zip")

    mock_boto3.client.assert_called_once_with("s3")
    mock_s3_client.upload_file.assert_called_once_with("/local/file.txt", "secure-store", "parameters.zip")


@patch("hotvect.utils.boto3")
def test_store_file_s3_normal_bucket(mock_boto3):
    """Test S3 path with normal bucket name"""
    mock_s3_client = MagicMock()
    mock_boto3.client.return_value = mock_s3_client

    store_file("/local/file.txt", "s3://my-bucket/deep/nested/path/file.txt")

    mock_boto3.client.assert_called_once_with("s3")
    mock_s3_client.upload_file.assert_called_once_with("/local/file.txt", "my-bucket", "deep/nested/path/file.txt")


@patch("hotvect.utils.boto3")
def test_store_file_s3_root_level(mock_boto3):
    """Test S3 path with file at bucket root"""
    mock_s3_client = MagicMock()
    mock_boto3.client.return_value = mock_s3_client

    store_file("/local/file.txt", "s3://my-bucket/file.txt")

    mock_boto3.client.assert_called_once_with("s3")
    mock_s3_client.upload_file.assert_called_once_with("/local/file.txt", "my-bucket", "file.txt")


@patch("hotvect.utils.boto3")
def test_assert_s3_directory_uri_empty_accepts_empty_prefix(mock_boto3):
    mock_s3_client = MagicMock()
    mock_s3_client.list_objects_v2.return_value = {"KeyCount": 0}
    mock_boto3.client.return_value = mock_s3_client

    assert_s3_directory_uri_empty("s3://my-bucket/path/to/prediction/")

    mock_boto3.client.assert_called_once_with("s3")
    mock_s3_client.list_objects_v2.assert_called_once_with(
        Bucket="my-bucket",
        Prefix="path/to/prediction/",
        MaxKeys=1,
    )


@patch("hotvect.utils.boto3")
def test_assert_s3_directory_uri_empty_rejects_existing_objects(mock_boto3):
    mock_s3_client = MagicMock()
    mock_s3_client.list_objects_v2.return_value = {"Contents": [{"Key": "path/to/prediction/part-00000.jsonl"}]}
    mock_boto3.client.return_value = mock_s3_client

    with pytest.raises(ValueError, match="must be empty before publish"):
        assert_s3_directory_uri_empty("s3://my-bucket/path/to/prediction/")

    mock_boto3.client.assert_called_once_with("s3")
    mock_s3_client.list_objects_v2.assert_called_once_with(
        Bucket="my-bucket",
        Prefix="path/to/prediction/",
        MaxKeys=1,
    )


def test_store_file_local_path(tmp_path):
    """Test local file path copying"""
    src_file = tmp_path / "source.txt"
    src_file.write_text("test content")

    dest_file = tmp_path / "subdir" / "dest.txt"

    store_file(str(src_file), str(dest_file))

    assert dest_file.exists()
    assert dest_file.read_text() == "test content"


def test_store_file_local_creates_intermediate_dirs(tmp_path):
    """Test that store_file creates intermediate directories for local paths"""
    src_file = tmp_path / "source.txt"
    src_file.write_text("test content")

    dest_file = tmp_path / "deep" / "nested" / "path" / "dest.txt"

    store_file(str(src_file), str(dest_file))

    assert dest_file.exists()
    assert dest_file.read_text() == "test content"
    assert dest_file.parent.exists()


@patch("hotvect.utils.boto3")
def test_store_file_s3_directory_uploads_all_files(mock_boto3, tmp_path):
    mock_s3_client = MagicMock()
    mock_boto3.client.return_value = mock_s3_client

    src_dir = tmp_path / "encoded"
    (src_dir / "nested").mkdir(parents=True)
    (src_dir / "part-00000").write_text("a")
    (src_dir / "nested" / "part-00001").write_text("b")

    with patch("hotvect.utils.shutil.which", return_value=None):
        store_file(str(src_dir), "s3://my-bucket/cache/encoded")

    assert {call.args for call in mock_s3_client.upload_file.call_args_list} == {
        (str(src_dir / "part-00000"), "my-bucket", "cache/encoded/part-00000"),
        (str(src_dir / "nested" / "part-00001"), "my-bucket", "cache/encoded/nested/part-00001"),
    }
    assert all(
        call.kwargs == {"Config": utils.S3_DIRECTORY_TRANSFER_CONFIG}
        for call in mock_s3_client.upload_file.call_args_list
    )


def test_store_file_s3_directory_uses_s5cmd_when_available(tmp_path):
    src_dir = tmp_path / "encoded"
    (src_dir / "nested").mkdir(parents=True)
    (src_dir / "part-00000").write_text("a")
    (src_dir / "nested" / "part-00001").write_text("b")

    with (
        patch("hotvect.utils.boto3") as mock_boto3,
        patch("hotvect.utils.shutil.which", return_value="/usr/bin/s5cmd"),
        patch("hotvect.utils.recommend_s3_directory_transfer_workers", return_value=7),
        patch("hotvect.utils.subprocess.run") as mock_run,
    ):
        store_file(str(src_dir), "s3://my-bucket/cache/encoded")

    mock_boto3.client.assert_not_called()
    mock_run.assert_called_once_with(
        [
            "/usr/bin/s5cmd",
            "--numworkers",
            "7",
            "cp",
            os.path.join(str(src_dir), ""),
            "s3://my-bucket/cache/encoded/",
        ],
        check=True,
    )


def test_sanitize_path_component_removes_separators():
    safe = utils.sanitize_path_component("chore/bump-version-82.2.20")
    assert "/" not in safe
    assert "\\" not in safe

    with tempfile.TemporaryDirectory(prefix=f"git-{safe}-") as tmpdir:
        assert pathlib.Path(tmpdir).exists()


def _write_tar_entry(tar: tarfile.TarFile, name: str, payload: bytes = b"x") -> None:
    info = tarfile.TarInfo(name=name)
    info.size = len(payload)
    tar.addfile(info, io.BytesIO(payload))


def test_safe_extract_tar_archive_allows_regular_files(tmp_path):
    archive_path = tmp_path / "ok.tar"
    with tarfile.open(archive_path, mode="w") as tar:
        _write_tar_entry(tar, "nested/file.txt", b"hello")

    dest = tmp_path / "dest"
    with tarfile.open(archive_path, mode="r") as tar:
        safe_extract_tar_archive(tar, dest)

    assert (dest / "nested" / "file.txt").read_text() == "hello"


def test_safe_extract_tar_archive_rejects_path_traversal(tmp_path):
    archive_path = tmp_path / "bad.tar"
    with tarfile.open(archive_path, mode="w") as tar:
        _write_tar_entry(tar, "../escaped.txt", b"bad")

    dest = tmp_path / "dest"
    with tarfile.open(archive_path, mode="r") as tar:
        with pytest.raises(ValueError, match="outside base directory"):
            safe_extract_tar_archive(tar, dest)

    assert not (tmp_path / "escaped.txt").exists()


def test_safe_extract_tar_archive_rejects_symlinks(tmp_path):
    archive_path = tmp_path / "bad-link.tar"
    with tarfile.open(archive_path, mode="w") as tar:
        info = tarfile.TarInfo(name="link")
        info.type = tarfile.SYMTYPE
        info.linkname = "target"
        tar.addfile(info)

    with tarfile.open(archive_path, mode="r") as tar:
        with pytest.raises(ValueError, match="link entry"):
            safe_extract_tar_archive(tar, tmp_path / "dest")


# Tests for as_locally_available_content() function


def _expected_single_file_cache_path(local_cache: pathlib.Path, bucket: str, key: str) -> pathlib.Path:
    key_hash = hashlib.sha256(key.encode("utf-8")).hexdigest()
    return local_cache / ".hotvect_s3_cache" / bucket / key_hash / pathlib.Path(key).name


@patch("hotvect.utils.boto3")
def test_as_locally_available_content_exact_match(mock_boto3, tmp_path):
    """Test that as_locally_available_content downloads only the exact key match, not prefix matches"""
    # Setup: Mock S3 to return multiple files matching the prefix
    mock_s3_resource = MagicMock()
    mock_boto3.resource.return_value = mock_s3_resource

    mock_bucket = MagicMock()
    mock_s3_resource.Bucket.return_value = mock_bucket

    # Create mock objects for files that would match the prefix
    mock_file1 = MagicMock()
    mock_file1.key = "path/to/predict-parameters.zip"

    mock_file2 = MagicMock()
    mock_file2.key = "path/to/predict-parameters.zip.metadata"

    # S3 returns both files when filtering by prefix
    mock_bucket.objects.filter.return_value = [mock_file1, mock_file2]

    local_cache = str(tmp_path)

    # Request the exact file (not the metadata)
    result = as_locally_available_content("s3://test-bucket/path/to/predict-parameters.zip", local_cache)

    # Verify: Should download only the exact match (predict-parameters.zip, not the .metadata file)
    mock_bucket.download_file.assert_called_once_with("path/to/predict-parameters.zip", result)
    assert result.endswith("predict-parameters.zip")


@patch("hotvect.utils.boto3")
def test_as_locally_available_content_exact_match_uses_local_cache(mock_boto3, tmp_path):
    """Test that as_locally_available_content reuses existing local downloads for single S3 files."""
    mock_s3_resource = MagicMock()
    mock_boto3.resource.return_value = mock_s3_resource

    mock_bucket = MagicMock()
    mock_s3_resource.Bucket.return_value = mock_bucket

    mock_file = MagicMock()
    mock_file.key = "path/to/predict-parameters.zip"
    mock_bucket.objects.filter.return_value = [mock_file]

    local_cache = tmp_path
    expected_path = _expected_single_file_cache_path(local_cache, "test-bucket", "path/to/predict-parameters.zip")
    expected_path.parent.mkdir(parents=True, exist_ok=True)
    expected_path.write_text("already downloaded")

    result = as_locally_available_content("s3://test-bucket/path/to/predict-parameters.zip", str(local_cache))

    assert result == str(expected_path)
    mock_bucket.download_file.assert_not_called()


@patch("hotvect.utils.boto3")
def test_as_locally_available_content_single_file_cache_avoids_basename_collisions(mock_boto3, tmp_path):
    """Different S3 keys with the same basename must not collide in the local cache."""
    mock_s3_resource = MagicMock()
    mock_boto3.resource.return_value = mock_s3_resource

    mock_bucket = MagicMock()
    mock_s3_resource.Bucket.return_value = mock_bucket

    key1 = "path/to/a/file.zip"
    key2 = "another/prefix/file.zip"

    obj1 = MagicMock()
    obj1.key = key1
    obj2 = MagicMock()
    obj2.key = key2

    def filter_side_effect(Prefix):
        if Prefix == key1:
            return [obj1]
        if Prefix == key2:
            return [obj2]
        return []

    mock_bucket.objects.filter.side_effect = filter_side_effect

    local_cache = tmp_path
    result1 = as_locally_available_content(f"s3://test-bucket/{key1}", str(local_cache))
    result2 = as_locally_available_content(f"s3://test-bucket/{key2}", str(local_cache))

    assert result1 is not None
    assert result2 is not None
    assert result1 != result2
    assert result1.endswith("file.zip")
    assert result2.endswith("file.zip")

    expected1 = _expected_single_file_cache_path(local_cache, "test-bucket", key1)
    expected2 = _expected_single_file_cache_path(local_cache, "test-bucket", key2)
    assert result1 == str(expected1)
    assert result2 == str(expected2)

    assert mock_bucket.download_file.call_args_list == [
        ((key1, str(expected1)),),
        ((key2, str(expected2)),),
    ]


@patch("hotvect.utils.boto3")
def test_as_locally_available_content_directory_replaces_existing_local_path(mock_boto3, tmp_path):
    mock_s3_resource = MagicMock()
    mock_boto3.resource.return_value = mock_s3_resource
    mock_s3_client = MagicMock()
    mock_boto3.client.return_value = mock_s3_client

    mock_bucket = MagicMock()
    mock_s3_resource.Bucket.return_value = mock_bucket

    mock_obj = MagicMock()
    mock_obj.key = "path/to/state-cache/file.txt"
    mock_bucket.objects.filter.return_value = [mock_obj]

    local_cache = tmp_path
    local_dir_path = local_cache / "state-cache"
    local_dir_path.mkdir(parents=True)
    (local_dir_path / "stale.txt").write_text("stale")

    with patch("hotvect.utils.shutil.which", return_value=None):
        result = as_locally_available_content("s3://test-bucket/path/to/state-cache/", str(local_cache))

    assert result == str(local_dir_path)
    mock_s3_client.download_file.assert_called_once_with(
        "test-bucket",
        "path/to/state-cache/file.txt",
        str(local_dir_path / "file.txt"),
        Config=utils.S3_DIRECTORY_TRANSFER_CONFIG,
    )
    assert not (local_dir_path / "stale.txt").exists()


@patch("hotvect.utils.boto3")
def test_as_locally_available_content_directory_downloads_all_files(mock_boto3, tmp_path):
    mock_s3_resource = MagicMock()
    mock_boto3.resource.return_value = mock_s3_resource
    mock_s3_client = MagicMock()
    mock_boto3.client.return_value = mock_s3_client

    mock_bucket = MagicMock()
    mock_s3_resource.Bucket.return_value = mock_bucket

    first = MagicMock()
    first.key = "path/to/state-cache/a.txt"
    second = MagicMock()
    second.key = "path/to/state-cache/nested/b.txt"
    mock_bucket.objects.filter.return_value = [first, second]

    with patch("hotvect.utils.shutil.which", return_value=None):
        result = as_locally_available_content("s3://test-bucket/path/to/state-cache/", str(tmp_path))

    assert result == str(tmp_path / "state-cache")
    assert {call.args for call in mock_s3_client.download_file.call_args_list} == {
        ("test-bucket", "path/to/state-cache/a.txt", str(tmp_path / "state-cache" / "a.txt")),
        ("test-bucket", "path/to/state-cache/nested/b.txt", str(tmp_path / "state-cache" / "nested" / "b.txt")),
    }
    assert all(
        call.kwargs == {"Config": utils.S3_DIRECTORY_TRANSFER_CONFIG}
        for call in mock_s3_client.download_file.call_args_list
    )


@patch("hotvect.utils.boto3")
def test_as_locally_available_content_trailing_slash_treats_s3_marker_as_directory(mock_boto3, tmp_path):
    mock_s3_resource = MagicMock()
    mock_boto3.resource.return_value = mock_s3_resource
    mock_s3_client = MagicMock()
    mock_boto3.client.return_value = mock_s3_client

    mock_bucket = MagicMock()
    mock_s3_resource.Bucket.return_value = mock_bucket

    marker = MagicMock()
    marker.key = "path/to/state-cache/"
    child = MagicMock()
    child.key = "path/to/state-cache/file.txt"
    mock_bucket.objects.filter.return_value = [marker, child]

    with patch("hotvect.utils.shutil.which", return_value=None):
        result = as_locally_available_content("s3://test-bucket/path/to/state-cache/", str(tmp_path))

    assert result == str(tmp_path / "state-cache")
    mock_bucket.download_file.assert_not_called()
    mock_s3_client.download_file.assert_called_once_with(
        "test-bucket",
        "path/to/state-cache/file.txt",
        str(tmp_path / "state-cache" / "file.txt"),
        Config=utils.S3_DIRECTORY_TRANSFER_CONFIG,
    )


def test_as_locally_available_content_directory_uses_s5cmd_when_available(tmp_path):
    with (
        patch("hotvect.utils.boto3") as mock_boto3,
        patch("hotvect.utils.shutil.which", return_value="/usr/bin/s5cmd"),
        patch("hotvect.utils.recommend_s3_directory_transfer_workers", return_value=9),
        patch("hotvect.utils.subprocess.run") as mock_run,
    ):
        mock_s3_resource = MagicMock()
        mock_boto3.resource.return_value = mock_s3_resource

        mock_bucket = MagicMock()
        mock_s3_resource.Bucket.return_value = mock_bucket

        first = MagicMock()
        first.key = "path/to/state-cache/a.txt"
        second = MagicMock()
        second.key = "path/to/state-cache/nested/b.txt"
        mock_bucket.objects.filter.return_value = [first, second]

        result = as_locally_available_content("s3://test-bucket/path/to/state-cache/", str(tmp_path))

    assert result == str(tmp_path / "state-cache")
    mock_boto3.client.assert_not_called()
    mock_run.assert_called_once_with(
        [
            "/usr/bin/s5cmd",
            "--numworkers",
            "9",
            "cp",
            "--concurrency",
            "1",
            "s3://test-bucket/path/to/state-cache/*",
            os.path.join(str(tmp_path / "state-cache"), ""),
        ],
        check=True,
    )


@patch("hotvect.utils.boto3")
def test_as_locally_available_content_no_exact_match(mock_boto3, tmp_path):
    """Test that as_locally_available_content returns None when exact key doesn't exist"""
    mock_s3_resource = MagicMock()
    mock_boto3.resource.return_value = mock_s3_resource

    mock_bucket = MagicMock()
    mock_s3_resource.Bucket.return_value = mock_bucket

    # Create mock object for a file that matches prefix but not exact key
    mock_file = MagicMock()
    mock_file.key = "path/to/predict-parameters.zip.metadata"

    # Mock filter to return different results based on prefix
    # First call: prefix="path/to/predict-parameters.zip" -> returns [mock_file]
    # Second call: prefix="path/to/predict-parameters.zip/" -> returns []
    def filter_side_effect(Prefix):
        if Prefix == "path/to/predict-parameters.zip":
            return [mock_file]
        elif Prefix == "path/to/predict-parameters.zip/":
            return []
        return []

    mock_bucket.objects.filter.side_effect = filter_side_effect

    local_cache = str(tmp_path)

    # Request exact file that doesn't exist (only .metadata exists)
    result = as_locally_available_content("s3://test-bucket/path/to/predict-parameters.zip", local_cache)

    # Verify: Should return None since exact key doesn't exist
    assert result is None
    mock_bucket.download_file.assert_not_called()


@patch("hotvect.utils.boto3")
def test_as_locally_available_content_no_files(mock_boto3, tmp_path):
    """Test that as_locally_available_content returns None when no files exist"""
    mock_s3_resource = MagicMock()
    mock_boto3.resource.return_value = mock_s3_resource

    mock_bucket = MagicMock()
    mock_s3_resource.Bucket.return_value = mock_bucket

    # S3 returns no files
    mock_bucket.objects.filter.return_value = []

    local_cache = str(tmp_path)

    result = as_locally_available_content("s3://test-bucket/path/to/nonexistent.zip", local_cache)

    assert result is None
    mock_bucket.download_file.assert_not_called()


def test_as_locally_available_content_local_file(tmp_path):
    """Test that as_locally_available_content works with local file paths"""
    # Create a local file
    local_file = tmp_path / "test_file.txt"
    local_file.write_text("test content")

    result = as_locally_available_content(str(local_file), str(tmp_path))

    assert result == str(local_file)


def test_as_locally_available_content_local_file_not_exists(tmp_path):
    """Test that as_locally_available_content returns None for non-existent local files"""
    nonexistent = tmp_path / "nonexistent.txt"

    result = as_locally_available_content(str(nonexistent), str(tmp_path))

    assert result is None


@patch("hotvect.utils.boto3")
def test_as_locally_available_content_nonexistent_cache_dir(mock_boto3, tmp_path):
    """Test that as_locally_available_content creates cache directory if it doesn't exist"""
    # Setup: Mock S3 to return a file
    mock_s3_resource = MagicMock()
    mock_boto3.resource.return_value = mock_s3_resource

    mock_bucket = MagicMock()
    mock_s3_resource.Bucket.return_value = mock_bucket

    # Create mock object for the file
    mock_file = MagicMock()
    mock_file.key = "path/to/file.zip"

    mock_bucket.objects.filter.return_value = [mock_file]

    # Use a non-existent subdirectory as cache path (simulating fresh cache)
    nonexistent_cache = tmp_path / "nonexistent" / "cache"
    assert not nonexistent_cache.exists(), "Cache directory should not exist yet"

    # Request the file - should create the directory and download
    result = as_locally_available_content("s3://test-bucket/path/to/file.zip", str(nonexistent_cache))

    # Verify: Should create the directory and download the file
    assert nonexistent_cache.exists(), "Cache directory should have been created"
    mock_bucket.download_file.assert_called_once_with("path/to/file.zip", result)
    assert result == str(_expected_single_file_cache_path(nonexistent_cache, "test-bucket", "path/to/file.zip"))


def test_execute_command_multiple_calls():
    """Test that execute_command_with_live_output can be called multiple times without RuntimeError"""
    outputs = []

    def capture_output(text):
        outputs.append(text)

    # First call
    rc1 = execute_command_with_live_output(["echo", "first"], capture_output)
    assert rc1 == 0
    assert "first" in "".join(outputs)

    # Second call should work (not raise RuntimeError: Event loop is closed)
    outputs.clear()
    rc2 = execute_command_with_live_output(["echo", "second"], capture_output)
    assert rc2 == 0
    assert "second" in "".join(outputs)

    # Third call for good measure
    outputs.clear()
    rc3 = execute_command_with_live_output(["echo", "third"], capture_output)
    assert rc3 == 0
    assert "third" in "".join(outputs)


def test_stream_output_raises_on_failure():
    """Test that stream_output raises subprocess.CalledProcessError on non-zero exit code"""
    import subprocess

    import pytest

    from hotvect.utils import stream_output

    outputs = []

    def capture_output(text):
        outputs.append(text)

    # Command that fails (exit 42)
    with pytest.raises(subprocess.CalledProcessError) as exc_info:
        stream_output(["/bin/sh", "-c", "exit 42"], capture_output)

    # Verify exception attributes (CalledProcessError uses 'returncode', not 'return_code')
    assert exc_info.value.returncode == 42
    assert "exit 42" in exc_info.value.cmd
    # Exception should have output and stderr captured
    assert hasattr(exc_info.value, "output")
    assert hasattr(exc_info.value, "stderr")


# ============================================================================
# S3 Data Dependency Utilities Tests
# ============================================================================


def test_resolve_data_dependency_s3_uri_string_form():
    """Test resolution of string-form s3_uri."""
    dep = DataDependency(
        algorithm_name="test-algo",
        algorithm_version="1.0.0",
        data_prefix="my_data",
        data_dates={date(2025, 10, 23)},
        data_type="test",
        additional_properties={"s3_uri": "s3://bucket/path/"},
    )
    result = resolve_data_dependency_s3_uri(dep)
    assert result == "s3://bucket/path/"


def test_resolve_data_dependency_s3_uri_dict_form_production():
    """Test resolution of dict-form s3_uri with production environment."""
    dep = DataDependency(
        algorithm_name="test-algo",
        algorithm_version="1.0.0",
        data_prefix="my_data",
        data_dates={date(2025, 10, 23)},
        data_type="test",
        additional_properties={"s3_uri": {"production": "s3://prod-bucket/", "staging": "s3://stage-bucket/"}},
    )
    result = resolve_data_dependency_s3_uri(dep, environment="production")
    assert result == "s3://prod-bucket/"


def test_resolve_data_dependency_s3_uri_dict_form_staging():
    """Test resolution of dict-form s3_uri with staging environment."""
    dep = DataDependency(
        algorithm_name="test-algo",
        algorithm_version="1.0.0",
        data_prefix="my_data",
        data_dates={date(2025, 10, 23)},
        data_type="test",
        additional_properties={"s3_uri": {"production": "s3://prod-bucket/", "staging": "s3://stage-bucket/"}},
    )
    result = resolve_data_dependency_s3_uri(dep, environment="staging")
    assert result == "s3://stage-bucket/"


def test_resolve_data_dependency_s3_uri_dict_form_case_sensitive():
    """Test that environment keys are case-sensitive."""
    dep = DataDependency(
        algorithm_name="test-algo",
        algorithm_version="1.0.0",
        data_prefix="my_data",
        data_dates={date(2025, 10, 23)},
        data_type="test",
        additional_properties={"s3_uri": {"Production": "s3://prod-bucket/", "STAGING": "s3://stage-bucket/"}},
    )

    assert resolve_data_dependency_s3_uri(dep, environment="Production") == "s3://prod-bucket/"
    assert resolve_data_dependency_s3_uri(dep, environment="STAGING") == "s3://stage-bucket/"

    with pytest.raises(ValueError, match=r"Environment 'production' not found"):
        resolve_data_dependency_s3_uri(dep, environment="production")


def test_resolve_data_dependency_s3_uri_dict_form_missing_environment():
    """Test that missing environment raises ValueError."""
    dep = DataDependency(
        algorithm_name="test-algo",
        algorithm_version="1.0.0",
        data_prefix="my_data",
        data_dates={date(2025, 10, 23)},
        data_type="test",
        additional_properties={"s3_uri": {"production": "s3://prod-bucket/", "staging": "s3://stage-bucket/"}},
    )

    with pytest.raises(ValueError) as exc_info:
        resolve_data_dependency_s3_uri(dep, environment="test")

    assert "Environment 'test' not found" in str(exc_info.value)
    assert "production" in str(exc_info.value)
    assert "staging" in str(exc_info.value)


def test_resolve_data_dependency_s3_uri_fallback_to_default():
    """Test fallback to default_s3_base when no s3_uri."""
    dep = DataDependency(
        algorithm_name="test-algo",
        algorithm_version="1.0.0",
        data_prefix="my_data",
        data_dates={date(2025, 10, 23)},
        data_type="test",
        additional_properties={},
    )
    result = resolve_data_dependency_s3_uri(dep, default_s3_base="s3://bucket/base")
    assert result == "s3://bucket/base/my_data/"


def test_resolve_data_dependency_s3_uri_no_resolution():
    """Test that None is returned when no resolution possible."""
    dep = DataDependency(
        algorithm_name="test-algo",
        algorithm_version="1.0.0",
        data_prefix="my_data",
        data_dates={date(2025, 10, 23)},
        data_type="test",
        additional_properties={},
    )
    result = resolve_data_dependency_s3_uri(dep)
    assert result is None


def test_parse_s3_uri():
    """Test parsing S3 URI into bucket and prefix."""
    bucket, prefix = parse_s3_uri("s3://my-bucket/data/path/")
    assert bucket == "my-bucket"
    assert prefix == "data/path"

    bucket2, prefix2 = parse_s3_uri("s3://bucket/")
    assert bucket2 == "bucket"
    assert prefix2 == ""

    bucket3, prefix3 = parse_s3_uri("s3://bucket")
    assert bucket3 == "bucket"
    assert prefix3 == ""


def test_build_s3_date_path():
    """Test building S3 date path."""
    result = build_s3_date_path("base/prefix", "my_data", date_str="2025-10-23")
    assert result == "base/prefix/my_data/dt=2025-10-23/"

    result2 = build_s3_date_path("", "my_data", date_str="2025-10-23")
    assert result2 == "my_data/dt=2025-10-23/"

    result3 = build_s3_date_path(date_str="2025-10-23")
    assert result3 == "dt=2025-10-23/"

    result4 = build_s3_date_path("base", "", "data", date_str="2025-10-23")
    assert result4 == "base/data/dt=2025-10-23/"


def test_format_s3_uri():
    """Test formatting S3 URI."""
    result = format_s3_uri("my-bucket", "path/to/data/")
    assert result == "s3://my-bucket/path/to/data/"

    result2 = format_s3_uri("my-bucket", "/path/to/data/")
    assert result2 == "s3://my-bucket/path/to/data/"

    result3 = format_s3_uri("my-bucket", "")
    assert result3 == "s3://my-bucket"
