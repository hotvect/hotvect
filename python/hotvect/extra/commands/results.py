"""Results inventory utilities for hv-ext CLI."""

from __future__ import annotations

import json
import re
import tarfile
import tempfile
from datetime import date, timezone
from pathlib import Path
from typing import Any

from hotvect.utils import resolve_path_within_base, safe_extract_tar_archive

from .base import BaseCommand


def _parse_day(day_s: str | None) -> date | None:
    if not day_s:
        return None
    return date.fromisoformat(day_s)


def _iso_z(dt) -> str:
    # boto3 returns tz-aware datetime; keep output stable.
    try:
        dt_utc = dt.astimezone(timezone.utc).replace(microsecond=0)
    except Exception:
        return str(dt)
    s = dt_utc.isoformat()
    return s.replace("+00:00", "Z")


def _parse_algorithm_id(algorithm_id: str) -> tuple[str | None, str | None, str | None]:
    if "@" not in algorithm_id:
        return None, None, None
    algorithm_name, rest = algorithm_id.split("@", 1)
    # Keep this intentionally conservative: only strip known hyperparameter suffixes.
    for hp in ("ordered", "unordered"):
        suffix = f"-{hp}"
        if rest.endswith(suffix):
            return algorithm_name, rest[: -len(suffix)], hp
    return algorithm_name, rest, None


class ResultsCommand(BaseCommand):
    """Top-level results command wrapper."""

    @classmethod
    def register_parser(cls, subparsers):
        parser = subparsers.add_parser(
            "results",
            help="Results inventory utilities (JSON output only)",
        )
        results_subparsers = parser.add_subparsers(dest="results_command", metavar="<results-command>")
        ResultsLsCommand.register_parser(results_subparsers)
        ResultsDownloadCommand.register_parser(results_subparsers)
        return parser

    def execute(self, args):
        if args.results_command == "ls":
            ResultsLsCommand().execute(args)
        elif args.results_command == "download":
            ResultsDownloadCommand().execute(args)
        else:
            raise SystemExit("Missing results subcommand. Use `hv-ext results -h`.")


