# Backtest Output Structure

**🌶️ CRITICAL: Read this to understand hotvect backtest outputs**

This document explains the structure of hotvect backtest outputs and how to navigate them programmatically.

## Overview

Hotvect backtests produce outputs in two parallel directory structures:

1. **`out/`** - Binary artifacts and data files (parameters, predictions, encoded data)
2. **`meta/`** - JSON metadata describing what happened (timing, metrics, configuration)

Both directories mirror each other's structure but contain different types of files.

## Directory Structure

```
${output_base_dir}/
├── out/                                    # Binary artifacts and data
│   ├── parent-algorithm@version/
│   │   └── last_test_date_YYYY-MM-DD/
│   │       ├── algorithm-parameters.json
│   │       ├── parent-algorithm@version@last_test_date_YYYY-MM-DD.parameters.zip
│   │       ├── parent-algorithm@version@last_test_date_YYYY-MM-DD.encode.parameters.zip
│   │       ├── prediction.jsonl          # Predictions from parent algorithm
│   │       └── cache/
│   └── child-algorithm@parent-version/
│       └── last_test_date_YYYY-MM-DD/
│           ├── algorithm-parameters.json
│           ├── child-algorithm@parent-version@last_test_date_YYYY-MM-DD.parameters.zip
│           ├── encoded/                  # Encoded training data (CatBoost format)
│           └── cache/
└── meta/                                   # Metadata and metrics
    ├── parent-algorithm@version/
    │   └── last_test_date_YYYY-MM-DD/
    │       ├── result.json               # 🌶️ COMPREHENSIVE RESULTS (read this!)
    │       ├── algorithm_definition.json
    │       ├── predict_metadata.json
    │       ├── evaluate_metadata.json
    │       └── hotvect-offline-utils.log
    └── child-algorithm@parent-version/
        └── last_test_date_YYYY-MM-DD/
            ├── result.json               # 🌶️ COMPREHENSIVE RESULTS (child)
            ├── algorithm_definition.json
            ├── encode_metadata.json
            ├── train_metadata.json
            └── hotvect-offline-utils.log
```

## Key Concepts

### 1. Parent vs Child Algorithms

In nested algorithm architectures (e.g., `my-ranker` with dependency `my-ranker-ml-model`):

- **Parent algorithm**: Orchestrates evaluation, uses trained child model for predictions
  - Directory: `parent-algorithm@version/`
  - Has: `predict_metadata.json`, `evaluate_metadata.json`
  - Does NOT have: `encode_metadata.json`, `train_metadata.json`

- **Child algorithm**: Trains the actual ML model (e.g., CatBoost)
  - Directory: `child-algorithm@parent-version/`
  - Has: `encode_metadata.json`, `train_metadata.json`
  - Does NOT have: `predict_metadata.json`, `evaluate_metadata.json`

### 2. Version Naming Convention

- **Parent algorithm directory**: `algorithm-name@version`
  - Version comes from parent's pom.xml
  - Example: `my-ranker@74.4.0`

- **Child algorithm directory**: `child-name@parent-version`
  - Uses parent's version, not child's own version
  - Example: `my-model@my-ranker-74.4.0`

### 3. result.json vs Stage Metadata Files

**🌶️ ALWAYS READ `result.json` FIRST**

Each algorithm produces TWO types of metadata:

#### Stage Metadata Files (Individual Operations)

Located in `meta/algorithm@version/last_test_date_YYYY-MM-DD/`:

- **`encode_metadata.json`** (child only)
  - Task type: `EncodeTask`
  - Contains: Records processed, throughput, source files
  - Example fields: `lines_read`, `records_processed_at_rate`

- **`train_metadata.json`** (child only)
  - Task type: `TrainTask`
  - Contains: Training parameters, model artifacts, training time
  - Example fields: `training_time_sec`, `model_file`

- **`predict_metadata.json`** (parent only)
  - Task type: `PredictTask`
  - Contains: Prediction throughput, memory usage, source data
  - Example fields: `total_record_count`, `mean_throughput`

- **`evaluate_metadata.json`** (parent only)
  - Task type: `EvaluateTask`
  - Contains: Evaluation metrics (AUC, NDCG, MAP, etc.)
  - Example fields: `roc_auc`, `ndcg_at_10`, `map_at_50`

#### result.json (Comprehensive Aggregation)

Located in `meta/algorithm@version/last_test_date_YYYY-MM-DD/result.json`:

**This is the authoritative source for all backtest results.**

