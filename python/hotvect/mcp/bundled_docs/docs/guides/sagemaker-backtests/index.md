---
title: How to Run Backtests on AWS SageMaker
description: Run ML algorithm backtests on scalable cloud infrastructure with automatic resource and data-channel configuration
tags: [backtest, sagemaker, aws, cloud, comparison, evaluation]
difficulty: intermediate
estimated_time: 30 minutes
prerequisites:
  - Algorithm JAR built and available in git
  - AWS credentials with SageMaker permissions
  - Training data available in S3
  - hotvect CLI installed
related_docs:
  - ../../reference/cli/index.md
  - ../patterns/override-files/index.md
  - ../../design/sagemaker-configuration/index.md
related_commands:
  - hv backtest
  - hv-ext results download
  - hv-ext metrics compare-quality
next_steps:
  - Analyze evaluation metrics
  - Compare algorithm performance
  - Deploy winning algorithm version
---

# How to: Run Backtests on AWS SageMaker

## Overview

Running backtests on AWS SageMaker lets you push heavy training workloads to managed compute while preserving the exact same algorithm JAR that runs locally. This guide walks through:

- Preparing credentials and configuration templates
- Letting hotvect auto-detect the correct instance types and data channels per git reference
- Monitoring jobs and retrieving results
- Troubleshooting common SageMaker pitfalls

The automatic workflow is now the default: SageMaker resource requirements come from the algorithm definition, and `InputDataConfig` entries are always attached by the framework.

## Prerequisites

### 1. AWS Credentials

You need a role that can submit SageMaker training jobs plus read/write the relevant S3 buckets:

```bash
# Ensure your AWS credentials are set up (adjust for your environment)
# Examples: `aws configure`, `aws sso login`, or exporting AWS_* env vars.
aws sts get-caller-identity
```

### 2. SageMaker Configuration Template

Create a reusable template at `~/.hotvect/sagemaker-template.json`. It should include global defaults but leave `InputDataConfig` empty—the framework will fill it in.

```json
{
  "RoleArn": "arn:aws:iam::123456789012:role/example-role",
  "AlgorithmSpecification": {
    "TrainingInputMode": "FastFile"
  },
  "ResourceConfig": {
    "InstanceType": "ml.m5.4xlarge",
    "InstanceCount": 1,
    "VolumeSizeInGB": 100
  },
  "StoppingCondition": {
    "MaxRuntimeInSeconds": 86400
  },
  "OutputDataConfig": {
    "S3OutputPath": "s3://example-bucket/sagemaker-output/"
  },
  "InputDataConfig": []
}
```

!!! note "About TrainingImage / TrainingJobName"
    This file is a **template**. Hotvect sets `TrainingJobName` automatically, and (for typical backtests) fills `AlgorithmSpecification.TrainingImage` from the algorithm definition's `training_container` unless you pin a base image explicitly for script-mode.

### 3. Hotvect Configuration

`~/.hotvect/config.json` should describe local directories and (optionally) the default SageMaker template path:

```json
{
  "directories": {
    "data_base_dir": "/local/path/to/data",
    "output_base_dir": "/local/path/to/output",
    "scratch_dir": "/local/path/to/scratch"
  },
  "sagemaker": {
    "sagemaker_config_template": "~/.hotvect/sagemaker-template.json"
  }
}
```

## Choosing an Execution Mode

Hotvect SageMaker backtests use a **single container entrypoint** and switch behavior by hyperparameters:

- **Regular mode** is the default. If `HyperParameters.s3_uri_custom_jar` is absent, the training image runs the Hotvect runtime already baked into the image.
- **Script-mode** is opt-in. If `HyperParameters.s3_uri_custom_jar` is present, the container runs a payload zip containing `custom.py` instead of going straight into the normal pipeline rebuild.

Use regular mode unless you specifically need one of these:

- override the Hotvect runtime without publishing a new image
- keep a stable base `TrainingImage` but install a different Hotvect version from a wheelhouse
- add extra Python dependencies that are not already in the training image

