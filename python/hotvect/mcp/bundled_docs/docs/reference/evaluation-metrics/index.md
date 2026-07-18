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
| `result.json` files from comparable runs | `hv-ext metrics export` or `hv-ext metrics plot` | JSON table or PDF/table report | Quality estimates use `value`; system metrics are present only when benchmark specifications agree |

Use `hv-ext metrics compare-quality` for a fast central-value comparison. Use a metrics plot when you need report
provenance, confidence intervals, and the evaluation/benchmark contract. Do not make a system-performance claim from a
plot that warns about mixed benchmark specifications.

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

## Export a machine-readable table

`export` preserves the structured estimates when they are available:

```bash
hv-ext metrics export \
  --result-files baseline/result.json treatment/result.json \
  --metrics roc_auc ndcg_at_10 \
  --out metrics.json
```

Use the resulting JSON for automation. Do not flatten `value`, `ci95_lower`, and `ci95_upper` into one number unless a
downstream consumer explicitly requires that projection.

## Create a comparison report

`plot` requires a relative baseline. It accepts explicit result files, a result glob, or a backtest output directory.

```bash
hv-ext metrics plot \
  --result-files baseline/result.json treatment/result.json \
  --relative-baseline <baseline-version> \
  --metrics roc_auc ndcg_at_10 p95 \
  --out comparison.pdf \
  --table-out comparison.json
```

The report includes quality plots with uncertainty where available, evaluation specification, benchmark
specification, provenance, and pipeline timing/cache sections.

### Mixed benchmark specifications

When plotted records have different performance-test specifications, `hv-ext metrics plot` exits successfully but
writes a warning to stderr. It omits system latency/throughput metrics such as `p50`, `p95`, `p99`, and
`mean_throughput` from the PDF and table. Quality metrics, provenance, specification, and pipeline timings remain.

Treat that output as a quality/pipeline report only. To compare system performance, rerun with matching instance,
runtime/image, parameter artifact, source/workload, measured samples, replay-pool size, target RPS, threads, and
other benchmark settings.

## Next steps

- [Reliable performance benchmarking](../../guides/performance-benchmarking/index.md) defines a defensible system-performance comparison.
- [Online/offline parity gaps](../../guides/online-offline-parity/index.md) explains how to validate a missing or divergent online dimension.
- [CLI reference](../cli/index.md) lists the full `hv-ext metrics` surface.