class ResultsLsCommand(BaseCommand):
    """List result.json runs locally or in S3 (latest-only)."""

    @classmethod
    def register_parser(cls, subparsers):
        parser = subparsers.add_parser(
            "ls",
            help="List result.json runs under a local meta dir or s3:// prefix (latest-only; JSON output only)",
        )
        parser.add_argument("location", help="Local meta dir path or an s3://... prefix")
        parser.add_argument("--from-date", default="", help="Only include runs on/after this date (YYYY-MM-DD)")
        parser.add_argument("--to-date", default="", help="Only include runs on/before this date (YYYY-MM-DD)")
        parser.add_argument("--algorithm-name-regex", default="", help="Regex to filter algorithm name (optional)")
        parser.add_argument(
            "--algorithm-version-regex", default="", help="Regex to filter algorithm version (optional)"
        )
        parser.add_argument("--job-name-regex", default="", help="Regex to filter job name (S3 only, optional)")
        parser.add_argument("--role-arn", default="", help="AWS role ARN to assume for S3 access (S3 only, optional)")
        return parser

    def execute(self, args):
        location: str = args.location
        from_date = _parse_day(args.from_date or None)
        to_date = _parse_day(args.to_date or None)
        if from_date and to_date and from_date > to_date:
            raise ValueError("--from-date must be <= --to-date")

        algorithm_name_re = re.compile(args.algorithm_name_regex) if args.algorithm_name_regex else None
        algorithm_version_re = re.compile(args.algorithm_version_regex) if args.algorithm_version_regex else None

        runs = (
            self._list_s3(
                location=location,
                from_date=from_date,
                to_date=to_date,
                algorithm_name_re=algorithm_name_re,
                algorithm_version_re=algorithm_version_re,
                job_name_regex=(args.job_name_regex or "").strip(),
                role_arn=(args.role_arn or "").strip(),
            )
            if location.startswith("s3://")
            else self._list_local(
                location=location,
                from_date=from_date,
                to_date=to_date,
                algorithm_name_re=algorithm_name_re,
                algorithm_version_re=algorithm_version_re,
            )
        )

        output = {
            "location": location,
            "filters": {
                "from_date": from_date.isoformat() if from_date else None,
                "to_date": to_date.isoformat() if to_date else None,
                "algorithm_name_regex": args.algorithm_name_regex or None,
                "algorithm_version_regex": args.algorithm_version_regex or None,
                "job_name_regex": (args.job_name_regex or None) if location.startswith("s3://") else None,
            },
            "runs": runs,
        }
        print(json.dumps(output, indent=2))

    def _list_local(
        self,
        *,
        location: str,
        from_date: date | None,
        to_date: date | None,
        algorithm_name_re: re.Pattern[str] | None,
        algorithm_version_re: re.Pattern[str] | None,
    ) -> list[dict[str, Any]]:
        meta_dir = Path(location)
        if not meta_dir.exists():
            raise FileNotFoundError(f"meta dir does not exist: {meta_dir}")
        if not meta_dir.is_dir():
            raise NotADirectoryError(f"meta dir is not a directory: {meta_dir}")

        best: dict[tuple[str, str], dict[str, Any]] = {}

        for algo_dir in sorted([p for p in meta_dir.iterdir() if p.is_dir()]):
            algorithm_id = algo_dir.name
            algorithm_name, algorithm_version, hyperparameter = _parse_algorithm_id(algorithm_id)
            if algorithm_name is None:
                continue
            if algorithm_name_re and not algorithm_name_re.match(algorithm_name):
                continue
            if algorithm_version_re and not algorithm_version_re.match(algorithm_version or ""):
                continue

            for run_dir in sorted([p for p in algo_dir.iterdir() if p.is_dir()]):
                if not run_dir.name.startswith("last_test_date_"):
                    continue
                test_date_s = run_dir.name[len("last_test_date_") :]
                try:
                    test_day = date.fromisoformat(test_date_s)
                except ValueError:
                    continue
                if from_date and test_day < from_date:
                    continue
                if to_date and test_day > to_date:
                    continue

                result_path = run_dir / "result.json"
                if not result_path.exists():
                    continue

                mtime = result_path.stat().st_mtime
                run = {
                    "test_date": test_date_s,
                    "algorithm_id": algorithm_id,
                    "algorithm_name": algorithm_name,
                    "algorithm_version": algorithm_version,
                    "hyperparameter": hyperparameter,
                    "result_json": {"path_or_key": str(result_path)},
                }

                key = (test_date_s, algorithm_id)
                prev = best.get(key)
                if prev is None or prev.get("_mtime", -1) < mtime:
                    run["_mtime"] = mtime
                    best[key] = run

        out = sorted(best.values(), key=lambda r: (r["test_date"], r["algorithm_id"]))
        for r in out:
            r.pop("_mtime", None)
        return out

    def _list_s3(
        self,
        *,
        location: str,
        from_date: date | None,
        to_date: date | None,
        algorithm_name_re: re.Pattern[str] | None,
        algorithm_version_re: re.Pattern[str] | None,
        job_name_regex: str,
        role_arn: str,
    ) -> list[dict[str, Any]]:
        # Reuse the existing SageMaker result matching logic (but do not download).
        from hotvect.backtest import SageMakerBacktestResultsDownloader

        training_job_id_pattern = job_name_regex or ".+?"
        algo_name_pattern = algorithm_name_re.pattern if algorithm_name_re else ".+?"
        algo_version_pattern = algorithm_version_re.pattern if algorithm_version_re else ".+?"

        with tempfile.TemporaryDirectory() as td:
            downloader = SageMakerBacktestResultsDownloader(
                s3_base_prefix=location,
                dest_base_dir=td,
                training_job_id_pattern=training_job_id_pattern,
                algorithm_name_pattern=algo_name_pattern,
                algorithm_version_pattern=algo_version_pattern,
                include_metadata=False,
                include_output_data=False,
                skip_data_if_already_present=True,
                from_including_test_date=from_date,
                to_including_test_date=to_date,
                role_arn_to_assume=(role_arn or None),
            )

            relevant = downloader._find_relevant_executions()

        # relevant already contains the latest run for each (test_date, algo_name, algo_version, hyperparameter)
        # based on S3 LastModified.
        runs = []
        for c in relevant.values():
            algorithm_id = f"{c.algorithm_name}@{c.algorithm_version}"
            if c.hyperparameter:
                algorithm_id = f"{algorithm_id}-{c.hyperparameter}"
            runs.append(
                {
                    "test_date": c.backtest_test_date,
                    "algorithm_id": algorithm_id,
                    "algorithm_name": c.algorithm_name,
                    "algorithm_version": c.algorithm_version,
                    "hyperparameter": c.hyperparameter or None,
                    "job_name": c.training_job,
                    "result_json": {
                        "path_or_key": f"s3://{downloader._s3_source_bucket}/{c.key}",
                        "last_modified": _iso_z(c.execution_date),
                    },
                }
            )

        return sorted(runs, key=lambda r: (r["test_date"], r["algorithm_id"], r.get("job_name") or ""))