Important practical point:

- `hv backtest` does **not** currently build or upload a script-mode payload for you. Script-mode only happens if your SageMaker job definition/template already includes `HyperParameters.s3_uri_custom_jar` or another launcher injects it.

For the full script-mode contract, payload layout, and wheelhouse pattern, see [How to Upgrade Hotvect on SageMaker (custom.py)](../sagemaker-upgrade-custom-py/index.md).

## Automatic Resource Configuration

Hotvect reads resource requirements directly from algorithm metadata (and optional overrides). For typical backtests, you don't need CLI flags to tweak instance types; declare them in the algorithm definition (or a versioned override file).

### How It Works

Precedence is simple for most fields: **Algorithm declaration (including overrides) > Template default**. If you pin `AlgorithmSpecification.TrainingImage` in the template (script-mode), it is respected; otherwise Hotvect fills it from `training_container`.

1. **Template** – Provides fallbacks for shared settings such as `RoleArn`, VPC config, and baseline `ResourceConfig`.
2. **Algorithm definition** – Supplies `training_container` plus either a partial `sagemaker_training_job_definition` or the legacy `sagemaker_execution_parameters` block. Legacy fields trigger a warning but are still honored.
3. **Algorithm override JSON** – `--algorithm-override` mutates the definition for a given git reference, so you can experiment without touching git history.

### Algorithm Declaration

Declare resource needs inside `algorithm-definition.json`:

```json
{
  "algorithm_name": "my-algorithm",
  "algorithm_version": "1.0.0",
  "training_container": "registry.opensource.example/hotvect:10.11.1",
  "sagemaker_training_job_definition": {
    "ResourceConfig": {
      "InstanceType": "ml.m5.12xlarge",
      "VolumeSizeInGB": 150
    },
    "StoppingCondition": {
      "MaxRuntimeInSeconds": 15000
    }
  }
}
```

Parent algorithms own these fields; children inherit the parent’s choice.

### Algorithm Overrides

Need a one-off experiment? Supply a JSON override file:

```json
{
  "training_container": "registry.opensource.example/hotvect:10.11.1",
  "sagemaker_execution_parameters": {
    "instance_type": "ml.c5.24xlarge",
    "max_runtime": 21600
  }
}
```

Attach it with `--algorithm-override overrides/experiment.json`. Overrides line up with git references in positional order, so you can vary resource allocations per version in a single `hv backtest` invocation.

### SageMaker Job Definition Overrides

For advanced customization (tags, metric definitions, VPC config) embed a partial `sagemaker_training_job_definition`. Hotvect deep-merges it into the template before applying `training_container` or legacy parameters.

### Precedence Examples

| Template | Algorithm | Override | Result |
|----------|-----------|----------|--------|
| `ml.m5.xlarge` | `ml.m5.12xlarge` | — | `ml.m5.12xlarge` (algorithm wins) |
| `ml.m5.xlarge` | `ml.m5.12xlarge` | `ml.c5.24xlarge` | `ml.c5.24xlarge` (override wins) |
| `ml.m5.4xlarge` | — | — | `ml.m5.4xlarge` (template fallback) |

## Understanding InputDataConfig

### Why It Matters

SageMaker mounts training data via named channels. If `InputDataConfig` is empty, the container starts successfully but immediately fails because the files never appear at `/opt/ml/input/data/{ChannelName}`.

### What the Framework Does

Whenever you run `hv backtest` with `--sagemaker-config`, the framework:

1. Builds each algorithm JAR.
2. Calls `AlgorithmPipeline.data_dependencies()` to list datasets.
3. Resolves the S3 URI for every dependency.
4. Deduplicates channels by `data_prefix`.
5. Appends missing channels to a per-job copy of the template.

The behavior is documented in [SageMaker InputDataConfig Solution](../../design/sagemaker-inputdataconfig/index.md).

### S3 URI Resolution Priority

