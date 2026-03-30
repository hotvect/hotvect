import json
import logging
import os
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Optional
from urllib.parse import urlparse

import boto3
from mypy_boto3_s3 import S3Client

import hotvect.hotvectjar
from hotvect.sagemaker import ALGO_DEF_HYPERPARAMETER_PREFIX, ALGO_DEF_S3_URI_HYPERPARAMETER, unflatten_dict
from hotvect.utils import read_algorithm_definition_from_jar, runshell

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class OneShotTaskRequest:
    task: str
    algorithm_jar_s3_uri: str
    parameter_zip_s3_uri: Optional[str]
    source_dir: Path
    metadata_dir: Path
    output_dir: Path
    s3_uri_metadata: str
    s3_uri_result_file: str
    s3_uri_task_output: str
    samples: Optional[int]
    target_rps: Optional[float]
    target_throughput_fraction: Optional[float]
    workload_mode: Optional[str]
    algorithm_definition: Dict[str, Any]
    include_feature_store_responses: bool = False


def _upload_file_to_s3(local_file_path: str, s3_target_uri: str, s3_client: S3Client) -> None:
    s3_uri_parsed = urlparse(s3_target_uri)
    bucket: str = s3_uri_parsed.netloc
    key: str = s3_uri_parsed.path.lstrip("/")
    s3_client.upload_file(Filename=local_file_path, Bucket=bucket, Key=key)


def _upload_directory_to_s3(local_dir_path: str, s3_target_uri: str, s3_client: S3Client) -> None:
    basedir_name = os.path.basename(os.path.normpath(local_dir_path))
    for root, _, files in os.walk(local_dir_path):
        for file in files:
            file_path = os.path.join(root, file)
            relative_path = os.path.relpath(file_path, local_dir_path)
            s3_uri = os.path.join(s3_target_uri, basedir_name, relative_path).replace("\\", "/")
            _upload_file_to_s3(file_path, s3_uri, s3_client)


def _download_s3_file(s3_uri: str, dest_path: Path, s3_client: S3Client) -> None:
    p = urlparse(s3_uri)
    if p.scheme != "s3":
        raise ValueError(f"Expected s3:// uri, got {s3_uri!r}")
    s3_client.download_file(Bucket=p.netloc, Key=p.path.lstrip("/"), Filename=str(dest_path))


def _write_json(path: Path, data: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, sort_keys=True))


def _base_java_cmd(
    *, task: str, algorithm_jar_local_path: Path, algorithm_definition_path: Path, metadata_path: Path
) -> list[str]:
    return [
        "java",
        "-cp",
        str(hotvect.hotvectjar.HOTVECT_JAR_PATH),
        "com.hotvect.offlineutils.commandline.Main",
        task,
        "--algorithm-jar",
        str(algorithm_jar_local_path),
        "--algorithm-definition",
        str(algorithm_definition_path),
        "--metadata-path",
        str(metadata_path),
    ]


