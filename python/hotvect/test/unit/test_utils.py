import pathlib
from datetime import date

import hypothesis.strategies as st
import pytest
from hypothesis import given, note

from hotvect import utils
from hotvect.utils import to_local_paths


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
    with pytest.raises(AssertionError):
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
