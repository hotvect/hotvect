---
title: SageMaker InputDataConfig Solution
description: Design reference for automatically constructing SageMaker InputDataConfig entries from algorithm metadata
tags: [design, sagemaker, data-dependencies]
difficulty: advanced
estimated_time: 10 minutes
related_docs:
  - ../../guides/sagemaker-backtests/index.md
  - ../sagemaker-configuration/index.md
---

# SageMaker InputDataConfig Solution

> **Note**: This is an internal design document. For user-facing documentation, see [How to: Run Backtests on AWS SageMaker](../../guides/sagemaker-backtests/index.md).

## Problem

Running `hv backtest --sagemaker-config ...` historically required the user (or a wrapper automation) to manually populate `InputDataConfig`. When the array was left empty, SageMaker training jobs failed immediately because no S3 channels were defined. The previous workaround involved invoking separate `hv-ext` commands to analyze dependencies and rewrite the config file—a slow and error-prone process that duplicated logic already present in the backtest framework.

## Current Solution (Framework-Integrated)

### Overview

`hv backtest` now owns InputDataConfig construction. Whenever you run in SageMaker mode, the framework automatically:

1. Builds the algorithm JAR (as before)
2. Uses `AlgorithmPipeline.data_dependencies()` to discover required datasets
3. Resolves the S3 URI for each dependency
4. Appends missing channels to the provided SageMaker config (in-memory copy)

This leverages information already available inside the backtest pipeline and avoids extra repo clones or CLI scripting.

### CLI Options

| Option | Purpose |
|--------|---------|
| `--auto-attach-data-default-s3-base <uri>` | Optional fallback such as `s3://example-bucket/tables/` when definitions do not specify `s3_uri`. |
| `--auto-attach-data-environment <env>` | Preferred environment key when `s3_uri` is a dictionary (default: `production`). Keys are matched case-insensitively with fallbacks (`production`, `prod`, `test`, `staging`, then first map entry). |

These options are documented in `hv backtest --help` and in the CLI docs under `reference/cli/index.md`.

### Flow In BacktestPipeline

1. Options are stored when `BacktestPipeline` is constructed.
2. During `_execute_on_sagemaker`, for each scheduled training job:
   - Dependencies are fetched via `algorithm_pipeline.data_dependencies()`.
   - `_attach_input_data_config()` deduplicates channel names and resolves S3 URIs.
   - Each auto-attached channel is logged (`channel`, `S3Uri`).
3. The job-specific copy of the SageMaker config is mutated; the original template remains untouched.

### S3 URI Resolution Rules

1. **Explicit string**
   `dependency.additional_properties["s3_uri"] = "s3://example-bucket/path/"`.

2. **Environment map**
   `dependency.additional_properties["s3_uri"] = {"production": "...", "test": "..."}`.
   Hotvect normalizes keys to lowercase and prefers (in order): the requested environment (`--auto-attach-data-environment`, default `production`), then `production`, `prod`, `test`, `staging`. If none match but the map is non-empty, the first entry is used as a last resort.

3. **Default base**
   If no explicit URI exists, we require `--auto-attach-data-default-s3-base`. The channel’s `data_prefix` is appended (with a trailing slash).

4. **Failure**
   If none of the above produce a URI, we raise a `ValueError` so the user can either add `s3_uri` to the algorithm definition or provide the default base option. There is no silent fallback to `~/.hotvect/config.json`—the value must come from the CLI or the algorithm metadata.

!!! note "Diagnostics vs. backtest behavior"
    `hv-ext show-data-dependency` uses a stricter resolver for display (`hotvect.utils.resolve_data_dependency_s3_uri`), which is case-sensitive and errors if the requested environment is missing. Auto-attach during `hv backtest` follows the (more forgiving) rules described above.

### Logging

For each newly created channel we emit:

```
Auto-attach InputDataConfig channel 'example_test_data' with S3 URI 's3://example-bucket/tables/example_test_data/'
```

If a dependency’s channel is already present in the template, we skip it without logging an addition (ensuring templates with hand-crafted channels remain intact).

## Optional: Inspecting Dependencies

The `hv-ext show-data-dependency` command remains available as an **optional diagnostic tool**:

```bash
hv-ext show-data-dependency \
  --repo-url https://github.com/example-org/example-algorithm.git \
  --git-reference v77.0.0 \
  --scratch-dir ./temp \
  --last-test-time 2025-08-09 \
  -o deps.json
```

This can be useful for auditing the discovered dependencies or sharing them in reviews, but it is no longer a required step in the backtest workflow.

## Updated Workflow

1. Copy the SageMaker template to the scratch directory (configuration protection policy still applies).
2. Invoke `hv backtest` with `--sagemaker-config <copied-file>`. Example:

```bash
hv backtest \
  --git-reference v77.0.0 \
  --git-reference v64.4.0 \
  --algo-repo-url ${repo_url} \
  --output-base-dir ${output_dir} \
  --scratch-dir ${scratch_dir} \
  --last-test-time ${test_date} \
  --sagemaker-config ${scratch_dir}/sagemaker-config.json \
  --auto-attach-data-default-s3-base s3://example-bucket/tables/
```

3. The framework augments the config before each job submission. There is no longer any need to edit JSON files manually.

## Advantages of the Integrated Approach

- **Single source of truth** – dependency extraction happens where the training pipeline already lives.
- **No duplicate builds** – the framework reuses the jar compiled for the backtest.
- **Better UX** – users don’t need manual preprocessing or extra scripts.
- **Safer** – templates are never modified directly; everything happens on per-job copies.
- **Testable** – the logic is concentrated in `_attach_input_data_config`, making it easy to unit-test with mocked dependencies.

## Cleanup Notes

- The old `hv-ext populate-sagemaker-config` command has been removed.
- Documentation (agents, skills, and this file) has been updated to reflect the automatic workflow.
- The optional `hv-ext show-data-dependency` command remains for debugging/inspection purposes only.
