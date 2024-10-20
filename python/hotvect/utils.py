import asyncio
import collections
import collections.abc
import glob
import json
import logging
import os
import pathlib
import re
import shutil
import string
import subprocess
import sys
import time
import traceback
import zipfile
from asyncio.subprocess import PIPE
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path
from typing import Any, Dict, List, NamedTuple, Optional, Tuple
from urllib.parse import urlparse

import boto3
import psutil
import six
from pebble import ProcessFuture, ProcessPool

MAX_CONCURRENT_GIT_REF = 6.0
MAX_CONCURRENT_BACKTEST_ITERATION_PER_PIPELINE = 6.0
MAX_HOTVECT_TASK_THREADS = 6.0
QUEUE_LENGTH_PER_HOTVECT_TASK_THREAD = 4.0

logger = logging.getLogger(__name__)


class MalformedAlgorithmException(Exception):
    pass


# TODO write tests


class AlgorithmSpec(NamedTuple):
    algorithm_name: str
    algorithm_jar_path: Path


def write_data(data: Dict[str, Any], dest: str) -> Path:
    with open(dest, "w") as fp:
        json.dump(data, fp)
    return Path(dest)


def runshell(command, shell=False):
    cmd = " ".join(command) if is_iterable(command) else command
    logger.info(f"Running {cmd}")

    if shell:
        # Uses bash so that additional features are available
        p = subprocess.run(
            command,
            shell=shell,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            executable="/bin/bash",
        )
    else:
        p = subprocess.run(command, shell=shell, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    stdout = p.stdout.decode("utf-8")
    stderr = p.stderr.decode("utf-8")
    ret_code = p.returncode

    logger.debug(f"return_code: {ret_code}\n")
    logger.debug(f"stdout: {p.stdout.decode('utf-8')}\n")
    logger.debug(f"stderr: {p.stderr.decode('utf-8')}\n")

    if p.returncode != 0:
        raise ValueError(f"cmd: {cmd}, ret: {ret_code}, stderr: {stderr}, stdout: {stdout}")

    return {
        "command": cmd,
        "return_code": ret_code,
        "stderr": stderr,
        "stdout": stdout,
    }


def trydelete(file):
    try:
        os.remove(file)
        return True
    except Exception:
        try:
            shutil.rmtree(file)
            return True
        except OSError:
            return False


def read_json(file) -> Dict[Any, Any]:
    with open(file) as f:
        return json.load(f)


def beep():
    for _i in range(30):
        time.sleep(0.05)
    os.system("printf '\a'")


def to_zip_archive(to_archive: List[Tuple[str, str]], dest: str, compress_type=zipfile.ZIP_DEFLATED):
    with zipfile.ZipFile(dest, "w") as zipF:
        for src, arcname in to_archive:
            if os.path.exists(src):
                zipF.write(src, arcname=arcname, compress_type=compress_type)


def clean_dir(d: str):
    trydelete(d)
    Path(d).mkdir(parents=True, exist_ok=True)
    return


def prepare_dir(f: str):
    p = Path(f)
    if p.is_dir():
        clean_dir(f)
    else:
        clean_dir(str(p.parent))


def ensure_file_exists(file: str):
    if not os.path.isfile(file):
        raise ValueError(f"{file} not found")


def ensure_dir_exists(directory: str):
    if not os.path.isdir(directory):
        raise ValueError(f"{directory} not found")


def is_iterable(x: Any) -> bool:
    return isinstance(x, collections.abc.Iterable) and not isinstance(x, six.string_types)


def read_algorithm_definition_from_jar(
    algorithm_name: str, algorithm_jar_path: Path, additional_jars: Optional[List[Path]] = None
) -> Dict[str, Any]:
    jars = [algorithm_jar_path] if additional_jars is None else additional_jars + [algorithm_jar_path]
    algo_def_filename = f"{algorithm_name}-algorithm-definition.json"

    for jar in jars:
        with zipfile.ZipFile(jar, "r") as zip_file:
            list_zip_info = zip_file.infolist()
            for zip_info in list_zip_info:
                file_name = zip_info.filename
                if file_name == algo_def_filename:
                    return json.loads(zip_file.read(zip_info.filename).decode("utf8"))
    raise MalformedAlgorithmException(f"Algorithm Definition {algo_def_filename} not in any Jars: {jars}")


_algorithm_name_pattern = re.compile(r"^[\w\-_]+$")
_algorithm_version_pattern = re.compile(r"^[\w\-_\.]+$")
_algorithm_hyper_parameter_version_pattern = re.compile(r"^[\w\-_]+$")


def verify_algorithm_name(algorithm_name: str) -> str:
    if not _algorithm_name_pattern.match(algorithm_name):
        raise ValueError(f"Invalid algorithm name: {algorithm_name}, it must match {_algorithm_name_pattern.pattern}")
    return algorithm_name


def verify_algorithm_version(algorithm_version: str) -> str:
    if not _algorithm_version_pattern.match(algorithm_version):
        raise ValueError(
            f"Invalid algorithm version: {algorithm_version}, it must match {_algorithm_version_pattern.pattern}"
        )
    return algorithm_version


def verify_algorithm_hyperparameter_version(algorithm_hyperparameter_version: str) -> str:
    if not _algorithm_hyper_parameter_version_pattern.match(algorithm_hyperparameter_version):
        raise ValueError(
            f"Invalid algorithm hyperparameter version: {algorithm_hyperparameter_version}, it must match {_algorithm_hyper_parameter_version_pattern.pattern}"
        )
    return algorithm_hyperparameter_version


def try_max(li):
    if li:
        return max(li)
    else:
        return "NA"


def to_local_paths(data_base_dir: str, dates: List[date], fail_if_unavailable: bool = True) -> List[str]:
    all_available_date_paths = glob.glob(os.path.join(data_base_dir, "dt=*"))

    def to_dt(path: str) -> date:
        last_dir = pathlib.PurePath(path).name
        if match := re.search(r"^dt=(\d{4}-\d{2}-\d{2})$", last_dir):
            return date.fromisoformat(match.group(1))
        else:
            raise ValueError(r"Last directory must have the format ^dt=(\d{4}-\d{2}-\d{2})$ but was " + path)

    all_available_dates = {to_dt(path) for path in all_available_date_paths}

    if fail_if_unavailable:
        asked_dates = set(dates)
        assert asked_dates.issubset(all_available_dates), (
            f"Was asked for dates between {min(asked_dates)} and {max(asked_dates)} but these "
            f"dates were not available: {sorted(asked_dates - all_available_dates)}. Looked in: {data_base_dir}"
        )

    specified_dt_dirs = [x for x in all_available_date_paths if to_dt(x) in dates]
    return sorted(specified_dt_dirs)


@dataclass(frozen=True)
class InputDataDates:
    training_dates: List[date]
    test_dates: List[date]


def create_backtest_specs(
    num_of_training_runs: int,
    num_of_training_days: int,
    last_training_date: date,
    test_date_lag: int,
) -> List[InputDataDates]:
    ret: List[InputDataDates] = []
    for i in range(num_of_training_runs):
        current_last_training_day = last_training_date - timedelta(i)
        training_dates = [current_last_training_day - timedelta(days=x) for x in range(num_of_training_days)]
        testing_dates = [current_last_training_day + timedelta(days=test_date_lag)]
        ret.append(InputDataDates(training_dates, testing_dates))
    return ret


def to_required_data_dates(backtest_specs: List[InputDataDates]) -> InputDataDates:
    all_training_dates = list(sorted({d for backtest_spec in backtest_specs for d in backtest_spec.training_dates}))
    all_test_dates = list(sorted({d for backtest_spec in backtest_specs for d in backtest_spec.test_dates}))
    return InputDataDates(all_training_dates, all_test_dates)


class ConcurrencySetting(NamedTuple):
    total_backtest_pipelines: int
    nproc_per_backtest_pipeline: int
    threads_per_backtest_process: int
    queue_length_for_threads: int


def recommend_concurrency(
    num_git_ref: int, num_runs: int, remote_execution: bool, available_physical_cores: Optional[int] = None
) -> ConcurrencySetting:
    num_git_ref = float(num_git_ref)
    num_runs = float(num_runs)

    if not available_physical_cores:
        available_physical_cores = psutil.cpu_count(logical=False)

    assert available_physical_cores > 0

    total_backtest_pipelines = min(MAX_CONCURRENT_GIT_REF, min(num_git_ref, available_physical_cores))

    available_cores_per_backtest_pipeline = float(available_physical_cores) / total_backtest_pipelines

    hotvect_threads = min(MAX_HOTVECT_TASK_THREADS, max(1.0, available_cores_per_backtest_pipeline))

    backtest_iteration_concurrency = min(
        MAX_CONCURRENT_BACKTEST_ITERATION_PER_PIPELINE,
        min(num_runs, max(1.0, available_cores_per_backtest_pipeline / hotvect_threads)),
    )

    if remote_execution:
        # In case of remote execution, always run at max concurrency
        total_backtest_pipelines = num_git_ref
        backtest_iteration_concurrency = num_runs
        hotvect_threads = 0
        # Provide 0 for nThreads and queue size so that the hotvect java library can decide on the best numbers here

    return ConcurrencySetting(
        total_backtest_pipelines=round(total_backtest_pipelines),
        nproc_per_backtest_pipeline=round(backtest_iteration_concurrency),
        threads_per_backtest_process=round(hotvect_threads),
        queue_length_for_threads=round(hotvect_threads * QUEUE_LENGTH_PER_HOTVECT_TASK_THREAD),
    )


def get_result(future: ProcessFuture) -> Dict[str, Any]:
    try:
        return future.result()  # blocks until results are ready
    except Exception as ex:
        return {"error": "".join(traceback.format_exception(etype=type(ex), value=ex, tb=ex.__traceback__))}


def get_immediate_subdirectories(a_dir):
    return [os.path.join(a_dir, name) for name in os.listdir(a_dir) if os.path.isdir(os.path.join(a_dir, name))]


class DirectProcessPool(ProcessPool):
    """Appears to be a pebble ProcessPool, but in fact executes the tasks using the current process

    Useful for avoiding the problems with logging in multipricessing
    """

    def schedule(self, function, args=(), kwargs={}, timeout=None):
        ret = function(*args, **kwargs)

        class ImmediateFuture:
            def result(self):
                return ret

        return ImmediateFuture()

    def map(self, function, *iterables, **kwargs):
        raise ValueError("Not suported")


def recursive_dict_update(base_dict: Dict[Any, Any], update: Dict[Any, Any]) -> Dict[Any, Any]:
    if isinstance(base_dict, list):
        # This element is defined as a list in the base, but is given as a dict in the update
        # Hence convert the base into a dict first
        base_dict = {x: {} for x in base_dict}
    for k, v in update.items():
        if isinstance(v, collections.abc.Mapping):
            base_dict[k] = recursive_dict_update(base_dict.get(k, {}), v)
        else:
            base_dict[k] = v
    return base_dict


def get_boto_session_after_assuming_role(role_arn_to_assume):
    sts_client = boto3.client("sts")
    assumed = sts_client.assume_role(RoleArn=role_arn_to_assume, RoleSessionName="CrossAccountAccess")
    session = boto3.Session(
        aws_access_key_id=assumed["Credentials"]["AccessKeyId"],
        aws_secret_access_key=assumed["Credentials"]["SecretAccessKey"],
        aws_session_token=assumed["Credentials"]["SessionToken"],
    )
    return session


def hexigest_as_alphanumeric(hex_digest: str) -> str:
    """
    Converts a hex digest into a representation that uses [a-z, A-Z, 0-9] in order to
    compress it. This is useful when trying to cut down sagemaker job names
    Args:
        hex_digest:

    Returns:
        compressed hex
    """
    allowed_characters = string.ascii_letters + string.digits
    base = len(allowed_characters)

    h = int(hex_digest, 16)

    encoded = []
    while h:
        h, remainder = divmod(h, base)
        encoded.append(allowed_characters[remainder])

    return "".join(encoded)


async def _read_stream_and_display(stream, display):
    """Read from stream line by line until EOF, display, and capture the lines."""
    output = []
    async for line in stream:
        if not line:
            break
        output.append(line)
        display(line.decode())  # decoding bytes to string
    return b"".join(output)


async def _read_and_display(cmd, display_fun):
    """Capture cmd's stdout, stderr while displaying them as they arrive (line by line)."""
    process = await asyncio.create_subprocess_shell(cmd, stdout=PIPE, stderr=PIPE)

    try:
        stdout, stderr = await asyncio.gather(
            _read_stream_and_display(process.stdout, display_fun), _read_stream_and_display(process.stderr, display_fun)
        )
    except Exception:
        process.kill()
        raise
    finally:
        rc = await process.wait()
    return rc, stdout, stderr


def execute_command_with_live_output(cmd, display_fun):
    """Executes a command with an asyncio event loop."""
    if os.name == "nt":
        loop = asyncio.ProactorEventLoop()  # for subprocess' pipes on Windows
        asyncio.set_event_loop(loop)
    else:
        loop = asyncio.get_event_loop()

    rc, *_ = loop.run_until_complete(_read_and_display(cmd, display_fun))
    loop.close()
    return rc


def as_locally_available_content(cache_path: Optional[str], local_cache_path: str) -> Optional[str]:
    """
    This function checks if the provided cache_path is an S3 path or a local file path.
    If it's an S3 path, it downloads the file to the local_cache_path.
    If it's a local file path, it simply returns the path.

    Args:
        cache_path (Optional[str]): The cache path to check. This can be an S3 path or a local file path.
        local_cache_path (str): The local path where the file will be downloaded if the cache_path is an S3 path.

    Returns:
        Optional[str]: Returns the local file path if the cache_path is a local file path.
                      If the cache_path is an S3 path, it returns the local path of the file after downloading the file.
                      If the cache_path is not a file, it raises an exception.
    """
    if cache_path is None:
        return None

    # Check if the path is an S3 path
    if cache_path.startswith("s3://"):
        s3 = boto3.resource("s3")
        parsed_url = urlparse(cache_path)
        bucket_name = parsed_url.netloc
        key = parsed_url.path.lstrip("/")

        bucket = s3.Bucket(bucket_name)
        files = list(bucket.objects.filter(Prefix=key))
        if len(files) == 0:
            return None
        else:
            # Download the file to a local path
            local_file_path = os.path.join(local_cache_path, os.path.basename(files[0].key))
            bucket.download_file(files[0].key, local_file_path)
            return local_file_path

    # If it's not an S3 path, assume it's a local file path
    else:
        if not os.path.exists(cache_path):
            return None
        elif not os.path.isfile(cache_path):
            raise ValueError("The cache path is not a file.")
        else:
            return cache_path


def copy_or_link(source: str, dest: str):
    """
    Copies the source file to the destination, or creates a softlink if the OS supports it.

    Args:
        source (str): The source file path.
        dest (str): The destination file path.
    """
    if sys.platform == "win32":
        # Windows doesn't support soft links for all users
        shutil.copy(source, dest)
    else:
        # Unix-based systems (like Linux and MacOS) support soft links
        os.symlink(source, dest)


def store_file(src: str, dest: str):
    # Check if the destination is an S3 path
    if dest.startswith("s3://"):
        s3 = boto3.client("s3")

        # Parse out the bucket and key from the destination
        dest = dest.lstrip("s3://")
        bucket, key = dest.split("/", 1)

        s3.upload_file(src, bucket, key)
    else:
        # If the destination is not an S3 path, assume it's a local file path
        # and use the built-in shutil library to copy the file

        # Extract the directory part of the destination path
        dest_dir = os.path.dirname(dest)

        # Create any missing intermediate directories
        os.makedirs(dest_dir, exist_ok=True)

        # Copy the file
        shutil.copy(src, dest)
