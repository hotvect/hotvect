import tempfile
import zipfile
from pathlib import Path
from types import SimpleNamespace

from hotvect.sagemaker_exp import SageMakerScriptExecutor


def test_sagemaker_script_executor_runs_custom_py(monkeypatch):
    with tempfile.TemporaryDirectory() as temp_dir:
        temp_dir_path = Path(temp_dir)

        custom_py_contents = "import sys\nprint('CUSTOM_SCRIPT_RAN')\nsys.exit(0)\n"
        archive_path = temp_dir_path / "payload.zip"
        with zipfile.ZipFile(archive_path, "w") as zipf:
            zipf.writestr("custom.py", custom_py_contents)

        executor = SageMakerScriptExecutor()
        executor.sagemaker_env = SimpleNamespace(
            log_level="INFO",
            hyperparameters={"s3_uri_custom_jar": "s3://bucket/key/payload.zip"},
            input_dir="/opt/ml/input",
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
