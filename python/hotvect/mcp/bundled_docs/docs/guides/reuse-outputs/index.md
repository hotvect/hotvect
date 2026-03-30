---
title: How to Reuse Existing Outputs
description: Reuse trained model parameters and other pipeline outputs for faster iteration and debugging
tags: [parameters, caching, training, pipeline, optimization]
difficulty: intermediate
estimated_time: 15 minutes
prerequisites:
  - Understanding of hotvect training pipeline
  - S3 access configured (if using S3 URIs)
related_docs:
  - ../caching/index.md
  - ../develop-algorithms/index.md
  - ../../reference/cli/index.md
related_commands:
  - hv train
  - hv predict
  - hv backtest
next_steps:
  - Run evaluation on different test datasets
  - Compare cached vs regenerated outputs
  - Set up automated caching strategy
---

# How to: Reuse existing outputs (parameters, encoded data, state)

Hotvect supports two related mechanisms:

1. **Pin an exact parameter zip** with `hotvect_execution_parameters.with_parameter` (strict: must exist).
2. **Enable caching** via `cache_base_dir` / `--cache` (best-effort: use if present, otherwise recompute and write).

## Option A (strict): Reuse an exact `predict-parameters.zip` via `with_parameter`

Use this when you want a run to use *exactly* the same model parameters as a previous run (e.g. offline/online debugging).

Example override (recommended on a dependency):
```json
{
  "dependencies": {
    "my-model": {
      "hotvect_execution_parameters": {
        "with_parameter": "s3://example-bucket/hotvect-cache/my-model@1.2.3/last_test_date_2024-06-01/train/predict-parameters.zip"
      }
    }
  }
}
```

Notes:
- `with_parameter` accepts `s3://example-bucket` or a local file path.
- If the zip does not exist, the pipeline raises an error.
- When `with_parameter` is set, Hotvect skips all upstream steps for that algorithm (generate-state / encode / train).

## Option B (best-effort): Reuse outputs via caching in the algorithm definition

Hotvect caches a few expensive artifacts (state generation output, encoded data, and packaged model parameters). You can enable caching by setting:

- `hotvect_execution_parameters.cache_base_dir` (local path or `s3://example-bucket`)
- optionally `hotvect_execution_parameters.cache_scope` (`major|minor|patch|hyperparam`, default: `hyperparam`)
- optionally per-step overrides under `generate-state|encode|train`:
  - `cache: false` disables caching for that step
  - `cache: true` uses the default location under `cache_base_dir`
  - `cache: "<explicit path>"` uses a custom location (S3 or local)

Example:
```json
{
  "hotvect_execution_parameters": {
    "cache_base_dir": "s3://example-bucket/hotvect-cache/",
    "cache_scope": "hyperparam",

    "generate-state": {"cache": true},
    "encode": {"cache": true},
    "train": {"cache": true},

    "performance-test": {"enabled": false}
  }
}
```

## Option C (backtest-only): `hv backtest --cache`

If you are iterating via `hv backtest`, the simplest way to reuse outputs is the CLI:

- `--cache <local_path_or_s3_uri>` enables caching
- `--cache-scope major|minor|patch|hyperparam` controls cache sharing across **algorithm versions**
- `--cache-refresh` ignores cache reads (forces recompute) while still writing to cache

Example (SageMaker):
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

**Important:** for SageMaker runs, set `--cache` to an `s3://example-bucket` prefix. Local paths only exist on the container filesystem and will not persist across jobs.

## What outputs can be reused / cached?

When caching is enabled, Hotvect may reuse:

- `generate-state`: state output (directory or file, depending on the algorithm definition)
- `generate-state/encoding-parameters.zip`: packaged encode parameters used by `encode`
- `encode`: encoded data directory + schema description directory
- `train/predict-parameters.zip`: packaged model parameters (what `with_parameter` points at)

By design, `predict`, `evaluate`, and `performance-test` are not cached (they consume the parameter zip).

## Key concept: caches are segmented by `parameter_version`

Cache keys always include `parameter_version`. If you do not explicitly set `parameter_version`, Hotvect defaults it to:

```
last_test_date_YYYY-MM-DD
```

So a cache built for one `--last-test-time` does not automatically apply to a different `--last-test-time`.

For a deeper explanation (including cache layout), see [How to Use Hotvect Caching](../caching/index.md).