def _run_task(
    req: OneShotTaskRequest, *, local_algorithm_jar: Path, local_parameter_zip: Optional[Path]
) -> Dict[str, Any]:
    algo_def_path = req.metadata_dir / "algorithm_definition.json"
    _write_json(algo_def_path, req.algorithm_definition)

    task = req.task
    if task not in {"audit", "predict", "encode", "performance-test"}:
        raise ValueError(f"Unsupported hotvect_task: {task}")

    stage_metadata_dir = req.metadata_dir / task
    stage_metadata_dir.mkdir(parents=True, exist_ok=True)
    output_base = req.output_dir
    output_base.mkdir(parents=True, exist_ok=True)

    cmd = _base_java_cmd(
        task=task,
        algorithm_jar_local_path=local_algorithm_jar,
        algorithm_definition_path=algo_def_path,
        metadata_path=stage_metadata_dir,
    )

    if task == "encode":
        dest_dir = output_base / "encoded"
        schema_path = output_base / "encoded-schema-description"
        cmd.extend(
            [
                "--ordered",
                "--source",
                str(req.source_dir),
                "--dest",
                str(dest_dir),
                "--dest-schema-description",
                str(schema_path),
            ]
        )
        if req.samples is not None:
            cmd.extend(["--samples", str(req.samples)])
    elif task == "predict":
        if not local_parameter_zip:
            raise ValueError("predict requires s3_uri_parameter_zip")
        dest_path = output_base / "prediction.jsonl"
        cmd.extend(
            [
                "--source",
                str(req.source_dir),
                "--dest",
                str(dest_path),
                "--parameters",
                str(local_parameter_zip),
            ]
        )
        if req.include_feature_store_responses:
            cmd.append("--include-feature-store-responses")
        if req.samples is not None:
            cmd.extend(["--samples", str(req.samples)])
    elif task == "audit":
        if not local_parameter_zip:
            raise ValueError("audit requires s3_uri_parameter_zip")
        dest_path = output_base / "audit.jsonl"
        cmd.extend(
            [
                "--source",
                str(req.source_dir),
                "--dest",
                str(dest_path),
                "--parameters",
                str(local_parameter_zip),
            ]
        )
        if req.include_feature_store_responses:
            cmd.append("--include-feature-store-responses")
        if req.samples is not None:
            cmd.extend(["--samples", str(req.samples)])
    else:
        # performance-test
        if not local_parameter_zip:
            raise ValueError("performance-test requires s3_uri_parameter_zip")
        cmd.extend(["--source", str(req.source_dir), "--parameters", str(local_parameter_zip)])
        if req.samples is not None:
            cmd.extend(["--samples", str(req.samples)])
        if req.target_rps is not None:
            cmd.extend(["--target-rps", str(req.target_rps)])
        if req.target_throughput_fraction is not None:
            cmd.extend(["--target-throughput-fraction", str(req.target_throughput_fraction)])
        if req.workload_mode is not None:
            cmd.extend(["--workload-mode", str(req.workload_mode)])

    logger.info("Running one-shot task: %s", " ".join(cmd))
    runshell(cmd)

    metadata_file = stage_metadata_dir / "metadata.json"
    if metadata_file.exists():
        return json.loads(metadata_file.read_text())
    return {"warning": "metadata file missing", "metadata_path": str(metadata_file)}


