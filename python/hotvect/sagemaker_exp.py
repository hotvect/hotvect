import copy
import glob
import json
import logging
import os
import re
import secrets
import shutil
import tempfile
from datetime import date, timedelta
from pathlib import Path
from typing import Any, Dict, Optional
from urllib.parse import urlparse
from xml.etree import ElementTree

import boto3
from mypy_boto3_s3 import S3Client
from mypy_boto3_sagemaker import SageMakerClient
from sagemaker_training.environment import Environment

from hotvect import utils
from hotvect.sagemaker import _upload_file_to_s3
from hotvect.utils import get_boto_session_after_assuming_role, hexigest_as_alphanumeric, prepare_dir, runshell

logger = logging.getLogger(__name__)


def _prepare_jar(repo_url: str, work_dir: str, git_reference: str) -> Path:
    source_path = Path(os.path.join(work_dir, "source"))
    prepare_dir(str(source_path))
    runshell(
        f"cd {source_path} && git clone {repo_url}",
        shell=True,
    )
    cloned_path = utils.get_immediate_subdirectories(source_path)
    assert (
        len(cloned_path) == 1
    ), f"More than one path found in algo source path after cloning:{os.path.abspath(source_path)}"
    cloned_path = next(iter(cloned_path))
    runshell(
        (f"cd {cloned_path} && " "git fetch --all --tags && " f"git checkout {git_reference} && " "git clean -df"),
        shell=True,
    )
    runshell(
        f"cd {cloned_path} && mvn clean package -DskipTests -B",
        shell=True,
    )
    pom_path = f"{cloned_path}/pom.xml"
    xml_root = ElementTree.parse(pom_path).getroot()
    ns = re.match(r"{.*}", xml_root.tag).group(0)
    artifact_name = xml_root.find(ns + "artifactId").text.strip()
    artifact_version = xml_root.find(ns + "version").text.strip()
    jars = [
        file
        for file in glob.glob(
            os.path.join(
                cloned_path,
                "target",
                f"{artifact_name}-{artifact_version}*.jar",
            )
        )
        if os.path.isfile(file)
    ]
    if len(jars) != 1:
        raise ValueError(f"JAR not found or there are more than one! {jars}")
    return Path(jars[0])


def run_remote_using_git_reference(
    remote_work_dir: str,
    local_work_dir: str,
    repo_url: str,
    git_reference: str,
    sagemaker_training_job_definition: Dict[str, Any],
    last_target_time: date,
    number_of_runs: int,
    role_arn_to_assume: Optional[str] = None,
    hyperparameters: Optional[Dict[str, Any]] = None,
):
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

    def updated_sagemaker_training_job_definition(target_day: str) -> Dict[str, Any]:
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
    def __init__(self):
        self.sagemaker_env = Environment()
        self._s3_client: S3Client = boto3.client("s3")

    def run(self) -> Dict[str, Any]:
        logging.getLogger().setLevel(self.sagemaker_env.log_level)
        local_custom_jar = self._download_custom_jar()

        temp_dir = tempfile.mkdtemp()
        shutil.unpack_archive(filename=local_custom_jar, extract_dir=temp_dir, format="zip")

        hyperparameters_copy = copy.deepcopy(self.sagemaker_env.hyperparameters)
        hyperparameters_copy["custom_jar_path"] = local_custom_jar
        hyperparameters_copy["input_dir"] = self.sagemaker_env.input_dir
        hyperparameters_file = self.hyperparameters_as_file(hyperparameters_copy)

        script_location = os.path.join(temp_dir, "custom.py")
        return runshell(["python", script_location, hyperparameters_file], shell=True)

    def hyperparameters_as_file(self, hyperparameters: Dict[str, Any]):
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
