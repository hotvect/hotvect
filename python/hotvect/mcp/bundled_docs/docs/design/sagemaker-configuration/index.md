---
title: Automatic SageMaker Configuration
description: Design notes for the algorithm-definition-first SageMaker workflow (resource precedence, helper structure, and rollout plan)
tags: [design, sagemaker, backtest, architecture]
difficulty: advanced
estimated_time: 15 minutes
related_docs:
  - ../../guides/sagemaker-backtests/index.md
  - ../sagemaker-inputdataconfig/index.md
  - ../../archive/design/backport-plan/index.md
---

# Automatic SageMaker Configuration – Design Update

## Overview

This document captures the current design for configuring SageMaker backtests in Hotvect. In short:

- You provide a **base SageMaker job definition** (typically via a template file, but template-free mode is supported via CLI flags).
- For each git reference, Hotvect loads the algorithm definition and **merges algorithm-declared overrides** into that base job definition.
- Hotvect then **auto-attaches `InputDataConfig` channels** from `AlgorithmPipeline.data_dependencies()`.

## Goals

1. **Algorithm-first overrides** – Algorithm definitions (and algorithm overrides) provide `sagemaker_training_job_definition` fragments (or legacy `sagemaker_execution_parameters`) that mutate the per-reference job definition.
2. **Safe fallbacks** – SageMaker templates provide boilerplate defaults (Role ARN, VPC config, output paths, tags, etc.).
3. **Template-free support** – Allow running without a template by supplying required fields via CLI flags (useful for ad-hoc runs). For deterministic behavior, commit resource settings into algorithm definitions or versioned override files.
4. **Per-Reference Fidelity** – Each git reference consulted during a backtest uses the version of the algorithm definition embedded in that reference (plus any override JSON provided).
5. **Clear Merge Semantics** – Algorithm definitions may provide a partial `sagemaker_training_job_definition` block that is recursively merged into the template before resource-specific helpers run.

## Architecture

### Precedence Model

```
Algorithm-defined overrides > Base job definition (template or template-free)
```

- Algorithm overrides are applied via recursive merge (so algorithm-provided values override the base).
- If neither the base nor the algorithm provides a required field, execution fails fast with a clear error.
- **Training image note:** `training_container` only fills `AlgorithmSpecification.TrainingImage` when the base job definition does not already specify `TrainingImage` (this supports script-mode, where a stable base image is intentionally pinned).

### Components

#### 1. Helper Functions (`hotvect/backtest.py`)

- **`apply_sagemaker_job_overrides(job_definition, overrides)`** – Recursively merges the algorithm’s `sagemaker_training_job_definition` fragment into the template-derived job definition.
- **`legacy_sagemaker_params_to_overrides(params)`** – Converts deprecated `sagemaker_execution_parameters` (instance_type, volume_size_in_gb, max_runtime) into the equivalent native SageMaker JSON so legacy algorithms continue to work.
- **`apply_training_container(job_definition, algorithm_definition)`** – If the job definition does **not** already specify `AlgorithmSpecification.TrainingImage`, fills it from the algorithm’s `training_container`. If `TrainingImage` is already set, it is respected (script-mode base image pinning).

#### 2. `_execute_on_sagemaker`

For each `last_test_day`:

```python
job_def = copy.deepcopy(base_job_definition)
apply_sagemaker_job_overrides(job_def, algorithm_definition.get("sagemaker_training_job_definition"))
legacy_overrides = legacy_sagemaker_params_to_overrides(
    algorithm_definition.get("sagemaker_execution_parameters")
)
apply_sagemaker_job_overrides(job_def, legacy_overrides)
apply_training_container(job_def, algorithm_definition)  # if training_container present

attach_input_channels(job_def, algorithm_pipeline)
```

Key properties:
- A base job definition must exist in SageMaker mode (from a template file or from template-free CLI flags).
- Per-reference isolation is achieved by deep-copying the base job definition for every iteration.
- Algorithm overrides propagate automatically because `AlgorithmPipeline` already merges override dictionaries via `recursive_dict_update`.

#### 3. CLI / Overrides

- `hv backtest` supports both template-based and template-free SageMaker execution.
  - Template-based: pass `--sagemaker-config <path>` (or set `sagemaker.sagemaker_config_template` in `~/.hotvect/config.json`).
  - Template-free: omit the template and pass required fields via CLI (`--role-arn`, `--s3-output-base`, `--instance-type`; optionally `--volume-gb`, `--max-runtime-seconds`, `--training-image`).
