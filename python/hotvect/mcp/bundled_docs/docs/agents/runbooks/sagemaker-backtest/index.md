---
title: Runbook - SageMaker backtest
description: Copy/paste runbook for `hv backtest` on SageMaker with S3-backed cache
tags: [agents, runbook, sagemaker, backtest, caching]
---

# Runbook: SageMaker backtest (`hv backtest --sagemaker-config`)

Goal: submit a SageMaker backtest and validate results + cache behavior via S3 metadata and CloudWatch logs.

## Preconditions

- AWS credentials configured for the target account and region (commonly `eu-central-1`)
- A SageMaker job template JSON (`--sagemaker-config`)
- If using caching: an **S3 cache prefix** (`--cache s3://example-bucket/`)

## Inputs (fill these in)

- `ALGO_REPO_URL`: git URL or local path to algorithm repo
- `GIT_REF`: branch/tag/commit
- `LAST_TEST_TIME`: `YYYY-MM-DD`
- `OUTPUT_BASE_DIR`: local destination (used for bookkeeping; SageMaker artifacts live in S3)
- `SCRATCH_DIR`: local build dir (used to build and upload jars/defs)
- `SAGEMAKER_CONFIG`: path to job template JSON
- `OVERRIDE_JSON`: override file
- `S3_CACHE_PREFIX`: e.g. `s3://example-bucket/hotvect-cache/` (recommended)

## Submit job

```bash
hv backtest \
  --git-reference "$GIT_REF" \
  --algo-repo-url "$ALGO_REPO_URL" \
  --output-base-dir "$OUTPUT_BASE_DIR" \
  --scratch-dir "$SCRATCH_DIR" \
  --last-test-time "$LAST_TEST_TIME" \
  --algorithm-override "$OVERRIDE_JSON" \
  --sagemaker-config "$SAGEMAKER_CONFIG" \
  --cache "$S3_CACHE_PREFIX" \
  --cache-scope hyperparam \
  --no-performance-test
```

Hotvect returns after submission and prints the `TrainingJobName`.

## Poll status

```bash
aws sagemaker describe-training-job \
  --training-job-name "<JOB_NAME>" \
  --query '{Status:TrainingJobStatus,Secondary:SecondaryStatus,FailureReason:FailureReason}' \
  --output json
```

## Where to find logs

### CloudWatch (container stdout/stderr)

- Log group: `/aws/sagemaker/TrainingJobs`
- Stream name prefix: `<JOB_NAME>/algo-1-...`

### S3 “hv.log” (recommended for automation)

In script-mode runs, Hotvect uploads metadata under the job output prefix:

```
.../<algo@version>/metadata/meta/<algo@version>/<parameter_version>/hv.log
.../<algo@version>/metadata/meta/<algo@version>/<parameter_version>/hv.all.log
```

These logs are stable for grepping:

- `Predict parameters available ... skipping encode and train`
- `Using cached predict parameters ...`

## Script-mode extras (important)

Some script-mode payloads default to installing `hotvect[tensorflow]`. If you want **no extras** (to reduce installs),
set `HOTVECT_EXTRAS` explicitly in the SageMaker job `Environment`.

If your payload treats empty string as “use default”, set a non-empty value that results in no extras.
One robust trick is:

```
HOTVECT_EXTRAS=","
```

(parses to no extras in many payloads that split on commas and drop empty entries).
