import json
import shutil
import subprocess
import sys
import zipfile
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from types import ModuleType

import pytest

FLOAT_EPSILON = 1e-12
UNORDERED_SHARDS = 3


@dataclass(frozen=True)
class RunResult:
    output_path: Path
    metadata: dict


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[4]


def _python_dir() -> Path:
    return Path(__file__).resolve().parents[3]


def _parity_algorithm_source() -> Path:
    return (
        _repo_root()
        / "hotvect-offline-util"
        / "src"
        / "test"
        / "java"
        / "com"
        / "hotvect"
        / "offlineutils"
        / "commandline"
        / "ParityFixtureAlgorithm.java"
    )


def _parity_precompiled_class_dir() -> Path:
    return (
        _repo_root()
        / "hotvect-offline-util"
        / "target"
        / "test-classes"
        / "com"
        / "hotvect"
        / "offlineutils"
        / "commandline"
    )


def _parity_parameter_zip() -> Path:
    return (
        _repo_root()
        / "hotvect-offline-util"
        / "src"
        / "test"
        / "resources"
        / "com"
        / "hotvect"
        / "offlineutils"
        / "commandline"
        / "test-algorithm-parameter.zip"
    )


def _parity_algorithm_definition_resource() -> Path:
    return (
        _repo_root()
        / "hotvect-offline-util"
        / "src"
        / "test"
        / "resources"
        / "test-algorithm-algorithm-definition.json"
    )


@lru_cache(maxsize=1)
def _bundled_offline_util_supports_predict_audit_ordering() -> bool:
    if shutil.which("java") is None:
        pytest.skip("local hv parity path requires a Java runtime")

    from hotvect.hotvectjar import HOTVECT_JAR_PATH

    for task in ("predict", "audit"):
        help_result = subprocess.run(
            [
                "java",
                "-cp",
                str(HOTVECT_JAR_PATH),
                "com.hotvect.offlineutils.commandline.Main",
                task,
                "--help",
            ],
            text=True,
            capture_output=True,
        )
        if help_result.returncode != 0:
            raise AssertionError(
                f"Failed to inspect bundled offline util help for {task}.\n"
                f"jar: {HOTVECT_JAR_PATH}\n"
                f"stdout:\n{help_result.stdout}\n"
                f"stderr:\n{help_result.stderr}"
            )
        if "--ordered" not in help_result.stdout or "--unordered" not in help_result.stdout:
            return False
    return True


def _build_parity_algorithm_jar(tmp_path: Path) -> Path:
    if shutil.which("java") is None:
        pytest.skip("local hv parity path requires a Java runtime")

    definition_resource = _parity_algorithm_definition_resource()
    if not definition_resource.exists():
        raise AssertionError(f"Missing fixture algorithm definition resource: {definition_resource}")

    compiled_classes_dir = tmp_path / "compiled-classes"
    packaged_class_files: list[tuple[Path, Path]] = []

    precompiled_class_dir = _parity_precompiled_class_dir()
    precompiled_class_files = sorted(precompiled_class_dir.glob("ParityFixtureAlgorithm*.class"))
    if precompiled_class_files:
        packaged_class_files = [
            (
                class_file,
                Path("com/hotvect/offlineutils/commandline") / class_file.name,
            )
            for class_file in precompiled_class_files
        ]
    else:
        if shutil.which("javac") is None:
            pytest.skip("requires precompiled parity fixture classes or javac")

        from hotvect.hotvectjar import HOTVECT_JAR_PATH

        source_file = _parity_algorithm_source()
        if not source_file.exists():
            raise AssertionError(f"Missing parity fixture source file: {source_file}")

        compiled_classes_dir.mkdir(parents=True, exist_ok=True)

        compile_result = subprocess.run(
            [
                "javac",
                "-cp",
                str(HOTVECT_JAR_PATH),
                "-d",
                str(compiled_classes_dir),
                str(source_file),
            ],
            text=True,
            capture_output=True,
        )
        if compile_result.returncode != 0:
            raise AssertionError(
                "Failed to compile parity fixture algorithm.\n"
                f"stdout:\n{compile_result.stdout}\n"
                f"stderr:\n{compile_result.stderr}"
            )

        compiled_class_files = sorted(
            compiled_classes_dir.glob("com/hotvect/offlineutils/commandline/ParityFixtureAlgorithm*.class")
        )
        if not compiled_class_files:
            raise AssertionError(f"Parity fixture classes were not compiled into {compiled_classes_dir}.")
        packaged_class_files = [
            (
                class_file,
                class_file.relative_to(compiled_classes_dir),
            )
            for class_file in compiled_class_files
        ]

    algorithm_jar = tmp_path / "test-algorithm-fixture.jar"
    with zipfile.ZipFile(algorithm_jar, "w", compression=zipfile.ZIP_DEFLATED) as jar_file:
        jar_file.write(definition_resource, "test-algorithm-algorithm-definition.json")
        for class_file, jar_path in packaged_class_files:
            jar_file.write(class_file, jar_path.as_posix())
    return algorithm_jar


