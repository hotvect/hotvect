# SageMaker Backtest Guide

## Overview

This guide explains how to run hotvect backtests on AWS SageMaker instead of locally. SageMaker provides scalable compute resources for training ML models, making it ideal for large-scale backtests that would be too slow or resource-intensive to run on a local machine.

## Prerequisites

### 1. AWS Credentials

You need AWS credentials with permission to:
- Submit SageMaker training jobs
- Read from S3 data buckets
- Write to S3 output buckets

```bash
# Login to AWS (adjust role/account as needed)
zalando-aws-cli login my-team-data ReadOnly
```

### 2. SageMaker Configuration Template

Create a SageMaker training job configuration template at `~/.hotvect/sagemaker-template.json`:

```json
{
  "TrainingImage": "registry.opensource.zalan.do/myteam/hotvect:9.31.2",
  "RoleArn": "arn:aws:iam::123456789012:role/SageMakerExecutionRole",
  "ResourceConfig": {
    "InstanceType": "ml.m5.4xlarge",
    "InstanceCount": 1,
    "VolumeSizeInGB": 100
  },
  "StoppingCondition": {
    "MaxRuntimeInSeconds": 86400
  },
  "OutputDataConfig": {
    "S3OutputPath": "s3://my-bucket/sagemaker-output/"
  },
  "InputDataConfig": []
}
```

**CRITICAL**: The `InputDataConfig` must be populated with your algorithm's data dependencies (see section below).

### 3. Hotvect Configuration

Your `~/.hotvect/config.json` should contain SageMaker-related paths:

```json
{
  "directories": {
    "data_base_dir": "/local/path/to/data",
    "output_base_dir": "/local/path/to/output",
    "scratch_dir": "/local/path/to/scratch"
  },
  "sagemaker": {
    "sagemaker_config_template": "~/.hotvect/sagemaker-template.json",
    "default_s3_data_base_dir": "s3://my-bucket/tables/"
  }
}
```

## Understanding InputDataConfig

### What is InputDataConfig?

`InputDataConfig` is a list of **data channels** that SageMaker mounts into the training container. Each channel specifies:
- **ChannelName**: Logical name for the data (e.g., `training_data`, `test_data`)
- **S3Uri**: S3 location of the data
- **S3DataType**: Either `S3Prefix` (directory) or `ManifestFile` (list of files)
- **InputMode**: How data is accessed (`File` or `FastFile`)

### Why Must InputDataConfig Be Populated?

**The training container cannot access S3 directly during training.** All data must be:
1. Specified in `InputDataConfig` before job submission
2. Downloaded by SageMaker into the container's `/opt/ml/input/data/{ChannelName}/` directory
3. Accessed by the training code from the local filesystem

**If `InputDataConfig: []` is empty**, the training job will start successfully but **fail when trying to access data** because no files are mounted.

### Data Channel Requirements

Your algorithm's data dependencies (defined in `algorithm-definition.json`) determine what channels are needed. For example:

**Algorithm Definition:**
```json
{
  "dependencies": [
    {
      "data_prefix": "my_data_train",
      "number_of_training_days": 7,
      "training_lag_days": 1,
      "data_type": "training_data"
    },
    {
      "data_prefix": "my_data_test",
      "number_of_training_days": 1,
      "training_lag_days": 1,
      "data_type": "test_data"
    }
  ]
}
```

**Required InputDataConfig:**
```json
{
  "InputDataConfig": [
    {
      "ChannelName": "my_data_train",
      "DataSource": {
        "S3DataSource": {
          "S3DataType": "S3Prefix",
          "S3Uri": "s3://my-bucket/tables/my_data_train/"
        }
      },
      "InputMode": "FastFile"
    },
    {
      "ChannelName": "my_data_test",
      "DataSource": {
        "S3DataSource": {
          "S3DataType": "S3Prefix",
          "S3Uri": "s3://my-bucket/tables/my_data_test/"
        }
      },
      "InputMode": "FastFile"
    }
  ]
}
```

### S3Prefix vs ManifestFile

**S3Prefix** (recommended):
- Mounts all files under the S3 prefix (directory)
- SageMaker handles date-based partitioning automatically
- Example: `s3://bucket/tables/training_data/` includes all `dt=YYYY-MM-DD/` subdirectories

