import asyncio
import collections
import collections.abc
import glob
import json
import logging
import os
import pathlib
import platform
import re
import shutil
import string
import subprocess
import sys
import tarfile
import time
import traceback
import zipfile
from asyncio.subprocess import PIPE
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import TYPE_CHECKING, Any, Dict, List, NamedTuple, Optional, Tuple
from urllib.parse import unquote, urlparse

import boto3
import psutil
from pebble import ProcessFuture, ProcessPool

if TYPE_CHECKING:
    from hotvect.pyhotvect import DataDependency

MAX_CONCURRENT_GIT_REF = 6.0
MAX_CONCURRENT_BACKTEST_ITERATION_PER_PIPELINE = 6.0
MAX_HOTVECT_TASK_THREADS = 6.0
QUEUE_LENGTH_PER_HOTVECT_TASK_THREAD = 4.0

logger = logging.getLogger(__name__)


class MalformedAlgorithmException(Exception):
    pass


def sanitize_path_component(value: str, max_length: int = 80) -> str:
    """
    Sanitize an arbitrary string to be safe as a single filesystem path component.

    This is primarily used to safely incorporate user-provided identifiers (e.g. git refs like
    "feature/foo") into directory names without accidentally creating nested paths.
    """
    if value is None:
        return "value"

    sanitized = str(value)

    # Replace common path separators first.
    for sep in (os.sep, os.altsep, "/", "\\"):
        if sep:
            sanitized = sanitized.replace(sep, "_")

    # Replace other potentially problematic characters.
    sanitized = re.sub(r"[^A-Za-z0-9._-]+", "_", sanitized)
    sanitized = sanitized.strip("._-")

    if not sanitized:
        sanitized = "value"

    return sanitized[:max_length]


# TODO write tests


def resolve_path_within_base(base_dir: str | os.PathLike, relative_path: str) -> Path:
    """
    Resolve a path under ``base_dir`` and fail if it escapes the base directory.
    """
    base_path = Path(base_dir).resolve()
    resolved_path = (base_path / relative_path).resolve()
    if not resolved_path.is_relative_to(base_path):
        raise ValueError(f"Refusing path outside base directory: {relative_path!r}")
    return resolved_path


def safe_extract_tar_archive(tar: tarfile.TarFile, dest_dir: str | os.PathLike) -> None:
    """
    Extract a tar archive after validating all entries stay within ``dest_dir``.

    Rejects path traversal and link/special-file entries.
    """
    destination = Path(dest_dir).resolve()
    destination.mkdir(parents=True, exist_ok=True)

    for member in tar.getmembers():
        resolve_path_within_base(destination, member.name)
        if member.issym() or member.islnk():
            raise ValueError(f"Refusing to extract link entry from tar archive: {member.name!r}")
        if member.ischr() or member.isblk() or member.isfifo():
            raise ValueError(f"Refusing to extract special entry from tar archive: {member.name!r}")

    tar.extractall(path=destination)


class AlgorithmSpec(NamedTuple):
    algorithm_name: str
    algorithm_jar_path: Path
    git_commit_hash: str


def write_data(data: Dict[str, Any], dest: str) -> Path:
    with open(dest, "w") as fp:
        json.dump(data, fp)
    return Path(dest)


def capture_output(command, shell: bool = False, cwd: str | os.PathLike | None = None, env: dict | None = None):
    """
    Execute a command and capture its output for programmatic use.

    Use this when you need to parse stdout/stderr. For user-facing operations
    where live progress feedback is desired, use stream_output() instead.

    Args:
        command: Command to execute (list or string if shell=True)
        shell: Whether to use shell execution

    Returns:
        Dict with keys: command, return_code, stdout, stderr

    Raises:
        subprocess.CalledProcessError: If command returns non-zero exit code
    """
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
            cwd=cwd,
            env=env,
        )
    else:
        p = subprocess.run(command, shell=shell, stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=cwd, env=env)
    stdout = p.stdout.decode("utf-8")
    stderr = p.stderr.decode("utf-8")
    ret_code = p.returncode

    logger.debug(f"return_code: {ret_code}\n")
    logger.debug(f"stdout: {p.stdout.decode('utf-8')}\n")
    logger.debug(f"stderr: {p.stderr.decode('utf-8')}\n")

    if p.returncode != 0:
        raise subprocess.CalledProcessError(returncode=ret_code, cmd=cmd, output=stdout, stderr=stderr)

    return {
        "command": cmd,
        "return_code": ret_code,
        "stderr": stderr,
        "stdout": stdout,
    }


