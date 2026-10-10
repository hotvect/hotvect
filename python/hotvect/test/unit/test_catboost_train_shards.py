import importlib.util
from datetime import date
from importlib.machinery import SourceFileLoader
from pathlib import Path
from shutil import copyfile

import pytest

from hotvect.pyhotvect import AlgorithmPipeline, AlgorithmPipelineContext

FIXTURE_DIR = Path(__file__).parent / "fixtures" / "catboost_train"


def _catboost_train_path() -> Path:
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "bin" / "catboost_train"
        if candidate.exists():
            return candidate
    raise FileNotFoundError("Could not locate bin/catboost_train relative to test file")


def _load_catboost_train_module():
    pytest.importorskip("catboost")
    candidate = _catboost_train_path()
    loader = SourceFileLoader("catboost_train_cli", str(candidate))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    assert spec is not None
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


def test_resolve_encoded_file_merges_multiple_parts_in_sorted_order(tmp_path: Path):
    module = _load_catboost_train_module()
    encoded_dir = tmp_path / "encoded"
    encoded_dir.mkdir()
    scratch_dir = tmp_path / "scratch"
    (encoded_dir / "part-00001.tsv").write_text("b\n")
    (encoded_dir / "part-00000.tsv").write_text("a\n")

    resolved = Path(module.resolve_encoded_file(str(encoded_dir), scratch_dir=str(scratch_dir)))

    assert resolved == scratch_dir / "catboost_train_encoded.tsv"
    assert resolved.read_text() == "a\nb\n"


def test_resolve_encoded_file_merges_date_partition_symlinks_with_duplicate_basenames(tmp_path: Path):
    module = _load_catboost_train_module()
    encoded_dir = tmp_path / "encoded"
    encoded_dir.mkdir()
    scratch_dir = tmp_path / "scratch"

    for partition_date, content in [("2000-02-16", "b\n"), ("2000-02-15", "a\n")]:
        partition = tmp_path / "cache" / partition_date / "encoded"
        partition.mkdir(parents=True)
        (partition / "part-00000.tsv").write_text(content)
        (encoded_dir / f"dt={partition_date}").symlink_to(partition, target_is_directory=True)

    resolved = Path(module.resolve_encoded_file(str(encoded_dir), scratch_dir=str(scratch_dir)))

    assert resolved.read_text() == "a\nb\n"


def test_resolve_encoded_file_requires_scratch_dir_for_multiple_parts(tmp_path: Path):
    module = _load_catboost_train_module()
    (tmp_path / "part-00000.tsv").write_text("a\n")
    (tmp_path / "part-00001.tsv").write_text("b\n")

    with pytest.raises(ValueError, match="Pass scratch_dir"):
        module.resolve_encoded_file(str(tmp_path))


def test_resolve_encoded_file_accepts_single_part(tmp_path: Path):
    module = _load_catboost_train_module()
    part = tmp_path / "part-00000.tsv"
    part.write_text("only")

    assert module.resolve_encoded_file(str(tmp_path)) == str(part)


def test_resolve_encoded_file_rejects_empty_encoded_dir(tmp_path: Path):
    module = _load_catboost_train_module()

    with pytest.raises(ValueError, match="Expected at least one plain"):
        module.resolve_encoded_file(str(tmp_path))


def test_resolve_encoded_file_accepts_single_tsv_with_arbitrary_basename(tmp_path: Path):
    module = _load_catboost_train_module()
    encoded = tmp_path / "encoded.tsv"
    encoded.write_text("only")

    assert module.resolve_encoded_file(str(tmp_path)) == str(encoded)


def test_resolve_encoded_file_accepts_extensionless_part(tmp_path: Path):
    module = _load_catboost_train_module()
    part = tmp_path / "part-00000"
    part.write_text("only")

    assert module.resolve_encoded_file(str(tmp_path)) == str(part)


def test_resolve_encoded_file_accepts_legacy_shard_basename_when_tsv(tmp_path: Path):
    module = _load_catboost_train_module()
    shard = tmp_path / "shard_0.tsv"
    shard.write_text("legacy")

    assert module.resolve_encoded_file(str(tmp_path)) == str(shard)


