"""Validation planning primitives for hv-qa."""

from __future__ import annotations

import json
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def parse_iso_date(value: str, *, field_name: str) -> date:
    try:
        return date.fromisoformat(value)
    except ValueError as exc:
        raise ValueError(f"{field_name} must be an ISO date, got {value!r}") from exc


def parse_iso_datetime(value: str, *, field_name: str) -> datetime:
    normalized = value[:-1] + "+00:00" if value.endswith("Z") else value
    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError as exc:
        raise ValueError(f"{field_name} must be an ISO datetime, got {value!r}") from exc
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed


def parse_hv_exp_default(default_json: Path) -> dict[str, str | None]:
    payload = read_json(default_json)
    algorithm = _first_present(payload, "algorithm", "algorithm_name", "name")
    version = _first_present(payload, "version", "algorithm_version")
    variant = _first_present(payload, "variant", "variant_id", "variantId")
    created_at = _first_present(payload, "created_at", "createdAt", "default_created_at", "defaultCreatedAt")
    missing = [
        name
        for name, value in {
            "algorithm": algorithm,
            "version": version,
            "variant": variant,
            "created_at": created_at,
        }.items()
        if value is None
    ]
    if missing:
        raise ValueError(f"{default_json} is missing EMS default fields: {', '.join(missing)}")
    return {
        "algorithm": str(algorithm),
        "version": str(version),
        "variant": str(variant),
        "created_at": str(created_at),
        "slot_name": str(payload["slot_name"]) if payload.get("slot_name") is not None else None,
    }


def prod_default_facts_from_slot_active_info(*, slot_name: str, active_info: Any) -> dict[str, str | None]:
    if active_info is None:
        raise ValueError(f"EMS slot {slot_name!r} has no active-info payload")
    payload = active_info.model_dump(mode="json") if hasattr(active_info, "model_dump") else active_info
    if not isinstance(payload, dict):
        raise ValueError(f"EMS slot {slot_name!r} active-info payload must be an object")

    default_variant = payload.get("default_variant")
    if not isinstance(default_variant, dict):
        raise ValueError(f"EMS slot {slot_name!r} active-info payload has no default_variant object")
    algorithm = default_variant.get("algorithm")
    if not isinstance(algorithm, dict):
        raise ValueError(f"EMS slot {slot_name!r} default_variant has no algorithm object")

    algorithm_name = algorithm.get("algorithm_name")
    algorithm_version = algorithm.get("algorithm_version")
    variant_id = default_variant.get("variant_id")
    created_at = default_variant.get("created_at")
    missing = [
        name
        for name, value in {
            "algorithm.algorithm_name": algorithm_name,
            "algorithm.algorithm_version": algorithm_version,
            "default_variant.variant_id": variant_id,
            "default_variant.created_at": created_at,
        }.items()
        if value is None
    ]
    if missing:
        raise ValueError(f"EMS slot {slot_name!r} default_variant is missing: {', '.join(missing)}")

    return {
        "algorithm": str(algorithm_name),
        "version": str(algorithm_version),
        "variant": str(variant_id),
        "created_at": str(created_at),
        "slot_name": slot_name,
    }


def resolve_treatment_source_data_by_date(
    *,
    definition_dir: Path,
    root_algorithm: str,
    included_dates: list[date],
    partitioned_sources: set[str],
) -> dict[str, dict[str, Any]]:
    definitions = _load_definition_graph(definition_dir=definition_dir, root_algorithm=root_algorithm)
    result: dict[str, dict[str, Any]] = {}
    for dt in included_dates:
        dependencies_for_date: dict[str, Any] = {}
        for algorithm_name, definition in sorted(definitions.items()):
            for source_name, source_config in sorted((definition.get("source_data") or {}).items()):
                dependencies_for_date[f"{algorithm_name}.{source_name}"] = _resolve_source_data(
                    dt=dt,
                    source_name=source_name,
                    source_config=source_config,
                    partitioned_sources=partitioned_sources,
                )
        result[dt.isoformat()] = dependencies_for_date
    return result


def _load_definition_graph(*, definition_dir: Path, root_algorithm: str) -> dict[str, dict[str, Any]]:
    definitions: dict[str, dict[str, Any]] = {}
    to_visit = [root_algorithm]
    while to_visit:
        algorithm_name = to_visit.pop()
        if algorithm_name in definitions:
            continue
        definition = _load_algorithm_definition(definition_dir, algorithm_name)
        definitions[algorithm_name] = definition
        for dependency_name in _dependency_names(definition.get("dependencies")):
            to_visit.append(dependency_name)
    return definitions


def _load_algorithm_definition(definition_dir: Path, algorithm_name: str) -> dict[str, Any]:
    path = definition_dir / f"{algorithm_name}-algorithm-definition.json"
    if not path.exists():
        raise FileNotFoundError(f"Missing algorithm definition for {algorithm_name}: {path}")
    payload = read_json(path)
    if payload.get("algorithm_name") != algorithm_name:
        raise ValueError(f"{path} algorithm_name is {payload.get('algorithm_name')!r}, expected {algorithm_name!r}")
    return payload


def _dependency_names(dependencies: Any) -> list[str]:
    if dependencies is None:
        return []
    if isinstance(dependencies, dict):
        return [str(key) for key in dependencies]
    if isinstance(dependencies, list):
        return [str(item) for item in dependencies]
    raise ValueError(f"dependencies must be an object or list, got {type(dependencies).__name__}")


def _resolve_source_data(
    *,
    dt: date,
    source_name: str,
    source_config: dict[str, Any],
    partitioned_sources: set[str],
) -> dict[str, Any]:
    data_prefix = source_config.get("data_prefix")
    number_of_days = source_config.get("number_of_days")
    lag_days = source_config.get("lag_days")
    issues: list[dict[str, str]] = []

    if (number_of_days is None) != (lag_days is None):
        issues.append(
            {
                "code": "invalid_source_date_config",
                "source": source_name,
                "message": f"{source_name} must set number_of_days and lag_days together",
            }
        )
        dates: list[str] = []
        static = False
    elif number_of_days is None:
        dates = []
        static = True
        if source_name in partitioned_sources or data_prefix in partitioned_sources:
            issues.append(
                {
                    "code": "partitioned_source_modeled_as_static",
                    "source": source_name,
                    "message": f"{source_name} is declared partitioned but has null number_of_days/lag_days",
                }
            )
    else:
        if not isinstance(number_of_days, int) or number_of_days <= 0:
            issues.append(
                {
                    "code": "invalid_number_of_days",
                    "source": source_name,
                    "message": f"{source_name}.number_of_days must be a positive integer",
                }
            )
            dates = []
        elif not isinstance(lag_days, int) or lag_days < 0:
            issues.append(
                {
                    "code": "invalid_lag_days",
                    "source": source_name,
                    "message": f"{source_name}.lag_days must be a non-negative integer",
                }
            )
            dates = []
        else:
            start_date = dt - timedelta(days=lag_days)
            dates = [(start_date - timedelta(days=offset)).isoformat() for offset in range(number_of_days)]
        static = False

    return {
        "data_prefix": data_prefix,
        "dates": dates,
        "static": static,
        "issues": issues,
    }


def _first_present(payload: dict[str, Any], *keys: str) -> Any:
    for key in keys:
        if key in payload and payload[key] is not None:
            return payload[key]
    return None
