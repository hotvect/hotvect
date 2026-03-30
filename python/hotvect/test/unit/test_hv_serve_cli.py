import importlib.util
import sys
from importlib.machinery import SourceFileLoader
from pathlib import Path

import pytest


def _load_hv_module():
    hotvectjar_dir = Path(__file__).resolve().parents[2] / "hotvectjar"
    hotvectjar_dir.mkdir(parents=True, exist_ok=True)
    for jar_name in (
        "hotvect-offline-util-10.13.3-jar-with-dependencies.jar",
        "hotvect-algorithm-demo-10.13.3-jar-with-dependencies.jar",
    ):
        jar_path = hotvectjar_dir / jar_name
        jar_path.touch(exist_ok=True)
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


def test_hv_serve_uses_algorithm_demo_server(monkeypatch):
    hv = _load_hv_module()
    captured = {}

    monkeypatch.setattr(hv.hotvect.hotvectjar, "HOTVECT_ALGORITHM_DEMO_JAR_PATH", Path("/tmp/demo.jar"))

    def _capture_run(cmd, *, host, port, health_timeout_seconds, health_request_timeout_seconds, env=None):
        captured["cmd"] = cmd
        captured["host"] = host
        captured["port"] = port
        captured["env"] = env
        captured["health_timeout_seconds"] = health_timeout_seconds

    monkeypatch.setattr(hv, "_run_http_server_process", _capture_run)
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
            "--host",
            "0.0.0.0",
            "--",
            "-Xmx2g",
        ],
    )

    hv.main()

    cmd = captured["cmd"]
    assert cmd[:2] == ["java", "-Xmx2g"]
    assert cmd[cmd.index("-cp") + 1] == "/tmp/demo.jar"
    assert "com.hotvect.algorithmdemo.Main" in cmd
    assert "--ui" not in cmd
    assert cmd[cmd.index("--algorithm-name") + 1] == "demo-algo"
    assert cmd[cmd.index("--host") + 1] == "0.0.0.0"
    assert cmd[cmd.index("--parameter-path") + 1] == "params.zip"
    assert captured["host"] == "0.0.0.0"
    assert captured["port"] == 8080
    assert captured["health_timeout_seconds"] == 120
    assert captured["env"] is None


def test_hv_serve_ui_passes_ui_flags(monkeypatch, tmp_path: Path):
    hv = _load_hv_module()
    captured = {}

    monkeypatch.setattr(hv.hotvect.hotvectjar, "HOTVECT_ALGORITHM_DEMO_JAR_PATH", Path("/tmp/demo.jar"))

    def _capture_run(cmd, *, host, port, health_timeout_seconds, health_request_timeout_seconds, env=None):
        captured["cmd"] = cmd
        captured["host"] = host
        captured["port"] = port

    monkeypatch.setattr(hv, "_run_http_server_process", _capture_run)
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
            "8081",
            "--ui",
            "--source-path",
            str(tmp_path / "examples"),
            "--action-metadata-path",
            str(tmp_path / "metadata"),
            "--demo-sqlite-path",
            str(tmp_path / "demo.db"),
            "--max-request-mib",
            "512",
        ],
    )

    hv.main()

    cmd = captured["cmd"]
    assert "--ui" in cmd
    assert cmd[cmd.index("--source-path") + 1] == str(tmp_path / "examples")
    assert cmd[cmd.index("--action-metadata-path") + 1] == str(tmp_path / "metadata")
    assert cmd[cmd.index("--demo-sqlite-path") + 1] == str(tmp_path / "demo.db")
    assert cmd[cmd.index("--max-request-mib") + 1] == "512"


def test_hv_serve_rejects_source_path_without_ui(monkeypatch, tmp_path: Path):
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
            "8081",
            "--source-path",
            str(tmp_path / "examples"),
        ],
    )

    with pytest.raises(ValueError, match="--source-path is only supported with --ui"):
        hv.main()


