---
description: Calculate performance metrics from model predictions (AUC, precision, recall, NDCG, etc.)
---


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


You are executing the evaluate command to calculate performance metrics from predictions.

## Purpose

Calculate ML evaluation metrics to assess model quality and compare algorithm performance.

## Required Arguments

Parse from user or ask:
- `--source-path`: Predictions file (from `/predict`)
- `--dest-path`: Output metrics file path

## Optional Arguments

- `--enable-online-offline-analysis`: Enable detailed online vs offline performance analysis

## Example Execution

```bash
hv evaluate \
  --source-path /path/to/predictions.jsonl \
  --dest-path /path/to/evaluation.json

# With online/offline analysis
hv evaluate \
  --source-path /path/to/predictions.jsonl \
  --dest-path /path/to/evaluation.json \
  --enable-online-offline-analysis
```

## What It Does

1. Reads predictions with ground truth labels
2. Calculates configured metrics
3. Optionally performs online vs offline analysis
4. Writes metrics to JSON file

## Common Metrics

**Classification:**
- AUC (Area Under ROC Curve)
- Precision, Recall, F1
- Accuracy
- Log loss

**Ranking:**
- NDCG (Normalized Discounted Cumulative Gain)
- MRR (Mean Reciprocal Rank)
- Precision@K
- MAP (Mean Average Precision)

**Regression:**
- MSE (Mean Squared Error)
- RMSE (Root Mean Squared Error)
- MAE (Mean Absolute Error)

Specific metrics depend on algorithm definition.

## Output Format

JSON file with calculated metrics:
```json
{
  "auc": 0.742,
  "precision": 0.623,
  "recall": 0.551,
  "ndcg@10": 0.698
}
```

## Use in Workflow

Evaluation is typically part of training pipeline (`hv train`), but can be run standalone for:
- Re-evaluating with different metrics
- Analyzing specific prediction subsets
- Custom metric calculations

## Next Steps

After evaluation:
- Compare metrics with baseline using `/compare-evaluations`
- Analyze low-performing segments
- Decide if model is ready for deployment

## Tips

- Predictions must include ground truth labels
- Metrics depend on algorithm configuration
- Multiple metrics provide fuller picture of performance
- Compare against baseline to assess improvement
