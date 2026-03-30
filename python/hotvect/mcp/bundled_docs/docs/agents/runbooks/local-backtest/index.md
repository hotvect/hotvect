---
title: Runbook - Local backtest
description: Copy/paste runbook for `hv backtest` on a real algorithm repo (local execution)
tags: [agents, runbook, backtest, local]
---

# Runbook: Local backtest (`hv backtest`)

Goal: run `hv backtest` locally with deterministic overrides and (optionally) caching.

## Inputs (fill these in)

- `ALGO_REPO_URL`: git URL or local path to algorithm repo
- `GIT_REF`: branch/tag/commit
- `LAST_TEST_TIME`: `YYYY-MM-DD`
- `DATA_BASE_DIR`: contains `<train_prefix>/dt=...` and `<test_prefix>/dt=...`
- `OUTPUT_BASE_DIR`: destination (safe to delete)
- `SCRATCH_DIR`: temp build dir (safe to delete)
- `OVERRIDE_JSON`: override file path

## Command template

```bash
hv backtest \
  --git-reference "$GIT_REF" \
  --algo-repo-url "$ALGO_REPO_URL" \
  --data-base-dir "$DATA_BASE_DIR" \
  --output-base-dir "$OUTPUT_BASE_DIR" \
  --scratch-dir "$SCRATCH_DIR" \
  --last-test-time "$LAST_TEST_TIME" \
  --algorithm-override "$OVERRIDE_JSON" \
  --number-of-runs 1 \
  --no-performance-test
```

## Optional: enable caching (local path)

```bash
hv backtest \
  ... \
  --cache /tmp/hotvect-cache \
  --cache-scope hyperparam
```

To force recompute while still writing cache artifacts:

```bash
hv backtest ... --cache /tmp/hotvect-cache --cache-refresh
```

## Validate success

Look for:

- Exit code `0`
- `output_base_dir/meta/.../result.json`
- `hv.log` / `hv.all.log` under `output_base_dir/meta/...`

## Validate cache hits

On a cache-hit run, look for log lines like:

- `Predict parameters available ... skipping encode and train`
- `Using cached predict parameters ...`

If your algorithm has dependencies, confirm you see cache hits for both the parent and the dependency pipelines
in `hv.all.log`.
