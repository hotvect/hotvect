# HV Command Reference - ACTUAL COMMANDS ONLY

**🌶️ CRITICAL: This is the AUTHORITATIVE reference for `hv` and `hv-ext` commands.**

**DO NOT HALLUCINATE OPTIONS.** If an option is not listed here, IT DOES NOT EXIST.

## hv backtest

**Purpose:** Run backtest comparing algorithm performance across git references (branches/tags/commits).

**ACTUAL COMMAND SIGNATURE:**
```bash
hv backtest \
  --git-reference <ref1> \
  --git-reference <ref2> \
  [--algorithm-override <override.json>] \
  --algo-repo-url <git-url> \
  [--data-base-dir <path>] \
  --output-base-dir <path> \
  --scratch-dir <path> \
  --last-test-time YYYY-MM-DD \
  [--number-of-runs <N>] \
  [--extra-jvm-args <args>] \
  [--sagemaker-config <config.json>] \
  [--role-arn <arn>] \
  [--n-process <N>] \
  [--max-threads-per-process <N>] \
  [--clean] \
  [--no-performance-test]
```

**REQUIRED OPTIONS:**
- `--git-reference` - Git reference to test (can specify multiple times, one or more)
- `--algo-repo-url` - Git repository URL
- `--output-base-dir` - Where results are saved
- `--scratch-dir` - Temporary directory for JARs
- `--last-test-time` - Test date in YYYY-MM-DD format

**OPTIONAL OPTIONS:**
- `--algorithm-override` - JSON file with config overrides
- `--data-base-dir` - Base directory for training/test data (required for local, not SageMaker)
- `--number-of-runs` - Number of runs (default: 1)
- `--extra-jvm-args` - JVM args like "-Xmx64g,-XX:+UseG1GC"
- `--sagemaker-config` - SageMaker training job config
- `--role-arn` - AWS role for SageMaker
- `--n-process` - Parallel processes (default: 1)
- `--max-threads-per-process` - Threads per process
- `--clean` - Clean output directories first
- `--no-performance-test` - Skip performance testing
- `--auto-attach-data` - Auto-populate SageMaker `InputDataConfig` from algorithm dependencies
- `--auto-attach-data-default-s3-base` - Default S3 base URI used when dependencies don't specify explicit `s3_uri`
- `--auto-attach-data-environment` - Environment key (`production`/`test`) for dependency `s3_uri` maps (default: `production`)

**HALLUCINATED OPTIONS THAT DO NOT EXIST:**
- ❌ `--algorithm` - DOES NOT EXIST
- ❌ `--baseline` - DOES NOT EXIST
- ❌ `--candidate` - DOES NOT EXIST
- ❌ `--algorithm-name` - DOES NOT EXIST

**CORRECT EXAMPLE:**
```bash
hv backtest \
  --git-reference v74.0.0 \
  --git-reference v81.0.0 \
  --algo-repo-url https://github.com/myorg/my-algorithm.git \
  --data-base-dir /data \
  --output-base-dir /output \
  --scratch-dir /scratch \
  --last-test-time 2025-11-15 \
  --algorithm-override 2day-override.json
```

**WRONG EXAMPLE (HALLUCINATED):**
```bash
# ❌ THIS IS WRONG - THESE OPTIONS DON'T EXIST
hv backtest \
  --algorithm my-algorithm \
  --baseline v74 \
  --candidate v81
```

**⚠️  CRITICAL PRE-BACKTEST CHECK:**

Before running `hv backtest`, verify that git references don't have version conflicts:

```bash
# For each git reference, check actual version in pom.xml
git clone ${algo_repo_url} /tmp/check-v74
cd /tmp/check-v74 && git checkout v74
VERSION=$(grep -m1 "<version>" pom.xml | sed 's/.*<version>\(.*\)<\/version>.*/\1/')
echo "v74 → version ${VERSION}"
cd - && rm -rf /tmp/check-v74
```

**Why:** Output directories use algorithm version from pom.xml, not git reference name.
If "v74" and "v81" both have version "74.4.0", the second backtest overwrites the first.

**See:** COMMON_PREAMBLE.md "Algorithm Version Verification" section for full procedure.

## hv train

**ACTUAL COMMAND SIGNATURE:**
```bash
hv train \
  --algorithm-name <name> \
  --data-base-dir <path> \
  --output-base-dir <path> \
  --algorithm-jar <path> \
  --last-test-time YYYY-MM-DD \
  [--algorithm-override <override.json>] \
  [--extra-jvm-args <args>]
```

