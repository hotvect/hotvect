---
title: Quick Start Guide
description: Get started with hotvect in 10 minutes - install, run your first audit, and verify setup
tags: [quickstart, getting-started, tutorial, beginner]
difficulty: beginner
estimated_time: 10 minutes
prerequisites:
  - Java 21+ installed
  - Python 3.11+ installed
  - Maven installed
  - 2GB free disk space
related_docs:
  - ../../concepts/index.md
  - ../develop-algorithms/index.md
  - ../../reference/cli/index.md
next_steps:
  - Read concepts and terminologies
  - Develop your first algorithm
  - Run a full training pipeline
---

# Quick Start Guide

Get hotvect running in 10 minutes. This guide will get you from zero to running your first feature audit.

## Prerequisites

Before starting, ensure you have:
- **Java 21+**: `java -version`
- **Python 3.11+**: `python3 --version`
- **Maven**: `mvn -version`
- **uv** (Python package manager): `pip install uv`

## Step 1: Install Hotvect (3 minutes)

### Clone and Build Java Modules

```bash
# Clone the repository (or download release)
git clone https://github.com/zalando/hotvect.git
cd hotvect

# Build Java modules
mvn clean install -DskipTests

# Expected output:
# [INFO] BUILD SUCCESS
# [INFO] Total time: ~2-3 minutes
```

### Install Python CLI

```bash
cd python

# Recommended from a source checkout: installs dev/test tooling, builds Java (skipping tests), copies JARs
make init

# Activate virtual environment
source .venv/bin/activate

# Verify installation
hv --version
```

**Expected output**:
```
hotvect (python): <VERSION>, hotvect (jar): <VERSION>
```

**Troubleshooting**:
- If `hv` command not found: confirm your venv is activated, then run `uv pip install -p .venv/bin/python -e .` from `python/`
- If internal JAR version mismatch: Rebuild with `make quick` (applies to hotvect developers only)

## Step 2: Verify Setup (1 minute)

Check that hotvect-offline-util JAR is available:

```bash
python3 -c "import hotvect.hotvectjar; print(hotvect.hotvectjar.HOTVECT_JAR_PATH)"
```

**Expected output**:
```
/path/to/hotvect/python/hotvect/hotvectjar/hotvect-offline-util-<VERSION>-jar-with-dependencies.jar
```

Verify the JAR exists:
```bash
ls -lh $(python3 -c "import hotvect.hotvectjar; print(hotvect.hotvectjar.HOTVECT_JAR_PATH)")
```

**Expected output**: File size ~50-100MB

## Step 2.5: Initialize hotvect CLI config (recommended)

Some commands read `~/.hotvect/config.json`. Today:

- `hv train` uses it as a fallback for missing `--data-base-dir` / `--output-base-dir`
- `hv backtest` still loads it unconditionally

The easiest way to create it is:

```bash
hv-ext config init
```

To avoid interactive prompts, pass all three directories:

```bash
hv-ext config init \
  --data-base-dir /local/path/to/data \
  --output-base-dir /local/path/to/output \
  --scratch-dir /local/path/to/scratch
```

If you plan to use `hv-exp` (experiment-management / “EMS” inspection), you can also configure its defaults:

```bash
hv-ext config init \
  --data-base-dir /local/path/to/data \
  --output-base-dir /local/path/to/output \
  --scratch-dir /local/path/to/scratch \
  --experiment-management-url https://ems.example.com \
  --experiment-management-token-provider-command "bash -lc 'echo $EMS_TOKEN'"
```

## Step 3: Run Your First Command (1 minute)

List available commands:

```bash
hv --help
```

**Expected output**:
```
usage: hv [-h] [--version]
          {audit,performance-test,encode,predict,generate-state,list-transformations,evaluate,train,backtest,serve,worker}
          ...

Hotvect Command Line Interface (CLI) tool for performing various operations
like encoding, predicting, auditing, etc.

positional arguments:
  {audit,performance-test,encode,predict,generate-state,list-transformations,evaluate,train,backtest,serve,worker}
                        Available subcommands
    audit               Generate human-readable audit data
    performance-test    Benchmark algorithm performance and measure
                        throughput/latency
    encode              Transform input data for training
    predict             Generate model predictions
    ...
    serve               Serve the full algorithm over HTTP for local debugging
    worker              Worker-runtime HTTP debugging utilities
```

