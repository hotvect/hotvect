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


def test_resolve_shard_file_rejects_multiple_shards(tmp_path: Path):
    module = _load_catboost_train_module()
    (tmp_path / "shard_0.tsv").write_text("a")
    (tmp_path / "shard_1.tsv").write_text("b")

    with pytest.raises(ValueError, match="you have to use one shard"):
        module.resolve_shard_file(str(tmp_path))


def test_resolve_shard_file_accepts_single_shard(tmp_path: Path):
    module = _load_catboost_train_module()
    shard = tmp_path / "shard_0.tsv"
    shard.write_text("only")

    assert module.resolve_shard_file(str(tmp_path)) == str(shard)
