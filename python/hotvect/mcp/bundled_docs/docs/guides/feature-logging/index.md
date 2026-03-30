---
title: How to Predict with Feature Logging
description: Debug composite algorithms by logging feature extraction during prediction
tags: [prediction, debugging, features, composite-algorithms, logging]
difficulty: advanced
estimated_time: 15 minutes
prerequisites:
  - Composite algorithm (parent-child structure)
  - Trained model parameters available
  - Test data in expected format
  - Understanding of algorithm dependencies
related_docs:
  - ../debug-feature-engineering/index.md
  - ../feature-audits/index.md
  - ../develop-algorithms/index.md
related_commands:
  - hv predict --log-features
  - hv audit
next_steps:
  - Analyze logged features with jq
  - Compare with expected feature values
  - Debug decoder issues in outer algorithm
---

# Predict with Feature Logging

## Overview

The `--log-features` flag for `hv predict` enables feature extraction logging during prediction, designed specifically for debugging composite algorithms where data flows through multiple layers (outer algorithm → inner algorithm dependencies).

This feature is particularly useful when you need to:
- Verify that an outer algorithm's decoder correctly processes test data
- Inspect intermediate feature values from dependency algorithms
- Debug composite algorithm behavior end-to-end with a trained model

## Problem Statement

When working with composite algorithms (e.g., `example-parent-algorithm` → `example-parent-algorithm-child-model`):

- **Standard `hv audit`** can only audit algorithms that have a vectorizer/transformer directly
- **Composite outer algorithms** typically lack vectorizers (they delegate to dependencies)
- **Need to test** that the outer algorithm's decoder works correctly while still seeing features

The `--log-features` flag solves this by running the full prediction pipeline while capturing and logging feature extraction from all dependency algorithms.

## Usage

### Basic Command

```bash
hv predict \
  --log-features \
  --algorithm-jar ~/.m2/repository/.../example-parent-algorithm-1.2.3.jar \
  --algorithm-name example-parent-algorithm \
  --parameter-path /path/to/trained-parameters.zip \
  --source-path /path/to/test-data.json.gz \
  --dest-path prediction-with-features.jsonl
```

### Example: Debugging Composite Algorithm

```bash
cd /path/to/workdir
source /path/to/hotvect/python/.venv/bin/activate

# Run prediction with feature logging
hv predict \
  --log-features \
  --algorithm-jar ~/.m2/repository/.../example-parent-algorithm-1.2.3.jar \
  --algorithm-name example-parent-algorithm \
  --parameter-path ./train-output/example-parent-algorithm@1.2.3-2day/last_test_date_2025-10-25/example-parent-algorithm@1.2.3-2day@last_test_date_2025-10-25.parameters.zip \
  --source-path /path/to/test-data/dt=2025-10-15/part-00000*.json.gz \
  --dest-path prediction-with-features.jsonl
```

## Output Format

### Without `--log-features` (standard prediction)

```json
{
  "example_id": "00000000-0000-0000-0000-000000000000",
  "result": [
    {
      "action_id": "ABC123H01-J11",
      "rank": 0,
      "score": 0.09722073761602304,
      "reward": 0.0
    },
    {
      "action_id": "DEF456T02-J00",
      "rank": 1,
      "score": 0.06079908387975933,
      "reward": 0.0
    }
  ]
}
```

### With `--log-features`

```json
{
  "example_id": "00000000-0000-0000-0000-000000000000",
  "result": [
    {
      "action_id": "ABC123H01-J11",
      "rank": 0,
      "score": 0.09722073761602304,
      "reward": 0.0
    },
    {
      "action_id": "DEF456T02-J00",
      "rank": 1,
      "score": 0.06079908387975933,
      "reward": 0.0
    }
  ],
  "feature_audit": {
    "example-parent-algorithm-child-model": {
      "actions": [
        {
          "action_id": "ABC123H01-J11",
          "features": {
            "feature_numeric_01": 22.0,
            "feature_categorical_01": "FAKE_CATEGORY_A",
            "feature_categorical_02": "FAKE_VALUE_A",
            "feature_numeric_02": 49.99,
            "feature_numeric_03": 5,
            "feature_numeric_04": 2573700.0,
            "feature_numeric_05": 7.0,
            "feature_numeric_06": 515782.0
          }
        },
        {
          "action_id": "DEF456T02-J00",
          "features": {
            "feature_numeric_01": 18.0,
            "feature_categorical_01": "FAKE_CATEGORY_B",
            "feature_categorical_02": "FAKE_VALUE_B",
            "feature_numeric_02": 89.99,
            "feature_numeric_03": 12,
            "feature_numeric_04": 1234567.0,
            "feature_numeric_05": 3.0,
            "feature_numeric_06": 123456.0
          }
        }
      ]
    }
  }
}
```

### Output Structure

- **`feature_audit`**: Top-level node containing feature extraction data (only present when `--log-features` is used)
- **Algorithm namespacing**: Features are grouped by algorithm name (e.g., `example-parent-algorithm-child-model`)
- **Actions array**: Contains feature data for each action/candidate, with `action_id` for matching
- **Features**: Exact same format as `hv audit` output for consistency

## How It Works

1. **CLI flag parsing**: The `--log-features` flag is passed from Python CLI to Java
2. **Post-construction configuration**: After algorithms are instantiated, `setLogFeatures(true)` is called on all `StandardRankingTransformer` instances
3. **Feature capture**: During prediction, transformers capture computed features alongside normal processing
4. **Recursive application**: Feature logging is automatically enabled for ALL dependency algorithms that use `StandardRankingTransformer`
5. **Output generation**: Features are collected from all algorithms and included in the prediction JSONL output

