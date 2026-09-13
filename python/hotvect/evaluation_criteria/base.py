"""Core abstractions for executable evaluation criteria.

This module is intentionally small and boring:

- a criteria package exposes a manifest plus a stage-handler registry
- `hv-qa` (or any future caller) passes normalized JSON-compatible payloads in
- the criteria package returns a normalized judgment dict out

The criteria package may internally use Python callables, but the process boundary
should stay JSON in / JSON out.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable, Mapping

StageHandler = Callable[[dict[str, Any]], dict[str, Any]]


@dataclass(frozen=True)
class CriteriaManifest:
    """Machine-readable identity for one criteria package."""

    name: str
    scenario: str
    supported_stages: tuple[str, ...]
    description: str = ""

    @property
    def identifier(self) -> str:
        return self.name

    def to_dict(self) -> dict[str, Any]:
        payload = {
            "name": self.name,
            "id": self.identifier,
            "scenario": self.scenario,
            "supported_stages": list(self.supported_stages),
            "description": self.description,
        }
        return payload


class CriteriaPolicy:
    """Executable criteria package backed by stage handlers."""

    def __init__(self, manifest: CriteriaManifest, stage_handlers: Mapping[str, StageHandler]) -> None:
        self.manifest = manifest
        self._stage_handlers = dict(stage_handlers)

    def supported_stages(self) -> tuple[str, ...]:
        return tuple(self._stage_handlers)

    def evaluate(self, payload: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(payload, dict):
            raise TypeError(f"Criteria payload must be a dict, got {type(payload)!r}.")

        payload_scenario = payload.get("scenario")
        if payload_scenario not in (None, self.manifest.scenario):
            raise ValueError(
                f"Criteria '{self.manifest.identifier}' only supports scenario '{self.manifest.scenario}', "
                f"got '{payload_scenario}'."
            )

        stage = payload.get("stage")
        if not isinstance(stage, str) or not stage:
            raise ValueError("Criteria payload must include a non-empty string 'stage'.")

        try:
            handler = self._stage_handlers[stage]
        except KeyError as exc:
            raise ValueError(
                f"Criteria '{self.manifest.identifier}' does not support stage '{stage}'. "
                f"Supported stages: {sorted(self._stage_handlers)}"
            ) from exc

        result = handler(payload)
        if not isinstance(result, dict):
            raise TypeError(f"Criteria handler for stage '{stage}' returned {type(result)!r}, expected dict.")

        if "stage_judgment" not in result:
            raise ValueError(f"Criteria handler for stage '{stage}' must return 'stage_judgment'.")
        if "rollout_recommendation" not in result:
            raise ValueError(f"Criteria handler for stage '{stage}' must return 'rollout_recommendation'.")

        merged = {
            "criteria": self.manifest.to_dict(),
            "scenario": self.manifest.scenario,
            "stage": stage,
        }
        merged.update(result)
        return merged
