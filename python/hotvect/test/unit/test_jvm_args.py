import pytest

from hotvect.jvm_args import (
    DEFAULT_MAX_RAM_PERCENTAGE_ARG,
    EXIT_ON_OUT_OF_MEMORY_ARG,
    normalize_pipeline_jvm_options,
    normalize_runtime_jvm_args,
)


def test_normalize_pipeline_jvm_options_defaults_empty_values() -> None:
    assert normalize_pipeline_jvm_options([]) == [DEFAULT_MAX_RAM_PERCENTAGE_ARG]
    assert normalize_pipeline_jvm_options(None) == [DEFAULT_MAX_RAM_PERCENTAGE_ARG]


def test_normalize_pipeline_jvm_options_adds_default_heap_cap_to_non_heap_args() -> None:
    assert normalize_pipeline_jvm_options(["-Dfoo=bar"]) == ["-Dfoo=bar", DEFAULT_MAX_RAM_PERCENTAGE_ARG]


def test_normalize_pipeline_jvm_options_preserves_explicit_heap_cap() -> None:
    assert normalize_pipeline_jvm_options(["-Xmx2g"]) == ["-Xmx2g"]
    assert normalize_pipeline_jvm_options(["-XX:MaxRAMPercentage=90"]) == ["-XX:MaxRAMPercentage=90"]


def test_normalize_pipeline_jvm_options_rejects_conflicting_heap_caps() -> None:
    with pytest.raises(ValueError, match="Specify either -Xmx"):
        normalize_pipeline_jvm_options(["-Xmx2g", "-XX:MaxRAMPercentage=80"])


def test_normalize_runtime_jvm_args_appends_exit_on_oom_once() -> None:
    assert normalize_runtime_jvm_args(["-Dfoo=bar"]) == [
        "-Dfoo=bar",
        DEFAULT_MAX_RAM_PERCENTAGE_ARG,
        EXIT_ON_OUT_OF_MEMORY_ARG,
    ]
    assert normalize_runtime_jvm_args(["-Xmx2g", EXIT_ON_OUT_OF_MEMORY_ARG]) == [
        "-Xmx2g",
        EXIT_ON_OUT_OF_MEMORY_ARG,
    ]
