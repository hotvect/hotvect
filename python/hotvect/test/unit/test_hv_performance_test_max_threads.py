import importlib.util
import sys
from importlib.machinery import SourceFileLoader
from pathlib import Path


def _load_hv_module():
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "bin" / "hv"
        if candidate.exists():
            loader = SourceFileLoader("hv_cli", str(candidate))
            spec = importlib.util.spec_from_loader(loader.name, loader)
            assert spec is not None
            module = importlib.util.module_from_spec(spec)
            loader.exec_module(module)
            return module
    raise FileNotFoundError("Could not locate bin/hv relative to test file")


def _run_hv_performance_test(monkeypatch, tmp_path: Path, *, physical_cores: int, extra_args: list[str] | None = None):
    hv = _load_hv_module()

    captured: dict[str, object] = {}

    def _capture_cmd(cmd, metadata_dir, env=None):
        captured["cmd"] = cmd
        captured["metadata_dir"] = metadata_dir

    monkeypatch.setattr(hv, "_tee_subprocess_output_to_metadata_dir", _capture_cmd)
    monkeypatch.setattr(hv, "_enable_file_logging", lambda _path: None)

    import psutil

    monkeypatch.setattr(psutil, "cpu_count", lambda logical=False: physical_cores)

    args = [
        "hv",
        "performance-test",
        "--algorithm-jar",
        "algo.jar",
        "--algorithm-name",
        "dummy-algorithm",
        "--source-path",
        "data.jsonl",
        "--metadata-path",
        str(tmp_path),
    ]
    if extra_args:
        args.extend(extra_args)

    monkeypatch.setattr(sys, "argv", args)
    hv.main()
    return captured["cmd"]


def test_performance_test_defaults_to_2_threads_on_4plus_physical_cores(monkeypatch, tmp_path: Path):
    cmd = _run_hv_performance_test(monkeypatch, tmp_path, physical_cores=8)
    idx = cmd.index("--max-threads")
    assert cmd[idx + 1] == "2"


def test_performance_test_defaults_to_1_thread_on_small_machines(monkeypatch, tmp_path: Path):
    cmd = _run_hv_performance_test(monkeypatch, tmp_path, physical_cores=2)
    idx = cmd.index("--max-threads")
    assert cmd[idx + 1] == "1"


def test_performance_test_respects_explicit_max_threads(monkeypatch, tmp_path: Path):
    cmd = _run_hv_performance_test(monkeypatch, tmp_path, physical_cores=48, extra_args=["--max-threads", "7"])
    idx = cmd.index("--max-threads")
    assert cmd[idx + 1] == "7"


def test_performance_test_allows_opt_out_with_max_threads_zero(monkeypatch, tmp_path: Path):
    cmd = _run_hv_performance_test(monkeypatch, tmp_path, physical_cores=48, extra_args=["--max-threads", "0"])
    assert "--max-threads" not in cmd
