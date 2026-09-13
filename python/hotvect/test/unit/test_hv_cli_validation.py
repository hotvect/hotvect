import json
import os
import re
import subprocess
import sys
from pathlib import Path

import pytest


def _hv_bin_path() -> Path:
    python_dir = Path(__file__).resolve().parents[3]
    return python_dir / "bin" / "hv"


def _run_hv(*args: str, input_text: str | None = None) -> subprocess.CompletedProcess[str]:
    hv_bin = _hv_bin_path()
    python_dir = hv_bin.parent.parent
    return subprocess.run(
        [sys.executable, str(hv_bin), *args],
        cwd=python_dir,
        capture_output=True,
        text=True,
        env=dict(os.environ, PYTHONPATH=str(python_dir)),
        input=input_text,
    )


def _run_python_bin(name: str, *args: str) -> subprocess.CompletedProcess[str]:
    hv_bin = _hv_bin_path()
    python_dir = hv_bin.parent.parent
    return subprocess.run(
        [sys.executable, str(python_dir / "bin" / name), *args],
        cwd=python_dir,
        capture_output=True,
        text=True,
        env=dict(os.environ, PYTHONPATH=str(python_dir)),
    )


def _run_backtest_cli(*extra_args: str) -> subprocess.CompletedProcess[str]:
    return _run_hv(
        "algorithm",
        "backtest",
        "--git-reference",
        "main",
        "--algo-repo-url",
        "https://example.invalid/repo.git",
        "--data-base-dir",
        "/tmp/data",
        "--output-base-dir",
        "/tmp/out",
        "--scratch-dir",
        "/tmp/scratch",
        "--last-test-time",
        "2000-03-27",
        *extra_args,
    )


def test_hv_help_exposes_canonical_namespaces_and_legacy_algorithm_aliases():
    result = _run_hv("--help")

    assert result.returncode == 0
    assert "algorithm" in result.stdout
    assert "qa" in result.stdout
    assert "exp" in result.stdout
    assert "ems" not in result.stdout
    assert "config" in result.stdout
    assert "data" in result.stdout
    assert "metrics" in result.stdout
    assert "Deprecated alias for `hv algorithm audit`" in result.stdout


def test_hv_version_is_available_only_at_the_root():
    root_version = _run_hv("--version")

    assert root_version.returncode == 0
    assert "hotvect" in root_version.stderr

    for namespace in ("algorithm", "qa", "exp", "config", "worker", "docs", "metrics", "results", "data", "audit"):
        result = _run_hv(namespace, "--version")
        assert result.returncode == 2


def test_hv_algorithm_audit_and_legacy_audit_expose_the_same_options():
    canonical = _run_hv("algorithm", "audit", "--help")
    legacy = _run_hv("audit", "--help")

    assert canonical.returncode == 0
    assert legacy.returncode == 0
    assert "--algorithm-name" in canonical.stdout
    assert "--algorithm-name" in legacy.stdout
    assert "Deprecated alias for `hv algorithm audit`" in legacy.stdout


def test_hv_qa_uses_candidate_namespace_and_keeps_criteria_evaluation_internal():
    root_help = _run_hv("qa", "--help")
    start_help = _run_hv("qa", "candidate", "start", "--help")
    listed = _run_hv("qa", "criteria", "list")
    raw_evaluate = _run_hv("qa", "criteria", "evaluate", "noninferiority")

    assert root_help.returncode == 0
    assert "candidate" in root_help.stdout
    assert "utils" in root_help.stdout
    assert "hv qa candidate start" in root_help.stdout
    assert "\n    start" not in root_help.stdout
    assert start_help.returncode == 0
    assert "--prod-default-of-slot-as-control" in start_help.stdout
    assert listed.returncode == 0
    assert json.loads(listed.stdout)["criteria"] == ["exact", "noninferiority", "superiority"]
    assert raw_evaluate.returncode == 2
    assert "invalid choice" in raw_evaluate.stderr


def test_hv_exp_and_config_namespaces_have_direct_help():
    exp_help = _run_hv("exp", "--help")
    exp_slot_help = _run_hv("exp", "slot", "--help")
    config_help = _run_hv("config", "--help")

    assert exp_help.returncode == 0
    assert "Slot operations" in exp_help.stdout
    assert exp_slot_help.returncode == 0
    assert "list" in exp_slot_help.stdout
    assert config_help.returncode == 0
    assert "hv config" in config_help.stdout


def test_hv_exposes_structured_utility_namespaces():
    metrics_help = _run_hv("metrics", "--help")
    results_help = _run_hv("results", "--help")
    data_help = _run_hv("data", "--help")
    dependency_inspect_help = _run_hv("data", "dependencies", "inspect", "--help")
    dependency_download_help = _run_hv("data", "dependencies", "download", "--help")

    assert metrics_help.returncode == 0
    assert "compare-quality" in metrics_help.stdout
    assert results_help.returncode == 0
    assert "download" in results_help.stdout
    assert data_help.returncode == 0
    assert "dependencies" in data_help.stdout
    assert "sagemaker-inputs" not in data_help.stdout
    assert dependency_inspect_help.returncode == 0
    assert "--remote" in dependency_inspect_help.stdout
    assert "--format" in dependency_inspect_help.stdout
    assert dependency_download_help.returncode == 0
    assert "--local-dir" in dependency_download_help.stdout
    assert "--all" in dependency_download_help.stdout
    assert "--name" in dependency_download_help.stdout