1. **Explicit `s3_uri` string** in the algorithm definition.
2. **Environment map** – when `s3_uri` is a dictionary, hotvect prefers the requested environment (default `production`, override via `--auto-attach-data-environment`). Keys are matched case-insensitively with fallbacks (`production`, `prod`, `test`, `staging`, then first map entry).
3. **Default base** – `--auto-attach-data-default-s3-base` + `data_prefix` if the definition omitted explicit URIs.
4. **Fail loudly** – If none of the above succeed, `hv backtest` raises a `ValueError`.

### Channel Deduplication

Channels are keyed by `data_prefix`, so multiple git references that share the same dependency reuse one `InputDataConfig` entry. SageMaker’s `S3Prefix` mode automatically includes all `dt=YYYY-MM-DD/` partitions beneath the prefix.

## Running SageMaker Backtests

### Automatic InputDataConfig (default)

```bash
hv backtest \
  --git-reference v77.0.0 \
  --git-reference v64.4.0 \
  --algo-repo-url https://github.com/example-org/example-algorithm.git \
  --output-base-dir /local/output \
  --scratch-dir /local/scratch \
  --last-test-time 2025-08-09 \
  --sagemaker-config ~/.hotvect/sagemaker-template.json \
  --auto-attach-data-default-s3-base s3://example-bucket/tables/ \
  --auto-attach-data-environment production \
  --algorithm-override overrides/2day.json
```

What happens:

1. Git references are cloned/built once.
2. Each algorithm keeps its own declared instance type and container.
3. Data dependencies become `InputDataConfig` channels automatically.
4. Jobs submit asynchronously; the CLI prints job names for tracking.

### Manual InputDataConfig (rare)

If you must handcraft channels (e.g., manifest files or bespoke input modes), copy the template to a scratch file, edit `InputDataConfig`, and pass that file via `--sagemaker-config`. The framework leaves existing channels untouched and only adds missing ones.

## Monitoring Jobs

### View Job Status

Use the AWS Console or CLI:

```bash
aws sagemaker list-training-jobs --sort-by CreationTime --sort-order Descending --max-results 10
aws sagemaker describe-training-job --training-job-name hotvect-backtest-abc123
aws logs tail /aws/sagemaker/TrainingJobs --follow --log-stream-name hotvect-backtest-abc123
```

### Troubleshoot a failed job (AWS CLI)

SageMaker records a high-level failure reason in the job description and writes detailed logs to CloudWatch Logs.

```bash
REGION=<aws-region>
JOB=<training-job-name>
LOG_GROUP="/aws/sagemaker/TrainingJobs"

# 1) Failure reason (plus quick pointers to result + metadata)
aws sagemaker describe-training-job --region "$REGION" --training-job-name "$JOB" \
  --query '{status:TrainingJobStatus,secondary:SecondaryStatus,reason:FailureReason,result:HyperParameters.s3_uri_result_file,meta:HyperParameters.s3_uri_metadata}' \
  --output json

# 2) Locate the most recent log stream for this job
STREAM=$(aws logs describe-log-streams --region "$REGION" --log-group-name "$LOG_GROUP" \
  --log-stream-name-prefix "$JOB" --order-by LastEventTime --descending \
  --max-items 1 --query 'logStreams[0].logStreamName' --output text)

# 3) Tail the last N log events from that stream
aws logs get-log-events --region "$REGION" --log-group-name "$LOG_GROUP" --log-stream-name "$STREAM" \
  --limit 200 --no-start-from-head --query 'events[].message' --output text
```

Notes:
- AWS credentials can expire; refresh them via your org’s mechanism (SSO/device auth/etc.) and rerun the commands.
- Some jobs emit multiple streams (e.g. `algo-1`, `algo-2`). Increase `--max-items` to list more streams.

### Download Results

Outputs land under `OutputDataConfig.S3OutputPath`. Download them with the helper:

```bash
hv-ext results download s3://example-bucket/sagemaker-output/ \
  --dest-base-dir ./backtest-results \
  --from-date 2025-08-09 \
  --to-date 2025-08-09
```

