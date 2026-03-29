# COMMON PREAMBLE - READ THIS FIRST

**🌶️ MANDATORY FIRST STEPS FOR ALL AGENTS, COMMANDS, AND SKILLS:**

## Step 1: Read Central Configuration

```bash
cat ~/.hotvect/config.json
```

This file contains critical context that should inform ALL your actions.

## Step 2: Check Command Reference (ANTI-HALLUCINATION)

**Before constructing ANY `hv` or `hv-ext` command, read:**
```bash
cat ~/workspace/hotvect/hotvect-claude-code-plugin/HV_COMMAND_REFERENCE.md
```

This contains the ACTUAL command signatures. DO NOT HALLUCINATE OPTIONS.

## Step 3: Check Algorithm Schema (If Working With Overrides)

**Before creating/reading algorithm overrides or definitions, read:**
```bash
cat ~/workspace/hotvect/hotvect-claude-code-plugin/ALGORITHM_DEFINITION_SCHEMA.md
```

This contains the ACTUAL JSON schema. DO NOT INVENT FIELDS.

**CRITICAL: Algorithm Override Confirmation**

Before using ANY algorithm override file in training or backtest commands:
1. Read the override file contents
2. Display the full JSON to the user
3. Explain what it will change (training days, hyperparameters, disabled steps, etc.)
4. Ask for explicit confirmation: "Proceed with this override? [y/N]"

Never run training/backtest commands with overrides without showing the user what will be overridden.

## Step 4: Understand Backtest Output Structure (If Reading Results)

**Before navigating backtest outputs or comparing results, read:**
```bash
cat ~/workspace/hotvect/hotvect-claude-code-plugin/BACKTEST_OUTPUT_STRUCTURE.md
```

This explains the `out/` vs `meta/` directory structure and where to find evaluation metrics.

## Configuration Structure

```json
{
  "hotvect_source_dir": "/home/user/workspace/hotvect",
  "aws": {
    "role_arn": "arn:aws:iam::123456789012:role/example-role",
    "login_command": "aws-cli-login-command"
  },
  "directories": {
    "output_base_dir": "/home/user/hotvect-workdir/output",
    "scratch_dir": "/home/user/hotvect-workdir/scratch",
    "data_base_dir": "/home/user/hotvect-workdir/data"
  },
  "sagemaker": {
    "default_s3_data_base_dir": "s3://example-bucket/tables",
    "sagemaker_config_template": "~/.hotvect/sagemaker-config-template.json"
  }
}
```

**CRITICAL SageMaker Configuration:**
- The SageMaker template (at `sagemaker_config_template` path) MUST include `OutputDataConfig.S3OutputPath`
- The template should NOT include `InputDataConfig` - hotvect auto-generates it from algorithm data requirements
- The template should NOT include `TrainingJobName` - hotvect generates unique names at runtime

## How to Use Config Values

### Data Discovery
- **ALWAYS check `directories.data_base_dir` first** when looking for test data or training data
- Search pattern: `${data_base_dir}/${data_prefix}/dt=YYYY-MM-DD/`
- Example: `/home/user/hotvect-workdir/data/my_test_dataset/dt=2025-10-15/`

### Algorithm JARs
- Check `~/.m2/repository/` for installed algorithm JARs
- If working on algorithm code, also check `${algorithm_repo}/target/`

### Output Paths
- Use `directories.output_base_dir` for training outputs
- Use `directories.scratch_dir` for temporary backtest artifacts
- Use current working directory for quick one-off operations (audits, comparisons)

### Virtual Environment
- **ALWAYS activate**: `source ${hotvect_source_dir}/python/.venv/bin/activate`
- Required before running any `hv` or `hv-ext` commands

### AWS Credentials
- Use `aws.login_command` command when AWS credentials expire
- Example: `aws sso login --profile my-profile`

## Algorithm Version Verification (CRITICAL for Backtests)

**Before running backtests, always verify that git references match expected algorithm versions:**

### Why This Matters
- Output directories are organized by algorithm **version** (from pom.xml), not git reference
- If two git refs have the same version, the second backtest **overwrites** the first's results
- Branch names like "v81" may not match the actual version in pom.xml (could still be "74.4.0")

### How to Check Versions

For each git reference before backtest:

```bash
# Clone to temporary location
TEMP_DIR="/tmp/version-check-$(date +%s)"
git clone ${algo_repo_url} "$TEMP_DIR"
cd "$TEMP_DIR"
git checkout ${git_reference}

# Extract version from pom.xml
VERSION=$(grep -m1 "<version>" pom.xml | sed 's/.*<version>\(.*\)<\/version>.*/\1/')

# Extract algorithm name
ALGO_NAME=$(grep -m1 "<artifactId>" pom.xml | sed 's/.*<artifactId>\(.*\)<\/artifactId>.*/\1/')

# Extract commit hash
COMMIT=$(git rev-parse --short HEAD)

echo "Git ref: ${git_reference}"
echo "  → Algorithm: ${ALGO_NAME}"
echo "  → Version: ${VERSION}"
echo "  → Commit: ${COMMIT}"

# Clean up
cd -
rm -rf "$TEMP_DIR"
```

