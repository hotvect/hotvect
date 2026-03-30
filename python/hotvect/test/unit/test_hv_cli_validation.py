import subprocess
import sys
from pathlib import Path


def _hv_bin_path() -> Path:
    python_dir = Path(__file__).resolve().parents[3]
    return python_dir / "bin" / "hv"


def test_hv_rejects_algorithm_name_json_path():
    hv_bin = _hv_bin_path()
    python_dir = hv_bin.parent.parent

    result = subprocess.run(
        [
            sys.executable,
            str(hv_bin),
            "audit",
            "--algorithm-jar",
            "algo.jar",
            "--algorithm-name",
            "algo-definition.json",
            "--source-path",
            "input.jsonl",
            "--dest-path",
            "out.jsonl",
            "--metadata-path",
            "meta.json",
        ],
        cwd=python_dir,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 2
    assert "Use --algorithm-override" in result.stderr
    assert "not a file path" in result.stderr


def test_hv_rejects_algorithm_name_with_path_separator():
    hv_bin = _hv_bin_path()
    python_dir = hv_bin.parent.parent

    result = subprocess.run(
        [
            sys.executable,
            str(hv_bin),
            "audit",
            "--algorithm-jar",
            "algo.jar",
            "--algorithm-name",
            "some/dir/my_algo",
            "--source-path",
            "input.jsonl",
            "--dest-path",
            "out.jsonl",
            "--metadata-path",
            "meta.json",
        ],
        cwd=python_dir,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 2
    assert "Use --algorithm-override" in result.stderr
    assert "not a file path" in result.stderr


def test_hv_predict_help_mentions_include_feature_store_responses_flag():
    hv_bin = _hv_bin_path()
    python_dir = hv_bin.parent.parent

    result = subprocess.run(
        [sys.executable, str(hv_bin), "predict", "--help"],
        cwd=python_dir,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 0
    assert "--include-feature-store-responses" in result.stdout


def test_hv_audit_help_mentions_include_feature_store_responses_flag():
    hv_bin = _hv_bin_path()
    python_dir = hv_bin.parent.parent

    result = subprocess.run(
        [sys.executable, str(hv_bin), "audit", "--help"],
        cwd=python_dir,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 0
    assert "--include-feature-store-responses" in result.stdout


def test_hv_train_help_mentions_cache_flags():
    hv_bin = _hv_bin_path()
    python_dir = hv_bin.parent.parent

    result = subprocess.run(
        [sys.executable, str(hv_bin), "train", "--help"],
        cwd=python_dir,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 0
    assert "--cache" in result.stdout
    assert "--cache-scope" in result.stdout
    assert "--cache-refresh" in result.stdout


def test_hv_train_cache_refresh_requires_cache_base_dir(tmp_path: Path):
    hv_bin = _hv_bin_path()
    python_dir = hv_bin.parent.parent

    dummy_jar = tmp_path / "algo.jar"
    dummy_jar.write_text("not a jar, but path existence is enough for CLI validation")

    result = subprocess.run(
        [
            sys.executable,
            str(hv_bin),
            "train",
            "--algorithm-name",
            "algo",
            "--algorithm-jar",
            str(dummy_jar),
            "--last-test-time",
            "2026-01-07",
            "--data-base-dir",
            str(tmp_path),
            "--output-base-dir",
            str(tmp_path),
            "--cache-refresh",
        ],
        cwd=python_dir,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 2
    assert "requires --cache" in result.stderr
