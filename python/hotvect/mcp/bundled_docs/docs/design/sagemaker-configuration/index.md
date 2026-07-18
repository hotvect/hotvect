---
title: SageMaker job configuration
description: Current precedence, per-reference assembly, capacity selection, and data-channel design for remote Hotvect jobs
tags: [design, sagemaker, backtest, architecture]
difficulty: advanced
related_docs:
  - ../../guides/sagemaker-backtests/index.md
  - ../sagemaker-inputdataconfig/index.md
---

# SageMaker job configuration

This page records how Hotvect assembles the SageMaker training-job definition for each git reference in a remote train
or backtest. Use the [SageMaker backtest guide](../../guides/sagemaker-backtests/index.md) for the task workflow.

## Configuration owners

Hotvect combines four layers:

```text
explicit CLI fields
  > algorithm override JSON
  > committed algorithm definition
  > base job definition
```

The higher layer wins at an overlapping field. Objects merge recursively; a higher-layer array replaces the lower
array.

| Layer | Owns |
| --- | --- |
| Base job definition | Shared role, networking, output location, tags, and resource defaults |
| `sagemaker_training_job_definition` in the algorithm | Image and resources that belong to that algorithm version |
| Algorithm override | Reproducible resource or image change for one run/reference |
| CLI | Explicit invocation-level values such as role, image, instance type, volume, and maximum runtime |

Changing only the template does not replace a field already owned by the algorithm or override. Put a per-algorithm
choice in its definition; put a one-run change in the explicit override.

## Per-reference assembly

For every reference and test date, Hotvect:

1. deep-copies the base job definition;
2. loads that reference's embedded algorithm definition;
3. applies the matching algorithm override, if present;
4. merges the effective `sagemaker_training_job_definition`;
5. applies explicit CLI job fields;
6. resolves the instance type and validates required fields;
7. derives `InputDataConfig` channels from that pipeline's data dependencies;
8. assigns the final `TrainingJobName` and submits the job.

The deep copy matters: one reference's settings must not leak into another reference in the same backtest.

## Template-based and template-free runs

A base definition can come from:

- `--sagemaker-config <path>` or `sagemaker.sagemaker_config_template` in the user config; or
- the required template-free CLI fields, including role, output prefix, and instance type.

The assembled job must contain every SageMaker-required field before submission. Hotvect fails before creating a
remote job when the base definition and higher-precedence layers still leave a required field absent.

Reusable templates should normally omit `AlgorithmSpecification.TrainingImage`. Put the normal image in the
algorithm's `sagemaker_training_job_definition`, use an algorithm override for a versioned experiment, or use
`--training-image` for an explicit invocation-level change.

## Ordered capacity choices

Use one fixed `ResourceConfig.InstanceType` when the job requires a single machine type. Use
`HotvectSubmissionOptions.PreferredInstanceTypes` when several acceptable types should be tried in order:

```json
{
  "sagemaker_training_job_definition": {
    "AlgorithmSpecification": {
      "TrainingImage": "registry.example/hotvect:<HOTVECT_VERSION>"
    },
    "HotvectSubmissionOptions": {
      "PreferredInstanceTypes": [
        "ml.m5.xlarge",
        "ml.r6i.xlarge"
      ]
    },
    "ResourceConfig": {
      "InstanceCount": 1,
      "VolumeSizeInGB": 100
    },
    "StoppingCondition": {
      "MaxRuntimeInSeconds": 18000
    }
  }
}
```

Hotvect materializes the first preferred type into `ResourceConfig.InstanceType`. If SageMaker rejects it with
`ResourceLimitExceeded`, Hotvect retries the remaining entries in order. The list must be nonempty and contain only
strings. If the definition also contains `ResourceConfig.InstanceType`, it must equal the first preferred type.

## Override one reference

An override remains a partial algorithm-definition patch:

```json
{
  "sagemaker_training_job_definition": {
    "HotvectSubmissionOptions": {
      "PreferredInstanceTypes": ["ml.c5.xlarge", "ml.m5.xlarge"]
    },
    "StoppingCondition": {
      "MaxRuntimeInSeconds": 21600
    }
  }
}
```

Attach overrides in the same positional order as repeated git references:

```bash
hv backtest \
  --git-reference v2.0.0 \
  --git-reference v1.0.0 \
  --algorithm-override overrides/v2.json \
  --algo-repo-url https://github.com/example-org/example-algorithm.git \
  --sagemaker \
  --sagemaker-job-prefix example-backtest \
  --sagemaker-config ~/.hotvect/sagemaker-template.json \
  --output-base-dir /work/output \
  --scratch-dir /work/scratch \
  --last-test-time 2000-01-08
```

Only the first reference receives the shown override. Its settings do not mutate the second reference or the base
template.

## Data channels are derived separately

Resource configuration does not select algorithm data. After the effective definition is known, Hotvect derives
SageMaker input channels from that reference's data dependencies and requested target. See
[SageMaker InputDataConfig](../sagemaker-inputdataconfig/index.md) for URI resolution, channel naming, and validation.
