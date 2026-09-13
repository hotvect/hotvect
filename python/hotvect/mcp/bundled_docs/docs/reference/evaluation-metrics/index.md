---
title: Evaluation Metrics and Uncertainty
description: Interpret Hotvect quality estimates, confidence intervals, exported metrics, and plot comparability safely
tags: [evaluation, metrics, uncertainty, backtest]
---

# Evaluation metrics and uncertainty

Use this page to read an evaluation result, compare algorithm revisions, or produce a metrics report without treating
incompatible runs as comparable.

## Comparison contract

| Inputs | Command | Artifacts | Verify |
| --- | --- | --- | --- |
| `result.json` files from comparable runs | `hv metrics export` or `hv metrics plot` | JSON table or PDF/table report | Quality estimates use `value`; system metrics are present only when benchmark specifications agree |

Use `hv metrics compare-quality` for a fast central-value comparison. Use a metrics plot when you need report
provenance, confidence intervals, and the evaluation/benchmark contract. Do not make a system-performance claim from a
plot that warns about mixed benchmark specifications.

## Prediction input contract

The evaluator reads ranking prediction JSONL. Each nonempty row needs a `result` array whose entries provide numeric
`rank` and `score`; standard evaluation also needs `reward` unless the explicit zero-fill policy is selected. Directory
inputs may contain multiple supported text part files and gzip files.

Ranking prediction rows stay in request-action order, so the evaluator uses each entry's `rank` rather than its array
position. See [Ranking and prediction contracts](../ranking-and-prediction-contracts/index.md) for the full schema and
metadata precedence.

Rows with an empty `result` are counted in evaluation metadata but skipped for metric calculation. If every result is
empty, the output contains a `skipped` explanation instead of fabricated quality values.

## Standard metric set

`standard_evaluation` computes:

| Metric | Population | Default labels | Meaning |
| --- | --- | --- | --- |
| NDCG | Per example, then mean | `ndcg_at_10`, `ndcg_at_50`, `ndcg_at_100` | Reward-weighted ranking quality |
| MAP | Per example, then mean | `map_at_10`, `map_at_50`, `map_at_100` | Precision over actions whose reward is greater than zero |
| ROC AUC | All actions | `roc_auc` | Score discrimination after binarizing reward at `> 0` |
| PR AUC | All actions | `pr_auc` | Average precision after the same reward binarization |
| Mean score | All actions | `mean_score` | Mean model score; useful for drift, not directly a quality measure |
| Category diversity | Per example, then mean | `diversity@5`, `diversity@10`, `diversity@30` | Fraction of unique categories in the top `k` |

Diversity is emitted only when prediction actions carry `additional_properties.action_category`. Supply that field for
every action in every evaluated example; a partially categorized row is not a well-defined diversity sample.

The defaults can be changed through evaluation-function arguments:

```json
{
  "hotvect_execution_parameters": {
    "evaluation_function": {
      "name": "standard_evaluation",
      "arguments": {
        "ks": [5, 20],
        "diversity_ks": [5, 20]
      }
    }
  }
}
```

ROC AUC and PR AUC require a meaningful binary-label population. A dataset with only one reward class cannot support
the same discrimination claim as a dataset containing both positive and nonpositive actions.

## Quality metric shape

New v10 evaluation results encode every quality metric as a structured estimate. The values below are synthetic:

```json
{
  "evaluate": {
    "roc_auc": {
      "value": 0.8124,
      "ci95_lower": 0.8051,
      "ci95_upper": 0.8196
    },
    "ndcg_at_10": {
      "value": 0.1967,
      "ci95_lower": 0.1894,
      "ci95_upper": 0.2040
    }
  }
}
```

Read the central estimate from `.value`, not `.mean`. The same shape applies to offline metrics and complete online
dimensions, for example `evaluate.online.<dimension>.roc_auc.value`.

Confidence interval fields are optional when an estimate cannot provide an interval. Current results use the
structured shape above.

### What the 95% interval means

- Metrics computed as sample means use a central 95% confidence interval on the mean.
- ROC AUC uses DeLong's variance estimate.
- PR AUC uses quantised bootstrap sampling.

The interval describes uncertainty in this evaluation sample. It does not replace a controlled, repeated experiment
when comparing operational performance or making a rollout decision.