def _write_source_dir(base_dir: Path) -> tuple[Path, list[str]]:
    source_dir = base_dir / "source"
    source_dir.mkdir(parents=True, exist_ok=True)

    ordered_example_ids: list[str] = []
    file_rows = {
        "part-0001.jsonl": [
            ("example-0001", 0.125),
            ("example-0002", 0.750),
            ("example-0003", 1.250),
            ("example-0004", 1.875),
            ("example-0005", 2.500),
            ("example-0006", 3.125),
        ],
        "part-0002.jsonl": [
            ("example-0007", 0.333),
            ("example-0008", 0.875),
            ("example-0009", 1.625),
            ("example-0010", 2.375),
            ("example-0011", 2.875),
            ("example-0012", 3.500),
        ],
    }

    for filename in sorted(file_rows):
        file_path = source_dir / filename
        lines = []
        for example_id, base_value in file_rows[filename]:
            ordered_example_ids.append(example_id)
            lines.append(f"{example_id}|{base_value:.6f}\n")
        file_path.write_text("".join(lines))

    return source_dir, ordered_example_ids


def _load_jsonl_records(path: Path) -> list[dict]:
    if path.is_file():
        files = [path]
    else:
        files = sorted(path.glob("part-*.jsonl"))
        assert files, f"No part files found under {path}"

    records: list[dict] = []
    for file_path in files:
        for line in file_path.read_text().splitlines():
            if line.strip():
                records.append(json.loads(line))
    return records


def _sorted_records(path: Path) -> list[dict]:
    records = _load_jsonl_records(path)
    example_ids = [record["example_id"] for record in records]
    assert len(example_ids) == len(set(example_ids)), "Fixture dataset must produce unique example_ids"
    return sorted(records, key=lambda record: record["example_id"])


def _assert_json_close(left, right, path: str = "root") -> None:
    numeric_types = (int, float)
    if (
        isinstance(left, numeric_types)
        and isinstance(right, numeric_types)
        and not isinstance(left, bool)
        and not isinstance(right, bool)
    ):
        assert abs(float(left) - float(right)) <= FLOAT_EPSILON, (
            f"{path}: numeric mismatch left={left!r} right={right!r} " f"(abs_delta={abs(float(left) - float(right))})"
        )
        return

    if isinstance(left, dict) and isinstance(right, dict):
        assert set(left) == set(right), f"{path}: key mismatch left={sorted(left)} right={sorted(right)}"
        for key in sorted(left):
            _assert_json_close(left[key], right[key], f"{path}.{key}")
        return

    if isinstance(left, list) and isinstance(right, list):
        assert len(left) == len(right), f"{path}: list length mismatch left={len(left)} right={len(right)}"
        for index, (left_item, right_item) in enumerate(zip(left, right)):
            _assert_json_close(left_item, right_item, f"{path}[{index}]")
        return

    assert left == right, f"{path}: value mismatch left={left!r} right={right!r}"


def _assert_output_parity(left_path: Path, right_path: Path) -> None:
    left_records = _sorted_records(left_path)
    right_records = _sorted_records(right_path)
    assert len(left_records) == len(right_records)
    for index, (left_record, right_record) in enumerate(zip(left_records, right_records)):
        _assert_json_close(left_record, right_record, f"record[{index}]")


