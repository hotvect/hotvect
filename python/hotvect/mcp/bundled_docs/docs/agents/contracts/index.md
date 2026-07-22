---
title: Workflow contracts
description: Deterministic contracts for overrides, caching, outputs, and SageMaker execution
tags: [agents, contracts, caching, overrides, sagemaker]
---

# Workflow contracts

These are the shared contracts behind the runbooks. Follow the linked guide when a task needs more context or a
complete example.

## Override merge semantics

- An **override JSON** uses **patch semantics** on top of the algorithm definition loaded from the JAR (or git ref).
- Object fields merge recursively.
- Scalar and array values replace the base value.
- `null` deletes a field from the effective definition.
- Overrides are fragments, not full definitions: `algorithm_name` is rejected before merge, and `hv backtest` also
  rejects `algorithm_version`.
- `dependencies` is a child-override map, not a generic JSON merge:
  - keys must match already-declared child algorithms
  - unknown child names are a hard error
  - overriding one child preserves unspecified siblings
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

See [Override files](../../guides/patterns/override-files/index.md) and
[Parent/child algorithms](../../guides/patterns/parent-child/index.md).

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

So a cache created with `--last-test-time 2000-01-07` is not expected to hit for `--last-test-time 2000-01-08`.

### SageMaker cache contract

- Use an `s3://...` prefix when a cache must persist and be reused across SageMaker jobs.
- Local cache paths only exist within the container and do not persist after the job ends.

### S3 single-file cache safety (collision-free)

When Hotvect downloads a single `s3://.../file.zip` cache object to local disk, it uses a **bucket+key-derived**
deterministic path under:

```
<local_cache_base>/.hotvect_s3_cache/<bucket>/<sha256(key)>/<basename>
```

This prevents wrong-cache hits when two different S3 keys share the same basename.

## Output + logs (contract)

Hotvect writes run artifacts under `output_base_dir` (local), and under a job-specific S3 prefix (SageMaker).

High-signal files (local):

- `hv backtest`: `output_base_dir/meta/<algo@version>/<parameter_version>/hv.log`, `hv.all.log`, and `result.json`.
- `hv train`: `output_base_dir/metadata/<algo@version>/<parameter_version>/hv.log`, `hv.all.log`, and `result.json`.

High-signal files (SageMaker):

- CloudWatch: log group `/aws/sagemaker/TrainingJobs`
- S3 metadata uploaded by script-mode under:
  `.../<algo@version>/metadata/meta/.../hv.log`

## SageMaker execution (scope)

- `hv train` supports SageMaker execution via `--sagemaker` **or** `--sagemaker-config` (submits a single training job for an algorithm JAR).
- `hv backtest` supports SageMaker execution via `--sagemaker` **or** `--sagemaker-config` (submits jobs per git reference × day).
- Both accept `--sagemaker-config <json>` and look for a default template in `~/.hotvect/config.json` under `sagemaker.sagemaker_config_template` when no explicit template is supplied.
- Both require `--sagemaker-job-prefix` and build the final `TrainingJobName` from it (must satisfy AWS naming rules; final name must be ≤ 63 characters).
- `hv backtest` always auto-attaches `InputDataConfig` channels in SageMaker mode. `hv train` only auto-attaches channels when `--auto-attach-data` is set (otherwise your template must define `InputDataConfig`).
- Some AWS accounts enforce IAM conditions on training job names. If job submission fails, ensure your
  `--sagemaker-job-prefix` is allowed (for example `exp-...`).
