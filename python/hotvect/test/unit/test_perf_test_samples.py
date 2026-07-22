import json
from datetime import date
from pathlib import Path

import psutil

from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext


def test_pipeline_perf_test_passes_samples(tmp_path, monkeypatch):
    # Arrange minimal filesystem layout so test_data_paths() works.
    data_base = tmp_path / "data"
    test_prefix = "test_data"
    (data_base / test_prefix / f"dt={date(2026, 2, 21).isoformat()}").mkdir(parents=True)

    algo_jar = tmp_path / "algo.jar"
    algo_jar.write_bytes(b"dummy")

    algo_def = {
        "algorithm_name": "a",
        "algorithm_version": "1.0.0",
        "algorithm_factory_classname": "x.y.Z",
        "test_data_prefix": test_prefix,
        "training_command": "echo no",  # required so audit path isn't blocked in other code paths
        "hotvect_execution_parameters": {"performance-test": {"enabled": True, "samples": 123}},
    }

    ctx = AlgorithmPipelineContext(
        algorithm_jar_path=algo_jar,
        data_base_path=data_base,
        metadata_base_path=tmp_path / "meta",
        output_base_path=tmp_path / "out",
        state_source_base_path=tmp_path / "state",
        jvm_options=[],
    )

    monkeypatch.setattr("hotvect.pyhotvect.read_algorithm_definition_from_jar", lambda **kwargs: algo_def)

    pipeline = AlgorithmPipeline(
        algorithm_pipeline_context=ctx,
        algorithm_definition="a",
        last_test_time=date(2026, 2, 21),
        evaluation_func=None,
        parameter_version="p",
    )

    captured = {}

    def fake_stream_output(cmd, display, env=None):
        captured["cmd"] = cmd
        idx = cmd.index("--metadata-path") + 1
        meta_dir = Path(cmd[idx])
        meta_dir.mkdir(parents=True, exist_ok=True)
        (meta_dir / "metadata.json").write_text(json.dumps({"ok": True}))
        display("")

    monkeypatch.setattr("hotvect.pyhotvect.stream_output", fake_stream_output)

    # Act
    Path(pipeline.metadata_path()).mkdir(parents=True, exist_ok=True)
    pipeline.performance_test(evaluate=True)

    # Assert
    cmd = captured["cmd"]
    assert "--samples" in cmd
    assert cmd[cmd.index("--samples") + 1] == "123"


def test_pipeline_perf_test_passes_sample_pool_size_separately_from_samples(tmp_path, monkeypatch):
    data_base = tmp_path / "data"
    test_prefix = "test_data"
    (data_base / test_prefix / f"dt={date(2026, 2, 21).isoformat()}").mkdir(parents=True)

    algo_jar = tmp_path / "algo.jar"
    algo_jar.write_bytes(b"dummy")

    algo_def = {
        "algorithm_name": "a",
        "algorithm_version": "1.0.0",
        "algorithm_factory_classname": "x.y.Z",
        "test_data_prefix": test_prefix,
        "training_command": "echo no",
        "hotvect_execution_parameters": {
            "performance-test": {
                "enabled": True,
                "samples": 1000000,
                "sample_pool_size": 25000,
            }
        },
    }

    ctx = AlgorithmPipelineContext(
        algorithm_jar_path=algo_jar,
        data_base_path=data_base,
        metadata_base_path=tmp_path / "meta",
        output_base_path=tmp_path / "out",
        state_source_base_path=tmp_path / "state",
        jvm_options=[],
    )

    monkeypatch.setattr("hotvect.pyhotvect.read_algorithm_definition_from_jar", lambda **kwargs: algo_def)

    pipeline = AlgorithmPipeline(
        algorithm_pipeline_context=ctx,
        algorithm_definition="a",
        last_test_time=date(2026, 2, 21),
        evaluation_func=None,
        parameter_version="p",
    )

    captured = {}

    def fake_stream_output(cmd, display, env=None):
        captured["cmd"] = cmd
        idx = cmd.index("--metadata-path") + 1
        meta_dir = Path(cmd[idx])
        meta_dir.mkdir(parents=True, exist_ok=True)
        (meta_dir / "metadata.json").write_text(json.dumps({"ok": True}))
        display("")

    monkeypatch.setattr("hotvect.pyhotvect.stream_output", fake_stream_output)

    Path(pipeline.metadata_path()).mkdir(parents=True, exist_ok=True)
    result = pipeline.performance_test(evaluate=True)

    cmd = captured["cmd"]
    assert cmd[cmd.index("--samples") + 1] == "1000000"
    assert cmd[cmd.index("--sample-pool-size") + 1] == "25000"
    assert result["requested_samples"] == 1000000
    assert result["requested_sample_pool_size"] == 25000


