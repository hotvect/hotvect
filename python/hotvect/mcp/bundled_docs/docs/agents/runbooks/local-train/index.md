---
title: Runbook - Local train
description: Copy/paste runbook for `hv train` with overrides and caching
tags: [agents, runbook, train, local, caching]
---

# Runbook: Local train (`hv train`)

Goal: run `hv train` locally, optionally reusing cached pipeline artifacts.

## Inputs (fill these in)

- `ALGORITHM_NAME`: algorithm name inside the JAR
- `ALGO_JAR`: path to algorithm jar
- `LAST_TEST_TIME`: `YYYY-MM-DD`
- `DATA_BASE_DIR`: training/test data base dir
- `OUTPUT_BASE_DIR`: output directory (safe to delete)
- `OVERRIDE_JSON`: optional override file path

## Command template

```bash
hv train \
  --algorithm-name "$ALGORITHM_NAME" \
  --algorithm-jar "$ALGO_JAR" \
  --data-base-dir "$DATA_BASE_DIR" \
  --output-base-dir "$OUTPUT_BASE_DIR" \
  --last-test-time "$LAST_TEST_TIME" \
  --algorithm-override "$OVERRIDE_JSON" \
  --extra-jvm-args "-XX:MaxRAMPercentage=80"
```

## Optional: enable caching

Local cache path:

```bash
hv train ... --cache /tmp/hotvect-cache --cache-scope hyperparam
```

Force recompute (ignore reads, still write fresh cache artifacts):

```bash
hv train ... --cache /tmp/hotvect-cache --cache-refresh
```

Notes:

- `--cache-refresh` requires `--cache`.
- Caching is keyed by `parameter_version` (default `last_test_date_YYYY-MM-DD`).

## Validate cache hits

On a cache-hit run, look for:

- `Predict parameters available ... skipping encode and train`
- `Using cached predict parameters ...`
