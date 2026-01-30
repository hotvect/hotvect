---
title: Data Dependencies Pattern
description: Understanding and managing data dependencies for algorithm training
tags: [patterns, data, dependencies, training, dates, calculation]
difficulty: intermediate
estimated_time: 15 minutes
prerequisites:
  - Understanding of hotvect training workflow
  - Knowledge of parent-child algorithm pattern
  - Familiarity with partitioned data formats
related_docs:
  - ./parent-child-algorithms.md
  - ./override-files.md
  - ../faq.md
  - ../cli/usage.md
related_commands:
  - hv train
  - hv-ext download-data-dependency
---

# Data Dependencies Pattern

## Overview

Hotvect algorithms declare their data dependencies in the algorithm-definition.json. Understanding how to calculate required dates and manage data downloads is crucial for successful training.

## Data Dependency Specification

### Basic Structure

```json
{
  "data_dependency_spec": {
    "training_data": {
      "data_prefix": "config_sort_add_to_cart_sampled_w_fs",
      "number_of_days": 7,
      "training_lag_days": 1,
      "s3_uri": {
        "production": "s3://bucket/tables/"
      }
    }
  },
  "test_data_spec": {
    "data_prefix": "my_test_data_w_fs",
    "s3_uri": {
      "production": "s3://bucket/test-data/"
    }
  }
}
```

### Key Fields

| Field | Description | Example |
|-------|-------------|---------|
| `data_prefix` | Directory name under data-base-dir | `config_sort_add_to_cart_sampled_w_fs` |
| `number_of_days` | How many days of data to use | `7` |
| `training_lag_days` | Days to lag from last-test-time | `1` |
| `s3_uri.production` | S3 location for download | `s3://bucket/tables/` |

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
- `last_test_time`: 2025-08-09
- `training_lag_days`: 1
- `number_of_training_days`: 7

**Calculation**:
```
end_date = 2025-08-09 - 1 = 2025-08-08
start_date = 2025-08-08 - (7 - 1) = 2025-08-02

Required dates: 2025-08-02, 2025-08-03, ..., 2025-08-08 (7 days)
```

#### Example 2: Fast 2-Day Training (Override)

**Given**:
- `last_test_time`: 2025-08-09
- `training_lag_days`: 1
- `number_of_training_days`: 2 (from override)

**Calculation**:
```
end_date = 2025-08-09 - 1 = 2025-08-08
start_date = 2025-08-08 - (2 - 1) = 2025-08-07

Required dates: 2025-08-07, 2025-08-08 (2 days)
```

### Test Data Dates

Test data typically uses `last_test_time` directly (no lag):

```
test_date = last_test_time
```

## Data Directory Structure

### Expected Layout

```
data-base-dir/
├── data_prefix_1/
│   ├── dt=2025-08-02/
│   │   ├── part-00000.json.gz
│   │   ├── part-00001.json.gz
│   │   └── ...
│   ├── dt=2025-08-03/
│   │   └── ...
│   └── ...
├── test_data_prefix/
│   └── dt=2025-08-09/
│       └── ...
└── ...
```

### Partition Format

Data must be partitioned by date with format: `dt=YYYY-MM-DD`

**Valid**:
- `dt=2025-08-08/`
- `dt=2025-01-15/`

**Invalid**:
- `2025-08-08/` (missing `dt=` prefix)
- `dt=20250808/` (wrong date format)
- `date=2025-08-08/` (wrong partition name)

## Downloading Data

### Automated Download with hv-ext

The recommended way to download data:

```bash
hv-ext download-data-dependency \
  --repo-url https://github.com/company/algorithm.git \
  --git-reference v77.0.0 \
  --s3-base-dir s3://bucket/tables \
  --local-data-dir /path/to/data \
  --scratch-dir ./temp \
  --last-test-time 2025-08-09
```

**What it does**:
1. Clones algorithm repo
2. Builds algorithm JAR
3. Reads algorithm-definition.json
4. Calculates required dates
5. Downloads only needed data from S3
6. Organizes in correct directory structure

**Options**:
- `--sample-ratio 0.01`: Download 1% of files for testing
- `--no-skip-if-present`: Re-download even if exists
- `--role-arn`: Assume AWS role for S3 access

### Manual Download

For specific dates:

```bash
# Login first
zalando-aws-cli login my-team-data ReadOnly

# Download single date
aws s3 sync \
  s3://bucket/tables/data_prefix/dt=2025-08-08 \
  /path/to/data/data_prefix/dt=2025-08-08

# Download multiple dates
for date in 2025-08-02 2025-08-03 2025-08-04 2025-08-05 2025-08-06 2025-08-07 2025-08-08; do
  aws s3 sync \
    s3://bucket/tables/data_prefix/dt=$date \
    /path/to/data/data_prefix/dt=$date
done
```