# Backward compatibility alias - will be removed in future version
def runshell(command, shell=False):
    """Deprecated: Use capture_output() instead."""
    return capture_output(command, shell)


def trydelete(file):
    file = str(file)
    try:
        os.remove(file)
        return True
    except FileNotFoundError:
        return True
    except (IsADirectoryError, OSError):
        pass

    try:
        shutil.rmtree(file)
        return True
    except FileNotFoundError:
        return True
    except Exception:
        logger.exception(f"Failed to delete {file}")
        return False


def read_json(file) -> Dict[Any, Any]:
    with open(file) as f:
        return json.load(f)


def beep():
    for _i in range(30):
        time.sleep(0.05)
    os.system("printf '\a'")


def _has_7z() -> bool:
    """Check if 7z is available in PATH."""
    try:
        subprocess.run(["7z", "--help"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
        return True
    except (subprocess.CalledProcessError, FileNotFoundError):
        return False


def _to_zip_archive_with_7z(to_archive: List[Tuple[str, str]], dest: str) -> None:
    """
    Create a zip archive using 7z with parallel compression and hard links.

    Args:
        to_archive: List of (source_path, archive_name) tuples
        dest: Destination zip file path

    Raises:
        FileNotFoundError: If any source file doesn't exist
    """
    import tempfile

    # First, verify all source files exist
    for src, arcname in to_archive:
        if not os.path.exists(src):
            raise FileNotFoundError(f"Source file does not exist: {src}")

    # Create temporary directory for hard link staging
    with tempfile.TemporaryDirectory() as tmpdir:
        # Create hard links with archive structure
        for src, arcname in to_archive:
            link_path = os.path.join(tmpdir, arcname)
            link_dir = os.path.dirname(link_path)

            # Create parent directories if needed
            if link_dir:
                os.makedirs(link_dir, exist_ok=True)

            # Stage content in temp dir using hard links for speed.
            # If hard-linking is not permitted, bubble up so we can fall back to Python zipfile.
            src_abs = os.path.abspath(src)
            if os.path.isdir(src_abs):
                raise ValueError(f"7z ZIP staging expects files, got directory: {src_abs}")
            os.link(src_abs, link_path)

        # Use 7z to create archive with parallel compression
        # -tzip: zip format
        # -mmt: multi-threaded compression (uses all available cores)
        # We need to run from within tmpdir to get correct archive paths
        dest_abs = os.path.abspath(dest)
        cmd = ["7z", "a", "-tzip", "-mmt", dest_abs, "."]
        result = subprocess.run(cmd, cwd=tmpdir, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

        if result.returncode != 0:
            raise RuntimeError(
                f"7z compression failed with code {result.returncode}\n"
                f"stdout: {result.stdout.decode('utf-8')}\n"
                f"stderr: {result.stderr.decode('utf-8')}"
            )


def _expand_directories(to_archive: List[Tuple[str, str]]) -> List[Tuple[str, str]]:
    """
    Expand any directories in to_archive to individual file entries.

    For each (src, arcname) where src is a directory, recursively add all
    files under src with appropriate archive paths.

    Args:
        to_archive: List of (source_path, archive_name) tuples (may include dirs)

    Returns:
        New list with directories expanded to individual files
    """
    expanded = []
    for src, arcname in to_archive:
        if os.path.isdir(src):
            # Walk the directory and add each file
            for root, _dirs, files in os.walk(src):
                for f in files:
                    file_path = os.path.join(root, f)
                    # Compute relative path from src
                    rel_path = os.path.relpath(file_path, src)
                    # Archive name is arcname/rel_path
                    file_arcname = os.path.join(arcname, rel_path)
                    expanded.append((file_path, file_arcname))
        else:
            expanded.append((src, arcname))
    return expanded


def to_zip_archive(to_archive: List[Tuple[str, str]], dest: str, compress_type=zipfile.ZIP_DEFLATED):
    """
    Create a zip archive from a list of (source, archive_name) tuples.

    Uses 7z with parallel compression and symlinks when:
    - Platform is NOT Windows
    - 7z is available in PATH

    Falls back to Python zipfile otherwise.

    Args:
        to_archive: List of (source_path, archive_name) tuples
        dest: Destination zip file path
        compress_type: Compression type for zipfile fallback

    Raises:
        FileNotFoundError: If any source file doesn't exist
    """
    # Expand directories to individual files
    to_archive = _expand_directories(to_archive)

    # Verify all source files exist upfront (fail fast)
    for src, arcname in to_archive:
        if not os.path.exists(src):
            raise FileNotFoundError(f"Source file does not exist: {src}")

    # Use 7z if available and not on Windows
    is_windows = platform.system() == "Windows"

    if not is_windows and _has_7z():
        logger.info("Using 7z for ZIP compression")
        try:
            _to_zip_archive_with_7z(to_archive, dest)
            return
        except Exception as e:
            logger.warning(f"7z ZIP failed, falling back to Python zipfile: {e}")

    # Fallback to Python zipfile
    if is_windows:
        logger.info("Using Python zipfile for ZIP compression. Faster compression available on Linux/macOS with 7z.")
    else:
        logger.info(
            "Using Python zipfile for ZIP compression. Install 7z for faster compression (macOS: 'brew install p7zip', Linux: 'apt install p7zip-full')"
        )

    with zipfile.ZipFile(dest, "w") as zipF:
        for src, arcname in to_archive:
            zipF.write(src, arcname=arcname, compress_type=compress_type)


def clean_dir(d: str):
    if not trydelete(d):
        raise OSError(f"Failed to clean directory: {d}")
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
    return isinstance(x, collections.abc.Iterable) and not isinstance(x, str)


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
    # Retrieve all directories that start with "dt="
    all_available_date_paths = glob.glob(os.path.join(data_base_dir, "dt=*"))

    def to_dt(path: str) -> date:
        # Get the last directory name and URL-decode it (converts %3A back to ':')
        last_dir = pathlib.PurePath(path).name
        decoded = unquote(last_dir)
        # Capture the date part and then optionally match a separator (' ' or 'T')
        # followed by any extra characters (which may be hours, minutes, timestamps etc.
        if match := re.search(r"^dt=(\d{4}-\d{2}-\d{2})(?:[\sT].*)?$", decoded):
            return date.fromisoformat(match.group(1))
        else:
            raise ValueError(
                r"Last directory must have the format ^dt=(\d{4}-\d{2}-\d{2})(?:[\sT].*)?$ but was " + path
            )

    # Create a set of all dates available in the directory names.
    all_available_dates = {to_dt(path) for path in all_available_date_paths}

    # If fail_if_unavailable is True, ensure all requested dates are present.
    if fail_if_unavailable:
        asked_dates = set(dates)
        assert asked_dates.issubset(all_available_dates), (
            f"Was asked for dates between {min(asked_dates)} and {max(asked_dates)} but these "
            f"dates were not available: {sorted(asked_dates - all_available_dates)}. Looked in: {data_base_dir}"
        )

    # Filter and return only the paths that correspond to the requested dates.
    specified_dt_dirs = [x for x in all_available_date_paths if to_dt(x) in dates]
    return sorted(specified_dt_dirs)


@dataclass(frozen=True)
class InputDataDates:
    training_dates: List[date]
    test_dates: List[date]


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
        if available_physical_cores is None:
            available_physical_cores = psutil.cpu_count(logical=True)

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
        return {"error": "".join(traceback.format_exception(type(ex), ex, ex.__traceback__))}


def get_immediate_subdirectories(a_dir):
    return [os.path.join(a_dir, name) for name in os.listdir(a_dir) if os.path.isdir(os.path.join(a_dir, name))]


class DirectProcessPool(ProcessPool):
    """Appears to be a pebble ProcessPool, but in fact executes the tasks using the current process

    Useful for avoiding the problems with logging in multipricessing
    """

    def schedule(self, function, args=(), kwargs=None, timeout=None):
        if kwargs is None:
            kwargs = {}
        ret = function(*args, **kwargs)

        class ImmediateFuture:
            def result(self):
                return ret

        return ImmediateFuture()

    def map(self, function, *iterables, **kwargs):
        raise ValueError("Not supported")


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


async def _read_stream_and_display(stream, display, max_lines=1000):
    """
    Read from stream in chunks until EOF, display, and capture last N lines.

    Uses chunk-based reading to avoid asyncio.LimitOverrunError on long lines.
    Truncates long lines (best-effort UTF-8 boundary) to avoid capturing unbounded output.

    Args:
        stream: Async stream to read from
        display: Function to display output
        max_lines: Maximum number of lines to keep in buffer (default: 1000)

    Returns:
        bytes: Last max_lines of output joined together (bounded by HOTVECT_STREAM_MAX_CAPTURE_BYTES)
    """
    import codecs
    from collections import deque

    def _env_int(name: str, default: int, *, min_value: int | None = None, max_value: int | None = None) -> int:
        raw = os.environ.get(name)
        if raw is None:
            return default
        try:
            value = int(raw)
        except ValueError:
            logger.warning("Invalid %s=%r; using %d", name, raw, default)
            return default
        original = value
        if min_value is not None:
            value = max(min_value, value)
        if max_value is not None:
            value = min(max_value, value)
        if value != original:
            logger.warning("Clamped %s=%r to %d", name, raw, value)
        return value

    def _truncate_utf8_tail(data: bytes, max_bytes: int) -> bytes:
        """Truncate to last max_bytes, avoiding starting on UTF-8 continuation bytes."""
        if len(data) <= max_bytes:
            return data

        tail = data[-max_bytes:]
        for i, byte in enumerate(tail):
            if (byte & 0b11000000) != 0b10000000:
                return tail[i:]
        return tail

    # Chunk size: balance between syscall overhead and memory.
    # Guard against 0/negative (can stop draining pipes and deadlock subprocesses).
    chunk_size = _env_int("HOTVECT_STREAM_CHUNK_SIZE", 16384, min_value=1, max_value=1024 * 1024)

    # Bound captured output to avoid huge CalledProcessError payloads.
    max_capture_bytes = _env_int("HOTVECT_STREAM_MAX_CAPTURE_BYTES", 2 * 1024 * 1024, min_value=1)
    max_line_bytes = _env_int("HOTVECT_STREAM_MAX_LINE_BYTES", 256 * 1024, min_value=1024, max_value=max_capture_bytes)

    truncation_marker = f"[hotvect] output line exceeded {max_line_bytes} bytes; kept tail only\n".encode("utf-8")

    output: deque[bytes] = deque()
    output_bytes = 0
    decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
    line_buffer = bytearray()
    line_truncated = False
    marker_emitted_for_line = False

    def _append_output(data: bytes) -> None:
        nonlocal output_bytes
        if not data:
            return
        output.append(data)
        output_bytes += len(data)
        while len(output) > max_lines or output_bytes > max_capture_bytes:
            removed = output.popleft()
            output_bytes -= len(removed)

    def _maybe_append_truncation_marker(truncated: bool) -> None:
        nonlocal marker_emitted_for_line
        if truncated and not marker_emitted_for_line:
            _append_output(truncation_marker)
            marker_emitted_for_line = True

    def _append_line(line: bytes, truncated: bool) -> None:
        _maybe_append_truncation_marker(truncated)
        _append_output(line)

    while True:
        chunk = await stream.read(chunk_size)
        if not chunk:
            # EOF reached - flush any remaining decoded text and capture any remaining incomplete line.
            try:
                final = decoder.decode(b"", final=True)
                if final:
                    display(final)
            except Exception:
                pass
            if line_buffer:
                tail = bytes(line_buffer)
                _append_line(tail, line_truncated)
            break

        # Always stream output to the user immediately, even if there are no newlines.
        try:
            decoded = decoder.decode(chunk, final=False)
            if decoded:
                display(decoded)
        except Exception:
            pass

        parts = chunk.split(b"\n")
        if len(parts) == 1:
            # No newlines in chunk - accumulate in line buffer (bounded).
            line_buffer.extend(chunk)
            if len(line_buffer) > max_line_bytes:
                line_buffer = bytearray(_truncate_utf8_tail(bytes(line_buffer), max_line_bytes))
                line_truncated = True
        else:
            # Process first part (completes the line_buffer)
            first = parts[0]
            full_line = bytes(line_buffer) + first + b"\n"
            truncated = line_truncated or (len(full_line) > max_line_bytes)
            if truncated:
                full_line = _truncate_utf8_tail(full_line, max_line_bytes)
                if not full_line.endswith(b"\n"):
                    full_line += b"\n"
            _append_line(full_line, truncated)

            line_buffer.clear()
            line_truncated = False
            marker_emitted_for_line = False

            # Process middle lines (complete lines)
            for middle in parts[1:-1]:
                line = middle + b"\n"
                truncated = len(line) > max_line_bytes
                if truncated:
                    line = _truncate_utf8_tail(line, max_line_bytes)
                    if not line.endswith(b"\n"):
                        line += b"\n"
                _append_line(line, truncated)
                marker_emitted_for_line = False

            # Process last part (start of incomplete line)
            last = parts[-1]
            if last:
                line_buffer.extend(last)
                if len(line_buffer) > max_line_bytes:
                    line_buffer = bytearray(_truncate_utf8_tail(bytes(line_buffer), max_line_bytes))
                    line_truncated = True
                    marker_emitted_for_line = False

    return b"".join(output)


async def _read_and_display(cmd, display_fun, env=None, cwd=None):
    """Capture cmd's stdout, stderr while displaying them as they arrive.

    Args:
        cmd: List of command arguments (e.g. ["java", "-jar", "file.jar", "--arg", "value"])
        display_fun: Function to display output
    """
    if not isinstance(cmd, list):
        raise TypeError(f"cmd must be a list of strings, got {type(cmd)}")

    process = await asyncio.create_subprocess_exec(*cmd, stdout=PIPE, stderr=PIPE, env=env, cwd=cwd)

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


def stream_output(cmd, display_fun, env=None, cwd=None):
    """
    Execute a command with live output streaming.

    Use this for user-facing operations where live progress feedback is desired.
    For operations that need to parse stdout/stderr, use capture_output() instead.

    Args:
        cmd: List of command arguments (e.g. ["java", "-jar", "file.jar", "--arg", "value"])
        display_fun: Function to display output (e.g., sys.stdout.write)
        env: Optional environment mapping to use for the subprocess (defaults to inheriting current env)

    Returns:
        int: Return code of the command (always 0, raises on failure)

    Raises:
        subprocess.CalledProcessError: If command returns non-zero exit code.
            Exception contains returncode, cmd, output (stdout), stderr attributes.
            Output includes tail output for debugging (bounded by HOTVECT_STREAM_MAX_CAPTURE_BYTES per stream).
    """
    cmd_str = " ".join(cmd) if isinstance(cmd, list) else cmd
    logger.info(f"Running {cmd_str}")

    import codecs
    import queue
    import threading

    if not isinstance(cmd, list):
        raise TypeError(f"cmd must be a list of strings, got {type(cmd)}")

    def _env_int(name: str, default: int, min_value: int | None = None, max_value: int | None = None) -> int:
        raw = os.environ.get(name)
        if raw is None:
            return default
        try:
            value = int(raw)
        except (TypeError, ValueError):
            logger.warning("Invalid %s=%r; using %d", name, raw, default)
            return default
        original = value
        if min_value is not None:
            value = max(min_value, value)
        if max_value is not None:
            value = min(max_value, value)
        if value != original:
            logger.warning("Clamped %s=%r to %d", name, raw, value)
        return value

    chunk_size = _env_int("HOTVECT_STREAM_CHUNK_SIZE", 16384, min_value=1, max_value=1024 * 1024)
    max_capture_bytes = _env_int("HOTVECT_STREAM_MAX_CAPTURE_BYTES", 2 * 1024 * 1024, min_value=1)

    process = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, cwd=cwd)
    assert process.stdout is not None
    assert process.stderr is not None

    q: "queue.Queue[tuple[str, bytes | None]]" = queue.Queue()

    def _reader(name: str, stream) -> None:
        try:
            while True:
                chunk = stream.read(chunk_size)
                if not chunk:
                    break
                q.put((name, chunk))
        finally:
            q.put((name, None))

    stdout_thread = threading.Thread(target=_reader, args=("stdout", process.stdout), daemon=True)
    stderr_thread = threading.Thread(target=_reader, args=("stderr", process.stderr), daemon=True)
    stdout_thread.start()
    stderr_thread.start()

    stdout_capture = bytearray()
    stderr_capture = bytearray()
    stdout_decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
    stderr_decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
    eof = {"stdout": False, "stderr": False}

    try:
        while not (eof["stdout"] and eof["stderr"]):
            stream_name, chunk = q.get()
            if chunk is None:
                eof[stream_name] = True
                try:
                    decoder = stdout_decoder if stream_name == "stdout" else stderr_decoder
                    final = decoder.decode(b"", final=True)
                    if final:
                        display_fun(final)
                except Exception:
                    pass
                continue

            if stream_name == "stdout":
                stdout_capture.extend(chunk)
                if len(stdout_capture) > max_capture_bytes:
                    del stdout_capture[:-max_capture_bytes]
                decoder = stdout_decoder
            else:
                stderr_capture.extend(chunk)
                if len(stderr_capture) > max_capture_bytes:
                    del stderr_capture[:-max_capture_bytes]
                decoder = stderr_decoder

            try:
                decoded = decoder.decode(chunk, final=False)
                if decoded:
                    display_fun(decoded)
            except Exception:
                pass
    finally:
        rc = process.wait()
        stdout_thread.join(timeout=1)
        stderr_thread.join(timeout=1)
        try:
            process.stdout.close()
        except Exception:
            pass
        try:
            process.stderr.close()
        except Exception:
            pass

    if rc != 0:
        # Include tail output in error for debugging (bounded by HOTVECT_STREAM_MAX_CAPTURE_BYTES).
        stdout_str = stdout_capture.decode("utf-8", errors="replace")
        stderr_str = stderr_capture.decode("utf-8", errors="replace")
        raise subprocess.CalledProcessError(returncode=rc, cmd=cmd_str, output=stdout_str, stderr=stderr_str)

    return rc


# Backward compatibility alias - will be removed in future version
def execute_command_with_live_output(cmd, display_fun):
    """Deprecated: Use stream_output() instead."""
    return stream_output(cmd, display_fun)


def as_locally_available_content(cache_path: Optional[str], local_cache_path: str) -> Optional[str]:
    """
    This function checks if the provided cache_path is an S3 path or a local file/directory path.
    If it's an S3 path, it downloads the file to the local_cache_path.
    If it's a local file/directory path, it simply returns the path.

    Args:
        cache_path (Optional[str]): The cache path to check. This can be an S3 path or a local file/directory path.
        local_cache_path (str): The local path where the file will be downloaded if the cache_path is an S3 path.

    Returns:
        Optional[str]: Returns the local file/directory path if the cache_path is a local file/directory path.
                      If the cache_path is an S3 path, it returns the local path of the file after downloading the file.
                      If the cache_path doesn't exist, returns None.
    """
    if cache_path is None:
        return None

    # Check if the path is an S3 path
    if cache_path.startswith("s3://"):
        s3 = boto3.resource("s3")
        parsed_url = urlparse(cache_path)
        bucket_name = parsed_url.netloc
        key_prefix = parsed_url.path.lstrip("/")

        bucket = s3.Bucket(bucket_name)

        def _s3_single_file_local_path(local_base_dir: str, bucket: str, key: str) -> str:
            """
            Compute a deterministic local path for caching a single S3 object.

            Using only basename is unsafe because different S3 keys can share the same filename.
            We derive the path from bucket + key to avoid collisions.
            """
            import hashlib

            key_hash = hashlib.sha256(key.encode("utf-8")).hexdigest()
            return os.path.join(local_base_dir, ".hotvect_s3_cache", bucket, key_hash, os.path.basename(key))

        def _normalize_dir_prefix(prefix: str) -> str:
            return prefix if prefix.endswith("/") else prefix + "/"

        def _dir_cache_metadata_path(local_dir_path: str) -> str:
            return os.path.join(local_dir_path, ".hotvect_s3_cache.json")

        def _try_load_dir_cache_metadata(local_dir_path: str) -> Optional[Dict[str, Any]]:
            metadata_path = _dir_cache_metadata_path(local_dir_path)
            if not os.path.exists(metadata_path):
                return None
            try:
                with open(metadata_path, "r") as f:
                    return json.load(f)
            except Exception:
                return None

        # List all objects with this prefix
        objects = list(bucket.objects.filter(Prefix=key_prefix))

        if not objects:
            return None

        # Check if it's a single file (exact key match)
        is_single_file = any(obj.key == key_prefix for obj in objects)

        if is_single_file:
            # Download single file
            local_file_path = _s3_single_file_local_path(local_cache_path, bucket_name, key_prefix)
            os.makedirs(os.path.dirname(local_file_path), exist_ok=True)
            if os.path.exists(local_file_path) and os.path.getsize(local_file_path) > 0:
                return local_file_path
            bucket.download_file(key_prefix, local_file_path)
            return local_file_path
        else:
            # Download directory tree
            # Ensure key_prefix ends with / for proper prefix matching
            if not key_prefix.endswith("/"):
                key_prefix = _normalize_dir_prefix(key_prefix)
                objects = list(bucket.objects.filter(Prefix=key_prefix))

            if not objects:
                return None

            # Create local directory (reuse when safe)
            local_dir_name = os.path.basename(key_prefix.rstrip("/"))
            local_dir_path = os.path.join(local_cache_path, local_dir_name)

            # If a previous download exists for the same bucket/prefix, reuse it.
            expected_prefix = _normalize_dir_prefix(key_prefix)
            cached_metadata = _try_load_dir_cache_metadata(local_dir_path)
            if (
                cached_metadata
                and cached_metadata.get("bucket") == bucket_name
                and cached_metadata.get("prefix") == expected_prefix
                and os.path.isdir(local_dir_path)
            ):
                return local_dir_path

            # Remove existing path, handling symlinks (including broken ones)
            if os.path.exists(local_dir_path) or os.path.islink(local_dir_path):
                if os.path.islink(local_dir_path):
                    os.unlink(local_dir_path)  # Remove symlink
                elif os.path.isdir(local_dir_path):
                    shutil.rmtree(local_dir_path)  # Remove real directory
                else:
                    os.remove(local_dir_path)  # Remove file
            os.makedirs(local_dir_path)

            # Download all files preserving structure
            for obj in objects:
                # Skip if this is a directory marker (key ends with /)
                if obj.key.endswith("/"):
                    continue

                # Get relative path from prefix
                rel_path = obj.key[len(key_prefix) :]
                local_file_path = os.path.join(local_dir_path, rel_path)

                # Create parent directories
                os.makedirs(os.path.dirname(local_file_path), exist_ok=True)

                # Download file using bucket's download_file to match test mocks
                bucket.download_file(obj.key, local_file_path)

            # Write cache metadata (written only after a successful download).
            metadata_tmp = _dir_cache_metadata_path(local_dir_path) + ".tmp"
            with open(metadata_tmp, "w") as f:
                json.dump({"schema": 1, "bucket": bucket_name, "prefix": expected_prefix}, f)
            os.replace(metadata_tmp, _dir_cache_metadata_path(local_dir_path))

            return local_dir_path

    # If it's not an S3 path, assume it's a local file or directory path
    else:
        if not os.path.exists(cache_path):
            return None
        else:
            # Return the path whether it's a file or directory
            return cache_path


def copy_or_link(source: str, dest: str):
    """
    Copies source to destination, or creates a symlink if the OS supports it.
    Works for both files and directories.

    Args:
        source (str): The source file or directory path.
        dest (str): The destination file or directory path.
    """
    # Remove destination if it already exists
    # Check for symlinks even if broken (os.path.exists returns False for broken symlinks)
    if os.path.exists(dest) or os.path.islink(dest):
        if os.path.islink(dest):
            os.unlink(dest)  # Remove symlink (works for both file and directory symlinks)
        elif os.path.isdir(dest):
            shutil.rmtree(dest)  # Remove real directory
        else:
            os.remove(dest)  # Remove file

    if sys.platform == "win32":
        # Windows doesn't support soft links for all users
        if os.path.isdir(source):
            shutil.copytree(source, dest, dirs_exist_ok=True)
        else:
            shutil.copy(source, dest)
    else:
        # Unix-based systems (like Linux and MacOS) support soft links
        # os.symlink works for both files and directories
        # Use absolute path for source to avoid broken symlinks when paths are relative
        abs_source = os.path.abspath(source)
        os.symlink(abs_source, dest)


def store_file(src: str, dest: str):
    """
    Store file or directory to destination (local or S3).

    Args:
        src (str): Source file or directory path.
        dest (str): Destination path (local or s3://).
    """
    # Check if the destination is an S3 path
    if dest.startswith("s3://"):
        s3 = boto3.client("s3")

        # Parse out the bucket and key from the destination
        parsed = urlparse(dest)
        bucket = parsed.netloc
        base_key = parsed.path.lstrip("/")

        if os.path.isdir(src):
            # Recursively upload directory to S3
            for root, dirs, files in os.walk(src):
                for file in files:
                    full_path = os.path.join(root, file)
                    rel_path = os.path.relpath(full_path, src)
                    s3_key = os.path.join(base_key, rel_path).replace("\\", "/")
                    s3.upload_file(full_path, bucket, s3_key)
        else:
            # Upload single file (no need to clear, will overwrite)
            s3.upload_file(src, bucket, base_key)
    else:
        # If the destination is not an S3 path, assume it's a local file path
        # and use the built-in shutil library to copy the file

        # Remove destination if it already exists, handling symlinks (including broken ones)
        if os.path.exists(dest) or os.path.islink(dest):
            if os.path.islink(dest):
                os.unlink(dest)  # Remove symlink
            elif os.path.isdir(dest):
                shutil.rmtree(dest)  # Remove real directory
            else:
                os.remove(dest)  # Remove file

        # Extract the directory part of the destination path
        dest_dir = os.path.dirname(dest)

        # Create any missing intermediate directories
        os.makedirs(dest_dir, exist_ok=True)

        if os.path.isdir(src):
            # Copy directory tree (dirs_exist_ok for Python 3.8+)
            shutil.copytree(src, dest, dirs_exist_ok=True)
        else:
            # Copy single file
            shutil.copy(src, dest)


# ============================================================================
# S3 Data Dependency Utilities
# ============================================================================


def resolve_data_dependency_s3_uri(
    dependency: "DataDependency",
    environment: str = "production",
    default_s3_base: Optional[str] = None,
) -> Optional[str]:
    """
    Resolve S3 URI for a data dependency from algorithm definition.

    Resolution logic:
    1. If s3_uri in additional_properties is a string → return it directly
    2. If s3_uri is a dict → look for exact environment key (case-sensitive)
       - If found, return the value
       - If not found, FAIL with clear error (no silent fallbacks)
    3. If no s3_uri → fallback to default_s3_base + data_prefix
    4. If nothing works → return None

    Args:
        dependency: DataDependency with additional_properties containing s3_uri
        environment: Environment to use ("production", "staging", "test").
                     Default: "production". Must exist in s3_uri dict if specified.
        default_s3_base: Fallback S3 base URI if no s3_uri in definition. Optional.

    Returns:
        Full S3 URI string (e.g., "s3://bucket/prefix/path/") or None

    Raises:
        ValueError: If environment not found in s3_uri dict

    Examples:
        # String form
        >>> dep = DataDependency(..., additional_properties={"s3_uri": "s3://bucket/path/"})
        >>> resolve_data_dependency_s3_uri(dep)
        "s3://bucket/path/"

        # Dict form with production
        >>> dep = DataDependency(..., additional_properties={
        ...     "s3_uri": {"production": "s3://example-bucket/", "staging": "s3://example-bucket/"}
        ... })
        >>> resolve_data_dependency_s3_uri(dep, environment="production")
        "s3://example-bucket/"

        # Dict form - fails if environment not found
        >>> resolve_data_dependency_s3_uri(dep, environment="test")
        ValueError: Environment 'test' not found in s3_uri...
    """
    additional_props = dependency.additional_properties or {}
    s3_uri_spec = additional_props.get("s3_uri")

    # Case 1: String form - return directly
    if isinstance(s3_uri_spec, str):
        return s3_uri_spec

    # Case 2: Dict form - look for exact environment, fail if not found
    if isinstance(s3_uri_spec, dict):
        if environment in s3_uri_spec:
            return s3_uri_spec[environment]

        # Environment not found - fail loudly
        available_envs = list(s3_uri_spec.keys())
        raise ValueError(
            f"Environment '{environment}' not found in s3_uri. "
            f"Available environments: {available_envs}. "
            f"Dependency: {dependency.data_prefix}"
        )

    # Case 3: No s3_uri - try default base
    if default_s3_base:
        base = default_s3_base.rstrip("/") + "/"
        return f"{base}{dependency.data_prefix}/"

    # Case 4: Cannot resolve
    return None


def parse_s3_uri(s3_uri: str) -> Tuple[str, str]:
    """
    Parse S3 URI into bucket and prefix components.

    Args:
        s3_uri: S3 URI like "s3://bucket/prefix/path/"

    Returns:
        Tuple of (bucket, prefix) where:
        - bucket: S3 bucket name
        - prefix: Path prefix with leading/trailing slashes stripped

    Examples:
        >>> parse_s3_uri("s3://example-bucket/data/path/")
        ("example-bucket", "data/path")

        >>> parse_s3_uri("s3://bucket/")
        ("bucket", "")
    """
    parsed = urlparse(s3_uri)
    bucket = parsed.netloc
    prefix = parsed.path.strip("/")

    return bucket, prefix


def build_s3_date_path(*parts: str, date_str: str) -> str:
    """
    Build S3 path with date partition from components.

    Handles proper slash joining and adds dt= date partition.

    Args:
        *parts: Path segments (base prefix, data prefix, etc.). Empty strings ignored.
        date_str: Date string in YYYY-MM-DD format

    Returns:
        Path like "prefix/data_prefix/dt=YYYY-MM-DD/" with trailing slash

    Examples:
        >>> build_s3_date_path("base/prefix", "my_data", date_str="2025-10-23")
        "base/prefix/my_data/dt=2025-10-23/"

        >>> build_s3_date_path("", "my_data", date_str="2025-10-23")
        "my_data/dt=2025-10-23/"

        >>> build_s3_date_path(date_str="2025-10-23")
        "dt=2025-10-23/"
    """
    # Filter out empty parts and strip slashes
    path_parts = [p.strip("/") for p in parts if p]

    # Add date partition
    path_parts.append(f"dt={date_str}")

    # Join and add trailing slash
    return "/".join(path_parts) + "/"


def format_s3_uri(bucket: str, key: str) -> str:
    """
    Format bucket and key/prefix as S3 URI.

    Args:
        bucket: S3 bucket name
        key: S3 key/prefix (leading slashes will be normalized)

    Returns:
        URI like "s3://bucket/key"

    Examples:
        >>> format_s3_uri("my-bucket", "path/to/data/")
        "s3://my-bucket/path/to/data/"

        >>> format_s3_uri("my-bucket", "/path/to/data/")
        "s3://my-bucket/path/to/data/"

        >>> format_s3_uri("my-bucket", "")
        "s3://my-bucket"
    """
    normalized_key = key.lstrip("/")

    if normalized_key:
        return f"s3://{bucket}/{normalized_key}"
    return f"s3://{bucket}"