- Users customize algorithm-specific resources by editing the algorithm definition in git or by supplying an `algorithm_definition_override` JSON (recommended for reproducibility).

### Template Expectations

Templates continue to define everything that is **not** algorithm specific (Role ARN, S3 destinations, metric definitions, environment variables, etc.). During migration:
- Only set `AlgorithmSpecification.TrainingImage` in the template if you want to **pin** a base image (script-mode). Otherwise, leave it unset and use `training_container` in algorithm definitions.
- Keep `ResourceConfig.InstanceType/VolumeSizeInGB` in the template as defaults for algorithms that omit those fields.

### Error Handling

- Attempting to execute on SageMaker without a base SageMaker job definition raises a `ValueError` before any remote work is scheduled.
- Missing `training_container` simply leaves the template’s image in place.
- Unknown keys inside `sagemaker_execution_parameters` are ignored silently to avoid tampering with the template surface area.

## Testing Strategy

Unit coverage focuses on the helper functions:

1. `test_legacy_sagemaker_params_to_overrides` – Verifies the shim converts legacy keys into native SageMaker JSON.
2. `test_apply_sagemaker_job_overrides` – Ensures recursive merging behaves as expected for nested dictionaries and list replacements.
3. `test_sagemaker_params_precedence` – Confirms template defaults persist unless the algorithm declares a new value, and validates `training_container` overrides.
4. `test_apply_training_container_handles_missing_spec` – Verifies the helper creates `AlgorithmSpecification` when the template omitted it.

Integration sanity:
- Run `hv backtest ... --sagemaker-config template.json` for two git references whose `sagemaker_execution_parameters` differ; confirm each job launches with its own instance type.
- Supply an algorithm override JSON that changes `training_container`; confirm the submitted job uses the override image.

## Usage Examples

### Algorithm Definition

```json
{
  "algorithm_name": "example-algorithm",
  "algorithm_version": "1.2.3",
  "training_container": "registry.opensource.example/hotvect:10.11.1",
  "sagemaker_execution_parameters": {
    "instance_type": "ml.m5.12xlarge",
    "volume_size_in_gb": 150,
    "max_runtime": 18000
  }
}
```

### Algorithm Override JSON

```json
{
  "training_container": "registry.opensource.example/hotvect:10.11.1",
  "sagemaker_execution_parameters": {
    "instance_type": "ml.c5.24xlarge",
    "max_runtime": 21600
  }
}
```

### SageMaker Job Definition Overrides

Algorithms (or overrides) can now embed partial SageMaker configs:

```json
{
  "sagemaker_training_job_definition": {
    "AlgorithmSpecification": {
      "MetricDefinitions": [
        {"Name": "ndcg_at_all", "Regex": "'ndcg_at_all': (.*?)[,}]"}
      ]
    },
    "Tags": [
      {"Key": "team", "Value": "example-team"}
    ],
    "ResourceConfig": {
      "InstanceCount": 2
    }
  }
}
```

This fragment is recursively merged into the template before resource helpers run, so `sagemaker_execution_parameters` (and `training_container`) still win when set.

Run with:

```bash
hv backtest \
  --git-reference v77.0.0 \
  --git-reference v64.4.0 \
  --algorithm-override overrides/v77.json \
  --algo-repo-url https://github.com/example-org/example-algorithm.git \
  --sagemaker-config ~/.hotvect/sagemaker-template.json \
  --output-base-dir /work/output \
  --scratch-dir /work/scratch \
  --last-test-time 2025-08-09
```

### Template Fallback

If `v64.4.0` does not declare `sagemaker_execution_parameters`, the template’s `InstanceType`, `VolumeSizeInGB`, and `MaxRuntimeInSeconds` remain untouched. Only the reference with overrides diverges.

## Rollout & Communication

1. **Docs** – Keep the CLI reference and SageMaker guide aligned with the actual precedence rules (especially around `TrainingImage` pinning for script-mode).
2. **Migration Guidance** – Prefer moving algorithm-specific resource config into algorithm definitions (or versioned override JSON) so backtests are deterministic across machines.
3. **Template Guidance** – Use templates for shared boilerplate (VPC config, output paths, tags). Use template-free mode for quick experiments only.

## Success Criteria

- `hv backtest` submissions differ only when algorithm definitions differ.
- No more placeholder training images or hard-coded S3 paths in remote mode.
- Users report fewer surprises because SageMaker behavior now matches what lives in the repo (or in explicit override files).
