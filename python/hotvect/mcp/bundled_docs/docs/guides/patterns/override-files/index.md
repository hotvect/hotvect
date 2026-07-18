---
title: Algorithm override files
description: Patch an embedded Hotvect algorithm definition without changing its identity or JAR
tags: [patterns, configuration, training, overrides]
---

# Algorithm override files

An override is a JSON **fragment** patched onto the definition embedded in an algorithm JAR. Use one to change an
experiment's data window, hyperparameters, dependency configuration, or execution policy without rebuilding feature
code.

## Override contract

| Inputs | Command | Artifact | Verify |
| --- | --- | --- | --- |
| JAR, algorithm name, override JSON | `hv train` or `hv backtest --algorithm-override` | `effective_algorithm_definition.json` under metadata | The effective definition contains only the intended changes |

## Identity rule

An override must not be a full definition. Do **not** include `algorithm_name`; the CLI rejects it before merging.
Do not try to change `algorithm_version` either—`hv backtest` rejects identity changes. Select a different JAR/ref
when the algorithm identity must change.

## Merge rules

- Object fields merge recursively.
- Scalar and array values replace the base value.
- `null` deletes a field.
- `dependencies` is a child-override map, not a free-form replacement:
  - each key must name a child already declared by the parent;
  - unknown children fail fast;
  - unspecified children remain unchanged.

## Small, valid override

```json
{
  "hyperparameter_version": "2day-no-perf",
  "hotvect_execution_parameters": {
    "performance-test": {
      "enabled": false
    }
  },
  "dependencies": {
    "my-model-algorithm": {
      "number_of_training_days": 2,
      "hotvect_execution_parameters": {
        "performance-test": {
          "enabled": false
        }
      }
    }
  }
}
```

`hyperparameter_version` distinguishes the output namespace for the experiment; for example,
`my-algorithm@1.0.0-2day-no-perf`. It does not change the algorithm's JAR identity.

Apply the fragment:

```bash
hv train \
  --algorithm-name my-algorithm \
  --algorithm-jar algorithm.jar \
  --algorithm-override /path/to/override.json \
  --data-base-dir /path/to/data \
  --output-base-dir ./output \
  --last-test-time 2000-01-08
```

## Control the pipeline with supported mechanisms

Do not set `hotvect_execution_parameters.encode.enabled` or `train.enabled`: those keys do not control v10
pipeline execution. An algorithm trains when it declares `training_command`, and encoding runs as part of that
training path.

| Goal | Use |
| --- | --- |
| Package parameters and stop before inference stages | `--target parameters` |
| Publish a prediction using `prediction_spec` | `--target predict` |
| Run the normal test-data prediction/evaluation path | `--target evaluate` |
| Skip pipeline performance benchmarking | `hotvect_execution_parameters.performance-test.enabled=false` |
| Reuse one exact parameter ZIP | `hotvect_execution_parameters.with_parameter` (strict; artifact must exist) |
| Reuse stage artifacts when available | cache configuration or `--cache` (best effort) |

See [Pipeline stages](../../pipeline-stages/index.md), [Reuse existing outputs](../../reuse-outputs/index.md), and
[Caching](../../caching/index.md) before combining a target with reuse behavior.

## Override a child

Keep child-specific fields under `dependencies.<child-name>`:

```json
{
  "dependencies": {
    "my-catboost-model": {
      "train_decoder_parameters": {
        "learning_rate": 0.1,
        "depth": 8,
        "iterations": 1000
      }
    }
  }
}
```

Apply a parent override to the parent. Applying a parent fragment to a child target can create a self-dependency and
fails.

## Delete a leaf value

Use `null` only when the effective definition is valid without that field:

```json
{
  "hotvect_execution_parameters": {
    "predict": {
      "samples": null
    }
  }
}
```

This removes `predict.samples` while preserving other `predict` settings.

## Verify before reporting success

Hotvect writes the materialized definition under the run metadata when an override is used. Read it rather than
assuming a nested merge worked as intended:

```bash
jq . "$METADATA_DIR/effective_algorithm_definition.json"
```

For parent/child behavior and target selection, see [Parent/child algorithms](../parent-child/index.md).
