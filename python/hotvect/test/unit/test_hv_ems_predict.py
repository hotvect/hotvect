import importlib.util
import sys
from importlib.machinery import SourceFileLoader
from pathlib import Path
from types import ModuleType

import pytest


def _load_hv_module(monkeypatch):
    fake_hotvectjar = ModuleType("hotvect.hotvectjar")
    fake_hotvectjar.HOTVECT_JAR_PATH = Path("/tmp/offline.jar")
    fake_hotvectjar.HOTVECT_ALGORITHM_DEMO_JAR_PATH = Path("/tmp/demo.jar")
    monkeypatch.setitem(sys.modules, "hotvect.hotvectjar", fake_hotvectjar)
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "bin" / "hv"
        if candidate.exists():
            loader = SourceFileLoader("hv_cli_ems_predict", str(candidate))
            spec = importlib.util.spec_from_loader(loader.name, loader)
            assert spec is not None
            module = importlib.util.module_from_spec(spec)
            loader.exec_module(module)
            return module
    raise FileNotFoundError("Could not locate bin/hv relative to test file")


def _base_args(tmp_path: Path) -> list[str]:
    return [
        "hv",
        "predict",
        "--ems-slot",
        "product-ranking",
        "--assignment-key-json-pointer",
        "/shared/user_id",
        "--source-path",
        "requests.jsonl",
        "--dest-path",
        "predictions",
        "--metadata-path",
        str(tmp_path / "metadata"),
    ]


@pytest.mark.parametrize("task", ["predict", "performance-test"])
def test_direct_source_passes_domain_model_jars(monkeypatch, tmp_path: Path, task: str) -> None:
    hv = _load_hv_module(monkeypatch)
    captured = {}

    def capture(cmd, metadata_dir, env=None):
        captured.update(cmd=cmd, metadata_dir=metadata_dir, env=env)

    monkeypatch.setattr(hv, "_tee_subprocess_output_to_metadata_dir", capture)
    monkeypatch.setattr(hv, "_enable_file_logging", lambda _path: None)
    args = [
        "hv",
        task,
        "--algorithm-jar",
        "algorithm.jar",
        "--algorithm-name",
        "ranker",
        "--domain-model-jar",
        "domain-a.jar",
        "--domain-model-jar",
        "domain-b.jar",
        "--source-path",
        "requests.jsonl",
        "--metadata-path",
        str(tmp_path / "metadata"),
    ]
    if task == "predict":
        args.extend(["--dest-path", "predictions"])
    monkeypatch.setattr(sys, "argv", args)

    hv.main()

    cmd = captured["cmd"]
    assert cmd[cmd.index("--algorithm-jar") : cmd.index("--metadata-path")] == [
        "--algorithm-jar",
        "algorithm.jar",
        "--algorithm-definition",
        "ranker",
        "--domain-model-jar",
        "domain-a.jar",
        "--domain-model-jar",
        "domain-b.jar",
    ]


def test_synthetic_ems_predict_passes_only_snapshot_arguments(monkeypatch, tmp_path: Path) -> None:
    hv = _load_hv_module(monkeypatch)
    captured = {}

    def capture(cmd, metadata_dir, env=None):
        captured.update(cmd=cmd, metadata_dir=metadata_dir, env=env)

    monkeypatch.setattr(hv, "_tee_subprocess_output_to_metadata_dir", capture)
    monkeypatch.setattr(hv, "_enable_file_logging", lambda _path: None)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            *_base_args(tmp_path),
            "--ems-state",
            "synthetic-ems.json",
            "--domain-model-jar",
            "product-domain.jar",
            "--domain-model-jar",
            "shared-types.jar",
            "--ordered",
        ],
    )

    hv.main()

    cmd = captured["cmd"]
    assert cmd[cmd.index("--ems-slot") : cmd.index("--ems-slot") + 8] == [
        "--ems-slot",
        "product-ranking",
        "--ems-state",
        "synthetic-ems.json",
        "--assignment-key-json-pointer",
        "/shared/user_id",
        "--domain-model-jar",
        "product-domain.jar",
    ]
    assert cmd.count("--domain-model-jar") == 2
    assert "--algorithm-jar" not in cmd
    assert "--algorithm-definition" not in cmd
    assert "--parameters" not in cmd
    assert captured["env"] is None


def test_pinned_ems_predict_does_not_load_ems_config_or_token(monkeypatch, tmp_path: Path) -> None:
    hv = _load_hv_module(monkeypatch)
    captured = {}
    from hotvect.experiment_management import auth, hotvect_config

    monkeypatch.setattr(
        hotvect_config,
        "load_experiment_management_hotvect_config",
        lambda: (_ for _ in ()).throw(AssertionError("prediction must not load EMS config")),
    )
    monkeypatch.setattr(
        auth,
        "CommandTokenProvider",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(AssertionError("prediction must not obtain an EMS token")),
    )
    monkeypatch.setattr(hv, "_enable_file_logging", lambda _path: None)

    def capture(cmd, metadata_dir, env=None):
        captured.update(cmd=cmd, metadata_dir=metadata_dir, env=env)

    monkeypatch.setattr(hv, "_tee_subprocess_output_to_metadata_dir", capture)
    monkeypatch.setattr(sys, "argv", [*_base_args(tmp_path), "--ems-state", "pinned-ems.json"])

    hv.main()

    cmd = captured["cmd"]
    assert "--ems-uri" not in cmd
    assert "--ems-connect-timeout-seconds" not in cmd
    assert "--ems-read-timeout-seconds" not in cmd
    assert captured["env"] is None