def test_hv_worker_serve_uses_tensorflow_backend_from_algo_def(monkeypatch, tmp_path: Path):
    hv = _load_hv_module()
    captured = {}

    monkeypatch.setattr(
        hv,
        "_load_effective_algorithm_definition",
        lambda *_args, **_kwargs: {
            "algorithm_name": "demo-model",
            "algorithm_parameters": {
                "backend": "tensorflow",
                "realtime": {
                    "litserve": {
                        "python_executable": "/usr/bin/python3",
                        "env": {"FOO": "bar"},
                        "accelerator": "cpu",
                        "devices": "auto",
                        "workers_per_device": 1,
                        "startup_timeout_ms": 45000,
                        "request_timeout_ms": 31000,
                    }
                },
            },
        },
    )
    monkeypatch.setattr(
        hv,
        "_extract_model_and_schema_from_parameters",
        lambda *_args, **_kwargs: (tmp_path / "model_parameter", tmp_path / "encoded-schema-description"),
    )

    def _capture_run(cmd, *, host, port, health_timeout_seconds, health_request_timeout_seconds, env=None):
        captured["cmd"] = cmd
        captured["host"] = host
        captured["port"] = port
        captured["health_timeout_seconds"] = health_timeout_seconds
        captured["env"] = env
        captured["health_request_timeout_seconds"] = health_request_timeout_seconds

    monkeypatch.setattr(hv, "_run_http_server_process", _capture_run)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "hv",
            "worker",
            "serve",
            "--algorithm-jar",
            "algo.jar",
            "--algorithm-name",
            "demo-model",
            "--parameter-path",
            "params.zip",
            "--port",
            "8082",
        ],
    )

    hv.main()

    cmd = captured["cmd"]
    assert cmd[0] == "/usr/bin/python3"
    assert cmd[1:3] == ["-m", "hotvect.worker.litserve_server"]
    assert cmd[cmd.index("--backend") + 1] == "tensorflow"
    assert cmd[cmd.index("--model-name") + 1] == "demo-model"
    assert "--debug-include-tf-inputs" not in cmd
    assert captured["host"] == "127.0.0.1"
    assert captured["port"] == 8082
    assert captured["health_timeout_seconds"] == 45
    assert captured["health_request_timeout_seconds"] == 31
    assert captured["env"]["FOO"] == "bar"
    assert captured["env"]["CUDA_VISIBLE_DEVICES"] == ""
    assert "PYTHONPATH" in captured["env"]
    assert captured["env"]["PYTHONUNBUFFERED"] == "1"


def test_hv_worker_serve_uses_batch_scope_debug_flag(monkeypatch, tmp_path: Path):
    hv = _load_hv_module()
    captured = {}

    monkeypatch.setattr(
        hv,
        "_load_effective_algorithm_definition",
        lambda *_args, **_kwargs: {
            "algorithm_name": "demo-model",
            "algorithm_parameters": {
                "backend": "tensorflow",
                "realtime": {
                    "litserve": {
                        "python_executable": "/usr/bin/python3",
                        "accelerator": "cpu",
                        "devices": "auto",
                        "workers_per_device": 1,
                        "startup_timeout_ms": 45000,
                        "debug": {"include_tf_inputs": False},
                    }
                },
                "batch": {
                    "litserve": {
                        "python_executable": "/usr/bin/python3",
                        "accelerator": "cpu",
                        "devices": "auto",
                        "workers_per_device": 1,
                        "startup_timeout_ms": 33000,
                        "debug": {"include_tf_inputs": True},
                    }
                },
            },
        },
    )
    monkeypatch.setattr(
        hv,
        "_extract_model_and_schema_from_parameters",
        lambda *_args, **_kwargs: (tmp_path / "model_parameter", tmp_path / "encoded-schema-description"),
    )

    def _capture_run(cmd, *, host, port, health_timeout_seconds, health_request_timeout_seconds, env=None):
        captured["cmd"] = cmd
        captured["health_timeout_seconds"] = health_timeout_seconds

    monkeypatch.setattr(hv, "_run_http_server_process", _capture_run)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "hv",
            "worker",
            "serve",
            "--algorithm-jar",
            "algo.jar",
            "--algorithm-name",
            "demo-model",
            "--parameter-path",
            "params.zip",
            "--port",
            "8083",
            "--scope",
            "batch",
        ],
    )

    hv.main()

    assert "--debug-include-tf-inputs" in captured["cmd"]
    assert captured["health_timeout_seconds"] == 33


