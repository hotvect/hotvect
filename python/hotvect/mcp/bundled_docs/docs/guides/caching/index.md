---
title: How to Use Hotvect Caching
description: Speed up backtests and training by reusing state, encoded data, and packaged parameters
tags: [caching, backtest, sagemaker, optimization, parameters]
difficulty: intermediate
estimated_time: 20 minutes
prerequisites:
  - Able to run `hv backtest` locally or on SageMaker
  - S3 access configured (if using `s3://example-bucket` cache paths)
related_docs:
  - ../reuse-outputs/index.md
  - ../sagemaker-backtests/index.md
  - ../../reference/cli/index.md
---

# How to: Use Hotvect caching

Hotvect can reuse a small set of expensive pipeline outputs between runs. This is most useful when you repeatedly run backtests (locally or on SageMaker) against the same algorithm version(s) and the same `--last-test-time`.

Caching is **best-effort**:
- If a cache artifact exists, Hotvect uses it and skips the corresponding step.
- If it does not exist, Hotvect computes the step and writes the artifact to the cache for future runs.

If you need strict pinning of *exact* model parameters, use `hotvect_execution_parameters.with_parameter` instead (see [Reuse existing outputs](../reuse-outputs/index.md)).

## What is cached (and what is not)

When caching is enabled, Hotvect may reuse:

- `generate-state`: state output (directory or file, depending on the algorithm definition)
- `generate-state/encoding-parameters.zip`: packaged encode parameters used by `encode`
- `encode`: encoded data directory + schema description directory
- `train/predict-parameters.zip`: packaged model parameters (what `with_parameter` points at)

By design, `predict`, `evaluate`, and `performance-test` are not cached.

## Disabling stages (manual control)

Independent of caching, you can disable certain stages via `hotvect_execution_parameters`.

Example:
```json
{
  "hotvect_execution_parameters": {
    "evaluate": {"enabled": false},
    "predict": {"enabled": false},
    "performance-test": {"enabled": false}
  }
}
```

Notes:
- Evaluation requires prediction. If `evaluate.enabled=true`, Hotvect will run `predict` even if `predict.enabled=false`.
- Disabling `performance-test` is a common speed-up during iteration.

## Key concept: caches are segmented by `parameter_version`

Cache keys always include `parameter_version`. If you do not explicitly set `parameter_version`, Hotvect generates:

```
last_test_date_YYYY-MM-DD
```

This means a cache created for `--last-test-time 2026-01-07` will not be used for `--last-test-time 2026-01-08` because they have different `parameter_version` values.

## Cache layout on disk / S3

If you enable caching via `cache_base_dir`, Hotvect stores artifacts under:

```
<cache_base_dir>/
  <algorithm_key>/
    <parameter_version>/
      generate-state/...
      encode/...
      train/...
```

Where:
- `algorithm_key` is controlled by `cache_scope` (see next section).
- `parameter_version` defaults to `last_test_date_YYYY-MM-DD`.

## Cache sharing across algorithm versions: `cache_scope`

Caching is keyed by algorithm name plus an “algorithm version key”. You can control how much different algorithm versions share the cache via:

- `cache_scope=hyperparam` (default): do not truncate the algorithm version string; if `hyperparameter_version` is set, append it to the key
- `cache_scope=patch`: share within `X.Y.Z` (if a semver-like substring exists), otherwise fall back to the full version string
- `cache_scope=minor`: share within `X.Y` (requires a semver-like `X.Y.Z` substring)
- `cache_scope=major`: share within `X` (requires a semver-like `X.Y.Z` substring)

Notes:
- For `major`/`minor`, Hotvect looks for a semver-like substring (`X.Y.Z`) inside `algorithm_version`. If it cannot find one, it raises an error.
- Use `--cache-refresh` (or `cache_refresh=true`) when experimenting with code changes while keeping the same `algorithm_version` string.

## Option 1 (recommended): `hv backtest --cache` / `hv train --cache`

The `hv` CLI provides cache flags for both **backtest** and **train**:

- `--cache <local_path_or_s3_uri>` enables caching
- `--cache-scope major|minor|patch|hyperparam` controls cache sharing across algorithm versions
- `--cache-refresh` ignores cache reads (forces recompute) while still writing fresh results to the cache (requires `--cache`)

