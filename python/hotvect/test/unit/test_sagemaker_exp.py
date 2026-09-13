import tempfile
import zipfile
from pathlib import Path
from types import SimpleNamespace
from urllib.parse import parse_qsl

import hotvect.sagemaker_exp as sagemaker_exp
from hotvect.sagemaker_exp import SageMakerScriptExecutor


def test_runshell_streams_and_flushes_stdout(monkeypatch):
    class RecordingStdout:
        def __init__(self):
            self.chunks = []
            self.flush_count = 0

        def write(self, chunk):
            self.chunks.append(chunk)

        def flush(self):
            self.flush_count += 1

    stdout = RecordingStdout()
    calls = {}

    def fake_stream_output(command, display_fun, env=None):
        calls["command"] = command
        calls["env"] = env
        display_fun("line 1\n")
        display_fun("line 2\n")

    monkeypatch.setattr(sagemaker_exp, "stream_output", fake_stream_output)
    monkeypatch.setattr(sagemaker_exp.sys, "stdout", stdout)

    result = sagemaker_exp.runshell(["python", "custom.py"], env={"K": "V"})

    assert calls == {"command": ["python", "custom.py"], "env": {"K": "V"}}
    assert stdout.chunks == ["line 1\n", "line 2\n"]
    assert stdout.flush_count == 2
    assert result == {
        "command": "python custom.py",
        "return_code": 0,
        "stdout": "",
        "stderr": "",
    }


def test_sagemaker_script_executor_runs_custom_py(monkeypatch):
    with tempfile.TemporaryDirectory() as temp_dir:
        temp_dir_path = Path(temp_dir)

        custom_py_contents = "import sys\nprint('CUSTOM_SCRIPT_RAN')\nsys.exit(0)\n"
        archive_path = temp_dir_path / "payload.zip"
        with zipfile.ZipFile(archive_path, "w") as zipf:
            zipf.writestr("custom.py", custom_py_contents)

        executor = SageMakerScriptExecutor(
            sagemaker_env=SimpleNamespace(
                log_level="INFO",
                hyperparameters={"s3_uri_custom_jar": "s3://bucket/key/payload.zip"},
                input_dir="/opt/ml/input",
            )
        )

        monkeypatch.setattr(executor, "_download_custom_jar", lambda: archive_path)

        calls = {}

        def fake_runshell(command, shell=False):
            calls["command"] = command
            calls["shell"] = shell
            return {"command": " ".join(map(str, command)), "return_code": 0, "stderr": "", "stdout": ""}

        monkeypatch.setattr("hotvect.sagemaker_exp.runshell", fake_runshell)

        executor.run()

        assert calls["shell"] is False
        assert calls["command"][0] == "python"
        assert calls["command"][1].endswith("/custom.py")
        assert Path(calls["command"][2]).is_file()


def test_sagemaker_script_executor_enables_jfr_in_metadata_dir(monkeypatch, tmp_path: Path):
    archive_path = tmp_path / "payload.zip"
    with zipfile.ZipFile(archive_path, "w") as zipf:
        zipf.writestr("custom.py", "import sys\nprint('CUSTOM_SCRIPT_RAN')\nsys.exit(0)\n")

    jfr_output_dir = tmp_path / "meta" / "jfr"
    monkeypatch.setattr(SageMakerScriptExecutor, "_JFR_METADATA_DIR", jfr_output_dir)
    executor = SageMakerScriptExecutor()
    executor.sagemaker_env = SimpleNamespace(
        log_level="INFO",
        hyperparameters={
            "s3_uri_custom_jar": "s3://bucket/key/payload.zip",
            "s3_uri_metadata": "s3://bucket/meta/job",
            "jfr_enabled": "true",
        },
        input_dir="/opt/ml/input",
    )

    monkeypatch.setattr(executor, "_download_custom_jar", lambda: archive_path)

    def fake_runshell(command, shell=False, env=None):
        assert shell is False
        assert env is not None
        java_tool_options = env["JAVA_TOOL_OPTIONS"]
        assert "-XX:StartFlightRecording=" in java_tool_options
        raw_options = java_tool_options.split("-XX:StartFlightRecording=", maxsplit=1)[1].split()[0]
        recording_options = dict(parse_qsl(raw_options.replace(",", "&")))
        recording_path = Path(recording_options["filename"])
        recording_path.parent.mkdir(parents=True, exist_ok=True)
        recording_path.write_bytes(b"jfr")
        return {"command": " ".join(map(str, command)), "return_code": 0, "stderr": "", "stdout": ""}

    monkeypatch.setattr("hotvect.sagemaker_exp.runshell", fake_runshell)

    executor.run()

    assert (jfr_output_dir / "performance-test.jfr").read_bytes() == b"jfr"
