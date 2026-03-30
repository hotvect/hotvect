---
name: download-results
description: Automatically downloads SageMaker backtest results from S3. Use when user mentions downloading results, checking backtest outputs, or analyzing SageMaker runs.
allowed-tools: Bash, Read, Grep
---

# Download Results Skill

## Purpose

## ⚠️ Configuration Protection Policy

**CRITICAL: Never modify `~/.hotvect/` configuration files.** See `CONFIG_PROTECTION_POLICY.md` for full policy.

This skill reads `~/.hotvect/config.json` for configuration but never modifies it. Work only in user-specified directories.

Automatically download SageMaker backtest results from S3 when user needs to analyze training outputs or compare algorithm performance.

## Configuration

**CRITICAL:** You (Claude) must read `~/.hotvect/config.json` at skill invocation to construct complete `hv-ext download-results` commands.

**How this works:**
- The `hv-ext download-results` CLI tool does NOT read config.json
- YOU read config.json and construct the full command line with all arguments
- Config provides defaults that can be overridden by user-specified values

**When this skill activates:**
1. Read config: `cat ~/.hotvect/config.json`
2. Parse the JSON to extract values (you can understand JSON directly)
3. Use config values for AWS operations:
   - `aws.login_command` → Command to run when refreshing credentials
   - `directories.output_base_dir` → Default for `--dest-base-dir` argument

4. **Fail fast** if config doesn't exist and AWS operations are needed
5. Tell user to run `/agent hotvect-setup-agent` if config is missing

**Important:** Always construct complete `hv-ext download-results` commands with ALL required arguments explicitly specified.

## When to Invoke

Invoke this skill when:
- User mentions downloading backtest results
- User asks to check SageMaker outputs
- User wants to analyze training results from S3
- User completed a backtest and needs the outputs

## Context Requirements Before Activation

**Before activating this skill, ensure you have gathered:**
1. **Date range**: From-date and to-date for results (ask user if not mentioned)
2. **S3 base prefix**: Standard location or user-specified
3. **Destination directory**: From config `directories.output_base_dir` or user-specified
4. **AWS credentials status**: Check if credentials are valid (run `aws sts get-caller-identity`)

**If critical information is missing:**
- Ask user for date range before proceeding
- Don't activate skill until you have enough information to construct a complete `hv-ext download-results` command

## Operations

### 1. Identify Download Requirements

From user's message, determine:
- Date range for results (from-date, to-date)
- Specific backtest run (if mentioned)
- Destination directory (default: `./backtest-results`)

### 2. Check AWS Credentials

Verify AWS access:
```bash
aws sts get-caller-identity
```

If expired or missing, guide user to refresh:
```bash
aws sso login
```

### 3. Determine S3 Location

Standard location for SageMaker backtest results:
```
s3://example-bucket/temp/exampleuser/sagemaker_output/
```

Or user-specific path if mentioned.

### 4. Construct Download Command

Build `hv-ext download-results` command:
```bash
hv-ext download-results \
  --s3-base-prefix s3://example-bucket/temp/exampleuser/sagemaker_output/ \
  --dest-base-dir ./backtest-results \
  --from-date ${start_date} \
  --to-date ${end_date} \
  --include-metadata
```

### 5. Execute Download

Run the command and monitor progress:
- Show dates being downloaded
- Report file counts and sizes
- Confirm successful completion

### 6. Verify Downloaded Results

After download, check:
```bash
ls -lh ./backtest-results
tree -L 2 ./backtest-results
```

Report:
- Number of dates downloaded
- Files per date
- Total size
- Location of result files

### 7. Suggest Next Steps

After successful download, suggest:
- Compare evaluation results: `hv-ext compare-evaluations`
- Review specific outputs
- Analyze performance metrics

## Expected Output

**Successful download:**
```
Downloading backtest results from S3...

Date range: 2025-06-01 to 2025-06-15
Destination: ./backtest-results

Downloaded:
- 2025-06-01: 3 files (1.2 GB)
- 2025-06-02: 3 files (1.1 GB)
...
- 2025-06-15: 3 files (1.2 GB)

Total: 45 files, 17.8 GB

Results available in: ./backtest-results
```

## Error Handling

**AWS credentials expired:**
- Run `aws sso login`
- Retry download

**S3 path not found:**
- Verify S3 base prefix
- Check if backtest completed successfully
- Confirm date range has data

**Network issues:**
- Retry with `--max-attempts` flag
- Download in smaller date ranges

**Insufficient disk space:**
- Report space required
- Ask user to free space or change destination

## Communication

- Show download progress clearly
- Report estimated total size before starting
- Confirm successful completion
- Suggest next analysis steps
