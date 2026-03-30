import hashlib
import secrets
from pathlib import Path

from hotvect.sagemaker_config import validate_job_prefix, validate_training_job_name_length
from hotvect.utils import hexigest_as_alphanumeric


def md5_hexdigest_of_file(path: Path) -> str:
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def generate_runid() -> str:
    return hexigest_as_alphanumeric(secrets.token_hex(4))


def compute_hph(hyperparameter_version: str) -> str:
    """Compute hyperparameter hash token exactly like backtest does."""
    hyperparam_hash = hashlib.md5(hyperparameter_version.encode("utf-8")).hexdigest()[:6]
    return hexigest_as_alphanumeric(hyperparam_hash)


def compute_jar4(jar_path: Path) -> str:
    jar_md5_hex = md5_hexdigest_of_file(jar_path)
    return hexigest_as_alphanumeric(jar_md5_hex)[:4]


def build_one_shot_training_job_name(*, prefix: str, jar_path: Path, hyperparameter_version: str, kind: str) -> str:
    validate_job_prefix(prefix)
    runid = generate_runid()
    jar4 = compute_jar4(jar_path)
    hph = compute_hph(hyperparameter_version or "")
    name = f"{prefix}-{runid}-{jar4}-{hph}-{kind}"
    validate_training_job_name_length(name)
    return name