**ManifestFile**:
- Explicitly lists individual S3 file URIs
- More control but requires maintenance
- Use only when you need to cherry-pick specific files

### InputMode: File vs FastFile

**FastFile** (recommended):
- Streams data on-demand during training
- Lower startup latency
- Reduces EBS volume requirements
- Default for hotvect backtests

**File**:
- Downloads all data before training starts
- Higher startup latency
- Use when algorithm needs random access to files

## Running SageMaker Backtests

### Method 1: Automatic InputDataConfig Population (Recommended)

Use the `--auto-attach-data` flag to automatically populate `InputDataConfig` from algorithm dependencies:

```bash
hv backtest \
  --git-reference v77.0.0 \
  --git-reference v64.4.0 \
  --algo-repo-url https://github.com/myorg/my-algorithm.git \
  --output-base-dir /local/output \
  --scratch-dir /local/scratch \
  --last-test-time 2025-08-09 \
  --sagemaker-config ~/.hotvect/sagemaker-template.json \
  --auto-attach-data \
  --auto-attach-data-default-s3-base s3://my-bucket/tables/ \
  --auto-attach-data-environment production
```

**What this does:**
1. Clones each git reference and builds algorithm JARs
2. Extracts data dependencies from algorithm definitions
3. Resolves S3 URIs for each dependency (see resolution priority below)
4. Populates `InputDataConfig` with required channels
5. Submits SageMaker training jobs with populated config

**Flags:**
- `--auto-attach-data`: Enable automatic population
- `--auto-attach-data-default-s3-base`: Default S3 base URI used when a dependency lacks explicit `s3_uri` (required in that scenario)
- `--auto-attach-data-environment`: Environment key for multi-environment S3 URIs (`production` or `test`, default: `production`)

### Method 2: Manual InputDataConfig Population

If you prefer manual control or have complex requirements:

**Step 1: Copy template to working directory**
```bash
cp ~/.hotvect/sagemaker-template.json ./my-backtest-config.json
```

**Step 2: Analyze algorithm dependencies**

You can inspect your algorithm's data dependencies:

```bash
# Option A: Read algorithm-definition.json from built JAR
unzip -p ~/.m2/repository/org/myorg/my-algorithm/77.0.0/my-algorithm-77.0.0.jar \
  my-algorithm-algorithm-definition.json | jq '.dependencies'

# Option B: Use show-data-dependency command (outputs JSON)
hv-ext show-data-dependency \
  --repo-url https://github.com/myorg/my-algorithm.git \
  --git-reference v77.0.0 \
  --scratch-dir /tmp/analyze \
  --last-test-time 2025-08-09 \
  -o dependencies.json

cat dependencies.json | jq
```

**Step 3: Populate InputDataConfig manually**

Edit `my-backtest-config.json` and add channels for each dependency:

```json
{
  "TrainingImage": "...",
  "RoleArn": "...",
  "InputDataConfig": [
    {
      "ChannelName": "training_data",
      "DataSource": {
        "S3DataSource": {
          "S3DataType": "S3Prefix",
          "S3Uri": "s3://my-bucket/tables/training_data/"
        }
      },
      "InputMode": "FastFile"
    },
    {
      "ChannelName": "test_data",
      "DataSource": {
        "S3DataSource": {
          "S3DataType": "S3Prefix",
          "S3Uri": "s3://my-bucket/tables/test_data/"
        }
      },
      "InputMode": "FastFile"
    }
  ]
}
```

**Step 4: Run backtest with manual config**

```bash
hv backtest \
  --git-reference v77.0.0 \
  --git-reference v64.4.0 \
  --algo-repo-url https://github.com/myorg/my-algorithm.git \
  --output-base-dir /local/output \
  --scratch-dir /local/scratch \
  --last-test-time 2025-08-09 \
  --sagemaker-config ./my-backtest-config.json
```

## S3 URI Resolution Priority

When using `--auto-attach-data`, S3 URIs are resolved in the following priority order:

### 1. Explicit s3_uri in Algorithm Definition (Highest Priority)

**String format:**
```json
{
  "data_prefix": "training_data",
  "additional_properties": {
    "s3_uri": "s3://specific-bucket/custom-path/training_data/"
  }
}
```

**Environment map format:**
```json
{
  "data_prefix": "training_data",
  "additional_properties": {
    "s3_uri": {
      "production": "s3://prod-bucket/tables/training_data/",
      "test": "s3://test-bucket/tables/training_data/"
    }
  }
}
```

