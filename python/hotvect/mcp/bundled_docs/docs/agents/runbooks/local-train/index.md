---
title: Runbook - Local train
description: Copy/paste runbook for `hv train` with overrides and caching
tags: [agents, runbook, train, local, caching]
---

# Runbook: Local train (`hv train`)

Train one algorithm locally and leave a reusable parameters artifact plus a run summary.

## Agent contract

| Inputs | Command | Artifacts | Verify |
| --- | --- | --- | --- |
| Algorithm JAR/name, local data, output directory, test date | `hv train` | predict-parameters ZIP, `result.json`, `hv.log` | Exit code `0`; `result.json` records the stages that ran or were skipped |

Use this to train one local algorithm. Use [`hv backtest`](../local-backtest/index.md) when you need to compare git
references or multiple historical dates.

## Inputs (fill these in)

- `ALGORITHM_NAME`: algorithm name inside the JAR
- `ALGO_JAR`: path to algorithm jar
- `LAST_TEST_TIME`: `YYYY-MM-DD`
- `DATA_BASE_DIR`: training/test data base dir
- `OUTPUT_BASE_DIR`: dedicated output directory for this run
- `OVERRIDE_JSON`: optional override file path

## Command template

```bash
hv train \
  --algorithm-name "$ALGORITHM_NAME" \
  --algorithm-jar "$ALGO_JAR" \
  --data-base-dir "$DATA_BASE_DIR" \
  --output-base-dir "$OUTPUT_BASE_DIR" \
  --last-test-time "$LAST_TEST_TIME"
```

## Optional: add a deterministic override

```bash
hv train ... --algorithm-override "$OVERRIDE_JSON"
```

An override is a recursive patch over the JAR definition. Keep dependency changes under
`dependencies.<dependency_algorithm_name>`; unknown children fail fast.

## Optional: enable caching

Local cache path:

```bash
hv train ... --cache /tmp/hotvect-cache --cache-scope hyperparam
```

Force recompute (ignore reads, still write fresh run-level cache artifacts) with an algorithm definition/override that
sets `hotvect_execution_parameters.cache="run"`:

```bash
hv train ... --cache /tmp/hotvect-cache --cache-refresh
```

Notes:

- `--cache-refresh` requires an effective `cache_base_dir` and effective cache mode `run`.
- Caching is keyed by `parameter_version` (default `last_test_date_YYYY-MM-DD`).

## Validate cache hits

On a cache-hit run, look for:

- `Predict parameters available ... skipping encode and train`
- `Using cached predict parameters ...`

## Validate success

Look for:

- Exit code `0`
- `$OUTPUT_BASE_DIR/metadata/<algorithm_id>/<parameter_version>/result.json`
- `$OUTPUT_BASE_DIR/metadata/<algorithm_id>/<parameter_version>/hv.log`
- A packaged predict-parameters ZIP under the run output

Read `result.json` before reporting success: it is the authoritative record of which stages ran, reused cache, or
were skipped.
