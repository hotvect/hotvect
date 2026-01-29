---
description: Run backtest to compare algorithm performance across git references (branches, tags, versions)
---

You are executing the backtest command to compare algorithm versions end-to-end.

## 🌶️ MANDATORY: Check Command Reference First

**Before constructing the command, read the actual signature:**
```bash
cat ~/workspace/hotvect/hotvect-claude-code-plugin/HV_COMMAND_REFERENCE.md
```

**DO NOT HALLUCINATE OPTIONS.** Common hallucinations to avoid:
- ❌ `--algorithm` - DOES NOT EXIST
- ❌ `--baseline` - DOES NOT EXIST
- ❌ `--candidate` - DOES NOT EXIST

**ACTUAL OPTIONS:** `--git-reference`, `--algo-repo-url`, `--data-base-dir`, `--output-base-dir`, `--scratch-dir`, `--last-test-time`, etc.

## Purpose

Compare algorithm performance by training and evaluating multiple git references (branches, tags, commits) on the same data.

## Configuration

**How config works with this command:**

When a user invokes `/backtest`, you (Claude) should:
1. Read `~/.hotvect/config.json` if it exists: `cat ~/.hotvect/config.json`
2. Parse the JSON to extract default values (you can understand JSON directly)
3. Use config values as defaults for constructing the `hv backtest` command:
   - `directories.data_base_dir` → `--data-base-dir` argument
   - `directories.output_base_dir` → `--output-base-dir` argument
   - `directories.scratch_dir` → `--scratch-dir` argument
   - `aws.credential_helper` → Command to run for AWS credential refresh
4. User-provided arguments override config defaults
5. Construct the complete `hv backtest` command with ALL arguments explicitly specified

**Important:** The `hv backtest` CLI tool does NOT read config.json. YOU read it and build the full command line.

If config doesn't exist and user didn't provide required arguments, tell them to run `/agent hotvect-setup-agent` first.

## Required Arguments

Parse from user or ask:
- `--git-reference`: Git reference to test (can specify multiple for comparison)
- `--algo-repo-url`: Git URL of the algorithm repository
- `--data-base-dir`: Base directory containing training/test data
- `--output-base-dir`: Base directory for backtest outputs
- `--scratch-dir`: Temporary workspace for builds
- `--last-test-time`: Test date in YYYY-MM-DD format

## CRITICAL: Pre-Backtest Version Check

**Before running `hv backtest`, verify algorithm versions to avoid result collisions:**

For each git reference:
1. **Clone and checkout** in temporary directory
2. **Extract version from pom.xml**: Look for `<version>` tag in project section
3. **Extract algorithm name from pom.xml**: Look for `<artifactId>` tag
4. **Report to user**: Git ref → actual version mapping

**Warn if:**
- Multiple git refs have the SAME version (second run overwrites first)
- Branch name implies different version than pom.xml (e.g., "v81" branch but "74.4.0" in pom.xml)
- Git ref is a branch rather than version tag (may be work-in-progress)

**Example version check:**
```bash
# For each git reference
git clone ${algo_repo_url} /tmp/check-${ref}
cd /tmp/check-${ref}
git checkout ${ref}

# Extract version
version=$(grep -m1 "<version>" pom.xml | sed 's/.*<version>\(.*\)<\/version>.*/\1/')
echo "Git ref '${ref}' has algorithm version: ${version}"

# Clean up
cd - && rm -rf /tmp/check-${ref}
```

**Ask user to confirm if issues detected** before proceeding with full backtest.

## Optional Arguments

- `--algorithm-override`: JSON file with custom configuration
- `--extra-jvm-args`: Additional JVM arguments

## Algorithm Override JSON (CRITICAL)

**Before creating override JSON, read the schema:**
```bash
cat ~/workspace/hotvect/hotvect-claude-code-plugin/ALGORITHM_DEFINITION_SCHEMA.md
```

**CORRECT 2-day training override structure:**
```json
{
  "hyperparameter_version": "2day",
  "hotvect_execution_parameters": {
    "performance-test": {
      "enabled": false
    }
  },
  "dependencies": {
    "child-algorithm-name": {
      "number_of_training_days": 2,
      "hotvect_execution_parameters": {
        "performance-test": {
          "enabled": false
        }
      }
    }
  }
}
```

