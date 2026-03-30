from pathlib import Path
from unittest.mock import ANY, MagicMock

import pytest


def _make_pipeline(tmp_path: Path, *, algorithm_definition: dict, algorithm_name="algo", algorithm_version="55.7.0"):
    from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext

    pipeline = AlgorithmPipeline.__new__(AlgorithmPipeline)
    pipeline.algorithm_definition = algorithm_definition
    pipeline.algorithm_name = algorithm_name
    pipeline.algorithm_version = algorithm_version
    pipeline.hyper_parameter_version = ""
    pipeline.parameter_version = "last_test_date_2026-02-17"
    pipeline.available_parameter_cache_path = None
    pipeline.algorithm_pipeline_context = AlgorithmPipelineContext(
        algorithm_jar_path=tmp_path / "algo.jar",
        data_base_path=tmp_path,
        metadata_base_path=tmp_path,
        output_base_path=tmp_path,
        jvm_options=None,
    )
    return pipeline


@pytest.mark.parametrize(
    ("cache_scope", "expected_key"),
    [
        ("major", "algo@55"),
        ("minor", "algo@55.7"),
        ("patch", "algo@55.7.0"),
        ("hyperparam", "algo@55.7.0"),
    ],
)
def test_cache_scope_affects_algorithm_cache_key(tmp_path: Path, cache_scope: str, expected_key: str):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={"hotvect_execution_parameters": {"cache_scope": cache_scope}},
        algorithm_name="algo",
        algorithm_version="55.7.0",
    )
    assert pipeline._cache_algorithm_key() == expected_key


def test_cache_scope_hyperparam_appends_hyperparameter_version(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={"hotvect_execution_parameters": {"cache_scope": "hyperparam"}},
        algorithm_name="algo",
        algorithm_version="55.7.0",
    )
    pipeline.hyper_parameter_version = "ordered"
    assert pipeline._cache_algorithm_key() == "algo@55.7.0-ordered"


def test_resolve_cache_path_uses_cache_algorithm_key_and_parameter_version(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "cache_base_dir": "/tmp/hotvect-cache",
                "cache_scope": "minor",
            }
        },
        algorithm_name="algo",
        algorithm_version="55.7.9",
    )
    pipeline.parameter_version = "last_test_date_2026-02-17"
    cache_path = pipeline._resolve_cache_path(["encode"], "encoded.bin")
    assert cache_path == "/tmp/hotvect-cache/algo@55.7/last_test_date_2026-02-17/encode/encoded.bin"


def test_encode_ignores_cache_reads_when_cache_refresh_enabled(tmp_path: Path, monkeypatch):
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "cache_scope": "hyperparam",
                "cache_refresh": True,
            }
        },
        algorithm_name="algo",
        algorithm_version="55.7.0",
    )

    def fail_if_called(*args, **kwargs):
        raise AssertionError("as_locally_available_content should not be called when cache_refresh is enabled")

    monkeypatch.setattr(pyhotvect_module, "as_locally_available_content", fail_if_called)

    store_calls = []

    def record_store(src: str, dest: str):
        store_calls.append((src, dest))

    monkeypatch.setattr(pyhotvect_module, "store_file", record_store)

    pipeline._do_encode = MagicMock(return_value={})

    # Provide minimal paths used by encode()
    pipeline.encoded_data_file_path = MagicMock(return_value=str(tmp_path / "encoded.bin"))
    pipeline.encoded_schema_description_file_path = MagicMock(return_value=str(tmp_path / "schema.json"))
    pipeline.cache_path = MagicMock(return_value=str(tmp_path / "local-cache"))

    pipeline.encode()

    assert pipeline._do_encode.call_count == 1
    assert len(store_calls) == 2


def test_available_predict_parameter_cache_path_returns_none_when_cache_refresh_enabled(tmp_path: Path, monkeypatch):
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "cache_refresh": True,
            }
        },
    )

    as_local = MagicMock(return_value=str(tmp_path / "predict-parameters.zip"))
    monkeypatch.setattr(pyhotvect_module, "as_locally_available_content", as_local)

    result = pipeline.available_predict_parameter_cache_path()

    assert result is None
    as_local.assert_not_called()


def test_available_predict_parameter_cache_path_still_requires_with_parameter(tmp_path: Path, monkeypatch):
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "cache_refresh": True,
                "with_parameter": "s3://bucket/prefix/params.zip",
            }
        },
    )

    as_local = MagicMock(return_value=None)
    monkeypatch.setattr(pyhotvect_module, "as_locally_available_content", as_local)

    with pytest.raises(ValueError, match="with_parameter"):
        pipeline.available_predict_parameter_cache_path()

    assert as_local.call_count == 1