def test_resolve_encoded_file_ignores_partition_metadata(tmp_path: Path):
    module = _load_catboost_train_module()
    partition = tmp_path / "dt=2000-02-16"
    partition.mkdir()
    (partition / "part-00000.tsv").write_text("data\n")
    (partition / "encoded-schema-description").write_text("schema")
    (partition / "_SUCCESS").touch()

    assert module.resolve_encoded_file(str(tmp_path)) == str(partition / "part-00000.tsv")


def test_resolve_encoded_file_rejects_gzipped_tsv(tmp_path: Path):
    module = _load_catboost_train_module()
    gzipped = tmp_path / "encoded.tsv.gz"
    gzipped.write_text("gzipped")

    with pytest.raises(ValueError, match="does not support gzipped encoded files"):
        module.resolve_encoded_file(str(tmp_path))


def test_resolve_encoded_file_rejects_gzipped_extensionless_part(tmp_path: Path):
    module = _load_catboost_train_module()
    gzipped = tmp_path / "part-00000.gz"
    gzipped.write_text("gzipped")

    with pytest.raises(ValueError, match="does not support gzipped encoded files"):
        module.resolve_encoded_file(str(tmp_path))


def test_algorithm_pipeline_train_runs_catboost_train_on_mock_encoded_tsv(tmp_path: Path):
    pytest.importorskip("catboost")
    catboost_train_path = _catboost_train_path()
    algorithm_definition = {
        "algorithm_name": "catboost-algo",
        "algorithm_version": "1.0.0",
        "algorithm_factory_classname": "example.CatBoostFactory",
        "training_command": (
            "LAST_TEST_TIME={{ last_test_time.strftime('%Y-%m-%d') }} {{ python_executable }} "
            f"{catboost_train_path} "
            "--algorithm_definition_path {{ algorithm_definition_path }} "
            "--algorithm_jar_path {{ algorithm_jar_path }} "
            "--encoded_data_file_path {{ encoded_data_file_path }} "
            "--encoded_schema_description_file_path {{ encoded_schema_description_file_path }} "
            "--scratch_dir {{ scratch_dir }} "
            "--parameter_output_path {{ parameter_output_path }}"
        ),
        "catboost_options": {
            "iterations": 2,
            "verbose": False,
            "text_processing": {
                "tokenizers": [{"tokenizer_id": "Space", "separator_type": "ByDelimiter", "delimiter": " "}],
                "dictionaries": [
                    {
                        "dictionary_id": "Word",
                        "max_dictionary_size": "50000",
                        "occurrence_lower_bound": "1",
                    }
                ],
                "feature_processing": {
                    "default": [
                        {
                            "dictionaries_names": ["Word"],
                            "feature_calcers": ["BoW"],
                            "tokenizers_names": ["Space"],
                        }
                    ]
                },
            },
        },
    }
    pipeline = AlgorithmPipeline(
        algorithm_pipeline_context=AlgorithmPipelineContext(
            algorithm_jar_path=tmp_path / "algorithm.jar",
            data_base_path=tmp_path / "data",
            metadata_base_path=tmp_path / "metadata",
            output_base_path=tmp_path / "output",
            jvm_options=[],
        ),
        algorithm_definition=algorithm_definition,
        last_test_time=date(2000, 2, 17),
        evaluation_func=None,
    )
    encoded_dir = Path(pipeline.encoded_data_file_path())
    encoded_dir.mkdir(parents=True)
    copyfile(FIXTURE_DIR / "encoded.tsv", encoded_dir / "part-00000.tsv")
    schema_file = Path(pipeline.encoded_schema_description_file_path())
    schema_file.parent.mkdir(parents=True)
    copyfile(FIXTURE_DIR / "encoded-schema-description", schema_file)
    Path(pipeline.metadata_path()).mkdir(parents=True)
    pipeline._write_algorithm_definition()

    metadata = pipeline.train()

    assert "LAST_TEST_TIME=2000-02-17" in metadata["training_command"]
    assert str(catboost_train_path) in metadata["training_command"]
    assert (Path(pipeline.parameter_file_path()) / "model.parameter").is_file()