def test_pipeline_perf_test_passes_target_rps_and_fraction(tmp_path, monkeypatch):
    data_base = tmp_path / "data"
    test_prefix = "test_data"
    (data_base / test_prefix / f"dt={date(2026, 2, 21).isoformat()}").mkdir(parents=True)

    algo_jar = tmp_path / "algo.jar"
    algo_jar.write_bytes(b"dummy")

    algo_def = {
        "algorithm_name": "a",
        "algorithm_version": "1.0.0",
        "algorithm_factory_classname": "x.y.Z",
        "test_data_prefix": test_prefix,
        "training_command": "echo no",
        "hotvect_execution_parameters": {
            "performance-test": {
                "enabled": True,
                "samples": 123,
                "target_rps": 120,
                "target_throughput_fraction": 0.5,
            }
        },
    }

    ctx = AlgorithmPipelineContext(
        algorithm_jar_path=algo_jar,
        data_base_path=data_base,
        metadata_base_path=tmp_path / "meta",
        output_base_path=tmp_path / "out",
        state_source_base_path=tmp_path / "state",
        jvm_options=[],
    )

    monkeypatch.setattr("hotvect.pyhotvect.read_algorithm_definition_from_jar", lambda **kwargs: algo_def)
    monkeypatch.setattr(psutil, "cpu_count", lambda logical=False: 8 if logical is False else 16)

    pipeline = AlgorithmPipeline(
        algorithm_pipeline_context=ctx,
        algorithm_definition="a",
        last_test_time=date(2026, 2, 21),
        evaluation_func=None,
        parameter_version="p",
    )

    captured = {}

    def fake_stream_output(cmd, display, env=None):
        captured["cmd"] = cmd
        idx = cmd.index("--metadata-path") + 1
        meta_dir = Path(cmd[idx])
        meta_dir.mkdir(parents=True, exist_ok=True)
        (meta_dir / "metadata.json").write_text(json.dumps({"ok": True}))
        display("")

    monkeypatch.setattr("hotvect.pyhotvect.stream_output", fake_stream_output)

    Path(pipeline.metadata_path()).mkdir(parents=True, exist_ok=True)
    result = pipeline.performance_test(evaluate=True)

    cmd = captured["cmd"]
    assert "--target-rps" in cmd
    assert cmd[cmd.index("--target-rps") + 1] == "120.0"
    assert "--target-throughput-fraction" in cmd
    assert cmd[cmd.index("--target-throughput-fraction") + 1] == "0.5"
    assert result["requested_target_rps"] == 120.0
    assert result["requested_target_throughput_fraction"] == 0.5


