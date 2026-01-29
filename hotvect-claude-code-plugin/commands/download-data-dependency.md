---
description: Download required training and test data dependencies from S3 based on algorithm requirements
---

You are executing the download-data-dependency command to fetch training/test data for local operations.

## Purpose

Automatically determine and download the exact training and test data required by an algorithm based on its definition and date parameters.

## Configuration

**How config works with this command:**

When a user invokes `/download-data-dependency`, you (Claude) should:
1. Read `~/.hotvect/config.json` if it exists: `cat ~/.hotvect/config.json`
2. Parse the JSON to extract default values (you can understand JSON directly)
3. Use config values as defaults for constructing the `hv-ext download-data-dependency` command:
   - `directories.data_base_dir` → `--local-data-dir` argument
   - `directories.scratch_dir` → `--scratch-dir` argument
   - `sagemaker.default_s3_data_base_dir` → `--s3-base-dir` argument
   - `aws.credential_helper` → Command to run for AWS credential refresh
4. User-provided arguments override config defaults
5. Construct the complete `hv-ext download-data-dependency` command with ALL arguments explicitly specified

**Important:** The `hv-ext download-data-dependency` CLI tool does NOT read config.json. YOU read it and build the full command line.

If config doesn't exist and user didn't provide required arguments, tell them to run `/agent hotvect-setup-agent` first.

## Required Arguments

Parse from user or ask:
- `--repo-url`: Git URL of the algorithm repository
- `--git-reference`: Git reference (branch, tag, commit) to analyze
- `--s3-base-dir`: S3 base directory for data (e.g., `s3://bucket/tables`)
- `--local-data-dir`: Local directory to download data to
- `--scratch-dir`: Temporary workspace for git operations
- `--last-test-time`: Test date in YYYY-MM-DD format

## Optional Arguments

- `--sample-ratio`: Download subset of files (e.g., 0.01 for 1% sample)
- `--no-skip-if-present`: Re-download even if data exists
- `--role-arn`: AWS role ARN for cross-account access

## Example Execution

**Full data download:**
```bash
hv-ext download-data-dependency \
  --repo-url https://github.com/company/algorithm.git \
  --git-reference v77.0.0 \
  --s3-base-dir s3://bucket/tables \
  --local-data-dir /path/to/data \
  --scratch-dir /tmp/data-download-scratch \
  --last-test-time 2025-08-09
```

**With sampling (for testing):**
```bash
hv-ext download-data-dependency \
  --repo-url https://github.com/company/algorithm.git \
  --git-reference v77.0.0 \
  --s3-base-dir s3://bucket/tables \
  --local-data-dir ./local-training-data \
  --scratch-dir ./temp-build \
  --last-test-time 2025-08-09 \
  --sample-ratio 0.01
```

## What It Does

1. **Clones** algorithm repository
2. **Analyzes** algorithm definition to determine data requirements:
   - Training data prefixes
   - Test data prefixes
   - Number of training days
   - Lag days
3. **Calculates** required date ranges based on `last-test-time`
4. **Downloads** data from S3 in parallel
5. **Organizes** data in expected directory structure

## Smart Features

**Skip existing data (default):**
- Checks if date directories already exist
- Skips download to save time and bandwidth
- Use `--no-skip-if-present` to force re-download

**Sampling:**
- Download subset of files for quick testing
- `--sample-ratio 0.01` downloads 1% of files
- Maintains date structure

**Parallel downloads:**
- Downloads multiple dates and files concurrently
- Significantly faster than sequential

## Data Structure

Downloaded data organized as:
```
local-data-dir/
├── training_data_prefix/
│   ├── dt=2025-08-07/
│   │   ├── part-00000-*.json.gz
│   │   └── part-00001-*.json.gz
│   └── dt=2025-08-08/
│       └── part-00000-*.json.gz
└── test_data_prefix/
    └── dt=2025-08-09/
        └── part-00000-*.json.gz
```

## AWS Credentials

Requires AWS access to S3 bucket. Verify credentials:
```bash
aws sts get-caller-identity
```

If expired, refresh:
```bash
zalando-aws-cli login my-team-data ReadOnly
```

## Use Cases

- Download data before local training
- Get test data for audit generation
- Fetch data for backtest runs
- Obtain sample data for development

## Next Steps

After download:
- Verify data with `ls` or `tree`
- Run training with `/train`
- Generate audits with `/audit`

## Tips

- Use sampling for initial testing (saves time and space)
- Download full data for actual training/backtesting
- Keep data organized by date for reuse
- Download once, use for multiple algorithm versions
