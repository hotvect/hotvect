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
