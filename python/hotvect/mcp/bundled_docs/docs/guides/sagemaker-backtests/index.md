---
title: Run backtests on AWS SageMaker
description: Submit Hotvect backtests to SageMaker, verify the effective job contract, and retrieve results
tags: [backtest, sagemaker, aws, cloud, comparison, evaluation]
difficulty: intermediate
estimated_time: 30 minutes
prerequisites:
  - Algorithm repository accessible and buildable at the requested Git references
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
---

# Run backtests on AWS SageMaker

## What a SageMaker backtest submits

SageMaker backtests build each requested algorithm reference, submit its pipeline to managed compute, and write local
submission records for later verification. This guide covers:

- Preparing credentials and configuration templates
- Resolving instance settings and data channels from explicit configuration
- Monitoring jobs and retrieving results
- Troubleshooting common SageMaker pitfalls

Resource settings come from the explicit CLI, algorithm override, committed algorithm definition, and template layers.
`hv backtest` auto-attaches missing `InputDataConfig` channels from the effective definition.

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
  "RoleArn": "arn:aws:iam::123456789012:role/SageMakerExecutionRole",
  "AlgorithmSpecification": {
    "TrainingInputMode": "FastFile"
  },
  "ResourceConfig": {
    "InstanceType": "ml.m5.4xlarge",
    "InstanceCount": 1,
    "VolumeSizeInGB": 100
  },
  "HotvectSubmissionOptions": {
    "PreferredInstanceTypes": [
      "ml.m5.4xlarge",
      "ml.r6i.4xlarge"
    ]
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
    This file is a **template**. Hotvect sets `TrainingJobName` automatically. Prefer leaving `AlgorithmSpecification.TrainingImage` out of shared templates; the image should normally come from the algorithm definition's `sagemaker_training_job_definition.AlgorithmSpecification.TrainingImage`, an algorithm override JSON, or explicit CLI `--training-image`.

!!! warning "Template means fallback, not force-override"
    Editing `--sagemaker-config` changes the base job definition only. It does **not** override `InstanceType`, `VolumeSizeInGB`, or `MaxRuntimeInSeconds` that are already declared by the algorithm definition or by `--algorithm-override`.

!!! note "PreferredInstanceTypes vs InstanceType"
    Use `HotvectSubmissionOptions.PreferredInstanceTypes` when you want ordered capacity fallback. Hotvect materializes the first entry into `ResourceConfig.InstanceType` before submission. If you only want one fixed type, plain `ResourceConfig.InstanceType` is still valid. If both are present, they must agree on the first instance type.

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

Use regular mode unless you specifically need a payload to own startup behavior—for example, installing a separately
built runtime, adding project-specific Python dependencies, or launching a custom workload. Those steps are choices
inside `custom.py`; Hotvect does not supply a runtime-upgrade or wheelhouse protocol.

Important practical point:

- `hv backtest` does **not** currently build or upload a script-mode payload for you. Script-mode only happens if your SageMaker job definition/template already includes `HyperParameters.s3_uri_custom_jar` or another launcher injects it.

For the exact script-mode contract and a payload-owned upgrade pattern, see [Use a custom SageMaker payload](../sagemaker-upgrade-custom-py/index.md).

## Automatic Resource Configuration

Hotvect reads resource requirements directly from algorithm metadata (and optional overrides). For typical backtests, you don't need CLI flags to tweak instance types; declare them in the algorithm definition (or a versioned override file).

### How It Works

Precedence is simple for most fields: **CLI flags > algorithm override JSON > committed algorithm definition > template default**. `AlgorithmSpecification.TrainingImage` follows the same rule, but reusable templates should normally omit it. Use `sagemaker_training_job_definition.AlgorithmSpecification.TrainingImage` for the committed image, an algorithm override JSON for reproducible per-run changes, and `--training-image` as the highest-precedence explicit override.

1. **Template** – Provides fallbacks for shared settings such as `RoleArn`, VPC config, and baseline `ResourceConfig`.
2. **Algorithm definition** – Supplies algorithm-owned SageMaker settings in `sagemaker_training_job_definition`.
3. **Algorithm override JSON** – `--algorithm-override` mutates the definition for a given git reference, so you can experiment without touching git history.

If you only change the template from `ml.m5.12xlarge` to `ml.r5.8xlarge`, references that already declare `ml.m5.12xlarge` keep using `ml.m5.12xlarge`. To make the per-run change win, put it in `--algorithm-override`.

### Algorithm Declaration

Declare resource needs inside `algorithm-definition.json`:

```json
{
  "algorithm_name": "my-algorithm",
  "algorithm_version": "1.0.0",
  "sagemaker_training_job_definition": {
    "AlgorithmSpecification": {
      "TrainingImage": "registry.opensource.example/hotvect:<HOTVECT_VERSION>"
    },
    "HotvectSubmissionOptions": {
      "PreferredInstanceTypes": [
        "ml.m5.12xlarge",
        "ml.r6i.8xlarge",
        "ml.r5.8xlarge"
      ]
    },
    "ResourceConfig": {
      "VolumeSizeInGB": 150
    },
    "StoppingCondition": {
      "MaxRuntimeInSeconds": 15000
    }
  }
}
```

Parent algorithms own these fields; children inherit the parent’s choice. When `PreferredInstanceTypes` is present, the first entry becomes the submitted `ResourceConfig.InstanceType`. If you do not need retries, setting `ResourceConfig.InstanceType` alone remains valid.

### Algorithm Overrides

Need a one-off experiment? Supply a JSON override file:

```json
{
  "sagemaker_training_job_definition": {
    "AlgorithmSpecification": {
      "TrainingImage": "registry.opensource.example/hotvect:<HOTVECT_VERSION>"
    },
    "HotvectSubmissionOptions": {
      "PreferredInstanceTypes": [
        "ml.c5.24xlarge",
        "ml.m5.12xlarge"
      ]
    },
    "StoppingCondition": {
      "MaxRuntimeInSeconds": 21600
    }
  }
}
```

Attach it with `--algorithm-override overrides/experiment.json`. Overrides line up with git references in positional order, so you can vary resource allocations per version in a single `hv backtest` invocation.

This is the correct surface for "I want this run to use a different instance type than what is committed in git." Editing the SageMaker template is only enough when the algorithm does not already own that field.

### SageMaker Job Definition Overrides

For advanced customization (tags, metric definitions, VPC config, capacity fallback) embed a partial `sagemaker_training_job_definition`. Hotvect applies those algorithm-owned settings above the template, and applies an algorithm override JSON above the committed definition.

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

Whenever you run `hv backtest` in SageMaker mode (for example, with `--sagemaker` and `--sagemaker-config`), the framework:

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

That fallback order applies to dependency data specifications. `prediction_spec.s3_uri` and
`prediction_spec.output_uri` deliberately use a stricter contract: when either is an environment map, the
`data_environment` key must match exactly. Hotvect does not lowercase, alias, or select another map entry for explicit
prediction input or output. See [Algorithm definitions](../../reference/algorithm-definition/index.md#prediction_spec).

### Channel deduplication

Within one submitted job, channels are keyed by `data_prefix`. Repeated dependencies with the same channel name do not
create duplicate entries. Each git reference still receives its own job-specific copy of the configuration.

## Running SageMaker Backtests

### Automatic InputDataConfig (default)

```bash
hv backtest \
  --git-reference v2.0.0 \
  --git-reference v1.0.0 \
  --algo-repo-url https://github.com/example-org/example-algorithm.git \
  --output-base-dir /local/output \
  --scratch-dir /local/scratch \
  --last-test-time 2000-01-08 \
  --sagemaker \
  --sagemaker-job-prefix example-backtest \
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
5. Each CLI invocation writes a run-scoped submission snapshot under `<output-base-dir>/meta/_backtest_submissions/<run_id>/`.

### Local Submission Metadata

Every SageMaker backtest invocation creates a fresh metadata directory:

```text
<output-base-dir>/meta/_backtest_submissions/<run_id>/
  backtest_submission_manifest.json
  backtest_submission_status.json
```

- `backtest_submission_manifest.json` contains the full per-job submission payload, including git reference, parameter version, and expected S3 locations.
- `backtest_submission_status.json` is a smaller submission-time snapshot for quick inspection. It is **not** a live SageMaker polling result.
- `<run_id>` is unique per CLI invocation, so repeated backtests in the same `--output-base-dir` keep their own history instead of overwriting earlier submission files.

Because these local submission manifests live under `--output-base-dir`, that flag stays required in SageMaker mode. `--data-base-dir` is optional for SageMaker backtests because the actual training/test inputs come from SageMaker channels.

### Manual InputDataConfig (rare)

If you must handcraft channels (e.g., manifest files or bespoke input modes), copy the template to a scratch file, edit `InputDataConfig`, and pass that file via `--sagemaker-config`. The framework leaves existing channels untouched and only adds missing ones.

Do not rely on this scratch-template edit to override algorithm-owned resource settings. For per-run resource changes, use `--algorithm-override`.

## Monitoring Jobs

### View Job Status

Use the AWS Console or CLI:

```bash
aws sagemaker list-training-jobs --sort-by CreationTime --sort-order Descending --max-results 10
aws sagemaker describe-training-job --training-job-name hotvect-backtest-abc123
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
  --from-date 2000-01-08 \
  --to-date 2000-01-08
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

If `HyperParameters.s3_uri_custom_jar` is present, the job is running in **script mode** (`custom.py`). Artifact output is
then defined by that payload: the paths above can be present even when no payload writes to them. `SAGEMAKER_TAR_INCLUDE_OUTPUT`
and `SAGEMAKER_TAR_INCLUDE_METADATA` matter only if the payload invokes the normal `SagemakerAlgorithmPipelineRebuilder`.
For a custom payload, inspect its own logging and artifact contract first.

!!! note "When `s3_uri_result_file` 404s"
    If a job fails before it writes `result.json` (for example, an exception in `evaluate`), `HyperParameters.s3_uri_result_file` can still be set, but the object may not exist yet (S3 `NoSuchKey` / 404). In that case, use `HyperParameters.s3_uri_python_log_file` and `HyperParameters.s3_uri_metadata` to debug.

#### Interpreting `result.json`

`result.json` is a machine-readable summary produced by the backtest runner. Common patterns:

- **Offline/backtest metrics** live under `evaluate.*` as estimates (for example `evaluate.roc_auc.value`, with optional
  `ci95_lower` and `ci95_upper`).
- Some pipelines also embed a comparison block under `evaluate.online.*` (for example
  `evaluate.online.<view>.roc_auc.value`, again with optional confidence bounds).

Current evaluation output uses the structured estimate, so consumers should read `.value`.

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
- **Fix:** Update `sagemaker_training_job_definition.AlgorithmSpecification.TrainingImage` to a Hotvect training image that supports the algorithm JAR. Do not rebuild algorithms just to "match" Hotvect versions.

### 4. Insufficient memory or disk
- **OOM:** Choose an instance type with more memory, or reduce the algorithm's per-process memory demand.
- **Disk full:** Increase `ResourceConfig.VolumeSizeInGB`.
- **Distributed training:** Increase `InstanceCount` only when the declared training command explicitly supports
  multi-instance execution.

## Best Practices

- **Use FastFile + S3Prefix when compatible** – this is the normal partitioned-input mode; benchmark alternatives if
  input mode is part of the performance question.
- **Start small** – use overrides to reduce training days for smoke tests.
- **Monitor costs** – cap `MaxRuntimeInSeconds` and clean old outputs from S3.
- **Verify locally** – before large SageMaker runs, test with a tiny local dataset.

## Backtest Caching (Recommended)

When iterating on backtests, enable caching to avoid re-running expensive steps (state generation, encoding, and packaging predict parameters) across repeated runs.

**Important:** For SageMaker runs, set `--cache` to an `s3://...` prefix. A local cache path (e.g. `/tmp/cache`) only exists on the container filesystem and will not persist across jobs.

Example:
```bash
hv backtest \
  --git-reference v2.0.0 \
  --algo-repo-url https://github.com/example-org/example-algorithm.git \
  --output-base-dir /path/to/backtest-output \
  --scratch-dir /path/to/backtest-scratch \
  --last-test-time 2000-01-08 \
  --sagemaker \
  --sagemaker-job-prefix example-cache-backtest \
  --sagemaker-config /tmp/hv/sagemaker-config.json \
  --auto-attach-data-default-s3-base s3://example-bucket/tables/ \
  --cache s3://example-bucket/hotvect-cache/ \
  --cache-scope hyperparam
```

If you need to force a run-level recompute (but still write fresh run-level results back to cache), use an effective
`cache_base_dir`, set `hotvect_execution_parameters.cache="run"` in the algorithm definition/override, and add:
```bash
--cache-refresh
```

See also: [Reuse existing outputs / caching](../reuse-outputs/index.md).

## Example End-to-End Workflow

```bash
# 1. Refresh AWS credentials (if needed)
aws sts get-caller-identity

# 2. Run SageMaker backtest
hv backtest \
  --git-reference v2.0.0 \
  --git-reference v1.0.0 \
  --algo-repo-url https://github.com/example-org/example-algorithm.git \
  --output-base-dir /path/to/backtest-output \
  --scratch-dir /path/to/backtest-scratch \
  --last-test-time 2000-01-08 \
  --sagemaker \
  --sagemaker-job-prefix example-backtest \
  --sagemaker-config ~/.hotvect/sagemaker-template.json \
  --auto-attach-data-default-s3-base s3://example-bucket/tables/ \
  --auto-attach-data-environment production \
  --algorithm-override overrides/2day.json

# 3. Track job progress
aws sagemaker describe-training-job --training-job-name hotvect-backtest-abc123

# 4. Download and compare results
hv-ext results download s3://example-bucket/sagemaker-output/ \
  --dest-base-dir ./backtest-results \
  --from-date 2000-01-08 \
  --to-date 2000-01-08

hv-ext metrics compare-quality \
  --output-base-dir ./backtest-results/meta \
  --control my-algorithm@1.0.0 \
  --treatment my-algorithm@1.0.1 \
  --from-test-date 2000-01-08 \
  --to-test-date 2000-01-08 \
  > comparison.json
```

## Additional Resources

- [Automatic SageMaker Configuration (Design)](../../design/sagemaker-configuration/index.md)
- [SageMaker InputDataConfig Solution (Design)](../../design/sagemaker-inputdataconfig/index.md)
- [FAQ](../../reference/faq/index.md) and [Troubleshooting](../../reference/troubleshooting/index.md) for general guidance
