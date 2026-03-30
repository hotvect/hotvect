---
title: Troubleshooting Guide
description: Diagnose and fix common hotvect issues with step-by-step solutions
tags: [troubleshooting, errors, debugging, solutions, problems]
difficulty: beginner
estimated_time: varies
related_docs:
  - ../faq/index.md
  - ../../guides/debug-feature-engineering/index.md
  - ../cli/index.md
  - ../../design/direct-python-workers/index.md
next_steps:
  - Check FAQ for additional questions
  - Debug feature engineering code
  - File issue if problem persists
---

# Troubleshooting Guide

This guide helps you diagnose and fix common issues with hotvect. If you don't find your issue here, check the [FAQ](../faq/index.md) or file an issue on GitHub.

## Quick Diagnosis

### Training Fails Immediately

Run through this checklist:

- [ ] **hotvect installed**: `hv --version` should show version number
- [ ] **Algorithm JAR exists**: `ls -lh /path/to/algorithm.jar`
- [ ] **Training data exists**: `ls /path/to/data-base-dir/data_prefix/dt=YYYY-MM-DD`
- [ ] **Sufficient memory**: `free -h` (need at least 16GB available)
- [ ] **Sufficient disk space**: `df -h` (need space for outputs)

**If still failing**: Check [Error Reference](#error-reference) for specific error message.

### Training Runs But No Output

Check these:

- [ ] **Output directory writable**: `ls -ld /path/to/output-dir`
- [ ] **Check logs**: Look for error messages in console output
- [ ] **Verify algorithm definition**: JAR contains valid algorithm-definition.json
- [ ] **Check data dependency spec**: Required data exists for calculated dates

**If still no output**: Enable debug logging (see [Enable Debug Logging](#enable-debug-logging))

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

See [Debugging Tools](#debugging-tools) for detailed workflow.

### Local Server Fails to Start

Run through this checklist for `hv serve` or `hv worker serve`:

- [ ] **Port is free**: `lsof -nP -iTCP:<port> -sTCP:LISTEN`
- [ ] **Parameters ZIP exists**: `ls -lh /path/to/parameters.zip`
- [ ] **Algorithm and params match**: use the same trained algorithm name you passed during training
- [ ] **Selected worker scope is usable**: if using `hv worker serve --scope auto`, confirm the preferred scope has a `litserve` or `direct_workers` block
- [ ] **Interpreter is correct**: if worker-only deps live in another venv, set `HOTVECT_PYTHON_EXECUTABLE=/path/to/python`
- [ ] **Startup timeout is high enough**: increase `startup_timeout_ms` in the selected worker config when model extraction or worker init is slow

**If still failing**: Check [Local Serve Errors](#local-serve-errors) below and compare the runtime contract in [Direct Python Workers](../../design/direct-python-workers/index.md).

---

## Error Reference

### Training Errors

#### TRAIN-001: FileNotFoundException for training data

**Error Message**:
```
java.io.FileNotFoundException: /path/to/data/example_training_data/dt=2025-08-09
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
  s3://example-bucket/tables/example_training_data/dt=2025-08-08 \
  /path/to/data/example_training_data/dt=2025-08-08
```

Or use the automated tool:
```bash
hv-ext data-dependency --download-all \
  --repo-url https://github.com/example-org/example-algorithm.git \
  --git-reference v77.0.0 \
  --s3-base-dir s3://example-bucket/tables \
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

**Prevention**: Always use `hv-ext data-dependency --download-all` before training.

**See Also**: [FAQ: How do I calculate which training dates I need?](../faq/index.md#q-how-do-i-calculate-which-training-dates-i-need)

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
hv train --extra-jvm-args "-XX:MaxRAMPercentage=80" [other options]
```

**Memory guidelines**:
- 1 day data: 16-32GB
- 7 days data: 64-128GB
- 30 days data: 256GB+

##### Solution 2: Use Better Garbage Collector

```bash
hv train --extra-jvm-args "-XX:MaxRAMPercentage=80,-XX:+UseG1GC" [other options]
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

**See Also**: [FAQ: I'm getting OutOfMemoryError](../faq/index.md#q-im-getting-outofmemoryerror-what-should-i-do)

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
hv train --algorithm-name example-parent-algorithm \
  --algorithm-override parent-override.json ...

# WRONG - training child with parent's override
hv train --algorithm-name example-parent-algorithm-child-model \
  --algorithm-override parent-override.json ...
```

**How to identify parent**:
- Parent has `dependencies` in algorithm-definition.json
- Parent orchestrates evaluation/testing
- Child is listed in parent's `dependencies` section

**Prevention**: Understand parent-child algorithm architecture before training.

**See Also**: [FAQ: Outer (parent) vs inner (child) algorithms](../faq/index.md#q-whats-the-difference-between-outer-parent-and-inner-child-algorithms-and-which-cli-commands-can-i-run-on-each)

---

### Local Serve Errors

#### SERVE-001: Address already in use / bind failure

**Error Message**:
```
java.net.BindException: Address already in use
# or
OSError: [Errno 48] Address already in use
```

**Meaning**: Another process is already listening on the requested `--host` / `--port`.

**Solutions**:
1. Find the current listener:
   ```bash
   lsof -nP -iTCP:<port> -sTCP:LISTEN
   ```
2. Stop that process or pick a different port:
   ```bash
   hv serve --port 18080 ...
   # or
   hv worker serve --port 18080 ...
   ```
3. If a previous debug server crashed or was backgrounded, make sure the old process is really gone before retrying.

---

#### SERVE-002: `Server started but health check timed out after ...`

**Error Message**:
```
RuntimeError: Server started but health check timed out after 30s
```

**Meaning**: The server process started, but `/health` did not return HTTP 200 before the startup timeout expired.

**Common Causes**:
1. Model extraction or worker initialization is slow
2. Python worker startup is blocked on imports or dependency loading
3. The server is logging startup errors but has not exited yet

**Solutions**:
1. Read the startup logs printed in the same terminal; `hv` streams subprocess stdout/stderr directly.
2. For `hv worker serve`, increase `startup_timeout_ms` in the selected `litserve` config. Default: `30000`.
3. For `hv serve`, remember the CLI always waits up to `120` seconds for `/health`; if it still times out, inspect JVM startup logs and reduce startup work.
4. Use `--keep-temp-dir` with `hv worker serve` when you need to inspect the extracted model and schema on disk.

**See Also**: [CLI reference: `serve`](../cli/index.md#10-serve) and [CLI reference: `worker serve`](../cli/index.md#11-worker-serve)

---

#### SERVE-003: `hv worker serve` starts with the wrong Python environment

**Symptoms**:
- `ModuleNotFoundError`
- unexpected package versions
- worker imports succeed in one shell but fail under `hv worker serve`

**Meaning**: The worker server was launched with a different Python interpreter than the one you expected.

**Interpreter resolution order**:
1. `HOTVECT_PYTHON_EXECUTABLE`
2. `litserve.python_executable`
3. the current `sys.executable`

**Solutions**:
1. Set `HOTVECT_PYTHON_EXECUTABLE` explicitly before starting the server:
   ```bash
   export HOTVECT_PYTHON_EXECUTABLE=/path/to/python
   hv worker serve ...
   ```
2. If you want the interpreter to be part of the algorithm config, set `litserve.python_executable` in the selected scope instead.
3. Verify the selected interpreter can import the worker dependencies outside `hv` first:
   ```bash
   /path/to/python -c "import tensorflow"
   ```

**See Also**: [Direct Python Workers](../../design/direct-python-workers/index.md)

---

#### SERVE-004: `hv worker serve --scope auto` picks a scope that is disabled or unusable

**Error Message**:
```
ValueError: algorithm_parameters.realtime.litserve or .direct_workers is not configured
# or
ValueError: Algorithm definition does not contain a usable worker serve config. Expected algorithm_parameters.{realtime,batch}.litserve or .direct_workers
```

**Meaning**: `--scope auto` currently chooses the first preferred scope with any usable worker-serve config (`realtime`, then `batch`). `hv worker serve` prefers `litserve`, but it can also fall back to `direct_workers` for overlapping runtime knobs. If neither scope declares either block, it fails fast.

**Solutions**:
1. Pass `--scope` explicitly while debugging:
   ```bash
   hv worker serve --scope batch ...
   ```
2. Use `--algorithm-override` to add or move the intended `litserve` or `direct_workers` config into the selected scope.
3. Keep worker runtime knobs (`python_executable`, `startup_timeout_ms`, `env`, etc.) in the same selected scope that you expect `hv worker serve` to use. If both blocks exist, `litserve` wins for worker-only HTTP startup.

---

#### SERVE-005: `hv worker serve` says `algorithm_parameters.backend must be a non-empty string`

**Error Message**:
```
ValueError: algorithm_parameters.backend must be a non-empty string
```

**Meaning**: `hv worker serve` now reads the backend from the top-level `algorithm_parameters.backend` field. Older definitions that still use `algorithm_parameters.workers.backend` will fail this validation until the backend is moved.

**Solutions**:
1. Move the backend to the top level in the algorithm definition:
   ```json
   {
     "algorithm_parameters": {
       "backend": "tensorflow"
     }
   }
   ```
2. If you only need a local experiment, use `--algorithm-override` to patch the backend into `algorithm_parameters.backend`.
3. Re-run `hv worker serve` after updating the definition so the worker contract, docs, and local override behavior all agree on the same backend field.

---

### AWS / SageMaker Errors

#### AWS-001: `ExpiredTokenException` (AWS credentials expired)

**Error Message**:
```
ExpiredTokenException: The security token included in the request is expired
```

**Meaning**: Your AWS credentials have expired (common with SSO / short-lived assumed roles).

**Solutions**:
- Refresh credentials using your org’s mechanism (SSO/device auth/credential helper) and retry.
- Sanity-check:
  ```bash
  aws sts get-caller-identity
  ```

---

#### SM-001: `CapacityError` / insufficient EC2 capacity

**Error Message**:
```
CapacityError: Unable to provision requested ML compute capacity. Please retry using a different ML instance type.
```

**Meaning**: SageMaker could not allocate your requested instance type in the region/AZ at that time.

**Solutions**:
- Retry later (capacity fluctuates).
- Use a different instance type by overriding SageMaker resources:
  - `sagemaker_training_job_definition.ResourceConfig.InstanceType` (preferred)
  - or legacy `sagemaker_execution_parameters.instance_type`
- If you use a SageMaker template, make sure it doesn't force an unintended `ResourceConfig.InstanceType`.

---

#### SM-002: `s3_uri_result_file` is present but the object 404s

**Error Message**:
```
An error occurred (404) when calling the HeadObject operation: Not Found
```

**Meaning**: The job failed before it wrote `result.json` (often during `evaluate`), but the expected S3 URI was still recorded.

**Solutions**:
- Use `HyperParameters.s3_uri_python_log_file` to find the stack trace.
- Use `HyperParameters.s3_uri_metadata` to inspect stage logs and the effective algorithm definition.

---

### Caching Errors

#### CACHE-001: `cache_refresh requires cache_base_dir`

**Error Message**:
```
ValueError: cache_refresh requires cache_base_dir
```

**Meaning**: You asked Hotvect to ignore cache reads (`cache_refresh=true` / `--cache-refresh`) but did not enable caching (`cache_base_dir` / `--cache`).

**Solutions**:
- Backtest CLI: add `--cache <local_path_or_s3_uri>` when using `--cache-refresh`
- Algorithm definition: set `hotvect_execution_parameters.cache_base_dir`

See [How to Use Hotvect Caching](../../guides/caching/index.md).

---

#### CACHE-002: `cache_scope=major|minor` requires semver-like algorithm version

**Error Message**:
```
ValueError: cache_scope=minor requires a semver-like algorithm_version (X.Y.Z); got ...
```

**Meaning**: `cache_scope=major` and `cache_scope=minor` only work when `algorithm_version` contains a semver-like substring (`X.Y.Z`).

**Solutions**:
- Use `--cache-scope patch` or `--cache-scope hyperparam`
- Or ensure `algorithm_version` contains an `X.Y.Z` substring (e.g. `82.2.35`)

---

#### CACHE-003: `with_parameter` was set, but the zip was not available

**Error Message**:
```
"with_parameter" option was used, but the specified parameter ... was not available
```

**Meaning**: `hotvect_execution_parameters.with_parameter` is strict pinning. The zip must exist (local or `s3://example-bucket`) and be readable.

**Solutions**:
- Verify the S3 key exists and the current AWS role can read it
- If you want best-effort reuse, use caching (`cache_base_dir`) instead of `with_parameter`

See [Reuse existing outputs](../../guides/reuse-outputs/index.md).

---

#### CACHE-004: Local cache path used on SageMaker does not persist across jobs

**Symptom**: The second SageMaker job does not get cache hits even though you used `--cache /tmp/...`.

**Meaning**: Local paths are on the ephemeral container filesystem and are not shared between jobs.

**Solution**: Use an `s3://example-bucket` cache base prefix for SageMaker (for example `--cache s3://example-bucket/hotvect-cache/`).

---

### Prediction Errors

#### PREDICT-001: Missing CatBoost model parameters key

**Error Message**:
```
Missing CatBoost model parameters. Expected key 'model_parameter/model.parameter'. Available keys: [...]
```

**Meaning**: The `parameters.zip` does not contain the CatBoost model file at the key expected by hotvect v10 CatBoost factories.

**Common Causes**:
1. Reusing a legacy parameters zip that only contains `model.parameter`
2. Hand-built parameters zip with a non-standard layout

**Solutions**:

##### Solution 1: Create a compatibility parameters zip (layout-only)

Create a new zip that contains both paths with identical bytes:

```bash
python - <<'PY'
import zipfile

src = "parameters.zip"
dst = "parameters.compat.zip"

with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
    for info in zin.infolist():
        zout.writestr(info, zin.read(info.filename))

    names = set(zin.namelist())
    if "model_parameter/model.parameter" not in names and "model.parameter" in names:
        zout.writestr("model_parameter/model.parameter", zin.read("model.parameter"))

print("wrote", dst)
PY
```

Then point `with_parameter` / `cache` / your pipeline at `parameters.compat.zip`.

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
     jq '{train_data_prefix, test_data_prefix, train_data_spec, test_data_spec, number_of_training_days, training_lag_days, source_data}'
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

**Prevention**: Use `hv-ext data-dependency --download-all` which handles partitioning automatically.

---

#### DATA-002: `hv-ext data-dependency` fails with "Too many open files" or "Connection pool is full"

**Error Message**:
```
[Errno 24] Too many open files
Connection pool is full, discarding connection
```

**Meaning**: Too many concurrent downloads for local file descriptor limits or the S3 client connection pool.

**Solutions**:
1. **Reduce parallelism**: lower `--max-parallel-downloads` (for example, `--max-parallel-downloads 2`).
2. **Download less**: use `--sample-ratio` to fetch a smaller subset for iteration.
3. **Narrow scope**: use `--download <name>` to fetch only a specific dependency.

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

See [How to: Debug Feature Engineering](../../guides/debug-feature-engineering/index.md) for complete IDE debugging setup.

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
- Parent: `example-parent-algorithm` (orchestration)
- Child: `example-parent-algorithm-child-model` (ML training)
- **Train**: `example-parent-algorithm` (parent)

**Why this matters**: Training child directly with parent's override file causes self-dependency bug (TRAIN-003).

---

### Data Directory Pollution

**Problem**: Running commands from wrong directory creates output files in repo.

**Rule**: **Never run training from `hotvect/python/` directory**.

**Correct workflow**:
```bash
# Work from separate directory
cd /path/to/workdir

# Activate hotvect environment from anywhere
source /path/to/hotvect/python/.venv/bin/activate

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
   --extra-jvm-args "-XX:MaxRAMPercentage=80"
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
- **Code examples**: [Develop a Re-ranker](../../guides/develop-algorithms/index.md)

---

## See Also

- [FAQ](../faq/index.md) - Frequently asked questions
- [Debug Feature Engineering](../../guides/debug-feature-engineering/index.md) - IDE debugging workflow
- [CLI Usage](../cli/index.md) - Complete command reference
- [Concepts](../../concepts/index.md) - Understanding hotvect architecture
