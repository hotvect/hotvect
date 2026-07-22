import importlib.util
import sys
from importlib.machinery import SourceFileLoader
from pathlib import Path

import pytest


def _load_hv_module():
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "bin" / "hv"
        if candidate.exists():
            loader = SourceFileLoader("hv_cli_passthrough", str(candidate))
            spec = importlib.util.spec_from_loader(loader.name, loader)
            assert spec is not None
            module = importlib.util.module_from_spec(spec)
            loader.exec_module(module)
            return module
    raise FileNotFoundError("Could not locate bin/hv relative to test file")


def test_hv_audit_accepts_explicit_passthrough_after_double_dash(monkeypatch, tmp_path: Path):
    hv = _load_hv_module()
    captured = {}

    monkeypatch.setattr(hv.hotvect.hotvectjar, "HOTVECT_JAR_PATH", Path("/tmp/offline.jar"))

    def _capture_run(cmd, metadata_dir: Path, env=None):
        captured["cmd"] = cmd
        captured["metadata_dir"] = metadata_dir

    monkeypatch.setattr(hv, "_tee_subprocess_output_to_metadata_dir", _capture_run)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "hv",
            "audit",
            "--algorithm-jar",
            "algo.jar",
            "--algorithm-name",
            "demo-algo",
            "--source-path",
            "input.jsonl",
            "--dest-path",
            "out.jsonl",
            "--metadata-path",
            str(tmp_path / "meta"),
            "--",
            "-Xmx2g",
            "-Dfoo=bar",
        ],
    )

    hv.main()

    assert captured["cmd"][:3] == ["java", "-Xmx2g", "-Dfoo=bar"]
    assert captured["cmd"][captured["cmd"].index("com.hotvect.offlineutils.commandline.Main") + 1] == "audit"


def test_hv_audit_uses_default_runtime_jvm_args_without_passthrough(monkeypatch, tmp_path: Path):
    hv = _load_hv_module()
    captured = {}

    monkeypatch.setattr(hv.hotvect.hotvectjar, "HOTVECT_JAR_PATH", Path("/tmp/offline.jar"))

    def _capture_run(cmd, metadata_dir: Path, env=None):
        captured["cmd"] = cmd

    monkeypatch.setattr(hv, "_tee_subprocess_output_to_metadata_dir", _capture_run)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "hv",
            "audit",
            "--algorithm-jar",
            "algo.jar",
            "--algorithm-name",
            "demo-algo",
            "--source-path",
            "input.jsonl",
            "--dest-path",
            "out.jsonl",
            "--metadata-path",
            str(tmp_path / "meta"),
        ],
    )

    hv.main()

    assert captured["cmd"][1:3] == ["-XX:MaxRAMPercentage=80", "-XX:+ExitOnOutOfMemoryError"]


def test_hv_audit_rejects_conflicting_heap_caps(monkeypatch, tmp_path: Path):
    hv = _load_hv_module()

    monkeypatch.setattr(hv.hotvect.hotvectjar, "HOTVECT_JAR_PATH", Path("/tmp/offline.jar"))
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "hv",
            "audit",
            "--algorithm-jar",
            "algo.jar",
            "--algorithm-name",
            "demo-algo",
            "--source-path",
            "input.jsonl",
            "--dest-path",
            "out.jsonl",
            "--metadata-path",
            str(tmp_path / "meta"),
            "--",
            "-Xmx2g",
            "-XX:MaxRAMPercentage=80",
        ],
    )

    with pytest.raises(ValueError, match="Specify either -Xmx"):
        hv.main()


def test_hv_rejects_implicit_passthrough_without_double_dash(monkeypatch, capsys):
    hv = _load_hv_module()

    monkeypatch.setattr(
        sys,
        "argv",
        [
            "hv",
            "serve",
            "--algorithm-jar",
            "algo.jar",
            "--algorithm-name",
            "demo-algo",
            "--parameter-path",
            "params.zip",
            "--port",
            "8080",
            "-Xmx2g",
        ],
    )

    with pytest.raises(SystemExit) as excinfo:
        hv.main()

    captured = capsys.readouterr()
    assert excinfo.value.code == 2
    assert "unrecognized arguments: -Xmx2g" in captured.err


def test_hv_rejects_passthrough_for_train(monkeypatch, capsys):
    hv = _load_hv_module()

    monkeypatch.setattr(
        sys,
        "argv",
        [
            "hv",
            "train",
            "--algorithm-name",
            "demo-algo",
            "--algorithm-jar",
            "algo.jar",
            "--last-test-time",
            "2026-01-07",
            "--",
            "-Xmx2g",
        ],
    )

    with pytest.raises(SystemExit) as excinfo:
        hv.main()

    captured = capsys.readouterr()
    assert excinfo.value.code == 2
    assert "does not accept passthrough arguments after '--'" in captured.err


def test_hv_rejects_unknown_flag_in_strict_mode(monkeypatch, capsys):
    hv = _load_hv_module()

    monkeypatch.setattr(
        sys,
        "argv",
        [
            "hv",
            "train",
            "--algorithm-name",
            "demo-algo",
            "--algorithm-jar",
            "algo.jar",
            "--last-test-time",
            "2026-01-07",
            "--dat-base-dir",
            "/tmp/data",
        ],
    )

    with pytest.raises(SystemExit) as excinfo:
        hv.main()

    captured = capsys.readouterr()
    assert excinfo.value.code == 2
    assert "unrecognized arguments: --dat-base-dir /tmp/data" in captured.err