def test_hv_does_not_promote_low_level_catboost_conversion():
    result = _run_hv("catboost-convert", "--help")

    assert result.returncode == 2
    assert "invalid choice" in result.stderr


def test_hv_does_not_promote_generic_jsonl_comparison():
    result = _run_hv("compare-jsonl", "--help")

    assert result.returncode == 2
    assert "invalid choice" in result.stderr


def test_hv_does_not_promote_prediction_equivalence_comparison():
    result = _run_hv("compare-equivalence", "--help")

    assert result.returncode == 2
    assert "invalid choice" in result.stderr


def test_hv_qa_exposes_prediction_equivalence_comparison():
    result = _run_hv("qa", "utils", "compare-predictions", "--help")

    assert result.returncode == 0
    assert "--score-eps" in result.stdout
    assert "--allow-non-deterministic-tie-breaking" in result.stdout


def test_legacy_entrypoints_remain_available_and_warn_about_the_canonical_cli():
    qa_help = _run_python_bin("hv-qa", "--help")
    qa_criteria = _run_python_bin("hv-qa", "criteria", "list")
    ext_help = _run_python_bin("hv-ext", "--help")

    assert qa_help.returncode == 0
    assert "start" in qa_help.stdout
    assert "Deprecated compatibility CLI" in qa_help.stdout
    assert "'hv-qa' is deprecated; use 'hv qa'" not in qa_help.stderr
    assert qa_criteria.returncode == 0
    assert "'hv-qa' is deprecated; use 'hv qa'" in qa_criteria.stderr
    assert ext_help.returncode == 0
    assert "data-dependency" in ext_help.stdout
    assert "compare-equivalence" not in ext_help.stdout

    ext_metrics = _run_python_bin("hv-ext", "metrics")
    assert ext_metrics.returncode != 0
    assert "'hv-ext metrics' is deprecated; use 'hv metrics'" in ext_metrics.stderr


def test_legacy_algorithm_alias_warns_on_invocation():
    result = _run_hv("evaluate", "--help")

    assert result.returncode == 0
    assert "'hv evaluate' is deprecated; use 'hv algorithm evaluate'" in result.stderr


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


def test_hv_evaluate_rejects_removed_online_offline_analysis_flag():
    result = _run_hv("algorithm", "evaluate", "--enable-online-offline-analysis")

    assert result.returncode == 2
    assert "unrecognized arguments: --enable-online-offline-analysis" in result.stderr


def test_hv_backtest_help_mentions_prewarm_flags():
    result = _run_hv("algorithm", "backtest", "--help")

    assert result.returncode == 0
    assert re.search(r"^\s*--prewarm(?:\s|$)", result.stdout, flags=re.MULTILINE)
    assert "--prewarm-instance-count" in result.stdout
    assert "--prewarm-instance-type" in result.stdout


def test_hv_encode_help_mentions_source_dest_mappings():
    result = _run_hv("algorithm", "encode", "--help")

    assert result.returncode == 0
    assert "--source-dest-mappings" in result.stdout


@pytest.mark.parametrize(
    ("conflicting_option", "value"),
    [("--source-path", "input"), ("--dest-path", "output")],
)
def test_hv_encode_rejects_source_dest_mappings_with_source_or_dest(conflicting_option: str, value: str):
    result = _run_hv(
        "algorithm",
        "encode",
        "--algorithm-jar",
        "algorithm.jar",
        "--algorithm-name",
        "ranker",
        "--source-dest-mappings",
        "source-dest-mappings.json",
        conflicting_option,
        value,
    )

    assert result.returncode != 0
    assert "--source-dest-mappings cannot be combined with --source-path or --dest-path" in result.stderr


@pytest.mark.parametrize(
    ("extra_args", "error"),
    [
        (("--prewarm",), "--prewarm requires SageMaker execution"),
        (("--sagemaker", "--prewarm"), "--prewarm requires --cache"),
        (
            ("--sagemaker", "--cache", "s3://example-bucket/cache", "--cache-refresh", "--prewarm"),
            "--prewarm is not supported with --cache-refresh",
        ),
        (("--sagemaker", "--cache", "/tmp/cache", "--prewarm"), "--prewarm requires an s3:// --cache path"),
        (("--prewarm-instance-count", "0"), "--prewarm-instance-count must be a positive integer"),
        (("--prewarm-instance-count", "2"), "--prewarm-instance-count requires --prewarm"),
        (("--prewarm-instance-type", "ml.m7i.4xlarge"), "--prewarm-instance-type requires --prewarm"),
    ],
)
def test_hv_backtest_validates_prewarm_options(extra_args: tuple[str, ...], error: str):
    result = _run_backtest_cli(*extra_args)

    assert result.returncode != 0
    assert error in result.stderr


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


def test_hv_does_not_expose_prompt_catalog():
    hv_bin = _hv_bin_path()
    python_dir = hv_bin.parent.parent

    result = subprocess.run(
        [sys.executable, str(hv_bin), "prompts", "--help"],
        cwd=python_dir,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 2
    assert "invalid choice" in result.stderr


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
