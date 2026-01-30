---
description: Download SageMaker backtest results from S3 to local directory for analysis and comparison
---

You are executing the download backtest results command to retrieve SageMaker training outputs from S3.

## Configuration

**How config works with this command:**

When a user invokes `/download-backtest-results`, you (Claude) should:
1. Read `~/.hotvect/config.json` if it exists: `cat ~/.hotvect/config.json`
2. Parse the JSON to extract default values (you can understand JSON directly)
3. Use config values as defaults for constructing the `hv-ext download-results` command:
   - `directories.output_base_dir` → `--dest-base-dir` argument
   - `aws.credential_helper` → Command to run for AWS credential refresh
4. User-provided arguments override config defaults
5. Construct the complete `hv-ext download-results` command with ALL arguments explicitly specified

**Important:** The `hv-ext download-results` CLI tool does NOT read config.json. YOU read it and build the full command line.

If config doesn't exist and user didn't provide required arguments, tell them to run `/agent hotvect-setup-agent` first.

## Steps

1. Parse command arguments:
   - `--from-date`: Start date for download range
   - `--to-date`: End date for download range
2. Check AWS credentials with `aws sts get-caller-identity`
3. If credentials expired, refresh with `zalando-aws-cli login my-team-data ReadOnly`
4. Ask user for destination directory (default: `./backtest-results`)
5. Construct `hv-ext download-results` command
6. Execute download
7. Report download summary (dates, files, size)

## Example Execution

**Check credentials:**
```bash
aws sts get-caller-identity
```

**Refresh if needed:**
```bash
zalando-aws-cli login my-team-data ReadOnly
```

**Download results:**
```bash
hv-ext download-results \
  --s3-base-prefix s3://my-experiment-bucket/temp/username/sagemaker_output/ \
  --dest-base-dir ./backtest-results \
  --from-date 2025-06-01 \
  --to-date 2025-06-15 \
  --include-metadata
```

## Output

Report:
```
Downloading backtest results from S3...

Date range: 2025-06-01 to 2025-06-15
Destination: ./backtest-results

Downloaded:
- 2025-06-01: 3 files (1.2 GB)
- 2025-06-02: 3 files (1.1 GB)
- 2025-06-03: 3 files (1.3 GB)
...
- 2025-06-15: 3 files (1.2 GB)

Total: 45 files, 17.8 GB

Results available in: ./backtest-results
```

Offer to compare results if user wants analysis.