@pytest.mark.parametrize("scope", ["major", "minor"])
def test_cache_scope_major_minor_requires_semver_like_algorithm_version(tmp_path: Path, scope: str):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={"hotvect_execution_parameters": {"cache_scope": scope}},
        algorithm_version="v82",
    )
    with pytest.raises(ValueError, match="requires a semver-like"):
        pipeline._cache_algorithm_key()


def test_cache_scope_patch_allows_non_semver_algorithm_version(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={"hotvect_execution_parameters": {"cache_scope": "patch"}},
        algorithm_version="v82",
    )
    assert pipeline._cache_algorithm_key() == "algo@v82"


def test_per_task_cache_can_be_disabled_even_if_cache_base_dir_is_set(tmp_path: Path, monkeypatch):
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "encode": {"cache": False},
            }
        },
    )

    store_file = MagicMock()
    monkeypatch.setattr(pyhotvect_module, "store_file", store_file)

    pipeline._do_encode = MagicMock(return_value={})
    pipeline.encoded_data_file_path = MagicMock(return_value=str(tmp_path / "encoded.bin"))
    pipeline.encoded_schema_description_file_path = MagicMock(return_value=str(tmp_path / "schema.json"))
    pipeline.cache_path = MagicMock(return_value=str(tmp_path / "local-cache"))

    pipeline.encode()

    assert pipeline._resolve_cache_path(["encode"], "encoded.bin") is None
    assert store_file.call_count == 0


def test_per_task_cache_string_override_template_is_rendered(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "encode": {
                    "cache": "/tmp/override/{{ parameter_version }}/{{ hyperparameter_slug }}",
                }
            }
        },
        algorithm_name="algo",
        algorithm_version="55.7.0",
    )
    pipeline.hyper_parameter_version = "ordered"
    pipeline.parameter_version = "last_test_date_2026-02-17"

    cache_path = pipeline._resolve_cache_path(["encode"], "encoded.bin")
    assert cache_path == "/tmp/override/last_test_date_2026-02-17/algo@55.7.0-ordered/encoded.bin"


def test_with_parameter_is_still_used_when_cache_refresh_enabled_if_available(tmp_path: Path, monkeypatch):
    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "cache_base_dir": str(tmp_path / "cache"),
                "cache_refresh": True,
                "with_parameter": "s3://bucket/prefix/params.zip",
            }
        },
    )
    pipeline.cache_path = MagicMock(return_value=str(tmp_path / "local-cache"))

    as_local = MagicMock(return_value=str(tmp_path / "params.zip"))
    monkeypatch.setattr(pyhotvect_module, "as_locally_available_content", as_local)

    result = pipeline.available_predict_parameter_cache_path()

    assert result == str(tmp_path / "params.zip")
    as_local.assert_called_once()


def test_with_parameter_skips_dependency_preparation(tmp_path: Path, monkeypatch):
    import contextlib
    from datetime import datetime, timezone

    from hotvect import pyhotvect as pyhotvect_module
    from hotvect.pyhotvect import AlgorithmPipeline

    pipeline = AlgorithmPipeline.__new__(AlgorithmPipeline)
    pipeline.algorithm_definition = {"hotvect_execution_parameters": {"with_parameter": "s3://bucket/params.zip"}}
    pipeline.algorithm_name = "algo"
    pipeline.algorithm_version = "55.7.0"
    pipeline.hyper_parameter_version = ""
    pipeline.parameter_version = "last_test_date_2026-02-17"
    pipeline.last_test_time = datetime(2026, 2, 17, tzinfo=timezone.utc)
    pipeline.ran_at = "2026-02-17T00:00:00Z"
    pipeline.hyperparameter_slug = MagicMock(return_value="algo@55.7.0")

    pipeline.clean = MagicMock()
    pipeline.cache_path = MagicMock(return_value=str(tmp_path / "cache"))
    pipeline._pipe_python_logs_to_metadata_dir = MagicMock(return_value=contextlib.nullcontext())
    pipeline._write_algorithm_definition = MagicMock(return_value=str(tmp_path / "algo-definition.json"))
    pipeline.available_predict_parameter_cache_path = MagicMock(return_value=str(tmp_path / "params.zip"))
    pipeline._step_predict_parameter = MagicMock()
    pipeline.test_data_paths = MagicMock(return_value=[])
    pipeline._write_data = MagicMock()
    pipeline.metadata_path = MagicMock(return_value=str(tmp_path / "metadata"))

    pipeline.algorithm_is_state = False
    pipeline.should_train = MagicMock(return_value=False)
    pipeline.execute_performance_test = False
    pipeline.encode_test_data = False
    pipeline.execute_audit = False

    dependency_pipeline = MagicMock()
    dependency_pipeline.run_all = MagicMock()
    pipeline.dependency_pipelines = {"dep": dependency_pipeline}

    monkeypatch.setattr(pyhotvect_module, "clean_dir", MagicMock())

    result = pipeline.run_all(clean=False, evaluate=True)

    dependency_pipeline.run_all.assert_not_called()
    assert result["dependencies"] == {"skipped": ANY}