### Warning Conditions

**CRITICAL WARNING** - abort unless user confirms:
- Multiple git refs have the SAME version (results will collide)

**WARNING** - inform user but allow to proceed:
- Branch name suggests different version than pom.xml (e.g., "v81" → "74.4.0")
- Using branch names instead of version tags (may be work-in-progress)

### Example Warning Message

```
⚠️  Version conflict detected:

Git reference 'v74':
  → my-algorithm version 74.4.0

Git reference 'v81':
  → my-algorithm version 74.4.0

Both references produce version 74.4.0, so the second backtest will overwrite
the first's results in the output directory.

Recommended actions:
1. Check if 'v81' branch has correct version in pom.xml
2. Use different git references that have distinct versions
3. Run backtests separately and rename output directories manually

Proceed anyway? [y/N]
```

## Algorithm Discovery

After reading config, use these strategies to find algorithm information:

### 1. Check Current Directory Context
```bash
pwd  # Parse directory name for hints (e.g., "my-algo-v1-v2-comparison")
ls -la  # Look for existing audit files, JARs, renamings.json
```

### 2. Inspect Existing Audit Files
```bash
# If audit files exist, extract algorithm name from them
head -1 v1-audit.jsonl | jq -r '.algorithm_metadata.algorithm_name'
```

### 3. Search Maven Repository
```bash
# Find recently installed algorithm JARs
find ~/.m2/repository -name "*algorithm*.jar" -mtime -30 | sort
```

### 4. Extract from JARs
```bash
# List algorithm definitions in JAR
unzip -l /path/to/algorithm.jar | grep 'algorithm-definition.json'

# Extract algorithm names and check which has features
for def in $(unzip -l algorithm.jar | grep 'algorithm-definition.json' | awk '{print $4}'); do
  echo "=== $def ==="
  unzip -p algorithm.jar "$def" | jq '{algorithm_name, training_command, transformer_factory_classname}'
done
```

## Test Data Discovery

After reading config, find test data systematically:

### 1. Check config data directory first
```bash
DATA_BASE_DIR=$(jq -r '.directories.data_base_dir' ~/.hotvect/config.json)
ls -ld "$DATA_BASE_DIR"/*/ | head -20
```

### 2. Look for date-partitioned data
```bash
# Find all data prefixes
ls -d "$DATA_BASE_DIR"/*/

# For each prefix, find latest date
for prefix_dir in "$DATA_BASE_DIR"/*/; do
  prefix=$(basename "$prefix_dir")
  latest=$(ls -1 "$prefix_dir" | grep '^dt=' | sort -r | head -1)
  echo "$prefix: $latest"
done
```

### 3. Match algorithm requirements
```bash
# Get test_data_spec from algorithm definition
TEST_PREFIX=$(unzip -p my-algorithm.jar my-algorithm-definition.json | jq -r '.test_data_spec.data_prefix')

# Find matching data
ls -lh "$DATA_BASE_DIR/$TEST_PREFIX/"
```

## Failing Fast vs Asking Questions

**Only ask the user when:**
- Config doesn't exist: Tell them to run the hotvect-setup agent
- Multiple equally valid options exist (e.g., multiple test dates available)
- Something genuinely ambiguous that can't be programmatically determined

**DO NOT ask when:**
- Information is in config.json
- Information is in existing files (audit files, JARs, directory structure)
- Information can be discovered by searching standard locations
- Information can be inferred from context (directory names, file patterns)

## Error Messages Must Be Actionable

When you fail, tell the user EXACTLY what to do:

❌ **Bad**: "Could not find algorithm JAR"
✅ **Good**: "Algorithm JAR not found at ~/.m2/repository/com/myorg/my-algorithm/1.0.0/. Build and install with: cd /path/to/algorithm && mvn clean install -DskipTests"

❌ **Bad**: "Test data not available"
✅ **Good**: "Test data not found at ${data_base_dir}/my_test_dataset/dt=2025-10-15. Download with: hv-ext download-data-dependency --repo-url ... --last-test-time 2025-10-15"

## Summary: The Invocation Pattern

```bash
# 1. ALWAYS read config first
cat ~/.hotvect/config.json

# 2. Activate virtual environment
source ${hotvect_source_dir}/python/.venv/bin/activate

# 3. Use config values + discovery to build context
# 4. Only ask user if genuinely ambiguous
# 5. Fail fast with actionable error messages
```