def test_hv_predict_passes_ordering_flags_to_java(monkeypatch, tmp_path: Path):
    hv = _load_hv_module()
    captured = {}

    monkeypatch.setattr(hv.hotvect.hotvectjar, "HOTVECT_JAR_PATH", Path("/tmp/offline.jar"))

    def _capture_run(cmd, metadata_dir: Path, env=None):
        captured["cmd"] = cmd
        captured["metadata_dir"] = metadata_dir

    monkeypatch.setattr(hv, "_tee_subprocess_output_to_metadata_dir", _capture_run)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "hv",
            "predict",
            "--algorithm-jar",
            "algo.jar",
            "--algorithm-name",
            "demo-algo",
            "--parameter-path",
            "params.zip",
            "--source-path",
            "input.jsonl",
            "--dest-path",
            "predictions",
            "--metadata-path",
            str(tmp_path / "meta"),
            "--ordered",
            "--writer-num-shards",
            "1",
        ],
    )

    hv.main()

    assert "--ordered" in captured["cmd"]
    assert "--writer-num-shards" in captured["cmd"]
    assert "1" in captured["cmd"]


def test_hv_audit_passes_unordered_flags_to_java(monkeypatch, tmp_path: Path):
    hv = _load_hv_module()
    captured = {}

    monkeypatch.setattr(hv.hotvect.hotvectjar, "HOTVECT_JAR_PATH", Path("/tmp/offline.jar"))

    def _capture_run(cmd, metadata_dir: Path, env=None):
        captured["cmd"] = cmd
        captured["metadata_dir"] = metadata_dir

    monkeypatch.setattr(hv, "_tee_subprocess_output_to_metadata_dir", _capture_run)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "hv",
            "audit",
            "--algorithm-jar",
            "algo.jar",
            "--algorithm-name",
            "demo-algo",
            "--parameter-path",
            "params.zip",
            "--source-path",
            "input.jsonl",
            "--dest-path",
            "audit-output",
            "--metadata-path",
            str(tmp_path / "meta"),
            "--unordered",
            "--writer-num-shards",
            "8",
        ],
    )

    hv.main()

    assert "--unordered" in captured["cmd"]
    assert "--writer-num-shards" in captured["cmd"]
    assert "8" in captured["cmd"]


def test_hv_encode_passes_unordered_flags_to_java(monkeypatch, tmp_path: Path):
    hv = _load_hv_module()
    captured = {}

    monkeypatch.setattr(hv.hotvect.hotvectjar, "HOTVECT_JAR_PATH", Path("/tmp/offline.jar"))

    def _capture_run(cmd, metadata_dir: Path, env=None):
        captured["cmd"] = cmd
        captured["metadata_dir"] = metadata_dir

    monkeypatch.setattr(hv, "_tee_subprocess_output_to_metadata_dir", _capture_run)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "hv",
            "encode",
            "--algorithm-jar",
            "algo.jar",
            "--algorithm-name",
            "demo-algo",
            "--source-path",
            "input.jsonl",
            "--dest-path",
            "encoded-output",
            "--metadata-path",
            str(tmp_path / "meta"),
            "--unordered",
            "--writer-num-shards",
            "8",
        ],
    )

    hv.main()

    assert "--unordered" in captured["cmd"]
    assert "--writer-num-shards" in captured["cmd"]
    assert "8" in captured["cmd"]
    assert "--ordered" not in captured["cmd"]


def test_hv_rejects_conflicting_order_flags_for_audit(monkeypatch):
    hv = _load_hv_module()

    monkeypatch.setattr(
        sys,
        "argv",
        [
            "hv",
            "audit",
            "--algorithm-jar",
            "algo.jar",
            "--algorithm-name",
            "demo-algo",
            "--parameter-path",
            "params.zip",
            "--source-path",
            "input.jsonl",
            "--dest-path",
            "audit-output",
            "--ordered",
            "--unordered",
        ],
    )

    with pytest.raises(ValueError) as excinfo:
        hv.main()

    assert "At most one of --ordered and --unordered may be specified" in str(excinfo.value)


def test_hv_rejects_conflicting_order_flags_for_encode(monkeypatch):
    hv = _load_hv_module()

    monkeypatch.setattr(
        sys,
        "argv",
        [
            "hv",
            "encode",
            "--algorithm-jar",
            "algo.jar",
            "--algorithm-name",
            "demo-algo",
            "--source-path",
            "input.jsonl",
            "--dest-path",
            "encoded-output",
            "--ordered",
            "--unordered",
        ],
    )

    with pytest.raises(ValueError) as excinfo:
        hv.main()

    assert "At most one of --ordered and --unordered may be specified" in str(excinfo.value)


def test_hv_rejects_multiple_writer_shards_for_ordered_predict(monkeypatch):
    hv = _load_hv_module()

    monkeypatch.setattr(
        sys,
        "argv",
        [
            "hv",
            "predict",
            "--algorithm-jar",
            "algo.jar",
            "--algorithm-name",
            "demo-algo",
            "--parameter-path",
            "params.zip",
            "--source-path",
            "input.jsonl",
            "--dest-path",
            "prediction-output",
            "--ordered",
            "--writer-num-shards",
            "8",
        ],
    )

    with pytest.raises(ValueError) as excinfo:
        hv.main()

    assert "--writer-num-shards > 1 may only be used with --unordered" in str(excinfo.value)
