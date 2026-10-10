"""QA run commands for hv-qa CLI."""

from __future__ import annotations

import argparse
import copy
import difflib
import gzip
import hashlib
import io
import json
import math
import os
import re
import secrets
import shlex
import shutil
import subprocess
import sys
import time
import traceback
import zipfile
from collections import Counter
from concurrent.futures import Future, ThreadPoolExecutor
from dataclasses import asdict, dataclass, field
from datetime import date, datetime, timedelta, timezone
from itertools import product, zip_longest
from pathlib import Path
from typing import Any, Callable, TypeVar
from urllib.parse import urlparse

import boto3
from botocore.exceptions import (
    ClientError,
    ConnectionClosedError,
    ConnectTimeoutError,
    EndpointConnectionError,
    ReadTimeoutError,
)

from hotvect.algorithm_definition_overrides import (
    load_algorithm_definition_override_fragment,
    merge_algorithm_definition_override_fragments,
)
from hotvect.build_utils import parse_pom_xml
from hotvect.evaluation_criteria import load_builtin_policy
from hotvect.experiment_management.hotvect_config import create_client_from_hotvect_config
from hotvect.extra import config as hv_config
from hotvect.extra.commands.metrics import (
    QUALITY_METRICS,
    SYSTEM_METRICS,
    _aggregate_by_date,
    _load_performance_file,
    _records_from_output_base_dir,
    _select_algorithm_id,
)
from hotvect.json_utils import find_difference_in_files
from hotvect.qa.commands.compare_predictions import compare_predict_jsonl
from hotvect.qa.planning import prod_default_facts_from_slot_active_info
from hotvect.qa.state import append_jsonl as _append_jsonl
from hotvect.qa.state import lock_run as _lock_run
from hotvect.qa.state import read_json as _read_json
from hotvect.qa.state import write_json as _write_json
from hotvect.sagemaker_config import validate_job_prefix
from hotvect.utils import (
    MalformedAlgorithmException,
    as_locally_available_content,
    get_boto_session_after_assuming_role,
    read_algorithm_definition_from_jar,
    sanitize_path_component,
)

from .base import BaseCommand

_SCENARIO_FILE = "plan.json"
_STATUS_FILE = "status.json"
_DECISIONS_FILE = "decisions.jsonl"
_EVIDENCE_FILE = "evidence.json"

_DEFAULT_CRITERIA_ID = "noninferiority"
_DEFAULT_UNTIL_STAGE = "multi_day_backtest"
_DEFAULT_BACKTEST_DAYS = 7
_SHARED_PARAMETER_REQUIRED_CRITERIA_IDS = frozenset(("exact",))
_PARITY_STAGE_SAMPLES = 3000
_PERFORMANCE_STAGE_SAMPLES = 3000
_DEFAULT_SAGEMAKER_POLL_SECONDS = 60
_DEFAULT_SYSTEM_PERFORMANCE_TRIALS = 7
_SYSTEM_PERFORMANCE_STATISTICAL_ALPHA = 0.05
_DEFAULT_SAGEMAKER_JOB_PREFIX = "ml-exp"
_MAX_FAILURE_SUMMARY_CHARS = 400
_MAX_DEBUG_LOG_LINES = 40
_MAX_DEBUG_LOG_LINE_CHARS = 400
_MAX_DEBUG_STAGE_FILES = 80
_MAX_DEBUG_DECISION_EVENTS = 10
_MAX_DEBUG_TRACEBACK_LINES = 80
_MAX_DEBUG_THROWABLE_LINES = 160
_MAX_DEBUG_THROWABLE_LINE_CHARS = 2000
_SYSTEM_PERFORMANCE_RUNNERS = ("auto", "local", "sagemaker")
_SYSTEM_PERFORMANCE_OPTION_KEYS = (
    "runner",
    "sagemaker_job_prefix",
    "sagemaker_config",
    "role_arn",
    "assume_role_arn",
    "s3_output_base",
    "instance_type",
    "volume_gb",
    "max_runtime_seconds",
    "training_image",
    "samples",
    "sample_pool_size",
    "target_rps",
    "target_throughput_fraction",
    "workload_mode",
    "max_threads",
    "poll_seconds",
    "trials",
)
_DEBUG_LOCAL_PATH_FLAGS = frozenset(
    (
        "--algorithm-jar",
        "--dest-path",
        "--dest-schema-path",
        "--metadata-path",
        "--parameter-path",
        "--sagemaker-config",
        "--scratch-dir",
        "--data-base-dir",
        "--output-base-dir",
        "--source-path",
    )
)
_DEBUG_REMOTE_OR_LOCAL_PATH_FLAGS = frozenset(
    (
        "--source-s3-uri",
        "--parameter-s3-uri",
        "--s3-output-base",
    )
)

_ALL_QA_RUN_STAGES = (
    "audit_parity",
    "encode_parity",
    "system_performance",
    "predict_parity",
    "realistic_single_day",
    "multi_day_backtest",
)
_PARAMETER_REQUIRED_STAGES = frozenset(("audit_parity", "encode_parity", "system_performance", "predict_parity"))
_BASE_QA_RUN_STAGE_SEQUENCE = (
    "realistic_single_day",
    "multi_day_backtest",
)
_PARAMETERIZED_QA_RUN_STAGE_SEQUENCE = (
    "encode_parity",
    "system_performance",
    "predict_parity",
    "realistic_single_day",
    "multi_day_backtest",
)
_BEHAVIOR_PRESERVING_QA_RUN_STAGE_SEQUENCE = (
    "audit_parity",
    "encode_parity",
    "predict_parity",
    "system_performance",
    "realistic_single_day",
    "multi_day_backtest",
)
_PER_REF_PARAMETER_QA_RUN_STAGE_SEQUENCE = ("system_performance",)

_TRACK_DIRS = (
    "preflight",
    "parameter_artifact",
    "quality",
    "performance",
)

_EXPIRED_AWS_TOKEN_CODES = frozenset(("ExpiredToken", "ExpiredTokenException", "RequestExpired"))
_RetryResult = TypeVar("_RetryResult")

_TRANSIENT_AWS_ERRORS = (ConnectTimeoutError, ConnectionClosedError, EndpointConnectionError, ReadTimeoutError)

_EXECUTABLE_QA_RUN_STAGES = frozenset(
    (
        "audit_parity",
        "encode_parity",
        "system_performance",
        "predict_parity",
        "realistic_single_day",
        "multi_day_backtest",
    )
)
_STAGE_TRACKS = {
    "audit_parity": "preflight",
    "encode_parity": "preflight",
    "system_performance": "performance",
    "predict_parity": "preflight",
    "realistic_single_day": "quality",
    "multi_day_backtest": "quality",
}
_STAGE_REQUIREMENTS = {
    "audit_parity": ("algo_repo_url", "encode_algorithm_name", "encode_source_path"),
    "encode_parity": ("algo_repo_url", "encode_algorithm_name", "encode_source_path"),
    "system_performance": ("algo_repo_url", "algorithm_name", "performance_source_path"),
    "predict_parity": ("algo_repo_url", "algorithm_name", "predict_source_path"),
    "realistic_single_day": ("algo_repo_url", "data_base_dir"),
    "multi_day_backtest": ("algo_repo_url", "data_base_dir"),
}


@dataclass(frozen=True)
class _BuiltRefArtifact:
    git_ref: str
    resolved_git_ref: str
    artifact_name: str
    artifact_version: str
    git_commit: str
    jar_path: Path
    build_dir: Path
    algo_repo_url: str | None = None


@dataclass(frozen=True)
class _ParameterSourceInspection:
    local_path: str
    algorithm_name: str
    algorithm_version: str
    parameter_id: str | None


@dataclass(frozen=True)
class _SystemPerformanceOptions:
    runner: str = "auto"
    sagemaker_job_prefix: str | None = None
    sagemaker_config: str | None = None
    role_arn: str | None = None
    assume_role_arn: str | None = None
    s3_output_base: str | None = None
    instance_type: str | None = None
    volume_gb: int | None = None
    max_runtime_seconds: int | None = None
    training_image: str | None = None
    samples: int | None = None
    sample_pool_size: int | None = None
    target_rps: float | None = None
    target_throughput_fraction: float | None = None
    workload_mode: str | None = None
    max_threads: int | None = None
    poll_seconds: int = _DEFAULT_SAGEMAKER_POLL_SECONDS
    trials: int = _DEFAULT_SYSTEM_PERFORMANCE_TRIALS

    @classmethod
    def from_mapping(cls, mapping: dict[str, Any] | None) -> "_SystemPerformanceOptions":
        payload = dict(mapping or {})
        return cls(
            runner=_normalize_runner(payload.get("runner")),
            sagemaker_job_prefix=_normalize_optional_string(payload.get("sagemaker_job_prefix")),
            sagemaker_config=_normalize_optional_string(payload.get("sagemaker_config")),
            role_arn=_normalize_optional_string(payload.get("role_arn")),
            assume_role_arn=_normalize_optional_string(payload.get("assume_role_arn")),
            s3_output_base=_normalize_optional_string(payload.get("s3_output_base")),
            instance_type=_normalize_optional_string(payload.get("instance_type")),
            volume_gb=_normalize_optional_int(
                payload.get("volume_gb"), flag_name="--performance-volume-gb", min_value=1
            ),
            max_runtime_seconds=_normalize_optional_int(
                payload.get("max_runtime_seconds"),
                flag_name="--performance-max-runtime-seconds",
                min_value=1,
            ),
            training_image=_normalize_optional_string(payload.get("training_image")),
            samples=_normalize_optional_int(payload.get("samples"), flag_name="--performance-samples", min_value=1),
            sample_pool_size=_normalize_optional_int(
                payload.get("sample_pool_size"),
                flag_name="--performance-sample-pool-size",
                min_value=1,
            ),
            target_rps=_normalize_optional_float(payload.get("target_rps"), flag_name="--performance-target-rps"),
            target_throughput_fraction=_normalize_optional_float(
                payload.get("target_throughput_fraction"),
                flag_name="--performance-target-throughput-fraction",
            ),
            workload_mode=_normalize_workload_mode(payload.get("workload_mode")),
            max_threads=_normalize_optional_int(
                payload.get("max_threads"),
                flag_name="--performance-max-threads",
                min_value=0,
            ),
            poll_seconds=_normalize_optional_int(
                payload.get("poll_seconds") or _DEFAULT_SAGEMAKER_POLL_SECONDS,
                flag_name="--performance-poll-seconds",
                min_value=1,
            )
            or _DEFAULT_SAGEMAKER_POLL_SECONDS,
            trials=_normalize_optional_int(
                payload.get("trials") or _DEFAULT_SYSTEM_PERFORMANCE_TRIALS,
                flag_name="--performance-trials",
                min_value=1,
            )
            or _DEFAULT_SYSTEM_PERFORMANCE_TRIALS,
        )

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    def to_hv_performance_test_args(
        self,
        *,
        include_remote_options: bool,
        include_samples: bool,
    ) -> list[str]:
        args: list[str] = []
        if include_remote_options:
            if self.sagemaker_config:
                args.extend(["--sagemaker-config", self.sagemaker_config])
            if self.role_arn:
                args.extend(["--role-arn", self.role_arn])
            if self.assume_role_arn:
                args.extend(["--assume-role-arn", self.assume_role_arn])
            if self.s3_output_base:
                args.extend(["--s3-output-base", self.s3_output_base])
            if self.instance_type:
                args.extend(["--instance-type", self.instance_type])
            if self.volume_gb is not None:
                args.extend(["--volume-gb", str(self.volume_gb)])
            if self.max_runtime_seconds is not None:
                args.extend(["--max-runtime-seconds", str(self.max_runtime_seconds)])
            if self.training_image:
                args.extend(["--training-image", self.training_image])
        if include_samples and self.samples is not None:
            args.extend(["--samples", str(self.samples)])
        if self.sample_pool_size is not None:
            args.extend(["--sample-pool-size", str(self.sample_pool_size)])
        if self.target_rps is not None:
            args.extend(["--target-rps", str(self.target_rps)])
        if self.target_throughput_fraction is not None:
            args.extend(["--target-throughput-fraction", str(self.target_throughput_fraction)])
        if self.workload_mode:
            args.extend(["--workload-mode", self.workload_mode])
        if self.max_threads is not None:
            args.extend(["--max-threads", str(self.max_threads)])
        return args


@dataclass(frozen=True)
class _BacktestOptions:
    runner: str = "auto"
    algorithm_overrides: list[str] = field(default_factory=list)
    no_performance_test: bool = False
    performance_test_samples: int | None = None
    performance_test_sample_pool_size: int | None = None
    sagemaker_job_prefix: str | None = None
    sagemaker_config: str | None = None
    role_arn: str | None = None
    assume_role_arn: str | None = None
    s3_output_base: str | None = None
    instance_type: str | None = None
    volume_gb: int | None = None
    max_runtime_seconds: int | None = None
    training_image: str | None = None
    auto_attach_data_default_s3_base: str | None = None
    auto_attach_data_environment: str = "production"
    poll_seconds: int = _DEFAULT_SAGEMAKER_POLL_SECONDS

    @classmethod
    def from_mapping(cls, mapping: dict[str, Any] | None) -> "_BacktestOptions":
        payload = dict(mapping or {})
        return cls(
            runner=_normalize_backtest_runner(payload.get("runner")),
            algorithm_overrides=_normalize_algorithm_overrides(payload.get("algorithm_overrides")),
            no_performance_test=bool(payload.get("no_performance_test")),
            performance_test_samples=_normalize_optional_int(
                payload.get("performance_test_samples"),
                flag_name="--backtest-performance-test-samples",
                min_value=1,
            ),
            performance_test_sample_pool_size=_normalize_optional_int(
                payload.get("performance_test_sample_pool_size"),
                flag_name="--backtest-performance-test-sample-pool-size",
                min_value=1,
            ),
            sagemaker_job_prefix=_normalize_optional_string(payload.get("sagemaker_job_prefix")),
            sagemaker_config=_normalize_context_value(payload.get("sagemaker_config"), allow_remote=False),
            role_arn=_normalize_optional_string(payload.get("role_arn")),
            assume_role_arn=_normalize_optional_string(payload.get("assume_role_arn")),
            s3_output_base=_normalize_optional_string(payload.get("s3_output_base")),
            instance_type=_normalize_optional_string(payload.get("instance_type")),
            volume_gb=_normalize_optional_int(payload.get("volume_gb"), flag_name="--backtest-volume-gb", min_value=1),
            max_runtime_seconds=_normalize_optional_int(
                payload.get("max_runtime_seconds"),
                flag_name="--backtest-max-runtime-seconds",
                min_value=1,
            ),
            training_image=_normalize_optional_string(payload.get("training_image")),
            auto_attach_data_default_s3_base=_normalize_optional_string(
                payload.get("auto_attach_data_default_s3_base")
            ),
            auto_attach_data_environment=str(payload.get("auto_attach_data_environment") or "production").strip()
            or "production",
            poll_seconds=_normalize_optional_int(
                payload.get("poll_seconds") or _DEFAULT_SAGEMAKER_POLL_SECONDS,
                flag_name="--backtest-poll-seconds",
                min_value=1,
            )
            or _DEFAULT_SAGEMAKER_POLL_SECONDS,
        )

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    def remote_requested(self) -> bool:
        return any(
            (
                self.sagemaker_job_prefix,
                self.sagemaker_config,
                self.role_arn,
                self.s3_output_base,
                self.instance_type,
                self.volume_gb is not None,
                self.max_runtime_seconds is not None,
                self.training_image,
            )
        )

    def resolved_runner(self) -> str:
        if self.runner != "auto":
            return self.runner
        return "sagemaker" if self.remote_requested() else "local"

    def to_hv_backtest_args(self, *, include_remote_options: bool) -> list[str]:
        args: list[str] = []
        if self.no_performance_test:
            args.append("--no-performance-test")
        if self.performance_test_samples is not None:
            args.extend(["--performance-test-samples", str(self.performance_test_samples)])
        if self.performance_test_sample_pool_size is not None:
            args.extend(["--performance-test-sample-pool-size", str(self.performance_test_sample_pool_size)])
        if include_remote_options:
            args.append("--sagemaker")
            if self.sagemaker_job_prefix:
                args.extend(["--sagemaker-job-prefix", self.sagemaker_job_prefix])
            if self.sagemaker_config:
                args.extend(["--sagemaker-config", self.sagemaker_config])
            if self.role_arn:
                args.extend(["--role-arn", self.role_arn])
            if self.assume_role_arn:
                args.extend(["--assume-role-arn", self.assume_role_arn])
            if self.s3_output_base:
                args.extend(["--s3-output-base", self.s3_output_base])
            if self.instance_type:
                args.extend(["--instance-type", self.instance_type])
            if self.volume_gb is not None:
                args.extend(["--volume-gb", str(self.volume_gb)])
            if self.max_runtime_seconds is not None:
                args.extend(["--max-runtime-seconds", str(self.max_runtime_seconds)])
            if self.training_image:
                args.extend(["--training-image", self.training_image])
            if self.auto_attach_data_default_s3_base:
                args.extend(["--auto-attach-data-default-s3-base", self.auto_attach_data_default_s3_base])
            if self.auto_attach_data_environment:
                args.extend(["--auto-attach-data-environment", self.auto_attach_data_environment])
        return args


class _HvCommandFailure(RuntimeError):
    def __init__(
        self,
        message: str,
        *,
        command_name: str,
        hv_args: list[str],
        wrapper_cmd: list[str],
        log_path: str,
        metadata_dir: str | None,
        log_paths: dict[str, str],
        log_tail: dict[str, list[str]],
        root_cause: str | None,
        throwable_stacktrace: str | None,
        return_code: int,
        cwd: str | None,
    ) -> None:
        super().__init__(message)
        self.command_name = command_name
        self.hv_args = list(hv_args)
        self.wrapper_cmd = list(wrapper_cmd)
        self.log_path = log_path
        self.metadata_dir = metadata_dir
        self.log_paths = dict(log_paths)
        self.log_tail = {key: list(value) for key, value in log_tail.items()}
        self.root_cause = root_cause
        self.throwable_stacktrace = throwable_stacktrace
        self.return_code = return_code
        self.cwd = cwd

    def to_debug_dict(self) -> dict[str, Any]:
        return {
            "kind": "hv_command_failure",
            "command": {
                "command_name": self.command_name,
                "hv_args": _normalize_debug_cli_args(self.hv_args),
                "wrapper_cmd": _normalize_debug_wrapper_cmd(self.wrapper_cmd),
                "cwd": _normalize_local_debug_path(self.cwd),
            },
            "return_code": self.return_code,
            "root_cause": self.root_cause,
            "throwable_stacktrace": self.throwable_stacktrace,
            "log_paths": {key: _normalize_local_debug_path(value) or value for key, value in self.log_paths.items()},
            "log_tail": self.log_tail,
            "metadata_dir": _normalize_local_debug_path(self.metadata_dir),
        }


def _now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _resolve_runs_dir(meta_dir: str | None) -> Path:
    return hv_config.resolve_meta_dir(meta_dir=meta_dir) / "hv-qa" / "runs"


def _resolve_run_dir(meta_dir: str | None, run_id: str) -> Path:
    run_dir = _resolve_runs_dir(meta_dir) / run_id
    if not run_dir.exists():
        raise FileNotFoundError(f"hv-qa run not found: {run_dir}")
    return run_dir


def _normalize_parameter_source(parameter_source: str | None) -> str | None:
    return _normalize_context_value(parameter_source, allow_remote=True)


def _normalize_parameter_source_list(parameter_sources: list[str] | None) -> list[str]:
    return [
        normalized
        for normalized in (
            _normalize_parameter_source(parameter_source) for parameter_source in parameter_sources or []
        )
        if normalized
    ]


def _resolve_parameter_sources(
    *,
    shared_parameter_source: str | None,
    control_parameter_source: str | None,
    treatment_parameter_sources: list[str] | None,
    control_ref: str,
    treatment_refs: list[str],
) -> dict[str, Any] | None:
    shared = _normalize_parameter_source(shared_parameter_source)
    control = _normalize_parameter_source(control_parameter_source)
    treatments = _normalize_parameter_source_list(treatment_parameter_sources)
    has_per_ref = bool(control or treatments)
    if shared and has_per_ref:
        raise ValueError("Use either a shared parameter source or per-ref parameter sources, not both.")
    if not has_per_ref:
        return None
    if not control:
        raise ValueError("Per-ref system_performance requires a control parameter source.")
    if len(treatments) != len(treatment_refs):
        raise ValueError(
            "Per-ref system_performance requires exactly one treatment parameter source for each treatment "
            f"in the same order. Got {len(treatments)} for {len(treatment_refs)} treatments."
        )
    return {
        "mode": "per_ref",
        "control": {
            "git_ref": control_ref,
            "source": control,
        },
        "treatments": [
            {
                "git_ref": git_ref,
                "source": source,
            }
            for git_ref, source in zip(treatment_refs, treatments)
        ],
    }


def _normalize_parameter_sources(parameter_sources: dict[str, Any] | None) -> dict[str, Any] | None:
    if not parameter_sources:
        return None
    mode = str(parameter_sources.get("mode") or "").strip()
    if mode != "per_ref":
        raise ValueError(f"Unsupported parameter_sources.mode: {mode!r}")
    control = dict(parameter_sources.get("control") or {})
    control_source = _normalize_parameter_source(control.get("source"))
    if not control_source:
        raise ValueError("parameter_sources.control.source is required.")
    treatments: list[dict[str, str]] = []
    for item in parameter_sources.get("treatments") or []:
        payload = dict(item or {})
        git_ref = str(payload.get("git_ref") or "").strip()
        source = _normalize_parameter_source(payload.get("source"))
        if not git_ref or not source:
            raise ValueError("Every parameter_sources.treatments entry requires git_ref and source.")
        treatments.append({"git_ref": git_ref, "source": source})
    return {
        "mode": "per_ref",
        "control": {
            "git_ref": str(control.get("git_ref") or "").strip(),
            "source": control_source,
        },
        "treatments": treatments,
    }


def _normalize_performance_source_path(source_path: str | None) -> str | None:
    return _normalize_context_value(source_path, allow_remote=True)


def _normalize_performance_source_path_list(source_paths: list[str] | None) -> list[str]:
    return [
        normalized
        for normalized in (_normalize_performance_source_path(source_path) for source_path in source_paths or [])
        if normalized
    ]


def _resolve_performance_source_paths(
    *,
    shared_performance_source_path: str | None,
    control_performance_source_path: str | None,
    treatment_performance_source_paths: list[str] | None,
    control_ref: str,
    treatment_refs: list[str],
) -> dict[str, Any] | None:
    shared = _normalize_performance_source_path(shared_performance_source_path)
    control = _normalize_performance_source_path(control_performance_source_path)
    treatments = _normalize_performance_source_path_list(treatment_performance_source_paths)
    has_per_ref = bool(control or treatments)
    if shared and has_per_ref:
        raise ValueError("Use either --performance-source-path or per-ref performance sources, not both.")
    if not has_per_ref:
        return None
    if not control:
        raise ValueError("Per-ref system_performance requires --control-performance-source-path.")
    if len(treatments) != len(treatment_refs):
        raise ValueError(
            "Per-ref system_performance requires exactly one --treatment-performance-source-path for each "
            f"--treatment in the same order. Got {len(treatments)} for {len(treatment_refs)} treatments."
        )
    return {
        "mode": "per_ref",
        "control": {
            "git_ref": control_ref,
            "source": control,
        },
        "treatments": [
            {
                "git_ref": git_ref,
                "source": source,
            }
            for git_ref, source in zip(treatment_refs, treatments)
        ],
    }


def _normalize_performance_source_paths(source_paths: dict[str, Any] | None) -> dict[str, Any] | None:
    if not source_paths:
        return None
    mode = str(source_paths.get("mode") or "").strip()
    if mode != "per_ref":
        raise ValueError(f"Unsupported performance_source_paths.mode: {mode!r}")
    control = dict(source_paths.get("control") or {})
    control_source = _normalize_performance_source_path(control.get("source"))
    if not control_source:
        raise ValueError("performance_source_paths.control.source is required.")
    treatments: list[dict[str, str]] = []
    for item in source_paths.get("treatments") or []:
        payload = dict(item or {})
        git_ref = str(payload.get("git_ref") or "").strip()
        source = _normalize_performance_source_path(payload.get("source"))
        if not git_ref or not source:
            raise ValueError("Every performance_source_paths.treatments entry requires git_ref and source.")
        treatments.append({"git_ref": git_ref, "source": source})
    return {
        "mode": "per_ref",
        "control": {
            "git_ref": str(control.get("git_ref") or "").strip(),
            "source": control_source,
        },
        "treatments": treatments,
    }


def _normalize_encode_algorithm_name(algorithm_name: str | None) -> str | None:
    return str(algorithm_name or "").strip() or None


def _normalize_encode_algorithm_name_list(algorithm_names: list[str] | None) -> list[str]:
    return [
        normalized
        for normalized in (_normalize_encode_algorithm_name(algorithm_name) for algorithm_name in algorithm_names or [])
        if normalized
    ]


def _resolve_encode_algorithm_names(
    *,
    control_encode_algorithm_name: str | None,
    treatment_encode_algorithm_names: list[str] | None,
    control_ref: str,
    treatment_refs: list[str],
) -> dict[str, Any] | None:
    control = _normalize_encode_algorithm_name(control_encode_algorithm_name)
    treatments = _normalize_encode_algorithm_name_list(treatment_encode_algorithm_names)
    has_per_ref = bool(control or treatments)
    if not has_per_ref:
        return None
    if not control:
        raise ValueError("Per-ref encode_parity requires --control-encode-algorithm-name.")
    if len(treatments) != len(treatment_refs):
        raise ValueError(
            "Per-ref encode_parity requires exactly one --treatment-encode-algorithm-name for each --treatment "
            f"in the same order. Got {len(treatments)} for {len(treatment_refs)} treatments."
        )
    return {
        "mode": "per_ref",
        "control": {
            "git_ref": control_ref,
            "algorithm_name": control,
        },
        "treatments": [
            {
                "git_ref": git_ref,
                "algorithm_name": algorithm_name,
            }
            for git_ref, algorithm_name in zip(treatment_refs, treatments)
        ],
    }


def _normalize_encode_algorithm_names(algorithm_names: dict[str, Any] | None) -> dict[str, Any] | None:
    if not algorithm_names:
        return None
    mode = str(algorithm_names.get("mode") or "").strip()
    if mode != "per_ref":
        raise ValueError(f"Unsupported encode_algorithm_names.mode: {mode!r}")
    control = dict(algorithm_names.get("control") or {})
    control_algorithm_name = _normalize_encode_algorithm_name(control.get("algorithm_name"))
    if not control_algorithm_name:
        raise ValueError("encode_algorithm_names.control.algorithm_name is required.")
    treatments: list[dict[str, str]] = []
    for item in algorithm_names.get("treatments") or []:
        payload = dict(item or {})
        git_ref = str(payload.get("git_ref") or "").strip()
        algorithm_name = _normalize_encode_algorithm_name(payload.get("algorithm_name"))
        if not git_ref or not algorithm_name:
            raise ValueError("Every encode_algorithm_names.treatments entry requires git_ref and algorithm_name.")
        treatments.append({"git_ref": git_ref, "algorithm_name": algorithm_name})
    return {
        "mode": "per_ref",
        "control": {
            "git_ref": str(control.get("git_ref") or "").strip(),
            "algorithm_name": control_algorithm_name,
        },
        "treatments": treatments,
    }


def _normalize_algorithm_repo_url(repo_url: str | None) -> str | None:
    return _normalize_context_value(repo_url, allow_remote=True)


def _normalize_algorithm_repo_url_list(repo_urls: list[str] | None) -> list[str]:
    return [
        normalized
        for normalized in (_normalize_algorithm_repo_url(repo_url) for repo_url in repo_urls or [])
        if normalized
    ]


def _resolve_ref_repository_urls(
    *,
    shared_repo_url: str | None,
    control_repo_url: str | None,
    treatment_repo_urls: list[str] | None,
    control_ref: str,
    treatment_refs: list[str],
) -> dict[str, Any]:
    shared = _normalize_algorithm_repo_url(shared_repo_url)
    control = _normalize_algorithm_repo_url(control_repo_url) or shared
    treatments = _normalize_algorithm_repo_url_list(treatment_repo_urls)
    has_repository_input = bool(shared or control or treatments)
    if treatments and len(treatments) != len(treatment_refs):
        raise ValueError(
            "Per-ref repositories require exactly one --treatment-repo for each --treatment "
            f"in the same order. Got {len(treatments)} for {len(treatment_refs)} treatments."
        )
    resolved_treatments = treatments or ([shared] * len(treatment_refs) if shared else [None] * len(treatment_refs))
    if has_repository_input and (
        not control or len(resolved_treatments) != len(treatment_refs) or any(not item for item in resolved_treatments)
    ):
        raise ValueError(
            "Every QA ref requires an algorithm repository. Set --repo as the shared default, or provide "
            "--control-repo and one --treatment-repo for each treatment."
        )
    return {
        "control": {"git_ref": control_ref, "algo_repo_url": control},
        "treatments": [
            {"git_ref": git_ref, "algo_repo_url": repo_url}
            for git_ref, repo_url in zip(treatment_refs, resolved_treatments)
        ],
    }


def _repository_url_for_ref(*, scenario: dict[str, Any], git_ref: str, role: str) -> str:
    refs = dict(scenario.get("refs") or {})
    if role == "control":
        payload = dict(refs.get("control") or {})
        if payload.get("git_ref") != git_ref:
            raise ValueError(f"Control ref mismatch: expected {git_ref!r}, got {payload.get('git_ref')!r}.")
    elif role == "treatment":
        matches = [
            dict(item or {})
            for item in refs.get("treatments") or []
            if str(dict(item or {}).get("git_ref") or "") == git_ref
        ]
        if len(matches) != 1:
            raise ValueError(f"Expected exactly one treatment ref matching {git_ref!r}, found {len(matches)}.")
        payload = matches[0]
    else:
        raise ValueError(f"Unsupported QA ref role: {role!r}.")

    repo_url = _normalize_algorithm_repo_url(payload.get("algo_repo_url"))
    if repo_url:
        return repo_url
    raise ValueError(f"Missing algorithm repository for {role} ref {git_ref!r}.")


def _has_repository_for_every_ref(scenario: dict[str, Any]) -> bool:
    try:
        control_ref = str((scenario.get("refs") or {}).get("control", {}).get("git_ref") or "")
        if not control_ref:
            return False
        _repository_url_for_ref(scenario=scenario, git_ref=control_ref, role="control")
        for treatment in (scenario.get("refs") or {}).get("treatments") or []:
            treatment_ref = str(dict(treatment or {}).get("git_ref") or "")
            if not treatment_ref:
                return False
            _repository_url_for_ref(scenario=scenario, git_ref=treatment_ref, role="treatment")
    except ValueError:
        return False
    return True


def _has_stage_parameter_sources(scenario: dict[str, Any]) -> bool:
    return bool(_normalize_parameter_source(scenario.get("parameter_source")) or scenario.get("parameter_sources"))


def _value_or_config_default(cli_value: Any, config_value: Any) -> Any:
    if isinstance(cli_value, str):
        return cli_value if cli_value.strip() else config_value
    return cli_value if cli_value is not None else config_value


def _normalize_last_test_date(last_test_date: str | None) -> str | None:
    normalized = (last_test_date or "").strip()
    if not normalized:
        return None
    try:
        return date.fromisoformat(normalized).isoformat()
    except ValueError as exc:
        raise ValueError(f"Invalid --last-test-date '{normalized}'. Expected YYYY-MM-DD.") from exc


def _normalize_backtest_days(backtest_days: Any) -> int:
    normalized = str(backtest_days).strip() if backtest_days is not None else ""
    if not normalized:
        return _DEFAULT_BACKTEST_DAYS
    try:
        days = int(normalized)
    except ValueError as exc:
        raise ValueError(f"Invalid --days '{normalized}'. Expected a positive integer.") from exc
    if days <= 0:
        raise ValueError(f"Invalid --days '{normalized}'. Expected a positive integer.")
    return days


def _resolve_offline_context(*, last_test_date: str | None, backtest_days: Any) -> dict[str, Any]:
    resolved_last_test_date = _normalize_last_test_date(last_test_date)
    resolved_backtest_days = _normalize_backtest_days(backtest_days)
    if resolved_last_test_date is None:
        return {
            "last_test_date": None,
            "backtest_days": resolved_backtest_days,
            "backtest_date_window": None,
        }
    end_date = date.fromisoformat(resolved_last_test_date)
    start_date = end_date - timedelta(days=resolved_backtest_days - 1)
    return {
        "last_test_date": resolved_last_test_date,
        "backtest_days": resolved_backtest_days,
        "backtest_date_window": {
            "start_date": start_date.isoformat(),
            "end_date": resolved_last_test_date,
        },
    }


def _resolve_prod_default_slot_control(slot_name: str) -> dict[str, Any]:
    client = create_client_from_hotvect_config()
    active_info = client.get_default_variant_and_active_experiments(slot_name)
    default_facts = prod_default_facts_from_slot_active_info(slot_name=slot_name, active_info=active_info)
    return {
        "mode": "prod_default_of_slot",
        "source": "experiment_management",
        "slot_name": slot_name,
        "algorithm": default_facts["algorithm"],
        "algorithm_version": default_facts["version"],
        "variant": default_facts["variant"],
        "default_created_at": default_facts["created_at"],
        "control_git_ref": default_facts["version"],
    }


def _resolve_prod_default_date_matched_parameters(
    *,
    offline_context: dict[str, Any],
    control_resolution: dict[str, Any],
) -> tuple[dict[str, Any], dict[str, dict[str, str]]]:
    requested_window = dict(offline_context.get("backtest_date_window") or {})
    start_date = date.fromisoformat(str(requested_window["start_date"]))
    end_date = date.fromisoformat(str(requested_window["end_date"]))
    client = create_client_from_hotvect_config()
    matching_parameters: list[dict[str, str | datetime]] = []
    for parameter in client.get_algorithm_parameters():
        algorithm_name = str(parameter.algorithm.algorithm_name).strip()
        algorithm_version = str(parameter.algorithm.algorithm_version).strip()
        if algorithm_name != str(control_resolution["algorithm"]) or algorithm_version != str(
            control_resolution["algorithm_version"]
        ):
            continue
        parameter_id = str(parameter.algorithm_parameter_id).strip()
        source = _normalize_parameter_source(parameter.absolute_s3_path)
        if not parameter_id or not source:
            raise ValueError(
                "Experiment-management returned an incomplete production parameter for "
                f"{algorithm_name}@{algorithm_version}."
            )
        created_at_value = parameter.created_at.astimezone(timezone.utc)
        matching_parameters.append(
            {
                "algorithm_parameter_id": parameter_id,
                "source": source,
                "created_at": created_at_value,
            }
        )
    matching_parameters.sort(key=lambda item: (item["created_at"], str(item["algorithm_parameter_id"])))

    requested_dates: list[date] = []
    cursor = start_date
    while cursor <= end_date:
        requested_dates.append(cursor)
        cursor += timedelta(days=1)

    parameters_by_date: dict[str, dict[str, str]] = {}
    resolution_by_date: dict[str, dict[str, str]] = {}
    for dt in requested_dates:
        day_start = datetime.combine(dt, datetime.min.time(), tzinfo=timezone.utc)
        day_end = day_start + timedelta(days=1)
        parameter_at_start = [parameter for parameter in matching_parameters if parameter["created_at"] < day_start]
        parameters_registered_on_date = [
            parameter for parameter in matching_parameters if day_start <= parameter["created_at"] < day_end
        ]
        if parameters_registered_on_date:
            selected = parameters_registered_on_date[-1]
        elif parameter_at_start:
            selected = parameter_at_start[-1]
        else:
            raise ValueError(
                "Cannot resolve a production parameter "
                f"for slot {control_resolution['slot_name']!r} on {dt.isoformat()}: "
                f"experiment-management has no parameter for {control_resolution['algorithm']}@"
                f"{control_resolution['algorithm_version']} registered by the end of that UTC date."
            )

        created_at = selected["created_at"]
        assert isinstance(created_at, datetime)
        serialized_selected = {
            "algorithm_parameter_id": str(selected["algorithm_parameter_id"]),
            "source": str(selected["source"]),
            "created_at": created_at.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        }
        dt_string = dt.isoformat()
        parameters_by_date[dt_string] = {
            "algorithm_name": str(control_resolution["algorithm"]),
            "algorithm_version": str(control_resolution["algorithm_version"]),
            **serialized_selected,
        }
        resolution_by_date[dt_string] = serialized_selected

    resolved_offline_context = copy.deepcopy(offline_context)
    resolved_offline_context["prod_default_parameter_resolution"] = {
        "algorithm_name": str(control_resolution["algorithm"]),
        "algorithm_version": str(control_resolution["algorithm_version"]),
        "timezone": "UTC",
        "parameter_policy": "last_registered_on_date_else_latest_earlier",
        "dates": resolution_by_date,
    }
    return resolved_offline_context, parameters_by_date


