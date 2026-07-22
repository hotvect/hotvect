from __future__ import annotations

import gzip
import json
import shutil
from dataclasses import dataclass
from datetime import timezone
from pathlib import Path
from typing import Any, BinaryIO

from hotvect.utils import format_s3_uri, parse_s3_uri

MANIFEST_FILENAME = "MANIFEST"


def _iso_z(dt: Any) -> str | None:
    if dt is None:
        return None
    try:
        dt_utc = dt.astimezone(timezone.utc).replace(microsecond=0)
    except Exception:
        return str(dt)
    return dt_utc.isoformat().replace("+00:00", "Z")


def _ensure_empty_destination(experiment_root: Path) -> None:
    if experiment_root.exists() and any(experiment_root.iterdir()):
        raise FileExistsError(
            f"Destination already contains data: {experiment_root}. "
            "Please remove the old data manually before downloading again."
        )


@dataclass(frozen=True)
class OnlineEvaluationResultPart:
    analysis_date: str
    key: str
    size_bytes: int | None
    etag: str | None
    last_modified: str | None

    @property
    def filename(self) -> str:
        return Path(self.key).name


class OnlineEvaluationResultsStore:
    def __init__(self, *, s3_client: Any, s3_base_prefix: str):
        if not isinstance(s3_base_prefix, str) or not s3_base_prefix.strip():
            raise ValueError("s3_base_prefix must be a non-empty string.")
        if not s3_base_prefix.startswith("s3://"):
            raise ValueError("s3_base_prefix must start with s3://")
        bucket, prefix = parse_s3_uri(s3_base_prefix)
        if not bucket:
            raise ValueError(f"Invalid s3 base prefix: {s3_base_prefix!r}")
        self._s3_client = s3_client
        self._bucket = bucket
        self._base_prefix = prefix.strip("/")
        self._s3_base_prefix = format_s3_uri(self._bucket, f"{self._base_prefix}/" if self._base_prefix else "")

    @property
    def s3_base_prefix(self) -> str:
        return self._s3_base_prefix

    def list_analysis_dates(self, *, experiment_id: int) -> list[dict[str, Any]]:
        grouped = self._group_parts_by_analysis_date(experiment_id=experiment_id)
        return [
            {
                "analysis_date": analysis_date,
                "part_count": len(parts),
                "s3_prefix": self._analysis_date_s3_prefix(experiment_id=experiment_id, analysis_date=analysis_date),
            }
            for analysis_date, parts in sorted(grouped.items())
        ]

    def stream_analysis_date(self, *, experiment_id: int, analysis_date: str, output_stream: BinaryIO) -> None:
        parts = self._parts_for_analysis_date(experiment_id=experiment_id, analysis_date=analysis_date)
        for part in parts:
            response = self._s3_client.get_object(Bucket=self._bucket, Key=part.key)
            body = response["Body"]
            try:
                with gzip.GzipFile(fileobj=body) as gz:
                    shutil.copyfileobj(gz, output_stream)
            finally:
                close = getattr(body, "close", None)
                if callable(close):
                    close()

    def download_analysis_dates(
        self,
        *,
        experiment_id: int,
        experiment_root: Path,
        analysis_date: str | None,
    ) -> dict[str, Any]:
        grouped = self._group_parts_by_analysis_date(experiment_id=experiment_id)
        if analysis_date:
            selected_dates = [analysis_date]
        else:
            selected_dates = sorted(grouped.keys())

        if not selected_dates:
            raise FileNotFoundError(f"No online evaluation results found for experiment_id={int(experiment_id)}")

        _ensure_empty_destination(experiment_root)
        experiment_root.mkdir(parents=True, exist_ok=True)

        manifest_entries = []
        downloaded_dates = []

        for date_value in selected_dates:
            parts = grouped.get(date_value)
            if not parts:
                raise FileNotFoundError(
                    f"No online evaluation results found for experiment_id={int(experiment_id)} and "
                    f"analysis_date={date_value}"
                )

            local_date_dir = experiment_root / f"last_date_of_analysis={date_value}"
            local_date_dir.mkdir(parents=True, exist_ok=True)

            file_entries = []
            for part in parts:
                local_part_path = local_date_dir / part.filename
                self._s3_client.download_file(self._bucket, part.key, str(local_part_path))
                file_entries.append(
                    {
                        "path": str(Path(f"last_date_of_analysis={date_value}") / part.filename),
                        "size_bytes": part.size_bytes,
                        "etag": part.etag,
                        "last_modified": part.last_modified,
                    }
                )

            manifest_entries.append(
                {
                    "analysis_date": date_value,
                    "s3_prefix": self._analysis_date_s3_prefix(experiment_id=experiment_id, analysis_date=date_value),
                    "files": file_entries,
                }
            )
            downloaded_dates.append(
                {
                    "analysis_date": date_value,
                    "part_count": len(parts),
                    "local_dir": str(local_date_dir),
                }
            )

        manifest_path = experiment_root / MANIFEST_FILENAME
        manifest_payload = {
            "schema_version": 1,
            "experiment_id": int(experiment_id),
            "s3_base_prefix": self._s3_base_prefix,
            "analysis_dates": manifest_entries,
        }
        manifest_path.write_text(json.dumps(manifest_payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")

        return {
            "download_root": str(experiment_root),
            "manifest_path": str(manifest_path),
            "analysis_dates": downloaded_dates,
            "manifest": manifest_payload,
        }

    def _parts_for_analysis_date(self, *, experiment_id: int, analysis_date: str) -> list[OnlineEvaluationResultPart]:
        grouped = self._group_parts_by_analysis_date(experiment_id=experiment_id)
        parts = grouped.get(analysis_date, [])
        if not parts:
            raise FileNotFoundError(
                f"No online evaluation results found for experiment_id={int(experiment_id)} and "
                f"analysis_date={analysis_date}"
            )
        return parts

    def _group_parts_by_analysis_date(self, *, experiment_id: int) -> dict[str, list[OnlineEvaluationResultPart]]:
        experiment_prefix = self._experiment_prefix(experiment_id=experiment_id)
        grouped: dict[str, list[OnlineEvaluationResultPart]] = {}

        for obj in self._list_objects(prefix=experiment_prefix):
            key = obj["Key"]
            if not key.startswith(experiment_prefix):
                continue
            relative_key = key[len(experiment_prefix) :]
            path_parts = relative_key.split("/", 1)
            if len(path_parts) != 2:
                continue
            date_partition, filename = path_parts
            if not date_partition.startswith("last_date_of_analysis="):
                continue
            if not filename.startswith("part-") or not filename.endswith(".json.gz"):
                continue
            analysis_date = date_partition[len("last_date_of_analysis=") :]
            grouped.setdefault(analysis_date, []).append(
                OnlineEvaluationResultPart(
                    analysis_date=analysis_date,
                    key=key,
                    size_bytes=obj.get("Size"),
                    etag=obj.get("ETag"),
                    last_modified=_iso_z(obj.get("LastModified")),
                )
            )

        for parts in grouped.values():
            parts.sort(key=lambda item: item.key)

        return grouped

    def _analysis_date_s3_prefix(self, *, experiment_id: int, analysis_date: str) -> str:
        return format_s3_uri(
            self._bucket,
            f"{self._experiment_prefix(experiment_id=experiment_id)}last_date_of_analysis={analysis_date}/",
        )

    def _experiment_prefix(self, *, experiment_id: int) -> str:
        parts = [self._base_prefix] if self._base_prefix else []
        parts.append(f"experiment_id={int(experiment_id)}")
        return "/".join(part.strip("/") for part in parts if part).rstrip("/") + "/"

    def _list_objects(self, *, prefix: str) -> list[dict[str, Any]]:
        contents: list[dict[str, Any]] = []
        continuation_token: str | None = None

        while True:
            kwargs: dict[str, Any] = {"Bucket": self._bucket, "Prefix": prefix}
            if continuation_token:
                kwargs["ContinuationToken"] = continuation_token
            response = self._s3_client.list_objects_v2(**kwargs)
            contents.extend(response.get("Contents", []))
            if not response.get("IsTruncated"):
                break
            continuation_token = response.get("NextContinuationToken")
            if not continuation_token:
                break

        return contents