def _assert_ordered_example_order(output_path: Path, expected_order: list[str]) -> None:
    actual_order = [record["example_id"] for record in _load_jsonl_records(output_path)]
    assert actual_order == expected_order


def _assert_unordered_output_layout(output_path: Path) -> None:
    assert output_path.is_dir(), f"Expected unordered output directory, got {output_path}"
    part_files = sorted(output_path.glob("part-*.jsonl"))
    assert [path.name for path in part_files] == [f"part-{index:05d}.jsonl" for index in range(UNORDERED_SHARDS)]


def _assert_predict_ordered_layout(output_path: Path) -> None:
    assert output_path.is_dir(), f"Expected prediction directory, got {output_path}"
    part_files = sorted(output_path.glob("part-*.jsonl"))
    assert [path.name for path in part_files] == ["part-00000.jsonl"]


def _metadata_subset(task: str, metadata: dict) -> dict:
    common = {key: metadata[key] for key in ["lines_written", "total_record_count"] if key in metadata}
    for optional_key in ["lines_read", "number_of_files_read"]:
        if optional_key in metadata:
            common[optional_key] = metadata[optional_key]
    if task == "audit":
        common.update(
            {
                "audit_output_ordering": metadata["audit_output_ordering"],
                "audit_writer_num_shards": metadata["audit_writer_num_shards"],
                "example_decoder": metadata["example_decoder"],
                "example_encoder": metadata["example_encoder"],
            }
        )
    else:
        common.update(
            {
                "prediction_output_ordering": metadata["prediction_output_ordering"],
                "prediction_writer_num_shards": metadata["prediction_writer_num_shards"],
            }
        )
    return common


def _assert_expected_metadata(task: str, metadata: dict, *, ordered: bool, expected_records: int) -> None:
    assert metadata["total_record_count"] == expected_records
    assert metadata["lines_written"] == expected_records
    if "number_of_files_read" in metadata:
        assert metadata["number_of_files_read"] == 2
    if "lines_read" in metadata:
        assert metadata["lines_read"] == expected_records
    if task == "audit":
        assert metadata["audit_output_ordering"] == ("ordered" if ordered else "unordered")
        assert metadata["audit_writer_num_shards"] == (1 if ordered else UNORDERED_SHARDS)
    else:
        assert metadata["prediction_output_ordering"] == ("ordered" if ordered else "unordered")
        assert metadata["prediction_writer_num_shards"] == (1 if ordered else UNORDERED_SHARDS)


def _run_local(
    *,
    task: str,
    ordered: bool,
    source_dir: Path,
    algorithm_jar: Path,
    parameter_zip: Path,
    tmp_path: Path,
) -> RunResult:
    python_dir = _python_dir()
    mode_dir = tmp_path / "local" / task / ("ordered" if ordered else "unordered")
    metadata_dir = mode_dir / "meta"
    output_dir = mode_dir / "out"
    metadata_dir.mkdir(parents=True, exist_ok=True)
    output_dir.mkdir(parents=True, exist_ok=True)

    if task == "audit":
        destination_path = output_dir / "audit"
    else:
        destination_path = output_dir / "prediction"

    command = [
        sys.executable,
        str(python_dir / "bin" / "hv"),
        task,
        "--algorithm-jar",
        str(algorithm_jar),
        "--algorithm-name",
        "test-algorithm",
        "--parameter-path",
        str(parameter_zip),
        "--source-path",
        str(source_dir),
        "--dest-path",
        str(destination_path),
        "--metadata-path",
        str(metadata_dir),
        "--ordered" if ordered else "--unordered",
    ]
    if not ordered:
        command.extend(["--writer-num-shards", str(UNORDERED_SHARDS)])

    completed = subprocess.run(
        command,
        cwd=python_dir,
        text=True,
        capture_output=True,
    )
    if completed.returncode != 0:
        raise AssertionError(
            f"Local {task} {'ordered' if ordered else 'unordered'} run failed.\n"
            f"stdout:\n{completed.stdout}\n"
            f"stderr:\n{completed.stderr}"
        )

    metadata = json.loads((metadata_dir / "metadata.json").read_text())
    return RunResult(output_path=destination_path, metadata=metadata)