Inspect `result.json` (metrics/timing), `hv.log` (Python orchestration), and per-stage logs (`<stage>/hotvect-offline-utils.log` and `<stage>/stdout-stderr.log`) alongside any model/prediction artifacts.

The canonical local layout produced by `hv-ext results download` is:

```
./backtest-results/
└── meta/
    └── <algorithm>@<version>(-<hyperparameter_version>)/
        └── last_test_date_YYYY-MM-DD/
            └── result.json
```

#### Canonical result pointers (recommended)

Hotvect backtests also write explicit S3 URIs into the SageMaker job hyperparameters. These are often the fastest way to locate results/logs (and they still exist even when the SageMaker model artifact is empty or the job fails early):

- `HyperParameters.s3_uri_result_file` – `result.json` (metrics + timings)
- `HyperParameters.s3_uri_metadata` – `meta/` directory (stage logs, effective algodef, etc.)
- `HyperParameters.s3_uri_algorithm_definition` – effective algorithm-definition JSON used for the run

Fetch them:

```bash
aws sagemaker describe-training-job --training-job-name <job> \
  --query '{status:TrainingJobStatus,result:HyperParameters.s3_uri_result_file,meta:HyperParameters.s3_uri_metadata,algo_def:HyperParameters.s3_uri_algorithm_definition,model:ModelArtifacts.S3ModelArtifacts}' \
  --output json
```

If `HyperParameters.s3_uri_custom_jar` is present, the job is running in **script-mode** (`custom.py`). In that mode, which files end up in SageMaker’s model artifact depends on the script-mode runner and `SAGEMAKER_TAR_INCLUDE_OUTPUT` / `SAGEMAKER_TAR_INCLUDE_METADATA`; prefer `s3_uri_result_file` + `s3_uri_metadata` for debugging and automation.

!!! note "When `s3_uri_result_file` 404s"
    If a job fails before it writes `result.json` (for example, an exception in `evaluate`), `HyperParameters.s3_uri_result_file` can still be set, but the object may not exist yet (S3 `NoSuchKey` / 404). In that case, use `HyperParameters.s3_uri_python_log_file` and `HyperParameters.s3_uri_metadata` to debug.

#### Interpreting `result.json`

`result.json` is a machine-readable summary produced by the backtest runner. Common patterns:

- **Offline/backtest metrics** live under `evaluate.*` (for example `evaluate.roc_auc.mean`).
- Some pipelines also embed a **baseline/online comparison block** under `evaluate.online.*` (for example `evaluate.online.algorithm.roc_auc.mean`).

For parity/regression checks, compare the offline block (`evaluate.*`) unless you explicitly intend to compare against the embedded baseline/online block.

##### 3-command recipe: fetch + print metrics via `s3_uri_result_file`

```bash
REGION=<aws-region> JOB=<training-job-name>

RESULT_URI=$(aws sagemaker describe-training-job --region "$REGION" --training-job-name "$JOB" \
  --query 'HyperParameters.s3_uri_result_file' --output text)

aws s3 cp "$RESULT_URI" - | python - <<'PY'
import json, sys

j = json.load(sys.stdin)
evaluate = j.get("evaluate") or {}

offline = {k: v for k, v in evaluate.items() if k != "online"} if isinstance(evaluate, dict) else evaluate
online = evaluate.get("online") if isinstance(evaluate, dict) else None

print("offline (evaluate.*):")
print(json.dumps(offline, indent=2, sort_keys=True))

print("\nembedded baseline/online (evaluate.online.*):")
print(json.dumps(online, indent=2, sort_keys=True))
PY
```

## Common Issues

### 1. Empty InputDataConfig
- **Symptom:** Training job fails with `FileNotFoundError`.
- **Fix:** Provide `--auto-attach-data-default-s3-base` or add explicit `s3_uri` entries to the algorithm definition.

