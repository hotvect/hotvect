import sys
from pathlib import Path
from types import ModuleType

import hotvect


def _import_pyhotvect(monkeypatch):
    fake_hotvectjar = ModuleType("hotvect.hotvectjar")
    fake_hotvectjar.HOTVECT_JAR_PATH = Path("/tmp/offline.jar")
    fake_hotvectjar.HOTVECT_ALGORITHM_SERVE_JAR_PATH = Path("/tmp/serve.jar")
    fake_hotvectjar.HOTVECT_ALGORITHM_DEMO_JAR_PATH = Path("/tmp/demo.jar")
    monkeypatch.setitem(sys.modules, "hotvect.hotvectjar", fake_hotvectjar)
    monkeypatch.setattr(hotvect, "hotvectjar", fake_hotvectjar, raising=False)

    from hotvect import pyhotvect

    return pyhotvect


class RecordingStdout:
    def __init__(self):
        self.chunks = []
        self.flush_count = 0

    def write(self, chunk):
        self.chunks.append(chunk)

    def flush(self):
        self.flush_count += 1


class FakePipeline:
    def __init__(self, base_dir):
        self.base_dir = Path(base_dir)

    def _stage_metadata_dir(self, stage):
        return str(self.base_dir / stage)


def test_stream_output_to_stage_log_flushes_stdout(monkeypatch, tmp_path):
    pyhotvect = _import_pyhotvect(monkeypatch)

    stage_dir = tmp_path / "encode"
    stage_dir.mkdir()
    stdout = RecordingStdout()

    def fake_stream_output(cmd, display, env=None):
        display("first\n")
        display("second\n")

    monkeypatch.setattr(pyhotvect, "stream_output", fake_stream_output)
    monkeypatch.setattr(pyhotvect.sys, "stdout", stdout)

    pyhotvect.AlgorithmPipeline._stream_output_to_stage_log(
        FakePipeline(tmp_path),
        stage="encode",
        cmd=["hotvect-offline-util"],
    )

    assert stdout.chunks == ["first\n", "second\n"]
    assert stdout.flush_count == 2
    assert (stage_dir / "stdout-stderr.log").read_text() == "first\nsecond\n"