def run_one_shot_from_sagemaker_env() -> None:
    logging.getLogger().setLevel(logging.INFO)
    try:
        from sagemaker_training.environment import Environment
    except ImportError as e:
        raise ImportError(
            "hotvect.sagemaker_tasks requires optional dependency 'sagemaker-training' "
            "(typically available in SageMaker Linux images)."
        ) from e

    env = Environment()
    hp = env.hyperparameters

    s3_client: S3Client = boto3.client("s3")

    required_hyperparameters = [
        "hotvect_task",
        "s3_uri_algorithm_jar",
        "s3_uri_metadata",
        "s3_uri_result_file",
        "s3_uri_task_output",
    ]
    missing = [k for k in required_hyperparameters if k not in hp or hp[k] in (None, "")]
    if missing:
        raise ValueError(
            "run_one_shot_from_sagemaker_env must be executed inside a SageMaker training container with the "
            f"required hyperparameters set. Missing: {', '.join(missing)}"
        )

    task = hp["hotvect_task"]
    s3_uri_algorithm_jar = hp["s3_uri_algorithm_jar"]
    s3_uri_metadata = hp["s3_uri_metadata"]
    s3_uri_result_file = hp["s3_uri_result_file"]
    s3_uri_task_output = hp["s3_uri_task_output"]
    s3_uri_parameter_zip = hp.get("s3_uri_parameter_zip")
    include_feature_store_responses = str(hp.get("hotvect_include_feature_store_responses", "false")).lower() in {
        "1",
        "true",
        "yes",
    }

    samples = None
    if "hotvect_samples" in hp and hp["hotvect_samples"] not in (None, ""):
        try:
            samples = int(hp["hotvect_samples"])
        except Exception:
            raise ValueError(f"hotvect_samples must be an int, got {hp['hotvect_samples']!r}")

    target_rps = None
    if "hotvect_target_rps" in hp and hp["hotvect_target_rps"] not in (None, ""):
        try:
            target_rps = float(hp["hotvect_target_rps"])
        except Exception:
            raise ValueError(f"hotvect_target_rps must be a float, got {hp['hotvect_target_rps']!r}")

    target_throughput_fraction = None
    if "hotvect_target_throughput_fraction" in hp and hp["hotvect_target_throughput_fraction"] not in (None, ""):
        try:
            target_throughput_fraction = float(hp["hotvect_target_throughput_fraction"])
        except Exception:
            raise ValueError(
                "hotvect_target_throughput_fraction must be a float, got "
                f"{hp['hotvect_target_throughput_fraction']!r}"
            )

    workload_mode = None
    if "hotvect_workload_mode" in hp and hp["hotvect_workload_mode"] not in (None, ""):
        workload_mode = str(hp["hotvect_workload_mode"])

    algorithm_definition: Dict[str, Any] = {}
    if ALGO_DEF_S3_URI_HYPERPARAMETER in hp and hp[ALGO_DEF_S3_URI_HYPERPARAMETER]:
        s3_uri = hp[ALGO_DEF_S3_URI_HYPERPARAMETER]
        p = urlparse(s3_uri)
        if p.scheme != "s3":
            raise ValueError(f"{ALGO_DEF_S3_URI_HYPERPARAMETER} must be an s3:// uri, got {s3_uri!r}")
        fp = tempfile.NamedTemporaryFile(delete=False)
        try:
            fp.close()
            s3_client.download_file(Bucket=p.netloc, Key=p.path.lstrip("/"), Filename=fp.name)
            with open(fp.name, "rb") as fin:
                algorithm_definition = json.load(fin)
        finally:
            try:
                os.unlink(fp.name)
            except Exception:
                pass
    else:
        algorithm_definition = unflatten_dict(hp, ALGO_DEF_HYPERPARAMETER_PREFIX)

    source_channel = hp.get("hotvect_source_channel", "source")
    source_dir = Path(env.input_dir) / "data" / source_channel

    metadata_dir = Path(env.output_data_dir) / "meta"
    output_dir = Path(env.output_data_dir) / "out"
    metadata_dir.mkdir(parents=True, exist_ok=True)
    output_dir.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory() as tmpd:
        tmp = Path(tmpd)
        local_algorithm_jar = tmp / Path(urlparse(s3_uri_algorithm_jar).path).name
        _download_s3_file(s3_uri_algorithm_jar, local_algorithm_jar, s3_client)

        if not algorithm_definition:
            algo_name = unflatten_dict(hp, ALGO_DEF_HYPERPARAMETER_PREFIX).get("algorithm_name")
            if not algo_name:
                raise ValueError("Missing algorithm definition in SageMaker hyperparameters (_algo_def_algorithm_name)")
            algorithm_definition = read_algorithm_definition_from_jar(
                algorithm_name=algo_name,
                algorithm_jar_path=local_algorithm_jar,
                additional_jars=[],
            )

        local_parameter_zip = None
        if s3_uri_parameter_zip:
            local_parameter_zip = tmp / Path(urlparse(s3_uri_parameter_zip).path).name
            _download_s3_file(s3_uri_parameter_zip, local_parameter_zip, s3_client)

        req = OneShotTaskRequest(
            task=task,
            algorithm_jar_s3_uri=s3_uri_algorithm_jar,
            parameter_zip_s3_uri=s3_uri_parameter_zip,
            source_dir=source_dir,
            metadata_dir=metadata_dir,
            output_dir=output_dir,
            s3_uri_metadata=s3_uri_metadata,
            s3_uri_result_file=s3_uri_result_file,
            s3_uri_task_output=s3_uri_task_output,
            samples=samples,
            target_rps=target_rps,
            target_throughput_fraction=target_throughput_fraction,
            workload_mode=workload_mode,
            algorithm_definition=algorithm_definition,
            include_feature_store_responses=include_feature_store_responses,
        )

        try:
            task_metadata = _run_task(
                req, local_algorithm_jar=local_algorithm_jar, local_parameter_zip=local_parameter_zip
            )

            _upload_directory_to_s3(str(metadata_dir), s3_uri_metadata, s3_client)

            if req.output_dir.exists():
                _upload_directory_to_s3(str(req.output_dir), s3_uri_task_output, s3_client)

            result = {
                "task": task,
                "requested_samples": samples,
                "requested_target_rps": target_rps,
                "requested_target_throughput_fraction": target_throughput_fraction,
                "requested_workload_mode": workload_mode,
                "s3_uri_metadata": s3_uri_metadata,
                "s3_uri_task_output": s3_uri_task_output,
                "task_metadata": task_metadata,
            }
            result_path = metadata_dir / "result.oneshot.json"
            _write_json(result_path, result)
            _upload_file_to_s3(str(result_path), s3_uri_result_file, s3_client)
            logger.info("One-shot task completed successfully.")
        except Exception as e:
            _upload_directory_to_s3(str(metadata_dir), s3_uri_metadata, s3_client)
            failure_path = Path(env.output_dir) / "failure"
            failure_path.write_text(str(e))
            raise