def test_pipeline_perf_test_passes_workload_mode(tmp_path, monkeypatch):
    data_base = tmp_path / "data"
    test_prefix = "test_data"
    (data_base / test_prefix / f"dt={date(2026, 2, 21).isoformat()}").mkdir(parents=True)

    algo_jar = tmp_path / "algo.jar"
    algo_jar.write_bytes(b"dummy")

    algo_def = {
        "algorithm_name": "a",
        "algorithm_version": "1.0.0",
        "algorithm_factory_classname": "x.y.Z",
        "test_data_prefix": test_prefix,
        "training_command": "echo no",
        "hotvect_execution_parameters": {"performance-test": {"enabled": True, "workload_mode": "batch"}},
    }

    ctx = AlgorithmPipelineContext(
        algorithm_jar_path=algo_jar,
        data_base_path=data_base,
        metadata_base_path=tmp_path / "meta",
        output_base_path=tmp_path / "out",
        state_source_base_path=tmp_path / "state",
        jvm_options=[],
    )

    monkeypatch.setattr("hotvect.pyhotvect.read_algorithm_definition_from_jar", lambda **kwargs: algo_def)

    pipeline = AlgorithmPipeline(
        algorithm_pipeline_context=ctx,
        algorithm_definition="a",
        last_test_time=date(2026, 2, 21),
        evaluation_func=None,
        parameter_version="p",
    )

    captured = {}

    def fake_stream_output(cmd, display, env=None):
        captured["cmd"] = cmd
        idx = cmd.index("--metadata-path") + 1
        meta_dir = Path(cmd[idx])
        meta_dir.mkdir(parents=True, exist_ok=True)
        (meta_dir / "metadata.json").write_text(json.dumps({"ok": True, "workload_mode": "batch"}))
        display("")

    monkeypatch.setattr("hotvect.pyhotvect.stream_output", fake_stream_output)

    Path(pipeline.metadata_path()).mkdir(parents=True, exist_ok=True)
    result = pipeline.performance_test(evaluate=True)

    cmd = captured["cmd"]
    assert "--workload-mode" in cmd
    assert cmd[cmd.index("--workload-mode") + 1] == "batch"
    assert result["requested_workload_mode"] == "batch"


def test_pipeline_perf_test_handles_missing_physical_core_count(tmp_path, monkeypatch):
    data_base = tmp_path / "data"
    test_prefix = "test_data"
    (data_base / test_prefix / f"dt={date(2026, 2, 21).isoformat()}").mkdir(parents=True)

    algo_jar = tmp_path / "algo.jar"
    algo_jar.write_bytes(b"dummy")

    algo_def = {
        "algorithm_name": "a",
        "algorithm_version": "1.0.0",
        "algorithm_factory_classname": "x.y.Z",
        "test_data_prefix": test_prefix,
        "training_command": "echo no",
        "hotvect_execution_parameters": {"performance-test": {"enabled": True}},
    }

    ctx = AlgorithmPipelineContext(
        algorithm_jar_path=algo_jar,
        data_base_path=data_base,
        metadata_base_path=tmp_path / "meta",
        output_base_path=tmp_path / "out",
        state_source_base_path=tmp_path / "state",
        jvm_options=[],
    )

    monkeypatch.setattr("hotvect.pyhotvect.read_algorithm_definition_from_jar", lambda **kwargs: algo_def)

    def fake_cpu_count(*, logical=False):
        if logical is False:
            return None
        return 16

    monkeypatch.setattr(psutil, "cpu_count", fake_cpu_count)

    pipeline = AlgorithmPipeline(
        algorithm_pipeline_context=ctx,
        algorithm_definition="a",
        last_test_time=date(2026, 2, 21),
        evaluation_func=None,
        parameter_version="p",
    )

    captured = {}

    def fake_stream_output(cmd, display, env=None):
        captured["cmd"] = cmd
        idx = cmd.index("--metadata-path") + 1
        meta_dir = Path(cmd[idx])
        meta_dir.mkdir(parents=True, exist_ok=True)
        (meta_dir / "metadata.json").write_text(json.dumps({"ok": True}))
        display("")

    monkeypatch.setattr("hotvect.pyhotvect.stream_output", fake_stream_output)

    Path(pipeline.metadata_path()).mkdir(parents=True, exist_ok=True)
    pipeline.performance_test(evaluate=True)

    cmd = captured["cmd"]
    idx = cmd.index("--max-threads")
    assert cmd[idx + 1] == "2"