Example (SageMaker; recommended):
```bash
hv backtest \
  --git-reference v81.0.6 \
  --algo-repo-url https://github.com/example-org/example-algorithm.git \
  --output-base-dir /tmp/out \
  --scratch-dir /tmp/scratch \
  --last-test-time 2026-01-07 \
  --sagemaker-config sagemaker-config.json \
  --auto-attach-data-default-s3-base s3://example-bucket/tables/ \
  --cache s3://example-bucket/hotvect-cache/ \
  --cache-scope hyperparam
```

Example (local execution; local cache path):
```bash
hv backtest \
  --git-reference main \
  --algo-repo-url https://github.com/example-org/example-algorithm.git \
  --data-base-dir /data \
  --output-base-dir /tmp/out \
  --scratch-dir /tmp/scratch \
  --last-test-time 2026-01-07 \
  --cache /tmp/hotvect-cache \
  --cache-scope hyperparam
```

Example (local `hv train`; local cache path):
```bash
hv train \
  --algorithm-name my-algorithm \
  --algorithm-jar /path/to/my-algorithm.jar \
  --data-base-dir /data \
  --output-base-dir /tmp/out \
  --last-test-time 2026-01-07 \
  --cache /tmp/hotvect-cache \
  --cache-scope hyperparam
```

### SageMaker warning: use an `s3://example-bucket` cache

For SageMaker runs, set `--cache` to an `s3://example-bucket` prefix. A local cache path (e.g. `/tmp/cache`) only exists on the container filesystem and will not persist across jobs.

## Option 2 (advanced): enable caching in the algorithm definition

You can enable caching by setting:

- `hotvect_execution_parameters.cache_base_dir` (local path or `s3://example-bucket`)
- optionally `hotvect_execution_parameters.cache_scope` (`major|minor|patch|hyperparam`)
- optionally `hotvect_execution_parameters.cache_refresh` (boolean)

Example:
```json
{
  "hotvect_execution_parameters": {
    "cache_base_dir": "s3://example-bucket/hotvect-cache/",
    "cache_scope": "hyperparam",
    "cache_refresh": false
  }
}
```

### Per-step overrides (`generate-state`, `encode`, `train`)

Each cacheable step supports:
- `cache: false` to disable caching for that step
- `cache: true` to use the default location under `cache_base_dir`
- `cache: "<explicit path>"` to use a custom cache location (S3 or local)

Example:
```json
{
  "hotvect_execution_parameters": {
    "cache_base_dir": "s3://example-bucket/hotvect-cache/",
    "cache_scope": "hyperparam",

    "generate-state": {"cache": true},
    "encode": {"cache": true},
    "train": {"cache": true}
  }
}
```

### Path templates for explicit overrides

If you use `cache: "<explicit path>"`, you can use the following template variables:
- `{{ hyperparameter_slug }}` (e.g. `example-parent-algorithm@1.2.3-2day`)
- `{{ parameter_version }}` (e.g. `last_test_date_2026-01-07`)

Example:
```json
{
  "hotvect_execution_parameters": {
    "train": {
      "cache": "s3://example-bucket/hotvect-cache/{{ hyperparameter_slug }}/{{ parameter_version }}/train/predict-parameters.zip"
    }
  }
}
```

## Forcing recompute: `cache_refresh`

To force recompute while still writing results back to the cache:
- Backtest: add `--cache-refresh`
- Algorithm definition: set `hotvect_execution_parameters.cache_refresh=true`

`cache_refresh` ignores cache reads for cacheable steps.

Important: `with_parameter` is still strict and still requires that the specified zip exists; `cache_refresh` does not override that behavior.

## “Clearing” cache

There is no `hv` command to clear caches. To clear:
- Local: delete the directory under `cache_base_dir`
- S3: delete the prefix under `cache_base_dir` (be careful; caches may be shared across runs by design)

## How to tell whether cache was used

Look for log lines like:
- `Skipping encoding ... because cache was available at ...`
- `Skipping training ... because predict parameter cache is available at ...`
- `Skipping state generation ... using S3 cache at ...`

For `train/predict-parameters.zip` cache hits, you commonly see:
- `Predict parameters available ... skipping encode and train`
- `Using cached predict parameters ...`
