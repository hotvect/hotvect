---
title: Frequently Asked Questions
description: Quick answers to common hotvect questions
tags: [faq, troubleshooting, getting-started, common-issues]
difficulty: beginner
estimated_time: 20 minutes (browsing)
related_docs:
  - ../cli/index.md
  - ../../guides/debug-feature-engineering/index.md
  - ../../concepts/index.md
---

# Frequently Asked Questions

Quick answers to common questions. For detailed guides, see [Guides](../../guides/index.md) and the CLI reference.

## Getting Started

### Q: Do algorithm and hotvect versions need to match?

**A**: **Usually no.** You typically do **not** need to rebuild an algorithm just because you upgraded your local `hotvect` CLI.

**Examples that work fine:**
- hotvect `10.11.x` CLI with algorithm built on hotvect `9.29.1` ✅
- hotvect `10.11.x` CLI with algorithm built on hotvect `9.28.0` ✅

**Recommendation:** Use a **newer** hotvect CLI with **older** algorithms when possible.

**Caveat:** Running a **newer** algorithm JAR on an **older** hotvect CLI is not guaranteed (you may hit `ClassNotFoundException` / `NoSuchMethodError` if the algorithm depends on newer APIs). In that case, upgrade your hotvect CLI and/or rebuild the algorithm.

For mixed-major runs, ensure the algorithm JAR includes the runtime modules it directly uses (for example `hotvect-core` and backend modules like `hotvect-catboost`). Keep `hotvect-api` as `provided`.

For the full compatibility story, see:
- [Hotvect versions and compatibility](../version-compatibility/index.md)