def test_hv_worker_serve_requires_backend_in_algo_def(monkeypatch):
    hv = _load_hv_module()

    monkeypatch.setattr(
        hv,
        "_load_effective_algorithm_definition",
        lambda *_args, **_kwargs: {
            "algorithm_name": "demo-model",
            "algorithm_parameters": {
                "realtime": {"litserve": {}},
            },
        },
    )
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "hv",
            "worker",
            "serve",
            "--algorithm-jar",
            "algo.jar",
            "--algorithm-name",
            "demo-model",
            "--parameter-path",
            "params.zip",
            "--port",
            "8082",
        ],
    )

    with pytest.raises(ValueError, match="algorithm_parameters.backend must be a non-empty string"):
        hv.main()


def test_hv_worker_serve_auto_prefers_realtime_litserve_scope(monkeypatch, tmp_path: Path):
    hv = _load_hv_module()
    captured = {}

    monkeypatch.setattr(
        hv,
        "_load_effective_algorithm_definition",
        lambda *_args, **_kwargs: {
            "algorithm_name": "demo-model",
            "algorithm_parameters": {
                "backend": "tensorflow",
                "batch": {
                    "litserve": {
                        "python_executable": "/usr/bin/python3",
                        "accelerator": "cpu",
                        "startup_timeout_ms": 33000,
                    }
                },
                "realtime": {
                    "litserve": {
                        "python_executable": "/usr/bin/python3",
                        "accelerator": "cpu",
                        "startup_timeout_ms": 47000,
                    }
                },
            },
        },
    )
    monkeypatch.setattr(
        hv,
        "_extract_model_and_schema_from_parameters",
        lambda *_args, **_kwargs: (tmp_path / "model_parameter", tmp_path / "encoded-schema-description"),
    )

    def _capture_run(cmd, *, host, port, health_timeout_seconds, health_request_timeout_seconds, env=None):
        captured["cmd"] = cmd
        captured["health_timeout_seconds"] = health_timeout_seconds

    monkeypatch.setattr(hv, "_run_http_server_process", _capture_run)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "hv",
            "worker",
            "serve",
            "--algorithm-jar",
            "algo.jar",
            "--algorithm-name",
            "demo-model",
            "--parameter-path",
            "params.zip",
            "--port",
            "8084",
        ],
    )

    hv.main()

    assert captured["cmd"][1:3] == ["-m", "hotvect.worker.litserve_server"]
    assert captured["health_timeout_seconds"] == 47


def test_hv_worker_serve_falls_back_to_direct_workers_scope(monkeypatch, tmp_path: Path):
    hv = _load_hv_module()
    captured = {}

    monkeypatch.setattr(
        hv,
        "_load_effective_algorithm_definition",
        lambda *_args, **_kwargs: {
            "algorithm_name": "demo-model",
            "algorithm_parameters": {
                "backend": "tensorflow",
                "batch": {
                    "direct_workers": {
                        "python_executable": "/usr/bin/python3",
                        "accelerator": "cpu",
                        "devices": "auto",
                        "workers_per_device": 1,
                        "startup_timeout_ms": 21000,
                    }
                },
            },
        },
    )
    monkeypatch.setattr(
        hv,
        "_extract_model_and_schema_from_parameters",
        lambda *_args, **_kwargs: (tmp_path / "model_parameter", tmp_path / "encoded-schema-description"),
    )
    monkeypatch.setattr(
        hv,
        "_run_http_server_process",
        lambda cmd, **kwargs: captured.update({"cmd": cmd, **kwargs}),
    )
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "hv",
            "worker",
            "serve",
            "--algorithm-jar",
            "algo.jar",
            "--algorithm-name",
            "demo-model",
            "--parameter-path",
            "params.zip",
            "--port",
            "8085",
        ],
    )

    hv.main()

    assert captured["cmd"][1:3] == ["-m", "hotvect.worker.litserve_server"]
    assert captured["cmd"][captured["cmd"].index("--backend") + 1] == "tensorflow"
    assert captured["health_timeout_seconds"] == 21


