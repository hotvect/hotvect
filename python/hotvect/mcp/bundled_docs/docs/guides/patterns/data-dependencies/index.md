---
title: Data dependencies
description: Resolve Hotvect data dependencies, partition dates, and local download plans
tags: [patterns, data, dependencies, training, dates, calculation]
difficulty: intermediate
estimated_time: 15 minutes
prerequisites:
  - Understanding of hotvect training workflow
  - Knowledge of parent-child algorithm pattern
  - Familiarity with partitioned data formats
related_docs:
  - ../parent-child/index.md
  - ../override-files/index.md
  - ../../../reference/faq/index.md
  - ../../../reference/cli/index.md
related_commands:
  - hv train
  - hv-ext data-dependency
---

# Data dependencies

Hotvect algorithm definitions declare train, test, prediction, and source-data inputs. Resolve them with the same
target, override, and `last_test_time` as the intended run; changing any of those inputs can change the dependency
plan.

## Data Dependency Specification

### Basic Structure

```json
{
  "train_data_spec": {
    "data_prefix": "example_training_data",
    "s3_uri": {"production": "s3://example-bucket/tables/"}
  },
  "test_data_spec": {
    "data_prefix": "example_test_data",
    "s3_uri": {"production": "s3://example-bucket/test-data/"}
  },
  "number_of_training_days": 7,
  "training_lag_days": 1
}
```

For explicit post-train inference, algorithms may also declare:

```json
{
  "prediction_spec": {
    "data_prefix": "example_prediction_input",
    "number_of_days": 1,
    "lag_days": 0,
    "s3_uri": {
      "production": "s3://example-bucket/tables/example_prediction_input/"
    },
    "output_uri": {
      "production": "s3://example-bucket-output/predictions/{{ parameter_version }}/dt={{ last_test_date }}/"
    }
  }
}
```

For `prediction_spec`, environment maps are strict:

- `prediction_spec.s3_uri` and `prediction_spec.output_uri` must match the selected `data_environment` exactly
- there is no case folding, alias mapping, or fallback to another entry

### Key Fields

| Field | Description | Example |
|-------|-------------|---------|
| `train_data_spec.data_prefix` | Training data directory name under `data_base_dir` | `example_training_data` |
| `test_data_spec.data_prefix` | Test data directory name under `data_base_dir` | `example_test_data` |
| `prediction_spec.data_prefix` | Non-test prediction input directory name used by `hv train --target predict` | `example_prediction_input` |
| `number_of_training_days` | How many days of training partitions to read | `7` |
| `training_lag_days` | Days to lag the end of training from `last_test_time` | `1` |
| `train_data_spec.s3_uri.production` | Optional S3 location for downloads | `s3://example-bucket/tables/` |
| `prediction_spec.output_uri.production` | Final destination for prediction artifacts from `target=predict` | `s3://example-bucket-output/predictions/...` |

## Date Calculation Formula

### Training Data Dates

```
start_date = last_test_time - training_lag_days - (number_of_training_days - 1)
end_date = last_test_time - training_lag_days

Required dates: [start_date, start_date+1, ..., end_date]
```

### Examples

#### Example 1: Standard 7-Day Training

**Given**:
- `last_test_time`: 2000-01-08
- `training_lag_days`: 1
- `number_of_training_days`: 7

**Calculation**:
```
end_date = 2000-01-08 - 1 = 2000-01-07
start_date = 2000-01-07 - (7 - 1) = 2000-01-01

Required dates: 2000-01-01, 2000-01-02, ..., 2000-01-07 (7 days)
```

#### Example 2: Fast 2-Day Training (Override)

**Given**:
- `last_test_time`: 2000-01-08
- `training_lag_days`: 1
- `number_of_training_days`: 2 (from override)

**Calculation**:
```
end_date = 2000-01-08 - 1 = 2000-01-07
start_date = 2000-01-07 - (2 - 1) = 2000-01-06

Required dates: 2000-01-06, 2000-01-07 (2 days)
```

### Test Data Dates

Test data typically uses `last_test_time` directly (no lag):

```
test_date = last_test_time
```

### Prediction Data Dates (`prediction_spec`)

When `hv train --target predict` is used, prediction input dates come from `prediction_spec`:

```
prediction_start = last_test_time - lag_days
prediction_dates = [prediction_start, prediction_start-1, ...]  # for number_of_days partitions
```

Example:

- `last_test_time`: `2000-01-08`
- `prediction_spec.number_of_days`: `1`
- `prediction_spec.lag_days`: `0`

Result:

- prediction input date = `2000-01-08`

## Data Directory Structure

### Expected Layout

```
data-base-dir/
├── data_prefix_1/
│   ├── dt=2000-01-01/
│   │   ├── part-00000.json.gz
│   │   ├── part-00001.json.gz
│   │   ├── # or extensionless Spark text shards like part-00000 / part-00000.gz
│   │   └── ...
│   ├── dt=2000-01-02/
│   │   └── ...
│   └── ...
├── test_data_prefix/
│   └── dt=2000-01-08/
│       └── ...
└── ...
```

### Partition Format

Data must be partitioned by date with format: `dt=YYYY-MM-DD`

**Valid**:
- `dt=2000-01-07/`
- `dt=2000-02-01/`

**Invalid**:
- `2000-01-07/` (missing `dt=` prefix)
- `dt=20000107/` (wrong date format)
- `date=2000-01-07/` (wrong partition name)

## Downloading Data

### Automated Download with hv-ext

The recommended way to list and download data:

```bash
# List dependencies as JSON (default, safe - no download)
hv-ext data-dependency \
  --repo-url https://github.com/example-org/example-algorithm.git \
  --git-reference v2.0.0 \
  --s3-base-dir s3://example-bucket/tables \
  --local-data-dir /path/to/data \
  --scratch-dir ./temp \
  --last-test-time 2000-01-08

# Download all dependencies
hv-ext data-dependency --download-all \
  --repo-url https://github.com/example-org/example-algorithm.git \
  --git-reference v2.0.0 \
  --s3-base-dir s3://example-bucket/tables \
  --local-data-dir /path/to/data \
  --scratch-dir ./temp \
  --last-test-time 2000-01-08

# Download specific dependency with sampling
hv-ext data-dependency --download example_training_data \
  --repo-url https://github.com/example-org/example-algorithm.git \
  --git-reference v2.0.0 \
  --s3-base-dir s3://example-bucket/tables \
  --local-data-dir /path/to/data \
  --scratch-dir ./temp \
  --last-test-time 2000-01-08 \
  --sample-ratio 0.01
```

**What it does**:
1. Clones algorithm repo
2. Builds algorithm JAR
3. Reads algorithm-definition.json
4. Calculates required dates
5. Lists or downloads data from S3 (depending on flags)
6. Organizes in correct directory structure

**Options**:
- `--download-all`: Download all dependencies (required to download)
- `--download <name>`: Download specific dependency (repeatable)
- `--sample-ratio 0.01`: Download 1% of files for testing
- `--role-arn`: Assume AWS role for S3 access

### Manual Download

For specific dates:

```bash
# Ensure AWS credentials are configured for your environment.
# (Org-specific: SSO, device auth, credential helper, etc.)
aws sts get-caller-identity

# Download single date
aws s3 sync \
  s3://example-bucket/tables/example_training_data/dt=2000-01-07 \
  /path/to/data/example_training_data/dt=2000-01-07

# Download multiple dates
for date in 2000-01-01 2000-01-02 2000-01-03 2000-01-04 2000-01-05 2000-01-06 2000-01-07; do
  aws s3 sync \
    s3://example-bucket/tables/example_training_data/dt=$date \
    /path/to/data/example_training_data/dt=$date
done
```

## Multiple Data Dependencies

In addition to `train_data_spec` / `test_data_spec`, algorithms can declare extra data under `source_data`.

```json
{
  "train_data_spec": {"data_prefix": "example_training_data"},
  "test_data_spec": {"data_prefix": "example_test_data"},
  "number_of_training_days": 7,
  "training_lag_days": 1,
  "source_data": {
    "context_records": {
      "data_prefix": "example_context_records",
      "number_of_days": 1,
      "lag_days": 0
    },
    "reference_data": {
      "data_prefix": "example_reference_data",
      "number_of_days": 1,
      "lag_days": 1
    },
    "static_lookup": {
      "data_prefix": "example_static_lookup"
    }
  }
}
```

## `target=predict` uses `prediction_spec`, not `test_data_spec`

