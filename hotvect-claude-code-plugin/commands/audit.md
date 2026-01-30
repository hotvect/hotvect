---
description: Generate human-readable feature audit for debugging algorithm transformations
---

You are executing the audit command to generate a feature audit file showing all computed features for an algorithm.

## 🌶️ STEP 0: READ CONFIG FIRST (MANDATORY)

**Before doing ANYTHING else, read the central configuration:**
```bash
cat ~/.hotvect/config.json
```

Extract and use:
- `directories.data_base_dir` - Where test data lives
- `hotvect_source_dir` - Where hotvect Python venv is
- Then activate: `source ${hotvect_source_dir}/python/.venv/bin/activate`

See `../COMMON_PREAMBLE.md` for full discovery strategies.

## Purpose

Generate a JSONL file containing human-readable feature values for debugging and verifying algorithm transformations.

## Understanding Algorithm Structure (CRITICAL)

**Composite Algorithm Architecture:**

Many algorithms have **parent-child (outer-inner) relationships**:
- **Parent (Outer)**: Orchestrates evaluation and testing (e.g., `my-ranker`)
- **Child (Inner)**: Implements feature transformations and training (e.g., `my-ranker-model`)

**For audit command:**
- Use the algorithm name that **has feature transformations** (commonly the inner/child algorithm)
- Audit shows computed features, so you need an algorithm that trains
- Parent algorithms that only orchestrate typically don't have features to audit

**Finding the correct algorithm name:**
```bash
# List all algorithm definition JSONs in JAR
unzip -l /path/to/algorithm.jar | grep 'algorithm-definition.json'

# Read each to find which has training_command (definitive way to check if algo trains)
unzip -p /path/to/algorithm.jar path/to/some-algorithm-definition.json | jq '{algorithm_name, training_command, transformer_factory_classname, vectorizer_factory_classname}'

# Or from source repository
ls /path/to/algorithm-repo/src/main/resources/*-algorithm-definition.json
cat /path/to/algorithm-repo/src/main/resources/my-algorithm-definition.json | jq '{algorithm_name, training_command, transformer_factory_classname, vectorizer_factory_classname}'

# Algorithm that trains has:
# - training_command (definitive indicator)
# - transformer_factory_classname OR vectorizer_factory_classname (or both)
```

## STEP 1: Read Algorithm Definition (For Inner Algorithms)

Before running audit, read the algorithm definition JSON to get correct data source:

**Location priority:**
1. Inside installed JAR: `~/.m2/repository/org/{group}/{artifact}/{version}/{artifact}-{version}.jar`
2. Built artifacts: `{algorithm-repo}/target/classes/*-algorithm-definition.json`
3. Source: `{algorithm-repo}/src/main/resources/*-algorithm-definition.json`

**Extract test data prefix:**
```bash
# From JAR
unzip -p ~/.m2/repository/.../algorithm-1.0.0.jar \
  my-algorithm-model-algorithm-definition.json | jq -r '.test_data_spec.data_prefix'

# From source
cat ~/path/to/algorithm/src/main/resources/*-algorithm-definition.json | jq -r '.test_data_spec.data_prefix'
```

Use this prefix to construct correct `--source-path`.

## Required Arguments

Parse from user or ask:
- `--algorithm-jar`: Path to algorithm JAR file
- `--algorithm-name`: Name of the INNER algorithm transformation (see STEP 0)
- `--source-path`: Input data file (JSONL or compressed) - use test_data_prefix from algorithm definition
- `--dest-path`: Output audit file path (use current working directory, not source repository directory)

## Finding Latest Available Data

**When user says "use latest" or "most recent":**

```bash
# 1. Get test_data_prefix from algorithm definition (STEP 1)
TEST_PREFIX="test_data"  # Example from algorithm definition

# 2. Find all available dates for that prefix
DATA_BASE_DIR="/path/to/data"
LATEST_DATE=$(ls -1 "$DATA_BASE_DIR/$TEST_PREFIX" | grep 'dt=' | sort -r | head -1 | cut -d= -f2)

# 3. List files in that date
ls -lh "$DATA_BASE_DIR/$TEST_PREFIX/dt=$LATEST_DATE/" | head -5

# 4. Use first file from latest date
FIRST_FILE=$(ls "$DATA_BASE_DIR/$TEST_PREFIX/dt=$LATEST_DATE/" | head -1)
SOURCE_PATH="$DATA_BASE_DIR/$TEST_PREFIX/dt=$LATEST_DATE/$FIRST_FILE"
```

**Do NOT hardcode dates** - always discover them dynamically.

## JAR Location Best Practices

**ALWAYS prefer Maven's .m2/repository for installed JARs:**

```bash
# Install to .m2/repository
cd /path/to/algorithm-repo
git checkout v1.0.0
mvn clean install -DskipTests

# JAR location (predictable pattern):
JAR_PATH=~/.m2/repository/org/myorg/myalgo/my-algorithm/1.0.0/my-algorithm-1.0.0.jar

# Verify
ls -lh "$JAR_PATH"
```

**When working on algorithm code** (not using a version tag), using JAR in `target/` directory is also valid:
```bash
cd /path/to/algorithm-repo
mvn clean package -DskipTests
JAR_PATH=/path/to/algorithm-repo/target/my-algorithm-1.0.0.jar
```

**❌ NEVER:**
- Create custom jars/ directories
- Copy JARs to working directory
- Use non-standard locations like `~/regression-test/jars/`

## Working Directory Discipline

**ALWAYS use current working directory for outputs:**

```bash
# ✅ CORRECT - outputs in current directory
pwd  # Verify you're in a work directory, NOT a repo directory
mkdir -p ./audits
hv audit ... --dest-path ./audits/v1-audit.jsonl

# ❌ WRONG - don't create under ~/
mkdir -p ~/regression-test/audits  # NO!
hv audit ... --dest-path ~/regression-test/audits/v1-audit.jsonl  # NO!
```

**If user is in a source repository directory**, ask them to specify a work directory first. Best practice: launch Claude from a work directory (not a source repo).

## Example Execution

```bash
hv audit \
  --algorithm-jar ~/.m2/repository/org/myorg/myalgo/my-algorithm/1.0.0/my-algorithm-1.0.0.jar \
  --algorithm-name my-algorithm-model \
  --source-path /path/to/data/test_data/dt=2025-10-15/part-00000.json.gz \
  --dest-path ./my-algorithm-audit.jsonl
```

## What It Does

1. Loads the algorithm from the JAR
2. Processes each record from source data
3. Executes all feature transformations
4. Writes human-readable feature values to output file

## Output Format

JSONL file with one record per input, showing:
- Original input fields
- All computed features with values
- Feature namespace organization

## Common Use Cases

- Debug why features have unexpected values
- Verify feature transformations are correct
- Compare feature outputs between algorithm versions
- Understand what features are being computed

## Next Steps

After generating audit:
- Review feature values manually
- Compare with another version using `/audit-compare`
- Use for debugging transformation logic

## Tips

- Use compressed input (`.gz`) to save space
- Limit to small test dataset for quick debugging
- Compare audits to verify behavioral consistency
