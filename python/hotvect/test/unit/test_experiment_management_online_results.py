from __future__ import annotations

import gzip
import io
import json

from hotvect.experiment_management.online_results import MANIFEST_FILENAME, OnlineEvaluationResultsStore


def _gzip_bytes(payload: bytes) -> bytes:
    return gzip.compress(payload)


class FakeS3Client:
    def __init__(self, *, objects, blobs_by_key):
        self._objects = list(objects)
        self._blobs_by_key = dict(blobs_by_key)

    def list_objects_v2(self, *, Bucket, Prefix, ContinuationToken=None):
        assert Bucket == "bucket"
        assert ContinuationToken is None
        return {
            "Contents": [obj for obj in self._objects if obj["Key"].startswith(Prefix)],
            "IsTruncated": False,
        }

    def get_object(self, *, Bucket, Key):
        assert Bucket == "bucket"
        return {"Body": io.BytesIO(self._blobs_by_key[Key])}

    def download_file(self, Bucket, Key, Filename):
        assert Bucket == "bucket"
        with open(Filename, "wb") as handle:
            handle.write(self._blobs_by_key[Key])


def test_store_requires_explicit_s3_base_prefix():
    try:
        OnlineEvaluationResultsStore(s3_client=FakeS3Client(objects=[], blobs_by_key={}), s3_base_prefix="")
    except ValueError as exc:
        assert "non-empty string" in str(exc)
    else:
        raise AssertionError("expected ValueError")


def test_list_analysis_dates_groups_parts_and_filters_noise():
    prefix = "root/results"
    objects = [
        {
            "Key": f"{prefix}/experiment_id=1208/last_date_of_analysis=2026-01-21/part-00001-a.json.gz",
            "Size": 11,
            "ETag": '"a"',
            "LastModified": None,
        },
        {
            "Key": f"{prefix}/experiment_id=1208/last_date_of_analysis=2026-01-21/part-00002-b.json.gz",
            "Size": 12,
            "ETag": '"b"',
            "LastModified": None,
        },
        {
            "Key": f"{prefix}/experiment_id=1208/last_date_of_analysis=2026-01-28/part-00001-c.json.gz",
            "Size": 13,
            "ETag": '"c"',
            "LastModified": None,
        },
        {
            "Key": f"{prefix}/experiment_id=1208/last_date_of_analysis=2026-01-28/README.txt",
            "Size": 99,
            "ETag": '"skip"',
            "LastModified": None,
        },
    ]
    store = OnlineEvaluationResultsStore(
        s3_client=FakeS3Client(objects=objects, blobs_by_key={}),
        s3_base_prefix="s3://bucket/root/results/",
    )

    assert store.list_analysis_dates(experiment_id=1208) == [
        {
            "analysis_date": "2026-01-21",
            "part_count": 2,
            "s3_prefix": "s3://bucket/root/results/experiment_id=1208/last_date_of_analysis=2026-01-21/",
        },
        {
            "analysis_date": "2026-01-28",
            "part_count": 1,
            "s3_prefix": "s3://bucket/root/results/experiment_id=1208/last_date_of_analysis=2026-01-28/",
        },
    ]


def test_stream_analysis_date_decompresses_parts_in_key_order():
    prefix = "root/results"
    key_a = f"{prefix}/experiment_id=1208/last_date_of_analysis=2026-01-21/part-00001-a.json.gz"
    key_b = f"{prefix}/experiment_id=1208/last_date_of_analysis=2026-01-21/part-00002-b.json.gz"
    objects = [
        {"Key": key_b, "Size": 2, "ETag": '"b"', "LastModified": None},
        {"Key": key_a, "Size": 2, "ETag": '"a"', "LastModified": None},
    ]
    blobs = {
        key_a: _gzip_bytes(b'{"row":"a"}\n'),
        key_b: _gzip_bytes(b'{"row":"b"}\n'),
    }
    store = OnlineEvaluationResultsStore(
        s3_client=FakeS3Client(objects=objects, blobs_by_key=blobs),
        s3_base_prefix="s3://bucket/root/results/",
    )

    output = io.BytesIO()
    store.stream_analysis_date(experiment_id=1208, analysis_date="2026-01-21", output_stream=output)

    assert output.getvalue() == b'{"row":"a"}\n{"row":"b"}\n'


def test_download_analysis_dates_writes_manifest_for_selected_dates(tmp_path):
    prefix = "root/results"
    key_a = f"{prefix}/experiment_id=1208/last_date_of_analysis=2026-01-21/part-00001-a.json.gz"
    key_b = f"{prefix}/experiment_id=1208/last_date_of_analysis=2026-01-28/part-00001-b.json.gz"
    objects = [
        {"Key": key_a, "Size": 11, "ETag": '"a"', "LastModified": None},
        {"Key": key_b, "Size": 12, "ETag": '"b"', "LastModified": None},
    ]
    blobs = {key_a: _gzip_bytes(b"one\n"), key_b: _gzip_bytes(b"two\n")}
    store = OnlineEvaluationResultsStore(
        s3_client=FakeS3Client(objects=objects, blobs_by_key=blobs),
        s3_base_prefix="s3://bucket/root/results/",
    )

    experiment_root = tmp_path / "meta" / "online-evaluation-results" / "experiment_id=1208"

    result = store.download_analysis_dates(
        experiment_id=1208,
        experiment_root=experiment_root,
        analysis_date=None,
    )

    manifest_path = experiment_root / MANIFEST_FILENAME
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

    assert result["manifest_path"] == str(manifest_path)
    assert manifest["experiment_id"] == 1208
    assert [item["analysis_date"] for item in manifest["analysis_dates"]] == ["2026-01-21", "2026-01-28"]
    assert (experiment_root / "last_date_of_analysis=2026-01-21" / "part-00001-a.json.gz").read_bytes() == blobs[key_a]
    assert (experiment_root / "last_date_of_analysis=2026-01-28" / "part-00001-b.json.gz").read_bytes() == blobs[key_b]


def test_download_analysis_dates_rejects_non_empty_destination(tmp_path):
    prefix = "root/results"
    key_a = f"{prefix}/experiment_id=1208/last_date_of_analysis=2026-01-21/part-00001-a.json.gz"
    store = OnlineEvaluationResultsStore(
        s3_client=FakeS3Client(
            objects=[{"Key": key_a, "Size": 11, "ETag": '"a"', "LastModified": None}],
            blobs_by_key={key_a: _gzip_bytes(b"one\n")},
        ),
        s3_base_prefix="s3://bucket/root/results/",
    )
    experiment_root = tmp_path / "meta" / "online-evaluation-results" / "experiment_id=1208"
    experiment_root.mkdir(parents=True, exist_ok=True)
    (experiment_root / "MANIFEST").write_text("old", encoding="utf-8")

    try:
        store.download_analysis_dates(
            experiment_id=1208,
            experiment_root=experiment_root,
            analysis_date="2026-01-21",
        )
    except FileExistsError as exc:
        assert "Please remove the old data manually" in str(exc)
    else:
        raise AssertionError("expected FileExistsError")
