from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from hotvect.experiment_management.auth import CommandTokenProvider, TokenProviderAuth
from hotvect.experiment_management.client import (
    DEFAULT_CONNECT_TIMEOUT_SECONDS,
    DEFAULT_READ_TIMEOUT_SECONDS,
    ExperimentManagementClient,
    ExperimentManagementConnection,
)
from hotvect.extra import config as hv_config


@dataclass(frozen=True)
class ExperimentManagementHotvectConfig:
    url: str
    token_provider_command: str
    token_provider_ttl_ms: int = 3600_000
    connect_timeout_seconds: float = DEFAULT_CONNECT_TIMEOUT_SECONDS
    read_timeout_seconds: float = DEFAULT_READ_TIMEOUT_SECONDS
    online_results_s3_base_prefixes_by_slot: dict[str, str] | None = None


def _parse_online_results_s3_base_prefixes_by_slot(raw_value: Any) -> dict[str, str]:
    if raw_value is None:
        return {}
    if not isinstance(raw_value, dict):
        raise ValueError("Config 'experiment_management.online_results' must be an object.")

    slots = raw_value.get("slots")
    if slots is None:
        return {}
    if not isinstance(slots, dict):
        raise ValueError("Config 'experiment_management.online_results.slots' must be an object.")

    resolved: dict[str, str] = {}
    for slot_name, slot_cfg in slots.items():
        if not isinstance(slot_name, str) or not slot_name.strip():
            raise ValueError("Config 'experiment_management.online_results.slots' has an invalid slot name.")
        if not isinstance(slot_cfg, dict):
            raise ValueError(f"Config 'experiment_management.online_results.slots.{slot_name}' must be an object.")
        s3_base_prefix = slot_cfg.get("s3_base_prefix")
        if not isinstance(s3_base_prefix, str) or not s3_base_prefix.strip():
            raise ValueError(
                f"Config 'experiment_management.online_results.slots.{slot_name}.s3_base_prefix' "
                "must be a non-empty string."
            )
        resolved[slot_name.strip()] = s3_base_prefix.strip()
    return resolved


def _parse_positive_timeout_seconds(raw_value: Any, *, field_name: str, default: float) -> float:
    if raw_value is None:
        return float(default)
    if isinstance(raw_value, bool):
        raise ValueError(f"Config '{field_name}' must be a positive number.")
    if not isinstance(raw_value, (int, float)):
        raise ValueError(f"Config '{field_name}' must be a positive number.")
    value = float(raw_value)
    if value <= 0:
        raise ValueError(f"Config '{field_name}' must be a positive number.")
    return value


def load_experiment_management_hotvect_config(
    *, config: dict[str, Any] | None = None
) -> ExperimentManagementHotvectConfig:
    """
    Load experiment-management settings from ~/.hotvect/config.json.

    Expected shape:
    {
      "experiment_management": {
        "url": "https://...",
        "token_provider_command": "ztoken token -n ems",
        "token_provider_ttl_ms": 3600000,
        "connect_timeout_seconds": 5.0,
        "read_timeout_seconds": 15.0,
        "online_results": {
          "slots": {
            "slot-a": {
              "s3_base_prefix": "s3://bucket/path/"
            }
          }
        }
      }
    }
    """

    cfg = config or hv_config.load_config()

    section = cfg.get("experiment_management")
    if not isinstance(section, dict):
        raise ValueError("Config missing 'experiment_management' object.")

    url = section.get("url")
    command = section.get("token_provider_command")
    ttl_ms = section.get("token_provider_ttl_ms", 3600_000)
    online_results_s3_base_prefixes_by_slot = _parse_online_results_s3_base_prefixes_by_slot(
        section.get("online_results")
    )
    connect_timeout_seconds = _parse_positive_timeout_seconds(
        section.get("connect_timeout_seconds"),
        field_name="experiment_management.connect_timeout_seconds",
        default=DEFAULT_CONNECT_TIMEOUT_SECONDS,
    )
    read_timeout_seconds = _parse_positive_timeout_seconds(
        section.get("read_timeout_seconds"),
        field_name="experiment_management.read_timeout_seconds",
        default=DEFAULT_READ_TIMEOUT_SECONDS,
    )

    if not isinstance(url, str) or not url.strip():
        raise ValueError("Config missing 'experiment_management.url'.")
    if not isinstance(command, str) or not command.strip():
        raise ValueError("Config missing 'experiment_management.token_provider_command'.")
    if not isinstance(ttl_ms, int) or ttl_ms <= 0:
        raise ValueError("Config 'experiment_management.token_provider_ttl_ms' must be a positive integer.")

    return ExperimentManagementHotvectConfig(
        url=url.strip(),
        token_provider_command=command.strip(),
        token_provider_ttl_ms=ttl_ms,
        connect_timeout_seconds=connect_timeout_seconds,
        read_timeout_seconds=read_timeout_seconds,
        online_results_s3_base_prefixes_by_slot=online_results_s3_base_prefixes_by_slot,
    )


def create_client_from_hotvect_config(
    *, config: dict[str, Any] | None = None, url_override: str | None = None
) -> ExperimentManagementClient:
    cfg = load_experiment_management_hotvect_config(config=config)
    provider = CommandTokenProvider(command=cfg.token_provider_command, ttl_seconds=cfg.token_provider_ttl_ms / 1000.0)
    auth = TokenProviderAuth(provider)
    conn = ExperimentManagementConnection(
        environment=url_override or cfg.url,
        connect_timeout=cfg.connect_timeout_seconds,
        read_timeout=cfg.read_timeout_seconds,
        bearer_auth=auth,
    )
    return ExperimentManagementClient(conn)