Check extended utilities:

```bash
hv-ext --help
```

**Expected output**:
```
usage: hv-ext [-h] {metrics,catboost-convert,config,compare-jsonl,data-dependency,show-data-dependency,results} ...

Hotvect extended utilities

positional arguments:
  {metrics,catboost-convert,config,compare-jsonl,data-dependency,show-data-dependency,results}
    ...
```

## Step 4: Run Feature Audit (3 minutes)

To run an audit, you need an algorithm JAR and test data. Here's an example workflow if you have these available:

### If You Have an Algorithm JAR

```bash
# Example: Run audit on algorithm
hv audit \
  --algorithm-jar ~/.m2/repository/com/company/my-algorithm/1.0.0/my-algorithm-1.0.0.jar \
  --algorithm-name my-algorithm-ctr-model \
  --source-path /path/to/test-data.json.gz \
  --dest-path audit-output.jsonl \
  --samples 10
```

**Expected output**:
```
Reading algorithm definition from JAR...
Algorithm: my-algorithm-ctr-model (version 1.0.0)
Processing /path/to/test-data.json.gz... 10 records
Extracting features... 127 features found
Writing audit to audit-output.jsonl... done
Audit complete: audit-output.jsonl (2.3MB)
```

### Inspect Audit Output

```bash
# View first record (requires jq)
head -n 1 audit-output.jsonl | jq '.'

# List all features
head -n 1 audit-output.jsonl | jq '.features | keys'

# Count features
head -n 1 audit-output.jsonl | jq '.features | keys | length'
```

## Step 5: Next Steps (2 minutes)

Now that hotvect is working, here are your next steps:

### Learn Core Concepts
- [Concepts and Terminologies](../../concepts/index.md) - Understanding algorithms, rankers, scorers
- [Key Differences](../../concepts/motivation/index.md) - Why hotvect's approach is different

### Build Your First Algorithm
- [How to: Develop a Re-ranker](../develop-algorithms/index.md) - Complete development guide
- Understand feature engineering patterns
- Learn about memoization and parallelization

### Explore CLI Commands
- [CLI Usage](../../reference/cli/index.md) - Complete reference for all commands
- Try `hv list-transformations` to see all available features
- Use `hv-ext compare-jsonl` to compare algorithm versions
- Note: `hv encode` writes a destination **directory** containing `shard_*<ext>` files (not a single output file).

### Run Training
Once you have training data:
```bash
hv train \
  --algorithm-name my-algorithm \
  --data-base-dir /path/to/training/data \
  --output-base-dir ./training-output \
  --algorithm-jar ~/.m2/repository/.../my-algorithm-1.0.0.jar \
  --last-test-time 2025-08-09
```

## Common Issues

### Issue: `hv` command not found

**Solution**:
```bash
# Ensure virtual environment is activated
source ~/path/to/hotvect/python/.venv/bin/activate

# Reinstall in development mode
cd ~/path/to/hotvect/python
uv pip uninstall -p .venv/bin/python hotvect
uv pip install -p .venv/bin/python -e .
```

### Issue: JAR not found or wrong version

**Solution**:
```bash
cd ~/path/to/hotvect
mvn clean install -pl hotvect-offline-util -am -DskipTests
cd python
python scripts/copy_jar.py
```

### Issue: Import errors or missing dependencies

**Solution**:
```bash
cd ~/path/to/hotvect/python
uv sync --group dev
```

## Getting Help

- **FAQ**: [Frequently Asked Questions](../../reference/faq/index.md)
- **How-To Guides**: [Guides index](../index.md)
- **Concepts**: [Concepts and Terminologies](../../concepts/index.md)
- **CLI Reference**: [CLI Usage Guide](../../reference/cli/index.md)

## Summary

You've successfully:
- ✅ Installed hotvect Java modules
- ✅ Installed hotvect Python CLI
- ✅ Verified installation
- ✅ Run hotvect commands
- ✅ (Optional) Generated feature audit

**Total time**: ~10 minutes

**Next**: Build your first algorithm with the [complete development guide](../develop-algorithms/index.md).
