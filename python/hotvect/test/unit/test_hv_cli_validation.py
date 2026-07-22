import json
import os
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


def test_hv_help_mentions_docs_command():
    hv_bin = _hv_bin_path()
    python_dir = hv_bin.parent.parent

    result = subprocess.run(
        [sys.executable, str(hv_bin), "--help"],
        cwd=python_dir,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 0
    assert "docs" in result.stdout


def test_hv_help_mentions_prompts_command():
    hv_bin = _hv_bin_path()
    python_dir = hv_bin.parent.parent

    result = subprocess.run(
        [sys.executable, str(hv_bin), "--help"],
        cwd=python_dir,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 0
    assert "prompts" in result.stdout


def test_hv_docs_search_outputs_json_and_defaults_to_scan(tmp_path: Path):
    hv_bin = _hv_bin_path()
    python_dir = hv_bin.parent.parent
    cache_dir = tmp_path / "cache"

    env = dict(os.environ, HOTVECT_CACHE_DIR=str(cache_dir), PYTHONPATH=str(python_dir))
    result = subprocess.run(
        [sys.executable, str(hv_bin), "docs", "search", "backtest", "--limit", "3"],
        cwd=python_dir,
        capture_output=True,
        text=True,
        env=env,
    )

    assert result.returncode == 0
    payload = json.loads(result.stdout)
    assert payload["backend"] == "scan"
    assert payload["query"] == "backtest"
    assert payload["matches"]
    assert not list(tmp_path.rglob("*.sqlite"))


def test_hv_docs_list_outputs_json():
    hv_bin = _hv_bin_path()
    python_dir = hv_bin.parent.parent

    result = subprocess.run(
        [sys.executable, str(hv_bin), "docs", "list"],
        cwd=python_dir,
        capture_output=True,
        text=True,
        env=dict(os.environ, PYTHONPATH=str(python_dir)),
    )

    assert result.returncode == 0
    payload = json.loads(result.stdout)
    assert payload["docs"]
    assert any(doc["relpath"] == "index.md" for doc in payload["docs"])


def test_hv_docs_read_accepts_relative_path():
    hv_bin = _hv_bin_path()
    python_dir = hv_bin.parent.parent

    result = subprocess.run(
        [sys.executable, str(hv_bin), "docs", "read", "index.md"],
        cwd=python_dir,
        capture_output=True,
        text=True,
        env=dict(os.environ, PYTHONPATH=str(python_dir)),
    )

    assert result.returncode == 0
    payload = json.loads(result.stdout)
    assert payload["uri"] == "hotvect://docs/index.md"
    assert payload["mimeType"] == "text/markdown"
    assert "Hotvect" in payload["text"]


def test_hv_docs_read_missing_doc_exits_cleanly():
    hv_bin = _hv_bin_path()
    python_dir = hv_bin.parent.parent

    result = subprocess.run(
        [sys.executable, str(hv_bin), "docs", "read", "does/not/exist.md"],
        cwd=python_dir,
        capture_output=True,
        text=True,
        env=dict(os.environ, PYTHONPATH=str(python_dir)),
    )

    assert result.returncode == 2
    assert "Not found: hotvect://docs/does/not/exist.md" in result.stderr
    assert "Traceback" not in result.stderr


def test_hv_docs_read_rejects_parent_traversal_cleanly():
    hv_bin = _hv_bin_path()
    python_dir = hv_bin.parent.parent

    result = subprocess.run(
        [sys.executable, str(hv_bin), "docs", "read", "../secret.md"],
        cwd=python_dir,
        capture_output=True,
        text=True,
        env=dict(os.environ, PYTHONPATH=str(python_dir)),
    )

    assert result.returncode == 2
    assert "Invalid docs target path: ../secret.md" in result.stderr
    assert "Traceback" not in result.stderr


def test_hv_docs_sqlite_index_path_creates_index(tmp_path: Path):
    hv_bin = _hv_bin_path()
    python_dir = hv_bin.parent.parent
    sqlite_path = tmp_path / "docs.sqlite"

    result = subprocess.run(
        [
            sys.executable,
            str(hv_bin),
            "docs",
            "--sqlite-index-path",
            str(sqlite_path),
            "search",
            "backtest",
            "--limit",
            "2",
        ],
        cwd=python_dir,
        capture_output=True,
        text=True,
        env=dict(os.environ, PYTHONPATH=str(python_dir)),
    )

    assert result.returncode == 0
    payload = json.loads(result.stdout)
    assert payload["backend"] == "sqlite_fts5"
    assert payload["matches"]
    assert sqlite_path.exists()


def test_hv_docs_rejects_sqlite_path_when_disabled(tmp_path: Path):
    hv_bin = _hv_bin_path()
    python_dir = hv_bin.parent.parent
    sqlite_path = tmp_path / "docs.sqlite"

    result = subprocess.run(
        [
            sys.executable,
            str(hv_bin),
            "docs",
            "--no-sqlite-index",
            "--sqlite-index-path",
            str(sqlite_path),
            "search",
            "backtest",
        ],
        cwd=python_dir,
        capture_output=True,
        text=True,
        env=dict(os.environ, PYTHONPATH=str(python_dir)),
    )

    assert result.returncode == 2
    assert "--sqlite-index-path cannot be used with --no-sqlite-index" in result.stderr
    assert "Traceback" not in result.stderr


def test_hv_prompts_list_outputs_json():
    hv_bin = _hv_bin_path()
    python_dir = hv_bin.parent.parent

    result = subprocess.run(
        [sys.executable, str(hv_bin), "prompts", "list"],
        cwd=python_dir,
        capture_output=True,
        text=True,
        env=dict(os.environ, PYTHONPATH=str(python_dir)),
    )

    assert result.returncode == 0
    payload = json.loads(result.stdout)
    assert payload["prompts"]
    assert any(prompt["name"] == "setup_config" for prompt in payload["prompts"])


def test_hv_prompts_read_returns_text():
    hv_bin = _hv_bin_path()
    python_dir = hv_bin.parent.parent

    result = subprocess.run(
        [sys.executable, str(hv_bin), "prompts", "read", "setup_config"],
        cwd=python_dir,
        capture_output=True,
        text=True,
        env=dict(os.environ, PYTHONPATH=str(python_dir)),
    )

    assert result.returncode == 0
    payload = json.loads(result.stdout)
    assert payload["name"] == "setup_config"
    assert "Set up local Hotvect environment" in payload["description"]
    assert "hv-ext config" in payload["text"]


def test_hv_prompts_read_unknown_prompt_exits_cleanly():
    hv_bin = _hv_bin_path()
    python_dir = hv_bin.parent.parent

    result = subprocess.run(
        [sys.executable, str(hv_bin), "prompts", "read", "does_not_exist"],
        cwd=python_dir,
        capture_output=True,
        text=True,
        env=dict(os.environ, PYTHONPATH=str(python_dir)),
    )

    assert result.returncode == 2
    assert "Unknown prompt: does_not_exist" in result.stderr
    assert "Traceback" not in result.stderr


def test_hv_docs_help_mentions_sqlite_flag():
    hv_bin = _hv_bin_path()
    python_dir = hv_bin.parent.parent

    result = subprocess.run(
        [sys.executable, str(hv_bin), "docs", "--help"],
        cwd=python_dir,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 0
    assert "--sqlite-index" in result.stdout
    assert "JSON on stdout" in result.stdout


def test_hv_prompts_help_mentions_json_output():
    hv_bin = _hv_bin_path()
    python_dir = hv_bin.parent.parent

    result = subprocess.run(
        [sys.executable, str(hv_bin), "prompts", "--help"],
        cwd=python_dir,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 0
    assert "JSON on stdout" in result.stdout
    assert "read one bundled prompt template" in result.stdout.lower()


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
    assert "--target" in result.stdout


def test_hv_train_cache_refresh_uses_effective_cache_base_dir(tmp_path: Path):
    hv_bin = _hv_bin_path()
    python_dir = hv_bin.parent.parent

    dummy_jar = tmp_path / "algo.jar"
    dummy_jar.write_text("not a jar, but path existence is enough for CLI validation")
    override_path = tmp_path / "override.json"
    override_path.write_text(
        json.dumps(
            {
                "hotvect_execution_parameters": {
                    "cache_base_dir": str(tmp_path / "cache"),
                    "cache": "run",
                }
            }
        )
    )

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
            "--algorithm-override",
            str(override_path),
            "--cache-refresh",
        ],
        cwd=python_dir,
        capture_output=True,
        text=True,
    )

    assert result.returncode != 2
    assert "requires --cache" not in result.stderr
