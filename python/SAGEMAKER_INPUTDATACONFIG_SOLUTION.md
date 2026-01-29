# SageMaker InputDataConfig Solution

## Problem

Running `hv backtest --sagemaker-config ...` historically required the user (or plugin) to manually populate `InputDataConfig`. When the array was left empty, SageMaker training jobs failed immediately because no S3 channels were defined. The previous workaround involved invoking separate `hv-ext` commands to analyze dependencies and rewrite the config file—a slow and error-prone process that duplicated logic already present in the backtest framework.

## Current Solution (Framework-Integrated)

### Overview

`hv backtest` now owns InputDataConfig construction. Users simply pass `--auto-attach-data` (plus optional helpers) and the framework:

1. Builds the algorithm JAR (as before)
2. Uses `AlgorithmPipeline.data_dependencies()` to discover required datasets
3. Resolves the S3 URI for each dependency
4. Appends missing channels to the provided SageMaker config (in-memory copy)

This leverages information already available inside the backtest pipeline and avoids extra repo clones or CLI scripting.

### CLI Flags

| Flag | Purpose |
|------|---------|
| `--auto-attach-data` | Enables automatic channel generation. Ignored in local mode. |
| `--auto-attach-data-default-s3-base <uri>` | Optional fallback such as `s3://bucket/tables/` when definitions do not specify `s3_uri`. |
| `--auto-attach-data-environment {production,test}` | Selects which key to use when `s3_uri` is a dictionary (default: `production`). |

All three flags are documented in `hotvect-claude-code-plugin/HV_COMMAND_REFERENCE.md`.

### Flow In BacktestPipeline

1. Flags are stored when `BacktestPipeline` is constructed.
2. During `_execute_on_sagemaker`, for each scheduled training job:
   - Dependencies are fetched via `algorithm_pipeline.data_dependencies()`.
   - `_attach_input_data_config()` deduplicates channel names and resolves S3 URIs.
   - Each auto-attached channel is logged (`channel`, `S3Uri`).
3. The job-specific copy of the SageMaker config is mutated; the original template remains untouched.

### S3 URI Resolution Rules

1. **Explicit string**
   `dependency.additional_properties["s3_uri"] = "s3://bucket/path/"`.

2. **Environment map**
   `dependency.additional_properties["s3_uri"] = {"production": "...", "test": "..."}`.
   We try the requested environment first, then fall back to common keys (`production`, `prod`, `test`, `staging`) before finally using any available value.

3. **Default base**
   If no explicit URI exists, we require `--auto-attach-data-default-s3-base`. The channel’s `data_prefix` is appended (with a trailing slash).

4. **Failure**
   If none of the above produce a URI, we raise a `ValueError` so the user can either add `s3_uri` to the algorithm definition or provide the default base flag. There is no silent fallback to `~/.hotvect/config.json`—the value must come from the CLI or the algorithm metadata.

### Logging

For each newly created channel we emit:

```
Auto-attach InputDataConfig channel 'my_data_w_fs' with S3 URI 's3://prod/.../my_data_w_fs/'
```

If a dependency’s channel is already present in the template, we skip it without logging an addition (ensuring templates with hand-crafted channels remain intact).

## Optional: Inspecting Dependencies

The `hv-ext show-data-dependency` command remains available as an **optional diagnostic tool**:

```bash
hv-ext show-data-dependency \
  --repo-url https://github.com/my-org/my-algorithm.git \
  --git-reference v77.0.0 \
  --scratch-dir ./temp \
  --last-test-time 2025-08-09 \
  -o deps.json
```

This can be useful for auditing the discovered dependencies or sharing them in reviews, but it is no longer a required step in the backtest workflow.

## Updated Plugin Workflow

1. Copy the SageMaker template to the scratch directory (configuration protection policy still applies).
2. Invoke `hv backtest` with `--sagemaker-config <copied-file>` and `--auto-attach-data`. Example:

```bash
hv backtest \
  --git-reference v77.0.0 \
  --git-reference v64.4.0 \
  --algo-repo-url ${repo_url} \
  --output-base-dir ${output_dir} \
  --scratch-dir ${scratch_dir} \
  --last-test-time ${test_date} \
  --sagemaker-config ${scratch_dir}/sagemaker-config.json \
  --auto-attach-data \
  --auto-attach-data-default-s3-base s3://my-bucket/tables/
```

3. The framework augments the config before each job submission. There is no longer any need to edit JSON files manually.

## Advantages of the Integrated Approach

- **Single source of truth** – dependency extraction happens where the training pipeline already lives.
- **No duplicate builds** – the framework reuses the jar compiled for the backtest.
- **Better UX** – users flip a flag instead of running multi-step scripts.
- **Safer** – templates are never modified directly; everything happens on per-job copies.
- **Testable** – the logic is concentrated in `_attach_input_data_config`, making it easy to unit-test with mocked dependencies.

## Cleanup Notes

- The old `hv-ext populate-sagemaker-config` command has been removed.
- Documentation (agents, skills, and this file) has been updated to reflect the new flag-based workflow.
- The optional `hv-ext show-data-dependency` command remains for debugging/inspection purposes only.
