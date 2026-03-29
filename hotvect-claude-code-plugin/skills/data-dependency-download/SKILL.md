---
name: data-dependency-download
description: Downloads training and test data dependencies required for local hotvect operations. Use when user needs data for training or backtesting.
allowed-tools: Bash, Read, Grep
---

# Data Dependency Download Skill

## Purpose

## ⚠️ Configuration Protection Policy

**CRITICAL: Never modify `~/.hotvect/` configuration files.** See `CONFIG_PROTECTION_POLICY.md` for full policy.

This skill reads `~/.hotvect/config.json` for configuration but never modifies it. Work only in user-specified directories.

Automatically download the correct training and test data needed for local algorithm training or backtesting, based on algorithm definitions and date parameters.

**Hotvect Version:** Always use the currently installed hotvect version. Hotvect is backward compatible - never switch versions to match algorithms.

## Configuration

**CRITICAL:** You (Claude) must read `~/.hotvect/config.json` at skill invocation to construct complete `hv-ext download-data-dependency` commands.

**How this works:**
- The `hv-ext download-data-dependency` CLI tool does NOT read config.json
- YOU read config.json and construct the full command line with all arguments
- Config provides defaults that can be overridden by user-specified values

**When this skill activates:**
1. Read config: `cat ~/.hotvect/config.json`
2. Parse the JSON to extract values (you can understand JSON directly)
3. Use config values as defaults when user hasn't specified:
   - `directories.data_base_dir` → `--local-data-dir` argument
   - `directories.scratch_dir` → `--scratch-dir` argument
   - `aws.login_command` → Command for AWS credential refresh
   - `sagemaker.default_s3_data_base_dir` → `--s3-base-dir` argument

4. **Fail fast** if config doesn't exist and user didn't provide required paths
5. Tell user to run `/agent hotvect-setup-agent` if config is missing
6. **Activate virtual environment**: `source ${hotvect_source_dir}/python/.venv/bin/activate`

**Important:** Always construct complete `hv-ext download-data-dependency` commands with ALL required arguments explicitly specified.

## When to Invoke

Invoke this skill when:
- User is setting up local training
- User mentions needing training data or test data
- User encounters "data not found" errors during training
- User wants to run backtest locally

**CRITICAL: Locate Algorithm Repository First**

Before doing anything, locate the algorithm repository:
1. Check current working directory and subdirectories (repos usually cloned here)
   - `ls -d */ | head -20` to see subdirectories
   - Look for directories with `.git/` subdirectory
2. Check parent directory
3. Read scratch_dir from config: `jq -r '.directories.scratch_dir' ~/.hotvect/config.json`
4. Ask user: "Where is your algorithm repository located?"
5. **Do NOT clone unless repo doesn't exist and user provides git URL**

**CRITICAL: Always Ask User First:**
- "Do you want to download data for quick local testing (2 days, 1% sample) or full production data?"
- Default to recommending local testing setup unless user explicitly needs full data
- Explain the size difference: local testing ~50MB vs full data ~5-50GB

## Context Requirements Before Activation

**Before activating this skill, ensure you have gathered:**
1. **Algorithm repository location**:
   - **FIRST**: Check current working directory and subdirectories (repos usually cloned here)
   - Check parent directory
   - Check scratch_dir from config: `jq -r '.directories.scratch_dir' ~/.hotvect/config.json`
   - Ask user: "Where is your algorithm repository located?"
   - **LAST RESORT**: Ask for Git URL to clone it
   - Prefer using existing local repos over cloning
2. **Git reference**: Which version/branch to analyze for data requirements
   - If using local repo, check current branch: `git -C /path/to/repo branch --show-current`
   - Ask user if they want to use current branch or checkout a different one
3. **Last test time**: Date in YYYY-MM-DD format
   - **CRITICAL**: Suggest **yesterday's date** (today - 1 day), not today
   - Data is typically not available on the same day
   - Example: If today is 2025-11-16, suggest 2025-11-15
4. **S3 base directory**: From config `sagemaker.default_s3_data_base_dir` or user-specified
5. **Local data directory**: From config `directories.data_base_dir` or user-specified
6. **Scratch directory**: From config `directories.scratch_dir` or user-specified
7. **Algorithm override** (optional): JSON file to reduce `number_of_training_days` for faster downloads
8. **Sampling** (optional): `--sample-ratio` to download subset of files (e.g., `0.01` = 1%)

**IMPORTANT for local development:**
- **Always suggest using `--algorithm-override`** to reduce training days (e.g., from 7 days to 1-2 days)
- **Always suggest using `--sample-ratio 0.01`** to download only 1% of files for quick testing
- Example: "For local development, I recommend using a 2-day override and 1% sampling to reduce download size"

**If critical information is missing:**
- Ask user for algorithm repo URL and version before proceeding
- Don't activate skill until you have enough information to construct a complete `hv-ext download-data-dependency` command

## Operations

### 1. Identify Data Requirements