The `--auto-attach-data-environment` flag selects which key to use. If the requested environment is not found, it falls back to: `production` → `prod` → `test` → `staging` → first available key.

### 2. Default S3 Base + data_prefix (Fallback)

If no explicit `s3_uri` is specified, constructs URI as:
```
{default_s3_base}/{data_prefix}/
```

Example:
- Default base: `s3://my-bucket/tables/`
- Data prefix: `training_data`
- Result: `s3://my-bucket/tables/training_data/`

Provide the default base via the `--auto-attach-data-default-s3-base` flag.

### 3. Fail Loudly (No Fallback)

If neither explicit `s3_uri` nor default S3 base is available, the backtest **fails immediately** with:

```
ValueError: Cannot resolve S3 URI for channel 'training_data'.
No explicit s3_uri in algorithm definition and no --auto-attach-data-default-s3-base provided.
```

**This is intentional** - no defensive fallback behavior. Fix the issue by either:
- Adding `s3_uri` to algorithm definition's `additional_properties`
- Providing `--auto-attach-data-default-s3-base` flag

## Channel Deduplication

When multiple git references have the same data dependency, channels are deduplicated by `data_prefix` (ChannelName).

**Example:**
- v77.0.0 depends on `training_data` for dates 2025-08-02 to 2025-08-08
- v64.4.0 depends on `training_data` for dates 2025-08-02 to 2025-08-08

**Result:** Only ONE channel created:
```json
{
  "ChannelName": "training_data",
  "DataSource": {
    "S3DataSource": {
      "S3DataType": "S3Prefix",
      "S3Uri": "s3://bucket/tables/training_data/"
    }
  }
}
```

The `S3Prefix` type automatically includes all date subdirectories under the prefix, so there's no need to create separate channels for different date ranges.

## Monitoring SageMaker Jobs

### View Job Status

SageMaker training jobs run asynchronously. Monitor progress via:

**AWS Console:**
1. Navigate to SageMaker → Training jobs
2. Find job by name (includes algorithm version and timestamp)
3. View logs in CloudWatch

**AWS CLI:**
```bash
# List recent training jobs
aws sagemaker list-training-jobs --sort-by CreationTime --sort-order Descending --max-results 10

# Get job details
aws sagemaker describe-training-job --training-job-name my-job-name

# Stream logs
aws logs tail /aws/sagemaker/TrainingJobs --follow --log-stream-name my-job-name
```

### Download Results

After jobs complete, download results from S3:

```bash
hv-ext download-results \
  --s3-base-prefix s3://my-bucket/sagemaker-output/ \
  --dest-base-dir ./backtest-results \
  --from-date 2025-08-01 \
  --to-date 2025-08-09
```

Results include:
- `result.json`: Evaluation metrics and timing info
- `model.parameters`: Trained model weights
- `prediction.jsonl`: Predictions on test data

## Common Issues

### Issue 1: Empty InputDataConfig

**Symptom:** Training job starts but fails with "FileNotFoundError: No such file or directory"

**Cause:** `InputDataConfig: []` is empty - no data channels specified

**Fix:** Use `--auto-attach-data` flag or manually populate `InputDataConfig`

### Issue 2: Wrong S3 URI

**Symptom:** Training job fails with "Access Denied" or "NoSuchKey"

**Cause:** S3 URI doesn't exist or role lacks permissions

**Fix:**
- Verify S3 paths exist: `aws s3 ls s3://bucket/path/`
- Check SageMaker role has S3 read permissions
- Verify bucket and data are in same AWS region as SageMaker job

### Issue 3: Missing Data Dependencies

**Symptom:** Training succeeds but evaluation metrics are 0.0 or NaN

**Cause:** Algorithm expected certain data channels but they weren't mounted

**Fix:**
- Inspect algorithm definition: `unzip -p algorithm.jar algorithm-algorithm-definition.json | jq '.dependencies'`
- Ensure every dependency has a corresponding InputDataConfig channel
- Use `--auto-attach-data` to populate automatically

### Issue 4: Version Mismatch

**Symptom:** Training job fails with "Unsupported hotvect version" or encoding errors

**Cause:** Algorithm JAR built with different hotvect version than training image

