---
title: Predict Score Equivalence Testing
description: Prove two algorithm builds preserve predict scores and ranking with one fixed parameter ZIP
tags: [predict, regression, equivalence, testing, comparison]
difficulty: intermediate
estimated_time: 15 minutes
prerequisites:
  - hotvect CLI installed and working
  - Algorithm JARs built for both control and treatment
  - One predict-parameters ZIP that both builds can read
  - A stable offline source dataset
related_docs:
  - ../feature-audits/index.md
  - ../performance-investigations/index.md
  - ../sagemaker-backtests/index.md
  - ../patterns/override-files/index.md
related_commands:
  - hv algorithm predict
  - hv algorithm audit
  - hv qa utils compare-predictions
  - hv-ext compare-jsonl
next_steps:
  - Run a multi-day quality backtest when retraining behaviour also matters
  - Add the fixed-parameter check to an algorithm release checklist
---

# Predict score equivalence testing

Prove that a control and treatment JAR produce the same prediction rows with the **same input** and **same
predict-parameters ZIP**. This is an inference-regression test: it isolates runtime, feature, and scorer changes from
training variance.

## Equivalence contract

| Inputs | Command | Artifacts | Verify |
| --- | --- | --- | --- |
| Control/treatment JARs, one predict-parameters ZIP, one source slice | `hv algorithm predict --ordered` twice, then `hv qa utils compare-predictions` | Two output directories, metadata, `comparison.json` | `status` is `passed`; score and rank mismatch counts are zero |

Use this when a refactor, Hotvect upgrade, or wiring change is intended to preserve inference. Do **not** use it to
claim backtest or model-quality parity: a normal backtest retrains parameters, so use a multi-day backtest for that
question.

## Smallest complete check

Keep the source rows and parameter ZIP identical. Ordered output gives both runs one row-for-row part file to compare.

```bash
RUN_DIR=./predict-equivalence-$(date +%Y%m%d-%H%M%S)
mkdir -p "$RUN_DIR"

hv algorithm predict \
  --algorithm-jar /path/to/control.jar \
  --algorithm-name <algorithm-name> \
  --parameter-path /path/to/predict.parameters.zip \
  --source-path /path/to/offline-data/dt=YYYY-MM-DD \
  --dest-path "$RUN_DIR/control.predict" \
  --metadata-path "$RUN_DIR/control.metadata" \
  --ordered \
  --samples 3000

hv algorithm predict \
  --algorithm-jar /path/to/treatment.jar \
  --algorithm-name <algorithm-name> \
  --parameter-path /path/to/predict.parameters.zip \
  --source-path /path/to/offline-data/dt=YYYY-MM-DD \
  --dest-path "$RUN_DIR/treatment.predict" \
  --metadata-path "$RUN_DIR/treatment.metadata" \
  --ordered \
  --samples 3000

hv qa utils compare-predictions \
  "$RUN_DIR/control.predict/part-00000.jsonl" \
  "$RUN_DIR/treatment.predict/part-00000.jsonl" \
  --score-eps 0 \
  --output "$RUN_DIR/compare"
```

`hv algorithm predict` writes a **destination directory**. With `--ordered`, the predictable comparison input is
`part-00000.jsonl`; do not point the comparator at the directory itself.

## Read the result

`hv qa utils compare-predictions` prints JSON and writes `comparison.json` in the requested output directory. It exits:

- `0` when `status` is `passed`;
- `1` when scores, action IDs, rank order, example IDs, or line counts differ;
- `2` when an input file or predict-record contract is invalid.

For a successful strict check, expect this shape:

```json
{
  "status": "passed",
  "score": {
    "passed": true,
    "eps": 0,
    "mismatch_count": 0
  },
  "rank": {
    "passed": true,
    "mismatch_count": 0
  }
}
```

The comparator requires every paired row to have a string `example_id`, a `result` array, one unique `action_id` per
result item, and a numeric `score`. It reports actionable mismatches rather than silently matching records by a
different key.

## Choose the right strictness

`--score-eps 0` is the exact numeric-score check. Use a positive tolerance only when a small floating-point delta is
expected and approved:

```bash
hv qa utils compare-predictions \
  "$RUN_DIR/control.predict/part-00000.jsonl" \
  "$RUN_DIR/treatment.predict/part-00000.jsonl" \
  --score-eps 1e-6 \
  --output "$RUN_DIR/compare-tolerant"
```

Rank order remains strict by default. If the only permissible difference is the ordering of genuinely tied scores, add
`--allow-non-deterministic-tie-breaking`; it permits permutations only within groups tied according to `--score-eps`.
Do not use that flag to hide a real ranking change.

## Localize a failure with a feature audit

When prediction equivalence fails, first check whether feature transformation already diverged:

```bash
hv algorithm audit \
  --algorithm-jar /path/to/control.jar \
  --algorithm-name <algorithm-name> \
  --parameter-path /path/to/predict.parameters.zip \
  --source-path /path/to/offline-data/dt=YYYY-MM-DD \
  --dest-path "$RUN_DIR/control.audit" \
  --metadata-path "$RUN_DIR/control.audit.metadata" \
  --ordered \
  --samples 200

hv algorithm audit \
  --algorithm-jar /path/to/treatment.jar \
  --algorithm-name <algorithm-name> \
  --parameter-path /path/to/predict.parameters.zip \
  --source-path /path/to/offline-data/dt=YYYY-MM-DD \
  --dest-path "$RUN_DIR/treatment.audit" \
  --metadata-path "$RUN_DIR/treatment.audit.metadata" \
  --ordered \
  --samples 200

hv-ext compare-jsonl \
  "$RUN_DIR/control.audit/part-00000.jsonl" \
  "$RUN_DIR/treatment.audit/part-00000.jsonl" \
  --output "$RUN_DIR/audit-diff"
```

If audit output differs, investigate feature engineering first. If audits match but prediction differs, inspect
parameter loading, scorer construction, and runtime wiring. Keep the failing output directories; their metadata and
logs are the evidence needed for the next comparison.

## What this check does not prove

`hv algorithm predict` equivalence does not prove that two versions train the same model. A backtest typically runs
encode/train/package again for each date. To isolate inference in a backtest-style run, reuse the same parameters ZIP
through `hotvect_execution_parameters.with_parameter` in an override, then compare offline `evaluate.*` results across
the desired dates.

For raw quality comparisons, use `hv metrics compare-quality`; for system performance, use matching benchmark
specifications and a metrics plot. See [Evaluation metrics and uncertainty](../../reference/evaluation-metrics/index.md).