### 2. Wrong S3 URI / Access Denied
- **Symptom:** `AccessDenied` or `NoSuchKey` errors.
- **Fix:** Verify the S3 path exists and the SageMaker role can read it; ensure the environment key matches the dictionary keys in your definition.

### 3. Version Mismatch
- **Symptom:** Training image rejects the algorithm JAR version.
- **Fix:** Update `training_container` to a hotvect training image that supports the algorithm JAR. Do not rebuild algorithms just to "match" hotvect versions.

### 4. Insufficient Resources
- **Symptom:** OOM or extremely slow training.
- **Fix:** Increase `InstanceType`, `InstanceCount`, or `VolumeSizeInGB` in the algorithm definition/override and rerun.

## Best Practices

- **Copy-first templates** – never edit `~/.hotvect/sagemaker-template.json` in place; work on a scratch copy per run.
- **Use FastFile + S3Prefix** – best performance for partitioned data.
- **Start small** – use overrides to reduce training days for smoke tests.
- **Monitor costs** – cap `MaxRuntimeInSeconds` and clean old outputs from S3.
- **Verify locally** – before large SageMaker runs, test with a tiny local dataset.

## Backtest Caching (Recommended)

When iterating on backtests, enable caching to avoid re-running expensive steps (state generation, encoding, and packaging predict parameters) across repeated runs.

**Important:** For SageMaker runs, set `--cache` to an `s3://example-bucket` prefix. A local cache path (e.g. `/tmp/cache`) only exists on the container filesystem and will not persist across jobs.

Example:
```bash
hv backtest \
  --git-reference v77.0.0 \
  --algo-repo-url https://github.com/example-org/example-algorithm.git \
  --output-base-dir /path/to/backtest-output \
  --scratch-dir /path/to/backtest-scratch \
  --last-test-time 2025-08-09 \
  --sagemaker-config /tmp/hv/sagemaker-config.json \
  --auto-attach-data-default-s3-base s3://example-bucket/tables/ \
  --cache s3://example-bucket/hotvect-cache/ \
  --cache-scope hyperparam
```

If you need to force a recompute (but still write fresh results back to cache), add:
```bash
--cache-refresh
```

See also: [Reuse existing outputs / caching](../reuse-outputs/index.md).

## Example End-to-End Workflow

```bash
# 1. Refresh AWS credentials (if needed)
aws sts get-caller-identity

# 2. Copy template into scratch space
cp ~/.hotvect/sagemaker-template.json /tmp/hv/sagemaker-config.json

# 3. Run SageMaker backtest
hv backtest \
  --git-reference v77.0.0 \
  --git-reference v64.4.0 \
  --algo-repo-url https://github.com/example-org/example-algorithm.git \
  --output-base-dir /path/to/backtest-output \
  --scratch-dir /path/to/backtest-scratch \
  --last-test-time 2025-08-09 \
  --sagemaker-config /tmp/hv/sagemaker-config.json \
  --auto-attach-data-default-s3-base s3://example-bucket/tables/ \
  --auto-attach-data-environment production \
  --algorithm-override overrides/2day.json

# 4. Track job progress
aws sagemaker describe-training-job --training-job-name hotvect-backtest-abc123

# 5. Download and compare results
hv-ext results download s3://example-bucket/sagemaker-output/ \
  --dest-base-dir ./backtest-results \
  --from-date 2025-08-09 \
  --to-date 2025-08-09

hv-ext metrics compare-quality \
  --output-base-dir ./backtest-results/meta \
  --control my-algorithm@1.0.0 \
  --treatment my-algorithm@1.0.1 \
  --from-test-date 2025-08-09 \
  --to-test-date 2025-08-09 \
  > comparison.json
```

## Additional Resources

- [Automatic SageMaker Configuration (Design)](../../design/sagemaker-configuration/index.md)
- [SageMaker InputDataConfig Solution (Design)](../../design/sagemaker-inputdataconfig/index.md)
- [FAQ](../../reference/faq/index.md) and [Troubleshooting](../../reference/troubleshooting/index.md) for general guidance