Parse algorithm definition JSON to understand:
- Training data prefix (e.g., `example_training_data_attribution`)
- Test data prefix (e.g., `example_test_data_with_features`)
- S3 URIs (production vs staging)
- Number of days required
- Lag days

Read from algorithm definition file (usually in `~/.m2/repository/` or algorithm repo).

**Use Local Algorithm Repository:**

If local algorithm repo exists, extract required information:

```bash
# Get repo URL from git remote
REPO_URL=$(git -C /path/to/local/repo remote get-url origin)

# Get current branch/commit
GIT_REF=$(git -C /path/to/local/repo branch --show-current)
# Or use specific commit: GIT_REF=$(git -C /path/to/local/repo rev-parse HEAD)
```

**Detect Child Algorithm Name:**

If algorithm has parent-child structure, find the child algorithm name:

1. Check the parent algorithm definition JSON for `dependencies` field
2. Extract child algorithm name from dependencies list
3. Use this name in the override file

Example:
```bash
# Look for algorithm definition in local algorithm repo
grep -r "dependencies" /path/to/local/repo/*algorithm-definition.json

# Or check pom.xml for artifact names
grep "<artifactId>" /path/to/local/repo/pom.xml
```

For parent algorithm `parent-algo`, the child might be: `parent-algo-model` or `parent-algo-subcomponent`

### 2. Calculate Date Ranges

Based on user's `--last-test-time` parameter:
- **Test data date**: Same as `last_test_time`
- **Training data dates**: `last_test_time - lag_days` going back `number_of_training_days`

Example:
- `last_test_time = 2025-10-15`
- `training_lag_days = 1`
- `number_of_training_days = 2`
- Training dates: 2025-10-14, 2025-10-13

### 3. Check AWS Credentials

Verify AWS access:
```bash
aws sts get-caller-identity
```

If expired, guide user to refresh:
```bash
aws sso login  # For example-bucket bucket
# or
aws sso login  # For other buckets
```

### 4. Download Data

**Recommended for Local Development:**
```bash
# IMPORTANT: First detect child algorithm name from algorithm definition
# Example: parent-algo → parent-algo-model

# Create a 2-day override file with ACTUAL child algorithm name
cat > /tmp/2day-override.json <<'EOF'
{
    "hyperparameter_version": "2day",
    "hotvect_execution_parameters": {
        "performance-test": {"enabled": false}
    },
    "dependencies": {
        "{detected-child-algorithm-name}": {
            "number_of_training_days": 2,
            "hotvect_execution_parameters": {
                "performance-test": {"enabled": false}
            }
        }
    }
}
EOF

# Download with reduced days and 1% sampling
hv-ext download-data-dependency \
  --repo-url https://github.com/org/algorithm.git \
  --git-reference v${version} \
  --s3-base-dir s3://bucket/tables \
  --local-data-dir /path/to/local/data \
  --scratch-dir /tmp/scratch \
  --last-test-time ${test_date} \
  --algorithm-override /tmp/2day-override.json \
  --sample-ratio 0.01
```

**CRITICAL:** Replace `{detected-child-algorithm-name}` with the actual child algorithm name detected from the algorithm definition. The skill must detect this automatically by reading the algorithm definition JSON or pom.xml.

**Full Download (Production Data):**
```bash
# Download all files for all training days (can be very large)
hv-ext download-data-dependency \
  --repo-url https://github.com/org/algorithm.git \
  --git-reference v${version} \
  --s3-base-dir s3://bucket/tables \
  --local-data-dir /path/to/local/data \
  --scratch-dir /tmp/scratch \
  --last-test-time ${test_date}
```

**Key Parameters:**
- `--algorithm-override`: JSON file to reduce `number_of_training_days` (e.g., 2 instead of 7)
- `--sample-ratio 0.01`: Download only 1% of files (~100x faster, much smaller)
- `--no-skip-if-present`: Force re-download even if directories exist

### 5. Verify Download

Check downloaded data:
```bash
# Count files per date
for date in ${training_dates}; do
  echo "Date $date:"
  ls /local/path/dt=$date/ | wc -l
done

# Check total size
du -sh /local/path/
```

Report to user:
- Number of dates downloaded
- Files per date
- Total data size

### 6. Parent-Child Algorithm Handling

If algorithm has parent-child structure:
- **Parent test data**: For parent algorithm's evaluation
- **Child training data**: For child algorithm's model training
- **Child test data**: For child algorithm's evaluation

Download ALL required data for both parent and child algorithms.

## Expected Output

**Successful download:**
```
Downloaded 2 training dates (2025-10-13, 2025-10-14)
Downloaded 1 test date (2025-10-15)
Total size: 5.2 GB
```

## Error Handling

**AWS credentials expired:**
- Run appropriate `aws sso login` command
- Retry download

**S3 path not found:**
- Verify S3 URI from algorithm definition
- Check if date exists in S3
- Ask user for alternative date

**Insufficient disk space:**
- Report space required
- Suggest using `--sample-ratio` for smaller download
- Ask user to free space or change destination

## Communication

- Inform user about data being downloaded
- Show estimated download size
- Report progress (dates completed)
- Confirm successful download with summary