def test_cache_string_override_that_points_to_file_is_not_appended_again(tmp_path: Path):
    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={
            "hotvect_execution_parameters": {
                "train": {
                    "cache": "s3://example-bucket/some-prefix/predict-parameters.zip",
                }
            }
        },
    )
    assert (
        pipeline._resolve_cache_path(["train"], "predict-parameters.zip")
        == "s3://example-bucket/some-prefix/predict-parameters.zip"
    )


def test_package_predict_parameters_cached_zip_skips_directory_entries(tmp_path: Path, monkeypatch):
    import zipfile

    from hotvect import pyhotvect as pyhotvect_module
    from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext

    out_base = tmp_path / "out"
    out_base.mkdir()

    pipeline = AlgorithmPipeline.__new__(AlgorithmPipeline)
    pipeline.algorithm_definition = {}
    pipeline.algorithm_name = "algo"
    pipeline.algorithm_version = "55.7.0"
    pipeline.hyper_parameter_version = ""
    pipeline.parameter_version = "last_test_date_2026-02-17"
    pipeline.available_parameter_cache_path = None
    pipeline.algorithm_pipeline_context = AlgorithmPipelineContext(
        algorithm_jar_path=tmp_path / "algo.jar",
        data_base_path=tmp_path,
        metadata_base_path=tmp_path,
        output_base_path=out_base,
        jvm_options=None,
    )

    cached_zip = tmp_path / "predict-parameters.zip"
    with zipfile.ZipFile(cached_zip, "w") as zf:
        zf.writestr("algo/", "")  # directory entry
        zf.writestr("algo/model.bin", b"abc")
        zf.writestr("algo/subdir/", "")  # directory entry
        zf.writestr("algo/subdir/file.txt", "hello")

    pipeline.available_predict_parameter_cache_path = MagicMock(return_value=str(cached_zip))
    monkeypatch.setattr(pyhotvect_module, "copy_or_link", lambda src, dst: None)

    result = pipeline.package_predict_parameters()

    extracted_root = Path(pipeline.output_path())
    assert (extracted_root / "model.bin").read_bytes() == b"abc"
    assert (extracted_root / "subdir" / "file.txt").read_text() == "hello"
    assert "skipped" in result


def test_state_predict_parameters_zip_is_opt_in(tmp_path: Path, monkeypatch):
    from hotvect.pyhotvect import AlgorithmPipeline

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={},
        algorithm_name="state-algo",
    )
    pipeline.algorithm_is_state = True
    pipeline.predict_parameter_cache_original_path = MagicMock(return_value=None)
    pipeline.available_predict_parameter_cache_path = MagicMock(return_value=None)
    pipeline._do_package_parameters = MagicMock(return_value={"package": None})

    AlgorithmPipeline.package_predict_parameters(pipeline)

    pipeline._do_package_parameters.assert_called_once_with(is_encode=False, skip_zip=True)


def test_state_predict_parameters_zip_can_be_enabled(tmp_path: Path, monkeypatch):
    from hotvect.pyhotvect import AlgorithmPipeline

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={"hotvect_execution_parameters": {"package_state_as_predict_parameters": True}},
        algorithm_name="state-algo",
    )
    pipeline.algorithm_is_state = True
    pipeline.predict_parameter_cache_original_path = MagicMock(return_value=None)
    pipeline.available_predict_parameter_cache_path = MagicMock(return_value=None)
    pipeline._do_package_parameters = MagicMock(return_value={"package": str(tmp_path / "params.zip")})

    AlgorithmPipeline.package_predict_parameters(pipeline)

    pipeline._do_package_parameters.assert_called_once_with(is_encode=False, skip_zip=False)


def test_package_encode_parameters_cached_zip_rejects_zip_slip(tmp_path: Path, monkeypatch):
    import zipfile

    from hotvect import pyhotvect as pyhotvect_module

    pipeline = _make_pipeline(
        tmp_path,
        algorithm_definition={"hotvect_execution_parameters": {"cache_base_dir": str(tmp_path / "cache")}},
        algorithm_name="algo",
        algorithm_version="55.7.0",
    )

    cached_zip = tmp_path / "encoding-parameters.zip"
    with zipfile.ZipFile(cached_zip, "w") as zf:
        zf.writestr("algo/../escaped.txt", "bad")

    monkeypatch.setattr(pyhotvect_module, "copy_or_link", lambda src, dst: None)
    monkeypatch.setattr(pyhotvect_module, "as_locally_available_content", lambda *_args, **_kwargs: str(cached_zip))

    escaped = Path(pipeline.output_path()).parent / "escaped.txt"
    with pytest.raises(ValueError, match="outside base directory"):
        pipeline.package_encode_parameters()

    assert not escaped.exists()
