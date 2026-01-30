---
title: Troubleshooting Guide
description: Diagnose and fix common hotvect issues with step-by-step solutions
tags: [troubleshooting, errors, debugging, solutions, problems]
difficulty: beginner
estimated_time: varies
related_docs:
  - ./faq.md
  - ./howto/debug-feature-engineering.md
  - ./cli/usage.md
next_steps:
  - Check FAQ for additional questions
  - Debug feature engineering code
  - File issue if problem persists
---

# Troubleshooting Guide

This guide helps you diagnose and fix common issues with hotvect. If you don't find your issue here, check the [FAQ](faq.md) or file an issue on GitHub.

## Quick Diagnosis

### Training Fails Immediately

Run through this checklist:

- [ ] **hotvect installed**: `hv --version` should show version number
- [ ] **Algorithm JAR exists**: `ls -lh /path/to/algorithm.jar`
- [ ] **Training data exists**: `ls /path/to/data-base-dir/data_prefix/dt=YYYY-MM-DD`
- [ ] **Sufficient memory**: `free -h` (need at least 16GB available)
- [ ] **Sufficient disk space**: `df -h` (need space for outputs)

**If still failing**: Check [Error Index](#error-index) for specific error message.

### Training Runs But No Output

Check these:

- [ ] **Output directory writable**: `ls -ld /path/to/output-dir`
- [ ] **Check logs**: Look for error messages in console output
- [ ] **Verify algorithm definition**: JAR contains valid algorithm-definition.json
- [ ] **Check data dependency spec**: Required data exists for calculated dates

**If still no output**: Enable debug logging (see [Debug Logging](#debug-logging))

### Training Succeeds But Results Wrong

Debug steps:

1. **Generate audit**: Compare feature values between versions
   ```bash
   hv audit --algorithm-jar algo.jar --algorithm-name my-algo \
     --source-path test.jsonl --dest-path audit.jsonl
   ```

2. **Check data dates**: Verify correct training period was used
   ```bash
   # Check what dates were actually read
   ls -la /path/to/data-base-dir/data_prefix/
   ```

3. **Validate hyperparameters**: Check override file settings are correct

4. **Compare with baseline**: Use `hv backtest` to identify changes

See [Debugging Features](#debugging-features) for detailed workflow.

---

## Error Index

### Training Errors

#### TRAIN-001: FileNotFoundException for training data

**Error Message**:
```
java.io.FileNotFoundException: /path/to/data/my_data/dt=2025-08-09
    at com.hotvect.offlineutils.hotdeploy.AlgorithmOfflineUtils.resolveDependencies
```

**Meaning**: Required training data not found for specified date.

**Common Causes**:
1. Training data not downloaded from S3
2. Wrong `--last-test-time` specified (no data for that date)
3. Incorrect `training_lag_days` in algorithm definition causing wrong date calculation
4. Wrong `--data-base-dir` path

**Solutions**:

##### Solution 1: Download Missing Data

First, calculate required dates:
```
Formula: last_test_time - training_lag_days - (number_of_training_days - 1)  to  last_test_time - training_lag_days

Example: last_test_time=2025-08-09, lag=1, days=7
Needs: 2025-08-08, 2025-08-07, ..., 2025-08-02
```

Then download from S3:
```bash
aws s3 sync \
  s3://bucket/tables/my_data/dt=2025-08-08 \
  /path/to/data/my_data/dt=2025-08-08
```

Or use the automated tool:
```bash
hv-ext download-data-dependency \
  --repo-url https://github.com/company/algorithm.git \
  --git-reference v77.0.0 \
  --s3-base-dir s3://bucket/tables \
  --local-data-dir /path/to/data \
  --scratch-dir ./temp \
  --last-test-time 2025-08-09
```

##### Solution 2: Adjust Training Period

Reduce training days in override file:
```json
{
  "dependencies": {
    "my-algorithm": {
      "number_of_training_days": 2
    }
  }
}
```

**Prevention**: Always use `hv-ext download-data-dependency` before training.

**See Also**: [FAQ: How do I calculate which training dates I need?](faq.md#q-how-do-i-calculate-which-training-dates-i-need)

---

#### TRAIN-002: OutOfMemoryError: Java heap space

**Error Message**:
```
java.lang.OutOfMemoryError: Java heap space
    at java.util.Arrays.copyOf
```

**Meaning**: JVM ran out of heap memory during operation.

**Common Causes**:
1. Default memory (256GB) insufficient for dataset size
2. Too many training days
3. Memory leak in algorithm code (rare)

**Solutions**:

##### Solution 1: Increase Heap Size

```bash
hv train --extra-jvm-args "-Xmx64g" [other options]
```

**Memory guidelines**:
- 1 day data: 16-32GB
- 7 days data: 64-128GB
- 30 days data: 256GB+

##### Solution 2: Use Better Garbage Collector

```bash
hv train --extra-jvm-args "-Xmx64g,-XX:+UseG1GC" [other options]
```

G1GC is more efficient for large heaps.

##### Solution 3: Reduce Training Days

Use override file to train on fewer days:
```json
{
  "dependencies": {
    "my-algorithm": {
      "number_of_training_days": 2
    }
  }
}
```

**Prevention**:
- Start with small dataset (1-2 days) for testing
- Increase memory proactively based on data size

**See Also**: [FAQ: I'm getting OutOfMemoryError](faq.md#q-im-getting-outofmemoryerror-what-should-i-do)

---

#### TRAIN-003: Duplicate key algorithm-parameters.json

**Error Message**:
```
Exception in thread "main" java.util.zip.ZipException: duplicate entry: algorithm-parameters.json
    at java.util.zip.ZipOutputStream.putNextEntry
```

**Meaning**: Self-dependency bug - algorithm trying to depend on itself during packaging.

**Cause**: Training a child algorithm directly with an override file designed for the parent algorithm. The override's `dependencies` section makes the child think it depends on itself.

**Solution**: Always train the PARENT algorithm, which cascades to children automatically.

**Example**:
```bash
# CORRECT - train parent
hv train --algorithm-name my-algorithm \
  --algorithm-override parent-override.json ...

# WRONG - training child with parent's override
hv train --algorithm-name my-algorithm-model \
  --algorithm-override parent-override.json ...
```

**How to identify parent**:
- Parent has `dependencies` in algorithm-definition.json
- Parent orchestrates evaluation/testing
- Child is listed in parent's `dependencies` section

**Prevention**: Understand parent-child algorithm architecture before training.

**See Also**: [FAQ: What's the difference between parent and child algorithms?](faq.md#q-whats-the-difference-between-parent-and-child-algorithms)

---

### Configuration Errors

(No configuration-specific errors. hotvect is backward compatible - algorithm versions don't need to match CLI versions.)

---

### Data Errors

#### DATA-001: No valid partition found for namespace

**Error Message**:
```
No valid partition found for namespace [namespace_name]
# OR
Data not available for date range
```

**Meaning**: Required data partition doesn't exist in data directory.

**Common Causes**:
1. Data not partitioned correctly (missing `dt=YYYY-MM-DD` subdirectories)
2. Wrong data prefix specified in algorithm definition
3. Partition name format mismatch

**Solution**:

1. **Verify data structure**:
   ```bash
   ls -la /path/to/data-base-dir/data_prefix/
   # Should show: dt=2025-08-08/, dt=2025-08-07/, etc.
   ```

2. **Check algorithm definition**:
   ```bash
   unzip -p algorithm.jar '*/algorithm-definition.json' | \
     jq '.data_dependency_spec'
   ```

3. **Fix data structure** if needed:
   ```bash
   # Data should be organized as:
   # data-base-dir/
   #   data_prefix/
   #     dt=2025-08-08/
   #       part-00000.json.gz
   #       part-00001.json.gz
   #       ...
   ```

**Prevention**: Use `hv-ext download-data-dependency` which handles partitioning automatically.

---

## Debugging Tools

### Enable Debug Logging

Add debug flags to see detailed execution:

```bash
hv train \
  --extra-jvm-args "-Dhotvect.debug=true,-Dhotvect.log.level=DEBUG" \
  [other args] \
  2>&1 | tee training-debug.log
```

**What you'll see**:
- Feature transformation calls and dependencies
- Data loading progress and file counts
- Namespace resolution and registration
- Memory usage statistics
- Timing information for each step

### Inspect Audit Files

Generate audit to see exact feature values:

```bash
hv audit \
  --algorithm-jar algo.jar \
  --algorithm-name my-algo \
  --source-path test.jsonl \
  --dest-path audit.jsonl

# Inspect specific record
cat audit.jsonl | jq 'select(.id == "record_123")'

# List all features
cat audit.jsonl | head -1 | jq '.features | keys'

# Show feature values for namespace
cat audit.jsonl | head -1 | jq '.features.namespace_name'

# Count features per namespace
cat audit.jsonl | head -1 | jq '.features | to_entries | map({namespace: .key, count: (.value | length)})'
```

### Profile Memory Usage

Monitor memory during training:

```bash
# Terminal 1: Run training
hv train [options] &
PID=$!

# Terminal 2: Monitor memory
watch -n 1 "ps -p $PID -o rss,vsz,comm"
```

Or enable heap dump on OOM:
```bash
hv train \
  --extra-jvm-args "-XX:+HeapDumpOnOutOfMemoryError,-XX:HeapDumpPath=./heap-dump.hprof" \
  [other options]
```

Then analyze with tools like Eclipse Memory Analyzer (MAT).

### Trace Feature Calculations

Enable feature tracing to see computation order:

```bash
hv audit \
  --algorithm-jar algo.jar \
  --algorithm-name my-algo \
  --source-path test.jsonl \
  --dest-path audit.jsonl \
  --extra-jvm-args "-Dhotvect.trace.features=true"
```

Output shows dependency graph of feature calculations.

### Debugging in IDE

See [How to: Debug Feature Engineering](howto/debug-feature-engineering.md) for complete IDE debugging setup.

---

## Common Issues

### Parent-Child Algorithm Confusion

**Problem**: Unclear which algorithm to train when there are dependencies.

**Rule**: **Always train the PARENT algorithm**. It cascades to children automatically.

**How to identify parent**:
- Parent has `dependencies` in algorithm definition
- Parent uses child outputs for testing/evaluation
- Child is listed in parent's `dependencies` section
- Parent often has no vectorizer (delegates to children)

**Example**:
- Parent: `my-algorithm` (orchestration)
- Child: `my-algorithm-model` (ML training)
- **Train**: `my-algorithm` (parent)

**Why this matters**: Training child directly with parent's override file causes self-dependency bug (TRAIN-003).

---

### Data Directory Pollution

**Problem**: Running commands from wrong directory creates output files in repo.

**Rule**: **Never run training from `hotvect/python/` directory**.

**Correct workflow**:
```bash
# Work from separate directory
cd ~/workspace/tmp

# Activate hotvect environment from anywhere
source ~/workspace/hotvect/python/.venv/bin/activate

# Run training
hv train --output-base-dir ./training-output [other options]
```

**Why**: Output files should not mix with source code. Separate work directory keeps repo clean and prevents accidental commits of large training outputs.

---

### Algorithm JAR Not Found

**Problem**: Command fails with "algorithm JAR not found" or import errors.

**Solution**:

1. **Build algorithm**:
   ```bash
   cd /path/to/algorithm
   mvn clean install -DskipTests
   ```

2. **Verify JAR exists**:
   ```bash
   ls -lh ~/.m2/repository/com/company/my-algorithm/1.0.0/my-algorithm-1.0.0.jar
   ```

3. **Check JAR is valid**:
   ```bash
   unzip -l algorithm.jar | grep algorithm-definition.json
   ```

---

## Performance Issues

### Slow Training

**Problem**: Training takes longer than expected.

**Diagnosis**:
```bash
# Check data size
du -sh /path/to/data-base-dir/*

# Monitor CPU/memory during training
htop  # or Activity Monitor on macOS

# Check I/O wait
iostat -x 1
```

**Solutions** (in order of impact):

1. **Reduce training days** (fastest impact):
   ```json
   {"dependencies": {"my-algo": {"number_of_training_days": 2}}}
   ```

2. **Disable performance tests**:
   ```json
   {"hotvect_execution_parameters": {"performance-test": {"enabled": false}}}
   ```

3. **Increase memory** (reduces GC time):
   ```bash
   --extra-jvm-args "-Xmx64g"
   ```

4. **Use better GC**:
   ```bash
   --extra-jvm-args "-XX:+UseG1GC"
   ```

5. **Increase parallelism**:
   ```bash
   --max-threads 8
   ```

**Expected performance**:
- 1 day data: ~5-10 minutes
- 7 days data: ~30-60 minutes
- 30 days data: ~2-4 hours

(Varies significantly based on data size, features, and hardware)

---

### High Memory Usage

**Problem**: Training using more memory than expected.

**Check current usage**:
```bash
# During training
ps aux | grep java | grep -v grep
```

**Common causes**:
1. Too many features being memoized
2. Large feature stores loaded into memory
3. Inefficient feature transformations

**Solutions**:

1. **Review memoization**: Disable caching for expensive transformations:
   ```java
   builder.withActionTransformation(MyNamespace.feature,
       myFunction,
       false  // disable caching
   );
   ```

2. **Optimize feature stores**: Load only required data

3. **Profile with heap dump**: See [Profile Memory Usage](#profile-memory-usage)

---

## Getting Help

### Before Filing an Issue

1. **Check this guide** for your error message
2. **Check FAQ** for common questions
3. **Search existing issues** on GitHub
4. **Try debugging tools** above

### Filing a Good Issue

Include:
- **Clear description** of the problem
- **Steps to reproduce** (minimal example)
- **Error messages** (full stack trace)
- **Versions**: `hv --version`, `java -version`, OS
- **What you've tried** already
- **Expected vs actual** behavior

### Where to Get Help

- **GitHub Issues**: Bug reports and feature requests
- **Documentation**: This guide, FAQ, How-Tos
- **Code examples**: [Develop a Re-ranker](howto/develop-a-re-ranker-with-hotvect.md)

---

## See Also

- [FAQ](faq.md) - Frequently asked questions
- [Debug Feature Engineering](howto/debug-feature-engineering.md) - IDE debugging workflow
- [CLI Usage](cli/usage.md) - Complete command reference
- [Concepts](highlevel/concepts.md) - Understanding hotvect architecture
