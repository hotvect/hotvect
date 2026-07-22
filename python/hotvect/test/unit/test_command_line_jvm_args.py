import atexit
from pathlib import Path

import pytest


def _ensure_offline_util_jar_present() -> None:
    jar_dir = Path(__file__).resolve().parents[2] / "hotvectjar"
    jar_dir.mkdir(parents=True, exist_ok=True)
    required_patterns = {
        "hotvect-offline-util-*-jar-with-dependencies.jar": "hotvect-offline-util-test-jar-with-dependencies.jar",
        "hotvect-algorithm-demo-*-jar-with-dependencies.jar": "hotvect-algorithm-demo-test-jar-with-dependencies.jar",
    }
    for pattern, filename in required_patterns.items():
        existing = list(jar_dir.glob(pattern))
        if len(existing) == 0:
            dummy = jar_dir / filename
            dummy.touch(exist_ok=True)
            atexit.register(lambda path=dummy: path.unlink(missing_ok=True))


def test_run_performance_test_adds_default_heap_cap_for_non_heap_java_args(monkeypatch, tmp_path: Path) -> None:
    _ensure_offline_util_jar_present()
    from hotvect import command_line as module

    captured = {}
    monkeypatch.setattr(module.hotvect.hotvectjar, "HOTVECT_JAR_PATH", Path("/tmp/offline.jar"))

    def _capture_stream_output(cmd, _writer):
        captured["cmd"] = cmd
        return 0

    monkeypatch.setattr(module, "stream_output", _capture_stream_output)

    with pytest.raises(SystemExit) as excinfo:
        module.run_performance_test(
            algorithm_jar=tmp_path / "algo.jar",
            algorithm_name="demo-algo",
            metadata_dir=tmp_path / "meta",
            source_path=tmp_path / "data.jsonl",
            parameter=tmp_path / "params.zip",
            samples=10,
            sample_pool_size=-1,
            java_args=["-Dfoo=bar"],
        )

    assert excinfo.value.code == 0
    assert captured["cmd"][:4] == [
        "java",
        "-Dfoo=bar",
        "-XX:MaxRAMPercentage=80",
        "-XX:+ExitOnOutOfMemoryError",
    ]
