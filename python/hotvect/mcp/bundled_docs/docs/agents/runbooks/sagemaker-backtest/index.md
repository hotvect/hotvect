---
title: Runbook - SageMaker backtest
description: Copy/paste runbook for `hv backtest` on SageMaker with S3-backed cache
tags: [agents, runbook, sagemaker, backtest, caching]
---

# Runbook: SageMaker backtest (`hv backtest --sagemaker`)

Submit a SageMaker backtest with an explicit job prefix, then validate the local submission manifest, job state, and
remote artifacts.

## Agent contract

| Inputs | Command | Artifacts | Verify |
| --- | --- | --- | --- |
| Algorithm repo/ref, SageMaker template or template-free settings, job prefix, test date | `hv backtest --sagemaker` | Local submission manifest/status, SageMaker job names, S3 metadata | Submission exits `0`; every job reaches a terminal successful state before comparing results |

Use a template for a repeatable job definition. Template-free mode additionally requires `--role-arn`,
`--s3-output-base`, and `--instance-type`.

## Preconditions

- AWS credentials configured for the target account and region (commonly `eu-central-1`)
- A SageMaker job template JSON (`--sagemaker-config`) **or** the template-free settings `--role-arn`,
  `--s3-output-base`, and `--instance-type`
- If using caching: an **S3 cache prefix** (`--cache s3://.../`)

## Inputs (fill these in)

- `ALGO_REPO_URL`: git URL or local path to algorithm repo
- `GIT_REF`: branch/tag/commit
- `LAST_TEST_TIME`: `YYYY-MM-DD`
- `OUTPUT_BASE_DIR`: local destination (used for bookkeeping; SageMaker artifacts live in S3)
- `SCRATCH_DIR`: local build dir (used to build and upload jars/defs)
- `SAGEMAKER_CONFIG`: path to job template JSON
- `SAGEMAKER_JOB_PREFIX`: valid, allowed SageMaker TrainingJobName prefix
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
  --sagemaker \
  --sagemaker-job-prefix "$SAGEMAKER_JOB_PREFIX" \
  --sagemaker-config "$SAGEMAKER_CONFIG" \
  --cache "$S3_CACHE_PREFIX" \
  --cache-scope hyperparam \
  --no-performance-test
```

Add `--algorithm-override "$OVERRIDE_JSON"` only when an override file is present.

Hotvect returns after all requested jobs are submitted and prints their `TrainingJobName` values. It also writes local
submission records under:

```text
$OUTPUT_BASE_DIR/meta/_backtest_submissions/<run_id>/
  backtest_submission_manifest.json
  backtest_submission_status.json
```

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

## Validate before comparing results

1. Check that each `TrainingJobName` in `backtest_submission_manifest.json` succeeds.
2. Confirm the expected `result.json` and `hv.log` locations in S3.
3. Compare only runs with the same evaluation and performance contracts. If a metrics plot warns about mixed benchmark
   specifications, it intentionally omits system latency/throughput metrics; it is not a system-performance comparison.

If a job fails, continue with the [debugging runbook](../debugging/index.md). For the human-facing setup and
configuration rationale, use [Run backtests on AWS SageMaker](../../../guides/sagemaker-backtests/index.md).