def _run_sagemaker(
    *,
    monkeypatch,
    task: str,
    ordered: bool,
    source_dir: Path,
    algorithm_jar: Path,
    parameter_zip: Path,
    tmp_path: Path,
) -> RunResult:
    import hotvect.sagemaker_tasks as st

    input_root = tmp_path / "sagemaker" / task / ("ordered" if ordered else "unordered") / "input"
    output_data_dir = tmp_path / "sagemaker" / task / ("ordered" if ordered else "unordered") / "output_data"
    output_dir = tmp_path / "sagemaker" / task / ("ordered" if ordered else "unordered") / "output"
    algorithm_definition_resource = _parity_algorithm_definition_resource()
    shutil.copytree(source_dir, input_root / "data" / "source", dirs_exist_ok=True)
    output_data_dir.mkdir(parents=True, exist_ok=True)
    output_dir.mkdir(parents=True, exist_ok=True)

    hyperparameters = {
        "hotvect_task": task,
        "hotvect_task_output": json.dumps({"s3_uri": "s3://fixture-bucket/task-output", "compression": "none"}),
        "s3_uri_algorithm_jar": f"s3://fixture-bucket/{algorithm_jar.name}",
        "s3_uri_parameter_zip": f"s3://fixture-bucket/{parameter_zip.name}",
        "s3_uri_algorithm_definition": f"s3://fixture-bucket/{algorithm_definition_resource.name}",
        "s3_uri_metadata": "s3://fixture-bucket/meta",
        "s3_uri_result_file": "s3://fixture-bucket/result.oneshot.json",
        "hotvect_source_channel": "source",
    }
    if ordered:
        hyperparameters["hotvect_ordered"] = "true"
    else:
        hyperparameters["hotvect_unordered"] = "true"
        hyperparameters["hotvect_writer_num_shards"] = str(UNORDERED_SHARDS)

    class FakeEnvironment:
        def __init__(self):
            self.hyperparameters = hyperparameters
            self.input_dir = str(input_root)
            self.output_data_dir = str(output_data_dir)
            self.output_dir = str(output_dir)

    env_module = ModuleType("sagemaker_training.environment")
    env_module.Environment = FakeEnvironment
    monkeypatch.setitem(sys.modules, "sagemaker_training", ModuleType("sagemaker_training"))
    monkeypatch.setitem(sys.modules, "sagemaker_training.environment", env_module)

    def _copy_fixture_artifact(basename: str, dest_path: Path) -> None:
        if basename == algorithm_jar.name:
            shutil.copyfile(algorithm_jar, dest_path)
            return
        if basename == parameter_zip.name:
            shutil.copyfile(parameter_zip, dest_path)
            return
        if basename == algorithm_definition_resource.name:
            shutil.copyfile(algorithm_definition_resource, dest_path)
            return
        raise AssertionError(f"Unexpected download request for {basename}")

    class FakeS3Client:
        def download_file(self, Bucket: str, Key: str, Filename: str) -> None:
            del Bucket
            _copy_fixture_artifact(Path(Key).name, Path(Filename))

        def download_fileobj(self, Bucket: str, Key: str, Fileobj) -> None:
            del Bucket
            basename = Path(Key).name
            if basename == algorithm_jar.name:
                Fileobj.write(algorithm_jar.read_bytes())
                return
            if basename == parameter_zip.name:
                Fileobj.write(parameter_zip.read_bytes())
                return
            if basename == algorithm_definition_resource.name:
                Fileobj.write(algorithm_definition_resource.read_bytes())
                return
            raise AssertionError(f"Unexpected download request for {basename}")

    monkeypatch.setattr(st.boto3, "client", lambda *_args, **_kwargs: FakeS3Client())

    def _fake_download(s3_uri: str, dest_path: Path, _client) -> None:
        _copy_fixture_artifact(Path(s3_uri).name, dest_path)

    monkeypatch.setattr(st, "_download_s3_file", _fake_download)
    monkeypatch.setattr(st, "_upload_directory_to_s3", lambda *_args, **_kwargs: None)
    monkeypatch.setattr(st, "_upload_file_to_s3", lambda *_args, **_kwargs: None)

    st.run_one_shot_from_sagemaker_env()

    if task == "audit":
        output_path = output_data_dir / "out" / "audit"
    else:
        output_path = output_data_dir / "out" / "prediction"
    metadata = json.loads((output_data_dir / "meta" / task / "metadata.json").read_text())
    return RunResult(output_path=output_path, metadata=metadata)


