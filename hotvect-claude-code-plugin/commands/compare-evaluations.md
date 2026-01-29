---
description: Compare ML evaluation metrics between two algorithm versions
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


You are executing the compare-evaluations command to compare model quality metrics.

## Purpose

Compare evaluation metrics (AUC, NDCG, precision, etc.) between algorithm versions to assess if changes improved model quality.

## Required Arguments

Parse from user or ask:
- Baseline evaluation JSON file
- Treatment evaluation JSON file

## Optional Arguments

- `-o, --output`: Save comparison to JSON file

## Example Execution

```bash
hv-ext compare-evaluations \
  baseline_results/evaluation.json \
  treatment_results/evaluation.json \
  -o comparison.json
```

## Input Format

Evaluation JSON files from `/evaluate` or `/train`:
```json
{
  "auc": 0.742,
  "precision": 0.623,
  "recall": 0.551,
  "ndcg@10": 0.698,
  "f1_score": 0.584
}
```

## Output

Comparison showing:
- Metric values for both versions
- Absolute and relative differences
- Direction of change (improvement/regression)

**Example output:**
```
Evaluation Metrics Comparison
============================

AUC:
  Baseline:   0.742
  Treatment:  0.758
  Change:     +0.016 (+2.2%) ✓ IMPROVEMENT

NDCG@10:
  Baseline:   0.698
  Treatment:  0.712
  Change:     +0.014 (+2.0%) ✓ IMPROVEMENT

Precision:
  Baseline:   0.623
  Treatment:  0.609
  Change:     -0.014 (-2.2%) ✗ REGRESSION

Recall:
  Baseline:   0.551
  Treatment:  0.573
  Change:     +0.022 (+4.0%) ✓ IMPROVEMENT
```

## Interpretation

**Significant improvements:**
- AUC increase >0.01 (1 percentage point)
- NDCG increase >0.02
- Precision/Recall trade-offs that favor business goal

**Minor changes:**
- Differences <1% may be noise
- Run multiple backtests for confidence

**Regressions:**
- Any decrease in key metrics needs investigation
- Assess if trade-off is intentional

## Business Impact

Consider which metrics matter most:
- **AUC**: Overall model discriminative power
- **NDCG**: Ranking quality (critical for recommendations)
- **Precision**: Accuracy of positive predictions
- **Recall**: Coverage of positive cases

## Use Cases

- Validate feature improvements worked
- Assess A/B test results
- Compare different modeling approaches
- Make deployment decisions

## Statistical Significance

For robust conclusions:
- Run backtest on multiple date ranges
- Calculate confidence intervals
- Consider sample size effects

## Next Steps

After comparison:
- If improved: proceed to deployment
- If regressed: investigate root cause
- If mixed: analyze trade-offs with stakeholders

## Tips

- Focus on metrics that matter for business goals
- Small improvements compound in production
- Consider online vs offline metric correlation
- Document learnings for future iterations
