from __future__ import annotations

from collections.abc import Sequence

DEFAULT_MAX_RAM_PERCENTAGE_ARG = "-XX:MaxRAMPercentage=80"
EXIT_ON_OUT_OF_MEMORY_ARG = "-XX:+ExitOnOutOfMemoryError"


def _as_list(java_args: Sequence[str] | None) -> list[str]:
    return list(java_args) if java_args else []


def _has_explicit_heap_cap(java_args: Sequence[str]) -> bool:
    return any(arg.startswith("-Xmx") or arg.startswith("-XX:MaxRAMPercentage") for arg in java_args)


def _validate_heap_cap_options(java_args: Sequence[str]) -> None:
    xmx_options = [arg for arg in java_args if arg.startswith("-Xmx")]
    max_ram_percentage_options = [arg for arg in java_args if arg.startswith("-XX:MaxRAMPercentage")]
    if len(xmx_options) > 1:
        raise ValueError(f"Specify only one -Xmx JVM option, got: {xmx_options}")
    if len(max_ram_percentage_options) > 1:
        raise ValueError(f"Specify only one -XX:MaxRAMPercentage JVM option, got: {max_ram_percentage_options}")
    if xmx_options and max_ram_percentage_options:
        raise ValueError(
            "Specify either -Xmx... or -XX:MaxRAMPercentage=..., not both. "
            f"Got: {xmx_options + max_ram_percentage_options}"
        )


def normalize_pipeline_jvm_options(java_args: Sequence[str] | None) -> list[str]:
    normalized = _as_list(java_args)
    _validate_heap_cap_options(normalized)
    if not _has_explicit_heap_cap(normalized):
        normalized.append(DEFAULT_MAX_RAM_PERCENTAGE_ARG)
    return normalized


def normalize_runtime_jvm_args(java_args: Sequence[str] | None) -> list[str]:
    normalized = normalize_pipeline_jvm_options(java_args)
    if EXIT_ON_OUT_OF_MEMORY_ARG not in normalized:
        normalized.append(EXIT_ON_OUT_OF_MEMORY_ARG)
    return normalized