**Key points:**
- Top-level fields apply to parent algorithm
- `dependencies.{child-name}` overrides child algorithm
- Use EXACT child algorithm name from parent's algorithm definition
- Can override any field from child algorithm definition

**Common mistakes:**
- ❌ Using parent algorithm name as key instead of child
- ❌ Missing `dependencies` wrapper
- ❌ Inventing fields that don't exist in algorithm definition

## Example Execution

**Single reference (absolute performance):**
```bash
hv backtest \
  --git-reference v77.0.0 \
  --algo-repo-url https://github.com/company/algorithm.git \
  --data-base-dir /path/to/data \
  --output-base-dir /path/to/backtest-output \
  --scratch-dir /tmp/backtest-scratch \
  --last-test-time 2025-08-09
```

**Multiple references (comparison):**
```bash
hv backtest \
  --git-reference v77.0.0 \
  --git-reference v64.4.0 \
  --algo-repo-url https://github.com/company/algorithm.git \
  --data-base-dir /path/to/data \
  --output-base-dir /path/to/backtest-output \
  --scratch-dir /tmp/backtest-scratch \
  --last-test-time 2025-08-09 \
  --algorithm-override 2day-override.json
```

## What It Does

For each git reference:

1. **Clone repository** into scratch directory
2. **Checkout** specified git reference
3. **Build** algorithm JAR with Maven
4. **Train** model on training data
5. **Evaluate** on test data
6. **Save** results and metrics

Then compare results across references.

## Output Structure

**🌶️ CRITICAL: Read the comprehensive output structure guide:**
```bash
cat ~/workspace/hotvect/hotvect-claude-code-plugin/BACKTEST_OUTPUT_STRUCTURE.md
```

**Quick summary:**

```
output-base-dir/
├── out/                                # Binary artifacts
│   └── algorithm@version/
│       └── last_test_date_YYYY-MM-DD/
│           ├── parameters.zip
│           ├── prediction.jsonl
│           └── cache/
└── meta/                               # Metadata and metrics (READ THIS!)
    └── algorithm@version/
        └── last_test_date_YYYY-MM-DD/
            ├── result.json             # 🌶️ ALL RESULTS HERE
            ├── algorithm_definition.json
            ├── predict_metadata.json
            ├── evaluate_metadata.json
            └── hotvect-offline-utils.log
```

**Key points:**
- Evaluation results are in `meta/algorithm@version/last_test_date_*/result.json`
- The `result.json` file contains comprehensive results including all timing and metrics
- There is NO `evaluation.json` in `out/` directory
- Parent and child algorithms have separate result.json files

## Scratch Directory

Temporary workspace for:
- Git clones
- Maven builds
- Intermediate files

Can be deleted after backtest completes.

## Duration

Backtests can take hours depending on:
- Number of git references
- Data size
- Training complexity

Use `--algorithm-override` with fewer training days (e.g., 2-day) for faster testing.

## AWS Credentials

Backtest may require AWS credentials for:
- S3 data access
- SageMaker execution (if configured)

Ensure credentials are valid:
```bash
aws sts get-caller-identity
```

If expired, refresh:
```bash
zalando-aws-cli login my-team-data ReadOnly
```

## Comparison Analysis

After backtest completes:

**Locate result.json files:**
```bash
# Results are in meta/ directory, NOT out/ directory
BASELINE="${output_base_dir}/meta/algorithm@baseline_version/last_test_date_YYYY-MM-DD/result.json"
TREATMENT="${output_base_dir}/meta/algorithm@treatment_version/last_test_date_YYYY-MM-DD/result.json"
```

**Compare evaluation metrics:**
```bash
hv-ext compare-evaluations "$BASELINE" "$TREATMENT"
```

**Extract specific metrics:**
```bash
# AUC
jq -r '.evaluate.roc_auc.mean' "$BASELINE"

# Verify evaluation ran
jq -r '.timing_info_sec.evaluate' "$BASELINE"  # Non-zero = evaluation ran
```

- Review prediction differences if needed
- Assess if changes improved performance

## Next Steps

After backtest:
- Compare evaluation results
- Download full outputs if on SageMaker
- Decide if new version should be deployed

## Tips

- Start with single reference to verify setup
- Use 2-day override for faster iteration
- Ensure data is available for all required dates
- Monitor logs for build or training errors
- Backtest validates end-to-end behavior, not just features
