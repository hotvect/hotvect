---
title: Runbook - Debugging & logs
description: Where hotvect writes logs/metadata (local + SageMaker) and what to grep for
tags: [agents, runbook, debugging, logs]
---

# Runbook: Debugging & logs

Goal: quickly locate the authoritative logs/artifacts for a run and extract the signal needed for automation.

## Local backtests

Primary locations under `<output-base-dir>/meta/<algorithm-id>/<parameter-version>/`:

- `hv.log`:
  - pipeline summary for the algorithm
  - best for cache-hit detection
- `hv.all.log`:
  - includes dependency pipelines
- `result.json`:
  - structured run output (stages, timings, evaluation, etc.)

## Local train runs

`hv train` writes the same high-signal files under
`<output-base-dir>/metadata/<algorithm-id>/<parameter-version>/`. The root is `metadata`, not the backtest `meta`
root.

Cache-hit signals:

- `Predict parameters available ... skipping encode and train`
- `Using cached predict parameters ...`

## SageMaker backtests

Use both sources when a job fails:

1. CloudWatch logs (what the container printed)

- Group: `/aws/sagemaker/TrainingJobs`
- Stream: `<JOB_NAME>/algo-1-...`

2. S3 metadata logs (stable and grep-friendly)

Under the job output prefix:

- `.../<algo@version>/metadata/meta/.../hv.log`
- `.../<algo@version>/metadata/meta/.../hv.all.log`

If a SageMaker job fails without writing `result.json`, CloudWatch usually has the root cause.

## Common SageMaker failure classes

- Missing region on the client side: set `AWS_DEFAULT_REGION=<aws-region>` to the region containing the job.
- IAM conditions on `TrainingJobName`: use a prefix allowed by the role policy.
- Tags permission: if your role cannot tag training jobs, remove `Tags` from the job template JSON

Related: [Troubleshooting](../../../reference/troubleshooting/index.md) for command-level failures and
[Pipeline stages](../../../guides/pipeline-stages/index.md) for the artifact and log layout.
