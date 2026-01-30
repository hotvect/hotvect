---
title: Frequently Asked Questions
description: Quick answers to common hotvect questions
tags: [faq, troubleshooting, getting-started, common-issues]
difficulty: beginner
estimated_time: 20 minutes (browsing)
related_docs:
  - ./cli/usage.md
  - ./howto/debug-feature-engineering.md
  - ./highlevel/concepts.md
---

# Frequently Asked Questions

Quick answers to common questions. For detailed guides, see [How-To Guides](howto/) and CLI Reference.

## Getting Started

### Q: Do algorithm and hotvect versions need to match?

**A**: **No.** Hotvect is backward compatible across versions - you can use any hotvect CLI version with algorithms built on older (or even newer) hotvect versions.

**Examples that work fine:**
- hotvect 9.31.2 CLI with algorithm built on hotvect 9.29.1 ✅
- hotvect 10.0.0 CLI with algorithm built on hotvect 9.28.0 ✅
- Even across major versions ✅

**Never rebuild algorithms to "match" hotvect versions.** Just use the currently installed hotvect version for all operations (audit, train, predict, backtest, etc.).

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

**A**: Use `hv-ext download-data-dependency`:

```bash
# Download data for algorithm
hv-ext download-data-dependency \
  --repo-url https://github.com/company/algorithm.git \
  --git-reference v77.0.0 \
  --s3-base-dir s3://bucket/tables \
  --local-data-dir ./data \
  --scratch-dir ./temp \
  --last-test-time 2025-08-09
```

This analyzes algorithm dependencies and downloads exactly what's needed.

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
hv train --extra-jvm-args "-Xmx64g" [other options]
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

See [How to: Debug Feature Engineering](howto/debug-feature-engineering.md) for detailed debugging workflow.

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

If missing, download with `hv-ext download-data-dependency`.

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

See [How to: Develop a Re-ranker](howto/develop-a-re-ranker-with-hotvect.md) for complete guide.

### Q: How do I debug feature engineering code in my IDE?

**A**: Run the hotvect-offline-util main class with your algorithm JAR in classpath.

**Quick steps**:
1. Create run configuration in IDE
2. Set main class: `com.hotvect.offlineutils.commandline.Main`
3. Add hotvect-offline-util JAR to classpath
4. Set program arguments (`--algorithm-jar`, `--audit`, etc.)
5. Set JVM args (`-Xmx32g`, `-XX:+ExitOnOutOfMemoryError`)

See [How to: Debug Feature Engineering](howto/debug-feature-engineering.md) for detailed IDE setup with screenshots.


**Algorithm audit test** (verify features unchanged):
```bash
# Generate audit for baseline
hv audit --algorithm-jar old-version.jar ... --dest-path old-audit.jsonl

# Generate audit for new version
hv audit --algorithm-jar new-version.jar ... --dest-path new-audit.jsonl

# Compare
hv-ext jsonl-compare old-audit.jsonl new-audit.jsonl
```

---

## Advanced Topics

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
        "with_parameter": "s3://bucket/path/parameters.zip"
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
      "cache": "s3://bucket/path/parameters.zip"
    }
  }
}
```

See [How to: Reuse Existing Outputs](howto/use-previous-outputs.md).

### Q: What's the difference between outer and inner algorithms, and which CLI commands can I run on each?

**A**: Outer algorithms (sometimes called parents) are the public entrypoints that orchestrate evaluation logic, often adding heuristics or business rules, and typically don't train ML models themselves. Inner algorithms (children) either train ML models or generate state through aggregation/fitting/transformation. Low-level CLI commands that require feature transformations—`hv audit` and `hv encode`—must point at an algorithm that trains ML models (has `training_command` in its definition, commonly inner algorithms). `hv generate-state` requires an algorithm with `generator_factory_classname` (algorithms that produce state parameters). High-level orchestration commands such as `hv train`, `hv predict`, `hv backtest`, and `hv performance-test` can be run against outer or inner algorithms; when targeting an outer algorithm, hotvect automatically walks the dependency graph and trains dependencies as needed. See [Concepts](highlevel/concepts.md#outer-vs-inner-algorithms) and [CLI Usage](cli/usage.md#commands-for-training-algorithms) for details on discovering the correct algorithm names.

### Q: How do I use feature logging with composite algorithms?

**A**: In hotvect v9, feature logging must be done separately using the audit command:

```bash
# Step 1: Generate feature audit
hv audit \
  --algorithm-jar parent-algorithm.jar \
  --algorithm-name parent-algorithm \
  --source-path test-data.jsonl \
  --dest-path feature-audit.jsonl

# Step 2: Run predictions
hv predict \
  --algorithm-jar parent-algorithm.jar \
  --algorithm-name parent-algorithm \
  --parameter-path trained-parameters.zip \
  --source-path test-data.jsonl \
  --dest-path predictions.jsonl
```

This captures feature extraction from all dependencies. Useful for debugging composite algorithms where parent has no vectorizer (delegates to children).

**Note:** The `--log-features` flag is available in hotvect v10+.

---

## Getting More Help

### Q: Where can I find more information?

**A**:
- **Concepts**: [Concepts and Terminologies](highlevel/concepts.md)
- **How-To Guides**: [How-To Index](howto/)
- **CLI Reference**: [CLI Usage](cli/usage.md)
- **Example Code**: [Develop a Re-ranker Guide](howto/develop-a-re-ranker-with-hotvect.md)

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

- [CLI Usage](cli/usage.md) - Complete command reference
- [Concepts](highlevel/concepts.md) - Core hotvect concepts
- [Develop a Re-ranker](howto/develop-a-re-ranker-with-hotvect.md) - Comprehensive development guide
- [Debug Feature Engineering](howto/debug-feature-engineering.md) - Debugging workflow
