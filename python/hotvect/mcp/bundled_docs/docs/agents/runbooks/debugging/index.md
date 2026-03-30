---
title: Runbook - Debugging & logs
description: Where hotvect writes logs/metadata (local + SageMaker) and what to grep for
tags: [agents, runbook, debugging, logs]
---

# Runbook: Debugging & logs

Goal: quickly locate the authoritative logs/artifacts for a run and extract the signal needed for automation.

## Local runs (`hv train`, `hv backtest`)

Primary locations under `output_base_dir`:

- `meta/<algo@version>/<parameter_version>/hv.log`:
  - pipeline summary for the algorithm
  - best for cache-hit detection
- `meta/<algo@version>/<parameter_version>/hv.all.log`:
  - includes dependency pipelines
- `meta/<algo@version>/<parameter_version>/result.json`:
  - structured run output (stages, timings, evaluation, etc.)

Cache-hit signals:

- `Predict parameters available ... skipping encode and train`
- `Using cached predict parameters ...`

## SageMaker runs (`hv backtest --sagemaker-config`)

You generally want **both**:

1) CloudWatch logs (what the container printed)

- Group: `/aws/sagemaker/TrainingJobs`
- Stream: `<JOB_NAME>/algo-1-...`

2) S3 metadata logs (stable, structured, grep-friendly)

Under the job output prefix:

- `.../<algo@version>/metadata/meta/.../hv.log`
- `.../<algo@version>/metadata/meta/.../hv.all.log`

If a SageMaker job fails without writing `result.json`, CloudWatch usually has the root cause.

## Common SageMaker failure classes

- Missing region on the client side: set `AWS_DEFAULT_REGION=eu-central-1`
- IAM conditions on `TrainingJobName`: use an allowed prefix (often `exp-...`)
- Tags permission: if your role cannot tag training jobs, remove `Tags` from the job template JSON
