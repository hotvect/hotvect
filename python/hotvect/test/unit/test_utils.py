import hypothesis.strategies as st
from hypothesis import given, note

from hotvect import utils


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
    note(f"Resulting recommendation was:{actual}, locals:{locals()}")

    # Use at least 60% of desired core usage
    assert actual_core_use >= 0.6 * target_core_use