When the pipeline target is `predict`:

- Hotvect reads prediction input from `prediction_spec`
- Hotvect does **not** silently fall back to `test_data_spec`
- `AlgorithmPipeline.data_dependencies()` reports the `prediction_spec` input dependency instead of the test dependency
- in SageMaker mode, `--auto-attach-data` will therefore mount the prediction input channel rather than the test slice

The final prediction artifact is published to `prediction_spec.output_uri`; metadata and `result.json` still remain
under the normal hv-managed output directory.

Each dependency has its own:
- `data_prefix` (directory name)
- `number_of_days` (how many days; optional)
- `lag_days` (days to lag from `last_test_time`; optional)

Calculate dates separately for each dependency.

## Parent-Child Data Dependencies

Parent and child algorithms have independent data dependencies:

**Parent** (`example-parent-algorithm`):
- Test data: `example_parent_test_data`
- Date: `dt=2000-01-08` (last_test_time)

**Child** (`example-parent-algorithm-child-model`):
- Training data: `example_child_training_data`
- Dates: `dt=2000-01-01` through `dt=2000-01-07` (7 days)
- Test data: `example_child_test_data` **only when the child is evaluated**
- Test date: `dt=2000-01-08` (last_test_time) when required

**Download both**:
```bash
hv-ext data-dependency --download-all \
  --repo-url https://github.com/example-org/example-algorithm.git \
  --git-reference v2.0.0 \
  --s3-base-dir s3://example-bucket/tables \
  --local-data-dir /path/to/data \
  --scratch-dir ./temp \
  --last-test-time 2000-01-08
```

The tool automatically follows parent and child dependencies, but it does not download a child's test slice merely
because the parent has one. A child gets target `evaluate` (and therefore needs test data) only when that child enables
`predict`, `evaluate`, or `performance-test`; otherwise it gets target `parameters`. A parent using a pre-v10 training
image is the compatibility exception and forces child evaluation. See
[Parent and child algorithms](../parent-child/index.md#dependency-targets-and-data).

## Troubleshooting Data Issues

### Issue: FileNotFoundException during training

**Check**:
1. Required dates calculated correctly
2. Data exists for those dates
3. Data in correct directory structure
4. Partitions named correctly (`dt=YYYY-MM-DD`)

**Verify**:
```bash
# List available dates
ls -la /path/to/data-base-dir/data_prefix/

# Check specific date
ls -lh /path/to/data-base-dir/data_prefix/dt=2000-01-07/

# Count files
find /path/to/data-base-dir/data_prefix/dt=2000-01-07/ -type f | wc -l
```

### Issue: Wrong dates downloaded

**Problem**: Misunderstanding date calculation

**Solution**: Use the formula carefully:
```
Training end = last_test_time - training_lag_days
Training start = Training end - (number_of_days - 1)
```

**Example**:
```
last_test_time = 2000-01-08
training_lag_days = 1
number_of_days = 7

end = 2000-01-08 - 1 = 2000-01-07
start = 2000-01-07 - 6 = 2000-01-01

Dates: [2000-01-01, ..., 2000-01-07]  # 7 days total
```

### Issue: Partial data (some files missing)

**Check**:
```bash
# Compare with S3
aws s3 ls s3://example-bucket/tables/example_training_data/dt=2000-01-07/ | wc -l
find /path/to/data/example_training_data/dt=2000-01-07/ -type f | wc -l
```

**Fix**: Re-download (partial downloads automatically resume)

## Sample only for smoke testing

Use `--sample-ratio` to download a fraction of files from every required date partition:

```bash
hv-ext data-dependency --download-all \
  --sample-ratio 0.01 \
  [other options]
```
`0.01` means roughly 1% of files per date, not 1% of rows. A sampled dataset is suitable for build and pipeline smoke
tests, not a final quality comparison.

Before training, compare the resolved plan with the local `dt=...` directories. Keep the same `last_test_time` and
override across dependency resolution, download, and execution.

## See Also

- [Parent-Child Algorithms](../parent-child/index.md) - Multiple data dependencies
- [Override Files](../override-files/index.md) - Changing number_of_training_days
- [FAQ: Data](../../../reference/faq/index.md#data)
- [CLI: hv-ext data-dependency](../../../reference/cli/index.md)
