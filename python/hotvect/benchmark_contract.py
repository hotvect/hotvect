from __future__ import annotations

from typing import Any

BENCHMARK_CONTRACT_KEY = "benchmark_contract"


def _put_if_present(target: dict[str, Any], key: str, value: Any) -> None:
    if value is not None:
        target[key] = value


def _clean_dict(value: dict[str, Any]) -> dict[str, Any]:
    cleaned: dict[str, Any] = {}
    for key, item in value.items():
        if item is None:
            continue
        if isinstance(item, dict):
            nested = _clean_dict(item)
            if nested:
                cleaned[key] = nested
            continue
        if isinstance(item, list) and not item:
            continue
        cleaned[key] = item
    return cleaned


def build_benchmark_contract(
    *,
    base_contract: dict[str, Any] | None = None,
    parameter_s3_uri: str | None = None,
    parameter_path: str | None = None,
    source_s3_uri: str | None = None,
    source_paths: list[str] | None = None,
    instance_type: str | None = None,
    training_image: str | None = None,
    samples: int | None = None,
    sample_pool_size: int | None = None,
    target_rps: float | None = None,
    target_throughput_fraction: float | None = None,
    max_threads: int | None = None,
    workload_mode: str | None = None,
    execution_command: list[str] | None = None,
    output_prefixes: dict[str, Any] | None = None,
) -> dict[str, Any]:
    contract: dict[str, Any] = dict(base_contract or {})

    merged_output_prefixes: dict[str, Any] = {}
    existing_output_prefixes = contract.get("output_prefixes")
    if isinstance(existing_output_prefixes, dict):
        merged_output_prefixes.update(existing_output_prefixes)
    if output_prefixes:
        merged_output_prefixes.update(output_prefixes)

    _put_if_present(contract, "parameter_s3_uri", parameter_s3_uri)
    _put_if_present(contract, "parameter_path", parameter_path)
    _put_if_present(contract, "source_s3_uri", source_s3_uri)
    if source_paths:
        contract["source_paths"] = list(source_paths)
    _put_if_present(contract, "instance_type", instance_type)
    _put_if_present(contract, "training_image", training_image)
    _put_if_present(contract, "samples", samples)
    _put_if_present(contract, "sample_pool_size", sample_pool_size)
    _put_if_present(contract, "target_rps", target_rps)
    _put_if_present(contract, "target_throughput_fraction", target_throughput_fraction)
    _put_if_present(contract, "max_threads", max_threads)
    _put_if_present(contract, "workload_mode", workload_mode)
    if execution_command:
        contract["execution_command"] = list(execution_command)
    if merged_output_prefixes:
        contract["output_prefixes"] = merged_output_prefixes

    return _clean_dict(contract)
