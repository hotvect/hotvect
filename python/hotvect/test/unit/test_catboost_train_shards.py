import importlib.util
from importlib.machinery import SourceFileLoader
from pathlib import Path

import pytest


def _load_catboost_train_module():
    pytest.importorskip("catboost")
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "bin" / "catboost_train"
        if candidate.exists():
            loader = SourceFileLoader("catboost_train_cli", str(candidate))
            spec = importlib.util.spec_from_loader(loader.name, loader)
            assert spec is not None
            module = importlib.util.module_from_spec(spec)
            loader.exec_module(module)
            return module
    raise FileNotFoundError("Could not locate bin/catboost_train relative to test file")


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
