import copy
import json
import logging
import os
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tarfile
import tempfile
import time
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path
from typing import Any, Optional
from urllib.parse import urlparse

import boto3
from mypy_boto3_s3 import S3Client
from mypy_boto3_sagemaker import SageMakerClient

from hotvect import utils
from hotvect.build_utils import parse_pom_xml, select_algorithm_jar
from hotvect.utils import (
    capture_output,
    get_boto_session_after_assuming_role,
    hexigest_as_alphanumeric,
    prepare_dir,
    stream_output,
)

logger = logging.getLogger(__name__)


def runshell(command, shell: bool = False, env: dict[str, str] | None = None):
    return capture_output(command, shell=shell, env=env)


def _prepare_jar(repo_url: str, work_dir: str, git_reference: str) -> Path:
    source_path = Path(os.path.join(work_dir, "source"))
    prepare_dir(str(source_path))
    stream_output(["git", "clone", repo_url], sys.stdout.write, cwd=str(source_path))
    cloned_path = utils.get_immediate_subdirectories(source_path)
    assert (
        len(cloned_path) == 1
    ), f"More than one path found in algo source path after cloning:{os.path.abspath(source_path)}"
    cloned_path = next(iter(cloned_path))
    stream_output(["git", "fetch", "--all", "--tags"], sys.stdout.write, cwd=str(cloned_path))
    stream_output(["git", "checkout", git_reference], sys.stdout.write, cwd=str(cloned_path))
    stream_output(["git", "clean", "-df"], sys.stdout.write, cwd=str(cloned_path))
    # `-DskipTests` skips running tests but still compiles them.
    # For training we only need the algorithm JAR, so skip test compilation as well.
    stream_output(["mvn", "clean", "package", "-Dmaven.test.skip=true", "-B"], sys.stdout.write, cwd=str(cloned_path))
    artifact = parse_pom_xml(Path(cloned_path) / "pom.xml")
    return select_algorithm_jar(Path(cloned_path) / "target", artifact)


def run_remote_using_git_reference(
    remote_work_dir: str,
    local_work_dir: str,
    repo_url: str,
    git_reference: str,
    sagemaker_training_job_definition: dict[str, Any],
    last_target_time: date,
    number_of_runs: int,
    role_arn_to_assume: str | None = None,
    hyperparameters: dict[str, Any] | None = None,
):
    # Local import to avoid importing heavy Hotvect modules (and requiring the bundled JAR) at import time.
    from hotvect.sagemaker import _upload_file_to_s3

    if hyperparameters is None:
        hyperparameters = {}
    if "HyperParameters" not in sagemaker_training_job_definition:
        sagemaker_training_job_definition["HyperParameters"] = hyperparameters
    else:
        sagemaker_training_job_definition["HyperParameters"].update(hyperparameters)

    session = get_boto_session_after_assuming_role(role_arn_to_assume) if role_arn_to_assume else boto3.Session()

    jar = _prepare_jar(repo_url=repo_url, work_dir=local_work_dir, git_reference=git_reference)
    s3_client: S3Client = session.client("s3")
    destination = os.path.join(remote_work_dir, "customjar/" + os.path.basename(jar))
    _upload_file_to_s3(local_file_path=str(jar), s3_target_uri=destination, s3_client=s3_client)

    sagemaker_training_job_definition["HyperParameters"]["s3_uri_custom_jar"] = destination
    sagemaker_client: SageMakerClient = session.client("sagemaker")

    def updated_sagemaker_training_job_definition(target_day: str) -> dict[str, Any]:
        copy_of_sagemaker_training_job_definition = copy.deepcopy(sagemaker_training_job_definition)
        job_name = copy_of_sagemaker_training_job_definition["TrainingJobName"]
        job_name += f"-{hexigest_as_alphanumeric(secrets.token_hex(4))}"
        job_name += f"-{git_reference[:6]}"
        job_name += "-placeholder"
        job_name += target_day
        copy_of_sagemaker_training_job_definition["TrainingJobName"] = job_name
        copy_of_sagemaker_training_job_definition["HyperParameters"]["target_dt"] = target_day
        return copy_of_sagemaker_training_job_definition

    target_days = [last_target_time - timedelta(days=i) for i in range(number_of_runs)]
    for target_day in target_days:
        this_iteration_sagemaker_training_job_definition = updated_sagemaker_training_job_definition(
            target_day.isoformat()
        )
        sagemaker_client.create_training_job(**this_iteration_sagemaker_training_job_definition)