**Fix:**
- **Rebuild algorithm JAR** with correct hotvect version: `mvn clean install -DskipTests`
- Or **use training image matching algorithm's hotvect version**
- Check versions: `grep hotvect.version pom.xml` vs `TrainingImage` tag

### Issue 5: Insufficient Resources

**Symptom:** Training job fails with "OutOfMemory" or runs extremely slowly

**Cause:** Instance type too small for dataset size

**Fix:**
- Increase `InstanceType` in SageMaker config (e.g., `ml.m5.4xlarge` → `ml.m5.12xlarge`)
- Increase `VolumeSizeInGB` if data doesn't fit
- Use algorithm override to reduce `number_of_training_days`

## Best Practices

### 1. Always Use Copy-First Pattern

**Never modify `~/.hotvect/sagemaker-template.json` directly.** Always:
```bash
cp ~/.hotvect/sagemaker-template.json ./my-config.json
# Edit my-config.json
hv backtest --sagemaker-config ./my-config.json ...
```

### 2. Use S3Prefix with FastFile

For date-partitioned data (`dt=YYYY-MM-DD/`), use:
- `S3DataType: S3Prefix` (not ManifestFile)
- `InputMode: FastFile` (not File)

This provides best performance and handles date ranges automatically.

### 3. Start with Small Datasets

For initial testing, use algorithm overrides to reduce training data:

```json
{
  "dependencies": {
    "my-algorithm": {
      "number_of_training_days": 2
    }
  }
}
```

```bash
hv backtest \
  --algorithm-override 2day-override.json \
  --sagemaker-config ./config.json \
  ...
```

### 4. Monitor Costs

SageMaker charges by instance-hour. For cost efficiency:
- Use smallest instance type that fits your data
- Set reasonable `MaxRuntimeInSeconds` to prevent runaway jobs
- Delete old output artifacts from S3 regularly

### 5. Verify Before Submission

Before expensive SageMaker jobs, verify locally:

```bash
# Test with small sample locally
hv train \
  --algorithm-name my-algorithm \
  --data-base-dir /local/data \
  --output-base-dir /local/output \
  --algorithm-jar algorithm.jar \
  --last-test-time 2025-08-09 \
  --algorithm-override 2day-override.json
```

If local training works, SageMaker should work too.

## Example: Complete Backtest Workflow

```bash
# 1. Ensure AWS credentials are valid
zalando-aws-cli login my-team-data ReadOnly

# 2. Verify configuration
cat ~/.hotvect/config.json | jq '.sagemaker'

# 3. Run backtest with auto-attach
hv backtest \
  --git-reference v77.0.0 \
  --git-reference v64.4.0 \
  --algo-repo-url https://github.com/my-org/my-algorithm.git \
  --output-base-dir /local/path/to/backtest-output \
  --scratch-dir /local/path/to/backtest-scratch \
  --last-test-time 2025-08-09 \
  --sagemaker-config ~/.hotvect/sagemaker-template.json \
  --auto-attach-data \
  --auto-attach-data-default-s3-base s3://my-bucket/tables/ \
  --auto-attach-data-environment production \
  --algorithm-override 2day-override.json

# 4. Monitor jobs (job names printed during submission)
aws sagemaker describe-training-job --training-job-name my-algorithm-77-0-0-20250809-123456

# 5. Wait for completion (or monitor in AWS Console)

# 6. Download results
hv-ext download-results \
  --s3-base-prefix s3://my-experiment-bucket/temp/username/sagemaker_output/ \
  --dest-base-dir ./backtest-results \
  --from-date 2025-08-09 \
  --to-date 2025-08-09

# 7. Compare evaluations
hv-ext compare-evaluations \
  backtest-results/my-algorithm@64.4.0/result.json \
  backtest-results/my-algorithm@77.0.0/result.json
```

## Additional Resources

- **Hotvect Documentation**: See `hotvect-claude-code-plugin/docs/` for detailed guides
- **Algorithm Definition Reference**: `ALGORITHM_DEFINITION_ARGUMENT.md`
- **Config Protection Policy**: `CONFIG_PROTECTION_POLICY.md`
- **Command Reference**: `hotvect-claude-code-plugin/HV_COMMAND_REFERENCE.md`
- **AWS SageMaker Documentation**: https://docs.aws.amazon.com/sagemaker/