class ResultsDownloadCommand(BaseCommand):
    """Download result artifacts from S3 into a local output dir (fail-fast)."""

    @classmethod
    def register_parser(cls, subparsers):
        parser = subparsers.add_parser(
            "download",
            help="Download result artifacts from an s3:// prefix into a local dir (latest-only; JSON output only)",
        )
        parser.add_argument("s3_prefix", help="S3 base prefix where backtest results are stored (s3://...)")
        parser.add_argument("--dest-base-dir", required=True, help="Local destination directory for downloaded results")

        parser.add_argument("--from-date", default="", help="Only include runs on/after this date (YYYY-MM-DD)")
        parser.add_argument("--to-date", default="", help="Only include runs on/before this date (YYYY-MM-DD)")
        parser.add_argument("--algorithm-name-regex", default="", help="Regex to filter algorithm name (optional)")
        parser.add_argument(
            "--algorithm-version-regex", default="", help="Regex to filter algorithm version (optional)"
        )
        parser.add_argument("--job-name-regex", default="", help="Regex to filter job name (optional)")
        parser.add_argument("--role-arn", default="", help="AWS role ARN to assume for S3 access (optional)")

        parser.add_argument(
            "--include-metadata", action="store_true", help="Download/extract metadata tar (output/output.tar.gz)"
        )
        parser.add_argument(
            "--include-output-data",
            action="store_true",
            help="Download/extract output data tar (output/model.tar.gz)",
        )
        parser.add_argument(
            "--no-skip-existing",
            action="store_true",
            help="Re-download files even if they already exist locally (default: skip existing)",
        )
        return parser

    def execute(self, args):
        s3_prefix: str = args.s3_prefix
        if not s3_prefix.startswith("s3://"):
            raise ValueError("s3_prefix must start with s3://")

        dest_base_dir = Path(args.dest_base_dir)
        dest_base_dir.mkdir(parents=True, exist_ok=True)

        from_date = _parse_day(args.from_date or None)
        to_date = _parse_day(args.to_date or None)
        if from_date and to_date and from_date > to_date:
            raise ValueError("--from-date must be <= --to-date")

        algorithm_name_re = re.compile(args.algorithm_name_regex) if args.algorithm_name_regex else None
        algorithm_version_re = re.compile(args.algorithm_version_regex) if args.algorithm_version_regex else None

        from hotvect.backtest import SageMakerBacktestResultsDownloader

        training_job_id_pattern = (args.job_name_regex or "").strip() or ".+?"
        algo_name_pattern = algorithm_name_re.pattern if algorithm_name_re else ".+?"
        algo_version_pattern = algorithm_version_re.pattern if algorithm_version_re else ".+?"

        downloader = SageMakerBacktestResultsDownloader(
            s3_base_prefix=s3_prefix,
            dest_base_dir=str(dest_base_dir),
            training_job_id_pattern=training_job_id_pattern,
            algorithm_name_pattern=algo_name_pattern,
            algorithm_version_pattern=algo_version_pattern,
            include_metadata=False,
            include_output_data=False,
            skip_data_if_already_present=True,
            from_including_test_date=from_date,
            to_including_test_date=to_date,
            role_arn_to_assume=((args.role_arn or "").strip() or None),
        )

        relevant = downloader._find_relevant_executions()

        matches = []
        for c in relevant.values():
            algorithm_id = f"{c.algorithm_name}@{c.algorithm_version}"
            if c.hyperparameter:
                algorithm_id = f"{algorithm_id}-{c.hyperparameter}"
            matches.append(
                {
                    "test_date": c.backtest_test_date,
                    "algorithm_id": algorithm_id,
                    "algorithm_name": c.algorithm_name,
                    "algorithm_version": c.algorithm_version,
                    "hyperparameter": c.hyperparameter or None,
                    "job_name": c.training_job,
                    "result_json": {
                        "s3": f"s3://{downloader._s3_source_bucket}/{c.key}",
                        "last_modified": _iso_z(c.execution_date),
                    },
                    "_key": c.key,
                }
            )

        matches.sort(key=lambda r: (r["test_date"], r["algorithm_id"], r.get("job_name") or ""))

        # Decide which ones we actually download (skip-existing is per (date, algorithm_id) local result.json).
        download_these = []
        for m in matches:
            local_result = resolve_path_within_base(
                dest_base_dir,
                Path("meta") / m["algorithm_id"] / f"last_test_date_{m['test_date']}" / "result.json",
            )
            m["_local_result_json"] = str(local_result)
            if (not args.no_skip_existing) and local_result.exists():
                m["skipped_existing"] = True
            else:
                download_these.append(m)

        # Download tars once per job root (derived from the result.json key).
        downloaded_metadata_roots = 0
        downloaded_output_roots = 0
        downloaded_result_json = 0

        roots_needed = {self._job_root_prefix(m["_key"]) for m in download_these}
        for root_prefix in sorted(roots_needed):
            if args.include_metadata:
                self._download_and_extract_tar(
                    s3_client=downloader._s3_client,
                    bucket=downloader._s3_source_bucket,
                    key=f"{root_prefix}/output/output.tar.gz",
                    dest_dir=dest_base_dir,
                )
                downloaded_metadata_roots += 1
            if args.include_output_data:
                self._download_and_extract_tar(
                    s3_client=downloader._s3_client,
                    bucket=downloader._s3_source_bucket,
                    key=f"{root_prefix}/output/model.tar.gz",
                    dest_dir=dest_base_dir / "output",
                )
                downloaded_output_roots += 1

        # Download result.json for each match (fail-fast).
        for m in download_these:
            local_result = Path(m["_local_result_json"])
            local_result.parent.mkdir(parents=True, exist_ok=True)
            downloader._s3_client.download_file(downloader._s3_source_bucket, m["_key"], str(local_result))
            downloaded_result_json += 1

        # Ensure expected local files exist for all matches (including skipped).
        for m in matches:
            expected = Path(m["_local_result_json"])
            if not expected.exists():
                raise FileNotFoundError(f"Missing local result.json after download: {expected}")

        for m in matches:
            m.pop("_key", None)
            m.pop("_local_result_json", None)

        output = {
            "s3_prefix": s3_prefix,
            "dest_base_dir": str(dest_base_dir),
            "filters": {
                "from_date": from_date.isoformat() if from_date else None,
                "to_date": to_date.isoformat() if to_date else None,
                "algorithm_name_regex": args.algorithm_name_regex or None,
                "algorithm_version_regex": args.algorithm_version_regex or None,
                "job_name_regex": args.job_name_regex or None,
            },
            "matches": matches,
            "downloaded": {
                "result_json": {"count": downloaded_result_json},
                "metadata": {"count": downloaded_metadata_roots},
                "output_data": {"count": downloaded_output_roots},
            },
        }
        print(json.dumps(output, indent=2))

    @staticmethod
    def _job_root_prefix(result_json_key: str) -> str:
        # <prefix>/<job>-<date>/<algo_id>/result.json -> <prefix>/<job>-<date>
        parts = result_json_key.split("/")
        if len(parts) < 3:
            raise ValueError(f"Unexpected result.json key: {result_json_key}")
        return "/".join(parts[:-2])

    @staticmethod
    def _download_and_extract_tar(*, s3_client, bucket: str, key: str, dest_dir: Path) -> None:
        dest_dir.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(suffix=".tar.gz") as tf:
            s3_client.download_fileobj(bucket, key, tf)
            tf.flush()
            with tarfile.open(tf.name) as tar:
                safe_extract_tar_archive(tar, dest_dir)