@pytest.mark.parametrize("task", ["audit", "predict"])
def test_local_and_sagemaker_parity_for_predict_and_audit(monkeypatch, tmp_path: Path, task: str):
    if not _bundled_offline_util_supports_predict_audit_ordering():
        from hotvect.hotvectjar import HOTVECT_JAR_PATH

        pytest.skip(
            "bundled hotvect-offline-util jar does not support predict/audit ordering flags; "
            f"update or rebuild the bundled jar to run this parity test ({HOTVECT_JAR_PATH})"
        )

    algorithm_jar = _build_parity_algorithm_jar(tmp_path)
    parameter_zip = _parity_parameter_zip()
    source_dir, expected_order = _write_source_dir(tmp_path)

    local_ordered = _run_local(
        task=task,
        ordered=True,
        source_dir=source_dir,
        algorithm_jar=algorithm_jar,
        parameter_zip=parameter_zip,
        tmp_path=tmp_path,
    )
    local_unordered = _run_local(
        task=task,
        ordered=False,
        source_dir=source_dir,
        algorithm_jar=algorithm_jar,
        parameter_zip=parameter_zip,
        tmp_path=tmp_path,
    )
    sagemaker_ordered = _run_sagemaker(
        monkeypatch=monkeypatch,
        task=task,
        ordered=True,
        source_dir=source_dir,
        algorithm_jar=algorithm_jar,
        parameter_zip=parameter_zip,
        tmp_path=tmp_path,
    )
    sagemaker_unordered = _run_sagemaker(
        monkeypatch=monkeypatch,
        task=task,
        ordered=False,
        source_dir=source_dir,
        algorithm_jar=algorithm_jar,
        parameter_zip=parameter_zip,
        tmp_path=tmp_path,
    )

    if task == "audit":
        _assert_ordered_example_order(local_ordered.output_path, expected_order)
        _assert_ordered_example_order(sagemaker_ordered.output_path, expected_order)
        _assert_unordered_output_layout(local_unordered.output_path)
        _assert_unordered_output_layout(sagemaker_unordered.output_path)
    else:
        _assert_predict_ordered_layout(local_ordered.output_path)
        _assert_predict_ordered_layout(sagemaker_ordered.output_path)
        _assert_ordered_example_order(local_ordered.output_path, expected_order)
        _assert_ordered_example_order(sagemaker_ordered.output_path, expected_order)
        _assert_unordered_output_layout(local_unordered.output_path)
        _assert_unordered_output_layout(sagemaker_unordered.output_path)

    _assert_output_parity(local_ordered.output_path, sagemaker_ordered.output_path)
    _assert_output_parity(local_unordered.output_path, sagemaker_unordered.output_path)
    _assert_output_parity(local_ordered.output_path, local_unordered.output_path)
    _assert_output_parity(sagemaker_ordered.output_path, sagemaker_unordered.output_path)

    expected_records = len(expected_order)
    _assert_expected_metadata(task, local_ordered.metadata, ordered=True, expected_records=expected_records)
    _assert_expected_metadata(task, sagemaker_ordered.metadata, ordered=True, expected_records=expected_records)
    _assert_expected_metadata(task, local_unordered.metadata, ordered=False, expected_records=expected_records)
    _assert_expected_metadata(task, sagemaker_unordered.metadata, ordered=False, expected_records=expected_records)

    _assert_json_close(
        _metadata_subset(task, local_ordered.metadata),
        _metadata_subset(task, sagemaker_ordered.metadata),
        f"{task}.ordered.metadata",
    )
    _assert_json_close(
        _metadata_subset(task, local_unordered.metadata),
        _metadata_subset(task, sagemaker_unordered.metadata),
        f"{task}.unordered.metadata",
    )
