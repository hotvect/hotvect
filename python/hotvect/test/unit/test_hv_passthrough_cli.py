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
