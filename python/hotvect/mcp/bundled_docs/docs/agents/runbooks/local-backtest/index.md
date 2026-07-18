---
title: Runbook - Local backtest
description: Copy/paste runbook for `hv backtest` on a real algorithm repo (local execution)
tags: [agents, runbook, backtest, local]
---

# Runbook: Local backtest (`hv backtest`)

Run a local comparison from a fixed git reference and produce an inspectable `result.json`.

## Agent contract

| Inputs | Command | Artifacts | Verify |
| --- | --- | --- | --- |
| Algorithm repository, git reference, local data, output directory, scratch directory, test date | `hv backtest` | `meta/<algorithm_id>/<parameter_version>/result.json`, `hv.log`, and `hv.all.log` | Exit code `0` and a result for every requested reference/date |

Use this for an offline backtest. Do not use it to prove production latency: use the [performance benchmarking guide](../../../guides/performance-benchmarking/index.md) with a fixed performance contract.

## Inputs (fill these in)

- `ALGO_REPO_URL`: git URL or local path to algorithm repo
- `GIT_REF`: branch/tag/commit
- `LAST_TEST_TIME`: `YYYY-MM-DD`
- `DATA_BASE_DIR`: contains `<train_prefix>/dt=...` and `<test_prefix>/dt=...`
- `OUTPUT_BASE_DIR`: dedicated destination for this run
- `SCRATCH_DIR`: dedicated temporary build directory
- `OVERRIDE_JSON`: optional override file path

## Command template

```bash
hv backtest \
  --git-reference "$GIT_REF" \
  --algo-repo-url "$ALGO_REPO_URL" \
  --data-base-dir "$DATA_BASE_DIR" \
  --output-base-dir "$OUTPUT_BASE_DIR" \
  --scratch-dir "$SCRATCH_DIR" \
  --last-test-time "$LAST_TEST_TIME" \
  --number-of-runs 1 \
  --no-performance-test
```

## Optional: add a deterministic override

Only add this flag when an override file is actually present. It is a patch over the algorithm definition, not a
second definition.

```bash
hv backtest ... --algorithm-override "$OVERRIDE_JSON"
```

## Optional: enable caching (local path)

```bash
hv backtest \
  ... \
  --cache /tmp/hotvect-cache \
  --cache-scope hyperparam
```

To force recompute while still writing run-level cache artifacts, use an algorithm definition/override with
`hotvect_execution_parameters.cache="run"`:

```bash
hv backtest ... --cache /tmp/hotvect-cache --cache-refresh
```

## Validate success

Look for:

- Exit code `0`
- `$OUTPUT_BASE_DIR/meta/.../result.json`
- `hv.log` and `hv.all.log` under `$OUTPUT_BASE_DIR/meta/...`

`hv backtest` exits nonzero when any local run or SageMaker submission fails. Treat a nonzero exit as an incomplete
comparison; inspect the per-reference logs before retrying.

## Validate cache hits

On a cache-hit run, look for log lines like:

- `Predict parameters available ... skipping encode and train`
- `Using cached predict parameters ...`

If your algorithm has dependencies, confirm you see cache hits for both the parent and the dependency pipelines
in `hv.all.log`.
