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
  - hv algorithm train
  - hv algorithm predict
  - hv algorithm backtest
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
        "with_parameter": "s3://example-bucket/hotvect-cache/my-model@1.2.3/runs/last_test_date_2000-06-01/train/predict-parameters.zip"
      }
    }
  }
}
```

Notes:
- `with_parameter` accepts `s3://...` or a local file path.
- If the zip does not exist, the pipeline raises an error.
- When `with_parameter` is set, Hotvect skips all upstream steps for that algorithm (generate-state / encode / train).

## Option B (best-effort): Reuse outputs via caching in the algorithm definition

Hotvect caches a few expensive artifacts (state generation output, encoded data, and packaged model parameters). You can enable caching by setting:

- `hotvect_execution_parameters.cache_base_dir` (local path or `s3://...`)
- optionally `hotvect_execution_parameters.cache` (`true|false|"run"|"partition"`)
- optionally `hotvect_execution_parameters.cache_scope` (`major|minor|patch|hyperparam`, default: `hyperparam`)
- root-level `cache` is the default cache policy:
  - omitted or `true`: use run-level caches
  - `false`: disable caching even when `cache_base_dir` is set
  - `"run"`: use only run-level caches
  - `"partition"`: use only encode partition cache; non-encode stages are not cached unless they override it
- optionally per-step overrides under `generate-state|encode|train`:
  - `cache: false` disables caching for that step
  - `cache: true` uses the default location under `cache_base_dir`
  - `cache: "<explicit path>"` uses a custom location (S3 or local)
  - omitted `encode.cache` inherits the root-level `cache` policy
  - `encode.cache=true` uses the run-level encode cache
  - `encode.cache="run"` or `encode.cache="partition"` selects only one encode cache mode

Example:
```json
{
  "hotvect_execution_parameters": {
    "cache_base_dir": "s3://example-bucket/hotvect-cache/",
    "cache": "run",
    "cache_scope": "hyperparam",

    "generate-state": {"cache": true},
    "encode": {"cache": "partition"},
    "train": {"cache": true},

    "performance-test": {"enabled": false}
  }
}
```

## Option C (backtest-only): `hv algorithm backtest --cache`

If you are iterating via `hv algorithm backtest`, the simplest way to reuse outputs is the CLI:

- `--cache <local_path_or_s3_uri>` enables caching
- `--cache-scope major|minor|patch|hyperparam` controls cache sharing across **algorithm versions**
- `--cache-refresh` ignores cache reads and writes fresh run-level cache artifacts; requires an effective `cache_base_dir` and effective cache mode `run`
- `--prewarm` pre-populates the encode partition cache in remote SageMaker mode before normal backtest jobs are submitted
- `--prewarm-instance-count <n>` makes one-instance prewarm jobs available per git reference

Example (SageMaker):
```bash
hv algorithm backtest \
  --git-reference v1.1.0 \
  --algo-repo-url https://github.com/example-org/example-algorithm.git \
  --output-base-dir /tmp/out \
  --scratch-dir /tmp/scratch \
  --last-test-time 2000-01-07 \
  --sagemaker-config sagemaker-config.json \
  --auto-attach-data-default-s3-base s3://example-bucket/tables/ \
  --cache s3://example-bucket/hotvect-cache/ \
  --cache-scope hyperparam
```

For partition-heavy remote SageMaker backtests, add `--prewarm` and optionally
`--prewarm-instance-type`. Hotvect assigns each required partition to the newest requested backtest window whose encode
parameters apply. In automatic mode, it selects the minimum required compatible encoding-parameter contexts and
submits every planned one-instance `encode-cache` SageMaker job together before waiting and then submitting the normal
backtest jobs. `--prewarm-instance-count <n>` must be at least that minimum or Hotvect fails before submission; a
larger count may use additional compatible contexts. Prewarm jobs recurse into dependencies. Existing completed
partition caches are reused automatically on rerun. It requires an `s3://` cache path.
Each prewarm invocation creates fresh SageMaker job attempts; `_SUCCESS` markers, rather than SageMaker job names,
are the durable record of completed cache partitions.
`--prewarm-instance-type` requires `--prewarm`; when set, it replaces any configured preferred-instance list, so
prewarm uses that type only.

If a partition cache prefix contains partial data or a `_STARTED` marker without a `_SUCCESS` marker, Hotvect
treats that partition as incomplete and will not overwrite it. Prewarm reports the blocked partition and stops before
submitting normal backtest jobs; clear or repair the partition cache prefix before retrying. A normal backtest without
prewarm can still encode an incomplete partition locally for that run without publishing it.

Partition reuse intentionally accepts an encode parameter set that was valid for a relevant backtest window, even when
another requested window would have produced different parameters.

**Important:** for SageMaker runs, set `--cache` to an `s3://...` prefix. Local paths only exist on the container filesystem and will not persist across jobs.

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

The exception is the encode partition cache: root `cache="partition"` or `encode.cache="partition"` enables reusable
per-date encoded partitions outside the `parameter_version` run directory so adjacent moving training windows can reuse
the overlapping dates while still training normally. Backtest prewarm selects this encode mode automatically. A
partition is reused only when its `_SUCCESS` marker exists.

For a deeper explanation (including cache layout), see [How to Use Hotvect Caching](../caching/index.md).