**REQUIRED OPTIONS:**
- `--algorithm-name` - Name of algorithm to train
- `--data-base-dir` - Base directory with training data
- `--output-base-dir` - Where outputs are saved
- `--algorithm-jar` - Path to algorithm JAR file
- `--last-test-time` - Test date in YYYY-MM-DD format

**OPTIONAL OPTIONS:**
- `--algorithm-override` - JSON file with config overrides
- `--extra-jvm-args` - Additional JVM arguments

## hv audit

**ACTUAL COMMAND SIGNATURE:**
```bash
hv audit \
  --algorithm-jar <path> \
  --algorithm-name <name> \
  --source-path <path> \
  --dest-path <path> \
  [--samples <N>]
```

**REQUIRED OPTIONS:**
- `--algorithm-jar` - Path to algorithm JAR
- `--algorithm-name` - Name of algorithm (must have feature transformations)
- `--source-path` - Input data file/directory
- `--dest-path` - Output audit file

**OPTIONAL OPTIONS:**
- `--samples` - Number of samples to process

## hv performance-test

**ACTUAL COMMAND SIGNATURE:**
```bash
hv performance-test \
  --algorithm-jar <path> \
  --algorithm-name <name> \
  --source-path <path> \
  [--samples <N>]
```

## hv encode

**ACTUAL COMMAND SIGNATURE:**
```bash
hv encode \
  --algorithm-jar <path> \
  --algorithm-name <name> \
  --source-path <path> \
  --dest-path <path>
```

## hv predict

**ACTUAL COMMAND SIGNATURE:**
```bash
hv predict \
  --algorithm-jar <path> \
  --algorithm-name <name> \
  --source-path <path> \
  --dest-path <path> \
  --parameter-path <path> \
  [--samples <N>]
```

## hv evaluate

**ACTUAL COMMAND SIGNATURE:**
```bash
hv evaluate \
  --source-path <path> \
  --dest-path <path> \
  [--enable-online-offline-analysis]
```

## hv list-transformations

**ACTUAL COMMAND SIGNATURE:**
```bash
hv list-transformations \
  --algorithm-jar <path> \
  --algorithm-name <name>
```

## hv-ext compare-jsonl

**ACTUAL COMMAND SIGNATURE:**
```bash
hv-ext compare-jsonl <file1.jsonl> <file2.jsonl> \
  [-c <renamings.json>]
```

**POSITIONAL ARGUMENTS:**
- First JSONL file path
- Second JSONL file path

**OPTIONAL OPTIONS:**
- `-c, --config` - Renamings configuration JSON

## hv-ext perf-compare

**ACTUAL COMMAND SIGNATURE:**
```bash
hv-ext perf-compare <baseline.json> <treatment.json> \
  [--format table|json] \
  [--output <path>]
```

## hv-ext compare-evaluations

**ACTUAL COMMAND SIGNATURE:**
```bash
hv-ext compare-evaluations <baseline.json> <treatment.json> \
  [-o <output.json>]
```

## hv-ext download-results

**ACTUAL COMMAND SIGNATURE:**
```bash
hv-ext download-results \
  --s3-base-prefix <s3-path> \
  --dest-base-dir <local-path> \
  --from-date YYYY-MM-DD \
  --to-date YYYY-MM-DD \
  [--role-arn <arn>] \
  [--include-metadata]
```

## hv-ext download-data-dependency

**ACTUAL COMMAND SIGNATURE:**
```bash
hv-ext download-data-dependency \
  --repo-url <git-url> \
  --git-reference <ref> \
  --s3-base-dir <s3-path> \
  --local-data-dir <path> \
  --scratch-dir <path> \
  --last-test-time YYYY-MM-DD \
  [--sample-ratio <0.0-1.0>] \
  [--role-arn <arn>] \
  [--no-skip-if-present]
```

## hv-ext catboost-convert

**ACTUAL COMMAND SIGNATURE:**
```bash
hv-ext catboost-convert \
  -s <schema-file> \
  -e <encoded-file> \
  -o <output-file> \
  [--format json|jsonl]
```

## Getting Help

**ALWAYS check actual help before using a command:**
```bash
hv --help
hv <command> --help
hv-ext --help
hv-ext <command> --help
```

**When in doubt, run the help command to see actual options.**