## Multiple Data Dependencies

Algorithms can have multiple data dependencies:

```json
{
  "data_dependency_spec": {
    "training_data": {
      "data_prefix": "impressions",
      "number_of_days": 7,
      "training_lag_days": 1
    },
    "user_features": {
      "data_prefix": "user_profile_features",
      "number_of_days": 1,
      "training_lag_days": 0
    },
    "available_skus": {
      "data_prefix": "online_configs",
      "number_of_days": 1,
      "training_lag_days": 1
    }
  }
}
```

Each dependency has its own:
- `data_prefix` (directory name)
- `number_of_days` (how many days)
- `training_lag_days` (lag from last-test-time)

Calculate dates separately for each dependency.

## Parent-Child Data Dependencies

Parent and child algorithms have independent data dependencies:

**Parent** (`my-algorithm`):
- Test data: `test_data_prefix`
- Date: `dt=2025-08-09` (last_test_time)

**Child** (`my-algorithm-model`):
- Training data: `training_data_prefix`
- Dates: `dt=2025-08-02` through `dt=2025-08-08` (7 days)
- Test data: `test_data_prefix`
- Date: `dt=2025-08-08` (last_test_time - training_lag_days)

**Download both**:
```bash
hv-ext download-data-dependency \
  --repo-url https://github.com/company/algorithm.git \
  --git-reference v77.0.0 \
  --s3-base-dir s3://bucket/tables \
  --local-data-dir /path/to/data \
  --scratch-dir ./temp \
  --last-test-time 2025-08-09
```

The tool automatically downloads data for parent AND all child dependencies.

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
ls -lh /path/to/data-base-dir/data_prefix/dt=2025-08-08/

# Count files
find /path/to/data-base-dir/data_prefix/dt=2025-08-08/ -type f | wc -l
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
last_test_time = 2025-08-09
training_lag_days = 1
number_of_days = 7

end = 2025-08-09 - 1 = 2025-08-08
start = 2025-08-08 - 6 = 2025-08-02

Dates: [2025-08-02, ..., 2025-08-08]  # 7 days total
```

### Issue: Partial data (some files missing)

**Check**:
```bash
# Compare with S3
aws s3 ls s3://bucket/tables/data_prefix/dt=2025-08-08/ | wc -l
find /local/data/data_prefix/dt=2025-08-08/ -type f | wc -l
```

**Fix**: Re-download with `--no-skip-if-present`

## Data Size Management

### Estimating Disk Space

| Duration | Typical Size |
|----------|--------------|
| 1 day | 5-10 GB |
| 7 days | 50-100 GB |
| 30 days | 200-500 GB |

**Varies by**:
- Data density (events per day)
- Feature complexity
- Compression

### Sampling for Testing

Use `--sample-ratio` for development:

```bash
hv-ext download-data-dependency \
  --sample-ratio 0.01 \  # 1% of files
  [other options]
```

**Tradeoffs**:
- Smaller download (~100x faster)
- Less accurate training
- Good for testing algorithm logic
- Not suitable for final evaluation

## Best Practices

### 1. Use Automated Download

Always prefer `hv-ext download-data-dependency` over manual downloads:
- Calculates dates automatically
- Handles parent-child dependencies
- Creates correct directory structure
- Supports sampling and parallel downloads

### 2. Verify Before Training

Before running `hv train`:
```bash
# Check all required dates exist
for date in 2025-08-02 2025-08-03 2025-08-04 2025-08-05 2025-08-06 2025-08-07 2025-08-08; do
  if [ ! -d "/path/to/data/data_prefix/dt=$date" ]; then
    echo "Missing: $date"
  fi
done
```

### 3. Document Data Requirements

In project README:
```markdown
## Data Requirements

- **Training data**: `config_sort_add_to_cart_sampled_w_fs`
  - Number of days: 7
  - Lag days: 1
  - S3: `s3://bucket/tables/`

- **Test data**: `my_test_data_w_fs`
  - S3: `s3://bucket/tables/`
```

### 4. Use Consistent last-test-time

For reproducibility, document and reuse standard test dates:
- Weekly: Every Monday (e.g., 2025-08-04, 2025-08-11)
- Monthly: First of month (e.g., 2025-08-01)

## See Also

- [Parent-Child Algorithms](./parent-child-algorithms.md) - Multiple data dependencies
- [Override Files](./override-files.md) - Changing number_of_training_days
- [FAQ: Data Management](../faq.md#data-management)
- [CLI: hv-ext download-data-dependency](../cli/usage.md)