For background and the hv9/hv10 matrix, see:
- [Dynamic JAR Loading and Class Isolation](../../concepts/jar-loading/index.md)
- [hv9/hv10 compatibility matrix](../../concepts/jar-loading/index.md#hv9hv10-compatibility-matrix)

---

### Q: What's the difference between `hv` and `hv-ext` commands?

**A**:
- **`hv`**: Core operations (audit, train, predict, backtest, encode)
- **`hv-ext`**: Extended utilities (compare evaluations, download results, JSONL comparison, data dependency management)

Both are installed together with the hotvect Python package.

---


### Q: How do I know what data my algorithm needs?

**A**: Check the algorithm definition JSON:

### Q: How do I calculate which training dates I need?

**A**: Use this formula:

```
last_test_time - training_lag_days - (number_of_training_days - 1)  to  last_test_time - training_lag_days
```

**Example**:
- `last_test_time`: 2025-08-09
- `training_lag_days`: 1
- `number_of_training_days`: 7
- Training dates: 2025-08-08, 2025-08-07, ..., 2025-08-02 (7 days total)

---

## Data Management

### Q: How do I download training data from S3?

**A**: Use `hv-ext data-dependency --download-all`:

```bash
# Download data for algorithm
hv-ext data-dependency --download-all \
  --repo-url https://github.com/example-org/example-algorithm.git \
  --git-reference v77.0.0 \
  --s3-base-dir s3://example-bucket/tables \
  --local-data-dir ./data \
  --scratch-dir ./temp \
  --last-test-time 2025-08-09
```

By default, `hv-ext data-dependency` only lists required dependencies as JSON; pass `--download-all` (or `--download <data_prefix>`) to download.

---

## Troubleshooting

### Q: Training is very slow. How can I speed it up?

**A**: Try these optimizations (in order of impact):

1. **Reduce training days**: Use override file
   ```json
   {
     "dependencies": {
       "my-algo": {
         "number_of_training_days": 2
       }
     }
   }
   ```

2. **Disable performance tests**:
   ```json
   {
     "hotvect_execution_parameters": {
       "performance-test": {"enabled": false}
     }
   }
   ```


### Q: I'm getting OutOfMemoryError. What should I do?

**A**: Increase JVM heap size with `--extra-jvm-args`:

```bash
hv train --extra-jvm-args "-XX:MaxRAMPercentage=80" [other options]
```

### Q: My features have wrong values. How do I debug?

**A**: Use the audit command to inspect feature values:

```bash
hv audit \
  --algorithm-jar algorithm.jar \
  --algorithm-name my-algo \
  --source-path test-data.jsonl \
  --dest-path audit.jsonl

# Inspect specific record
cat audit.jsonl | jq 'select(.id == "record_123")'

# List all features
cat audit.jsonl | head -1 | jq '.features | keys'

# Show specific namespace values
cat audit.jsonl | head -1 | jq '.features.my_namespace'
```

See [How to: Debug Feature Engineering](../../guides/debug-feature-engineering/index.md) for detailed debugging workflow.

### Q: Training fails with "FileNotFoundException" for data. What's wrong?

**A**: The training data for the calculated date doesn't exist.

**Common causes**:
1. Data not downloaded from S3
2. Wrong `--last-test-time` (no data for that date)
3. Incorrect `training_lag_days` causing wrong date calculation
4. Wrong `--data-base-dir` path

**Solution**: Verify required dates exist:
```bash
# Calculate required dates (see Q: How do I calculate which training dates I need?)
# Then check if data exists
ls -la /path/to/data-base-dir/data_prefix/dt=2025-08-08
```

If missing, download with `hv-ext data-dependency --download-all`.

---

## Development

### Q: How do I add a new feature to my algorithm?

**A**: Follow the development workflow:

1. **Define feature transformation** in Java (implement Computing interface or transformation function)
2. **Register transformation** in algorithm factory
3. **Rebuild algorithm JAR**: `mvn clean install`
4. **Generate audit** to verify feature values: `hv audit ...`
5. **Run local training** to test: `hv train ...`
6. **Compare with baseline** using backtest: `hv backtest ...`

See [How to: Develop a Re-ranker](../../guides/develop-algorithms/index.md) for complete guide.

### Q: How do I debug feature engineering code in my IDE?

**A**: Run the hotvect-offline-util main class with your algorithm JAR in classpath.

**Quick steps**:
1. Create run configuration in IDE
2. Set main class: `com.hotvect.offlineutils.commandline.Main`
3. Add hotvect-offline-util JAR to classpath
4. Set program arguments (`--algorithm-jar`, `--audit`, etc.)
5. Set JVM args (`-XX:MaxRAMPercentage=80`, `-XX:+ExitOnOutOfMemoryError`)

See [How to: Debug Feature Engineering](../../guides/debug-feature-engineering/index.md) for detailed IDE setup with screenshots.


**Algorithm audit test** (verify features unchanged):
```bash
# Generate audit for baseline
hv audit --algorithm-jar old-version.jar ... --dest-path old-audit.jsonl

# Generate audit for new version
hv audit --algorithm-jar new-version.jar ... --dest-path new-audit.jsonl

# Compare
hv-ext compare-jsonl old-audit.jsonl new-audit.jsonl
```

---

## Advanced Topics

### Q: How do I enable caching for backtests?

**A**: Use the backtest-only caching flags:

- `--cache <local_path_or_s3_uri>` enables caching
- `--cache-scope major|minor|patch|hyperparam` controls cache sharing across **algorithm versions** (default: `hyperparam`)
- `--cache-refresh` forces recompute while still writing fresh results back to the cache (requires `--cache`)

Example (SageMaker; recommended):
```bash
hv backtest \
  --git-reference v81.0.6 \
  --algo-repo-url https://github.com/example-org/example-algorithm.git \
  --output-base-dir /tmp/out \
  --scratch-dir /tmp/scratch \
  --last-test-time 2026-01-07 \
  --sagemaker-config sagemaker-config.json \
  --auto-attach-data-default-s3-base s3://example-bucket/tables/ \
  --cache s3://example-bucket/hotvect-cache/ \
  --cache-scope hyperparam
```

**Important (SageMaker):** use an `s3://example-bucket` cache. A local cache path (e.g. `/tmp/cache`) only exists on the container filesystem and will not persist across jobs.

See [How to Use Hotvect Caching](../../guides/caching/index.md) for cache layout and scope behavior.

### Q: What's the difference between `with_parameter` and `cache` in algorithm overrides?

**A**:

**`with_parameter`** (recommended for dependencies):
- Only works with prediction parameters
- Must be used in `dependencies` section
- Skips all training steps for that dependency
- Raises error if parameters not found

```json
{
  "dependencies": {
    "my-model": {
      "hotvect_execution_parameters": {
        "with_parameter": "s3://example-bucket/path/parameters.zip"
      }
    }
  }
}
```

**`cache`** (advanced usage):
- Works with any output (generate-state, encode, train)
- Falls back to generating if not found
- Can be used at top level or in dependencies

```json
{
  "hotvect_execution_parameters": {
    "train": {
      "cache": "s3://example-bucket/path/parameters.zip"
    }
  }
}
```

See [How to: Reuse Existing Outputs](../../guides/reuse-outputs/index.md).

### Q: I enabled caching, but I think Hotvect is using stale outputs. What should I do?

**A**:
- If you are changing code without changing `algorithm_version`, caches may be unsafe to reuse. Prefer bumping the algorithm version string, or force recompute.
- To force recompute while still writing fresh artifacts back to cache:
  - Backtest: add `--cache-refresh`
  - Algorithm definition: set `hotvect_execution_parameters.cache_refresh=true`
- Remember caches are segmented by `parameter_version` (default: `last_test_date_YYYY-MM-DD`), so changing `--last-test-time` naturally isolates caches.

See [How to Use Hotvect Caching](../../guides/caching/index.md).

### Q: What's the difference between outer (parent) and inner (child) algorithms, and which CLI commands can I run on each?

**A**: Outer algorithms (sometimes called parents) are the public entrypoints that orchestrate evaluation logic, often adding heuristics or business rules, and typically don't train ML models themselves. Inner algorithms (children) either train ML models or generate state through aggregation/fitting/transformation. Low-level CLI commands that require feature transformations—`hv audit` and `hv encode`—must point at an algorithm that trains ML models (has `training_command` in its definition, commonly inner algorithms). `hv generate-state` requires an algorithm with `generator_factory_classname` (algorithms that produce state parameters). High-level orchestration commands such as `hv train`, `hv predict`, `hv backtest`, and `hv performance-test` can be run against outer or inner algorithms; when targeting an outer algorithm, hotvect automatically walks the dependency graph and trains dependencies as needed. See [Concepts](../../concepts/index.md#outer-vs-inner-algorithms) and [CLI Usage](../cli/index.md#commands-for-training-algorithms) for details on discovering the correct algorithm names.

### Q: How do I use feature logging with composite algorithms?

**A**: Use the `--log-features` flag during prediction to capture feature extraction from all dependencies:

```bash
hv predict \
  --algorithm-jar parent-algorithm.jar \
  --algorithm-name parent-algorithm \
  --parameter-path trained-parameters.zip \
  --source-path test-data.jsonl \
  --dest-path predictions.jsonl \
  --log-features
```

This is especially useful for debugging composite algorithms where the parent has no vectorizer (delegates to children).

**Alternative (v9-style):** If you need separate feature audit files without predictions, use:
```bash
hv audit \
  --algorithm-jar parent-algorithm.jar \
  --algorithm-name parent-algorithm \
  --source-path test-data.jsonl \
  --dest-path feature-audit.jsonl
```

---

## Getting More Help

### Q: Where can I find more information?

**A**:
- **Concepts**: [Concepts and Terminologies](../../concepts/index.md)
- **Guides**: [Guides index](../../guides/index.md)
- **CLI Reference**: [CLI Usage](../cli/index.md)
- **Example Code**: [Develop a Re-ranker Guide](../../guides/develop-algorithms/index.md)

### Q: I found a bug or have a feature request. What should I do?

**A**: File an issue on the GitHub repository with:
- Clear description of the issue or feature request
- Steps to reproduce (for bugs)
- Error messages and logs
- Versions (`hv --version`, Java version, OS)
- Expected vs actual behavior

### Q: Can I contribute to hotvect?

**A**: Yes! Hotvect is open source. Contributions are welcome:
- Bug fixes
- Documentation improvements
- Feature implementations
- Example algorithms

Check the repository for contribution guidelines.

---

## See Also

- [CLI Usage](../cli/index.md) - Complete command reference
- [Concepts](../../concepts/index.md) - Core hotvect concepts
- [Develop a Re-ranker](../../guides/develop-algorithms/index.md) - Comprehensive development guide
- [Debug Feature Engineering](../../guides/debug-feature-engineering/index.md) - Debugging workflow
