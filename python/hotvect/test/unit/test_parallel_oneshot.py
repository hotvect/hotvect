from pathlib import Path

import pytest
from botocore.exceptions import ClientError

from hotvect.parallel_oneshot import (
    build_parallel_shard_groups,
    build_parallel_shard_plans,
    build_parallel_submission_state,
    build_submission_pointer,
    expected_output_descriptor,
    list_source_objects,
    load_parallel_manifest_for_dest,
    load_parallel_submission_state_for_dest,
    parallel_submission_pointer_uri,
    put_json_to_s3,
    stable_shard_index,
    validate_parallel_dest_path_is_empty,
    verify_parallel_manifest,
)
from hotvect.sagemaker_contracts import ParallelExecutionConfig, parallel_text_output_uri


class _FakeBody:
    def __init__(self, data: bytes):
        self._data = data

    def read(self) -> bytes:
        return self._data


class _FakeS3Client:
    def __init__(self):
        self.objects: dict[tuple[str, str], bytes] = {}

    def put_object(self, *, Bucket, Key, Body, ContentType=None):
        del ContentType
        if isinstance(Body, str):
            body = Body.encode("utf-8")
        else:
            body = Body
        self.objects[(Bucket, Key)] = body

    def get_object(self, *, Bucket, Key):
        return {"Body": _FakeBody(self.objects[(Bucket, Key)])}

    def upload_file(self, Filename, Bucket, Key):
        self.objects[(Bucket, Key)] = Path(Filename).read_bytes()

    def download_fileobj(self, *, Bucket, Key, Fileobj):
        Fileobj.write(self.objects[(Bucket, Key)])

    def head_object(self, *, Bucket, Key):
        if (Bucket, Key) not in self.objects:
            raise ClientError({"Error": {"Code": "404"}}, "HeadObject")
        return {"ContentLength": len(self.objects[(Bucket, Key)])}

    def list_objects_v2(self, *, Bucket, Prefix, MaxKeys=None):
        matching = []
        for (bucket, key), body in self.objects.items():
            if bucket == Bucket and key.startswith(Prefix):
                matching.append({"Key": key, "Size": len(body)})
        if MaxKeys is not None:
            matching = matching[:MaxKeys]
        return {"Contents": matching} if matching else {}

    def get_paginator(self, operation_name):
        assert operation_name == "list_objects_v2"

        client = self

        class _Paginator:
            def paginate(self, **kwargs):
                yield client.list_objects_v2(**kwargs)

        return _Paginator()


class _FakeSageMakerClient:
    def __init__(self, statuses: dict[str, str]):
        self.statuses = statuses

    def describe_training_job(self, *, TrainingJobName):
        return {"TrainingJobStatus": self.statuses[TrainingJobName]}


def test_build_parallel_shard_groups_is_deterministic():
    from hotvect.parallel_oneshot import S3ObjectRef

    objects = [
        S3ObjectRef(key="prefix/file-a.json.gz", size=10),
        S3ObjectRef(key="prefix/file-b.json.gz", size=11),
        S3ObjectRef(key="prefix/file-c.json.gz", size=12),
        S3ObjectRef(key="prefix/file-d.json.gz", size=13),
    ]

    first = build_parallel_shard_groups(objects, 3, "s3://bucket/prefix/")
    second = build_parallel_shard_groups(objects, 3, "s3://bucket/prefix/")

    assert [[item.key for item in group] for group in first] == [[item.key for item in group] for group in second]
    assert stable_shard_index("prefix/file-a.json.gz", 3) == stable_shard_index("prefix/file-a.json.gz", 3)


def test_build_parallel_shard_groups_rejects_keys_outside_source_prefix():
    from hotvect.parallel_oneshot import S3ObjectRef

    with pytest.raises(ValueError, match="does not live under source prefix"):
        build_parallel_shard_groups(
            [S3ObjectRef(key="other/file-a.json.gz", size=10)],
            3,
            "s3://bucket/prefix/",
        )


def test_build_parallel_shard_plans_use_source_relative_paths_and_skip_empty_shards():
    from hotvect.parallel_oneshot import S3ObjectRef

    source_objects = [
        S3ObjectRef(key="prefix/file-a.json.gz", size=10),
        S3ObjectRef(key="prefix/nested/file-b.json.gz", size=11),
    ]

    plans = build_parallel_shard_plans(
        task="predict",
        source_s3_uri="s3://bucket/prefix/",
        dest_path="s3://bucket/out/",
        compression="none",
        job_parallelism=8,
        source_objects=source_objects,
    )

    assert [plan.index for plan in plans] == sorted(
        {
            stable_shard_index("file-a.json.gz", 8),
            stable_shard_index("nested/file-b.json.gz", 8),
        }
    )


def test_build_parallel_shard_plans_rejects_empty_source_objects():
    with pytest.raises(ValueError, match="No source objects found"):
        build_parallel_shard_plans(
            task="predict",
            source_s3_uri="s3://bucket/prefix/",
            dest_path="s3://bucket/out/",
            compression="none",
            job_parallelism=8,
            source_objects=[],
        )


