import atexit
from pathlib import Path


# The offline-util jar is typically staged into hotvect/hotvectjar/ by build tooling.
# Unit tests only validate CLI argument composition, so create a dummy jar if none is present.
def _ensure_offline_util_jar_present() -> None:
    jar_dir = Path(__file__).resolve().parents[2] / "hotvectjar"
    jar_dir.mkdir(parents=True, exist_ok=True)
    pattern = "hotvect-offline-util-*-jar-with-dependencies.jar"
    existing = list(jar_dir.glob(pattern))
    if len(existing) == 0:
        dummy = jar_dir / "hotvect-offline-util-test-jar-with-dependencies.jar"
        dummy.touch(exist_ok=True)
        atexit.register(lambda: dummy.unlink(missing_ok=True))


def _make_pipeline(tmp_path: Path, *, algorithm_definition: dict, jvm_options=None):
    _ensure_offline_util_jar_present()
    from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext

    pipeline = AlgorithmPipeline.__new__(AlgorithmPipeline)
    pipeline.algorithm_definition = algorithm_definition
    pipeline.algorithm_pipeline_context = AlgorithmPipelineContext(
        algorithm_jar_path=tmp_path / "algo.jar",
        data_base_path=tmp_path,
        metadata_base_path=tmp_path,
        output_base_path=tmp_path,
        jvm_options=jvm_options,
    )

    def _write_algorithm_definition() -> str:
        return str(tmp_path / "algorithm_definition.json")

    pipeline._write_algorithm_definition = _write_algorithm_definition
    return pipeline


def test_base_command_appends_exit_on_oom_when_no_jvm_options(tmp_path: Path):
    pipeline = _make_pipeline(tmp_path, algorithm_definition={}, jvm_options=None)

    command = pipeline._base_command(task_name="train", metadata_location=str(tmp_path / "meta"))

    assert command.count("-XX:+ExitOnOutOfMemoryError") == 1


def test_base_command_does_not_duplicate_exit_on_oom_when_provided_via_algorithm_definition(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {"jvm_args": ["-Xmx2g", "-XX:+ExitOnOutOfMemoryError"]},
        },
        jvm_options=["-Xmx512m"],
    )

    command = pipeline._base_command(task_name="train", metadata_location=str(tmp_path / "meta"))

    assert command.count("-XX:+ExitOnOutOfMemoryError") == 1


def test_base_command_does_not_duplicate_exit_on_oom_when_provided_via_pipeline_context(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={},
        jvm_options=["-Xmx2g", "-XX:+ExitOnOutOfMemoryError"],
    )

    command = pipeline._base_command(task_name="train", metadata_location=str(tmp_path / "meta"))

    assert command.count("-XX:+ExitOnOutOfMemoryError") == 1


def test_base_command_uses_offline_util_subcommand_when_known_task(tmp_path: Path):
    pipeline = _make_pipeline(tmp_path, algorithm_definition={}, jvm_options=None)

    command = pipeline._base_command(task_name="predict", metadata_location=str(tmp_path / "meta"))

    main_index = command.index("com.hotvect.offlineutils.commandline.Main")
    assert command[main_index + 1] == "predict"
    assert "--predict" not in command


def test_generate_state_does_not_include_execution_options(tmp_path: Path):
    pipeline = _make_pipeline(tmp_path, algorithm_definition={}, jvm_options=None)
    pipeline.algorithm_pipeline_context = pipeline.algorithm_pipeline_context._replace(
        max_threads=3,
        queue_length=7,
        batch_size=11,
    )

    command = pipeline._base_command(task_name="generate_state", metadata_location=str(tmp_path / "meta"))

    main_index = command.index("com.hotvect.offlineutils.commandline.Main")
    assert command[main_index + 1] == "generate-state"
    assert "--max-threads" not in command
    assert "--queue-length" not in command
    assert "--batch-size" not in command


def test_generate_state_prefers_hyphenated_task_key_for_jvm_args(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "generate-state": {"jvm_args": ["-Xmx2g"]},
                "generate_state": {"jvm_args": ["-Xmx1g"]},
            },
        },
        jvm_options=None,
    )

    command = pipeline._base_command(task_name="generate_state", metadata_location=str(tmp_path / "meta"))

    assert "-Xmx2g" in command
    assert "-Xmx1g" not in command


def test_generate_state_accepts_legacy_underscored_task_key_for_jvm_args(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "generate_state": {"jvm_args": ["-Xmx2g"]},
            },
        },
        jvm_options=None,
    )

    command = pipeline._base_command(task_name="generate-state", metadata_location=str(tmp_path / "meta"))

    assert "-Xmx2g" in command