Structure:
```json
{
  "algorithm_id": "parent-algorithm@version",
  "parameter_version": "last_test_date_YYYY-MM-DD",
  "test_data_time": "YYYY-MM-DD",
  "ran_at": "2025-11-17T16:43:29.039537+00:00",
  "algorithm_definition": { /* Full algorithm definition with overrides */ },
  "timing_info_sec": {
    "prepare_dependencies": 36.76,
    "encode_parameter": 0.17,
    "package_predict_params": 0.17,
    "predict": 11.72,
    "evaluate": 1.44,
    "performance_test": 0.00003
  },
  "dependencies": {
    "child-algorithm": {
      /* Full child result.json nested here */
      "algorithm_id": "child-algorithm@parent-version",
      "timing_info_sec": {
        "encode": 17.12,
        "train": 19.46,
        "total_time": 36.76
      },
      "encode": { /* encode_metadata content */ },
      "train": { /* train_metadata content */ },
      "package_encode_params": { /* ... */ }
    }
  },
  "predict": { /* predict_metadata content */ },
  "evaluate": { /* evaluate_metadata content */ }
}
```

**Key points:**
- `result.json` contains ALL metadata from individual stage files
- Child algorithm results are nested under `dependencies[child-name]`
- `timing_info_sec` shows time spent in each stage
- Stage metadata is embedded: `result.json.predict` = `predict_metadata.json` content

## How to Find Evaluation Metrics

### ❌ WRONG: Looking for evaluation.json

```bash
# This file DOES NOT EXIST in backtest outputs
cat ${output_base_dir}/out/my-algorithm@1.0.0/last_test_date_2025-11-15/evaluation.json
```

### ✅ CORRECT: Read from result.json

```bash
# Parent algorithm evaluation metrics
cat ${output_base_dir}/meta/my-algorithm@1.0.0/last_test_date_2025-11-15/result.json | jq '.evaluate'

# Or read from evaluate_metadata.json (same content)
cat ${output_base_dir}/meta/my-algorithm@1.0.0/last_test_date_2025-11-15/evaluate_metadata.json
```

### Extract Specific Metrics

```bash
# AUC score
jq -r '.evaluate.roc_auc.mean' ${meta_dir}/result.json

# NDCG@10
jq -r '.evaluate.ndcg_at_10' ${meta_dir}/result.json

# Check if evaluation ran
jq -r '.timing_info_sec.evaluate' ${meta_dir}/result.json  # Non-zero = ran
```

## How to Compare Backtest Results

### Step 1: Locate result.json for each version

```bash
# Version 1 results
RESULT_V1="${output_base_dir}/meta/my-algorithm@1.0.0/last_test_date_2025-11-15/result.json"

# Version 2 results
RESULT_V2="${output_base_dir}/meta/my-algorithm@2.0.0/last_test_date_2025-11-15/result.json"
```

### Step 2: Extract metrics

```bash
# Extract AUC for both versions
AUC_V1=$(jq -r '.evaluate.roc_auc.mean' "$RESULT_V1")
AUC_V2=$(jq -r '.evaluate.roc_auc.mean' "$RESULT_V2")

echo "V1 AUC: $AUC_V1"
echo "V2 AUC: $AUC_V2"
```

### Step 3: Use hv-ext compare-evaluations

```bash
# Compare full evaluation results
hv-ext compare-evaluations "$RESULT_V1" "$RESULT_V2" -o comparison.json
```

## Common Patterns for Agents

### Pattern 1: Check if backtest produced results

```bash
# Check if result.json exists and has evaluation
RESULT_FILE="${output_base_dir}/meta/${algorithm_name}@${version}/last_test_date_${test_date}/result.json"

if [ ! -f "$RESULT_FILE" ]; then
  echo "ERROR: Backtest did not complete - result.json missing"
  exit 1
fi

# Check if evaluation ran
EVAL_TIME=$(jq -r '.timing_info_sec.evaluate // 0' "$RESULT_FILE")
if [ "$EVAL_TIME" = "0" ] || [ "$EVAL_TIME" = "0.0" ]; then
  echo "WARNING: Evaluation did not run (timing = $EVAL_TIME seconds)"
fi
```

### Pattern 2: Find all backtest results in output directory

```bash
# Find all result.json files
find "${output_base_dir}/meta" -name "result.json" -type f

# Extract algorithm versions and test dates
find "${output_base_dir}/meta" -name "result.json" -type f | while read result_file; do
  algo_id=$(jq -r '.algorithm_id' "$result_file")
  test_date=$(jq -r '.test_data_time' "$result_file")
  auc=$(jq -r '.evaluate.roc_auc.mean // "N/A"' "$result_file")
  echo "$algo_id (test date: $test_date) - AUC: $auc"
done
```

