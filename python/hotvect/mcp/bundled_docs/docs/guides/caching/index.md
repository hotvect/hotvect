---
title: How to Use Hotvect Caching
description: Speed up backtests and training by reusing state, encoded data, and packaged parameters
tags: [caching, backtest, sagemaker, optimization, parameters]
difficulty: intermediate
estimated_time: 20 minutes
prerequisites:
  - Able to run `hv backtest` locally or on SageMaker
  - S3 access configured (if using `s3://...` cache paths)
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

Full-run cache keys always include `parameter_version`. If you do not explicitly set `parameter_version`, Hotvect generates:

```
last_test_date_YYYY-MM-DD
```

This means a full-run cache created for `--last-test-time 2000-01-07` will not be used as a full-run cache for
`--last-test-time 2000-01-08` because they have different `parameter_version` values.

## Cache layout on disk / S3

If you enable caching via `cache_base_dir`, Hotvect stores artifacts under:

```
<cache_base_dir>/
  <algorithm_key>/
    runs/
      <parameter_version>/
        generate-state/...
        encode/...
        train/...
```

Where:
- `algorithm_key` is controlled by `cache_scope` (see next section).
- `parameter_version` defaults to `last_test_date_YYYY-MM-DD`.

When the encode partition cache is enabled, Hotvect also stores reusable date partitions under the algorithm cache root:

```
<cache_base_dir>/
  <algorithm_key>/
    partitions/
      encode/
        dt=YYYY-MM-DD/
          _STARTED
          encoded/
          encoded-schema-description
          _SUCCESS
```

Those partitions are intentionally not nested under `parameter_version`, so adjacent moving training windows can reuse
overlapping training dates. Hotvect reads a partition cache only after the partition-level `_SUCCESS` marker is valid;
writers publish that marker after the encoded data and schema have been written. The marker is JSON with a format
version, partition date, relative encoded/schema paths, and creation timestamp. Writers also create `_STARTED` before
encoding/publishing a missed partition. `_STARTED` records the writer process details and acts as a partition write
claim; if another run sees a started-but-not-successful partition, it warns, does not reuse it, and does not overwrite
it. The current run re-encodes the partition locally.

## Cache sharing across algorithm versions: `cache_scope`

Caching is keyed by algorithm name plus an “algorithm version key”. You can control how much different algorithm versions share the cache via:

- `cache_scope=hyperparam` (default): do not truncate the algorithm version string; if `hyperparameter_version` is set, append it to the key
- `cache_scope=patch`: share within `X.Y.Z` (if `algorithm_version` is semver-like), otherwise fall back to the full version string
- `cache_scope=minor`: share within `X.Y` (requires a semver-like `algorithm_version`)
- `cache_scope=major`: share within `X` (requires a semver-like `algorithm_version`)

Notes:
- For `major`/`minor`, Hotvect expects `algorithm_version` to be semver-like, for example `1.2.3`, `v1.2.3`, or `1.2.3-SNAPSHOT`.
- Use `--cache-refresh` when experimenting with code changes while keeping the same `algorithm_version` string and using run-level cache.

## Option 1 (recommended): `hv backtest --cache` / `hv train --cache`

The `hv` CLI provides cache flags for both **backtest** and **train**:

- `--cache <local_path_or_s3_uri>` enables caching
- `--cache-scope major|minor|patch|hyperparam` controls cache sharing across algorithm versions
- `--cache-refresh` ignores cache reads and writes fresh run-level cache results (requires an effective `cache_base_dir` and effective cache mode `run`)

Example (SageMaker; recommended):
```bash
hv backtest \
  --git-reference v1.1.0 \
  --algo-repo-url https://github.com/example-org/example-algorithm.git \
  --output-base-dir /tmp/out \
  --scratch-dir /tmp/scratch \
  --last-test-time 2000-01-07 \
  --sagemaker \
  --sagemaker-job-prefix example-cache-backtest \
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
  --last-test-time 2000-01-07 \
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
  --last-test-time 2000-01-07 \
  --cache /tmp/hotvect-cache \
  --cache-scope hyperparam
```

### SageMaker warning: use an `s3://...` cache

For SageMaker runs, set `--cache` to an `s3://...` prefix. A local cache path (e.g. `/tmp/cache`) only exists on the container filesystem and will not persist across jobs.

## Option 2 (advanced): enable caching in the algorithm definition

You can enable caching by setting:

- `hotvect_execution_parameters.cache_base_dir` (local path or `s3://...`)
- optionally `hotvect_execution_parameters.cache` (`true|false|"run"|"partition"`)
- optionally `hotvect_execution_parameters.cache_scope` (`major|minor|patch|hyperparam`)
- optionally `hotvect_execution_parameters.cache_refresh` (boolean)

Example:
```json
{
  "hotvect_execution_parameters": {
    "cache_base_dir": "s3://example-bucket/hotvect-cache/",
    "cache": true,
    "cache_scope": "hyperparam"
  }
}
```