def _resolve_qa_run_stage_sequence(
    parameter_source: str | None,
    parameter_sources: dict[str, Any] | None = None,
    criteria: dict[str, Any] | None = None,
    requested_parameter_stage: bool = False,
) -> tuple[str, ...]:
    if _criteria_requires_shared_parameter_source(criteria):
        criteria_id = _qa_run_criteria_id(criteria)
        if parameter_sources:
            raise ValueError(
                f"Evaluation criteria '{criteria_id}' requires a shared parameter source so hv-qa can run "
                "fixed-parameter audit_parity, encode_parity, and predict_parity. Per-ref parameter sources are only supported "
                "for system_performance."
            )
        if criteria_id == "exact":
            return _BEHAVIOR_PRESERVING_QA_RUN_STAGE_SEQUENCE
        return _PARAMETERIZED_QA_RUN_STAGE_SEQUENCE
    if parameter_sources:
        return _PER_REF_PARAMETER_QA_RUN_STAGE_SEQUENCE
    if requested_parameter_stage:
        return _PARAMETERIZED_QA_RUN_STAGE_SEQUENCE
    if parameter_source:
        return _PARAMETERIZED_QA_RUN_STAGE_SEQUENCE
    return _BASE_QA_RUN_STAGE_SEQUENCE


def _stage_request_needs_parameterized_sequence(*, until_stage: str | None, only_stage: str | None) -> bool:
    requested_stage = (only_stage or until_stage or "").strip()
    return requested_stage in _PARAMETER_REQUIRED_STAGES


def _qa_run_criteria_id(criteria: dict[str, Any] | None) -> str | None:
    if not isinstance(criteria, dict):
        return None
    criteria_id = criteria.get("id")
    return str(criteria_id) if criteria_id else None


def _criteria_requires_shared_parameter_source(criteria: dict[str, Any] | None) -> bool:
    return _qa_run_criteria_id(criteria) in _SHARED_PARAMETER_REQUIRED_CRITERIA_IDS


def _validate_parameter_source_inputs_for_criteria(
    *,
    criteria: dict[str, Any] | None,
    parameter_source: str | None,
    parameter_sources: dict[str, Any] | None,
) -> None:
    if _qa_run_criteria_id(criteria) != "exact":
        return
    if parameter_source or parameter_sources:
        raise ValueError("Evaluation criteria 'exact' does not accept manual parameter source flags.")


def _explicit_version_ref(git_ref: str) -> str | None:
    match = re.fullmatch(r"v?(\d+\.\d+\.\d+)", git_ref.strip())
    if not match:
        return None
    return match.group(1)


def _validate_same_version_policy(control_ref: str, treatment_refs: list[str]) -> None:
    hinted_refs = [(control_ref, _explicit_version_ref(control_ref))]
    hinted_refs.extend((ref, _explicit_version_ref(ref)) for ref in treatment_refs)

    seen_by_version: dict[str, str] = {}
    collisions: list[tuple[str, str, str]] = []
    for git_ref, version_hint in hinted_refs:
        if not version_hint:
            continue
        existing_ref = seen_by_version.get(version_hint)
        if existing_ref is not None:
            collisions.append((existing_ref, git_ref, version_hint))
            continue
        seen_by_version[version_hint] = git_ref

    if collisions:
        rendered = "; ".join(
            f"'{left_ref}' and '{right_ref}' both point at version {version}"
            for left_ref, right_ref, version in collisions
        )
        raise ValueError(
            "QA runs require a new algorithm version for every compared ref. "
            f"Detected version collisions from ref names: {rendered}. "
            "Bump the treatment version before running hv-qa."
        )


def _resolve_qa_run_criteria(criteria_spec: str | None) -> dict[str, Any]:
    criteria_selector = (criteria_spec or "").strip() or _DEFAULT_CRITERIA_ID
    try:
        policy = load_builtin_policy(criteria_selector)
    except KeyError as exc:
        message = exc.args[0] if exc.args else str(exc)
        raise ValueError(message) from exc
    if policy.manifest.scenario != "qa":
        raise ValueError(
            f"Criteria '{policy.manifest.identifier}' is for scenario '{policy.manifest.scenario}', not 'qa'."
        )
    return {
        "kind": "builtin",
        "id": policy.manifest.name,
        "manifest": policy.manifest.to_dict(),
    }


def _validate_frozen_criteria(criteria: dict[str, Any]) -> None:
    policy = load_builtin_policy(str(criteria["id"]))
    recorded_manifest = criteria["manifest"]
    current_manifest = policy.manifest.to_dict()
    if recorded_manifest != current_manifest:
        raise ValueError(
            "The criteria implementation no longer matches this run's frozen manifest. "
            f"Recorded={recorded_manifest!r}, current={current_manifest!r}. Start a new QA run."
        )


def _resolve_stage_request(
    *,
    until_stage: str | None,
    only_stage: str | None,
    stage_sequence: tuple[str, ...],
    default_until_stage: str,
    require_resume_for_only: bool,
    resume: bool,
) -> tuple[str, str, list[str]]:
    if until_stage and only_stage:
        raise ValueError("Use either --until or --stage, not both.")

    if only_stage:
        if require_resume_for_only and not resume:
            raise ValueError("--stage requires an existing run context; resume the existing QA run instead.")
        if only_stage not in _ALL_QA_RUN_STAGES:
            raise ValueError(f"Unsupported stage '{only_stage}'. Supported stages: {list(_ALL_QA_RUN_STAGES)}")
        if only_stage not in stage_sequence:
            raise ValueError(_stage_unavailable_message(only_stage))
        return "only", only_stage, [only_stage]

    target_stage = (until_stage or default_until_stage).strip()
    if target_stage not in _ALL_QA_RUN_STAGES:
        raise ValueError(f"Unsupported stage '{target_stage}'. Supported stages: {list(_ALL_QA_RUN_STAGES)}")
    if target_stage not in stage_sequence:
        raise ValueError(_stage_unavailable_message(target_stage))
    target_index = stage_sequence.index(target_stage)
    return "until", target_stage, list(stage_sequence[: target_index + 1])


def _stage_unavailable_message(stage: str) -> str:
    if stage in {"audit_parity", "encode_parity", "predict_parity"}:
        return f"Stage '{stage}' is only available for fixed-parameter QA runs."
    if stage == "system_performance":
        return "Stage 'system_performance' is only available for fixed-parameter QA runs."
    return f"Stage '{stage}' is not available in the current QA run configuration."


def _generate_run_id(*, control_ref: str, treatment_refs: list[str]) -> str:
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    treatment_slug = sanitize_path_component(treatment_refs[0], max_length=24) if treatment_refs else "qa"
    control_slug = sanitize_path_component(control_ref, max_length=16)
    suffix = secrets.token_hex(2)
    return f"{timestamp}-qa-{control_slug}-vs-{treatment_slug}-{suffix}"


def _scenario_paths(run_dir: Path) -> dict[str, Path]:
    return {
        "scenario": run_dir / _SCENARIO_FILE,
        "status": run_dir / _STATUS_FILE,
        "decisions": run_dir / _DECISIONS_FILE,
        "evidence": run_dir / _EVIDENCE_FILE,
    }


def _python_dir() -> Path:
    return Path(__file__).resolve().parents[3]


def _hv_bin_path(command_name: str) -> Path:
    return _python_dir() / "bin" / command_name


def _normalize_context_value(value: str | None, *, allow_remote: bool) -> str | None:
    normalized = (value or "").strip()
    if not normalized:
        return None
    if allow_remote and (normalized.startswith("s3://") or normalized.startswith("git@") or "://" in normalized):
        return normalized
    return str(Path(normalized).expanduser().resolve())


def _normalize_optional_int(value: Any, *, flag_name: str, min_value: int | None = None) -> int | None:
    if value in (None, ""):
        return None
    try:
        normalized = int(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"Invalid {flag_name!s} '{value}'. Expected an integer.") from exc
    if min_value is not None and normalized < min_value:
        raise ValueError(f"Invalid {flag_name!s} '{value}'. Expected >= {min_value}.")
    return normalized


def _normalize_optional_float(value: Any, *, flag_name: str) -> float | None:
    if value in (None, ""):
        return None
    try:
        return float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"Invalid {flag_name!s} '{value}'. Expected a number.") from exc


def _normalize_runner(value: Any) -> str:
    normalized = str(value or "").strip() or "auto"
    if normalized not in _SYSTEM_PERFORMANCE_RUNNERS:
        raise ValueError(
            f"Invalid system performance runner '{normalized}'. Supported: {list(_SYSTEM_PERFORMANCE_RUNNERS)}"
        )
    return normalized


def _normalize_backtest_runner(value: Any) -> str:
    normalized = str(value or "").strip() or "auto"
    if normalized not in _SYSTEM_PERFORMANCE_RUNNERS:
        raise ValueError(f"Invalid backtest runner '{normalized}'. Supported: {list(_SYSTEM_PERFORMANCE_RUNNERS)}")
    return normalized


def _normalize_workload_mode(value: Any) -> str | None:
    normalized = str(value or "").strip() or None
    if normalized not in (None, "realtime", "batch"):
        raise ValueError(f"Invalid system performance workload mode '{normalized}'. Supported: ['realtime', 'batch']")
    return normalized


