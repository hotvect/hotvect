"""Shared config loading for hv/hv-ext.

This is intentionally small and convention-driven:
- Default path: ~/.hotvect/config.json
- The config file is expected to contain either:
    - the config object directly, or
    - a wrapper with top-level key "config" (as some tools output).
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

DEFAULT_CONFIG_PATH = Path("~/.hotvect/config.json").expanduser()


def get_config_path() -> Path:
    return DEFAULT_CONFIG_PATH


def load_config() -> dict[str, Any]:
    path = get_config_path()
    if not path.exists():
        raise FileNotFoundError(f"Config not found: {path}")
    data = json.loads(path.read_text())
    if isinstance(data, dict) and "config" in data and isinstance(data["config"], dict):
        return data["config"]
    if isinstance(data, dict):
        return data
    raise ValueError(f"Invalid config JSON (expected object): {path}")


def try_load_config() -> dict[str, Any] | None:
    try:
        return load_config()
    except FileNotFoundError:
        return None


def _load_qa_run_section() -> dict[str, Any]:
    cfg = try_load_config()
    if cfg is None:
        return {}

    qa = cfg.get("qa")
    if qa is None:
        return {}
    if not isinstance(qa, dict):
        raise ValueError("Config field 'qa' must be an object")

    run = qa.get("run")
    if run is None:
        return {}
    if not isinstance(run, dict):
        raise ValueError("Config field 'qa.run' must be an object")

    return dict(run)


def load_qa_run_defaults() -> dict[str, Any]:
    """Return optional hv-qa run defaults from ~/.hotvect/config.json.

    Expected shape:
      {
        "qa": {
          "run": {
            "defaults": { ... }
          }
        }
      }
    """

    run = _load_qa_run_section()
    defaults = run.get("defaults")
    if defaults is None:
        return {}
    if not isinstance(defaults, dict):
        raise ValueError("Config field 'qa.run.defaults' must be an object")

    return dict(defaults)


def load_qa_run_execution_context() -> dict[str, Any]:
    """Return optional hv-qa run execution defaults from ~/.hotvect/config.json.

    Expected shape:
      {
        "qa": {
          "run": {
            "execution": { ... }
          }
        }
      }
    """

    run = _load_qa_run_section()
    execution = run.get("execution")
    if execution is None:
        return {}
    if not isinstance(execution, dict):
        raise ValueError("Config field 'qa.run.execution' must be an object")
    return dict(execution)


def load_qa_run_system_performance() -> dict[str, Any]:
    """Return optional hv-qa run system-performance defaults.

    Expected shape:
      {
        "qa": {
          "run": {
            "system_performance": { ... }
          }
        }
      }
    """

    run = _load_qa_run_section()
    system_performance = run.get("system_performance")
    if system_performance is None:
        return {}
    if not isinstance(system_performance, dict):
        raise ValueError("Config field 'qa.run.system_performance' must be an object")
    return dict(system_performance)


def load_qa_run_backtest() -> dict[str, Any]:
    """Return optional hv-qa run backtest defaults.

    Expected shape:
      {
        "qa": {
          "run": {
            "backtest": { ... }
          }
        }
      }
    """

    run = _load_qa_run_section()
    backtest = run.get("backtest")
    if backtest is None:
        return {}
    if not isinstance(backtest, dict):
        raise ValueError("Config field 'qa.run.backtest' must be an object")
    return dict(backtest)


def load_sagemaker_defaults() -> dict[str, Any]:
    """Return the top-level sagemaker config object if present."""

    cfg = try_load_config()
    if cfg is None:
        return {}
    sagemaker = cfg.get("sagemaker")
    if sagemaker is None:
        return {}
    if not isinstance(sagemaker, dict):
        raise ValueError("Config field 'sagemaker' must be an object")
    return dict(sagemaker)


def load_directory_defaults() -> dict[str, Any]:
    """Return the top-level directories config object if present."""

    cfg = try_load_config()
    if cfg is None:
        return {}
    directories = cfg.get("directories")
    if directories is None:
        return {}
    if not isinstance(directories, dict):
        raise ValueError("Config field 'directories' must be an object")
    return dict(directories)


def resolve_meta_dir(*, meta_dir: str | None = None) -> Path:
    """Resolve a meta directory from CLI arg or ~/.hotvect/config.json directories.output_base_dir/meta."""
    if meta_dir:
        return Path(meta_dir).expanduser()
    cfg = load_config()
    directories = cfg.get("directories") if isinstance(cfg, dict) else None
    if not isinstance(directories, dict):
        raise ValueError("Config missing 'directories' object")
    output_base_dir = directories.get("output_base_dir")
    if not isinstance(output_base_dir, str) or not output_base_dir:
        raise ValueError("Config missing 'directories.output_base_dir'")
    return Path(output_base_dir).expanduser() / "meta"


def write_config(*, config: dict[str, Any]) -> Path:
    path = get_config_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(config, indent=2) + "\n")
    return path