Root-level `cache` is the default policy for cacheable stages:
- omitted: with `cache_base_dir`, use run-level caches and enable encode partition cache for date-windowed training
- `true`: same default policy as omitted
- `false`: disable caching even when `cache_base_dir` is set
- `"run"`: use only run-level caches
- `"partition"`: use only encode partition cache; non-encode stages are not cached unless they override this policy

### Per-step overrides (`generate-state`, `encode`, `train`)

Per-step settings override the root-level `cache` policy.

Each cacheable step supports:
- `cache: false` to disable caching for that step
- `cache: true` to use the default location under `cache_base_dir`
- `cache: "<explicit path>"` to use a custom cache location (S3 or local)

For `encode.cache`, the values are:
- omitted: inherit the root-level `cache` policy
- `true`: use both the run-level encode cache and the encode partition cache
- `"run"`: use only the run-level encode cache
- `"partition"`: use only the encode partition cache
- `false`: disable encode caching

Example:
```json
{
  "hotvect_execution_parameters": {
    "cache_base_dir": "s3://example-bucket/hotvect-cache/",
    "cache": "run",
    "cache_scope": "hyperparam",

    "generate-state": {"cache": true},
    "encode": {"cache": "partition"},
    "train": {"cache": true}
  }
}
```

Partition mode applies to date-windowed training definitions with `number_of_training_days`; the training data root is
`train_data_spec.data_prefix` when set, otherwise `train/`. It keeps training and later stages unchanged. Hotvect reuses
or creates per-`dt` encoded partitions under `partitions/encode/dt=YYYY-MM-DD/` and assembles the normal `encoded/`
directory from those partitions. In SageMaker, when cached partitions already exist in S3, Hotvect mounts each partition
cache root `<cache_base>/<algorithm_key>/partitions/` as a FastFile input channel, including the common case where
partition-cache encoders are dependencies of a parent algorithm. Encode then reads from the mounted
`encode/dt=YYYY-MM-DD/` path that belongs to the current algorithm cache root. It writes newly missed date partitions
back to the partition cache after claiming the partition with `_STARTED`, with the JSON `_SUCCESS` marker written last
so partial partition writes are not reused. If a partition path already contains incomplete content or an invalid marker,
Hotvect treats it as dirty, skips publishing to that partition cache path, and continues with the locally encoded
partition for the current run. Partition identity is the algorithm cache root and partition `dt`; run-level parameters
and debug metadata remain in the normal `algorithm-parameters.json` package. When only partition mode is active
(`encode.cache="partition"`), Hotvect does not write an assembled full-window encode cache for every moving window.

The `"run"` and `"partition"` values are reserved encode cache modes. Other string values keep the existing
explicit-path cache behavior.

When both encode caches are enabled, either by setting `cache_base_dir`, root `cache=true`, or `encode.cache=true`,
Hotvect first checks the run-level encode cache under `runs/<parameter_version>/encode/`. If that exact-window cache is
missing and the training definition is partitionable, it uses the encode partition cache and writes the assembled
full-window encode output back to the run-level cache.

For composite algorithms, the child algorithm that owns the encode/train stages uses the inherited cache settings. If
the top-level algorithm only wraps child models, top-level `hotvect_execution_parameters.cache_base_dir` and
`hotvect_execution_parameters.cache` are inherited by child pipelines. A top-level
`hotvect_execution_parameters.encode.cache` is also inherited by child pipelines. Put an override under a child
dependency when that child needs a different encode cache mode:

```json
{
  "hotvect_execution_parameters": {
    "cache_base_dir": "s3://example-bucket/hotvect-cache/",
    "cache": "partition"
  },
  "dependencies": {
    "my-algorithm-child-model": {
      "hotvect_execution_parameters": {
        "encode": {"cache": "run"}
      }
    }
  }
}
```

### Path templates for explicit overrides

If you use `cache: "<explicit path>"`, you can use the following template variables:
- `{{ hyperparameter_slug }}` (e.g. `example-parent-algorithm@1.2.3-2day`)
- `{{ parameter_version }}` (e.g. `last_test_date_2000-01-07`)

Example:
```json
{
  "hotvect_execution_parameters": {
    "train": {
      "cache": "s3://example-bucket/hotvect-cache/{{ hyperparameter_slug }}/runs/{{ parameter_version }}/train/predict-parameters.zip"
    }
  }
}
```

## Forcing recompute: `cache_refresh`

To force recompute while still writing results back to the cache:
- Backtest: add `--cache-refresh` with an effective `cache_base_dir` and cache mode `run`
- Algorithm definition: set `hotvect_execution_parameters.cache_base_dir`, `hotvect_execution_parameters.cache="run"`, and `hotvect_execution_parameters.cache_refresh=true`

`cache_refresh` is run-cache-only. It ignores run-level cache reads for cacheable steps and writes fresh run-level cache artifacts. It is intentionally not supported with encode partition cache.

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