@dataclass(frozen=True)
class _PerformanceTestSpec:
    samples: int
    sample_pool_size: int
    target_rps: float | None
    target_throughput_fraction: float | None
    workload_mode: str

    @classmethod
    def from_algorithm_definition(cls, algorithm_definition: dict[str, Any], *, git_ref: str) -> "_PerformanceTestSpec":
        execution_parameters = algorithm_definition.get("hotvect_execution_parameters")
        performance_test = (
            execution_parameters.get("performance-test") if isinstance(execution_parameters, dict) else None
        )
        if not isinstance(performance_test, dict):
            raise ValueError(f"Missing committed performance-test spec for {git_ref}.")
        if performance_test.get("enabled") is False:
            raise ValueError(f"Committed performance-test spec is disabled for {git_ref}.")

        samples = _normalize_optional_int(
            performance_test.get("samples"), flag_name=f"{git_ref} performance-test.samples", min_value=1
        )
        sample_pool_size = _normalize_optional_int(
            performance_test.get("sample_pool_size"),
            flag_name=f"{git_ref} performance-test.sample_pool_size",
            min_value=1,
        )
        target_rps = _normalize_optional_float(
            performance_test.get("target_rps"), flag_name=f"{git_ref} performance-test.target_rps"
        )
        target_throughput_fraction = _normalize_optional_float(
            performance_test.get("target_throughput_fraction"),
            flag_name=f"{git_ref} performance-test.target_throughput_fraction",
        )
        workload_mode = _normalize_workload_mode(performance_test.get("workload_mode"))

        if samples is None or sample_pool_size is None or workload_mode is None:
            raise ValueError(
                f"Committed performance-test spec for {git_ref} must define samples, sample_pool_size, and workload_mode."
            )
        if (target_rps is None) == (target_throughput_fraction is None):
            raise ValueError(
                f"Committed performance-test spec for {git_ref} must define exactly one of target_rps or "
                "target_throughput_fraction."
            )
        if target_rps is not None and target_rps <= 0:
            raise ValueError(f"Committed performance-test spec for {git_ref} has non-positive target_rps.")
        if target_throughput_fraction is not None and target_throughput_fraction < 0:
            raise ValueError(f"Committed performance-test spec for {git_ref} has negative target_throughput_fraction.")
        return cls(
            samples=samples,
            sample_pool_size=sample_pool_size,
            target_rps=target_rps,
            target_throughput_fraction=target_throughput_fraction,
            workload_mode=workload_mode,
        )

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    def sha256(self) -> str:
        return hashlib.sha256(json.dumps(self.to_dict(), sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def _resolve_matched_performance_spec(
    *,
    control_ref: str,
    control_definition: dict[str, Any],
    treatment_ref: str,
    treatment_definition: dict[str, Any],
) -> _PerformanceTestSpec:
    control_spec = _PerformanceTestSpec.from_algorithm_definition(control_definition, git_ref=control_ref)
    treatment_spec = _PerformanceTestSpec.from_algorithm_definition(treatment_definition, git_ref=treatment_ref)
    if control_spec != treatment_spec:
        raise ValueError(
            "System performance comparison requires equal committed performance-test specs: "
            f"{control_ref}={control_spec.to_dict()}, {treatment_ref}={treatment_spec.to_dict()}."
        )
    return control_spec


def _resolve_system_performance_options_from_spec(
    *,
    configured_options: _SystemPerformanceOptions,
    specification: _PerformanceTestSpec,
) -> _SystemPerformanceOptions:
    configured = configured_options.to_dict()
    specification_values = specification.to_dict()
    for field_name, specification_value in specification_values.items():
        configured_value = configured.get(field_name)
        if configured_value is not None and configured_value != specification_value:
            raise ValueError(
                f"Configured system performance {field_name}={configured_value!r} conflicts with the committed "
                f"performance-test spec value {specification_value!r}."
            )
        configured[field_name] = specification_value
    return _SystemPerformanceOptions.from_mapping(configured)


def _normalize_algorithm_overrides(values: Any) -> list[str]:
    ret: list[str] = []
    if isinstance(values, str):
        values = [values]
    for value in values or []:
        normalized = _normalize_context_value(value, allow_remote=False)
        if normalized:
            ret.append(normalized)
    return ret


def _normalize_backtest_result_sources(values: Any) -> list[str]:
    ret: list[str] = []
    if isinstance(values, str):
        values = [values]
    for value in values or []:
        normalized = _normalize_context_value(value, allow_remote=True)
        if normalized:
            ret.append(normalized)
    return ret


def _is_s3_uri(value: str | None) -> bool:
    return bool(value and str(value).startswith("s3://"))


def _client_error_code(exc: ClientError) -> str | None:
    error = exc.response.get("Error") if isinstance(exc.response, dict) else None
    if not isinstance(error, dict):
        return None
    code = error.get("Code")
    return str(code) if code else None


def _is_expired_aws_credentials_error(exc: ClientError) -> bool:
    return _client_error_code(exc) in _EXPIRED_AWS_TOKEN_CODES


def _credential_helper_command() -> list[str] | None:
    cfg = hv_config.try_load_config() or {}
    aws_cfg = cfg.get("aws")
    if not isinstance(aws_cfg, dict):
        return None
    helper = aws_cfg.get("credential_helper")
    if not isinstance(helper, str) or not helper.strip():
        return None
    return shlex.split(helper)


def _refresh_aws_credentials(*, reason: str) -> None:
    helper_cmd = _credential_helper_command()
    if not helper_cmd:
        raise ValueError(
            f"AWS credentials expired while {reason}, and no aws.credential_helper is configured in ~/.hotvect/config.json."
        )
    print(
        f"Refreshing AWS credentials via configured helper while {reason}: {' '.join(helper_cmd)}",
        file=sys.stderr,
        flush=True,
    )
    completed = subprocess.run(
        helper_cmd,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if completed.returncode != 0:
        stderr = (completed.stderr or "").strip()
        stdout = (completed.stdout or "").strip()
        details = stderr or stdout or f"exit code {completed.returncode}"
        raise ValueError(f"AWS credential helper failed while {reason}: {details}")
    boto3.DEFAULT_SESSION = None


def _retry_after_refreshing_expired_aws_credentials(
    *,
    reason: str,
    operation: Callable[[], _RetryResult],
) -> _RetryResult:
    max_attempts = 4
    for attempt in range(1, max_attempts + 1):
        try:
            return operation()
        except ClientError as exc:
            if not _is_expired_aws_credentials_error(exc):
                raise
            _refresh_aws_credentials(reason=reason)
            return operation()
        except _TRANSIENT_AWS_ERRORS:
            if attempt >= max_attempts:
                raise
            time.sleep(min(2 * attempt, 10))
    raise RuntimeError("unreachable")


def _normalize_optional_string(value: Any) -> str | None:
    if value is None:
        return None
    normalized = str(value).strip()
    return normalized or None


def _git_command_output(repo_path: Path, *args: str) -> str:
    completed = subprocess.run(
        ["git", "-C", str(repo_path), *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if completed.returncode != 0:
        return ""
    return completed.stdout.strip()


def _git_ref_exists(repo_path: Path, git_ref: str) -> bool:
    completed = subprocess.run(
        ["git", "-C", str(repo_path), "rev-parse", "--verify", "--quiet", f"{git_ref}^{{commit}}"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    return completed.returncode == 0


def _list_known_git_refs(repo_path: Path) -> list[str]:
    output = _git_command_output(
        repo_path, "for-each-ref", "--format=%(refname:short)", "refs/heads", "refs/remotes", "refs/tags"
    )
    return [line.strip() for line in output.splitlines() if line.strip()]


def _resolve_remote_tracking_git_ref(repo_path: Path, git_ref: str) -> str | None:
    known_refs = _list_known_git_refs(repo_path)
    exact_origin_ref = f"origin/{git_ref}"
    if exact_origin_ref in known_refs and _git_ref_exists(repo_path, exact_origin_ref):
        return exact_origin_ref

    remote_matches = [
        ref for ref in known_refs if "/" in ref and ref.rsplit("/", 1)[-1] != git_ref and ref.endswith(f"/{git_ref}")
    ]
    if len(remote_matches) == 1 and _git_ref_exists(repo_path, remote_matches[0]):
        return remote_matches[0]
    return None


def _semver_alternate_git_refs(git_ref: str) -> tuple[str, ...]:
    normalized = git_ref.strip()
    match = re.fullmatch(r"v?(\d+\.\d+\.\d+)", normalized)
    if not match:
        return ()
    version = match.group(1)
    alternate = version if normalized.startswith("v") else f"v{version}"
    return () if alternate == normalized else (alternate,)


def _resolve_checkout_git_ref(repo_path: Path, git_ref: str) -> str:
    ref_options = list(dict.fromkeys((git_ref, *_semver_alternate_git_refs(git_ref))))
    for ref_option in ref_options:
        if _git_ref_exists(repo_path, ref_option):
            return ref_option
    for ref_option in ref_options:
        remote_tracking_ref = _resolve_remote_tracking_git_ref(repo_path, ref_option)
        if remote_tracking_ref:
            return remote_tracking_ref
    raise ValueError(_missing_git_ref_message(repo_path=repo_path, git_ref=git_ref))


def _missing_git_ref_message(*, repo_path: Path, git_ref: str) -> str:
    known_refs = _list_known_git_refs(repo_path)
    suggestions = difflib.get_close_matches(git_ref, known_refs, n=5, cutoff=0.35)
    suggestion_suffix = f" Similar refs: {', '.join(suggestions)}." if suggestions else ""
    return f"Git ref '{git_ref}' was not found in algorithm repo {repo_path}.{suggestion_suffix}"


def _resolve_execution_context(
    *,
    algo_repo_url: str | None,
    algorithm_name: str | None,
    encode_algorithm_name: str | None,
    source_path: str | None,
    predict_source_path: str | None,
    performance_source_path: str | None,
    encode_source_path: str | None,
    data_base_dir: str | None,
    scratch_dir: str | None,
) -> dict[str, str | None]:
    normalized_algorithm_name = (algorithm_name or "").strip() or None
    normalized_encode_algorithm_name = (encode_algorithm_name or "").strip() or None
    resolved = {
        "algo_repo_url": _normalize_context_value(algo_repo_url, allow_remote=True),
        "algorithm_name": normalized_algorithm_name,
        "encode_algorithm_name": normalized_encode_algorithm_name,
        "source_path": _normalize_context_value(source_path, allow_remote=True),
        "predict_source_path": _normalize_context_value(predict_source_path, allow_remote=True),
        "performance_source_path": _normalize_context_value(performance_source_path, allow_remote=True),
        "encode_source_path": _normalize_context_value(encode_source_path, allow_remote=True),
        "data_base_dir": _normalize_context_value(data_base_dir, allow_remote=False),
        "scratch_dir": _normalize_context_value(scratch_dir, allow_remote=False),
    }
    if not resolved["encode_algorithm_name"] and resolved["algorithm_name"]:
        resolved["encode_algorithm_name"] = resolved["algorithm_name"]
    if not resolved["predict_source_path"] and resolved["source_path"]:
        resolved["predict_source_path"] = resolved["source_path"]
    if not resolved["performance_source_path"] and resolved["source_path"]:
        resolved["performance_source_path"] = resolved["source_path"]
    if not resolved["encode_source_path"] and resolved["source_path"]:
        resolved["encode_source_path"] = resolved["source_path"]
    return resolved


def _merge_execution_context(
    existing: dict[str, Any] | None,
    incoming: dict[str, Any] | None,
) -> dict[str, str | None]:
    merged: dict[str, str | None] = {
        "algo_repo_url": None,
        "algorithm_name": None,
        "encode_algorithm_name": None,
        "source_path": None,
        "predict_source_path": None,
        "performance_source_path": None,
        "encode_source_path": None,
        "data_base_dir": None,
        "scratch_dir": None,
    }
    for source in (existing or {}, incoming or {}):
        for key in merged:
            value = source.get(key)
            if value:
                merged[key] = str(value)
    if not merged["encode_algorithm_name"] and merged["algorithm_name"]:
        merged["encode_algorithm_name"] = merged["algorithm_name"]
    if not merged["predict_source_path"] and merged["source_path"]:
        merged["predict_source_path"] = merged["source_path"]
    if not merged["performance_source_path"] and merged["source_path"]:
        merged["performance_source_path"] = merged["source_path"]
    if not merged["encode_source_path"] and merged["source_path"]:
        merged["encode_source_path"] = merged["source_path"]
    return merged


def _normalize_execution_context_for_output(execution_context: dict[str, Any] | None) -> dict[str, str | None]:
    context = execution_context or {}
    return {
        "algo_repo_url": _normalize_context_value(context.get("algo_repo_url"), allow_remote=True),
        "algorithm_name": _normalize_optional_string(context.get("algorithm_name")),
        "encode_algorithm_name": _normalize_optional_string(context.get("encode_algorithm_name")),
        "source_path": _normalize_context_value(context.get("source_path"), allow_remote=True),
        "predict_source_path": _normalize_context_value(context.get("predict_source_path"), allow_remote=True),
        "performance_source_path": _normalize_context_value(context.get("performance_source_path"), allow_remote=True),
        "encode_source_path": _normalize_context_value(context.get("encode_source_path"), allow_remote=True),
        "data_base_dir": _normalize_context_value(context.get("data_base_dir"), allow_remote=False),
        "scratch_dir": _normalize_context_value(context.get("scratch_dir"), allow_remote=False),
    }


def _normalize_stage_options_for_output(stage_options: dict[str, Any] | None) -> dict[str, Any]:
    normalized = copy.deepcopy(stage_options or {})
    system_performance = dict(normalized.get("system_performance") or {})
    if "sagemaker_config" in system_performance:
        system_performance["sagemaker_config"] = _normalize_context_value(
            system_performance.get("sagemaker_config"), allow_remote=False
        )
    if "s3_output_base" in system_performance:
        system_performance["s3_output_base"] = _normalize_context_value(
            system_performance.get("s3_output_base"), allow_remote=True
        )
    normalized["system_performance"] = system_performance
    backtest = _BacktestOptions.from_mapping(normalized.get("backtest")).to_dict()
    normalized["backtest"] = backtest
    return normalized


def _resolve_system_performance_options(
    *,
    config_options: dict[str, Any] | None,
    sagemaker_defaults: dict[str, Any] | None,
    runner: Any,
    sagemaker_job_prefix: Any,
    sagemaker_config: Any,
    role_arn: Any,
    assume_role_arn: Any,
    s3_output_base: Any,
    instance_type: Any,
    volume_gb: Any,
    max_runtime_seconds: Any,
    training_image: Any,
    samples: Any,
    sample_pool_size: Any,
    target_rps: Any,
    target_throughput_fraction: Any,
    workload_mode: Any,
    max_threads: Any,
    poll_seconds: Any,
    trials: Any,
) -> dict[str, Any]:
    config_options = dict(config_options or {})
    sagemaker_defaults = dict(sagemaker_defaults or {})
    template_default = sagemaker_defaults.get("sagemaker_config_template")
    sagemaker_config_value = _normalize_context_value(
        _value_or_config_default(sagemaker_config, config_options.get("sagemaker_config") or template_default),
        allow_remote=False,
    )
    s3_output_base_value = _normalize_optional_string(
        _value_or_config_default(s3_output_base, config_options.get("s3_output_base"))
    )
    sagemaker_job_prefix_value = _normalize_optional_string(
        _value_or_config_default(sagemaker_job_prefix, config_options.get("sagemaker_job_prefix"))
    )
    if not sagemaker_job_prefix_value:
        sagemaker_job_prefix_value = _derive_sagemaker_job_prefix(
            template_path=sagemaker_config_value,
            s3_output_base=s3_output_base_value,
        )

    return _SystemPerformanceOptions.from_mapping(
        {
            "runner": _value_or_config_default(runner, config_options.get("runner")),
            "sagemaker_job_prefix": sagemaker_job_prefix_value,
            "sagemaker_config": sagemaker_config_value,
            "role_arn": _value_or_config_default(role_arn, config_options.get("role_arn")),
            "assume_role_arn": _value_or_config_default(assume_role_arn, config_options.get("assume_role_arn")),
            "s3_output_base": s3_output_base_value,
            "instance_type": _value_or_config_default(instance_type, config_options.get("instance_type")),
            "volume_gb": _value_or_config_default(volume_gb, config_options.get("volume_gb")),
            "max_runtime_seconds": _value_or_config_default(
                max_runtime_seconds,
                config_options.get("max_runtime_seconds"),
            ),
            "training_image": _value_or_config_default(training_image, config_options.get("training_image")),
            "samples": _value_or_config_default(samples, config_options.get("samples")),
            "sample_pool_size": _value_or_config_default(sample_pool_size, config_options.get("sample_pool_size")),
            "target_rps": _value_or_config_default(target_rps, config_options.get("target_rps")),
            "target_throughput_fraction": _value_or_config_default(
                target_throughput_fraction,
                config_options.get("target_throughput_fraction"),
            ),
            "workload_mode": _value_or_config_default(workload_mode, config_options.get("workload_mode")),
            "max_threads": _value_or_config_default(max_threads, config_options.get("max_threads")),
            "poll_seconds": _value_or_config_default(
                poll_seconds,
                config_options.get("poll_seconds") or _DEFAULT_SAGEMAKER_POLL_SECONDS,
            ),
            "trials": _value_or_config_default(
                trials,
                config_options.get("trials") if "trials" in config_options else _DEFAULT_SYSTEM_PERFORMANCE_TRIALS,
            ),
        }
    ).to_dict()


def _resolve_backtest_options(
    *,
    config_options: dict[str, Any] | None,
    sagemaker_defaults: dict[str, Any] | None,
    runner: Any,
    algorithm_overrides: Any,
    no_performance_test: Any,
    performance_test_samples: Any,
    performance_test_sample_pool_size: Any,
    sagemaker_job_prefix: Any,
    sagemaker_config: Any,
    role_arn: Any,
    assume_role_arn: Any,
    s3_output_base: Any,
    instance_type: Any,
    volume_gb: Any,
    max_runtime_seconds: Any,
    training_image: Any,
    auto_attach_data_default_s3_base: Any,
    auto_attach_data_environment: Any,
    poll_seconds: Any,
) -> dict[str, Any]:
    config_options = dict(config_options or {})
    sagemaker_defaults = dict(sagemaker_defaults or {})
    template_default = sagemaker_defaults.get("sagemaker_config_template")
    sagemaker_config_value = _normalize_context_value(
        _value_or_config_default(sagemaker_config, config_options.get("sagemaker_config") or template_default),
        allow_remote=False,
    )
    s3_output_base_value = _normalize_optional_string(
        _value_or_config_default(s3_output_base, config_options.get("s3_output_base"))
    )
    sagemaker_job_prefix_value = _normalize_optional_string(
        _value_or_config_default(sagemaker_job_prefix, config_options.get("sagemaker_job_prefix"))
    )
    if not sagemaker_job_prefix_value:
        sagemaker_job_prefix_value = _derive_sagemaker_job_prefix(
            template_path=sagemaker_config_value,
            s3_output_base=s3_output_base_value,
        )

    return _BacktestOptions.from_mapping(
        {
            "runner": _value_or_config_default(runner, config_options.get("runner")),
            "algorithm_overrides": (
                algorithm_overrides if algorithm_overrides else config_options.get("algorithm_overrides")
            ),
            "no_performance_test": bool(no_performance_test or config_options.get("no_performance_test")),
            "performance_test_samples": _value_or_config_default(
                performance_test_samples,
                config_options.get("performance_test_samples"),
            ),
            "performance_test_sample_pool_size": _value_or_config_default(
                performance_test_sample_pool_size,
                config_options.get("performance_test_sample_pool_size"),
            ),
            "sagemaker_job_prefix": sagemaker_job_prefix_value,
            "sagemaker_config": sagemaker_config_value,
            "role_arn": _value_or_config_default(role_arn, config_options.get("role_arn")),
            "assume_role_arn": _value_or_config_default(assume_role_arn, config_options.get("assume_role_arn")),
            "s3_output_base": s3_output_base_value,
            "instance_type": _value_or_config_default(instance_type, config_options.get("instance_type")),
            "volume_gb": _value_or_config_default(volume_gb, config_options.get("volume_gb")),
            "max_runtime_seconds": _value_or_config_default(
                max_runtime_seconds,
                config_options.get("max_runtime_seconds"),
            ),
            "training_image": _value_or_config_default(training_image, config_options.get("training_image")),
            "auto_attach_data_default_s3_base": _value_or_config_default(
                auto_attach_data_default_s3_base,
                config_options.get("auto_attach_data_default_s3_base")
                or sagemaker_defaults.get("default_s3_data_base_dir"),
            ),
            "auto_attach_data_environment": _value_or_config_default(
                auto_attach_data_environment,
                config_options.get("auto_attach_data_environment") or "production",
            ),
            "poll_seconds": _value_or_config_default(
                poll_seconds,
                config_options.get("poll_seconds") or _DEFAULT_SAGEMAKER_POLL_SECONDS,
            ),
        }
    ).to_dict()


def _command_env() -> dict[str, str]:
    python_dir = _python_dir()
    existing_pythonpath = os.environ.get("PYTHONPATH")
    combined_pythonpath = str(python_dir)
    if existing_pythonpath:
        combined_pythonpath = f"{python_dir}{os.pathsep}{existing_pythonpath}"
    return dict(
        os.environ,
        PYTHONPATH=combined_pythonpath,
    )


def _run_logged_command(
    cmd: list[str],
    *,
    log_path: Path,
    cwd: Path | None = None,
    env: dict[str, str] | None = None,
) -> dict[str, Any]:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    tail: list[str] = []
    with open(log_path, "w", encoding="utf-8") as log_fp:
        process = subprocess.Popen(
            cmd,
            cwd=str(cwd) if cwd is not None else None,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        assert process.stdout is not None
        for line in process.stdout:
            log_fp.write(line)
            if len(tail) >= 200:
                tail.pop(0)
            tail.append(line.rstrip("\n"))
        return_code = process.wait()

    if return_code != 0:
        raise subprocess.CalledProcessError(
            returncode=return_code,
            cmd=cmd,
            output="\n".join(tail),
        )
    return {
        "command": cmd,
        "cwd": str(cwd) if cwd is not None else None,
        "log_path": str(log_path),
        "tail": tail,
    }


def _truncate_failure_summary(message: str, *, max_chars: int = _MAX_FAILURE_SUMMARY_CHARS) -> str:
    normalized = re.sub(r"\s+", " ", message).strip()
    if len(normalized) <= max_chars:
        return normalized
    return normalized[: max_chars - 3].rstrip() + "..."


def _truncate_debug_line(line: str, *, max_chars: int = _MAX_DEBUG_LOG_LINE_CHARS) -> str:
    normalized = line.rstrip("\n")
    if len(normalized) <= max_chars:
        return normalized
    return normalized[: max_chars - 3].rstrip() + "..."


def _tail_text_file(path: Path, *, max_lines: int = _MAX_DEBUG_LOG_LINES) -> list[str]:
    if not path.exists():
        return []
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return []
    return [_truncate_debug_line(line) for line in lines[-max_lines:]]


def _truncate_throwable_line(line: str, *, max_chars: int = _MAX_DEBUG_THROWABLE_LINE_CHARS) -> str:
    normalized = line.rstrip("\n")
    if len(normalized) <= max_chars:
        return normalized
    return normalized[: max_chars - 3].rstrip() + "..."


def _normalize_logged_failure_line(line: str) -> str:
    normalized = line.strip()
    if normalized.startswith("Caused by: "):
        normalized = normalized[len("Caused by: ") :]
    if "available keys:" in normalized:
        normalized = normalized.split("available keys:", 1)[0].rstrip(" .,:;")
        normalized = f"{normalized}. Available keys omitted."
    return _truncate_failure_summary(normalized)


def _strip_logged_throwable_header(raw_line: str) -> str:
    line = raw_line.rstrip("\n")
    if " ERROR - " in line:
        payload = line.split(" ERROR - ", 1)[1].strip()
        if payload:
            return payload
    return line.strip()


def _looks_like_logged_java_throwable_header(raw_line: str) -> bool:
    header = _strip_logged_throwable_header(raw_line)
    if header.startswith("Caused by: "):
        header = header[len("Caused by: ") :].strip()
    if header.startswith("Suppressed: "):
        header = header[len("Suppressed: ") :].strip()
    if not header:
        return False

    throwable_type = header.split(":", 1)[0].strip().split(" ", 1)[0]
    simple_name = throwable_type.rsplit(".", 1)[-1]
    if simple_name in {"Exception", "Error", "Throwable"}:
        return False
    return simple_name.endswith(("Exception", "Error", "Throwable"))


def _collect_logged_java_throwable_stacktrace(lines: list[str], *, start_index: int) -> str | None:
    collected: list[str] = []
    for index in range(start_index, len(lines)):
        raw_line = lines[index].rstrip("\n")
        if index == start_index:
            header = _strip_logged_throwable_header(raw_line)
            if not header:
                return None
            collected.append(_truncate_throwable_line(header))
            continue

        stripped = raw_line.strip()
        if not stripped:
            break

        is_stack_line = raw_line.startswith((" ", "\t")) or stripped.startswith(
            ("at ", "... ", "Caused by: ", "Suppressed: ")
        )
        if not is_stack_line:
            break
        collected.append(_truncate_throwable_line(raw_line.rstrip()))

        if len(collected) >= _MAX_DEBUG_THROWABLE_LINES:
            collected.append("... throwable stacktrace truncated ...")
            break

    if not collected:
        return None
    return "\n".join(collected)


def _extract_logged_throwable_stacktrace(log_path: Path) -> str | None:
    if not log_path.exists():
        return None
    try:
        lines = log_path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return None

    for index in range(len(lines) - 1, -1, -1):
        if _looks_like_logged_java_throwable_header(lines[index]):
            stacktrace = _collect_logged_java_throwable_stacktrace(lines, start_index=index)
            if stacktrace:
                return stacktrace
    return None


def _extract_logged_root_cause(log_path: Path) -> str | None:
    if not log_path.exists():
        return None
    try:
        lines = log_path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return None

    for raw_line in reversed(lines):
        line = raw_line.strip()
        if not line:
            continue
        if line.startswith("Caused by: "):
            return _normalize_logged_failure_line(line)

    for raw_line in reversed(lines):
        line = raw_line.strip()
        if not line:
            continue
        if " ERROR - " in line:
            payload = line.split(" ERROR - ", 1)[1].strip()
            if payload and payload != "Error while running:":
                return _normalize_logged_failure_line(payload)
        if "Exception:" in line or "Error:" in line:
            return _normalize_logged_failure_line(line)
    return None


def _metadata_path_from_hv_args(args: list[str]) -> Path | None:
    if "--metadata-path" not in args:
        return None
    index = args.index("--metadata-path")
    if index + 1 >= len(args):
        return None
    return Path(str(args[index + 1]))


def _build_hv_failure_details(*, args: list[str], log_path: Path, exc: subprocess.CalledProcessError) -> dict[str, Any]:
    command_name = str(args[0]) if args else "command"
    metadata_dir = _metadata_path_from_hv_args(args)
    log_options: list[tuple[str, Path]] = [("command", log_path)]
    if metadata_dir is not None:
        log_options = [
            ("hotvect_offline_utils", metadata_dir / "hotvect-offline-utils.log"),
            ("stdout_stderr", metadata_dir / "stdout-stderr.log"),
            ("hv", metadata_dir / "hv.log"),
            *log_options,
        ]

    root_cause = None
    throwable_stacktrace = None
    log_paths: dict[str, str] = {}
    log_tail: dict[str, list[str]] = {}
    for label, log_option in log_options:
        if log_option.exists():
            log_paths[label] = _normalize_local_debug_path(log_option) or str(log_option)
            tail = _tail_text_file(log_option)
            if tail:
                log_tail[label] = tail
            if root_cause is None:
                root_cause = _extract_logged_root_cause(log_option)
            if throwable_stacktrace is None:
                throwable_stacktrace = _extract_logged_throwable_stacktrace(log_option)

    if root_cause:
        summary = f"hv {command_name} failed: {root_cause}"
    else:
        tail = str(exc.output or exc.stderr or "").strip()
        if tail:
            summary = f"hv {command_name} failed: {_truncate_failure_summary(tail)}"
        else:
            summary = f"hv {command_name} failed: {exc}"

    return {
        "summary": summary,
        "command_name": command_name,
        "log_paths": log_paths,
        "log_tail": log_tail,
        "root_cause": root_cause,
        "throwable_stacktrace": throwable_stacktrace,
        "metadata_dir": _normalize_local_debug_path(metadata_dir) if metadata_dir is not None else None,
    }


def _summarize_hv_failure(*, args: list[str], log_path: Path, exc: subprocess.CalledProcessError) -> str:
    return str(_build_hv_failure_details(args=args, log_path=log_path, exc=exc)["summary"])


def _list_stage_files(stage_dir: Path | None, *, max_files: int = _MAX_DEBUG_STAGE_FILES) -> list[str]:
    if stage_dir is None or not stage_dir.exists():
        return []
    files = sorted(path for path in stage_dir.rglob("*") if path.is_file())
    return [str(path) for path in files[:max_files]]


def _load_recent_decision_events(path: Path, *, max_events: int = _MAX_DEBUG_DECISION_EVENTS) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return []
    events: list[dict[str, Any]] = []
    for raw_line in lines[-max_events:]:
        raw_line = raw_line.strip()
        if not raw_line:
            continue
        try:
            payload = json.loads(raw_line)
        except json.JSONDecodeError:
            continue
        if isinstance(payload, dict):
            events.append(payload)
    return events


def _failure_stage(stage_status: dict[str, str], current_stage: str | None) -> str | None:
    if current_stage and stage_status.get(current_stage) in {"failed", "blocked", "inconclusive"}:
        return current_stage
    for stage, state in stage_status.items():
        if state in {"failed", "blocked", "inconclusive"}:
            return stage
    return current_stage


def _build_stage_failure_debug(
    *,
    run_dir: Path,
    paths: dict[str, Path],
    stage: str,
    exc: Exception | None = None,
    stage_result: dict[str, Any] | None = None,
) -> dict[str, Any]:
    stage_dir = _stage_dir(run_dir, stage) if stage in _STAGE_TRACKS else None
    debug: dict[str, Any] = {
        "stage_dir": str(stage_dir) if stage_dir is not None else None,
        "stage_files": _list_stage_files(stage_dir),
        "recent_decisions": _load_recent_decision_events(paths["decisions"]),
    }

    if stage_result:
        artifact_paths = {
            key: value
            for key, value in stage_result.items()
            if isinstance(value, str) and (key.endswith("_path") or key.endswith("_dir"))
        }
        if artifact_paths:
            debug["artifact_paths"] = {
                key: _normalize_context_value(value, allow_remote=True) or value
                for key, value in artifact_paths.items()
            }

        nested_failure_debug: dict[str, Any] | None = None
        control_failure = (
            stage_result.get("control", {}).get("failure") if isinstance(stage_result.get("control"), dict) else None
        )
        if isinstance(control_failure, dict) and isinstance(control_failure.get("debug"), dict):
            nested_failure_debug = control_failure["debug"]
        if nested_failure_debug is None:
            for treatment in stage_result.get("treatments") or []:
                if not isinstance(treatment, dict):
                    continue
                failure = treatment.get("failure")
                if isinstance(failure, dict) and isinstance(failure.get("debug"), dict):
                    nested_failure_debug = failure["debug"]
                    break
        if nested_failure_debug is not None:
            debug.update(nested_failure_debug)

    if isinstance(exc, _HvCommandFailure):
        debug.update(exc.to_debug_dict())
        return debug

    if exc is not None:
        debug.update(
            {
                "kind": "python_exception",
                "exception_type": exc.__class__.__name__,
                "traceback": [
                    _truncate_debug_line(line, max_chars=800)
                    for line in traceback.format_exception(type(exc), exc, exc.__traceback__)[
                        -_MAX_DEBUG_TRACEBACK_LINES:
                    ]
                ],
            }
        )
    return debug


def _normalize_local_debug_path(value: str | Path | None) -> str | None:
    if value is None:
        return None
    return _normalize_context_value(str(value), allow_remote=False)


def _looks_like_local_path_string(value: str) -> bool:
    if not value:
        return False
    if value.startswith(("~", ".", os.sep)):
        return True
    if os.altsep and value.startswith(os.altsep):
        return True
    return os.sep in value or (os.altsep in value if os.altsep else False)


def _normalize_debug_cli_args(args: list[str]) -> list[str]:
    normalized: list[str] = []
    pending_flag: str | None = None
    for arg in args:
        token = str(arg)
        if pending_flag is not None:
            allow_remote = pending_flag in _DEBUG_REMOTE_OR_LOCAL_PATH_FLAGS
            normalized.append(_normalize_context_value(token, allow_remote=allow_remote) or token)
            pending_flag = None
            continue
        normalized.append(token)
        if token in _DEBUG_LOCAL_PATH_FLAGS or token in _DEBUG_REMOTE_OR_LOCAL_PATH_FLAGS:
            pending_flag = token
    return normalized


def _normalize_debug_wrapper_cmd(args: list[str]) -> list[str]:
    if not args:
        return []

    normalized = list(args)
    if _looks_like_local_path_string(normalized[0]):
        normalized[0] = _normalize_context_value(normalized[0], allow_remote=False) or normalized[0]
    if len(normalized) > 1 and _looks_like_local_path_string(normalized[1]):
        normalized[1] = _normalize_context_value(normalized[1], allow_remote=False) or normalized[1]
    if len(normalized) > 2:
        normalized[2:] = _normalize_debug_cli_args(normalized[2:])
    return normalized


def _build_debug_handoff(*, run_dir: Path, scenario: dict[str, Any], status: dict[str, Any]) -> dict[str, Any] | None:
    if status.get("state") not in {"failed", "blocked"}:
        return None

    stage_status = dict(status.get("stage_status") or {})
    stage_results = dict(status.get("stage_results") or {})
    execution_context = _normalize_execution_context_for_output(scenario.get("execution_context"))
    stage_options = _normalize_stage_options_for_output(scenario.get("stage_options"))
    parameter_source = _normalize_parameter_source(scenario.get("parameter_source"))
    parameter_sources = _normalize_parameter_sources(scenario.get("parameter_sources"))
    encode_algorithm_names = _normalize_encode_algorithm_names(scenario.get("encode_algorithm_names"))
    stage = _failure_stage(stage_status, status.get("current_stage"))
    if not stage:
        return None

    stage_result = dict(stage_results.get(stage) or {})
    stage_dir = _stage_dir(run_dir, stage) if stage in _STAGE_TRACKS else None
    files = {
        "plan": str(run_dir / _SCENARIO_FILE),
        "status": str(run_dir / _STATUS_FILE),
        "decisions": str(run_dir / _DECISIONS_FILE),
        "evidence": str(run_dir / _EVIDENCE_FILE),
    }

    suggested_files = list(files.values())
    if stage_dir is not None:
        suggested_files.append(str(stage_dir))
    stage_debug = stage_result.get("debug") if isinstance(stage_result.get("debug"), dict) else {}
    if not stage_debug:
        control_failure = (
            stage_result.get("control", {}).get("failure") if isinstance(stage_result.get("control"), dict) else None
        )
        if isinstance(control_failure, dict) and isinstance(control_failure.get("debug"), dict):
            stage_debug = control_failure["debug"]
        if not stage_debug:
            for treatment in stage_result.get("treatments") or []:
                if not isinstance(treatment, dict):
                    continue
                failure = treatment.get("failure")
                if isinstance(failure, dict) and isinstance(failure.get("debug"), dict):
                    stage_debug = failure["debug"]
                    break
    for path in (stage_debug.get("log_paths") or {}).values():
        if isinstance(path, str):
            suggested_files.append(path)

    deduped_suggested_files: list[str] = []
    seen: set[str] = set()
    for path in suggested_files:
        if path and path not in seen:
            seen.add(path)
            deduped_suggested_files.append(path)

    failure_summary = stage_result.get("summary") or next(iter(status.get("notes") or []), None)
    root_cause = stage_debug.get("root_cause") if isinstance(stage_debug, dict) else None
    if root_cause:
        root_cause = _truncate_failure_summary(str(root_cause))
        if failure_summary:
            if root_cause not in failure_summary:
                failure_summary = f"{failure_summary} Root cause: {root_cause}"
        else:
            failure_summary = root_cause

    handoff = {
        "run_id": scenario["run_id"],
        "state": status.get("state"),
        "failed_stage": stage,
        "failure_summary": failure_summary,
        "scenario_name": scenario.get("scenario"),
        "criteria": scenario.get("criteria"),
        "refs": scenario.get("refs"),
        "parameter_source": parameter_source,
        "parameter_sources": parameter_sources,
        "encode_algorithm_names": encode_algorithm_names,
        "execution_context": execution_context,
        "stage_options": stage_options,
        "requested_mode": status.get("requested_mode"),
        "requested_stages": status.get("requested_stages"),
        "target_stage": status.get("target_stage"),
        "notes": status.get("notes"),
        "run_files": files,
        "suggested_files": deduped_suggested_files,
        "stage_dir": str(stage_dir) if stage_dir is not None else None,
        "stage_files": _list_stage_files(stage_dir),
        "recent_decisions": _load_recent_decision_events(run_dir / _DECISIONS_FILE),
        "stage_result": stage_result,
    }
    if stage_debug:
        handoff["stage_debug"] = stage_debug
    return handoff


def _run_captured_command(
    cmd: list[str],
    *,
    log_path: Path,
    cwd: Path | None = None,
    env: dict[str, str] | None = None,
) -> str:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    completed = subprocess.run(
        cmd,
        cwd=str(cwd) if cwd is not None else None,
        env=env,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    rendered = completed.stdout
    if completed.stderr:
        rendered += completed.stderr
    log_path.write_text(rendered, encoding="utf-8")
    if completed.returncode != 0:
        raise subprocess.CalledProcessError(
            returncode=completed.returncode,
            cmd=cmd,
            output=completed.stdout,
            stderr=completed.stderr,
        )
    return completed.stdout.strip()


def _build_manifest_path(build_dir: Path) -> Path:
    return build_dir / "artifact.json"


def _safe_ref_slug(git_ref: str) -> str:
    return sanitize_path_component(git_ref, max_length=64)


def _select_runtime_jar(jar_options: list[Path], *, artifact_id: str, version: str, git_ref: str) -> Path:
    shaded_name = f"{artifact_id}-{version}-shaded.jar"
    shaded_options = [path for path in jar_options if path.name == shaded_name]
    if len(shaded_options) == 1:
        return shaded_options[0]
    if len(jar_options) == 1:
        return jar_options[0]
    raise ValueError(f"Expected exactly one runtime jar for {git_ref}, found {[str(path) for path in jar_options]}")


def _build_ref_artifact(*, run_dir: Path, algo_repo_url: str, git_ref: str) -> _BuiltRefArtifact:
    repo_slug = hashlib.sha256(algo_repo_url.encode("utf-8")).hexdigest()[:12]
    build_dir = run_dir / "tracks" / "preflight" / "builds" / f"{_safe_ref_slug(git_ref)}-{repo_slug}"
    manifest_path = _build_manifest_path(build_dir)
    if manifest_path.exists():
        payload = _read_json(manifest_path)
        jar_path = Path(payload["jar_path"])
        if jar_path.exists():
            return _BuiltRefArtifact(
                git_ref=str(payload["git_ref"]),
                resolved_git_ref=str(payload.get("resolved_git_ref") or payload["git_ref"]),
                artifact_name=str(payload["artifact_name"]),
                artifact_version=str(payload["artifact_version"]),
                git_commit=str(payload["git_commit"]),
                jar_path=jar_path,
                build_dir=build_dir,
                algo_repo_url=str(payload.get("algo_repo_url") or algo_repo_url),
            )

    if build_dir.exists():
        shutil.rmtree(build_dir)
    build_dir.mkdir(parents=True, exist_ok=True)
    source_dir = build_dir / "source"
    _run_logged_command(["git", "clone", algo_repo_url, str(source_dir)], log_path=build_dir / "git-clone.log")
    _run_logged_command(
        ["git", "fetch", "--all", "--tags"],
        cwd=source_dir,
        log_path=build_dir / "git-fetch.log",
    )
    resolved_git_ref = _resolve_checkout_git_ref(source_dir, git_ref)
    _run_logged_command(
        ["git", "checkout", resolved_git_ref],
        cwd=source_dir,
        log_path=build_dir / "git-checkout.log",
    )
    _run_logged_command(
        ["git", "clean", "-df"],
        cwd=source_dir,
        log_path=build_dir / "git-clean.log",
    )
    git_commit = _run_captured_command(
        ["git", "rev-parse", "HEAD"],
        cwd=source_dir,
        log_path=build_dir / "git-rev-parse.log",
    )

    pom_path = source_dir / "pom.xml"
    if not pom_path.exists():
        raise FileNotFoundError(f"Expected pom.xml in algorithm repo checkout: {pom_path}")
    artifact = parse_pom_xml(pom_path)

    _run_logged_command(
        ["mvn", "clean", "package", "-Dmaven.test.skip=true", "-B"],
        cwd=source_dir,
        log_path=build_dir / "maven-package.log",
    )

    jar_options = sorted(
        path
        for path in (source_dir / "target").glob(f"{artifact.artifact_id}-{artifact.version}*.jar")
        if path.is_file()
        and "-sources" not in path.name
        and "-javadoc" not in path.name
        and "original-" not in path.name
    )
    jar_option = _select_runtime_jar(
        jar_options,
        artifact_id=artifact.artifact_id,
        version=artifact.version,
        git_ref=git_ref,
    )

    artifact_dir = build_dir / "artifact"
    artifact_dir.mkdir(parents=True, exist_ok=True)
    jar_path = artifact_dir / jar_option.name
    shutil.copy2(jar_option, jar_path)

    manifest = {
        "git_ref": git_ref,
        "resolved_git_ref": resolved_git_ref,
        "artifact_name": artifact.artifact_id,
        "artifact_version": artifact.version,
        "git_commit": git_commit,
        "algo_repo_url": algo_repo_url,
        "jar_path": str(jar_path),
        "build_dir": str(build_dir),
    }
    _write_json(manifest_path, manifest)
    return _BuiltRefArtifact(
        git_ref=git_ref,
        resolved_git_ref=resolved_git_ref,
        artifact_name=artifact.artifact_id,
        artifact_version=artifact.version,
        git_commit=git_commit,
        jar_path=jar_path,
        build_dir=build_dir,
        algo_repo_url=algo_repo_url,
    )


def _read_algorithm_definition_from_artifact(
    *,
    artifact: _BuiltRefArtifact,
    algorithm_name: str,
    cache: dict[tuple[str, str], dict[str, Any]],
) -> dict[str, Any]:
    cache_key = (str(artifact.jar_path), algorithm_name)
    if cache_key not in cache:
        cache[cache_key] = read_algorithm_definition_from_jar(
            algorithm_name=algorithm_name,
            algorithm_jar_path=artifact.jar_path,
        )
    return cache[cache_key]


def _encode_default_output_ordering(algorithm_definition: dict[str, Any]) -> str:
    train_decoder_parameters = algorithm_definition.get("train_decoder_parameters", {})
    ordering = str(train_decoder_parameters.get("ordering", ""))
    return "ordered" if ordering.lower() == "ordered" else "unordered"


def _algorithm_dependency_names(algorithm_definition: dict[str, Any]) -> list[str]:
    dependencies = algorithm_definition.get("dependencies")
    if dependencies is None:
        return []
    if isinstance(dependencies, list):
        return [str(item) for item in dependencies if item]
    if isinstance(dependencies, dict):
        return [str(name) for name in dependencies.keys()]
    raise ValueError(f"Unsupported dependencies type in algorithm definition: {type(dependencies).__name__}")


def _looks_like_trainable_algorithm_definition(algorithm_definition: dict[str, Any]) -> bool:
    training_markers = (
        "training_command",
        "train_data_spec",
        "number_of_training_days",
        "catboost_options",
        "vw_options",
    )
    return any(algorithm_definition.get(marker) is not None for marker in training_markers)


def _test_data_s3_base_uri_from_algorithm_definition(algorithm_definition: dict[str, Any]) -> str | None:
    test_data_spec = algorithm_definition.get("test_data_spec")
    if isinstance(test_data_spec, dict):
        s3_uri = test_data_spec.get("s3_uri")
        if isinstance(s3_uri, str) and s3_uri.strip():
            return s3_uri.strip()
        if isinstance(s3_uri, dict):
            for key in ("production", "prod", "default", "test"):
                value = s3_uri.get(key)
                if isinstance(value, str) and value.strip():
                    return value.strip()

    test_data_prefix = algorithm_definition.get("test_data_prefix")
    if not isinstance(test_data_prefix, str) or not test_data_prefix.strip():
        return None

    normalized_prefix = test_data_prefix.strip().rstrip("/")
    if normalized_prefix.startswith("s3://"):
        return normalized_prefix

    default_s3_data_base_dir = hv_config.load_sagemaker_defaults().get("default_s3_data_base_dir")
    if not isinstance(default_s3_data_base_dir, str) or not default_s3_data_base_dir.strip():
        return None

    return _s3_uri_join(default_s3_data_base_dir.strip().rstrip("/"), normalized_prefix.lstrip("/"))


def _discover_latest_contiguous_test_data_date(*, data_base_uri: str, days: int) -> str:
    parsed = urlparse(data_base_uri)
    if parsed.scheme != "s3" or not parsed.netloc:
        raise ValueError(f"Control test-data source must be an S3 URI, got {data_base_uri!r}.")

    base_prefix = parsed.path.lstrip("/").rstrip("/")
    dated_source_match = re.search(r"(?:^|/)dt=(\d{4}-\d{2}-\d{2})$", base_prefix)
    if dated_source_match:
        available_dates = {date.fromisoformat(dated_source_match.group(1))}
    else:
        prefix = f"{base_prefix}/" if base_prefix else ""
        paginator = boto3.client("s3").get_paginator("list_objects_v2")
        available_dates: set[date] = set()
        for page in paginator.paginate(Bucket=parsed.netloc, Prefix=prefix, Delimiter="/"):
            for item in page.get("CommonPrefixes", []) or []:
                child_prefix = str(item.get("Prefix") or "")
                if not child_prefix.startswith(prefix):
                    continue
                relative_prefix = child_prefix[len(prefix) :].strip("/")
                partition_match = re.fullmatch(r"dt=(\d{4}-\d{2}-\d{2})", relative_prefix)
                if partition_match:
                    available_dates.add(date.fromisoformat(partition_match.group(1)))

    for end_date in sorted(available_dates, reverse=True):
        window = {end_date - timedelta(days=offset) for offset in range(days)}
        if window.issubset(available_dates):
            newer_dates = sorted(dt for dt in available_dates if dt > end_date)
            if newer_dates:
                missing = sorted(
                    {end_date + timedelta(days=offset) for offset in range(1, (newer_dates[-1] - end_date).days + 1)}
                    - available_dates
                )
                raise ValueError(
                    f"Refusing to guess --last-test-date from {data_base_uri}: the newest complete {days}-day "
                    f"window ends {end_date.isoformat()}, but newer test data exists "
                    f"({', '.join(dt.isoformat() for dt in newer_dates)}). "
                    f"Missing dates: {', '.join(dt.isoformat() for dt in missing)}. "
                    "Pass --last-test-date explicitly to choose a window."
                )
            return end_date.isoformat()

    available = ", ".join(dt.isoformat() for dt in sorted(available_dates)) or "none"
    raise ValueError(
        f"Could not determine --last-test-date from {data_base_uri}: no contiguous {days}-day dt=YYYY-MM-DD "
        f"window exists (available dates: {available}). Pass --last-test-date explicitly."
    )


def _resolve_automatic_offline_context(
    *, root_definition: dict[str, Any], encode_definition: dict[str, Any], backtest_days: int
) -> dict[str, Any]:
    data_base_uri = _test_data_s3_base_uri_from_algorithm_definition(
        root_definition
    ) or _test_data_s3_base_uri_from_algorithm_definition(encode_definition)
    if not data_base_uri:
        raise ValueError(
            "Cannot determine --last-test-date because the control algorithm does not define an S3 test-data source. "
            "Pass --last-test-date explicitly."
        )
    return _resolve_offline_context(
        last_test_date=_discover_latest_contiguous_test_data_date(
            data_base_uri=data_base_uri,
            days=backtest_days,
        ),
        backtest_days=backtest_days,
    )


def _dated_test_data_s3_uri_from_algorithm_definition(
    algorithm_definition: dict[str, Any],
    *,
    last_test_date: str,
) -> str | None:
    if not last_test_date:
        return None

    base_uri = _test_data_s3_base_uri_from_algorithm_definition(algorithm_definition)
    if not base_uri:
        return None

    normalized = base_uri.rstrip("/")
    if re.search(r"/dt=\d{4}-\d{2}-\d{2}$", normalized):
        return normalized + "/"
    return _s3_uri_join(normalized, f"dt={last_test_date}") + "/"


def _infer_encode_algorithm_name(
    *,
    control_artifact: _BuiltRefArtifact,
    root_definition: dict[str, Any],
    definition_cache: dict[tuple[str, str], dict[str, Any]],
) -> str:
    root_algorithm_name = str(root_definition.get("algorithm_name") or control_artifact.artifact_name)
    dependency_names = _algorithm_dependency_names(root_definition)
    if not dependency_names:
        return root_algorithm_name
    if len(dependency_names) == 1:
        return dependency_names[0]

    trainable_dependencies: list[str] = []
    for dependency_name in dependency_names:
        try:
            dependency_definition = _read_algorithm_definition_from_artifact(
                artifact=control_artifact,
                algorithm_name=dependency_name,
                cache=definition_cache,
            )
        except MalformedAlgorithmException:
            continue
        if _looks_like_trainable_algorithm_definition(dependency_definition):
            trainable_dependencies.append(dependency_name)

    if len(trainable_dependencies) == 1:
        return trainable_dependencies[0]

    raise ValueError(
        "Could not infer encode algorithm name from the control artifact. "
        f"Root algorithm '{root_algorithm_name}' declares dependencies {dependency_names}, "
        f"with trainable dependencies {trainable_dependencies or 'none'}. "
        "Set qa.run.execution.encode_algorithm_name or pass --encode-algorithm-name explicitly."
    )


def _validate_built_artifact_version_uniqueness(
    *,
    control_artifact: _BuiltRefArtifact,
    treatment_artifacts: list[_BuiltRefArtifact],
) -> list[tuple[str, str, str]]:
    seen_by_version: dict[str, _BuiltRefArtifact] = {control_artifact.artifact_version: control_artifact}
    collisions: list[tuple[str, str, str]] = []
    for artifact in treatment_artifacts:
        existing = seen_by_version.get(artifact.artifact_version)
        if existing is not None:
            collisions.append((existing.git_ref, artifact.git_ref, artifact.artifact_version))
            continue
        seen_by_version[artifact.artifact_version] = artifact

    return collisions


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _validate_distinct_built_artifacts(artifacts: list[_BuiltRefArtifact]) -> None:
    seen_commits: dict[str, _BuiltRefArtifact] = {}
    seen_digests: dict[str, _BuiltRefArtifact] = {}
    for artifact in artifacts:
        matching_commit = seen_commits.get(artifact.git_commit)
        if matching_commit is not None:
            raise ValueError(
                "QA control and treatment must resolve to different commits: "
                f"{matching_commit.git_ref!r} and {artifact.git_ref!r} both resolve to {artifact.git_commit}."
            )
        seen_commits[artifact.git_commit] = artifact

        jar_digest = _sha256_file(artifact.jar_path)
        matching_artifact = seen_digests.get(jar_digest)
        if matching_artifact is not None:
            raise ValueError(
                "QA control and treatment must contain different artifacts: "
                f"{matching_artifact.git_ref!r} and {artifact.git_ref!r} have JAR SHA-256 {jar_digest}."
            )
        seen_digests[jar_digest] = artifact


def _derive_qa_run_execution_context(
    *,
    run_dir: Path,
    scenario: dict[str, Any],
    artifact_cache: dict[str, _BuiltRefArtifact],
) -> bool:
    execution_context = dict(scenario.get("execution_context") or {})
    initial_execution_context = dict(execution_context)

    control_ref = str(scenario["refs"]["control"]["git_ref"])
    treatment_refs = [str(item["git_ref"]) for item in scenario["refs"]["treatments"]]
    updates: dict[str, str | None] = {}
    offline_context_changed = False

    if not _has_repository_for_every_ref(scenario):
        return False

    control_artifact = _ensure_ref_artifact(
        cache=artifact_cache,
        run_dir=run_dir,
        scenario=scenario,
        git_ref=control_ref,
        role="control",
    )
    treatment_artifacts = [
        _ensure_ref_artifact(
            cache=artifact_cache,
            run_dir=run_dir,
            scenario=scenario,
            git_ref=git_ref,
            role="treatment",
        )
        for git_ref in treatment_refs
    ]
    _validate_distinct_built_artifacts([control_artifact, *treatment_artifacts])
    version_collisions = _validate_built_artifact_version_uniqueness(
        control_artifact=control_artifact,
        treatment_artifacts=treatment_artifacts,
    )
    if version_collisions:
        updates["backtest_output_mode"] = "isolated_ref_outputs"

    definition_cache: dict[tuple[str, str], dict[str, Any]] = {}
    root_definition = _read_algorithm_definition_from_artifact(
        artifact=control_artifact,
        algorithm_name=control_artifact.artifact_name,
        cache=definition_cache,
    )
    root_algorithm_name = str(root_definition.get("algorithm_name") or control_artifact.artifact_name)

    if not execution_context.get("algorithm_name"):
        updates["algorithm_name"] = root_algorithm_name

    encode_algorithm_name = execution_context.get("encode_algorithm_name")
    if not encode_algorithm_name:
        encode_algorithm_name = _infer_encode_algorithm_name(
            control_artifact=control_artifact,
            root_definition=root_definition,
            definition_cache=definition_cache,
        )
        updates["encode_algorithm_name"] = encode_algorithm_name

    encode_definition = root_definition
    if encode_algorithm_name and encode_algorithm_name != root_algorithm_name:
        encode_definition = _read_algorithm_definition_from_artifact(
            artifact=control_artifact,
            algorithm_name=encode_algorithm_name,
            cache=definition_cache,
        )

    offline_context = dict(scenario["offline_context"])
    if not offline_context.get("last_test_date"):
        scenario["offline_context"] = _resolve_automatic_offline_context(
            root_definition=root_definition,
            encode_definition=encode_definition,
            backtest_days=int(offline_context["backtest_days"]),
        )
        offline_context_changed = True
    if scenario.get("control_resolution") is not None and scenario.get("prod_default_parameters_by_date") is None:
        (
            scenario["offline_context"],
            scenario["prod_default_parameters_by_date"],
        ) = _resolve_prod_default_date_matched_parameters(
            offline_context=dict(scenario["offline_context"]),
            control_resolution=dict(scenario["control_resolution"]),
        )
        offline_context_changed = True
    last_test_date = str(scenario["offline_context"]["last_test_date"])

    if not execution_context.get("predict_source_path"):
        updates["predict_source_path"] = _dated_test_data_s3_uri_from_algorithm_definition(
            root_definition, last_test_date=last_test_date
        ) or _dated_test_data_s3_uri_from_algorithm_definition(encode_definition, last_test_date=last_test_date)
    performance_source_paths = _normalize_performance_source_paths(scenario.get("performance_source_paths"))
    if not performance_source_paths and not execution_context.get("performance_source_path"):
        updates["performance_source_path"] = _dated_test_data_s3_uri_from_algorithm_definition(
            root_definition, last_test_date=last_test_date
        ) or _dated_test_data_s3_uri_from_algorithm_definition(encode_definition, last_test_date=last_test_date)
    if not execution_context.get("encode_source_path"):
        updates["encode_source_path"] = _dated_test_data_s3_uri_from_algorithm_definition(
            encode_definition, last_test_date=last_test_date
        ) or _dated_test_data_s3_uri_from_algorithm_definition(root_definition, last_test_date=last_test_date)

    merged_execution_context = _merge_execution_context(execution_context, updates)
    if performance_source_paths:
        merged_execution_context["performance_source_path"] = None
    changed = merged_execution_context != initial_execution_context
    scenario["execution_context"] = merged_execution_context

    if not scenario["execution_context"].get("source_path"):
        source_options = [
            scenario["execution_context"].get("predict_source_path"),
            scenario["execution_context"].get("encode_source_path"),
        ]
        if not performance_source_paths:
            source_options.append(scenario["execution_context"].get("performance_source_path"))
        resolved_sources = [source for source in source_options if source]
        if resolved_sources and len(set(resolved_sources)) == 1:
            scenario["execution_context"]["source_path"] = resolved_sources[0]
            changed = True

    refs_changed = False
    if scenario["refs"]["control"].get("resolved_git_ref") != control_artifact.resolved_git_ref:
        scenario["refs"]["control"]["resolved_git_ref"] = control_artifact.resolved_git_ref
        refs_changed = True
    if scenario["refs"]["control"].get("resolved_algorithm_version") != control_artifact.artifact_version:
        scenario["refs"]["control"]["resolved_algorithm_version"] = control_artifact.artifact_version
        refs_changed = True
    if scenario["refs"]["control"].get("resolved_git_commit") != control_artifact.git_commit:
        scenario["refs"]["control"]["resolved_git_commit"] = control_artifact.git_commit
        refs_changed = True
    control_jar_sha256 = _sha256_file(control_artifact.jar_path)
    if scenario["refs"]["control"].get("jar_sha256") != control_jar_sha256:
        scenario["refs"]["control"]["jar_sha256"] = control_jar_sha256
        refs_changed = True
    for treatment_payload, treatment_artifact in zip(scenario["refs"]["treatments"], treatment_artifacts):
        if treatment_payload.get("resolved_git_ref") != treatment_artifact.resolved_git_ref:
            treatment_payload["resolved_git_ref"] = treatment_artifact.resolved_git_ref
            refs_changed = True
        if treatment_payload.get("resolved_algorithm_version") != treatment_artifact.artifact_version:
            treatment_payload["resolved_algorithm_version"] = treatment_artifact.artifact_version
            refs_changed = True
        if treatment_payload.get("resolved_git_commit") != treatment_artifact.git_commit:
            treatment_payload["resolved_git_commit"] = treatment_artifact.git_commit
            refs_changed = True
        treatment_jar_sha256 = _sha256_file(treatment_artifact.jar_path)
        if treatment_payload.get("jar_sha256") != treatment_jar_sha256:
            treatment_payload["jar_sha256"] = treatment_jar_sha256
            refs_changed = True

    return changed or refs_changed or offline_context_changed


def _derive_shared_parameter_source(
    *,
    run_dir: Path,
    scenario: dict[str, Any],
    artifact_cache: dict[str, _BuiltRefArtifact],
) -> bool:
    if _has_stage_parameter_sources(scenario):
        return False

    execution_context = dict(scenario.get("execution_context") or {})
    if not _has_repository_for_every_ref(scenario):
        return False

    control_ref = str(scenario["refs"]["control"]["git_ref"])
    control_artifact = _ensure_ref_artifact(
        cache=artifact_cache,
        run_dir=run_dir,
        scenario=scenario,
        git_ref=control_ref,
        role="control",
    )
    algorithm_name = str(execution_context.get("algorithm_name") or control_artifact.artifact_name)
    algorithm_version = control_artifact.artifact_version

    production_parameters = scenario.get("prod_default_parameters_by_date")
    if production_parameters:
        last_test_date = str(scenario["offline_context"]["last_test_date"])
        parameter = dict(production_parameters[last_test_date])
        scenario["parameter_source"] = str(parameter["source"])
        scenario["parameter_source_resolution"] = {
            "mode": "date_matched_production_parameter",
            "source": "experiment_management",
            "algorithm_name": parameter["algorithm_name"],
            "algorithm_version": parameter["algorithm_version"],
            "algorithm_parameter_id": parameter["algorithm_parameter_id"],
            "test_date": last_test_date,
        }
        return True

    client = create_client_from_hotvect_config()
    parameter = client.get_latest_algorithm_parameter(algorithm_name, algorithm_version)
    if parameter is None:
        raise ValueError(f"Experiment-management has no latest parameter for {algorithm_name}@{algorithm_version}.")

    parameter_source = _normalize_parameter_source(getattr(parameter, "absolute_s3_path", None))
    if not parameter_source:
        raise ValueError(
            f"Experiment-management latest parameter for {algorithm_name}@{algorithm_version} has no S3 path."
        )

    scenario["parameter_source"] = parameter_source
    scenario["parameter_source_resolution"] = {
        "mode": "control_latest_parameter",
        "source": "experiment_management",
        "algorithm_name": algorithm_name,
        "algorithm_version": algorithm_version,
        "algorithm_parameter_id": getattr(parameter, "algorithm_parameter_id", None),
    }
    return True


def _localize_optional_content(source: str | None, *, cache_dir: Path) -> str | None:
    if not source:
        return None
    local_path = _retry_after_refreshing_expired_aws_credentials(
        reason=f"localizing QA run content {source}",
        operation=lambda: as_locally_available_content(source, str(cache_dir)),
    )
    if not local_path:
        raise FileNotFoundError(f"Could not resolve content locally from: {source}")
    return str(Path(local_path).expanduser().resolve())


def _looks_like_jsonl_data_key(key: str) -> bool:
    name = Path(key).name
    if not name or name.startswith(("_", ".")) or key.endswith("/"):
        return False
    return name.endswith((".json", ".json.gz", ".jsonl", ".jsonl.gz"))


def _s3_sample_cache_dir(cache_dir: Path, *, source: str, sample_count: int) -> Path:
    digest = hashlib.sha256(f"{source}\n{sample_count}".encode("utf-8")).hexdigest()[:16]
    return cache_dir / "sampled-jsonl" / digest


def _iter_s3_text_lines(s3_client: Any, *, bucket: str, key: str):
    response = s3_client.get_object(Bucket=bucket, Key=key)
    body = response["Body"]
    try:
        if key.endswith(".gz"):
            with gzip.GzipFile(fileobj=body) as gzip_file:
                with io.TextIOWrapper(gzip_file, encoding="utf-8", errors="replace") as text_file:
                    for line in text_file:
                        yield line
        elif hasattr(body, "iter_lines"):
            for raw_line in body.iter_lines():
                if not raw_line:
                    continue
                line = raw_line.decode("utf-8", errors="replace") if isinstance(raw_line, bytes) else str(raw_line)
                yield line if line.endswith("\n") else f"{line}\n"
        else:
            with io.TextIOWrapper(body, encoding="utf-8", errors="replace") as text_file:
                for line in text_file:
                    yield line
    finally:
        close = getattr(body, "close", None)
        if callable(close):
            close()


def _list_s3_sample_source_keys(s3_client: Any, *, bucket: str, key_prefix: str) -> list[str]:
    if key_prefix and not key_prefix.endswith("/") and _looks_like_jsonl_data_key(key_prefix):
        try:
            s3_client.head_object(Bucket=bucket, Key=key_prefix)
            return [key_prefix]
        except ClientError as exc:
            if _client_error_code(exc) not in {"404", "NoSuchKey", "NotFound"}:
                raise

    normalized_prefix = key_prefix if key_prefix.endswith("/") else f"{key_prefix}/"
    paginator = s3_client.get_paginator("list_objects_v2")
    keys: list[str] = []
    for page in paginator.paginate(Bucket=bucket, Prefix=normalized_prefix):
        for item in page.get("Contents", []) or []:
            key = str(item.get("Key") or "")
            if int(item.get("Size") or 0) <= 0:
                continue
            if _looks_like_jsonl_data_key(key):
                keys.append(key)
    return sorted(keys)


def _sample_s3_jsonl_source(source: str, *, cache_dir: Path, sample_count: int) -> str | None:
    parsed = urlparse(source)
    bucket = parsed.netloc
    key_prefix = parsed.path.lstrip("/")
    if not bucket or not key_prefix:
        raise ValueError(f"Expected s3://bucket/key URI, got: {source}")

    sample_dir = _s3_sample_cache_dir(cache_dir, source=source, sample_count=sample_count)
    sample_path = sample_dir / "part-00000.json.gz"
    metadata_path = sample_dir.parent / f"{sample_dir.name}.metadata.json"
    if sample_path.exists() and metadata_path.exists():
        try:
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        except Exception:
            metadata = {}
        if (
            metadata.get("source") == source
            and metadata.get("sample_count") == sample_count
            and int(metadata.get("record_count") or 0) > 0
        ):
            return str(sample_dir.resolve())

    s3_client = boto3.client("s3")
    keys = _list_s3_sample_source_keys(s3_client, bucket=bucket, key_prefix=key_prefix)
    if not keys:
        return None

    if sample_dir.exists():
        shutil.rmtree(sample_dir)
    sample_dir.mkdir(parents=True, exist_ok=True)
    tmp_path = sample_path.with_name(f"{sample_path.name}.tmp")

    record_count = 0
    read_keys: list[str] = []
    with gzip.open(tmp_path, "wt", encoding="utf-8") as output:
        for key in keys:
            read_keys.append(key)
            for line in _iter_s3_text_lines(s3_client, bucket=bucket, key=key):
                if not line.strip():
                    continue
                output.write(line if line.endswith("\n") else f"{line}\n")
                record_count += 1
                if record_count >= sample_count:
                    break
            if record_count >= sample_count:
                break

    if record_count == 0:
        tmp_path.unlink(missing_ok=True)
        return None

    os.replace(tmp_path, sample_path)
    metadata = {
        "schema": 1,
        "source": source,
        "bucket": bucket,
        "prefix": key_prefix,
        "sample_count": sample_count,
        "record_count": record_count,
        "listed_object_count": len(keys),
        "read_object_count": len(read_keys),
        "first_read_key": read_keys[0] if read_keys else None,
        "last_read_key": read_keys[-1] if read_keys else None,
    }
    metadata_path.write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return str(sample_dir.resolve())


def _localize_sampled_input_content(source: str | None, *, cache_dir: Path, sample_count: int) -> str | None:
    if not source:
        return None
    if not _is_s3_uri(source):
        return _localize_optional_content(source, cache_dir=cache_dir)
    local_path = _retry_after_refreshing_expired_aws_credentials(
        reason=f"sampling QA run parity input {source}",
        operation=lambda: _sample_s3_jsonl_source(source, cache_dir=cache_dir, sample_count=sample_count),
    )
    if not local_path:
        raise FileNotFoundError(f"Could not resolve sampled parity input locally from: {source}")
    return str(Path(local_path).expanduser().resolve())


def _inspect_parameter_source(
    *,
    parameter_path: Path,
    expected_algorithm_name: str,
) -> _ParameterSourceInspection:
    try:
        with zipfile.ZipFile(parameter_path) as zip_file:
            entries: list[tuple[str, dict[str, Any], str, str, str | None]] = []
            for entry_name in zip_file.namelist():
                if not entry_name.endswith("algorithm-parameters.json"):
                    continue
                try:
                    payload = json.loads(zip_file.read(entry_name))
                except Exception as exc:
                    raise ValueError(
                        f"Parameter source '{parameter_path}' contains unreadable metadata entry '{entry_name}'."
                    ) from exc
                algorithm_name = str(payload.get("algorithm_name") or "").strip()
                algorithm_version = str(payload.get("algorithm_version") or "").strip()
                parameter_id = str(payload.get("parameter_id") or "").strip() or None
                entries.append((entry_name, payload, algorithm_name, algorithm_version, parameter_id))
    except zipfile.BadZipFile as exc:
        raise ValueError(f"Parameter source '{parameter_path}' is not a readable zip archive.") from exc

    if not entries:
        raise ValueError(f"Parameter source '{parameter_path}' does not contain any algorithm-parameters.json entries.")

    selected = next((item for item in entries if item[2] == expected_algorithm_name), None)
    if selected is None:
        available = ", ".join(sorted(item[2] or item[0] for item in entries))
        raise ValueError(
            f"Parameter source '{parameter_path}' does not contain metadata for algorithm "
            f"'{expected_algorithm_name}'. Available entries: {available}."
        )

    _, _, algorithm_name, algorithm_version, parameter_id = selected
    if not algorithm_version:
        raise ValueError(
            f"Parameter source '{parameter_path}' is missing algorithm_version for '{expected_algorithm_name}'."
        )

    return _ParameterSourceInspection(
        local_path=str(parameter_path),
        algorithm_name=algorithm_name,
        algorithm_version=algorithm_version,
        parameter_id=parameter_id,
    )


def _resolve_validated_parameter_source(
    *,
    run_dir: Path,
    scenario: dict[str, Any],
    expected_algorithm_name: str,
    expected_algorithm_version: str,
) -> tuple[str, _ParameterSourceInspection]:
    parameter_source = _normalize_parameter_source(scenario.get("parameter_source"))
    if not parameter_source:
        raise ValueError("Missing parameter_source for fixed-parameter stage.")

    cache_dir = run_dir / "tracks" / "parameter_artifact" / "cache"
    local_parameter_path = _localize_optional_content(parameter_source, cache_dir=cache_dir)
    if not local_parameter_path:
        raise FileNotFoundError(f"Could not resolve parameter source locally from: {parameter_source}")

    inspection = _inspect_parameter_source(
        parameter_path=Path(local_parameter_path),
        expected_algorithm_name=expected_algorithm_name,
    )
    if inspection.algorithm_version != expected_algorithm_version:
        control_ref = str((scenario.get("refs") or {}).get("control", {}).get("git_ref") or "<control>")
        raise ValueError(
            f"Parameter source '{parameter_source}' packages {inspection.algorithm_name} version "
            f"{inspection.algorithm_version}, but control ref '{control_ref}' expects {expected_algorithm_version}. "
            "Use a parameters zip built for the control version."
        )

    return local_parameter_path, inspection


def _parameter_source_for_ref(
    *,
    scenario: dict[str, Any],
    git_ref: str,
    role: str,
) -> str:
    per_ref = _normalize_parameter_sources(scenario.get("parameter_sources"))
    if not per_ref:
        shared = _normalize_parameter_source(scenario.get("parameter_source"))
        if not shared:
            raise ValueError("Missing parameter_source for fixed-parameter stage.")
        return shared
    if role == "control":
        control = per_ref["control"]
        if control.get("git_ref") and control["git_ref"] != git_ref:
            raise ValueError(
                f"Per-ref parameter source control ref mismatch: expected {git_ref!r}, " f"got {control['git_ref']!r}."
            )
        return str(control["source"])
    for treatment in per_ref["treatments"]:
        if treatment["git_ref"] == git_ref:
            return str(treatment["source"])
    raise ValueError(f"Missing per-ref parameter source for treatment {git_ref!r}.")


def _performance_source_for_ref(
    *,
    scenario: dict[str, Any],
    git_ref: str,
    role: str,
) -> str:
    per_ref = _normalize_performance_source_paths(scenario.get("performance_source_paths"))
    if not per_ref:
        shared = _normalize_performance_source_path(
            (scenario.get("execution_context") or {}).get("performance_source_path")
        )
        if not shared:
            raise ValueError("Missing performance_source_path for system_performance stage.")
        return shared
    if role == "control":
        control = per_ref["control"]
        if control.get("git_ref") and control["git_ref"] != git_ref:
            raise ValueError(
                f"Per-ref performance source control ref mismatch: expected {git_ref!r}, "
                f"got {control['git_ref']!r}."
            )
        return str(control["source"])
    for treatment in per_ref["treatments"]:
        if treatment["git_ref"] == git_ref:
            return str(treatment["source"])
    raise ValueError(f"Missing per-ref performance source for treatment {git_ref!r}.")


def _encode_algorithm_name_for_ref(
    *,
    scenario: dict[str, Any],
    git_ref: str,
    role: str,
) -> str:
    per_ref = _normalize_encode_algorithm_names(scenario.get("encode_algorithm_names"))
    if not per_ref:
        algorithm_name = _normalize_encode_algorithm_name(
            (scenario.get("execution_context") or {}).get("encode_algorithm_name")
        )
        if not algorithm_name:
            raise ValueError("Missing encode_algorithm_name for encode_parity stage.")
        return algorithm_name
    if role == "control":
        control = per_ref["control"]
        if control.get("git_ref") and control["git_ref"] != git_ref:
            raise ValueError(
                f"Per-ref encode algorithm control ref mismatch: expected {git_ref!r}, " f"got {control['git_ref']!r}."
            )
        return str(control["algorithm_name"])
    for treatment in per_ref["treatments"]:
        if treatment["git_ref"] == git_ref:
            return str(treatment["algorithm_name"])
    raise ValueError(f"Missing per-ref encode algorithm name for treatment {git_ref!r}.")


def _resolve_validated_parameter_source_for_ref(
    *,
    run_dir: Path,
    scenario: dict[str, Any],
    git_ref: str,
    role: str,
    expected_algorithm_name: str,
    expected_algorithm_version: str,
) -> tuple[str, _ParameterSourceInspection]:
    parameter_source = _parameter_source_for_ref(scenario=scenario, git_ref=git_ref, role=role)
    cache_dir = run_dir / "tracks" / "parameter_artifact" / "cache" / _safe_ref_slug(git_ref)
    local_parameter_path = _localize_optional_content(parameter_source, cache_dir=cache_dir)
    if not local_parameter_path:
        raise FileNotFoundError(f"Could not resolve parameter source locally from: {parameter_source}")

    inspection = _inspect_parameter_source(
        parameter_path=Path(local_parameter_path),
        expected_algorithm_name=expected_algorithm_name,
    )
    if inspection.algorithm_version != expected_algorithm_version:
        raise ValueError(
            f"Parameter source '{parameter_source}' packages {inspection.algorithm_name} version "
            f"{inspection.algorithm_version}, but ref '{git_ref}' expects {expected_algorithm_version}."
        )
    return local_parameter_path, inspection


def _s3_uri_join(prefix: str, *parts: str) -> str:
    rendered = prefix.rstrip("/")
    for part in parts:
        if part:
            rendered = f"{rendered}/{part.strip('/')}"
    return rendered


def _split_s3_uri(s3_uri: str) -> tuple[str, str]:
    parsed = urlparse(s3_uri)
    bucket = parsed.netloc
    key = parsed.path.lstrip("/")
    if parsed.scheme != "s3" or not bucket or not key:
        raise ValueError(f"Expected s3://bucket/key URI, got: {s3_uri}")
    return bucket, key


def _qa_run_boto_session(*, assume_role_arn: str | None):
    if assume_role_arn:
        return get_boto_session_after_assuming_role(assume_role_arn)
    return boto3.Session()


def _load_sagemaker_output_base_from_template(template_path: str | None) -> str | None:
    if not template_path:
        return None
    payload = json.loads(Path(template_path).read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"SageMaker template must be a JSON object: {template_path}")
    output_base = (payload.get("OutputDataConfig") or {}).get("S3OutputPath")
    if output_base is None:
        return None
    if not isinstance(output_base, str) or not output_base.strip():
        raise ValueError(f"OutputDataConfig.S3OutputPath must be a non-empty string in {template_path}")
    return output_base.strip()


def _looks_like_ml_exp_output_base(s3_uri: str | None) -> bool:
    if not s3_uri:
        return False
    try:
        bucket, _key = _split_s3_uri(s3_uri)
    except ValueError:
        return False
    return bucket.startswith("ml-exp")


def _load_sagemaker_job_prefix_from_template(template_path: str | None) -> str | None:
    if not template_path:
        return None

    payload = json.loads(Path(template_path).read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"SageMaker template must be a JSON object: {template_path}")

    training_job_name = payload.get("TrainingJobName")
    if isinstance(training_job_name, str) and training_job_name.strip():
        job_prefix = training_job_name.strip()
        try:
            validate_job_prefix(job_prefix)
        except ValueError:
            pass
        else:
            return job_prefix

    output_base = (payload.get("OutputDataConfig") or {}).get("S3OutputPath")
    if isinstance(output_base, str) and _looks_like_ml_exp_output_base(output_base.strip()):
        return _DEFAULT_SAGEMAKER_JOB_PREFIX

    return None


def _derive_sagemaker_job_prefix(*, template_path: str | None, s3_output_base: str | None) -> str | None:
    derived_from_template = _load_sagemaker_job_prefix_from_template(template_path)
    if derived_from_template:
        return derived_from_template
    if _looks_like_ml_exp_output_base(s3_output_base):
        return _DEFAULT_SAGEMAKER_JOB_PREFIX
    return None


def _resolve_system_performance_runner(
    *,
    source_path: str | None,
    system_performance_options: dict[str, Any],
) -> str:
    runner = _normalize_runner(system_performance_options.get("runner"))
    if runner != "auto":
        return runner
    return "sagemaker" if _is_s3_uri(source_path) else "local"


def _resolve_backtest_runner(*, backtest_options: dict[str, Any]) -> str:
    return _BacktestOptions.from_mapping(backtest_options).resolved_runner()


def _resolve_system_performance_s3_output_base(system_performance_options: dict[str, Any]) -> str | None:
    explicit = system_performance_options.get("s3_output_base")
    if explicit:
        return str(explicit)
    return _load_sagemaker_output_base_from_template(system_performance_options.get("sagemaker_config"))


def _stage_local_file_to_s3(*, local_path: Path, s3_uri: str, s3_client) -> None:
    bucket, key = _split_s3_uri(s3_uri)
    _retry_after_refreshing_expired_aws_credentials(
        reason=f"uploading {local_path} to {s3_uri}",
        operation=lambda: s3_client.upload_file(Filename=str(local_path), Bucket=bucket, Key=key),
    )


def _remote_s3_object_matches_local_file(*, local_path: Path, s3_uri: str, s3_client) -> bool:
    head_object = getattr(s3_client, "head_object", None)
    if not callable(head_object):
        return False

    bucket, key = _split_s3_uri(s3_uri)
    try:
        response = _retry_after_refreshing_expired_aws_credentials(
            reason=f"checking staged object {s3_uri}",
            operation=lambda: head_object(Bucket=bucket, Key=key),
        )
    except ClientError as exc:
        if _client_error_code(exc) in {"404", "NoSuchKey", "NotFound"}:
            return False
        raise

    try:
        local_size = local_path.stat().st_size
    except OSError:
        return False

    content_length = response.get("ContentLength")
    if content_length is None:
        return False
    return int(content_length) == local_size


def _download_s3_uri_to_path(*, s3_uri: str, dest_path: Path, s3_client) -> Path:
    bucket, key = _split_s3_uri(s3_uri)
    dest_path.parent.mkdir(parents=True, exist_ok=True)
    _retry_after_refreshing_expired_aws_credentials(
        reason=f"downloading {s3_uri} to {dest_path}",
        operation=lambda: s3_client.download_file(Bucket=bucket, Key=key, Filename=str(dest_path)),
    )
    return dest_path


def _download_system_performance_metadata(*, metadata_prefix: str, stage_dir: Path, s3_client) -> Path:
    metadata_locations = (
        _s3_uri_join(str(metadata_prefix), "metadata.json"),
        _s3_uri_join(str(metadata_prefix), "meta", "performance-test", "metadata.json"),
    )
    last_not_found: ClientError | None = None
    dest_path = stage_dir / "metadata" / "metadata.json"
    for s3_uri in metadata_locations:
        try:
            return _download_s3_uri_to_path(
                s3_uri=s3_uri,
                dest_path=dest_path,
                s3_client=s3_client,
            )
        except ClientError as exc:
            if _client_error_code(exc) not in {"404", "NoSuchKey", "NotFound"}:
                raise
            last_not_found = exc
    if last_not_found is not None:
        raise last_not_found
    raise FileNotFoundError(f"Could not download system performance metadata from {metadata_prefix}")


def _parse_sagemaker_training_job_name(command_result: dict[str, Any]) -> str:
    lines = [str(item) for item in command_result.get("tail") or []]
    if not lines and command_result.get("log_path"):
        lines = Path(command_result["log_path"]).read_text(encoding="utf-8").splitlines()
    for line in reversed(lines):
        match = re.search(r"SageMaker job submitted:\s*(\S+)", line)
        if match:
            return match.group(1)
    raise ValueError("Could not determine SageMaker training job name from system performance submission log.")


def _wait_for_sagemaker_training_job(
    *,
    training_job_name: str,
    sagemaker_client,
    poll_seconds: int,
    refresh_sagemaker_client: Callable[[], Any] | None = None,
) -> dict[str, Any]:
    client = sagemaker_client
    transient_attempt = 0
    while True:
        try:
            description = client.describe_training_job(TrainingJobName=training_job_name)
            transient_attempt = 0
        except ClientError as exc:
            error_code = exc.response.get("Error", {}).get("Code")
            if _is_expired_aws_credentials_error(exc):
                _refresh_aws_credentials(reason=f"polling SageMaker training job {training_job_name}")
                if refresh_sagemaker_client is not None:
                    client = refresh_sagemaker_client()
                continue
            if error_code in {"ResourceNotFound", "ValidationException"}:
                time.sleep(2)
                continue
            raise
        except _TRANSIENT_AWS_ERRORS:
            transient_attempt += 1
            if transient_attempt >= 4:
                raise
            time.sleep(min(2 * transient_attempt, 10))
            continue

        training_job_status = description.get("TrainingJobStatus")
        if training_job_status in {"Completed", "Failed", "Stopped"}:
            return description
        time.sleep(poll_seconds)


def _ensure_remote_parameter_source(
    *,
    parameter_source: str,
    run_id: str,
    stage_dir: Path,
    system_performance_options: dict[str, Any],
    staging_subdir: str | None = None,
) -> str:
    if _is_s3_uri(parameter_source):
        return parameter_source

    output_base = _resolve_system_performance_s3_output_base(system_performance_options)
    if not output_base:
        raise ValueError(
            "Remote system_performance needs an S3 staging base for a local parameter source. "
            "Provide --performance-s3-output-base, or use --performance-sagemaker-config with "
            "OutputDataConfig.S3OutputPath."
        )

    local_parameter = _localize_optional_content(parameter_source, cache_dir=stage_dir / "parameter_cache")
    if not local_parameter:
        raise FileNotFoundError(f"Could not resolve parameter source locally from: {parameter_source}")

    staged_s3_uri = _s3_uri_join(
        output_base,
        "_hv_qa",
        "qa",
        run_id,
        "parameter_artifacts",
        staging_subdir or "",
        Path(local_parameter).name,
    )
    session = _qa_run_boto_session(assume_role_arn=system_performance_options.get("assume_role_arn"))
    s3_client = session.client("s3")
    if _remote_s3_object_matches_local_file(
        local_path=Path(local_parameter),
        s3_uri=staged_s3_uri,
        s3_client=s3_client,
    ):
        return staged_s3_uri
    _stage_local_file_to_s3(local_path=Path(local_parameter), s3_uri=staged_s3_uri, s3_client=s3_client)
    return staged_s3_uri


def _run_remote_system_performance_job(
    *,
    git_ref: str,
    artifact: _BuiltRefArtifact,
    algorithm_name: str,
    source_s3_uri: str,
    parameter_s3_uri: str,
    stage_dir: Path,
    system_performance_options: dict[str, Any],
) -> dict[str, Any]:
    options = _SystemPerformanceOptions.from_mapping(system_performance_options)
    if not options.sagemaker_job_prefix:
        raise ValueError(
            "Remote system_performance requires a SageMaker job prefix. "
            "Set qa.run.system_performance.sagemaker_job_prefix, pass "
            "--performance-sagemaker-job-prefix, or use a SageMaker template/output base that lets hv-qa derive one."
        )

    if not _is_s3_uri(source_s3_uri):
        raise ValueError("Remote system_performance currently requires an s3:// performance source path.")

    hv_args = [
        "performance-test",
        "--sagemaker",
        "--sagemaker-job-prefix",
        options.sagemaker_job_prefix,
        "--algorithm-jar",
        str(artifact.jar_path),
        "--algorithm-name",
        algorithm_name,
        "--source-s3-uri",
        source_s3_uri,
        "--parameter-s3-uri",
        parameter_s3_uri,
    ]
    hv_args.extend(options.to_hv_performance_test_args(include_remote_options=True, include_samples=True))

    submission_path = stage_dir / "submission.json"
    if submission_path.exists():
        submission_payload = _read_json(submission_path)
        expected_identity = {
            "git_ref": git_ref,
            "source_s3_uri": source_s3_uri,
            "parameter_s3_uri": parameter_s3_uri,
        }
        recorded_identity = {key: submission_payload.get(key) for key in expected_identity}
        if recorded_identity != expected_identity:
            raise ValueError(
                f"Remote system-performance submission identity changed for {stage_dir}: "
                f"recorded={recorded_identity!r}, expected={expected_identity!r}."
            )
        training_job_name = str(submission_payload["training_job_name"])
    else:
        command_result = _run_hv(args=hv_args, log_path=stage_dir / "command.log")
        training_job_name = _parse_sagemaker_training_job_name(command_result)
        submission_payload = {
            "git_ref": git_ref,
            "training_job_name": training_job_name,
            "training_job_status": "Submitted",
            "failure_reason": None,
            "source_s3_uri": source_s3_uri,
            "parameter_s3_uri": parameter_s3_uri,
            "s3_uri_metadata": None,
            "s3_uri_result_file": None,
            "s3_uri_task_output": None,
        }
        _write_json(submission_path, submission_payload)

    session = _qa_run_boto_session(assume_role_arn=options.assume_role_arn)
    sagemaker_client = session.client("sagemaker")
    description = _wait_for_sagemaker_training_job(
        training_job_name=training_job_name,
        sagemaker_client=sagemaker_client,
        poll_seconds=options.poll_seconds,
        refresh_sagemaker_client=lambda: _qa_run_boto_session(assume_role_arn=options.assume_role_arn).client(
            "sagemaker"
        ),
    )
    s3_client = _qa_run_boto_session(assume_role_arn=options.assume_role_arn).client("s3")
    hyperparameters = description.get("HyperParameters") or {}
    submission_payload = {
        "git_ref": git_ref,
        "training_job_name": training_job_name,
        "training_job_status": description.get("TrainingJobStatus"),
        "failure_reason": description.get("FailureReason"),
        "source_s3_uri": source_s3_uri,
        "parameter_s3_uri": parameter_s3_uri,
        "s3_uri_metadata": hyperparameters.get("s3_uri_metadata"),
        "s3_uri_result_file": hyperparameters.get("s3_uri_result_file"),
        "s3_uri_task_output": hyperparameters.get("s3_uri_task_output"),
    }
    _write_json(submission_path, submission_payload)

    if description.get("TrainingJobStatus") != "Completed":
        failure_reason = description.get("FailureReason") or "unknown SageMaker failure"
        raise ValueError(
            f"Remote system_performance job failed for {git_ref}: {description.get('TrainingJobStatus')} ({failure_reason})"
        )

    metadata_prefix = hyperparameters.get("s3_uri_metadata")
    if not metadata_prefix:
        raise ValueError(
            f"Remote system_performance job for {git_ref} completed without HyperParameters.s3_uri_metadata."
        )
    metadata_path = _download_system_performance_metadata(
        metadata_prefix=str(metadata_prefix),
        stage_dir=stage_dir,
        s3_client=s3_client,
    )
    return {
        "metadata_path": str(metadata_path),
        "submission_path": str(submission_path),
        "training_job_name": training_job_name,
        "s3_uri_metadata": metadata_prefix,
    }


def _resolve_prediction_output_files(dest_path: Path) -> list[Path]:
    if dest_path.is_file():
        return [dest_path]
    if dest_path.is_dir():
        shard_paths = sorted(dest_path.glob("shard_*.jsonl"))
        if shard_paths:
            return shard_paths
        file_paths = sorted(dest_path.glob("*.jsonl"))
        if file_paths:
            return file_paths
    raise FileNotFoundError(f"Could not locate prediction output under {dest_path}")


def _load_prediction_records(files: list[Path]) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    seen_example_ids: set[str] = set()
    for file_path in files:
        for line_number, raw_line in enumerate(file_path.read_text(encoding="utf-8").splitlines(), start=1):
            line = raw_line.strip()
            if not line:
                continue
            payload = json.loads(line)
            if not isinstance(payload, dict):
                raise ValueError(f"Prediction output record in {file_path}:{line_number} must be a JSON object.")
            example_id = payload.get("example_id")
            if not isinstance(example_id, str) or not example_id:
                raise ValueError(f"Prediction output record in {file_path}:{line_number} is missing example_id.")
            if example_id in seen_example_ids:
                raise ValueError(
                    f"Prediction output under {file_path.parent if file_path.parent.exists() else file_path} "
                    f"contains duplicate example_id '{example_id}'."
                )
            seen_example_ids.add(example_id)
            records.append(payload)
    return records


def _normalize_prediction_output(dest_path: Path, normalized_path: Path) -> dict[str, Any]:
    input_files = _resolve_prediction_output_files(dest_path)
    records = sorted(_load_prediction_records(input_files), key=lambda payload: str(payload["example_id"]))
    normalized_path.parent.mkdir(parents=True, exist_ok=True)
    with open(normalized_path, "w", encoding="utf-8") as fp:
        for record in records:
            fp.write(json.dumps(record, sort_keys=True) + "\n")
    return {
        "source_path": str(dest_path),
        "input_files": [str(path) for path in input_files],
        "record_count": len(records),
        "normalized_path": str(normalized_path),
    }


def _compare_artifact_files(control_file: Path, treatment_file: Path) -> dict[str, Any]:
    if not control_file.exists():
        raise FileNotFoundError(f"Missing control artifact for comparison: {control_file}")
    if not treatment_file.exists():
        raise FileNotFoundError(f"Missing treatment artifact for comparison: {treatment_file}")

    control_bytes = control_file.read_bytes()
    treatment_bytes = treatment_file.read_bytes()
    if control_bytes == treatment_bytes:
        return {
            "identical": True,
            "mode": "bytes",
            "control_size_bytes": len(control_bytes),
            "treatment_size_bytes": len(treatment_bytes),
        }

    result: dict[str, Any] = {
        "identical": False,
        "control_size_bytes": len(control_bytes),
        "treatment_size_bytes": len(treatment_bytes),
    }

    try:
        with (
            open(control_file, "r", encoding="utf-8") as control_fp,
            open(treatment_file, "r", encoding="utf-8") as treatment_fp,
        ):
            control_line_count = 0
            treatment_line_count = 0
            for line_number, (control_line, treatment_line) in enumerate(
                zip_longest(control_fp, treatment_fp),
                start=1,
            ):
                if control_line is not None:
                    control_line_count += 1
                if treatment_line is not None:
                    treatment_line_count += 1
                if control_line != treatment_line:
                    result.update(
                        {
                            "mode": "text",
                            "first_difference_line": line_number,
                            "control_preview": (control_line or "").rstrip("\n")[:240],
                            "treatment_preview": (treatment_line or "").rstrip("\n")[:240],
                        }
                    )
                    break
            result["control_line_count"] = control_line_count
            result["treatment_line_count"] = treatment_line_count
            return result
    except UnicodeDecodeError:
        pass

    min_length = min(len(control_bytes), len(treatment_bytes))
    differing_index = next(
        (index for index in range(min_length) if control_bytes[index] != treatment_bytes[index]),
        min_length if len(control_bytes) != len(treatment_bytes) else None,
    )
    result.update({"mode": "binary", "first_difference_byte": differing_index})
    return result


def _compare_audit_jsonl_artifacts(control_file: Path, treatment_file: Path, output_dir: Path) -> dict[str, Any]:
    if not control_file.exists():
        raise FileNotFoundError(f"Missing control audit artifact for comparison: {control_file}")
    if not treatment_file.exists():
        raise FileNotFoundError(f"Missing treatment audit artifact for comparison: {treatment_file}")
    output_dir.mkdir(parents=True, exist_ok=True)
    if control_file.is_dir() or treatment_file.is_dir():
        control_records: Counter[bytes] = Counter()
        treatment_records: Counter[bytes] = Counter()
        for file_path in _artifact_regular_files(control_file):
            with file_path.open("r", encoding="utf-8") as fp:
                for raw_line in fp:
                    line = raw_line.strip()
                    if not line:
                        continue
                    payload = json.loads(line)
                    control_records[json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")] += 1
        for file_path in _artifact_regular_files(treatment_file):
            with file_path.open("r", encoding="utf-8") as fp:
                for raw_line in fp:
                    line = raw_line.strip()
                    if not line:
                        continue
                    payload = json.loads(line)
                    treatment_records[json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")] += 1
        missing = control_records - treatment_records
        unexpected = treatment_records - control_records
        return {
            "identical": not missing and not unexpected,
            "mode": "jsonl_record_multiset",
            "control_path": str(control_file),
            "treatment_path": str(treatment_file),
            "control_record_count": sum(control_records.values()),
            "treatment_record_count": sum(treatment_records.values()),
            "missing_record_count": sum(missing.values()),
            "unexpected_record_count": sum(unexpected.values()),
            "missing_preview": _preview_multiset(missing),
            "unexpected_preview": _preview_multiset(unexpected),
        }
    comparison = json.loads(find_difference_in_files(str(control_file), str(treatment_file), str(output_dir), None))
    comparison["mode"] = "jsonl_compare"
    comparison["identical"] = comparison.get("message") == "The two files are identical"
    comparison["control_path"] = str(control_file)
    comparison["treatment_path"] = str(treatment_file)
    return comparison


def _artifact_regular_files(path: Path) -> list[Path]:
    if path.is_file():
        return [path]
    if path.is_dir():
        files = sorted(file_path for file_path in path.rglob("*") if file_path.is_file())
        if files:
            return files
    raise FileNotFoundError(f"Could not locate artifact files under {path}")


def _artifact_total_size_bytes(path: Path) -> int:
    return sum(file_path.stat().st_size for file_path in _artifact_regular_files(path))


def _line_multiset_for_artifact(path: Path) -> Counter[bytes]:
    lines: Counter[bytes] = Counter()
    for file_path in _artifact_regular_files(path):
        with file_path.open("rb") as fp:
            for raw_line in fp:
                line = raw_line.rstrip(b"\r\n")
                if line:
                    lines[line] += 1
    return lines


def _encoded_schema_column_count(schema_path: Path) -> int:
    text = schema_path.read_text(encoding="utf-8")
    try:
        schema = json.loads(text)
    except json.JSONDecodeError:
        schema = None
    if isinstance(schema, dict) and isinstance(schema.get("columns"), list):
        return len(schema["columns"])
    if isinstance(schema, list):
        return len(schema)
    return sum(1 for line in text.splitlines() if line.strip())


def _encoded_tsv_records_for_artifact(path: Path, *, expected_columns: int) -> tuple[Counter[bytes], dict[str, int]]:
    records: Counter[bytes] = Counter()
    stats = {
        "physical_line_count": 0,
        "record_count": 0,
        "repaired_joined_record_boundaries": 0,
    }
    pending_fields: list[bytes] = []
    for file_path in _artifact_regular_files(path):
        with file_path.open("rb") as fp:
            for raw_line in fp:
                stats["physical_line_count"] += 1
                line = raw_line.rstrip(b"\r\n")
                if not line and not pending_fields:
                    continue
                line_fields = line.split(b"\t")
                if pending_fields:
                    pending_fields[-1] = pending_fields[-1] + b"\n" + line_fields[0]
                    fields = pending_fields + line_fields[1:]
                    pending_fields = []
                else:
                    fields = line_fields

                while fields:
                    if len(fields) < expected_columns:
                        pending_fields = fields
                        break
                    record_fields = fields[:expected_columns]
                    fields = fields[expected_columns:]
                    if fields:
                        joined_boundary_field = record_fields[-1]
                        if expected_columns <= 1:
                            raise ValueError(
                                f"Cannot split joined encoded TSV records in {file_path}: "
                                f"expected at least two schema columns, got {expected_columns}."
                            )
                        joined_label = joined_boundary_field[-1:]
                        if joined_label not in (b"0", b"1"):
                            raise ValueError(
                                f"Cannot split joined encoded TSV records in {file_path}: "
                                f"expected a binary label at the joined boundary, got {joined_label!r}."
                            )
                        record_fields[-1] = joined_boundary_field[:-1]
                        fields = [joined_label] + fields
                        stats["repaired_joined_record_boundaries"] += 1
                    record = b"\t".join(record_fields)
                    if record:
                        records[record] += 1
                        stats["record_count"] += 1
        if pending_fields:
            raise ValueError(
                f"Could not parse encoded TSV artifact {file_path}: trailing record has "
                f"{len(pending_fields)} columns, expected {expected_columns}."
            )
    return records, stats


def _preview_multiset(counter: Counter[bytes], *, limit: int = 3) -> list[str]:
    preview: list[str] = []
    for line, count in counter.most_common(limit):
        decoded = line[:240].decode("utf-8", errors="replace")
        preview.append(f"{decoded} (count={count})")
    return preview


def _compare_text_artifact_lines_unordered(control_path: Path, treatment_path: Path) -> dict[str, Any]:
    control_lines = _line_multiset_for_artifact(control_path)
    treatment_lines = _line_multiset_for_artifact(treatment_path)
    missing = control_lines - treatment_lines
    unexpected = treatment_lines - control_lines
    return {
        "identical": not missing and not unexpected,
        "mode": "line_multiset",
        "control_size_bytes": _artifact_total_size_bytes(control_path),
        "treatment_size_bytes": _artifact_total_size_bytes(treatment_path),
        "control_file_count": len(_artifact_regular_files(control_path)),
        "treatment_file_count": len(_artifact_regular_files(treatment_path)),
        "control_line_count": sum(control_lines.values()),
        "treatment_line_count": sum(treatment_lines.values()),
        "missing_line_count": sum(missing.values()),
        "unexpected_line_count": sum(unexpected.values()),
        "missing_preview": _preview_multiset(missing),
        "unexpected_preview": _preview_multiset(unexpected),
    }


def _compare_encoded_tsv_artifacts_unordered(
    control_path: Path,
    treatment_path: Path,
    schema_path: Path,
) -> dict[str, Any]:
    expected_columns = _encoded_schema_column_count(schema_path)
    control_records, control_stats = _encoded_tsv_records_for_artifact(
        control_path,
        expected_columns=expected_columns,
    )
    treatment_records, treatment_stats = _encoded_tsv_records_for_artifact(
        treatment_path,
        expected_columns=expected_columns,
    )
    missing = control_records - treatment_records
    unexpected = treatment_records - control_records
    return {
        "identical": not missing and not unexpected,
        "mode": "encoded_tsv_record_multiset",
        "expected_columns": expected_columns,
        "control_size_bytes": _artifact_total_size_bytes(control_path),
        "treatment_size_bytes": _artifact_total_size_bytes(treatment_path),
        "control_file_count": len(_artifact_regular_files(control_path)),
        "treatment_file_count": len(_artifact_regular_files(treatment_path)),
        "control_record_count": sum(control_records.values()),
        "treatment_record_count": sum(treatment_records.values()),
        "missing_record_count": sum(missing.values()),
        "unexpected_record_count": sum(unexpected.values()),
        "control_repaired_joined_record_boundaries": control_stats["repaired_joined_record_boundaries"],
        "treatment_repaired_joined_record_boundaries": treatment_stats["repaired_joined_record_boundaries"],
        "control_physical_line_count": control_stats["physical_line_count"],
        "treatment_physical_line_count": treatment_stats["physical_line_count"],
        "missing_preview": _preview_multiset(missing),
        "unexpected_preview": _preview_multiset(unexpected),
    }


def _representative_offline_quality(metrics: dict[str, dict[str, Any]]) -> float | None:
    for metric in ("ndcg_at_50", "map_at_50", "pr_auc", "roc_auc", "ndcg_at_10", "map_at_10"):
        if metric in metrics and metrics[metric].get("treatment_mean") is not None:
            return float(metrics[metric]["treatment_mean"])
    for metric, payload in metrics.items():
        if payload.get("treatment_mean") is not None:
            return float(payload["treatment_mean"])
    return None


def _representative_performance(metrics: dict[str, dict[str, Any]]) -> float | None:
    throughput = metrics.get("mean_throughput")
    if throughput and throughput.get("percent_change") is not None:
        return float(throughput["percent_change"])
    p99 = metrics.get("p99")
    if p99 and p99.get("percent_change") is not None:
        return -float(p99["percent_change"])
    return None


def _stage_dir(run_dir: Path, stage: str) -> Path:
    return run_dir / "tracks" / _STAGE_TRACKS[stage] / stage


def _trial_dir(base_dir: Path, trial_index: int, trial_count: int) -> Path:
    if trial_count == 1:
        return base_dir
    return base_dir / f"trial-{trial_index:02d}"


def _existing_system_performance_remote_artifacts(trial_dir: Path, metadata_path: Path) -> dict[str, Any]:
    submission_path = trial_dir / "submission.json"
    if not submission_path.exists():
        return {}
    submission = _read_json(submission_path)
    return {
        "metadata_path": str(metadata_path),
        "submission_path": str(submission_path),
        "training_job_name": submission.get("training_job_name"),
        "s3_uri_metadata": submission.get("s3_uri_metadata"),
    }


def _offline_context_dates(offline_context: dict[str, Any]) -> list[str]:
    window = dict(offline_context.get("backtest_date_window") or {})
    start_date = date.fromisoformat(str(window["start_date"]))
    end_date = date.fromisoformat(str(window["end_date"]))
    ret: list[str] = []
    cursor = start_date
    while cursor <= end_date:
        ret.append(cursor.isoformat())
        cursor += timedelta(days=1)
    return ret


def _materialize_existing_backtest_results(
    *,
    result_sources: list[str],
    output_base_dir: Path,
    offline_context: dict[str, Any],
    role: str = "backtest",
) -> dict[str, Any]:
    normalized_sources = _normalize_backtest_result_sources(result_sources)
    if not normalized_sources:
        raise ValueError("Expected at least one saved backtest result source.")
    meta_dir = output_base_dir / "meta"
    cache_dir = output_base_dir / "_result_cache"
    meta_dir.mkdir(parents=True, exist_ok=True)
    materialized: list[dict[str, Any]] = []
    seen_dates: set[str] = set()
    for index, result_source in enumerate(normalized_sources, start=1):
        local_result_path = _localize_optional_content(result_source, cache_dir=cache_dir / f"item-{index:02d}")
        if not local_result_path:
            raise FileNotFoundError(f"Could not resolve saved backtest result locally from: {result_source}")
        payload = _read_json(Path(local_result_path))
        algorithm_id = str(payload.get("algorithm_id") or "").strip()
        test_data_time = str(payload.get("test_data_time") or "").strip()
        if not algorithm_id:
            raise ValueError(f"Saved backtest result '{result_source}' is missing algorithm_id.")
        if not test_data_time:
            raise ValueError(f"Saved backtest result '{result_source}' is missing test_data_time.")
        date.fromisoformat(test_data_time)
        if test_data_time in seen_dates:
            raise ValueError(f"Duplicate saved {role} backtest result date '{test_data_time}' for reuse.")
        seen_dates.add(test_data_time)
        destination = meta_dir / algorithm_id / f"last_test_date_{test_data_time}" / "result.json"
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(local_result_path, destination)
        materialized.append(
            {
                "source": result_source,
                "local_path": str(Path(local_result_path).resolve()),
                "algorithm_id": algorithm_id,
                "test_data_time": test_data_time,
                "materialized_result_path": str(destination),
            }
        )
    expected_dates = set(_offline_context_dates(offline_context))
    missing_dates = sorted(expected_dates - seen_dates)
    if missing_dates:
        raise ValueError(
            f"Saved {role} backtest results do not cover the requested date window. Missing dates: "
            + ", ".join(missing_dates)
        )
    return {
        "output_base_dir": str(output_base_dir),
        "results": materialized,
    }


def _latest_remote_backtest_submission_manifest(output_base_dir: Path) -> Path:
    submissions_dir = output_base_dir / "meta" / "_backtest_submissions"
    manifests = sorted(submissions_dir.glob("*/backtest_submission_manifest.json"))
    if not manifests:
        raise FileNotFoundError(f"No SageMaker backtest submission manifest found under {submissions_dir}")
    return manifests[-1]


def _materialize_remote_backtest_result(
    *,
    job: dict[str, Any],
    description: dict[str, Any],
    output_base_dir: Path,
    s3_client,
) -> dict[str, Any]:
    training_job_name = str(job.get("training_job_name") or "")
    hyperparameters = description.get("HyperParameters") or {}
    s3_uri_result_file = job.get("s3_uri_result_file") or hyperparameters.get("s3_uri_result_file")
    if not isinstance(s3_uri_result_file, str) or not s3_uri_result_file:
        raise ValueError(f"Completed SageMaker backtest job {training_job_name} has no s3_uri_result_file.")

    download_path = (
        output_base_dir
        / "meta"
        / "_remote_backtest_results"
        / sanitize_path_component(training_job_name or "job")
        / "result.json"
    )
    _download_s3_uri_to_path(s3_uri=s3_uri_result_file, dest_path=download_path, s3_client=s3_client)
    payload = _read_json(download_path)
    algorithm_id = str(payload.get("algorithm_id") or "").strip()
    test_data_time = str(payload.get("test_data_time") or job.get("test_data_time") or "").strip()
    if not algorithm_id:
        raise ValueError(f"Remote backtest result {s3_uri_result_file} is missing algorithm_id.")
    if not test_data_time:
        raise ValueError(f"Remote backtest result {s3_uri_result_file} is missing test_data_time.")
    date.fromisoformat(test_data_time)

    destination = output_base_dir / "meta" / algorithm_id / f"last_test_date_{test_data_time}" / "result.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(download_path, destination)
    return {
        "algo_git_reference": job.get("algo_git_reference"),
        "parameter_version": job.get("parameter_version"),
        "test_data_time": test_data_time,
        "training_job_name": training_job_name,
        "training_job_status": description.get("TrainingJobStatus"),
        "secondary_status": description.get("SecondaryStatus"),
        "s3_uri_result_file": s3_uri_result_file,
        "s3_uri_metadata": job.get("s3_uri_metadata") or hyperparameters.get("s3_uri_metadata"),
        "s3_uri_python_log_file": hyperparameters.get("s3_uri_python_log_file"),
        "downloaded_result_path": str(download_path),
        "materialized_result_path": str(destination),
        "algorithm_id": algorithm_id,
    }


def _wait_for_remote_backtest_submission(
    *,
    output_base_dir: Path,
    options: _BacktestOptions,
) -> dict[str, Any]:
    manifest_path = _latest_remote_backtest_submission_manifest(output_base_dir)
    manifest = _read_json(manifest_path)
    jobs = manifest.get("jobs")
    if not isinstance(jobs, list) or not jobs:
        raise ValueError(f"SageMaker backtest submission manifest contains no jobs: {manifest_path}")

    session = _qa_run_boto_session(assume_role_arn=options.assume_role_arn)
    sagemaker_client = session.client("sagemaker")
    materialized: list[dict[str, Any]] = []
    failures: list[dict[str, Any]] = []

    for job in jobs:
        if not isinstance(job, dict):
            raise ValueError(f"Invalid SageMaker backtest job entry in {manifest_path}: {job!r}")
        training_job_name = str(job.get("training_job_name") or "").strip()
        if not training_job_name:
            raise ValueError(f"SageMaker backtest job entry is missing training_job_name in {manifest_path}")

        description = _wait_for_sagemaker_training_job(
            training_job_name=training_job_name,
            sagemaker_client=sagemaker_client,
            poll_seconds=options.poll_seconds,
            refresh_sagemaker_client=lambda: _qa_run_boto_session(assume_role_arn=options.assume_role_arn).client(
                "sagemaker"
            ),
        )
        status = description.get("TrainingJobStatus")
        if status != "Completed":
            hyperparameters = description.get("HyperParameters") or {}
            failures.append(
                {
                    "algo_git_reference": job.get("algo_git_reference"),
                    "test_data_time": job.get("test_data_time"),
                    "training_job_name": training_job_name,
                    "training_job_status": status,
                    "secondary_status": description.get("SecondaryStatus"),
                    "failure_reason": description.get("FailureReason"),
                    "s3_uri_result_file": job.get("s3_uri_result_file") or hyperparameters.get("s3_uri_result_file"),
                    "s3_uri_metadata": job.get("s3_uri_metadata") or hyperparameters.get("s3_uri_metadata"),
                    "s3_uri_python_log_file": hyperparameters.get("s3_uri_python_log_file"),
                }
            )
            continue

        materialized.append(
            _materialize_remote_backtest_result(
                job=job,
                description=description,
                output_base_dir=output_base_dir,
                s3_client=_qa_run_boto_session(assume_role_arn=options.assume_role_arn).client("s3"),
            )
        )

    status_payload = {
        "manifest_path": str(manifest_path),
        "job_count": len(jobs),
        "completed_count": len(materialized),
        "failed_count": len(failures),
        "jobs": materialized,
        "failures": failures,
    }
    status_path = manifest_path.parent / "hv_qa_backtest_status.json"
    _write_json(status_path, status_payload)
    if failures:
        rendered = "; ".join(
            f"{failure['training_job_name']}={failure.get('training_job_status')} "
            f"({failure.get('failure_reason') or 'no failure reason'})"
            for failure in failures
        )
        raise ValueError(f"Remote backtest failed: {rendered}. Details: {status_path}")

    return {
        "runner": "sagemaker",
        "manifest_path": str(manifest_path),
        "status_path": str(status_path),
        "jobs": materialized,
    }


def _missing_execution_context(stage: str, scenario: dict[str, Any]) -> list[str]:
    execution_context = scenario.get("execution_context", {})
    missing = [key for key in _STAGE_REQUIREMENTS.get(stage, ()) if not execution_context.get(key)]
    if "algo_repo_url" in missing and _has_repository_for_every_ref(scenario):
        missing.remove("algo_repo_url")
    if stage in {"realistic_single_day", "multi_day_backtest"} and "data_base_dir" in missing:
        backtest_options = dict((scenario.get("stage_options") or {}).get("backtest") or {})
        if _resolve_backtest_runner(backtest_options=backtest_options) == "sagemaker":
            missing.remove("data_base_dir")
    if stage in {"audit_parity", "encode_parity"} and "encode_algorithm_name" in missing:
        if _normalize_encode_algorithm_names(scenario.get("encode_algorithm_names")):
            missing.remove("encode_algorithm_name")
    if stage == "system_performance" and "performance_source_path" in missing:
        if _normalize_performance_source_paths(scenario.get("performance_source_paths")):
            missing.remove("performance_source_path")
    if stage in _PARAMETER_REQUIRED_STAGES and not _has_stage_parameter_sources(scenario):
        missing.append("parameter_source")
    return missing


def _missing_context_message(stage: str, missing_keys: list[str]) -> str:
    field_help = {
        "algo_repo_url": "algorithm repo (--repo, --control-repo/--treatment-repo, or qa.run.execution.algo_repo_url)",
        "algorithm_name": "predict/performance algorithm name (usually derived from the control artifact)",
        "encode_algorithm_name": "encode algorithm name (usually derived from the control artifact dependency graph)",
        "source_path": "generic source path",
        "predict_source_path": "predict source path (usually derived from test_data_spec)",
        "performance_source_path": "system-performance source path (usually derived from test_data_spec)",
        "performance_source_paths": "per-ref system-performance source paths",
        "encode_algorithm_names": "per-ref encode algorithm names",
        "encode_source_path": "encode source path (usually derived from test_data_spec)",
        "data_base_dir": "data base dir (--data-base-dir or directories.data_base_dir)",
        "scratch_dir": "scratch dir (--scratch-dir or directories.scratch_dir)",
        "parameter_source": "fixed-parameter QA setup",
    }
    rendered = ", ".join(field_help.get(key, key) for key in missing_keys)
    return f"Stage '{stage}' is missing required execution context: {rendered}."


def _build_stage_status(requested_stages: list[str], stage_sequence: tuple[str, ...]) -> dict[str, str]:
    return {stage: ("pending" if stage in requested_stages else "not_requested") for stage in stage_sequence}


def _rebuild_stage_status_for_request(
    *,
    completed_stages: list[str],
    requested_stages: list[str],
    stage_sequence: tuple[str, ...],
    requested_mode: str,
) -> dict[str, str]:
    completed = set(completed_stages)
    stage_status: dict[str, str] = {}
    for stage in stage_sequence:
        if requested_mode != "only" and stage in completed:
            stage_status[stage] = "passed"
        elif stage in requested_stages:
            stage_status[stage] = "pending"
        else:
            stage_status[stage] = "not_requested"
    return stage_status


def _create_run_layout(run_dir: Path) -> None:
    run_dir.mkdir(parents=True, exist_ok=False)
    (run_dir / "criteria").mkdir(parents=True, exist_ok=True)
    tracks_dir = run_dir / "tracks"
    tracks_dir.mkdir(parents=True, exist_ok=True)
    for track in _TRACK_DIRS:
        (tracks_dir / track).mkdir(parents=True, exist_ok=True)


def _append_decision_event(paths: dict[str, Path], payload: dict[str, Any]) -> None:
    _append_jsonl(paths["decisions"], payload)


def _plan_sha256(scenario: dict[str, Any]) -> str:
    return hashlib.sha256(json.dumps(scenario, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()


def _validate_frozen_plan(scenario: dict[str, Any], status: dict[str, Any]) -> None:
    recorded_sha256 = str(status["plan_sha256"])
    actual_sha256 = _plan_sha256(scenario)
    if actual_sha256 != recorded_sha256:
        raise ValueError(
            f"QA run plan was modified after start: recorded SHA-256 {recorded_sha256}, "
            f"current SHA-256 {actual_sha256}. Start a new QA run."
        )


def _stage_status_from_judgment(stage_judgment: str) -> str:
    if stage_judgment == "pass":
        return "passed"
    if stage_judgment == "inconclusive":
        return "inconclusive"
    return "failed"


def _persist_qa_run_state(
    *,
    run_dir: Path,
    scenario: dict[str, Any],
    status: dict[str, Any],
) -> None:
    paths = _scenario_paths(run_dir)
    _write_json(paths["scenario"], scenario)
    _write_json(paths["status"], status)
    plan_sha256 = _plan_sha256(scenario)
    _write_json(
        paths["evidence"],
        {
            "schema_version": 1,
            "run_id": scenario["run_id"],
            "plan_sha256": plan_sha256,
            "criteria": scenario["criteria"],
            "refs": scenario["refs"],
            "offline_context": scenario["offline_context"],
            "parameter_source": scenario.get("parameter_source"),
            "parameter_sources": scenario.get("parameter_sources"),
            "execution_context": scenario.get("execution_context"),
            "stage_options": scenario.get("stage_options"),
            "state": status["state"],
            "completed_stages": status.get("completed_stages", []),
            "stage_status": status.get("stage_status", {}),
            "stage_results": status.get("stage_results", {}),
        },
    )


def _stages_pending_execution(status: dict[str, Any]) -> list[str]:
    requested_mode = str(status.get("requested_mode") or "")
    completed_stages = set(status.get("completed_stages") or [])
    requested_stages = list(status.get("requested_stages") or [])
    if requested_mode == "only":
        return requested_stages
    return [stage for stage in requested_stages if stage not in completed_stages]


def _should_execute_requested_stages(status: dict[str, Any]) -> bool:
    pending_stages = _stages_pending_execution(status)
    if not pending_stages:
        return False
    first_pending_stage = pending_stages[0]
    return str((status.get("stage_status") or {}).get(first_pending_stage)) == "pending"


def _record_qa_run_preflight_failure(
    *,
    run_dir: Path,
    scenario: dict[str, Any],
    status: dict[str, Any],
    stage: str,
    message: str,
    missing_context: list[str] | None = None,
    error: str | None = None,
    debug: dict[str, Any] | None = None,
    phase: str,
) -> None:
    paths = _scenario_paths(run_dir)
    stage_results = dict(status.get("stage_results") or {})
    stage_result: dict[str, Any] = {
        "stage": stage,
        "state": "failed",
        "summary": message,
    }
    if missing_context:
        stage_result["missing_context"] = list(missing_context)
    if error:
        stage_result["error"] = error
    if debug is not None:
        stage_result["debug"] = debug
    stage_results[stage] = stage_result

    status["stage_results"] = stage_results
    status["stage_status"][stage] = "failed"
    status["current_stage"] = stage
    status["state"] = "failed"
    status["notes"] = [message]
    status["updated_at"] = _now_iso()
    _persist_qa_run_state(run_dir=run_dir, scenario=scenario, status=status)
    _append_decision_event(
        paths,
        {
            "timestamp": status["updated_at"],
            "event": "stage_failed",
            "stage": stage,
            "phase": phase,
            "error": error or message,
            **({"missing_context": list(missing_context)} if missing_context else {}),
        },
    )


def _prepare_qa_run_execution(
    *,
    run_dir: Path,
    scenario: dict[str, Any],
    status: dict[str, Any],
) -> dict[str, _BuiltRefArtifact]:
    artifact_cache: dict[str, _BuiltRefArtifact] = {}
    stages_to_execute = _stages_pending_execution(status)
    if not stages_to_execute:
        return artifact_cache

    first_stage = stages_to_execute[0]
    try:
        derived_context = _derive_qa_run_execution_context(
            run_dir=run_dir,
            scenario=scenario,
            artifact_cache=artifact_cache,
        )
    except Exception as exc:
        message = f"Stage '{first_stage}' failed while deriving execution context: {exc}"
        _record_qa_run_preflight_failure(
            run_dir=run_dir,
            scenario=scenario,
            status=status,
            stage=first_stage,
            message=message,
            error=str(exc),
            debug=_build_stage_failure_debug(
                run_dir=run_dir,
                paths=_scenario_paths(run_dir),
                stage=first_stage,
                exc=exc,
            ),
            phase="derive_execution_context",
        )
        return artifact_cache

    needs_parameter_source = any(stage in _PARAMETER_REQUIRED_STAGES for stage in scenario["stage_sequence"])
    try:
        derived_parameter_source = (
            _derive_shared_parameter_source(run_dir=run_dir, scenario=scenario, artifact_cache=artifact_cache)
            if needs_parameter_source
            else False
        )
    except Exception as exc:
        message = f"Stage '{first_stage}' failed while deriving parameter source: {exc}"
        _record_qa_run_preflight_failure(
            run_dir=run_dir,
            scenario=scenario,
            status=status,
            stage=first_stage,
            message=message,
            error=str(exc),
            debug=_build_stage_failure_debug(
                run_dir=run_dir,
                paths=_scenario_paths(run_dir),
                stage=first_stage,
                exc=exc,
            ),
            phase="derive_parameter_source",
        )
        return artifact_cache

    if derived_context or derived_parameter_source:
        status["updated_at"] = _now_iso()
        _persist_qa_run_state(run_dir=run_dir, scenario=scenario, status=status)
        _append_decision_event(
            _scenario_paths(run_dir),
            {
                "timestamp": status["updated_at"],
                "event": "execution_context_derived",
                "stage": first_stage,
                "execution_context": scenario.get("execution_context"),
                "parameter_source_resolution": scenario.get("parameter_source_resolution"),
                "refs": scenario.get("refs"),
            },
        )

    for stage in stages_to_execute:
        missing = _missing_execution_context(stage, scenario)
        if missing:
            _record_qa_run_preflight_failure(
                run_dir=run_dir,
                scenario=scenario,
                status=status,
                stage=stage,
                message=_missing_context_message(stage, missing),
                missing_context=missing,
                phase="validate_execution_context",
            )
            break

    return artifact_cache


def _ensure_ref_artifact(
    *,
    cache: dict[str, _BuiltRefArtifact],
    run_dir: Path,
    scenario: dict[str, Any],
    git_ref: str,
    role: str,
    algo_repo_url: str | None = None,
) -> _BuiltRefArtifact:
    resolved_algo_repo_url = _normalize_algorithm_repo_url(algo_repo_url) or _repository_url_for_ref(
        scenario=scenario,
        git_ref=git_ref,
        role=role,
    )
    cache_key = f"{role}\0{resolved_algo_repo_url}\0{git_ref}"
    if cache_key not in cache:
        cache[cache_key] = _build_ref_artifact(
            run_dir=run_dir,
            algo_repo_url=resolved_algo_repo_url,
            git_ref=git_ref,
        )
    return cache[cache_key]


def _run_hv(
    *,
    args: list[str],
    log_path: Path,
    cwd: Path | None = None,
) -> dict[str, Any]:
    cmd = [sys.executable, str(_hv_bin_path("hv")), *args]
    try:
        return _run_logged_command(cmd, log_path=log_path, cwd=cwd, env=_command_env())
    except subprocess.CalledProcessError as exc:
        failure_details = _build_hv_failure_details(args=args, log_path=log_path, exc=exc)
        raise _HvCommandFailure(
            str(failure_details["summary"]),
            command_name=str(failure_details["command_name"]),
            hv_args=args,
            wrapper_cmd=cmd,
            log_path=str(log_path),
            metadata_dir=failure_details.get("metadata_dir"),
            log_paths=dict(failure_details.get("log_paths") or {}),
            log_tail=dict(failure_details.get("log_tail") or {}),
            root_cause=failure_details.get("root_cause"),
            throwable_stacktrace=failure_details.get("throwable_stacktrace"),
            return_code=int(exc.returncode),
            cwd=str(cwd) if cwd is not None else None,
        ) from exc


def _mean(values: list[float]) -> float:
    if not values:
        raise ValueError("Cannot compute mean of empty values.")
    return sum(values) / len(values)


def _sample_standard_deviation(values: list[float]) -> float | None:
    if len(values) < 2:
        return None
    value_mean = _mean(values)
    return math.sqrt(sum((value - value_mean) ** 2 for value in values) / (len(values) - 1))


def _paired_permutation_p_value(deltas: list[float]) -> float | None:
    if len(deltas) < 2:
        return None
    observed = abs(_mean(deltas))
    if observed == 0:
        return 1.0
    total = 0
    at_least_as_extreme = 0
    for signs in product((-1.0, 1.0), repeat=len(deltas)):
        total += 1
        permuted = abs(_mean([delta * sign for delta, sign in zip(deltas, signs)]))
        if permuted >= observed - 1e-12:
            at_least_as_extreme += 1
    return at_least_as_extreme / total


def _extract_system_metrics(performance_payload: dict[str, Any]) -> dict[str, float]:
    metrics: dict[str, float] = {
        "max_memory_usage": float(performance_payload["max_memory_usage"]),
        "mean_throughput": float(performance_payload["mean_throughput"]),
    }
    response_time_metrics = performance_payload.get("response_time_metrics") or {}
    for key in ["p50", "p75", "p95", "p99", "p999"]:
        value = response_time_metrics.get(key, {}).get("mean")
        if value is not None:
            metrics[key] = float(value)
    return metrics


def _compare_system_metrics_from_trial_files(
    control_files: list[Path],
    treatment_files: list[Path],
) -> dict[str, dict[str, Any]]:
    if len(control_files) != len(treatment_files):
        raise ValueError(
            f"Paired system performance comparison needs equal trial counts; "
            f"got {len(control_files)} control and {len(treatment_files)} treatment files."
        )
    if not control_files:
        raise ValueError("Paired system performance comparison needs at least one trial.")

    paired_metrics: list[tuple[dict[str, float], dict[str, float]]] = []
    for control_file, treatment_file in zip(control_files, treatment_files):
        control_metrics = _extract_system_metrics(_load_performance_file(str(control_file)))
        treatment_metrics = _extract_system_metrics(_load_performance_file(str(treatment_file)))
        paired_metrics.append((control_metrics, treatment_metrics))

    expected_metrics = sorted(set().union(*(set(control) | set(treatment) for control, treatment in paired_metrics)))
    for trial_index, (control_metrics, treatment_metrics) in enumerate(paired_metrics, start=1):
        missing_control = sorted(set(expected_metrics) - set(control_metrics))
        missing_treatment = sorted(set(expected_metrics) - set(treatment_metrics))
        if missing_control or missing_treatment:
            raise ValueError(
                "Incomplete system-performance evidence for paired trial "
                f"{trial_index}: missing control metrics={missing_control}, "
                f"missing treatment metrics={missing_treatment}."
            )

    paired_values: dict[str, list[tuple[float, float]]] = {metric: [] for metric in expected_metrics}
    for control_metrics, treatment_metrics in paired_metrics:
        for metric in SYSTEM_METRICS:
            if metric in control_metrics and metric in treatment_metrics:
                paired_values[metric].append((float(control_metrics[metric]), float(treatment_metrics[metric])))

    metrics: dict[str, dict[str, Any]] = {}
    for metric in SYSTEM_METRICS:
        pairs = paired_values.get(metric) or []
        if not pairs:
            continue
        control_values = [control_value for control_value, _ in pairs]
        treatment_values = [treatment_value for _, treatment_value in pairs]
        absolute_deltas = [treatment_value - control_value for control_value, treatment_value in pairs]
        percent_deltas = [
            ((treatment_value - control_value) / control_value) * 100.0
            for control_value, treatment_value in pairs
            if control_value != 0
        ]
        control_mean = _mean(control_values)
        treatment_mean = _mean(treatment_values)
        absolute_change = treatment_mean - control_mean
        percent_change = None if control_mean == 0 else (absolute_change / control_mean) * 100.0
        metrics[metric] = {
            "control_mean": control_mean,
            "treatment_mean": treatment_mean,
            "absolute_change": absolute_change,
            "percent_change": percent_change,
            "trial_count": len(pairs),
            "control_values": control_values,
            "treatment_values": treatment_values,
            "paired_absolute_changes": absolute_deltas,
            "paired_percent_changes": percent_deltas,
            "paired_absolute_change_mean": _mean(absolute_deltas),
            "paired_absolute_change_stddev": _sample_standard_deviation(absolute_deltas),
            "paired_percent_change_mean": _mean(percent_deltas) if percent_deltas else None,
            "paired_percent_change_stddev": _sample_standard_deviation(percent_deltas),
            "paired_permutation_p_value": _paired_permutation_p_value(absolute_deltas),
            "statistical_alpha": _SYSTEM_PERFORMANCE_STATISTICAL_ALPHA,
        }
    return metrics


def _compare_system_metrics_from_files(control_file: Path, treatment_file: Path) -> dict[str, dict[str, Any]]:
    return _compare_system_metrics_from_trial_files([control_file], [treatment_file])


def _compare_backtest_outputs(
    *,
    output_base_dir: Path | None = None,
    control_output_base_dir: Path | None = None,
    treatment_output_base_dir: Path | None = None,
    algorithm_name: str | None = None,
    control_artifact: _BuiltRefArtifact,
    treatment_artifact: _BuiltRefArtifact,
    offline_context: dict[str, Any],
) -> dict[str, Any]:
    if output_base_dir is not None:
        control_output_base_dir = output_base_dir
        treatment_output_base_dir = output_base_dir
    if control_output_base_dir is None or treatment_output_base_dir is None:
        raise ValueError("Backtest comparison requires either output_base_dir or both per-ref output dirs.")

    control_meta_dir = control_output_base_dir / "meta"
    treatment_meta_dir = treatment_output_base_dir / "meta"
    start_date = date.fromisoformat(offline_context["backtest_date_window"]["start_date"])
    end_date = date.fromisoformat(offline_context["backtest_date_window"]["end_date"])
    selected_control_algorithm_name = (algorithm_name or "").strip() or control_artifact.artifact_name
    selected_treatment_algorithm_name = (
        selected_control_algorithm_name
        if control_artifact.artifact_name == treatment_artifact.artifact_name
        else treatment_artifact.artifact_name
    )
    control_all_records = _records_from_output_base_dir(
        output_base_dir=str(control_meta_dir),
        algorithm_name_pattern=f"^{re.escape(selected_control_algorithm_name)}$",
        algorithm_version_pattern=".*",
        from_date=start_date,
        to_date=end_date,
    )
    treatment_all_records = (
        control_all_records
        if control_meta_dir == treatment_meta_dir
        and selected_control_algorithm_name == selected_treatment_algorithm_name
        else _records_from_output_base_dir(
            output_base_dir=str(treatment_meta_dir),
            algorithm_name_pattern=f"^{re.escape(selected_treatment_algorithm_name)}$",
            algorithm_version_pattern=".*",
            from_date=start_date,
            to_date=end_date,
        )
    )
    if not control_all_records:
        raise ValueError(f"No control backtest result records found under {control_meta_dir}")
    if not treatment_all_records:
        raise ValueError(f"No treatment backtest result records found under {treatment_meta_dir}")

    control_ids = _select_backtest_algorithm_ids(
        control_all_records,
        algorithm_name=selected_control_algorithm_name,
        artifact_version=control_artifact.artifact_version,
    )
    treatment_ids = _select_backtest_algorithm_ids(
        treatment_all_records,
        algorithm_name=selected_treatment_algorithm_name,
        artifact_version=treatment_artifact.artifact_version,
    )
    control_records = [record for record in control_all_records if record["algorithm_id"] in control_ids]
    treatment_records = [record for record in treatment_all_records if record["algorithm_id"] in treatment_ids]
    if not control_records or not treatment_records:
        raise ValueError("Missing control or treatment backtest records after run.")

    control_quality = _aggregate_by_date(control_records, QUALITY_METRICS)
    treatment_quality = _aggregate_by_date(treatment_records, QUALITY_METRICS)
    control_system = _aggregate_by_date(control_records, SYSTEM_METRICS)
    treatment_system = _aggregate_by_date(treatment_records, SYSTEM_METRICS)
    expected_dates = [date.fromisoformat(day) for day in _offline_context_dates(offline_context)]
    quality_metrics = _compare_complete_qa_metrics(
        control_by_date=control_quality,
        treatment_by_date=treatment_quality,
        expected_dates=expected_dates,
        evidence_name="quality",
    )
    has_system_evidence = any(control_system.values()) or any(treatment_system.values())
    system_metrics = (
        _compare_complete_qa_metrics(
            control_by_date=control_system,
            treatment_by_date=treatment_system,
            expected_dates=expected_dates,
            evidence_name="system",
        )
        if has_system_evidence
        else {}
    )
    return {
        "control_id": _display_backtest_algorithm_id(
            control_ids,
            algorithm_name=selected_control_algorithm_name,
            artifact_version=control_artifact.artifact_version,
        ),
        "treatment_id": _display_backtest_algorithm_id(
            treatment_ids,
            algorithm_name=selected_treatment_algorithm_name,
            artifact_version=treatment_artifact.artifact_version,
        ),
        "control_algorithm_ids": control_ids,
        "treatment_algorithm_ids": treatment_ids,
        "dates_used": [day.isoformat() for day in expected_dates],
        "quality_metrics": quality_metrics,
        "quality_statistical_basis": {
            "pairing_unit": "test_date",
            "metric_estimate_field": "value",
            "source_confidence_intervals": "not_used_without_paired_covariance",
        },
        "system_metrics": system_metrics,
        "offline_quality": _representative_offline_quality(quality_metrics),
        "performance": _representative_performance(system_metrics),
    }


def _compare_complete_qa_metrics(
    *,
    control_by_date: dict[date, dict[str, float]],
    treatment_by_date: dict[date, dict[str, float]],
    expected_dates: list[date],
    evidence_name: str,
) -> dict[str, dict[str, Any]]:
    missing_control_dates = [day.isoformat() for day in expected_dates if day not in control_by_date]
    missing_treatment_dates = [day.isoformat() for day in expected_dates if day not in treatment_by_date]
    if missing_control_dates or missing_treatment_dates:
        raise ValueError(
            f"Incomplete {evidence_name} evidence: missing control dates={missing_control_dates}, "
            f"missing treatment dates={missing_treatment_dates}."
        )

    expected_metrics = sorted(
        set().union(*(set(control_by_date[day]) | set(treatment_by_date[day]) for day in expected_dates))
    )
    if not expected_metrics:
        raise ValueError(f"No {evidence_name} metrics found for the requested dates.")

    for day in expected_dates:
        missing_control = sorted(set(expected_metrics) - set(control_by_date[day]))
        missing_treatment = sorted(set(expected_metrics) - set(treatment_by_date[day]))
        if missing_control or missing_treatment:
            raise ValueError(
                f"Incomplete {evidence_name} evidence for {day.isoformat()}: "
                f"missing control metrics={missing_control}, missing treatment metrics={missing_treatment}."
            )

    comparisons: dict[str, dict[str, Any]] = {}
    for metric in expected_metrics:
        control_values = [control_by_date[day][metric] for day in expected_dates]
        treatment_values = [treatment_by_date[day][metric] for day in expected_dates]
        absolute_changes = [treatment - control for control, treatment in zip(control_values, treatment_values)]
        percent_changes = [
            ((treatment - control) / control) * 100.0
            for control, treatment in zip(control_values, treatment_values)
            if control != 0
        ]
        control_mean = _mean(control_values)
        treatment_mean = _mean(treatment_values)
        absolute_change = treatment_mean - control_mean
        percent_change = None if control_mean == 0 else (absolute_change / control_mean) * 100.0
        comparisons[metric] = {
            "dates": [day.isoformat() for day in expected_dates],
            "control_mean": control_mean,
            "treatment_mean": treatment_mean,
            "absolute_change": absolute_change,
            "percent_change": percent_change,
            "date_count": len(expected_dates),
            "control_values": control_values,
            "treatment_values": treatment_values,
            "paired_absolute_changes": absolute_changes,
            "paired_percent_changes": percent_changes,
            "paired_absolute_change_mean": _mean(absolute_changes),
            "paired_absolute_change_stddev": _sample_standard_deviation(absolute_changes),
            "paired_percent_change_mean": _mean(percent_changes) if percent_changes else None,
            "paired_percent_change_stddev": _sample_standard_deviation(percent_changes),
        }
    return comparisons


def _display_backtest_algorithm_id(
    algorithm_ids: list[str],
    *,
    algorithm_name: str,
    artifact_version: str,
) -> str:
    if len(algorithm_ids) == 1:
        return algorithm_ids[0]
    return f"{algorithm_name}@{artifact_version}"


def _select_backtest_algorithm_ids(
    records: list[dict[str, Any]],
    *,
    algorithm_name: str,
    artifact_version: str,
) -> list[str]:
    expected_id = f"{algorithm_name}@{artifact_version}"
    algorithm_ids = sorted({str(record["algorithm_id"]) for record in records})
    if expected_id in algorithm_ids:
        return [expected_id]
    prefix_matches = [
        algorithm_id
        for algorithm_id in algorithm_ids
        if algorithm_id.startswith(f"{expected_id}-") or algorithm_id.startswith(f"{expected_id}_")
    ]
    if len(prefix_matches) == 1:
        return [prefix_matches[0]]
    if len(prefix_matches) > 1:
        return _select_date_aligned_backtest_algorithm_ids(
            records,
            expected_id=expected_id,
            prefix_matches=prefix_matches,
        )
    return [_select_algorithm_id(records, expected_id)]


def _select_date_aligned_backtest_algorithm_ids(
    records: list[dict[str, Any]],
    *,
    expected_id: str,
    prefix_matches: list[str],
) -> list[str]:
    prefix_match_set = set(prefix_matches)
    ids_by_date: dict[date, set[str]] = {}
    for record in records:
        algorithm_id = str(record["algorithm_id"])
        if algorithm_id not in prefix_match_set:
            continue
        test_date = record["test_date"]
        if not isinstance(test_date, date):
            raise ValueError(f"Backtest record for '{algorithm_id}' has non-date test_date: {test_date!r}")
        ids_by_date.setdefault(test_date, set()).add(algorithm_id)

    colliding_dates = {day: sorted(ids) for day, ids in ids_by_date.items() if len(ids) > 1}
    if colliding_dates:
        raise ValueError(f"Algorithm id '{expected_id}' is ambiguous. Matches share test dates: {colliding_dates}")
    return prefix_matches


def _hv_failure_payload(exc: _HvCommandFailure) -> dict[str, Any]:
    return {
        "summary": str(exc),
        "debug": exc.to_debug_dict(),
    }


def _normalized_failure_signature_text(value: Any) -> str | None:
    if value is None:
        return None
    normalized = re.sub(r"\s+", " ", str(value)).strip()
    return normalized or None


def _failure_signature(failure_payload: dict[str, Any] | None) -> dict[str, Any]:
    failure_payload = failure_payload or {}
    debug = failure_payload.get("debug") if isinstance(failure_payload.get("debug"), dict) else {}
    command = debug.get("command") if isinstance(debug.get("command"), dict) else {}
    return {
        "command_name": _normalized_failure_signature_text(command.get("command_name")),
        "return_code": debug.get("return_code"),
        "root_cause": _normalized_failure_signature_text(debug.get("root_cause")),
        "throwable_stacktrace": _normalized_failure_signature_text(debug.get("throwable_stacktrace")),
        "summary": _normalized_failure_signature_text(failure_payload.get("summary")),
    }


def _compare_failure_payloads(
    control_failure: dict[str, Any] | None,
    treatment_failure: dict[str, Any] | None,
) -> dict[str, Any]:
    if not isinstance(control_failure, dict):
        return {
            "equivalent": False,
            "reason": "missing_control_failure",
            "mismatches": ["Control failure payload is missing."],
        }
    if not isinstance(treatment_failure, dict):
        return {
            "equivalent": False,
            "reason": "missing_treatment_failure",
            "mismatches": ["Treatment failure payload is missing."],
        }

    control_signature = _failure_signature(control_failure)
    treatment_signature = _failure_signature(treatment_failure)
    mismatches: list[str] = []
    notes: list[str] = []

    if (
        control_signature["command_name"] is not None
        and treatment_signature["command_name"] is not None
        and control_signature["command_name"] != treatment_signature["command_name"]
    ):
        mismatches.append(
            "command_name mismatch: "
            f"{control_signature['command_name']!r} vs {treatment_signature['command_name']!r}"
        )

    if (
        control_signature["return_code"] is not None
        and treatment_signature["return_code"] is not None
        and control_signature["return_code"] != treatment_signature["return_code"]
    ):
        mismatches.append(
            f"return_code mismatch: {control_signature['return_code']!r} vs {treatment_signature['return_code']!r}"
        )

    matched_on: str | None = None
    if not mismatches:
        if (
            control_signature.get("throwable_stacktrace")
            and treatment_signature.get("throwable_stacktrace")
            and control_signature["throwable_stacktrace"] == treatment_signature["throwable_stacktrace"]
        ):
            matched_on = "throwable_stacktrace"
        elif (
            control_signature.get("root_cause")
            and treatment_signature.get("root_cause")
            and control_signature["root_cause"] == treatment_signature["root_cause"]
        ):
            matched_on = "root_cause"
            if (
                control_signature.get("throwable_stacktrace")
                and treatment_signature.get("throwable_stacktrace")
                and control_signature["throwable_stacktrace"] != treatment_signature["throwable_stacktrace"]
            ):
                notes.append(
                    "throwable_stacktrace differed, but command_name, return_code, and root_cause still matched"
                )
        elif (
            control_signature.get("summary")
            and treatment_signature.get("summary")
            and control_signature["summary"] == treatment_signature["summary"]
        ):
            matched_on = "summary"

    if matched_on is None and not mismatches:
        comparable_fields = [
            field
            for field in ("root_cause", "throwable_stacktrace", "summary")
            if control_signature.get(field) and treatment_signature.get(field)
        ]
        if comparable_fields:
            primary_field = comparable_fields[0]
            mismatches.append(
                f"{primary_field} mismatch: "
                f"{control_signature[primary_field]!r} vs {treatment_signature[primary_field]!r}"
            )
        else:
            mismatches.append("No comparable failure signature was available on both sides.")

    return {
        "equivalent": not mismatches,
        "reason": "matched_failure" if not mismatches else "failure_mismatch",
        "matched_on": matched_on,
        "control_signature": control_signature,
        "treatment_signature": treatment_signature,
        "mismatches": mismatches,
        "notes": notes,
    }


def _record_failure_equivalence(
    *,
    control_payload: dict[str, Any],
    treatment_payload: dict[str, Any],
    comparison_path: Path,
) -> dict[str, Any]:
    control_complete = bool((control_payload.get("status") or {}).get("jobs_complete"))
    treatment_complete = bool((treatment_payload.get("status") or {}).get("jobs_complete"))
    if control_complete:
        raise ValueError("Failure equivalence can only be recorded when the control side did not complete.")

    if treatment_complete:
        comparison = {
            "equivalent": False,
            "reason": "treatment_completed_while_control_failed",
            "mismatches": ["Treatment completed successfully while the control failed."],
        }
    else:
        comparison = _compare_failure_payloads(control_payload.get("failure"), treatment_payload.get("failure"))

    comparison_path.parent.mkdir(parents=True, exist_ok=True)
    comparison_path.write_text(json.dumps(comparison, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    treatment_payload.setdefault("status", {})["matched_control_failure"] = bool(comparison.get("equivalent"))
    treatment_payload.setdefault("artifacts", {})["failure_comparison_path"] = str(comparison_path)
    treatment_payload["metrics"] = {
        **dict(treatment_payload.get("metrics") or {}),
        "matched_control_failure": bool(comparison.get("equivalent")),
    }
    return comparison


def _execute_audit_parity_stage(
    *,
    run_dir: Path,
    scenario: dict[str, Any],
    artifact_cache: dict[str, _BuiltRefArtifact],
) -> dict[str, Any]:
    stage_dir = _stage_dir(run_dir, "audit_parity")
    stage_dir.mkdir(parents=True, exist_ok=True)
    execution_context = scenario["execution_context"]
    control_ref = scenario["refs"]["control"]["git_ref"]
    control_algorithm_name = _encode_algorithm_name_for_ref(scenario=scenario, git_ref=control_ref, role="control")
    control_artifact = _ensure_ref_artifact(
        cache=artifact_cache, run_dir=run_dir, scenario=scenario, git_ref=control_ref, role="control"
    )
    definition_cache: dict[tuple[str, str], dict[str, Any]] = {}
    control_definition = _read_algorithm_definition_from_artifact(
        artifact=control_artifact,
        algorithm_name=control_algorithm_name,
        cache=definition_cache,
    )
    expected_parameter_version = str(control_definition.get("algorithm_version") or control_artifact.artifact_version)
    parameter_path, parameter_inspection = _resolve_validated_parameter_source(
        run_dir=run_dir,
        scenario=scenario,
        expected_algorithm_name=control_algorithm_name,
        expected_algorithm_version=expected_parameter_version,
    )
    source_path = _localize_sampled_input_content(
        execution_context["encode_source_path"],
        cache_dir=stage_dir / "input_cache",
        sample_count=_PARITY_STAGE_SAMPLES,
    )
    if not source_path:
        raise ValueError("audit_parity requires encode_source_path and parameter_source.")

    control_dir = stage_dir / _safe_ref_slug(control_ref)
    control_dir.mkdir(parents=True, exist_ok=True)
    control_audit_path = control_dir / "audit.jsonl"
    control_payload: dict[str, Any] = {
        "git_ref": control_ref,
        "algorithm_name": control_artifact.artifact_name,
        "encode_algorithm_name": control_algorithm_name,
        "algorithm_version": control_artifact.artifact_version,
        "status": {
            "jobs_complete": False,
        },
        "artifacts": {
            "audit_path": str(control_audit_path),
        },
    }
    control_succeeded = False
    try:
        _run_hv(
            args=[
                "audit",
                "--algorithm-jar",
                str(control_artifact.jar_path),
                "--algorithm-name",
                control_algorithm_name,
                "--parameter-path",
                parameter_path,
                "--source-path",
                source_path,
                "--dest-path",
                str(control_audit_path),
                "--metadata-path",
                str(control_dir / "metadata"),
                "--samples",
                str(_PARITY_STAGE_SAMPLES),
            ],
            log_path=control_dir / "command.log",
        )
        control_payload["status"]["jobs_complete"] = True
        control_succeeded = True
    except _HvCommandFailure as exc:
        control_payload["failure"] = _hv_failure_payload(exc)

    treatments_payload: list[dict[str, Any]] = []
    for treatment in scenario["refs"]["treatments"]:
        git_ref = treatment["git_ref"]
        treatment_algorithm_name = _encode_algorithm_name_for_ref(scenario=scenario, git_ref=git_ref, role="treatment")
        treatment_artifact = _ensure_ref_artifact(
            cache=artifact_cache, run_dir=run_dir, scenario=scenario, git_ref=git_ref, role="treatment"
        )
        treatment_root_algorithm_name = treatment_artifact.artifact_name
        treatment_dir = stage_dir / _safe_ref_slug(git_ref)
        treatment_dir.mkdir(parents=True, exist_ok=True)
        treatment_audit_path = treatment_dir / "audit.jsonl"
        treatment_payload: dict[str, Any] = {
            "git_ref": git_ref,
            "algorithm_name": treatment_root_algorithm_name,
            "encode_algorithm_name": treatment_algorithm_name,
            "algorithm_version": treatment_artifact.artifact_version,
            "status": {
                "jobs_complete": False,
            },
            "artifacts": {
                "audit_path": str(treatment_audit_path),
            },
        }
        try:
            _run_hv(
                args=[
                    "audit",
                    "--algorithm-jar",
                    str(treatment_artifact.jar_path),
                    "--algorithm-name",
                    treatment_algorithm_name,
                    "--parameter-path",
                    parameter_path,
                    "--source-path",
                    source_path,
                    "--dest-path",
                    str(treatment_audit_path),
                    "--metadata-path",
                    str(treatment_dir / "metadata"),
                    "--samples",
                    str(_PARITY_STAGE_SAMPLES),
                ],
                log_path=treatment_dir / "command.log",
            )
            treatment_payload["status"]["jobs_complete"] = True
        except _HvCommandFailure as exc:
            treatment_payload["failure"] = _hv_failure_payload(exc)
            if control_succeeded:
                treatment_payload["status"]["output_equivalent"] = False
            else:
                _record_failure_equivalence(
                    control_payload=control_payload,
                    treatment_payload=treatment_payload,
                    comparison_path=treatment_dir / "failure-comparison.json",
                )
            treatments_payload.append(treatment_payload)
            continue

        if control_succeeded:
            comparison_dir = stage_dir / "comparison" / _safe_ref_slug(git_ref)
            comparison = _compare_audit_jsonl_artifacts(control_audit_path, treatment_audit_path, comparison_dir)
            comparison_path = comparison_dir / "comparison.json"
            comparison_path.write_text(json.dumps(comparison, indent=2, sort_keys=True) + "\n", encoding="utf-8")
            treatment_payload["status"]["output_equivalent"] = bool(comparison.get("identical"))
            treatment_payload["metrics"] = {
                "processed_lines": comparison.get("processed_lines"),
                "identical_lines": comparison.get("identical_lines"),
                "different_lines": comparison.get("different_lines"),
                "fields_with_difference": comparison.get("fields_with_difference"),
            }
            treatment_payload["artifacts"]["comparison_path"] = str(comparison_path)
        else:
            _record_failure_equivalence(
                control_payload=control_payload,
                treatment_payload=treatment_payload,
                comparison_path=treatment_dir / "failure-comparison.json",
            )
            treatment_payload["notes"] = [
                f"Control ref '{control_ref}' failed, so hv-qa compared failure behavior instead of outputs."
            ]
        treatments_payload.append(treatment_payload)

    payload = {
        "scenario": "qa",
        "stage": "audit_parity",
        "parameter_source": scenario["parameter_source"],
        "context": {
            "run_id": scenario["run_id"],
            "algorithm_name": control_algorithm_name,
            "control_encode_algorithm_name": control_algorithm_name,
            "encode_algorithm_names": _normalize_encode_algorithm_names(scenario.get("encode_algorithm_names")),
            "source_path": source_path,
            "sample_count": _PARITY_STAGE_SAMPLES,
            "parameter_source": scenario.get("parameter_source"),
            "parameter_algorithm_version": parameter_inspection.algorithm_version,
        },
        "control": control_payload,
        "treatments": treatments_payload,
    }
    policy = load_builtin_policy(scenario["criteria"]["id"])
    judgment = policy.evaluate(payload)
    (stage_dir / "payload.json").write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (stage_dir / "judgment.json").write_text(
        json.dumps(judgment, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return {
        "stage": "audit_parity",
        "state": _stage_status_from_judgment(judgment["stage_judgment"]),
        "stage_judgment": judgment["stage_judgment"],
        "summary": judgment.get("summary"),
        "rollout_recommendation": judgment.get("rollout_recommendation"),
        "control": control_payload,
        "treatments": treatments_payload,
        "payload_path": str(stage_dir / "payload.json"),
        "judgment_path": str(stage_dir / "judgment.json"),
    }


def _execute_encode_parity_stage(
    *,
    run_dir: Path,
    scenario: dict[str, Any],
    artifact_cache: dict[str, _BuiltRefArtifact],
) -> dict[str, Any]:
    stage_dir = _stage_dir(run_dir, "encode_parity")
    stage_dir.mkdir(parents=True, exist_ok=True)
    execution_context = scenario["execution_context"]
    control_ref = scenario["refs"]["control"]["git_ref"]
    control_algorithm_name = _encode_algorithm_name_for_ref(scenario=scenario, git_ref=control_ref, role="control")
    control_artifact = _ensure_ref_artifact(
        cache=artifact_cache, run_dir=run_dir, scenario=scenario, git_ref=control_ref, role="control"
    )
    definition_cache: dict[tuple[str, str], dict[str, Any]] = {}
    control_definition = _read_algorithm_definition_from_artifact(
        artifact=control_artifact,
        algorithm_name=control_algorithm_name,
        cache=definition_cache,
    )
    control_output_ordering = _encode_default_output_ordering(control_definition)
    expected_parameter_version = str(control_definition.get("algorithm_version") or control_artifact.artifact_version)
    parameter_path = None
    parameter_inspection = None
    if scenario.get("parameter_source"):
        parameter_path, parameter_inspection = _resolve_validated_parameter_source(
            run_dir=run_dir,
            scenario=scenario,
            expected_algorithm_name=control_algorithm_name,
            expected_algorithm_version=expected_parameter_version,
        )

    source_path = _localize_sampled_input_content(
        execution_context["encode_source_path"],
        cache_dir=stage_dir / "input_cache",
        sample_count=_PARITY_STAGE_SAMPLES,
    )
    if not source_path:
        raise ValueError("encode_parity requires encode_source_path.")

    control_dir = stage_dir / _safe_ref_slug(control_ref)
    control_dir.mkdir(parents=True, exist_ok=True)
    control_encode_path = control_dir / "encoded.tsv"
    control_schema_path = control_dir / "encoded-schema-description.json"
    control_args = [
        "encode",
        "--algorithm-jar",
        str(control_artifact.jar_path),
        "--algorithm-name",
        control_algorithm_name,
        "--source-path",
        source_path,
        "--dest-path",
        str(control_encode_path),
        "--dest-schema-path",
        str(control_schema_path),
        "--metadata-path",
        str(control_dir / "metadata"),
        "--samples",
        str(_PARITY_STAGE_SAMPLES),
        f"--{control_output_ordering}",
    ]
    if parameter_path:
        control_args.extend(["--parameter-path", parameter_path])
    control_payload: dict[str, Any] = {
        "git_ref": control_ref,
        "algorithm_name": control_artifact.artifact_name,
        "encode_algorithm_name": control_algorithm_name,
        "algorithm_version": control_artifact.artifact_version,
        "output_ordering": control_output_ordering,
        "status": {
            "jobs_complete": False,
        },
        "artifacts": {
            "encode_path": str(control_encode_path),
            "schema_path": str(control_schema_path),
        },
    }
    control_succeeded = False
    try:
        _run_hv(args=control_args, log_path=control_dir / "command.log")
        control_payload["status"]["jobs_complete"] = True
        control_succeeded = True
    except _HvCommandFailure as exc:
        control_payload["failure"] = _hv_failure_payload(exc)

    treatments_payload: list[dict[str, Any]] = []
    for treatment in scenario["refs"]["treatments"]:
        git_ref = treatment["git_ref"]
        treatment_algorithm_name = _encode_algorithm_name_for_ref(scenario=scenario, git_ref=git_ref, role="treatment")
        treatment_artifact = _ensure_ref_artifact(
            cache=artifact_cache, run_dir=run_dir, scenario=scenario, git_ref=git_ref, role="treatment"
        )
        treatment_definition = _read_algorithm_definition_from_artifact(
            artifact=treatment_artifact,
            algorithm_name=treatment_algorithm_name,
            cache=definition_cache,
        )
        treatment_output_ordering = _encode_default_output_ordering(treatment_definition)
        treatment_dir = stage_dir / _safe_ref_slug(git_ref)
        treatment_dir.mkdir(parents=True, exist_ok=True)
        treatment_encode_path = treatment_dir / "encoded.tsv"
        treatment_schema_path = treatment_dir / "encoded-schema-description.json"
        treatment_args = [
            "encode",
            "--algorithm-jar",
            str(treatment_artifact.jar_path),
            "--algorithm-name",
            treatment_algorithm_name,
            "--source-path",
            source_path,
            "--dest-path",
            str(treatment_encode_path),
            "--dest-schema-path",
            str(treatment_schema_path),
            "--metadata-path",
            str(treatment_dir / "metadata"),
            "--samples",
            str(_PARITY_STAGE_SAMPLES),
            f"--{treatment_output_ordering}",
        ]
        if parameter_path:
            treatment_args.extend(["--parameter-path", parameter_path])
        treatment_payload: dict[str, Any] = {
            "git_ref": git_ref,
            "algorithm_name": treatment_artifact.artifact_name,
            "encode_algorithm_name": treatment_algorithm_name,
            "algorithm_version": treatment_artifact.artifact_version,
            "output_ordering": treatment_output_ordering,
            "status": {
                "jobs_complete": False,
            },
            "artifacts": {
                "encode_path": str(treatment_encode_path),
                "schema_path": str(treatment_schema_path),
            },
        }
        try:
            _run_hv(args=treatment_args, log_path=treatment_dir / "command.log")
            treatment_payload["status"]["jobs_complete"] = True
        except _HvCommandFailure as exc:
            treatment_payload["failure"] = _hv_failure_payload(exc)
            if control_succeeded:
                treatment_payload["status"]["output_equivalent"] = False
            else:
                _record_failure_equivalence(
                    control_payload=control_payload,
                    treatment_payload=treatment_payload,
                    comparison_path=treatment_dir / "failure-comparison.json",
                )
            treatments_payload.append(treatment_payload)
            continue

        if control_succeeded:
            comparison_dir = stage_dir / "comparison" / _safe_ref_slug(git_ref)
            comparison_dir.mkdir(parents=True, exist_ok=True)
            comparison_payload = {
                "encoded": _compare_encoded_tsv_artifacts_unordered(
                    control_encode_path,
                    treatment_encode_path,
                    control_schema_path,
                ),
                "schema": _compare_artifact_files(control_schema_path, treatment_schema_path),
            }
            output_equivalent = comparison_payload["encoded"]["identical"] and comparison_payload["schema"]["identical"]
            (comparison_dir / "comparison.json").write_text(
                json.dumps(comparison_payload, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            treatment_payload["status"]["output_equivalent"] = output_equivalent
            treatment_payload["metrics"] = {
                "encoded_identical": comparison_payload["encoded"]["identical"],
                "schema_identical": comparison_payload["schema"]["identical"],
                "encoded_control_size_bytes": comparison_payload["encoded"]["control_size_bytes"],
                "encoded_treatment_size_bytes": comparison_payload["encoded"]["treatment_size_bytes"],
            }
            treatment_payload["artifacts"]["comparison_path"] = str(comparison_dir / "comparison.json")
        else:
            _record_failure_equivalence(
                control_payload=control_payload,
                treatment_payload=treatment_payload,
                comparison_path=treatment_dir / "failure-comparison.json",
            )
            treatment_payload["notes"] = [
                f"Control ref '{control_ref}' failed, so hv-qa compared failure behavior instead of outputs."
            ]
        treatments_payload.append(treatment_payload)

    payload = {
        "scenario": "qa",
        "stage": "encode_parity",
        "parameter_source": scenario.get("parameter_source"),
        "context": {
            "run_id": scenario["run_id"],
            "algorithm_name": control_algorithm_name,
            "control_encode_algorithm_name": control_algorithm_name,
            "encode_algorithm_names": _normalize_encode_algorithm_names(scenario.get("encode_algorithm_names")),
            "source_path": source_path,
            "sample_count": _PARITY_STAGE_SAMPLES,
            "output_ordering": "explicit_per_ref_algorithm_default",
            "parameter_source": scenario.get("parameter_source"),
            "parameter_algorithm_version": parameter_inspection.algorithm_version if parameter_inspection else None,
        },
        "control": control_payload,
        "treatments": treatments_payload,
    }
    policy = load_builtin_policy(scenario["criteria"]["id"])
    judgment = policy.evaluate(payload)
    (stage_dir / "payload.json").write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (stage_dir / "judgment.json").write_text(
        json.dumps(judgment, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return {
        "stage": "encode_parity",
        "state": _stage_status_from_judgment(judgment["stage_judgment"]),
        "stage_judgment": judgment["stage_judgment"],
        "summary": judgment.get("summary"),
        "rollout_recommendation": judgment.get("rollout_recommendation"),
        "control": control_payload,
        "treatments": treatments_payload,
        "payload_path": str(stage_dir / "payload.json"),
        "judgment_path": str(stage_dir / "judgment.json"),
    }


def _execute_predict_parity_stage(
    *,
    run_dir: Path,
    scenario: dict[str, Any],
    artifact_cache: dict[str, _BuiltRefArtifact],
) -> dict[str, Any]:
    stage_dir = _stage_dir(run_dir, "predict_parity")
    stage_dir.mkdir(parents=True, exist_ok=True)
    execution_context = scenario["execution_context"]
    algorithm_name = str(execution_context["algorithm_name"])
    control_ref = scenario["refs"]["control"]["git_ref"]
    control_artifact = _ensure_ref_artifact(
        cache=artifact_cache, run_dir=run_dir, scenario=scenario, git_ref=control_ref, role="control"
    )
    definition_cache: dict[tuple[str, str], dict[str, Any]] = {}
    control_definition = _read_algorithm_definition_from_artifact(
        artifact=control_artifact,
        algorithm_name=algorithm_name,
        cache=definition_cache,
    )
    expected_parameter_version = str(control_definition.get("algorithm_version") or control_artifact.artifact_version)
    parameter_path, parameter_inspection = _resolve_validated_parameter_source(
        run_dir=run_dir,
        scenario=scenario,
        expected_algorithm_name=algorithm_name,
        expected_algorithm_version=expected_parameter_version,
    )
    source_path = _localize_sampled_input_content(
        execution_context["predict_source_path"],
        cache_dir=stage_dir / "input_cache",
        sample_count=_PARITY_STAGE_SAMPLES,
    )
    if not source_path:
        raise ValueError("predict_parity requires predict_source_path and parameter_source.")

    control_dir = stage_dir / _safe_ref_slug(control_ref)
    control_dir.mkdir(parents=True, exist_ok=True)
    control_predict_dir = control_dir / "predict"
    control_normalized_predict_path: Path | None = None
    control_normalization: dict[str, Any] | None = None
    control_payload: dict[str, Any] = {
        "git_ref": control_ref,
        "algorithm_name": control_artifact.artifact_name,
        "algorithm_version": control_artifact.artifact_version,
        "status": {
            "jobs_complete": False,
        },
        "artifacts": {
            "predict_dir": str(control_predict_dir),
        },
    }
    try:
        _run_hv(
            args=[
                "predict",
                "--algorithm-jar",
                str(control_artifact.jar_path),
                "--algorithm-name",
                algorithm_name,
                "--parameter-path",
                parameter_path,
                "--source-path",
                source_path,
                "--dest-path",
                str(control_predict_dir),
                "--metadata-path",
                str(control_dir / "metadata"),
                "--samples",
                str(_PARITY_STAGE_SAMPLES),
            ],
            log_path=control_dir / "command.log",
        )
        control_normalization = _normalize_prediction_output(
            control_predict_dir,
            control_dir / "predict.normalized.jsonl",
        )
        control_normalized_predict_path = Path(control_normalization["normalized_path"])
        control_payload["status"]["jobs_complete"] = True
        control_payload["artifacts"].update(
            {
                "predict_path": str(control_normalized_predict_path),
                "normalized_predict_path": str(control_normalized_predict_path),
                "predict_output_files": list(control_normalization["input_files"]),
                "record_count": control_normalization["record_count"],
            }
        )
    except _HvCommandFailure as exc:
        control_payload["failure"] = _hv_failure_payload(exc)

    treatments_payload: list[dict[str, Any]] = []
    for treatment in scenario["refs"]["treatments"]:
        git_ref = treatment["git_ref"]
        treatment_artifact = _ensure_ref_artifact(
            cache=artifact_cache, run_dir=run_dir, scenario=scenario, git_ref=git_ref, role="treatment"
        )
        treatment_algorithm_name = treatment_artifact.artifact_name
        treatment_dir = stage_dir / _safe_ref_slug(git_ref)
        treatment_dir.mkdir(parents=True, exist_ok=True)
        treatment_predict_dir = treatment_dir / "predict"
        treatment_payload: dict[str, Any] = {
            "git_ref": git_ref,
            "algorithm_name": treatment_algorithm_name,
            "algorithm_version": treatment_artifact.artifact_version,
            "status": {
                "jobs_complete": False,
            },
            "artifacts": {
                "predict_dir": str(treatment_predict_dir),
            },
        }
        try:
            _run_hv(
                args=[
                    "predict",
                    "--algorithm-jar",
                    str(treatment_artifact.jar_path),
                    "--algorithm-name",
                    treatment_algorithm_name,
                    "--parameter-path",
                    parameter_path,
                    "--source-path",
                    source_path,
                    "--dest-path",
                    str(treatment_predict_dir),
                    "--metadata-path",
                    str(treatment_dir / "metadata"),
                    "--samples",
                    str(_PARITY_STAGE_SAMPLES),
                ],
                log_path=treatment_dir / "command.log",
            )
            treatment_normalization = _normalize_prediction_output(
                treatment_predict_dir,
                treatment_dir / "predict.normalized.jsonl",
            )
            treatment_payload["status"]["jobs_complete"] = True
            treatment_payload["artifacts"].update(
                {
                    "predict_path": str(treatment_normalization["normalized_path"]),
                    "normalized_predict_path": str(treatment_normalization["normalized_path"]),
                    "predict_output_files": list(treatment_normalization["input_files"]),
                    "record_count": treatment_normalization["record_count"],
                }
            )
        except _HvCommandFailure as exc:
            treatment_payload["failure"] = _hv_failure_payload(exc)
            if control_normalized_predict_path is not None:
                treatment_payload["status"]["output_equivalent"] = False
            else:
                _record_failure_equivalence(
                    control_payload=control_payload,
                    treatment_payload=treatment_payload,
                    comparison_path=treatment_dir / "failure-comparison.json",
                )
            treatments_payload.append(treatment_payload)
            continue

        if control_normalized_predict_path is not None:
            comparison_payload = compare_predict_jsonl(
                baseline_file=str(control_normalized_predict_path),
                treatment_file=str(treatment_normalization["normalized_path"]),
                score_eps=1e-6,
                allow_non_deterministic_tie_breaking=False,
            )
            comparison_payload["normalization"] = {
                "control": control_normalization,
                "treatment": treatment_normalization,
            }
            comparison_path = treatment_dir / "comparison.json"
            comparison_path.write_text(
                json.dumps(comparison_payload, indent=2, sort_keys=True) + "\n", encoding="utf-8"
            )
            treatment_payload["status"]["output_equivalent"] = comparison_payload.get("status") == "passed"
            treatment_payload["metrics"] = {
                "processed_lines": comparison_payload.get("processed_lines"),
                "score_mismatch_count": comparison_payload.get("score", {}).get("mismatch_count"),
                "rank_mismatch_count": comparison_payload.get("rank", {}).get("mismatch_count"),
            }
            treatment_payload["artifacts"]["comparison_path"] = str(comparison_path)
        else:
            _record_failure_equivalence(
                control_payload=control_payload,
                treatment_payload=treatment_payload,
                comparison_path=treatment_dir / "failure-comparison.json",
            )
            treatment_payload["notes"] = [
                f"Control ref '{control_ref}' failed, so hv-qa compared failure behavior instead of outputs."
            ]
        treatments_payload.append(treatment_payload)

    payload = {
        "scenario": "qa",
        "stage": "predict_parity",
        "parameter_source": scenario["parameter_source"],
        "context": {
            "run_id": scenario["run_id"],
            "source_path": source_path,
            "sample_count": _PARITY_STAGE_SAMPLES,
            "parameter_algorithm_version": parameter_inspection.algorithm_version,
        },
        "control": control_payload,
        "treatments": treatments_payload,
    }
    policy = load_builtin_policy(scenario["criteria"]["id"])
    judgment = policy.evaluate(payload)
    (stage_dir / "payload.json").write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (stage_dir / "judgment.json").write_text(
        json.dumps(judgment, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return {
        "stage": "predict_parity",
        "state": _stage_status_from_judgment(judgment["stage_judgment"]),
        "stage_judgment": judgment["stage_judgment"],
        "summary": judgment.get("summary"),
        "rollout_recommendation": judgment.get("rollout_recommendation"),
        "control": control_payload,
        "treatments": treatments_payload,
        "payload_path": str(stage_dir / "payload.json"),
        "judgment_path": str(stage_dir / "judgment.json"),
    }


def _execute_system_performance_stage(
    *,
    run_dir: Path,
    scenario: dict[str, Any],
    artifact_cache: dict[str, _BuiltRefArtifact],
) -> dict[str, Any]:
    stage_dir = _stage_dir(run_dir, "system_performance")
    stage_dir.mkdir(parents=True, exist_ok=True)
    execution_context = scenario["execution_context"]
    system_performance_options = dict((scenario.get("stage_options") or {}).get("system_performance") or {})
    options = _SystemPerformanceOptions.from_mapping(system_performance_options)
    algorithm_name = str(execution_context["algorithm_name"])
    control_ref = scenario["refs"]["control"]["git_ref"]
    definition_cache: dict[tuple[str, str], dict[str, Any]] = {}
    control_artifact = _ensure_ref_artifact(
        cache=artifact_cache, run_dir=run_dir, scenario=scenario, git_ref=control_ref, role="control"
    )
    control_definition = _read_algorithm_definition_from_artifact(
        artifact=control_artifact,
        algorithm_name=algorithm_name,
        cache=definition_cache,
    )
    control_performance_spec = _PerformanceTestSpec.from_algorithm_definition(control_definition, git_ref=control_ref)
    treatment_artifacts: dict[str, _BuiltRefArtifact] = {}
    treatment_definitions: dict[str, dict[str, Any]] = {}
    treatment_performance_specs: dict[str, _PerformanceTestSpec] = {}
    for treatment in scenario["refs"]["treatments"]:
        git_ref = treatment["git_ref"]
        treatment_artifact = _ensure_ref_artifact(
            cache=artifact_cache, run_dir=run_dir, scenario=scenario, git_ref=git_ref, role="treatment"
        )
        treatment_algorithm_name = treatment_artifact.artifact_name
        treatment_definition = _read_algorithm_definition_from_artifact(
            artifact=treatment_artifact,
            algorithm_name=treatment_algorithm_name,
            cache=definition_cache,
        )
        treatment_performance_specs[git_ref] = _resolve_matched_performance_spec(
            control_ref=control_ref,
            control_definition=control_definition,
            treatment_ref=git_ref,
            treatment_definition=treatment_definition,
        )
        treatment_artifacts[git_ref] = treatment_artifact
        treatment_definitions[git_ref] = treatment_definition
    options = _resolve_system_performance_options_from_spec(
        configured_options=options,
        specification=control_performance_spec,
    )
    per_ref_source_paths = _normalize_performance_source_paths(scenario.get("performance_source_paths"))
    runner = _resolve_system_performance_runner(
        source_path=_performance_source_for_ref(
            scenario=scenario,
            git_ref=control_ref,
            role="control",
        ),
        system_performance_options=system_performance_options,
    )
    sample_count = int(options.samples)
    trial_count = int(options.trials or _DEFAULT_SYSTEM_PERFORMANCE_TRIALS)
    control_source_spec = _performance_source_for_ref(scenario=scenario, git_ref=control_ref, role="control")
    expected_parameter_version = str(control_definition.get("algorithm_version") or control_artifact.artifact_version)
    per_ref_parameter_sources = _normalize_parameter_sources(scenario.get("parameter_sources"))
    control_parameter_source = _parameter_source_for_ref(scenario=scenario, git_ref=control_ref, role="control")
    control_dir = stage_dir / _safe_ref_slug(control_ref)
    control_dir.mkdir(parents=True, exist_ok=True)
    parameter_inspection = None
    control_perf_files: list[Path] = []
    control_remote_artifacts: list[dict[str, Any]] = []
    control_remote_futures: list[Future[dict[str, Any]]] = []
    remote_executor: ThreadPoolExecutor | None = None
    if runner == "local":
        parameter_path, parameter_inspection = _resolve_validated_parameter_source_for_ref(
            run_dir=run_dir,
            scenario=scenario,
            git_ref=control_ref,
            role="control",
            expected_algorithm_name=algorithm_name,
            expected_algorithm_version=expected_parameter_version,
        )
        control_source_path = _localize_sampled_input_content(
            control_source_spec,
            cache_dir=control_dir / "input_cache",
            sample_count=sample_count,
        )
        if not control_source_path:
            raise ValueError("system_performance requires performance_source_path and parameter_source.")

        control_base_args = [
            "performance-test",
            "--algorithm-jar",
            str(control_artifact.jar_path),
            "--algorithm-name",
            algorithm_name,
            "--parameter-path",
            parameter_path,
            "--source-path",
            control_source_path,
            "--samples",
            str(sample_count),
        ]
        control_base_args.extend(
            options.to_hv_performance_test_args(include_remote_options=False, include_samples=False)
        )
        for trial_index in range(1, trial_count + 1):
            trial_dir = _trial_dir(control_dir, trial_index, trial_count)
            trial_dir.mkdir(parents=True, exist_ok=True)
            metadata_dir = trial_dir / "metadata"
            metadata_path = metadata_dir / "metadata.json"
            if metadata_path.exists():
                control_perf_files.append(metadata_path)
                continue
            _run_hv(
                args=[*control_base_args, "--metadata-path", str(metadata_dir)],
                log_path=trial_dir / "command.log",
            )
            control_perf_files.append(metadata_path)
    else:
        if not control_source_spec or not _is_s3_uri(control_source_spec):
            raise ValueError("Remote system_performance currently requires an s3:// performance source path.")
        if not _is_s3_uri(control_parameter_source):
            _, parameter_inspection = _resolve_validated_parameter_source_for_ref(
                run_dir=run_dir,
                scenario=scenario,
                git_ref=control_ref,
                role="control",
                expected_algorithm_name=algorithm_name,
                expected_algorithm_version=expected_parameter_version,
            )
        parameter_s3_uri = _ensure_remote_parameter_source(
            parameter_source=control_parameter_source,
            run_id=str(scenario["run_id"]),
            stage_dir=stage_dir,
            system_performance_options=system_performance_options,
            staging_subdir=_safe_ref_slug(control_ref) if per_ref_parameter_sources else None,
        )
        remote_executor = ThreadPoolExecutor(max_workers=trial_count * (1 + len(scenario["refs"]["treatments"])))
        for trial_index in range(1, trial_count + 1):
            trial_dir = _trial_dir(control_dir, trial_index, trial_count)
            trial_dir.mkdir(parents=True, exist_ok=True)
            metadata_path = trial_dir / "metadata" / "metadata.json"
            if metadata_path.exists():
                control_remote_artifacts.append(_existing_system_performance_remote_artifacts(trial_dir, metadata_path))
                control_perf_files.append(metadata_path)
                continue
            control_remote_futures.append(
                remote_executor.submit(
                    _run_remote_system_performance_job,
                    git_ref=control_ref,
                    artifact=control_artifact,
                    algorithm_name=algorithm_name,
                    source_s3_uri=str(control_source_spec),
                    parameter_s3_uri=parameter_s3_uri,
                    stage_dir=trial_dir,
                    system_performance_options=system_performance_options,
                )
            )
        control_source_path = str(control_source_spec)
    if runner == "local":
        for control_perf_file in control_perf_files:
            if not control_perf_file.exists():
                raise FileNotFoundError(f"Missing control performance metadata: {control_perf_file}")

    treatment_runs: list[dict[str, Any]] = []
    for treatment in scenario["refs"]["treatments"]:
        git_ref = treatment["git_ref"]
        treatment_source_spec = _performance_source_for_ref(scenario=scenario, git_ref=git_ref, role="treatment")
        treatment_artifact = treatment_artifacts[git_ref]
        treatment_algorithm_name = treatment_artifact.artifact_name
        treatment_parameter_path = parameter_path if runner == "local" else None
        treatment_parameter_s3_uri = parameter_s3_uri if runner != "local" else None
        treatment_parameter_inspection = parameter_inspection
        if per_ref_parameter_sources:
            treatment_definition = treatment_definitions[git_ref]
            expected_treatment_parameter_version = str(
                treatment_definition.get("algorithm_version") or treatment_artifact.artifact_version
            )
            treatment_parameter_source = _parameter_source_for_ref(scenario=scenario, git_ref=git_ref, role="treatment")
            if runner == "local":
                treatment_parameter_path, treatment_parameter_inspection = _resolve_validated_parameter_source_for_ref(
                    run_dir=run_dir,
                    scenario=scenario,
                    git_ref=git_ref,
                    role="treatment",
                    expected_algorithm_name=treatment_algorithm_name,
                    expected_algorithm_version=expected_treatment_parameter_version,
                )
            else:
                if not _is_s3_uri(treatment_parameter_source):
                    _, treatment_parameter_inspection = _resolve_validated_parameter_source_for_ref(
                        run_dir=run_dir,
                        scenario=scenario,
                        git_ref=git_ref,
                        role="treatment",
                        expected_algorithm_name=treatment_algorithm_name,
                        expected_algorithm_version=expected_treatment_parameter_version,
                    )
                treatment_parameter_s3_uri = _ensure_remote_parameter_source(
                    parameter_source=treatment_parameter_source,
                    run_id=str(scenario["run_id"]),
                    stage_dir=stage_dir,
                    system_performance_options=system_performance_options,
                    staging_subdir=_safe_ref_slug(git_ref),
                )
        treatment_dir = stage_dir / _safe_ref_slug(git_ref)
        treatment_dir.mkdir(parents=True, exist_ok=True)
        treatment_perf_files: list[Path] = []
        remote_artifacts: list[dict[str, Any]] = []
        treatment_remote_futures: list[Future[dict[str, Any]]] = []
        if runner == "local":
            if not treatment_parameter_path:
                raise ValueError(f"Missing local parameter path for treatment {git_ref}.")
            treatment_source_path = _localize_sampled_input_content(
                treatment_source_spec,
                cache_dir=treatment_dir / "input_cache",
                sample_count=sample_count,
            )
            if not treatment_source_path:
                raise ValueError(f"Missing local performance source path for treatment {git_ref}.")
            treatment_base_args = [
                "performance-test",
                "--algorithm-jar",
                str(treatment_artifact.jar_path),
                "--algorithm-name",
                treatment_algorithm_name,
                "--parameter-path",
                treatment_parameter_path,
                "--source-path",
                treatment_source_path,
                "--samples",
                str(sample_count),
            ]
            treatment_base_args.extend(
                options.to_hv_performance_test_args(include_remote_options=False, include_samples=False)
            )
            for trial_index in range(1, trial_count + 1):
                trial_dir = _trial_dir(treatment_dir, trial_index, trial_count)
                trial_dir.mkdir(parents=True, exist_ok=True)
                metadata_dir = trial_dir / "metadata"
                metadata_path = metadata_dir / "metadata.json"
                if metadata_path.exists():
                    treatment_perf_files.append(metadata_path)
                    continue
                _run_hv(
                    args=[*treatment_base_args, "--metadata-path", str(metadata_dir)],
                    log_path=trial_dir / "command.log",
                )
                treatment_perf_files.append(metadata_path)
        else:
            if not treatment_parameter_s3_uri:
                raise ValueError(f"Missing remote parameter source for treatment {git_ref}.")
            if not _is_s3_uri(treatment_source_spec):
                raise ValueError(
                    f"Remote system_performance currently requires an s3:// performance source path for {git_ref}."
                )
            for trial_index in range(1, trial_count + 1):
                trial_dir = _trial_dir(treatment_dir, trial_index, trial_count)
                trial_dir.mkdir(parents=True, exist_ok=True)
                metadata_path = trial_dir / "metadata" / "metadata.json"
                if metadata_path.exists():
                    remote_artifacts.append(_existing_system_performance_remote_artifacts(trial_dir, metadata_path))
                    treatment_perf_files.append(metadata_path)
                    continue
                if remote_executor is None:
                    raise RuntimeError("Remote system-performance executor was not initialized.")
                treatment_remote_futures.append(
                    remote_executor.submit(
                        _run_remote_system_performance_job,
                        git_ref=git_ref,
                        artifact=treatment_artifact,
                        algorithm_name=treatment_algorithm_name,
                        source_s3_uri=str(treatment_source_spec),
                        parameter_s3_uri=str(treatment_parameter_s3_uri),
                        stage_dir=trial_dir,
                        system_performance_options=system_performance_options,
                    )
                )

        treatment_runs.append(
            {
                "git_ref": git_ref,
                "artifact": treatment_artifact,
                "algorithm_name": treatment_algorithm_name,
                "source_spec": treatment_source_spec,
                "parameter_inspection": treatment_parameter_inspection,
                "directory": treatment_dir,
                "perf_files": treatment_perf_files,
                "remote_artifacts": remote_artifacts,
                "remote_futures": treatment_remote_futures,
            }
        )

    if runner != "local":
        for control_remote_future in control_remote_futures:
            control_remote = control_remote_future.result()
            control_remote_artifacts.append(control_remote)
            control_perf_files.append(Path(control_remote["metadata_path"]))
        for control_perf_file in control_perf_files:
            if not control_perf_file.exists():
                raise FileNotFoundError(f"Missing control performance metadata: {control_perf_file}")
        for treatment_run in treatment_runs:
            remote_artifacts = treatment_run["remote_artifacts"]
            treatment_perf_files = treatment_run["perf_files"]
            for treatment_remote_future in treatment_run["remote_futures"]:
                trial_remote_artifacts = treatment_remote_future.result()
                remote_artifacts.append(trial_remote_artifacts)
                treatment_perf_files.append(Path(trial_remote_artifacts["metadata_path"]))

    treatments_payload: list[dict[str, Any]] = []
    for treatment_run in treatment_runs:
        git_ref = str(treatment_run["git_ref"])
        treatment_artifact = treatment_run["artifact"]
        treatment_algorithm_name = str(treatment_run["algorithm_name"])
        treatment_source_spec = treatment_run["source_spec"]
        treatment_parameter_inspection = treatment_run["parameter_inspection"]
        treatment_dir = treatment_run["directory"]
        treatment_perf_files = treatment_run["perf_files"]
        remote_artifacts = treatment_run["remote_artifacts"]
        for treatment_perf_file in treatment_perf_files:
            if not treatment_perf_file.exists():
                raise FileNotFoundError(f"Missing treatment performance metadata: {treatment_perf_file}")
        metrics = _compare_system_metrics_from_trial_files(control_perf_files, treatment_perf_files)
        comparison_path = treatment_dir / "comparison.json"
        comparison_path.write_text(json.dumps(metrics, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        p99_value = metrics.get("p99", {}).get("treatment_mean")
        throughput_value = metrics.get("mean_throughput", {}).get("treatment_mean")
        memory_value = metrics.get("max_memory_usage", {}).get("treatment_mean")
        artifact_payload = {
            "metadata_path": str(treatment_perf_files[0]) if treatment_perf_files else None,
            "metadata_paths": [str(path) for path in treatment_perf_files],
            "comparison_path": str(comparison_path),
        }
        if remote_artifacts:
            artifact_payload["submission_path"] = str(remote_artifacts[0]["submission_path"])
            artifact_payload["training_job_name"] = str(remote_artifacts[0]["training_job_name"])
            artifact_payload["trial_artifacts"] = remote_artifacts
        treatments_payload.append(
            {
                "git_ref": git_ref,
                "algorithm_name": treatment_algorithm_name,
                "algorithm_version": treatment_artifact.artifact_version,
                "status": {
                    "jobs_complete": True,
                    "performance_spec_compatible": True,
                },
                "metrics": {
                    "p99_ms": p99_value,
                    "throughput_rps": throughput_value,
                    "peak_rss_gib": memory_value,
                    "system": metrics,
                },
                "parameter_source": _parameter_source_for_ref(scenario=scenario, git_ref=git_ref, role="treatment"),
                "performance_source_path": treatment_source_spec,
                "parameter_algorithm_version": (
                    treatment_parameter_inspection.algorithm_version if treatment_parameter_inspection else None
                ),
                "performance_test_spec": treatment_performance_specs[git_ref].to_dict(),
                "performance_test_spec_sha256": treatment_performance_specs[git_ref].sha256(),
                "artifacts": artifact_payload,
            }
        )

    if remote_executor is not None:
        remote_executor.shutdown()

    payload = {
        "scenario": "qa",
        "stage": "system_performance",
        "parameter_source": scenario.get("parameter_source"),
        "parameter_sources": per_ref_parameter_sources,
        "context": {
            "run_id": scenario["run_id"],
            "runner": runner,
            "source_path": None if per_ref_source_paths else control_source_path,
            "performance_source_paths": per_ref_source_paths,
            "sample_count": sample_count,
            "sample_pool_size": options.sample_pool_size,
            "performance_test_spec": control_performance_spec.to_dict(),
            "performance_test_spec_sha256": control_performance_spec.sha256(),
            "trial_count": trial_count,
            "parameter_algorithm_version": parameter_inspection.algorithm_version if parameter_inspection else None,
        },
        "control": {
            "git_ref": control_ref,
            "algorithm_name": control_artifact.artifact_name,
            "algorithm_version": control_artifact.artifact_version,
            "parameter_source": control_parameter_source,
            "performance_source_path": control_source_spec,
            "parameter_algorithm_version": parameter_inspection.algorithm_version if parameter_inspection else None,
            "performance_test_spec": control_performance_spec.to_dict(),
            "performance_test_spec_sha256": control_performance_spec.sha256(),
            "artifacts": {
                "metadata_path": str(control_perf_files[0]) if control_perf_files else None,
                "metadata_paths": [str(path) for path in control_perf_files],
                **(
                    {
                        "submission_path": str(control_remote_artifacts[0]["submission_path"]),
                        "training_job_name": str(control_remote_artifacts[0]["training_job_name"]),
                        "trial_artifacts": control_remote_artifacts,
                    }
                    if control_remote_artifacts
                    else {}
                ),
            },
        },
        "treatments": treatments_payload,
    }
    policy = load_builtin_policy(scenario["criteria"]["id"])
    judgment = policy.evaluate(payload)
    (stage_dir / "payload.json").write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (stage_dir / "judgment.json").write_text(
        json.dumps(judgment, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return {
        "stage": "system_performance",
        "state": _stage_status_from_judgment(judgment["stage_judgment"]),
        "stage_judgment": judgment["stage_judgment"],
        "summary": judgment.get("summary"),
        "rollout_recommendation": judgment.get("rollout_recommendation"),
        "payload_path": str(stage_dir / "payload.json"),
        "judgment_path": str(stage_dir / "judgment.json"),
    }


def _run_backtest_hv_command(
    *,
    args: list[str],
    log_path: Path,
    output_base_dir: Path,
    options: _BacktestOptions,
    remote: bool,
) -> dict[str, Any]:
    if remote:
        try:
            manifest_path = _latest_remote_backtest_submission_manifest(output_base_dir)
        except FileNotFoundError:
            manifest_path = None
        if manifest_path is not None:
            log_path.parent.mkdir(parents=True, exist_ok=True)
            log_path.write_text(
                f"Reusing existing SageMaker backtest submission manifest: {manifest_path}\n",
                encoding="utf-8",
            )
            remote_artifacts = _wait_for_remote_backtest_submission(output_base_dir=output_base_dir, options=options)
            return {
                "log_path": str(log_path),
                **remote_artifacts,
                "submission_tail": [f"Reused existing submission manifest: {manifest_path}"],
                "reused_existing_submission": True,
            }

    command_result = _run_hv(args=args, log_path=log_path)
    if not remote:
        return {
            "runner": "local",
            "log_path": str(log_path),
        }
    remote_artifacts = _wait_for_remote_backtest_submission(output_base_dir=output_base_dir, options=options)
    return {
        "log_path": str(log_path),
        **remote_artifacts,
        "submission_tail": command_result.get("tail") or [],
    }


def _prod_default_parameters_by_date(scenario: dict[str, Any]) -> dict[str, dict[str, str]]:
    raw_parameters = scenario.get("prod_default_parameters_by_date")
    if raw_parameters is None:
        return {}
    if not isinstance(raw_parameters, dict) or not raw_parameters:
        raise ValueError("prod_default_parameters_by_date must be a non-empty object when configured.")

    parameters: dict[str, dict[str, str]] = {}
    for dt in _offline_context_dates(dict(scenario["offline_context"])):
        payload = raw_parameters.get(dt)
        if not isinstance(payload, dict):
            raise ValueError(f"Missing date-matched production parameter for {dt}.")
        required_fields = ("algorithm_name", "algorithm_version", "algorithm_parameter_id", "source", "created_at")
        missing = [field for field in required_fields if not str(payload.get(field) or "").strip()]
        if missing:
            raise ValueError(f"Date-matched production parameter for {dt} is missing: {', '.join(missing)}.")
        parameters[dt] = {field: str(payload[field]) for field in required_fields}
    return parameters


def _write_prod_default_control_backtest_override(
    *,
    stage_dir: Path,
    control_artifact: _BuiltRefArtifact,
    dt: str,
    parameter: dict[str, str],
    base_override_path: str | None,
) -> Path:
    if parameter["algorithm_name"] != control_artifact.artifact_name:
        raise ValueError(
            f"Production parameter for {dt} targets {parameter['algorithm_name']!r}, but the control artifact is "
            f"{control_artifact.artifact_name!r}."
        )
    if parameter["algorithm_version"] != control_artifact.artifact_version:
        raise ValueError(
            f"Production parameter for {dt} targets version {parameter['algorithm_version']!r}, but the control "
            f"artifact is version {control_artifact.artifact_version!r}."
        )

    base_override = (
        load_algorithm_definition_override_fragment(control_artifact.artifact_name, base_override_path)
        if base_override_path
        else None
    )
    existing_execution_parameters = dict((base_override or {}).get("hotvect_execution_parameters") or {})
    if "with_parameter" in existing_execution_parameters:
        raise ValueError(
            "--algorithm-override must not set hotvect_execution_parameters.with_parameter when "
            "--prod-default-of-slot-as-control is used; hv-qa resolves the production parameter for each date."
        )
    override = merge_algorithm_definition_override_fragments(
        base_override,
        {"hotvect_execution_parameters": {"with_parameter": parameter["source"]}},
        offline=True,
    )
    override_path = stage_dir / "prod_default_parameters" / dt / "control-backtest-override.json"
    override_path.parent.mkdir(parents=True, exist_ok=True)
    _write_json(override_path, override)
    return override_path


def _copy_date_matched_control_result(
    *, source_output_base_dir: Path, aggregate_output_base_dir: Path, dt: str
) -> Path:
    candidates = sorted((source_output_base_dir / "meta").rglob(f"last_test_date_{dt}/result.json"))
    if len(candidates) != 1:
        raise ValueError(
            f"Expected exactly one control result for {dt} under {source_output_base_dir}, found {len(candidates)}."
        )
    source_result = candidates[0]
    payload = _read_json(source_result)
    algorithm_id = str(payload.get("algorithm_id") or "").strip()
    test_data_time = str(payload.get("test_data_time") or "").strip()
    if not algorithm_id or test_data_time != dt:
        raise ValueError(f"Control backtest result for {dt} is malformed: {source_result}")
    destination = aggregate_output_base_dir / "meta" / algorithm_id / f"last_test_date_{dt}" / "result.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source_result, destination)
    return destination


def _execute_backtest_stage(
    *,
    stage: str,
    run_dir: Path,
    scenario: dict[str, Any],
    artifact_cache: dict[str, _BuiltRefArtifact],
    number_of_runs: int,
) -> dict[str, Any]:
    stage_dir = _stage_dir(run_dir, stage)
    stage_dir.mkdir(parents=True, exist_ok=True)
    execution_context = scenario["execution_context"]
    output_base_dir = stage_dir / "output"
    scratch_dir = stage_dir / "scratch"
    output_base_dir.mkdir(parents=True, exist_ok=True)
    scratch_dir.mkdir(parents=True, exist_ok=True)
    backtest_options = _BacktestOptions.from_mapping((scenario.get("stage_options") or {}).get("backtest"))
    algorithm_overrides = list(backtest_options.algorithm_overrides)
    control_ref = scenario["refs"]["control"]["git_ref"]
    treatment_refs = [treatment["git_ref"] for treatment in scenario["refs"]["treatments"]]
    control_arm = ("control", control_ref)
    treatment_arms = [("treatment", git_ref) for git_ref in treatment_refs]
    all_arms = [control_arm, *treatment_arms]
    remote_backtest = backtest_options.resolved_runner() == "sagemaker"
    if backtest_options.runner == "local" and backtest_options.remote_requested():
        raise ValueError("Backtest runner 'local' cannot be combined with SageMaker backtest options.")
    if remote_backtest and not backtest_options.sagemaker_job_prefix:
        raise ValueError(
            "Remote backtest requires a SageMaker job prefix. Set qa.run.backtest.sagemaker_job_prefix, "
            "pass --backtest-sagemaker-job-prefix, or use a SageMaker template/output base that lets hv-qa derive one."
        )
    if len(algorithm_overrides) not in (0, 1, len(all_arms)):
        raise ValueError(
            "Backtest --algorithm-override must be passed once to apply to all refs, "
            "or once per ref in control/treatment order."
        )
    algorithm_overrides_by_arm: dict[tuple[str, str], list[str]] = {}
    if len(algorithm_overrides) == 1:
        algorithm_overrides_by_arm = {arm: [algorithm_overrides[0]] for arm in all_arms}
    elif algorithm_overrides:
        algorithm_overrides_by_arm = {arm: [algorithm_overrides[index]] for index, arm in enumerate(all_arms)}

    control_artifact = _ensure_ref_artifact(
        cache=artifact_cache, run_dir=run_dir, scenario=scenario, git_ref=control_ref, role="control"
    )
    treatment_artifacts_by_ref = {
        git_ref: _ensure_ref_artifact(
            cache=artifact_cache, run_dir=run_dir, scenario=scenario, git_ref=git_ref, role="treatment"
        )
        for git_ref in treatment_refs
    }
    performance_spec_validation_required = (
        scenario["criteria"]["id"] == "noninferiority" or stage == "multi_day_backtest"
    )
    treatment_performance_specs: dict[str, _PerformanceTestSpec] = {}
    control_algorithm_name = str(execution_context.get("algorithm_name") or control_artifact.artifact_name)
    if performance_spec_validation_required:
        definition_cache: dict[tuple[str, str], dict[str, Any]] = {}
        control_definition = _read_algorithm_definition_from_artifact(
            artifact=control_artifact,
            algorithm_name=control_algorithm_name,
            cache=definition_cache,
        )
        control_performance_spec = _PerformanceTestSpec.from_algorithm_definition(
            control_definition, git_ref=control_ref
        )
        if backtest_options.no_performance_test:
            raise ValueError(f"QA stage '{stage}' requires its performance test to be enabled.")
        if (
            backtest_options.performance_test_samples is not None
            and backtest_options.performance_test_samples != control_performance_spec.samples
        ):
            raise ValueError(
                "Configured backtest performance_test_samples conflicts with the committed performance-test spec."
            )
        if (
            backtest_options.performance_test_sample_pool_size is not None
            and backtest_options.performance_test_sample_pool_size != control_performance_spec.sample_pool_size
        ):
            raise ValueError(
                "Configured backtest performance_test_sample_pool_size conflicts with the committed performance-test spec."
            )
        for git_ref, treatment_artifact in treatment_artifacts_by_ref.items():
            treatment_definition = _read_algorithm_definition_from_artifact(
                artifact=treatment_artifact,
                algorithm_name=treatment_artifact.artifact_name,
                cache=definition_cache,
            )
            treatment_performance_specs[git_ref] = _resolve_matched_performance_spec(
                control_ref=control_ref,
                control_definition=control_definition,
                treatment_ref=git_ref,
                treatment_definition=treatment_definition,
            )

    artifact_versions = [
        control_artifact.artifact_version,
        *(artifact.artifact_version for artifact in treatment_artifacts_by_ref.values()),
    ]
    has_version_collision = len(set(artifact_versions)) != len(artifact_versions)
    repositories_by_arm = {control_arm: _repository_url_for_ref(scenario=scenario, git_ref=control_ref, role="control")}
    repositories_by_arm.update(
        {
            treatment_arm: _repository_url_for_ref(scenario=scenario, git_ref=git_ref, role="treatment")
            for treatment_arm, git_ref in zip(treatment_arms, treatment_refs)
        }
    )
    has_ref_collision = len({git_ref for _, git_ref in all_arms}) != len(all_arms)
    requires_isolated_ref_outputs = (
        has_ref_collision or has_version_collision or len(set(repositories_by_arm.values())) > 1
    )
    backtest_artifacts_by_arm: dict[tuple[str, str], list[dict[str, Any]]] = {}

    def build_hv_args(
        git_refs: list[str],
        *,
        algo_repo_url: str,
        ref_output_base_dir: Path,
        ref_scratch_dir: Path,
        ref_algorithm_overrides: list[str],
        last_test_date: str,
        runs: int,
    ) -> list[str]:
        args = ["backtest"]
        for git_ref in git_refs:
            args.extend(["--git-reference", git_ref])
        for algorithm_override in ref_algorithm_overrides:
            args.extend(["--algorithm-override", algorithm_override])
        args.extend(
            [
                "--algo-repo-url",
                algo_repo_url,
                "--output-base-dir",
                str(ref_output_base_dir),
                "--scratch-dir",
                str(ref_scratch_dir),
                "--last-test-time",
                last_test_date,
                "--number-of-runs",
                str(runs),
            ]
        )
        if execution_context.get("data_base_dir"):
            args.extend(["--data-base-dir", str(execution_context["data_base_dir"])])
        elif not remote_backtest:
            raise ValueError("Local backtest requires data_base_dir.")
        args.extend(backtest_options.to_hv_backtest_args(include_remote_options=remote_backtest))
        return args

    def record_backtest_artifacts(
        artifacts: dict[str, Any],
        *,
        requested_arms: list[tuple[str, str]],
    ) -> None:
        arms_by_ref = {git_ref: (role, git_ref) for role, git_ref in requested_arms}
        if len(arms_by_ref) != len(requested_arms):
            raise ValueError(f"Backtest submission contains duplicate git refs: {requested_arms}.")
        for job in artifacts.get("jobs") or []:
            if not isinstance(job, dict):
                continue
            git_ref = str(job.get("algo_git_reference") or "").strip()
            if git_ref:
                if git_ref not in arms_by_ref:
                    raise ValueError(
                        f"Backtest returned unexpected git ref {git_ref!r}; expected {sorted(arms_by_ref)}."
                    )
                backtest_artifacts_by_arm.setdefault(arms_by_ref[git_ref], []).append(job)

    output_base_dirs_by_arm: dict[tuple[str, str], Path] = {}
    prod_default_parameters = _prod_default_parameters_by_date(scenario)
    if prod_default_parameters:
        control_output_base_dir = output_base_dir / "control"
        control_aggregate_output_base_dir = control_output_base_dir / "aggregate"
        control_scratch_base_dir = scratch_dir / "control"
        dates_to_run = (
            _offline_context_dates(dict(scenario["offline_context"]))
            if stage == "multi_day_backtest"
            else [str(scenario["offline_context"]["last_test_date"])]
        )
        for dt in dates_to_run:
            date_output_base_dir = control_output_base_dir / "by-date" / dt
            date_scratch_dir = control_scratch_base_dir / dt
            control_override_path = _write_prod_default_control_backtest_override(
                stage_dir=stage_dir,
                control_artifact=control_artifact,
                dt=dt,
                parameter=prod_default_parameters[dt],
                base_override_path=next(iter(algorithm_overrides_by_arm.get(control_arm, [])), None),
            )
            artifacts = _run_backtest_hv_command(
                args=build_hv_args(
                    [control_ref],
                    algo_repo_url=repositories_by_arm[control_arm],
                    ref_output_base_dir=date_output_base_dir,
                    ref_scratch_dir=date_scratch_dir,
                    ref_algorithm_overrides=[str(control_override_path)],
                    last_test_date=dt,
                    runs=1,
                ),
                log_path=stage_dir / "control" / f"{dt}.command.log",
                output_base_dir=date_output_base_dir,
                options=backtest_options,
                remote=remote_backtest,
            )
            record_backtest_artifacts(artifacts, requested_arms=[control_arm])
            _copy_date_matched_control_result(
                source_output_base_dir=date_output_base_dir,
                aggregate_output_base_dir=control_aggregate_output_base_dir,
                dt=dt,
            )
        output_base_dirs_by_arm[control_arm] = control_aggregate_output_base_dir

        for treatment_arm, git_ref in zip(treatment_arms, treatment_refs):
            ref_slug = _safe_ref_slug(git_ref)
            treatment_output_base_dir = output_base_dir / "treatments" / ref_slug
            treatment_scratch_dir = scratch_dir / "treatments" / ref_slug
            artifacts = _run_backtest_hv_command(
                args=build_hv_args(
                    [git_ref],
                    algo_repo_url=repositories_by_arm[treatment_arm],
                    ref_output_base_dir=treatment_output_base_dir,
                    ref_scratch_dir=treatment_scratch_dir,
                    ref_algorithm_overrides=algorithm_overrides_by_arm.get(treatment_arm, []),
                    last_test_date=str(scenario["offline_context"]["last_test_date"]),
                    runs=number_of_runs,
                ),
                log_path=stage_dir / "treatments" / f"{ref_slug}.command.log",
                output_base_dir=treatment_output_base_dir,
                options=backtest_options,
                remote=remote_backtest,
            )
            record_backtest_artifacts(artifacts, requested_arms=[treatment_arm])
            output_base_dirs_by_arm[treatment_arm] = treatment_output_base_dir
    elif requires_isolated_ref_outputs:
        for arm in all_arms:
            role, git_ref = arm
            ref_slug = f"{role}-{_safe_ref_slug(git_ref)}"
            ref_output_base_dir = output_base_dir / ref_slug
            ref_scratch_dir = scratch_dir / ref_slug
            ref_output_base_dir.mkdir(parents=True, exist_ok=True)
            ref_scratch_dir.mkdir(parents=True, exist_ok=True)
            artifacts = _run_backtest_hv_command(
                args=build_hv_args(
                    [git_ref],
                    algo_repo_url=repositories_by_arm[arm],
                    ref_output_base_dir=ref_output_base_dir,
                    ref_scratch_dir=ref_scratch_dir,
                    ref_algorithm_overrides=algorithm_overrides_by_arm.get(arm, []),
                    last_test_date=str(scenario["offline_context"]["last_test_date"]),
                    runs=number_of_runs,
                ),
                log_path=stage_dir / f"{ref_slug}.command.log",
                output_base_dir=ref_output_base_dir,
                options=backtest_options,
                remote=remote_backtest,
            )
            record_backtest_artifacts(artifacts, requested_arms=[arm])
            output_base_dirs_by_arm[arm] = ref_output_base_dir
    else:
        artifacts = _run_backtest_hv_command(
            args=build_hv_args(
                [control_ref, *treatment_refs],
                algo_repo_url=repositories_by_arm[control_arm],
                ref_output_base_dir=output_base_dir,
                ref_scratch_dir=scratch_dir,
                ref_algorithm_overrides=algorithm_overrides,
                last_test_date=str(scenario["offline_context"]["last_test_date"]),
                runs=number_of_runs,
            ),
            log_path=stage_dir / "command.log",
            output_base_dir=output_base_dir,
            options=backtest_options,
            remote=remote_backtest,
        )
        record_backtest_artifacts(artifacts, requested_arms=all_arms)
        output_base_dirs_by_arm = {arm: output_base_dir for arm in all_arms}

    treatments_payload: list[dict[str, Any]] = []
    stage_summaries: list[dict[str, Any]] = []
    comparison_offline_context = copy.deepcopy(scenario["offline_context"])
    if stage == "realistic_single_day":
        last_test_date = str(comparison_offline_context["last_test_date"])
        comparison_offline_context["backtest_days"] = 1
        comparison_offline_context["backtest_date_window"] = {
            "start_date": last_test_date,
            "end_date": last_test_date,
        }
    for treatment in scenario["refs"]["treatments"]:
        git_ref = treatment["git_ref"]
        treatment_arm = ("treatment", git_ref)
        treatment_artifact = treatment_artifacts_by_ref[git_ref]
        comparison = _compare_backtest_outputs(
            control_output_base_dir=output_base_dirs_by_arm[control_arm],
            treatment_output_base_dir=output_base_dirs_by_arm[treatment_arm],
            algorithm_name=control_algorithm_name,
            control_artifact=control_artifact,
            treatment_artifact=treatment_artifact,
            offline_context=comparison_offline_context,
        )
        comparison_path = stage_dir / f"{_safe_ref_slug(git_ref)}.comparison.json"
        comparison_path.write_text(json.dumps(comparison, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        system_metrics = comparison["system_metrics"]
        quality_metrics = comparison["quality_metrics"]
        status_flags = {
            "jobs_complete": True,
            "packaging_ok": True,
            "data_shape_ok": True,
            "runtime_ok": True,
            "has_semantic_shortcuts": False,
            **({"performance_spec_compatible": True} if performance_spec_validation_required else {}),
        }
        stage_summaries.append(
            {
                "git_ref": git_ref,
                "dates_used": comparison["dates_used"],
                "quality_metrics": quality_metrics,
                "quality_statistical_basis": comparison["quality_statistical_basis"],
                "system_metrics": system_metrics,
            }
        )
        payload_entry = {
            "git_ref": git_ref,
            "algorithm_name": treatment_artifact.artifact_name,
            "algorithm_version": treatment_artifact.artifact_version,
            "status": status_flags,
            "metrics": {
                "offline_quality": comparison["offline_quality"],
                "performance": comparison["performance"],
                "quality": quality_metrics,
                "system": system_metrics,
            },
            **(
                {
                    "performance_test_spec": treatment_performance_specs[git_ref].to_dict(),
                    "performance_test_spec_sha256": treatment_performance_specs[git_ref].sha256(),
                }
                if performance_spec_validation_required
                else {}
            ),
            "artifacts": {
                "comparison_path": str(comparison_path),
                "output_base_dir": str(output_base_dirs_by_arm[treatment_arm]),
                "control_output_base_dir": str(output_base_dirs_by_arm[control_arm]),
                **(
                    {"remote_backtest_jobs": backtest_artifacts_by_arm[treatment_arm]}
                    if backtest_artifacts_by_arm.get(treatment_arm)
                    else {}
                ),
            },
        }
        if stage == "realistic_single_day":
            payload_entry["status"] = {
                "jobs_complete": status_flags["jobs_complete"],
                "packaging_ok": status_flags["packaging_ok"],
                "data_shape_ok": status_flags["data_shape_ok"],
                "runtime_ok": status_flags["runtime_ok"],
            }
        else:
            payload_entry["status"] = {
                "jobs_complete": status_flags["jobs_complete"],
                "has_semantic_shortcuts": status_flags["has_semantic_shortcuts"],
                **(
                    {"performance_spec_compatible": status_flags["performance_spec_compatible"]}
                    if performance_spec_validation_required
                    else {}
                ),
            }
        treatments_payload.append(payload_entry)

    payload = {
        "scenario": "qa",
        "stage": stage,
        "context": {
            "run_id": scenario["run_id"],
            "slot_name": scenario.get("slot_name"),
            "online_policy_known": False,
            "target_dates": stage_summaries[0]["dates_used"] if stage_summaries else [],
            "quality_statistical_basis": (stage_summaries[0]["quality_statistical_basis"] if stage_summaries else None),
            "backtest_output_mode": (
                "date_matched_prod_parameter_outputs"
                if prod_default_parameters
                else "isolated_ref_outputs"
                if requires_isolated_ref_outputs
                else "combined_output"
            ),
            "algorithm_override_count": len(algorithm_overrides),
            "backtest_runner": "sagemaker" if remote_backtest else "local",
            "control_parameter_policy": "prod_parameters_by_date" if prod_default_parameters else "backtest_trained",
        },
        "control": {
            "git_ref": control_ref,
            "algorithm_name": control_artifact.artifact_name,
            "algorithm_version": control_artifact.artifact_version,
            **({"prod_parameters_by_date": prod_default_parameters} if prod_default_parameters else {}),
            **(
                {"remote_backtest_jobs": backtest_artifacts_by_arm[control_arm]}
                if backtest_artifacts_by_arm.get(control_arm)
                else {}
            ),
        },
        "treatments": treatments_payload,
    }
    policy = load_builtin_policy(scenario["criteria"]["id"])
    judgment = policy.evaluate(payload)
    (stage_dir / "payload.json").write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (stage_dir / "judgment.json").write_text(
        json.dumps(judgment, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return {
        "stage": stage,
        "state": _stage_status_from_judgment(judgment["stage_judgment"]),
        "stage_judgment": judgment["stage_judgment"],
        "summary": judgment.get("summary"),
        "rollout_recommendation": judgment.get("rollout_recommendation"),
        "payload_path": str(stage_dir / "payload.json"),
        "judgment_path": str(stage_dir / "judgment.json"),
        "output_base_dir": str(output_base_dir),
    }


def _execute_qa_run_stage(
    *,
    stage: str,
    run_dir: Path,
    scenario: dict[str, Any],
    artifact_cache: dict[str, _BuiltRefArtifact],
) -> dict[str, Any]:
    if stage == "audit_parity":
        return _execute_audit_parity_stage(run_dir=run_dir, scenario=scenario, artifact_cache=artifact_cache)
    if stage == "encode_parity":
        return _execute_encode_parity_stage(run_dir=run_dir, scenario=scenario, artifact_cache=artifact_cache)
    if stage == "predict_parity":
        return _execute_predict_parity_stage(run_dir=run_dir, scenario=scenario, artifact_cache=artifact_cache)
    if stage == "system_performance":
        return _execute_system_performance_stage(run_dir=run_dir, scenario=scenario, artifact_cache=artifact_cache)
    if stage == "realistic_single_day":
        return _execute_backtest_stage(
            stage=stage,
            run_dir=run_dir,
            scenario=scenario,
            artifact_cache=artifact_cache,
            number_of_runs=1,
        )
    if stage == "multi_day_backtest":
        return _execute_backtest_stage(
            stage=stage,
            run_dir=run_dir,
            scenario=scenario,
            artifact_cache=artifact_cache,
            number_of_runs=int(scenario["offline_context"]["backtest_days"]),
        )
    raise ValueError(f"Unsupported stage: {stage}")


def _execute_requested_stages(
    *,
    action: str,
    run_dir: Path,
    scenario: dict[str, Any],
    status: dict[str, Any],
    artifact_cache: dict[str, _BuiltRefArtifact] | None = None,
) -> None:
    paths = _scenario_paths(run_dir)
    requested_mode = status.get("requested_mode")
    requested_stages = list(status.get("requested_stages", []))
    completed_stages = list(status.get("completed_stages", []))
    existing_stage_results = dict(status.get("stage_results", {}))
    artifact_cache = {} if artifact_cache is None else artifact_cache

    notes: list[str] = []
    executed_any = False
    for stage in requested_stages:
        if requested_mode != "only" and stage in completed_stages:
            continue

        status["state"] = "running"
        status["current_stage"] = stage
        status["stage_status"][stage] = "running"
        status["stage_results"] = existing_stage_results
        status["notes"] = notes
        status["updated_at"] = _now_iso()
        _persist_qa_run_state(run_dir=run_dir, scenario=scenario, status=status)
        _append_decision_event(
            paths,
            {
                "timestamp": status["updated_at"],
                "event": "stage_started",
                "stage": stage,
            },
        )
        try:
            stage_result = _execute_qa_run_stage(
                stage=stage,
                run_dir=run_dir,
                scenario=scenario,
                artifact_cache=artifact_cache,
            )
        except Exception as exc:
            message = f"Stage '{stage}' failed: {exc}"
            existing_stage_results[stage] = {
                "stage": stage,
                "state": "failed",
                "summary": message,
                "error": str(exc),
                "debug": _build_stage_failure_debug(run_dir=run_dir, paths=paths, stage=stage, exc=exc),
            }
            status["stage_status"][stage] = "failed"
            status["state"] = "failed"
            status["current_stage"] = stage
            notes.append(message)
            _append_decision_event(
                paths,
                {
                    "timestamp": _now_iso(),
                    "event": "stage_failed",
                    "stage": stage,
                    "error": str(exc),
                },
            )
            break

        executed_any = True
        existing_stage_results[stage] = stage_result
        stage_status = _stage_status_from_judgment(str(stage_result["stage_judgment"]))
        status["stage_status"][stage] = stage_status
        if stage_status == "passed" and stage not in completed_stages:
            completed_stages.append(stage)
        if stage_status != "passed":
            stage_result["debug"] = _build_stage_failure_debug(
                run_dir=run_dir,
                paths=paths,
                stage=stage,
                stage_result=stage_result,
            )
            status["state"] = "failed"
            notes.append(stage_result.get("summary") or f"Stage '{stage}' did not pass.")
            _append_decision_event(
                paths,
                {
                    "timestamp": _now_iso(),
                    "event": "stage_completed",
                    "stage": stage,
                    "stage_judgment": stage_result["stage_judgment"],
                    "summary": stage_result.get("summary"),
                },
            )
            break

        _append_decision_event(
            paths,
            {
                "timestamp": _now_iso(),
                "event": "stage_completed",
                "stage": stage,
                "stage_judgment": stage_result["stage_judgment"],
                "summary": stage_result.get("summary"),
            },
        )
    else:
        if executed_any or requested_stages:
            status["state"] = (
                "completed"
                if all(status["stage_status"].get(stage) == "passed" for stage in requested_stages)
                else status.get("state", "prepared")
            )
        status["current_stage"] = requested_stages[-1] if requested_stages else None

    status["completed_stages"] = completed_stages
    status["stage_results"] = existing_stage_results
    status["notes"] = notes
    status["updated_at"] = _now_iso()
    _persist_qa_run_state(run_dir=run_dir, scenario=scenario, status=status)


def _qa_run_output(
    *,
    action: str,
    run_dir: Path,
    scenario: dict[str, Any],
    status: dict[str, Any],
) -> dict[str, Any]:
    error_message = _qa_run_error_message(status)
    parameter_source = _normalize_parameter_source(scenario.get("parameter_source"))
    parameter_sources = _normalize_parameter_sources(scenario.get("parameter_sources"))
    encode_algorithm_names = _normalize_encode_algorithm_names(scenario.get("encode_algorithm_names"))
    performance_source_paths = _normalize_performance_source_paths(scenario.get("performance_source_paths"))
    execution_context = _normalize_execution_context_for_output(scenario.get("execution_context"))
    stage_options = _normalize_stage_options_for_output(scenario.get("stage_options"))
    output = {
        "ok": error_message is None,
        "action": action,
        "run_id": scenario["run_id"],
        "run_dir": str(run_dir),
        "scenario": scenario["scenario"],
        "criteria": scenario["criteria"],
        "refs": scenario["refs"],
        "slot_name": scenario.get("slot_name"),
        "offline_context": scenario.get("offline_context"),
        "control_resolution": scenario.get("control_resolution"),
        "prod_default_parameters_by_date": scenario.get("prod_default_parameters_by_date"),
        "parameter_source": parameter_source,
        "parameter_sources": parameter_sources,
        "parameter_source_resolution": scenario.get("parameter_source_resolution"),
        "encode_algorithm_names": encode_algorithm_names,
        "performance_source_paths": performance_source_paths,
        "execution_context": execution_context,
        "stage_options": stage_options,
        "stage_sequence": scenario.get("stage_sequence", []),
        "state": status["state"],
        "requested_mode": status["requested_mode"],
        "target_stage": status["target_stage"],
        "requested_stages": status["requested_stages"],
        "notes": status["notes"],
        "stage_status": status.get("stage_status", {}),
        "stage_results": status.get("stage_results", {}),
        "files": {
            "plan": str(run_dir / _SCENARIO_FILE),
            "status": str(run_dir / _STATUS_FILE),
            "decisions": str(run_dir / _DECISIONS_FILE),
            "evidence": str(run_dir / _EVIDENCE_FILE),
        },
    }
    debug_handoff = _build_debug_handoff(run_dir=run_dir, scenario=scenario, status=status)
    if debug_handoff is not None:
        output["debug_handoff"] = debug_handoff
    return output


def _qa_run_error_message(status: dict[str, Any]) -> str | None:
    stage_status = dict(status.get("stage_status") or {})
    stage_results = dict(status.get("stage_results") or {})
    for stage in status.get("requested_stages") or []:
        state = stage_status.get(stage)
        if state in {"blocked", "failed", "inconclusive"}:
            stage_result = dict(stage_results.get(stage) or {})
            return (
                stage_result.get("summary")
                or stage_result.get("error")
                or next(iter(status.get("notes") or []), None)
                or f"Stage '{stage}' did not complete successfully."
            )
    if status.get("state") in {"blocked", "failed"}:
        return next(iter(status.get("notes") or []), None) or "QA run did not complete successfully."
    return None


def _non_empty_string(value: Any) -> str:
    return str(value or "").strip()


_QA_RUN_PARSER_DEFAULTS: dict[str, Any] = {
    "resume": "",
    "control": "",
    "prod_default_of_slot_as_control": "",
    "treatments": [],
    "slot_name": "",
    "parameter_source": "",
    "control_parameter_source": "",
    "treatment_parameter_sources": [],
    "last_test_date": "",
    "backtest_days": "",
    "evaluation_criteria": "",
    "algo_repo_url": "",
    "control_algo_repo_url": "",
    "treatment_algo_repo_urls": [],
    "algorithm_name": "",
    "encode_algorithm_name": "",
    "control_encode_algorithm_name": "",
    "treatment_encode_algorithm_names": [],
    "source_path": "",
    "predict_source_path": "",
    "performance_source_path": "",
    "control_performance_source_path": "",
    "treatment_performance_source_paths": [],
    "encode_source_path": "",
    "performance_runner": "",
    "performance_sagemaker_job_prefix": "",
    "performance_sagemaker_config": "",
    "performance_role_arn": "",
    "performance_assume_role_arn": "",
    "performance_s3_output_base": "",
    "performance_instance_type": "",
    "performance_volume_gb": None,
    "performance_max_runtime_seconds": None,
    "performance_training_image": "",
    "performance_samples": None,
    "performance_sample_pool_size": None,
    "performance_target_rps": None,
    "performance_target_throughput_fraction": None,
    "performance_workload_mode": "",
    "performance_max_threads": None,
    "performance_poll_seconds": None,
    "performance_trials": None,
    "data_base_dir": "",
    "scratch_dir": "",
    "algorithm_overrides": [],
    "backtest_runner": "",
    "backtest_sagemaker_job_prefix": "",
    "backtest_sagemaker_config": "",
    "backtest_role_arn": "",
    "backtest_assume_role_arn": "",
    "backtest_s3_output_base": "",
    "backtest_instance_type": "",
    "backtest_volume_gb": None,
    "backtest_max_runtime_seconds": None,
    "backtest_training_image": "",
    "backtest_auto_attach_data_default_s3_base": "",
    "backtest_auto_attach_data_environment": "",
    "backtest_poll_seconds": None,
    "backtest_no_performance_test": False,
    "backtest_performance_test_samples": None,
    "backtest_performance_test_sample_pool_size": None,
    "until": "",
    "only": "",
    "meta_dir": "",
}


def _set_qa_run_parser_defaults(parser: argparse.ArgumentParser, **overrides: Any) -> None:
    defaults = dict(_QA_RUN_PARSER_DEFAULTS)
    defaults.update(overrides)
    parser.set_defaults(**defaults)


class QaRunCommand:
    """Dispatch staged QA run commands."""

    @classmethod
    def register_subcommands(cls, subparsers, *, command_prefix: str = "hv-qa") -> None:
        StartCommand.register_parser(subparsers, command_prefix=command_prefix)
        ResumeCommand.register_parser(subparsers, command_prefix=command_prefix)
        StatusCommand.register_parser(subparsers, command_prefix=command_prefix)

    def execute(self, args, *, command: str | None = None):
        command = command or args.command
        if command == "start":
            StartCommand().execute(args)
        elif command == "resume":
            ResumeCommand().execute(args)
        elif command == "status":
            StatusCommand().execute(args)
        else:
            raise SystemExit("Missing QA run command.")


class StartCommand(BaseCommand):
    """Start a staged QA validation."""

    @classmethod
    def register_parser(cls, subparsers, *, command_prefix: str = "hv-qa"):
        parser = subparsers.add_parser(
            "start",
            help="Start a staged QA validation",
            formatter_class=argparse.RawDescriptionHelpFormatter,
            epilog=f"""
Examples:
  {command_prefix} start \\
      --control baseline-ref \\
      --treatment candidate-ref

  {command_prefix} start \\
      --control baseline-ref \\
      --treatment candidate-ref \\
      --criteria noninferiority \\
      --stage multi_day_backtest
            """,
        )
        _set_qa_run_parser_defaults(parser, resume="")
        control_group = parser.add_mutually_exclusive_group(required=True)
        control_group.add_argument("--control", help="Control git reference")
        control_group.add_argument(
            "--prod-default-of-slot-as-control",
            dest="prod_default_of_slot_as_control",
            help="Resolve the current EMS production default of this slot and use it as control",
        )
        parser.add_argument(
            "--treatment",
            action="append",
            dest="treatments",
            required=True,
            help="Treatment git reference",
        )
        parser.add_argument(
            "--repo",
            dest="algo_repo_url",
            default="",
            help="Algorithm git repository URL or local path",
        )
        parser.add_argument(
            "--control-repo",
            dest="control_algo_repo_url",
            default="",
            help="Control algorithm repository URL or local path",
        )
        parser.add_argument(
            "--treatment-repo",
            action="append",
            dest="treatment_algo_repo_urls",
            default=[],
            help="Treatment algorithm repository URL or local path; repeat in --treatment order",
        )
        parser.add_argument(
            "--parameter-source",
            default="",
            help="Shared fixed parameter archive for parameter-backed QA stages",
        )
        parser.add_argument(
            "--control-parameter-source",
            default="",
            help="Control parameter archive for a per-ref system-performance run",
        )
        parser.add_argument(
            "--treatment-parameter-source",
            action="append",
            dest="treatment_parameter_sources",
            default=[],
            help="Treatment parameter archive; repeat in --treatment order",
        )
        parser.add_argument(
            "--performance-source-path",
            default="",
            help="Shared system-performance source path",
        )
        parser.add_argument(
            "--control-performance-source-path",
            default="",
            help="Control system-performance source path for a per-ref run",
        )
        parser.add_argument(
            "--treatment-performance-source-path",
            action="append",
            dest="treatment_performance_source_paths",
            default=[],
            help="Treatment system-performance source path; repeat in --treatment order",
        )
        parser.add_argument(
            "--last-test-date",
            default="",
            help="Offline anchor date in YYYY-MM-DD format; defaults to the latest complete control test-data window",
        )
        parser.add_argument(
            "--days",
            dest="backtest_days",
            default="",
            help="Number of offline comparison days; defaults to 7",
        )
        parser.add_argument(
            "--criteria",
            dest="evaluation_criteria",
            default="",
            help="Criteria id: exact, noninferiority, or superiority",
        )
        parser.add_argument("--until", default="", help="Run until this stage, inclusive")
        parser.add_argument("--stage", dest="only", default="", help="Run exactly one stage")
        parser.add_argument("--work-dir", dest="meta_dir", default="", help="Base directory for hv-qa run state")
        return parser

    def execute(self, args):
        meta_dir = (args.meta_dir or "").strip() or None
        resume_run_id = (args.resume or "").strip()

        if resume_run_id:
            if (
                args.control
                or args.prod_default_of_slot_as_control
                or args.treatments
                or args.evaluation_criteria
                or args.last_test_date
                or args.backtest_days
            ):
                raise ValueError(
                    "resume does not accept --control, --prod-default-of-slot-as-control, "
                    "--treatment, --last-test-date, --days, or --criteria."
                )

            run_dir = _resolve_run_dir(meta_dir, resume_run_id)
            paths = _scenario_paths(run_dir)
            scenario = _read_json(paths["scenario"])
            status = _read_json(paths["status"])
            _validate_frozen_plan(scenario, status)
            _validate_frozen_criteria(dict(scenario["criteria"]))
            parameter_source = _normalize_parameter_source(scenario.get("parameter_source"))
            parameter_sources = _normalize_parameter_sources(scenario.get("parameter_sources"))
            stage_sequence = tuple(str(stage) for stage in scenario["stage_sequence"])

            requested_mode, target_stage, requested_stages = _resolve_stage_request(
                until_stage=(args.until or "").strip() or None,
                only_stage=(args.only or "").strip() or None,
                stage_sequence=stage_sequence,
                default_until_stage=str(
                    status.get("target_stage") or scenario.get("default_until") or _DEFAULT_UNTIL_STAGE
                ),
                require_resume_for_only=True,
                resume=True,
            )

            now = _now_iso()
            status.update(
                {
                    "state": "prepared",
                    "updated_at": now,
                    "requested_mode": requested_mode,
                    "target_stage": target_stage,
                    "requested_stages": requested_stages,
                    "current_stage": None,
                    "stage_status": _rebuild_stage_status_for_request(
                        completed_stages=list(status.get("completed_stages", [])),
                        requested_stages=requested_stages,
                        stage_sequence=stage_sequence,
                        requested_mode=requested_mode,
                    ),
                    "notes": [],
                }
            )
            status.setdefault("stage_results", {})

            _persist_qa_run_state(run_dir=run_dir, scenario=scenario, status=status)
            _append_jsonl(
                paths["decisions"],
                {
                    "timestamp": now,
                    "event": "run_resumed",
                    "parameter_source": parameter_source,
                    "parameter_sources": parameter_sources,
                    "encode_algorithm_names": _normalize_encode_algorithm_names(scenario.get("encode_algorithm_names")),
                    "performance_source_paths": _normalize_performance_source_paths(
                        scenario.get("performance_source_paths")
                    ),
                    "execution_context": scenario.get("execution_context"),
                    "stage_options": scenario.get("stage_options"),
                    "control_resolution": scenario.get("control_resolution"),
                    "requested_mode": requested_mode,
                    "target_stage": target_stage,
                    "requested_stages": requested_stages,
                },
            )
            artifact_cache = _prepare_qa_run_execution(run_dir=run_dir, scenario=scenario, status=status)
            if _should_execute_requested_stages(status):
                _execute_requested_stages(
                    action="resumed",
                    run_dir=run_dir,
                    scenario=scenario,
                    status=status,
                    artifact_cache=artifact_cache,
                )
            elif requested_stages and all(status["stage_status"].get(stage) == "passed" for stage in requested_stages):
                status["state"] = "completed"
                status["current_stage"] = requested_stages[-1]
                status["updated_at"] = _now_iso()
                _persist_qa_run_state(run_dir=run_dir, scenario=scenario, status=status)
            payload = _qa_run_output(action="resumed", run_dir=run_dir, scenario=scenario, status=status)
            print(json.dumps(payload, indent=2), flush=True)
            error_message = _qa_run_error_message(status)
            if error_message is not None:
                raise ValueError(error_message)
            return

        control_ref = (args.control or "").strip()
        treatment_refs = [item.strip() for item in (args.treatments or []) if item and item.strip()]
        prod_default_slot_name = (args.prod_default_of_slot_as_control or "").strip() or None
        slot_name = prod_default_slot_name
        qa_run_defaults = hv_config.load_qa_run_defaults()
        if "last_test_date" in qa_run_defaults:
            raise ValueError(
                "Config field 'qa.run.defaults.last_test_date' is not supported. "
                "hv-qa discovers the latest complete test-data window unless --last-test-date is supplied."
            )
        execution_defaults = hv_config.load_qa_run_execution_context()
        system_performance_defaults = hv_config.load_qa_run_system_performance()
        backtest_defaults = hv_config.load_qa_run_backtest()
        directory_defaults = hv_config.load_directory_defaults()
        sagemaker_defaults = hv_config.load_sagemaker_defaults()
        parameter_source = _normalize_parameter_source(
            _value_or_config_default(args.parameter_source, execution_defaults.get("parameter_source"))
        )

        if control_ref and prod_default_slot_name:
            raise ValueError("Specify exactly one control source: --control or --prod-default-of-slot-as-control.")
        if not control_ref and not prod_default_slot_name:
            raise ValueError("Missing required argument: --control or --prod-default-of-slot-as-control")
        if not treatment_refs:
            raise ValueError("Provide at least one --treatment")
        control_resolution: dict[str, Any] | None = None
        if prod_default_slot_name:
            control_resolution = _resolve_prod_default_slot_control(prod_default_slot_name)
            control_ref = str(control_resolution["control_git_ref"])
        parameter_sources = _resolve_parameter_sources(
            shared_parameter_source=parameter_source,
            control_parameter_source=args.control_parameter_source,
            treatment_parameter_sources=args.treatment_parameter_sources,
            control_ref=control_ref,
            treatment_refs=treatment_refs,
        )
        performance_source_paths = _resolve_performance_source_paths(
            shared_performance_source_path=args.performance_source_path,
            control_performance_source_path=args.control_performance_source_path,
            treatment_performance_source_paths=args.treatment_performance_source_paths,
            control_ref=control_ref,
            treatment_refs=treatment_refs,
        )
        encode_algorithm_names = _resolve_encode_algorithm_names(
            control_encode_algorithm_name=args.control_encode_algorithm_name,
            treatment_encode_algorithm_names=args.treatment_encode_algorithm_names,
            control_ref=control_ref,
            treatment_refs=treatment_refs,
        )
        ref_repositories = _resolve_ref_repository_urls(
            shared_repo_url=_value_or_config_default(args.algo_repo_url, execution_defaults.get("algo_repo_url")),
            control_repo_url=_value_or_config_default(
                args.control_algo_repo_url,
                execution_defaults.get("control_algo_repo_url"),
            ),
            treatment_repo_urls=(
                args.treatment_algo_repo_urls
                if args.treatment_algo_repo_urls
                else execution_defaults.get("treatment_algo_repo_urls")
            ),
            control_ref=control_ref,
            treatment_refs=treatment_refs,
        )

        _validate_same_version_policy(control_ref, treatment_refs)
        offline_context = _resolve_offline_context(
            last_test_date=args.last_test_date,
            backtest_days=_value_or_config_default(args.backtest_days, qa_run_defaults.get("backtest_days")),
        )
        prod_default_parameters_by_date: dict[str, dict[str, str]] | None = None
        if control_resolution is not None and offline_context["last_test_date"] is not None:
            offline_context, prod_default_parameters_by_date = _resolve_prod_default_date_matched_parameters(
                offline_context=offline_context,
                control_resolution=control_resolution,
            )
        execution_context = _resolve_execution_context(
            algo_repo_url=_value_or_config_default(args.algo_repo_url, execution_defaults.get("algo_repo_url")),
            algorithm_name=_value_or_config_default(args.algorithm_name, execution_defaults.get("algorithm_name")),
            encode_algorithm_name=_value_or_config_default(
                args.encode_algorithm_name,
                execution_defaults.get("encode_algorithm_name"),
            ),
            source_path=_value_or_config_default(args.source_path, execution_defaults.get("source_path")),
            predict_source_path=_value_or_config_default(
                args.predict_source_path,
                execution_defaults.get("predict_source_path"),
            ),
            performance_source_path=None
            if performance_source_paths
            else _value_or_config_default(
                args.performance_source_path,
                execution_defaults.get("performance_source_path"),
            ),
            encode_source_path=_value_or_config_default(
                args.encode_source_path,
                execution_defaults.get("encode_source_path"),
            ),
            data_base_dir=_value_or_config_default(
                args.data_base_dir,
                execution_defaults.get("data_base_dir") or directory_defaults.get("data_base_dir"),
            ),
            scratch_dir=_value_or_config_default(
                args.scratch_dir,
                execution_defaults.get("scratch_dir") or directory_defaults.get("scratch_dir"),
            ),
        )
        if performance_source_paths:
            execution_context["performance_source_path"] = None
        if control_resolution is not None:
            control_algorithm = str(control_resolution["algorithm"])
            existing_algorithm_name = _normalize_optional_string(execution_context.get("algorithm_name"))
            if existing_algorithm_name and existing_algorithm_name != control_algorithm:
                raise ValueError(
                    f"Resolved prod default for slot {prod_default_slot_name!r} uses algorithm "
                    f"{control_algorithm!r}, but execution context requested {existing_algorithm_name!r}."
                )
            if not existing_algorithm_name:
                execution_context["algorithm_name"] = control_algorithm
        stage_options = {
            "system_performance": _resolve_system_performance_options(
                config_options=system_performance_defaults,
                sagemaker_defaults=sagemaker_defaults,
                runner=args.performance_runner,
                sagemaker_job_prefix=args.performance_sagemaker_job_prefix,
                sagemaker_config=args.performance_sagemaker_config,
                role_arn=args.performance_role_arn,
                assume_role_arn=args.performance_assume_role_arn,
                s3_output_base=args.performance_s3_output_base,
                instance_type=args.performance_instance_type,
                volume_gb=args.performance_volume_gb,
                max_runtime_seconds=args.performance_max_runtime_seconds,
                training_image=args.performance_training_image,
                samples=args.performance_samples,
                sample_pool_size=args.performance_sample_pool_size,
                target_rps=args.performance_target_rps,
                target_throughput_fraction=args.performance_target_throughput_fraction,
                workload_mode=args.performance_workload_mode,
                max_threads=args.performance_max_threads,
                poll_seconds=args.performance_poll_seconds,
                trials=args.performance_trials,
            ),
            "backtest": _resolve_backtest_options(
                config_options=backtest_defaults,
                sagemaker_defaults=sagemaker_defaults,
                runner=args.backtest_runner,
                algorithm_overrides=args.algorithm_overrides,
                no_performance_test=args.backtest_no_performance_test,
                performance_test_samples=args.backtest_performance_test_samples,
                performance_test_sample_pool_size=args.backtest_performance_test_sample_pool_size,
                sagemaker_job_prefix=args.backtest_sagemaker_job_prefix,
                sagemaker_config=args.backtest_sagemaker_config,
                role_arn=args.backtest_role_arn,
                assume_role_arn=args.backtest_assume_role_arn,
                s3_output_base=args.backtest_s3_output_base,
                instance_type=args.backtest_instance_type,
                volume_gb=args.backtest_volume_gb,
                max_runtime_seconds=args.backtest_max_runtime_seconds,
                training_image=args.backtest_training_image,
                auto_attach_data_default_s3_base=args.backtest_auto_attach_data_default_s3_base,
                auto_attach_data_environment=args.backtest_auto_attach_data_environment,
                poll_seconds=args.backtest_poll_seconds,
            ),
        }
        criteria = _resolve_qa_run_criteria(
            _value_or_config_default(args.evaluation_criteria, qa_run_defaults.get("evaluation_criteria"))
        )
        _validate_parameter_source_inputs_for_criteria(
            criteria=criteria,
            parameter_source=parameter_source,
            parameter_sources=parameter_sources,
        )
        stage_sequence = _resolve_qa_run_stage_sequence(
            parameter_source,
            parameter_sources,
            criteria,
            requested_parameter_stage=_stage_request_needs_parameterized_sequence(
                until_stage=(args.until or "").strip() or None,
                only_stage=(args.only or "").strip() or None,
            ),
        )

        requested_mode, target_stage, requested_stages = _resolve_stage_request(
            until_stage=(args.until or "").strip() or None,
            only_stage=(args.only or "").strip() or None,
            stage_sequence=stage_sequence,
            default_until_stage=_DEFAULT_UNTIL_STAGE,
            require_resume_for_only=False,
            resume=False,
        )

        run_id = _generate_run_id(control_ref=control_ref, treatment_refs=treatment_refs)
        run_dir = _resolve_runs_dir(meta_dir) / run_id
        _create_run_layout(run_dir)
        now = _now_iso()

        scenario = {
            "version": 1,
            "run_id": run_id,
            "scenario": "qa",
            "created_at": now,
            "slot_name": slot_name,
            "offline_context": offline_context,
            "control_resolution": control_resolution,
            "prod_default_parameters_by_date": prod_default_parameters_by_date,
            "parameter_source": parameter_source,
            "parameter_sources": parameter_sources,
            "parameter_source_resolution": None,
            "encode_algorithm_names": encode_algorithm_names,
            "performance_source_paths": performance_source_paths,
            "execution_context": execution_context,
            "stage_options": stage_options,
            "refs": {
                "control": ref_repositories["control"],
                "treatments": [dict(item) for item in ref_repositories["treatments"]],
            },
            "criteria": criteria,
            "stage_sequence": list(stage_sequence),
            "default_until": _DEFAULT_UNTIL_STAGE,
        }

        status = {
            "run_id": run_id,
            "scenario": "qa",
            "state": "prepared",
            "created_at": now,
            "updated_at": now,
            "requested_mode": requested_mode,
            "target_stage": target_stage,
            "requested_stages": requested_stages,
            "current_stage": None,
            "completed_stages": [],
            "stage_status": _build_stage_status(requested_stages, stage_sequence),
            "stage_results": {},
            "notes": [],
        }

        paths = _scenario_paths(run_dir)
        _persist_qa_run_state(run_dir=run_dir, scenario=scenario, status=status)
        _append_jsonl(
            paths["decisions"],
            {
                "timestamp": now,
                "event": "run_created",
                "parameter_source": parameter_source,
                "parameter_sources": parameter_sources,
                "encode_algorithm_names": encode_algorithm_names,
                "performance_source_paths": performance_source_paths,
                "refs": scenario["refs"],
                "execution_context": execution_context,
                "stage_options": stage_options,
                "control_resolution": control_resolution,
                "prod_default_parameters_by_date": prod_default_parameters_by_date,
                "requested_mode": requested_mode,
                "target_stage": target_stage,
                "requested_stages": requested_stages,
            },
        )
        artifact_cache = _prepare_qa_run_execution(run_dir=run_dir, scenario=scenario, status=status)
        status["plan_sha256"] = _plan_sha256(scenario)
        _persist_qa_run_state(run_dir=run_dir, scenario=scenario, status=status)
        if _should_execute_requested_stages(status):
            _execute_requested_stages(
                action="created",
                run_dir=run_dir,
                scenario=scenario,
                status=status,
                artifact_cache=artifact_cache,
            )
        payload = _qa_run_output(action="created", run_dir=run_dir, scenario=scenario, status=status)
        print(json.dumps(payload, indent=2), flush=True)
        error_message = _qa_run_error_message(status)
        if error_message is not None:
            raise ValueError(error_message)


class ResumeCommand(BaseCommand):
    """Resume an existing QA run."""

    @classmethod
    def register_parser(cls, subparsers, *, command_prefix: str = "hv-qa"):
        parser = subparsers.add_parser(
            "resume",
            help="Resume an existing QA run",
            formatter_class=argparse.RawDescriptionHelpFormatter,
            epilog=f"""
Examples:
  {command_prefix} resume <run_id> --until multi_day_backtest
  {command_prefix} resume <run_id> --stage system_performance
            """,
        )
        _set_qa_run_parser_defaults(parser)
        parser.add_argument("run_id", help="Run id")
        parser.add_argument("--until", default="", help="Run until this stage, inclusive")
        parser.add_argument("--stage", dest="only", default="", help="Run exactly one stage")
        parser.add_argument("--work-dir", dest="meta_dir", default="", help="Base directory for hv-qa run state")
        return parser

    def execute(self, args):
        args.resume = str(args.run_id)
        args.control = ""
        args.prod_default_of_slot_as_control = ""
        args.treatments = []
        args.slot_name = ""
        args.last_test_date = ""
        args.backtest_days = ""
        args.evaluation_criteria = ""
        run_dir = _resolve_run_dir((args.meta_dir or "").strip() or None, args.resume)
        with _lock_run(run_dir):
            StartCommand().execute(args)


class StatusCommand(BaseCommand):
    """Show machine-readable QA run status."""

    @classmethod
    def register_parser(cls, subparsers, *, command_prefix: str = "hv-qa"):
        parser = subparsers.add_parser(
            "status",
            help="Print machine-readable status for a QA run",
        )
        parser.add_argument("run_id", help="Run id")
        parser.add_argument("--work-dir", dest="meta_dir", default="", help="Base directory for hv-qa run state")
        return parser

    def execute(self, args):
        run_dir = _resolve_run_dir((args.meta_dir or "").strip() or None, str(args.run_id))
        paths = _scenario_paths(run_dir)
        scenario = _read_json(paths["scenario"])
        status = _read_json(paths["status"])
        parameter_source = _normalize_parameter_source(scenario.get("parameter_source"))
        parameter_sources = _normalize_parameter_sources(scenario.get("parameter_sources"))
        encode_algorithm_names = _normalize_encode_algorithm_names(scenario.get("encode_algorithm_names"))
        performance_source_paths = _normalize_performance_source_paths(scenario.get("performance_source_paths"))
        execution_context = _normalize_execution_context_for_output(scenario.get("execution_context"))
        stage_options = _normalize_stage_options_for_output(scenario.get("stage_options"))
        payload = {
            "ok": _qa_run_error_message(status) is None,
            "run_id": scenario["run_id"],
            "run_dir": str(run_dir),
            "scenario": scenario["scenario"],
            "slot_name": scenario.get("slot_name"),
            "offline_context": scenario.get("offline_context"),
            "control_resolution": scenario.get("control_resolution"),
            "parameter_source": parameter_source,
            "parameter_sources": parameter_sources,
            "encode_algorithm_names": encode_algorithm_names,
            "performance_source_paths": performance_source_paths,
            "execution_context": execution_context,
            "stage_options": stage_options,
            "stage_sequence": scenario.get("stage_sequence", []),
            "criteria": scenario["criteria"],
            "refs": scenario["refs"],
            "status": status,
            "files": {
                "plan": str(paths["scenario"]),
                "status": str(paths["status"]),
                "decisions": str(paths["decisions"]),
                "evidence": str(paths["evidence"]),
            },
        }
        debug_handoff = _build_debug_handoff(run_dir=run_dir, scenario=scenario, status=status)
        if debug_handoff is not None:
            payload["debug_handoff"] = debug_handoff
        print(json.dumps(payload, indent=2))