def test_list_source_objects_normalizes_prefix_and_skips_nested_underscore_prefixed_paths():
    s3_client = _FakeS3Client()
    s3_client.put_object(Bucket="bucket", Key="prefix/_temporary/file-a.json.gz", Body=b"a")
    s3_client.put_object(Bucket="bucket", Key="prefix/nested/_metadata/file-b.json.gz", Body=b"b")
    s3_client.put_object(Bucket="bucket", Key="prefix/nested/file-c.json.gz", Body=b"c")
    s3_client.put_object(Bucket="bucket", Key="prefix-other/file-d.json.gz", Body=b"d")

    objects = list_source_objects(s3_client, "s3://bucket/prefix")

    assert [obj.key for obj in objects] == ["prefix/nested/file-c.json.gz"]


def test_validate_parallel_dest_path_is_empty_rejects_existing_prefix():
    s3_client = _FakeS3Client()
    s3_client.put_object(Bucket="bucket", Key="dest/existing.jsonl", Body=b"already here")

    with pytest.raises(ValueError, match="fresh output prefix"):
        validate_parallel_dest_path_is_empty(s3_client=s3_client, dest_path="s3://bucket/dest/")


def test_expected_output_descriptor_for_predict_uses_part_files():
    dest_path = "s3://bucket/final/predict/"

    assert parallel_text_output_uri(dest_path, 3, 12, "gzip") == "s3://bucket/final/predict/part-00003-00012.jsonl.gz"
    assert expected_output_descriptor("predict", dest_path, 3, "gzip") == {
        "type": "part-files",
        "uri_prefix": dest_path,
        "filename_glob": "part-00003-*.jsonl.gz",
    }


def test_expected_output_descriptor_for_encode_uses_part_prefixes():
    dest_path = "s3://bucket/final/encode/"

    assert expected_output_descriptor("encode", dest_path, 3, "none") == {
        "type": "encode",
        "schema_uri": "s3://bucket/final/encode/part-00003/encoded-schema-description",
        "encoded_prefix_uri": "s3://bucket/final/encode/part-00003/encoded/",
    }


@pytest.mark.parametrize(
    ("worker_count", "worker_index", "match"),
    [
        (1, 0, "worker_count"),
        (4, -1, "worker_index"),
        (4, 4, "worker_index"),
    ],
)
def test_parallel_execution_config_rejects_invalid_bounds(worker_count: int, worker_index: int, match: str):
    with pytest.raises(ValueError, match=match):
        ParallelExecutionConfig(worker_count=worker_count, worker_index=worker_index)


def test_verify_parallel_manifest_writes_success_when_complete():
    s3_client = _FakeS3Client()
    dest_path = "s3://bucket/final/predict/"

    manifest = {
        "run_id": "run-1",
        "task": "predict",
        "dest_path": dest_path,
        "shards": [
            {
                "index": 0,
                "training_job_name": "job-0",
                "expected_output": expected_output_descriptor("predict", dest_path, 0, "gzip"),
            }
        ],
    }
    submission_state = build_parallel_submission_state(
        run_id="run-1",
        task="predict",
        dest_path=dest_path,
        shards=[
            type(
                "_Shard",
                (),
                {
                    "index": 0,
                    "training_job_name": "job-0",
                    "s3_uri_result_file": None,
                    "s3_uri_metadata": None,
                    "task_output_s3_uri": None,
                    "sagemaker_output_s3_path": None,
                    "hyperparameter_slug": None,
                },
            )()
        ],
    )
    sagemaker_client = _FakeSageMakerClient({"job-0": "Completed"})

    result = verify_parallel_manifest(
        s3_client=s3_client,
        sagemaker_client=sagemaker_client,
        manifest=manifest,
        submission_state=submission_state,
        write_success=True,
    )

    assert result["complete"] is True
    assert result["success_written"] is True
    assert ("bucket", "final/predict/_SUCCESS") in s3_client.objects


def test_verify_parallel_manifest_does_not_write_success_while_job_still_running():
    s3_client = _FakeS3Client()
    dest_path = "s3://bucket/final/predict/"
    manifest = {
        "run_id": "run-2",
        "task": "predict",
        "dest_path": dest_path,
        "shards": [
            {
                "index": 0,
                "training_job_name": "job-0",
                "expected_output": expected_output_descriptor("predict", dest_path, 0, "gzip"),
            }
        ],
    }
    submission_state = {
        "run_id": "run-2",
        "task": "predict",
        "dest_path": dest_path,
        "shards": [{"index": 0, "training_job_name": "job-0"}],
    }
    sagemaker_client = _FakeSageMakerClient({"job-0": "InProgress"})

    result = verify_parallel_manifest(
        s3_client=s3_client,
        sagemaker_client=sagemaker_client,
        manifest=manifest,
        submission_state=submission_state,
        write_success=True,
    )

    assert result["complete"] is False
    assert result["running_jobs"] == ["job-0"]
    assert result["failed_jobs"] == []
    assert result["success_written"] is False
    assert ("bucket", "final/predict/_SUCCESS") not in s3_client.objects


