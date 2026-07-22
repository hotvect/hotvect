from __future__ import annotations

import json
import logging
import os
import tempfile
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

logger = logging.getLogger(__name__)


def require_s3_uri(uri: str) -> tuple[str, str]:
    parsed = urlparse(uri)
    if parsed.scheme != "s3" or not parsed.netloc:
        raise ValueError(f"Expected s3:// URI, got {uri!r}")
    return parsed.netloc, parsed.path.lstrip("/")


def join_s3_uri(base_uri: str, *parts: str) -> str:
    bucket, key = require_s3_uri(base_uri)
    suffix_parts = [part.strip("/") for part in parts if part and part.strip("/")]
    joined = "/".join([key.rstrip("/")] + suffix_parts if key else suffix_parts)
    return f"s3://{bucket}/{joined}" if joined else f"s3://{bucket}"


def normalize_s3_prefix_uri(uri: str) -> str:
    bucket, key = require_s3_uri(uri)
    normalized_key = key.strip("/")
    return f"s3://{bucket}/{normalized_key}/" if normalized_key else f"s3://{bucket}"


def upload_file_to_s3(
    local_file_path: str,
    s3_target_uri: str,
    s3_client: Any,
    *,
    fail_fast: bool = False,
) -> None:
    bucket, key = require_s3_uri(s3_target_uri)
    try:
        s3_client.upload_file(Filename=local_file_path, Bucket=bucket, Key=key)
        logger.info("Successfully uploaded %s to %s/%s", local_file_path, bucket, key)
    except Exception:
        logger.exception("Failed to upload %s to %s/%s", local_file_path, bucket, key)
        if fail_fast:
            raise


def upload_directory_to_s3(local_dir_path: str, s3_target_uri: str, s3_client: Any, *, fail_fast: bool = False) -> None:
    basedir_name = os.path.basename(os.path.normpath(local_dir_path))
    for root, _, files in os.walk(local_dir_path):
        for file in files:
            file_path = os.path.join(root, file)
            relative_path = os.path.relpath(file_path, local_dir_path)
            s3_uri = join_s3_uri(s3_target_uri, basedir_name, relative_path).replace("\\", "/")
            upload_file_to_s3(file_path, s3_uri, s3_client, fail_fast=fail_fast)


def download_s3_file(s3_uri: str, dest_path: Path, s3_client: Any) -> None:
    bucket, key = require_s3_uri(s3_uri)
    s3_client.download_file(Bucket=bucket, Key=key, Filename=str(dest_path))


def download_json_from_s3(s3_uri: str, s3_client: Any) -> dict[str, Any]:
    bucket, key = require_s3_uri(s3_uri)
    with tempfile.NamedTemporaryFile() as temp_file:
        s3_client.download_fileobj(Bucket=bucket, Key=key, Fileobj=temp_file)
        temp_file.seek(0)
        return json.load(temp_file)


def upload_json_to_s3(
    payload: dict[str, Any],
    s3_target_uri: str,
    s3_client: Any,
    *,
    fail_fast: bool = False,
    default: Any = None,
) -> None:
    local_path: str | None = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as temp_file:
            json.dump(payload, temp_file, indent=2, sort_keys=True, default=default)
            temp_file.write("\n")
            local_path = temp_file.name
        upload_file_to_s3(local_path, s3_target_uri, s3_client, fail_fast=fail_fast)
    finally:
        if local_path and os.path.exists(local_path):
            os.remove(local_path)