def test_select_cuda_visible_device_tokens_rejects_fractional_count():
    hv = _load_hv_module()

    with pytest.raises(ValueError, match="devices numeric value must be an integer"):
        hv._select_cuda_visible_device_tokens(1.5, ["0", "1"])


def test_hv_worker_serve_supports_torch_backend(monkeypatch, tmp_path: Path):
    hv = _load_hv_module()
    captured = {}

    monkeypatch.setattr(
        hv,
        "_load_effective_algorithm_definition",
        lambda *_args, **_kwargs: {
            "algorithm_name": "zmclip-text-encoder",
            "algorithm_parameters": {
                "backend": "torch",
                "realtime": {
                    "litserve": {
                        "python_executable": "/usr/bin/python3",
                        "accelerator": "cpu",
                        "startup_timeout_ms": 25000,
                    }
                },
                "zmclip": {
                    "model_name": "xlm-roberta-base-ViT-B-32",
                    "use_torch_compile": True,
                    "torch_num_threads": 8,
                    "torch_num_interop_threads": 1,
                    "warmup_iterations": 2,
                },
            },
        },
    )
    monkeypatch.setattr(
        hv,
        "_extract_model_and_schema_from_parameters",
        lambda *_args, **_kwargs: (tmp_path / "model_parameter", tmp_path / "encoded-schema-description"),
    )

    def _capture_run(cmd, *, host, port, health_timeout_seconds, health_request_timeout_seconds, env=None):
        captured["cmd"] = cmd
        captured["host"] = host
        captured["port"] = port
        captured["health_timeout_seconds"] = health_timeout_seconds
        captured["env"] = env

    monkeypatch.setattr(hv, "_run_http_server_process", _capture_run)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "hv",
            "worker",
            "serve",
            "--algorithm-jar",
            "algo.jar",
            "--algorithm-name",
            "zmclip-text-encoder",
            "--parameter-path",
            "params.zip",
            "--port",
            "18080",
        ],
    )

    hv.main()

    cmd = captured["cmd"]
    assert cmd[0] == "/usr/bin/python3"
    assert cmd[1:3] == ["-m", "hotvect.worker.litserve_server"]
    assert cmd[cmd.index("--backend") + 1] == "torch"
    assert cmd[cmd.index("--model-name") + 1] == "zmclip-text-encoder"
    assert cmd[cmd.index("--torch-model-name") + 1] == "xlm-roberta-base-ViT-B-32"
    assert cmd[cmd.index("--torch-num-threads") + 1] == "8"
    assert cmd[cmd.index("--torch-num-interop-threads") + 1] == "1"
    assert cmd[cmd.index("--warmup-iterations") + 1] == "2"
    assert "--use-torch-compile" in cmd
    assert captured["health_timeout_seconds"] == 25


def test_hv_worker_serve_rejects_tf_debug_flag_for_torch(monkeypatch, tmp_path: Path):
    hv = _load_hv_module()

    monkeypatch.setattr(
        hv,
        "_load_effective_algorithm_definition",
        lambda *_args, **_kwargs: {
            "algorithm_name": "zmclip-text-encoder",
            "algorithm_parameters": {
                "backend": "torch",
                "realtime": {
                    "litserve": {
                        "python_executable": "/usr/bin/python3",
                        "accelerator": "cpu",
                        "startup_timeout_ms": 25000,
                    }
                },
                "zmclip": {"model_name": "xlm-roberta-base-ViT-B-32"},
            },
        },
    )
    monkeypatch.setattr(
        hv,
        "_extract_model_and_schema_from_parameters",
        lambda *_args, **_kwargs: (tmp_path / "model_parameter", tmp_path / "encoded-schema-description"),
    )
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "hv",
            "worker",
            "serve",
            "--algorithm-jar",
            "algo.jar",
            "--algorithm-name",
            "zmclip-text-encoder",
            "--parameter-path",
            "params.zip",
            "--port",
            "18080",
            "--debug-include-tf-inputs",
        ],
    )

    with pytest.raises(ValueError, match="only supported for tensorflow"):
        hv.main()
