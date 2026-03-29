---
title: Quick Start Guide
description: Get started with hotvect in 10 minutes - install, run your first audit, and verify setup
tags: [quickstart, getting-started, tutorial, beginner]
difficulty: beginner
estimated_time: 10 minutes
prerequisites:
  - Java 17+ installed
  - Python 3.11+ installed
  - Maven installed
  - 2GB free disk space
related_docs:
  - ./highlevel/concepts.md
  - ./howto/develop-a-re-ranker-with-hotvect.md
  - ./cli/usage.md
next_steps:
  - Read concepts and terminologies
  - Develop your first algorithm
  - Run a full training pipeline
---

# Quick Start Guide

Get hotvect running in 10 minutes. This guide will get you from zero to running your first feature audit.

## Prerequisites

Before starting, ensure you have:
- **Java 17+**: `java -version`
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

# Install all dependencies
uv sync --all-extras

# Activate virtual environment
source .venv/bin/activate

# Verify installation
hv --version
```

**Expected output**:
```
hotvect, version 10.0.0
```

**Troubleshooting**:
- If `hv` command not found: `uv pip uninstall hotvect && uv pip install -e .`
- If internal JAR version mismatch: Rebuild with `make quick` (applies to hotvect developers only)

## Step 2: Verify Setup (1 minute)

Check that hotvect-offline-util JAR is available:

```bash
python3 -c "import hotvect.hotvectjar; print(hotvect.hotvectjar.HOTVECT_JAR_PATH)"
```

**Expected output**:
```
/path/to/hotvect/python/hotvect/hotvectjar/hotvect-offline-util-10.0.0-jar-with-dependencies.jar
```

Verify the JAR exists:
```bash
ls -lh $(python3 -c "import hotvect.hotvectjar; print(hotvect.hotvectjar.HOTVECT_JAR_PATH)")
```

**Expected output**: File size ~50-100MB

## Step 3: Run Your First Command (1 minute)

List available commands:

```bash
hv --help
```

**Expected output**:
```
usage: hv [-h] {audit,performance-test,encode,predict,list-transformations,evaluate,train,backtest,generate-state} ...

Hotvect CLI tool

positional arguments:
  {audit,performance-test,encode,predict,list-transformations,evaluate,train,backtest,generate-state}
    audit               Generate human-readable audit data
    performance-test    Benchmark algorithm performance
    encode              Transform input data for training
    predict             Generate model predictions
    ...
```

Check extended utilities:

```bash
hv-ext --help
```

**Expected output**:
```
usage: hv-ext [-h] {perf-compare,jsonl-compare,download-results,download-data-dependency,catboost-convert,compare-evaluations} ...

Hotvect extended utilities

positional arguments:
  {perf-compare,jsonl-compare,download-results,download-data-dependency,catboost-convert,compare-evaluations}
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
- [Concepts and Terminologies](highlevel/concepts.md) - Understanding algorithms, rankers, scorers
- [Key Differences](highlevel/motivation.md) - Why hotvect's approach is different

### Build Your First Algorithm
- [How to: Develop a Re-ranker](howto/develop-a-re-ranker-with-hotvect.md) - Complete development guide
- Understand feature engineering patterns
- Learn about memoization and parallelization

### Explore CLI Commands
- [CLI Usage](cli/usage.md) - Complete reference for all commands
- Try `hv list-transformations` to see all available features
- Use `hv-ext jsonl-compare` to compare algorithm versions

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
uv pip uninstall hotvect && uv pip install -e .
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
uv sync --all-extras --group dev
```

## Getting Help

- **FAQ**: [Frequently Asked Questions](faq.md)
- **How-To Guides**: [How-To Index](howto/)
- **Concepts**: [Concepts and Terminologies](highlevel/concepts.md)
- **CLI Reference**: [CLI Usage Guide](cli/usage.md)

## Summary

You've successfully:
- ✅ Installed hotvect Java modules
- ✅ Installed hotvect Python CLI
- ✅ Verified installation
- ✅ Run hotvect commands
- ✅ (Optional) Generated feature audit

**Total time**: ~10 minutes

**Next**: Build your first algorithm with the [complete development guide](howto/develop-a-re-ranker-with-hotvect.md).
