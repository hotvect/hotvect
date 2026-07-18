---
title: Frequently asked questions
description: Short answers to common Hotvect setup, data, artifact, and workflow questions
tags: [faq, troubleshooting, getting-started]
related_docs:
  - ../cli/index.md
  - ../troubleshooting/index.md
  - ../../concepts/index.md
---

# Frequently asked questions

## Setup and versions

### Do the algorithm and Hotvect versions have to match?

Use the same Hotvect major when possible. A newer runner can sometimes execute an older algorithm JAR, but this
depends on class ownership and the exact API and artifact path in use. There is no blanket cross-major guarantee.

See [Version compatibility](../version-compatibility/index.md) and validate the smallest command that exercises your
required path.

### What is the difference between `hv`, `hv-ext`, and `hv-exp`?

- `hv` runs core algorithm and pipeline operations such as audit, encode, predict, train, and backtest.
- `hv-ext` provides result, metrics, comparison, configuration, and data-dependency utilities.
- `hv-exp` reads experiment-management state and online evaluation result partitions.

All three are installed with the Hotvect Python package. Use `--help` on the installed checkout for the exact command
surface.

### Does `hv backtest` need `~/.hotvect/config.json` when I pass every directory flag?

No. The current command reads the config only when at least one of `--data-base-dir`, `--output-base-dir`, or
`--scratch-dir` is missing. If a config is needed, `hv-ext config init` creates it and refuses to replace an existing
file unless `--force` is present.

See [Configuration reference](../config/index.md).

## Data

### How do I find the data a run needs?

Use `hv-ext data-dependency` or `hv-ext show-data-dependency` against the same git reference, override, target, and test
date as the intended run. The default `data-dependency` mode lists rather than downloads; add `--download-all` or a
specific `--download <data-prefix>` only after reviewing the plan.

The command reserves stdout for its JSON plan and sends clone/build progress to stderr, so the result can be redirected
or piped to `jq`.

### Which training dates are required?

For `number_of_training_days = N`:

```text
end   = last_test_time - training_lag_days
start = end - (N - 1 days)
```

The inclusive range from `start` through `end` contains `N` partitions. Use the resolved dependency plan rather than
reimplementing this calculation in automation.

### Why does a sampled download not produce a trustworthy quality result?

`--sample-ratio` samples files within each required date partition, not rows from a defined statistical population.
Use it to validate build, dependency, and pipeline wiring. Use the intended full data contract for model-quality
claims.

## Artifacts and debugging

### Which path is a file and which is a directory?

- `hv audit`, `hv predict`, and `hv encode` write a destination directory containing `part-*` files.
- `--metadata-path` is a directory containing `metadata.json` and logs.
- a predict-parameters artifact is a ZIP file.
- `hv evaluate --dest-path` writes one JSON file.

### How do I inspect wrong feature values?

Run a small ordered audit with the exact JAR, source rows, and parameters ZIP used by the failing path:

```bash
hv audit \
  --algorithm-jar /path/to/algorithm.jar \
  --algorithm-name <algorithm-name> \
  --parameter-path /path/to/predict.parameters.zip \
  --source-path /path/to/source.jsonl.gz \
  --dest-path ./audit \
  --ordered \
  --samples 100
```

Compare two ordered outputs with `hv-ext compare-jsonl`. See [Feature audits](../../guides/feature-audits/index.md).

### When should I use `hv predict --log-features` instead of `hv audit`?

Use `hv audit` to inspect the transformer owned by one algorithm. The current audit task rejects vectorizer-only
definitions. Use `hv predict --log-features` when
you need the full outer-algorithm decode and scoring path with dependency feature values attached to each result item.

See [Predict with feature logging](../../guides/feature-logging/index.md).

### What should I do after an `OutOfMemoryError`?

First identify the failing stage and its actual process/container limit. Then reduce the intended workload or set one
explicit heap cap. `train` and `backtest` accept `--extra-jvm-args`; standalone JVM commands such as `audit`, `encode`,
and `predict` accept JVM arguments after `--`. Use either `-Xmx...` or `-XX:MaxRAMPercentage=...`, never both; Hotvect
rejects duplicate heap policies.

Do not treat a fixed memory number as universal: requirements depend on data, features, model, and stage.

## Overrides and reuse

### How do override files merge?

Objects merge recursively, scalars and arrays replace, and `null` deletes a field. `dependencies` is a map of patches
for already declared children; unknown child names fail. Overrides cannot change `algorithm_name`, and backtests reject
`algorithm_version` changes.

See [Override files](../../guides/patterns/override-files/index.md).

### What is the difference between `with_parameter` and caching?

`hotvect_execution_parameters.with_parameter` strictly pins one parameters ZIP. It can be set on the top-level
algorithm or inside a dependency override. The artifact must exist; Hotvect does not retrain when the pin is missing.

Caching is best-effort reuse of state, encode, or train artifacts. A cache miss recomputes the stage and may populate
the cache. Predict, evaluate, and performance-test are not cached.

See [Reuse existing outputs](../../guides/reuse-outputs/index.md) and [Caching](../../guides/caching/index.md).

### Why did a cache not hit?

Check the effective definition and logs. Run-level cache keys include the algorithm cache key and `parameter_version`,
which defaults to `last_test_date_YYYY-MM-DD`. A changed date, version scope, hyperparameter version, or cache mode can
select a different entry. Encode partition caching has a separate per-date layout.

For SageMaker reuse across jobs, use an `s3://...` cache. A local container path does not persist.

## Composite algorithms

### Which algorithm should a command target?

Choose the algorithm that owns the contract needed by the command:

- audit: transformer (the current audit task rejects vectorizers);
- encode: encoder;
- generate-state: state generator;
- end-to-end train/backtest: usually the public outer algorithm;
- focused stage debugging: the child that owns that stage, with a child-specific override.

An outer algorithm is not necessarily evaluation-only, and a child is not necessarily a trainable model. Inspect the
embedded definition rather than inferring behavior from nesting alone. See [Hotvect concepts](../../concepts/index.md)
and [Parent/child algorithms](../../guides/patterns/parent-child/index.md).

## More help

- [Troubleshooting](../troubleshooting/index.md) maps concrete failures to checks.
- [CLI reference](../cli/index.md) lists flags and output contracts.