## Multi-day release QA

[Release QA validation](../../guides/hv-qa-release-validation/index.md) makes an offline release decision from the
central `evaluate.<metric>.value` estimates paired by test date. It does **not** pool each result's marginal
`ci95_lower` / `ci95_upper` values: separate intervals do not include the control-treatment covariance needed for a
paired comparison. The QA output records this basis as `quality_statistical_basis`.

For a superiority claim, `hv-qa` applies a Bonferroni correction across every evaluated quality metric and treatment.
Read the recorded adjusted alpha and family size with the decision rather than treating a plotted confidence interval
as the release test.

## Missing rewards and online dimensions

`standard_evaluation` fails by default when a prediction action has no `reward`. If an algorithm deliberately uses
zero-fill, make that policy explicit in the algorithm definition or override:

```json
{
  "hotvect_execution_parameters": {
    "evaluation_function": {
      "name": "standard_evaluation",
      "arguments": {
        "missing_reward_policy": "zero"
      }
    }
  }
}
```

Zero-fill changes the meaning of the quality result. Inspect `evaluate.evaluation_policy` and
`evaluate.missing_reward` before comparing it with a run that used the default `error` policy.

An online metric dimension is emitted only when it exists for every scored prediction row. A partially populated
dimension is omitted rather than calculated on a changing population. Confirm its presence in `result.json` before
reporting an online/offline gap.

Online dimensions come from each action's
`additional_properties.online.<dimension>.rank` and `.score`. A dimension must be present for every action of every
nonempty row. The evaluator reuses the offline rewards and computes NDCG, MAP, ROC AUC, PR AUC, and mean score for each
complete dimension; it does not compute a separate online diversity value.

## Real-number reward evaluation

Select `real_numbers_reward_evaluation` when negative rewards are intentional penalties rather than merely the
nonpositive class of a binary relevance metric:

```json
{
  "hotvect_execution_parameters": {
    "evaluation_function": {
      "name": "real_numbers_reward_evaluation",
      "arguments": {
        "ks": [10, 50, 100]
      }
    }
  }
}
```

This evaluator computes only an adapted `ndcg_at_<k>`. It compares the observed ordering with both the ideal and worst
ordering, preserves negative penalties, and normalizes the result between zero and one. Missing rewards are errors;
the zero-fill policy is not available for this evaluator.

## Export a machine-readable table

`export` preserves the structured estimates when they are available:

```bash
hv metrics export \
  --result-files baseline/result.json treatment/result.json \
  --metrics roc_auc ndcg_at_10 \
  --out metrics.json
```

Use the resulting JSON for automation. Do not flatten `value`, `ci95_lower`, and `ci95_upper` into one number unless a
downstream consumer explicitly requires that projection.

## Create a comparison report

`plot` requires a relative baseline. It accepts explicit result files, a result glob, or a backtest output directory.

```bash
hv metrics plot \
  --result-files baseline/result.json treatment/result.json \
  --relative-baseline <baseline-version> \
  --metrics roc_auc ndcg_at_10 p95 \
  --out comparison.pdf \
  --table-out comparison.json
```

The report includes quality plots with uncertainty where available, evaluation specification, benchmark
specification, provenance, and pipeline timing/cache sections.

### Mixed benchmark specifications

When plotted records have different performance-test specifications, `hv metrics plot` exits successfully but
writes a warning to stderr. It omits system latency/throughput metrics such as `p50`, `p95`, `p99`, and
`mean_throughput` from the PDF and table. Quality metrics, provenance, specification, and pipeline timings remain.

Treat that output as a quality/pipeline report only. To compare system performance, rerun with matching instance,
runtime/image, parameter artifact, source/workload, measured samples, replay-pool size, target RPS, threads, and
other benchmark settings.

## Next steps

- [Release QA validation](../../guides/hv-qa-release-validation/index.md) explains the paired multi-day release gate.
- [Reliable performance benchmarking](../../guides/performance-benchmarking/index.md) defines a defensible system-performance comparison.
- [Online/offline parity gaps](../../guides/online-offline-parity/index.md) explains how to validate a missing or divergent online dimension.
- [CLI reference](../cli/index.md) lists the full `hv metrics` surface.