def test_verify_parallel_manifest_uses_completed_jobs_as_completion_signal():
    s3_client = _FakeS3Client()
    dest_path = "s3://bucket/final/predict/"
    manifest = {
        "run_id": "run-3",
        "task": "predict",
        "dest_path": dest_path,
        "shards": [
            {
                "index": 0,
                "training_job_name": "job-0",
                "expected_output": expected_output_descriptor("predict", dest_path, 0, "gzip"),
            }
        ],
    }
    submission_state = {
        "run_id": "run-3",
        "task": "predict",
        "dest_path": dest_path,
        "shards": [{"index": 0, "training_job_name": "job-0"}],
    }
    sagemaker_client = _FakeSageMakerClient({"job-0": "Completed"})

    result = verify_parallel_manifest(
        s3_client=s3_client,
        sagemaker_client=sagemaker_client,
        manifest=manifest,
        submission_state=submission_state,
        write_success=True,
    )

    assert result["complete"] is True
    assert result["running_jobs"] == []
    assert result["failed_jobs"] == []
    assert result["success_written"] is True
    assert ("bucket", "final/predict/_SUCCESS") in s3_client.objects


def test_verify_parallel_manifest_treats_unsubmitted_shard_as_incomplete():
    s3_client = _FakeS3Client()
    dest_path = "s3://bucket/final/predict/"
    manifest = {
        "run_id": "run-4",
        "task": "predict",
        "dest_path": dest_path,
        "shards": [
            {
                "index": 0,
                "training_job_name": "job-0",
                "expected_output": expected_output_descriptor("predict", dest_path, 0, "none"),
            },
            {
                "index": 1,
                "training_job_name": None,
                "expected_output": expected_output_descriptor("predict", dest_path, 1, "none"),
            },
        ],
    }
    submission_state = {
        "run_id": "run-4",
        "task": "predict",
        "dest_path": dest_path,
        "shards": [
            {"index": 0, "training_job_name": "job-0"},
            {"index": 1, "training_job_name": None},
        ],
    }
    sagemaker_client = _FakeSageMakerClient({"job-0": "Completed"})

    result = verify_parallel_manifest(
        s3_client=s3_client,
        sagemaker_client=sagemaker_client,
        manifest=manifest,
        submission_state=submission_state,
        write_success=True,
    )

    assert result["complete"] is False
    assert result["running_jobs"] == []
    assert result["failed_jobs"] == []
    assert result["unsubmitted_shards"] == [1]
    assert result["shards"][1]["job_status"] == "Unsubmitted"
    assert ("bucket", "final/predict/_SUCCESS") not in s3_client.objects


def test_load_parallel_manifest_for_dest_uses_submission_pointer():
    s3_client = _FakeS3Client()
    manifest_uri = "s3://managed/out/_parallel_oneshot_runs/run-1/parallel_oneshot_manifest.json"
    submission_state_uri = "s3://managed/out/_parallel_oneshot_runs/run-1/parallel_oneshot_submission_state.json"
    manifest = {"run_id": "run-1", "task": "predict", "dest_path": "s3://bucket/final/"}
    put_json_to_s3(s3_client, manifest_uri, manifest)
    put_json_to_s3(
        s3_client,
        submission_state_uri,
        {"run_id": "run-1", "task": "predict", "dest_path": "s3://bucket/final/", "shards": []},
    )
    put_json_to_s3(
        s3_client,
        parallel_submission_pointer_uri("s3://bucket/final/"),
        build_submission_pointer(
            task="predict",
            run_id="run-1",
            manifest_s3_uri=manifest_uri,
            submission_state_s3_uri=submission_state_uri,
            dest_path="s3://bucket/final/",
        ),
    )

    loaded = load_parallel_manifest_for_dest(
        s3_client=s3_client,
        dest_path="s3://bucket/final/",
        s3_output_base="s3://managed/out/",
    )

    assert loaded == manifest


def test_load_parallel_submission_state_for_dest_uses_submission_pointer():
    s3_client = _FakeS3Client()
    manifest_uri = "s3://managed/out/_parallel_oneshot_runs/run-1/parallel_oneshot_manifest.json"
    submission_state_uri = "s3://managed/out/_parallel_oneshot_runs/run-1/parallel_oneshot_submission_state.json"
    submission_state = {"run_id": "run-1", "task": "predict", "dest_path": "s3://bucket/final/", "shards": []}
    put_json_to_s3(s3_client, manifest_uri, {"run_id": "run-1", "task": "predict", "dest_path": "s3://bucket/final/"})
    put_json_to_s3(s3_client, submission_state_uri, submission_state)
    put_json_to_s3(
        s3_client,
        parallel_submission_pointer_uri("s3://bucket/final/"),
        build_submission_pointer(
            task="predict",
            run_id="run-1",
            manifest_s3_uri=manifest_uri,
            submission_state_s3_uri=submission_state_uri,
            dest_path="s3://bucket/final/",
        ),
    )

    loaded = load_parallel_submission_state_for_dest(
        s3_client=s3_client,
        dest_path="s3://bucket/final/",
        s3_output_base="s3://managed/out/",
    )

    assert loaded == submission_state