class SageMakerScriptExecutor:
    def __init__(self, *, sagemaker_env: Any | None = None, s3_client: S3Client | None = None):
        self.sagemaker_env = sagemaker_env
        self._s3_client: S3Client = s3_client or boto3.client("s3")

        if self.sagemaker_env is None:
            try:
                from sagemaker_training.environment import Environment
            except ModuleNotFoundError:
                # `sagemaker-training` is an optional dependency (typically installed in SageMaker containers).
                # Allow injecting a lightweight env for local testing, and raise a clear error at runtime if
                # neither is available.
                return

            self.sagemaker_env = Environment()

    def run(self) -> dict[str, Any]:
        if self.sagemaker_env is None:
            raise ModuleNotFoundError(
                "SageMakerScriptExecutor requires `sagemaker-training` (sagemaker_training) or an injected "
                "`sagemaker_env`. Install with `pip install 'hotvect[sagemaker]'` (in SageMaker containers this "
                "is typically already present)."
            )

        logging.getLogger().setLevel(self.sagemaker_env.log_level)
        local_custom_jar = self._download_custom_jar()

        temp_dir = tempfile.mkdtemp()
        shutil.unpack_archive(filename=local_custom_jar, extract_dir=temp_dir, format="zip")

        hyperparameters_copy = copy.deepcopy(self.sagemaker_env.hyperparameters)
        hyperparameters_copy["custom_jar_path"] = local_custom_jar
        hyperparameters_copy["input_dir"] = self.sagemaker_env.input_dir
        hyperparameters_file = self.hyperparameters_as_file(hyperparameters_copy)

        script_location = os.path.join(temp_dir, "custom.py")
        tracing_ctx = self._maybe_start_local_tracing(hyperparameters=hyperparameters_copy)
        try:
            cmd = ["python", script_location, hyperparameters_file]
            if tracing_ctx:
                return runshell(cmd, env=tracing_ctx.env)
            return runshell(cmd)
        finally:
            if tracing_ctx:
                self._finalize_local_tracing(tracing_ctx=tracing_ctx, hyperparameters=hyperparameters_copy)

    @dataclass(frozen=True)
    class _LocalTracingContext:
        env: dict[str, str]
        jaeger_proc: subprocess.Popen
        output_dir: Path
        log_path: Path

    _TRACE_MODE_KEY = "otel_trace_mode"
    _TRACE_MODE_LOCAL_JAEGER = "local_jaeger"
    _S3_URI_JAEGER_BIN_KEY = "s3_uri_jaeger_all_in_one"
    _S3_URI_OTEL_JAVAAGENT_KEY = "s3_uri_otel_javaagent"
    _SERVICE_NAME_KEY = "otel_service_name"
    _TRACE_RATIO_KEY = "otel_trace_ratio"

    def _maybe_start_local_tracing(self, *, hyperparameters: dict[str, Any]) -> Optional["_LocalTracingContext"]:
        trace_mode = str(hyperparameters.get(self._TRACE_MODE_KEY, "")).strip()
        if trace_mode != self._TRACE_MODE_LOCAL_JAEGER:
            return None

        s3_uri_jaeger_bin = hyperparameters.get(self._S3_URI_JAEGER_BIN_KEY)
        s3_uri_javaagent = hyperparameters.get(self._S3_URI_OTEL_JAVAAGENT_KEY)
        s3_uri_metadata = hyperparameters.get("s3_uri_metadata")
        if not s3_uri_jaeger_bin:
            raise KeyError(
                f"{self._TRACE_MODE_KEY}={self._TRACE_MODE_LOCAL_JAEGER!r} requires hyperparameter {self._S3_URI_JAEGER_BIN_KEY!r}"
            )
        if not s3_uri_javaagent:
            raise KeyError(
                f"{self._TRACE_MODE_KEY}={self._TRACE_MODE_LOCAL_JAEGER!r} requires hyperparameter {self._S3_URI_OTEL_JAVAAGENT_KEY!r}"
            )
        if not s3_uri_metadata:
            raise KeyError(
                f"{self._TRACE_MODE_KEY}={self._TRACE_MODE_LOCAL_JAEGER!r} requires hyperparameter 's3_uri_metadata' "
                "so the trace archive can be uploaded under metadata/otel/"
            )

        output_dir = Path("/opt/ml/output/otel-jaeger").resolve()
        output_dir.mkdir(parents=True, exist_ok=True)
        log_path = output_dir / "jaeger-all-in-one.log"

        jaeger_bin_path = Path(tempfile.mkdtemp()) / "jaeger-all-in-one"
        self._download_s3_file(s3_uri=str(s3_uri_jaeger_bin), dest_path=jaeger_bin_path)
        jaeger_bin_path.chmod(jaeger_bin_path.stat().st_mode | 0o111)

        javaagent_path = output_dir / "opentelemetry-javaagent.jar"
        self._download_s3_file(s3_uri=str(s3_uri_javaagent), dest_path=javaagent_path)

        badger_key_dir = output_dir / "badger" / "key"
        badger_value_dir = output_dir / "badger" / "value"
        badger_key_dir.mkdir(parents=True, exist_ok=True)
        badger_value_dir.mkdir(parents=True, exist_ok=True)

        jaeger_env = os.environ.copy()
        jaeger_env.update(
            {
                "SPAN_STORAGE_TYPE": "badger",
                "BADGER_EPHEMERAL": "false",
                "BADGER_DIRECTORY_KEY": str(badger_key_dir),
                "BADGER_DIRECTORY_VALUE": str(badger_value_dir),
            }
        )

        logger.info("Starting local Jaeger all-in-one for OTLP ingestion (traces will be archived).")
        with log_path.open("ab") as log_fp:
            jaeger_proc = subprocess.Popen(
                [
                    str(jaeger_bin_path),
                    "--collector.otlp.enabled=true",
                ],
                stdout=log_fp,
                stderr=subprocess.STDOUT,
                env=jaeger_env,
            )

        try:
            self._wait_for_tcp("127.0.0.1", 4317, timeout_s=30.0)
        except Exception:
            self._stop_process(jaeger_proc)
            raise

        env = os.environ.copy()
        env.update(
            {
                "OTEL_TRACES_EXPORTER": "otlp",
                "OTEL_EXPORTER_OTLP_ENDPOINT": "http://127.0.0.1:4317",
                "OTEL_EXPORTER_OTLP_PROTOCOL": "grpc",
                "OTEL_METRICS_EXPORTER": "none",
                "OTEL_LOGS_EXPORTER": "none",
            }
        )

        service_name = str(hyperparameters.get(self._SERVICE_NAME_KEY, "")).strip()
        if service_name:
            env["OTEL_SERVICE_NAME"] = service_name

        trace_ratio_raw = hyperparameters.get(self._TRACE_RATIO_KEY, 0.01)
        try:
            trace_ratio = float(trace_ratio_raw)
        except Exception as e:
            raise ValueError(f"Invalid {self._TRACE_RATIO_KEY!r}: {trace_ratio_raw!r}") from e
        trace_ratio = max(0.0, min(1.0, trace_ratio))
        env["OTEL_TRACES_SAMPLER"] = "traceidratio"
        env["OTEL_TRACES_SAMPLER_ARG"] = str(trace_ratio)

        existing_java_tool_options = env.get("JAVA_TOOL_OPTIONS", "").strip()
        javaagent_flag = f"-javaagent:{javaagent_path}"
        env["JAVA_TOOL_OPTIONS"] = f"{javaagent_flag} {existing_java_tool_options}".strip()

        return self._LocalTracingContext(env=env, jaeger_proc=jaeger_proc, output_dir=output_dir, log_path=log_path)

    def _finalize_local_tracing(self, *, tracing_ctx: "_LocalTracingContext", hyperparameters: dict[str, Any]) -> None:
        self._stop_process(tracing_ctx.jaeger_proc)

        archive_path = tracing_ctx.output_dir / "otel-jaeger-traces.tgz"
        self._create_trace_archive(output_dir=tracing_ctx.output_dir, archive_path=archive_path)

        s3_uri_metadata = hyperparameters["s3_uri_metadata"]
        s3_uri_archive = self._s3_join_prefix(str(s3_uri_metadata), "otel/jaeger-traces.tgz")
        logger.info("Uploading trace archive: %s", s3_uri_archive)
        self._upload_file_to_s3(local_file_path=str(archive_path), s3_target_uri=str(s3_uri_archive))

    def _wait_for_tcp(self, host: str, port: int, timeout_s: float) -> None:
        deadline = time.time() + timeout_s
        last_err: Exception | None = None
        while time.time() < deadline:
            try:
                with socket.create_connection((host, port), timeout=1.0):
                    return
            except Exception as e:
                last_err = e
                time.sleep(0.25)
        raise TimeoutError(f"Timed out waiting for TCP {host}:{port}") from last_err

    def _stop_process(self, proc: subprocess.Popen) -> None:
        try:
            proc.send_signal(signal.SIGTERM)
            proc.wait(timeout=10.0)
            return
        except Exception:
            pass
        try:
            proc.kill()
        except Exception:
            return
        try:
            proc.wait(timeout=5.0)
        except Exception:
            pass

    def _create_trace_archive(self, *, output_dir: Path, archive_path: Path) -> None:
        archive_path.parent.mkdir(parents=True, exist_ok=True)
        with tarfile.open(archive_path, mode="w:gz") as tar:
            for rel in ("badger", "jaeger-all-in-one.log", "opentelemetry-javaagent.jar"):
                p = output_dir / rel
                if p.exists():
                    tar.add(str(p), arcname=rel)

    def _s3_join_prefix(self, s3_uri_prefix: str, suffix: str) -> str:
        parsed = urlparse(str(s3_uri_prefix))
        if parsed.scheme != "s3" or not parsed.netloc or not parsed.path:
            raise ValueError(f"Expected s3:// uri, got: {s3_uri_prefix!r}")
        key_prefix = parsed.path.lstrip("/")
        key_prefix = key_prefix.rstrip("/") + "/"
        return f"s3://{parsed.netloc}/{key_prefix}{suffix.lstrip('/')}"

    def _download_s3_file(self, *, s3_uri: str, dest_path: Path) -> None:
        parsed = urlparse(str(s3_uri))
        if parsed.scheme != "s3" or not parsed.netloc or not parsed.path:
            raise ValueError(f"Expected s3:// uri, got: {s3_uri!r}")
        dest_path.parent.mkdir(parents=True, exist_ok=True)
        self._s3_client.download_file(
            Bucket=parsed.netloc,
            Key=parsed.path.lstrip("/"),
            Filename=str(dest_path),
        )

    def _upload_file_to_s3(self, *, local_file_path: str, s3_target_uri: str) -> None:
        s3_uri_parsed = urlparse(s3_target_uri)
        s3_target_bucket: str = s3_uri_parsed.netloc
        s3_target_key: str = s3_uri_parsed.path.lstrip("/")

        self._s3_client.upload_file(Filename=local_file_path, Bucket=s3_target_bucket, Key=s3_target_key)

    def hyperparameters_as_file(self, hyperparameters: dict[str, Any]):
        class StringEncoder(json.JSONEncoder):
            def default(self, o):
                return str(o)

        hyperparameters_file = tempfile.NamedTemporaryFile(mode="w", delete=False)
        json.dump(hyperparameters, hyperparameters_file, cls=StringEncoder)
        hyperparameters_file.close()
        return hyperparameters_file.name

    def _download_custom_jar(self) -> Path:
        s3_uri_custom_jar = self.sagemaker_env.hyperparameters["s3_uri_custom_jar"]
        custom_jar_local_path = s3_uri_custom_jar.split("/")[-1]
        s3_uri_custom_jar_parsed = urlparse(s3_uri_custom_jar)
        s3_custom_jar_bucket: str = s3_uri_custom_jar_parsed.netloc
        s3_custom_jar_key: str = s3_uri_custom_jar_parsed.path.lstrip("/")
        self._s3_client.download_file(
            Bucket=s3_custom_jar_bucket, Key=s3_custom_jar_key, Filename=custom_jar_local_path
        )
        return Path(custom_jar_local_path)
