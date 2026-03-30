---
title: Contracts (Agent-First)
description: Deterministic contracts for overrides, caching, outputs, and SageMaker execution
tags: [agents, contracts, caching, overrides, sagemaker]
---

# Contracts (Agent-First)

This page is the “ground truth” for behaviors agents should rely on when writing code, tests, or automation around
Hotvect workflows.

## Override merge semantics

- An **override JSON** is a **recursive merge overlay** onto the algorithm definition loaded from the JAR (or git ref).
- **Missing keys are not deletions**. If you need to disable something, set an explicit `enabled: false` (or equivalent)
  at the right location.
- **Dependency overrides must be nested**:

```json
{
  "dependencies": {
    "dependency-algorithm-name": {
      "number_of_training_days": 1,
      "train_data_prefix": "local_small_train",
      "hotvect_execution_parameters": {
        "performance-test": { "enabled": false }
      }
    }
  }
}
```

See: `guides/patterns/override-files/index.md`, `guides/patterns/parent-child/index.md`.

## Caching contracts

### What caching can skip

When caching is enabled (via CLI flags or algorithm definition), Hotvect may reuse:

- `generate-state`
- `encode`
- `train` (predict-parameters packaging)

By design, Hotvect does **not** cache `predict`, `evaluate`, or `performance-test`.

### Cache key contract

- Cache keys always include **`parameter_version`**.
- If you do not set `parameter_version`, Hotvect uses:

```
last_test_date_YYYY-MM-DD
```

So a cache created with `--last-test-time 2026-01-07` is not expected to hit for `--last-test-time 2026-01-08`.

### SageMaker cache contract

- For SageMaker runs, cache must be an `s3://example-bucket` prefix.
- Local cache paths only exist within the container and do not persist across jobs.

### S3 single-file cache safety (collision-free)

When Hotvect downloads a single `s3://example-bucket/file.zip` cache object to local disk, it uses a **bucket+key-derived**
deterministic path under:

```
<local_cache_base>/.hotvect_s3_cache/<bucket>/<sha256(key)>/<basename>
```

This prevents wrong-cache hits when two different S3 keys share the same basename.

## Output + logs (contract)

Hotvect writes run artifacts under `output_base_dir` (local), and under a job-specific S3 prefix (SageMaker).

High-signal files (local):

- `output_base_dir/meta/<algo@version>/<parameter_version>/hv.log` (pipeline summary)
- `output_base_dir/meta/<algo@version>/<parameter_version>/hv.all.log` (includes dependency logs)
- `output_base_dir/meta/<algo@version>/<parameter_version>/result.json` (pipeline result)

High-signal files (SageMaker):

- CloudWatch: log group `/aws/sagemaker/TrainingJobs`
- S3 metadata uploaded by script-mode under:
  `.../<algo@version>/metadata/meta/.../hv.log`

## SageMaker execution (scope)

- `hv train` supports SageMaker execution via `--sagemaker` (submits a single one-shot training job for an algorithm JAR).
- `hv backtest` supports SageMaker execution via `--sagemaker` (submits jobs per git reference × day).
- Both support `--sagemaker-config <json>` and will also look for a default template in `~/.hotvect/config.json` under `sagemaker.sagemaker_config_template` when the flag is omitted.
- Both require `--sagemaker-job-prefix` and build the final `TrainingJobName` from it (must satisfy AWS naming rules; final name must be ≤ 63 characters).
- `hv backtest` always auto-attaches `InputDataConfig` channels in SageMaker mode. `hv train` only auto-attaches channels when `--auto-attach-data` is set (otherwise your template must define `InputDataConfig`).
- Some AWS accounts enforce IAM conditions on training job names. If job submission fails, ensure your
  `--sagemaker-job-prefix` is allowed (for example `exp-...`).