def test_ems_predict_requires_pinned_state(monkeypatch, tmp_path: Path) -> None:
    hv = _load_hv_module(monkeypatch)
    monkeypatch.setattr(sys, "argv", _base_args(tmp_path))

    with pytest.raises(ValueError, match="--ems-state is required"):
        hv.main()


def test_fixed_composition_predict_passes_no_ems_or_local_root_arguments(monkeypatch, tmp_path: Path) -> None:
    hv = _load_hv_module(monkeypatch)
    captured = {}

    def capture(cmd, metadata_dir, env=None):
        captured.update(cmd=cmd, metadata_dir=metadata_dir, env=env)

    monkeypatch.setattr(hv, "_tee_subprocess_output_to_metadata_dir", capture)
    monkeypatch.setattr(hv, "_enable_file_logging", lambda _path: None)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "hv",
            "predict",
            "--composition",
            "fixed-product-ranking.json",
            "--source-path",
            "requests.jsonl",
            "--dest-path",
            "predictions",
            "--metadata-path",
            str(tmp_path / "metadata"),
            "--domain-model-jar",
            "product-domain.jar",
        ],
    )

    hv.main()

    cmd = captured["cmd"]
    assert cmd[cmd.index("--composition") : cmd.index("--composition") + 4] == [
        "--composition",
        "fixed-product-ranking.json",
        "--domain-model-jar",
        "product-domain.jar",
    ]
    assert "--ems-slot" not in cmd
    assert "--assignment-key-json-pointer" not in cmd
    assert "--algorithm-jar" not in cmd
    assert "--algorithm-definition" not in cmd
    assert captured["env"] is None


def test_ems_performance_test_passes_only_snapshot_arguments(monkeypatch, tmp_path: Path) -> None:
    hv = _load_hv_module(monkeypatch)
    captured = {}

    def capture(cmd, metadata_dir, env=None):
        captured.update(cmd=cmd, metadata_dir=metadata_dir, env=env)

    monkeypatch.setattr(hv, "_tee_subprocess_output_to_metadata_dir", capture)
    monkeypatch.setattr(hv, "_enable_file_logging", lambda _path: None)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "hv",
            "performance-test",
            "--ems-slot",
            "product-ranking",
            "--ems-state",
            "synthetic-ems.json",
            "--assignment-key-json-pointer",
            "/shared/user_id",
            "--source-path",
            "requests.jsonl",
            "--metadata-path",
            str(tmp_path / "metadata"),
        ],
    )

    hv.main()

    cmd = captured["cmd"]
    assert "performance-test" in cmd
    assert cmd[cmd.index("--ems-slot") : cmd.index("--ems-slot") + 6] == [
        "--ems-slot",
        "product-ranking",
        "--ems-state",
        "synthetic-ems.json",
        "--assignment-key-json-pointer",
        "/shared/user_id",
    ]
    assert "--algorithm-jar" not in cmd
    assert "--algorithm-definition" not in cmd
    assert "--parameters" not in cmd
    assert captured["env"] is None


def test_fixed_composition_performance_test_passes_no_ems_or_local_root_arguments(monkeypatch, tmp_path: Path) -> None:
    hv = _load_hv_module(monkeypatch)
    captured = {}

    def capture(cmd, metadata_dir, env=None):
        captured.update(cmd=cmd, metadata_dir=metadata_dir, env=env)

    monkeypatch.setattr(hv, "_tee_subprocess_output_to_metadata_dir", capture)
    monkeypatch.setattr(hv, "_enable_file_logging", lambda _path: None)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "hv",
            "performance-test",
            "--composition",
            "fixed-product-ranking.json",
            "--source-path",
            "requests.jsonl",
            "--metadata-path",
            str(tmp_path / "metadata"),
        ],
    )

    hv.main()

    cmd = captured["cmd"]
    assert "performance-test" in cmd
    assert cmd[cmd.index("--composition") : cmd.index("--composition") + 2] == [
        "--composition",
        "fixed-product-ranking.json",
    ]
    assert "--ems-slot" not in cmd
    assert "--algorithm-jar" not in cmd
    assert "--algorithm-definition" not in cmd
    assert "--parameters" not in cmd
    assert captured["env"] is None


@pytest.mark.parametrize(
    "extra_args, message",
    [
        (["--ems-state", "state.json", "--algorithm-jar", "algo.jar"], "cannot be combined"),
        (["--ems-state", "state.json", "--parameter-path", "params.zip"], "cannot be combined"),
    ],
)
def test_ems_predict_rejects_direct_algorithm_options(
    monkeypatch, tmp_path: Path, extra_args: list[str], message: str
) -> None:
    hv = _load_hv_module(monkeypatch)
    monkeypatch.setattr(sys, "argv", [*_base_args(tmp_path), *extra_args])

    with pytest.raises(ValueError, match=message):
        hv.main()


@pytest.mark.parametrize(
    "extra_args, message",
    [
        (["--ems-slot", "product-ranking"], "mutually exclusive"),
        (["--algorithm-jar", "algo.jar"], "cannot be combined"),
        (["--parameter-path", "params.zip"], "cannot be combined"),
    ],
)
def test_fixed_composition_predict_rejects_other_source_modes(
    monkeypatch, tmp_path: Path, extra_args: list[str], message: str
) -> None:
    hv = _load_hv_module(monkeypatch)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "hv",
            "predict",
            "--composition",
            "composition.json",
            "--source-path",
            "requests.jsonl",
            "--dest-path",
            "predictions",
            "--metadata-path",
            str(tmp_path / "metadata"),
            *extra_args,
        ],
    )

    with pytest.raises(ValueError, match=message):
        hv.main()