### Pattern 3: Verify evaluation ran automatically

```bash
# For a specific backtest result
RESULT_FILE="$output_base_dir/meta/my-algorithm@1.0.0/last_test_date_2025-11-15/result.json"

# Check timing_info_sec.evaluate
if jq -e '.timing_info_sec.evaluate > 0' "$RESULT_FILE" > /dev/null; then
  echo "✓ Evaluation ran automatically during backtest"
else
  echo "✗ Evaluation did NOT run - check algorithm configuration"
fi
```

### Pattern 4: Extract child algorithm training time

```bash
# Child algorithm is nested in parent's result.json
PARENT_RESULT="$output_base_dir/meta/parent-algo@1.0.0/last_test_date_2025-11-15/result.json"

# Extract child training time
CHILD_NAME="child-model"
TRAIN_TIME=$(jq -r ".dependencies[\"${CHILD_NAME}\"].timing_info_sec.train" "$PARENT_RESULT")

echo "Child algorithm training took: ${TRAIN_TIME} seconds"
```

## File Location Rules

### Where to find data files:

| File Type | Location | Description |
|-----------|----------|-------------|
| **Predictions** | `out/algorithm@version/last_test_date_*/prediction.jsonl` | JSONL with scores for each test record |
| **Parameters (trained model)** | `out/algorithm@version/last_test_date_*/algorithm@version@last_test_date_*.parameters.zip` | Trained model weights/parameters |
| **Encoded training data** | `out/child-algorithm@version/last_test_date_*/encoded/` | ML library format (CatBoost TSV) |
| **Comprehensive results** | `meta/algorithm@version/last_test_date_*/result.json` | 🌶️ ALL metadata aggregated |
| **Evaluation metrics** | `meta/algorithm@version/last_test_date_*/evaluate_metadata.json` | AUC, NDCG, MAP, etc. |
| **Logs** | `meta/algorithm@version/last_test_date_*/hotvect-offline-utils.log` | Execution logs |

### Where NOT to look:

- ❌ `out/algorithm@version/last_test_date_*/evaluation.json` - DOES NOT EXIST
- ❌ `out/algorithm@version/last_test_date_*/result.json` - Wrong directory (use `meta/`)

## Workflow for Agents

When user asks "compare v1 vs v2 backtest results":

1. **Read config** to get `output_base_dir`:
   ```bash
   OUTPUT_DIR=$(jq -r '.directories.output_base_dir' ~/.hotvect/config.json)
   ```

2. **Find result.json for each version**:
   ```bash
   # Note: Use ALGORITHM VERSION from pom.xml, not git reference name
   RESULT_V1="${OUTPUT_DIR}/meta/my-algorithm@1.0.0/last_test_date_2025-11-15/result.json"
   RESULT_V2="${OUTPUT_DIR}/meta/my-algorithm@2.0.0/last_test_date_2025-11-15/result.json"
   ```

3. **Check files exist**:
   ```bash
   [ -f "$RESULT_V1" ] || echo "ERROR: V1 results not found"
   [ -f "$RESULT_V2" ] || echo "ERROR: V2 results not found"
   ```

4. **Extract and compare metrics**:
   ```bash
   # Use jq to extract evaluation metrics
   jq '.evaluate' "$RESULT_V1" > v1_eval.json
   jq '.evaluate' "$RESULT_V2" > v2_eval.json

   # Or use hv-ext command
   hv-ext compare-evaluations "$RESULT_V1" "$RESULT_V2"
   ```

5. **Report findings** with actual metric values

## Summary: Quick Reference

**Finding evaluation results:**
```bash
# CORRECT - Read from result.json
cat ${output_base_dir}/meta/${algorithm}@${version}/last_test_date_${date}/result.json | jq '.evaluate'

# ALSO CORRECT - Read from evaluate_metadata.json (same content)
cat ${output_base_dir}/meta/${algorithm}@${version}/last_test_date_${date}/evaluate_metadata.json
```

**Checking if evaluation ran:**
```bash
jq '.timing_info_sec.evaluate' result.json  # Non-zero = evaluation ran
```

**Comparing two backtests:**
```bash
hv-ext compare-evaluations \
  ${output_base_dir}/meta/algo@v1/last_test_date_*/result.json \
  ${output_base_dir}/meta/algo@v2/last_test_date_*/result.json
```

**Never assume evaluation.json exists in out/ directory - it doesn't!**