## Comparison: `hv audit` vs `hv predict --log-features`

| Aspect | `hv audit` | `hv predict --log-features` |
|--------|------------|----------------------------|
| **Purpose** | Inspect feature extraction only | Full prediction with feature debugging |
| **Input** | Raw test data | Raw test data |
| **Decoder** | Inner algorithm's decoder | Outer algorithm's decoder ✅ |
| **Features** | Single algorithm only | All dependencies recursively ✅ |
| **Predictions** | No scores/rankings | Full prediction scores ✅ |
| **Trained model** | Not required | Required (parameter file) ✅ |
| **Use case** | Debug feature engineering | Debug composite algorithms ✅ |

## When to Use Each Approach

### Use `hv audit` when:
- Debugging feature engineering for a single algorithm
- Inspecting features without needing a trained model
- Comparing feature extraction between algorithm versions
- Quick feature inspection during development

### Use `hv predict --log-features` when:
- **Debugging composite algorithms** (outer/inner pattern) ✅
- Verifying outer algorithm's decoder processes data correctly
- Need both predictions AND features together
- End-to-end validation with a trained model
- Catching bugs in data flow through algorithm dependencies

## Requirements and Limitations

### Requirements

1. All dependency algorithms must use `StandardRankingTransformer`
2. A trained model (parameter file) is required
3. The `--log-features` flag is only valid with the `predict` command

### Limitations

**Transformer type validation**: If any dependency algorithm does NOT use `StandardRankingTransformer`, the command will fail with a clear error:

```
Error: Feature logging requires StandardRankingTransformer, but algorithm
'some-custom-algorithm' uses 'CustomTransformer'. Feature logging is not
supported for custom transformers.
```

This is intentional to ensure consistent feature logging behavior across all algorithms.

### Supported Transformers

- ✅ `StandardRankingTransformer` (fully supported)
- ❌ Custom transformers (not supported - will error)
- ❌ Legacy transformers (not supported - will error)

## Technical Implementation

### Architecture

The feature logging capability is implemented at the `StandardRankingTransformer` level using a post-construction configuration pattern:

1. **Factory creates transformer** (standard instantiation)
2. **`setLogFeatures(boolean)` called** after construction
3. **Features captured during `transform()`** method execution
4. **Recursive application** to all dependency transformers

This design maintains API compatibility while adding the debugging capability.

### Code References

- **CLI layer**: `python/bin/hv` - `predict` subcommand / `--log-features`
- **Transformer**: `hotvect-core/.../StandardRankingTransformer.java:setLogFeatures()`
- **Algorithm loader**: `hotvect-online-util/.../AlgorithmInstanceFactory.java:doLoadParameterizedDependency()`
- **Output formatting**: `hotvect-offline-util/.../PredictTask.java`

## Example Debugging Workflow

### Scenario: v64 vs v77 Regression Investigation

```bash
# 1. Run prediction with feature logging for both versions
hv predict --log-features \
  --algorithm-jar ~/.m2/repository/.../example-parent-algorithm-1.0.0.jar \
  --algorithm-name example-parent-algorithm \
  --parameter-path ./v1-params.zip \
  --source-path ./test-data.json.gz \
  --dest-path v1-prediction-with-features.jsonl

hv predict --log-features \
  --algorithm-jar ~/.m2/repository/.../example-parent-algorithm-1.2.3.jar \
  --algorithm-name example-parent-algorithm \
  --parameter-path ./v2-params.zip \
  --source-path ./test-data.json.gz \
  --dest-path v2-prediction-with-features.jsonl

# 2. Compare outputs
# Now you have both predictions AND features for debugging:
# - Verify outer decoder processes data correctly
# - Compare feature values between versions
# - Correlate feature differences with prediction differences
# - Identify which features changed and why
```

## FAQ

**Q: Why not just use `hv audit` on the inner algorithm directly?**

A: When you audit the inner algorithm directly, you're using the inner algorithm's decoder, which bypasses the outer algorithm entirely. This means you can't catch bugs in the outer algorithm's data processing. With `--log-features`, data flows through the outer algorithm's decoder first, then features are extracted from the inner algorithm - testing the full composite pipeline.

**Q: Can I use this with algorithms that don't have dependencies?**

A: Yes, but it's less useful. For simple algorithms without dependencies, `hv audit` is more appropriate since it doesn't require a trained model.

**Q: What if my algorithm uses a custom transformer?**

A: The feature will fail with a clear error message. Feature logging currently only supports `StandardRankingTransformer`. If you need feature logging for custom transformers, you'll need to implement the `setLogFeatures()` method in your custom transformer.

**Q: Does this impact prediction performance?**

A: Yes, feature logging adds overhead for feature serialization. This feature is intended for debugging and should not be used in production pipelines.

**Q: Can I compare the feature output with `hv audit` output?**

A: Yes! The feature format in the `feature_audit` node matches `hv audit` output exactly, making it easy to compare features extracted through different code paths.

## See Also

- [CLI Reference: hv predict](../../reference/cli/index.md#4-predict)
- [CLI Reference: hv audit](../../reference/cli/index.md#1-audit)
- [Debug feature engineering guide](../debug-feature-engineering/index.md)
