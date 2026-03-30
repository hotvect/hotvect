---
title: Algorithm Override Files Pattern
description: How to use algorithm override files to customize training behavior
tags: [patterns, configuration, training, overrides, hyperparameters]
difficulty: intermediate
estimated_time: 15 minutes
prerequisites:
  - Understanding of hotvect training workflow
  - Familiarity with JSON format
  - Knowledge of parent-child algorithm pattern
related_docs:
  - ../parent-child/index.md
  - ../data-dependencies/index.md
  - ../../reuse-outputs/index.md
  - ../../../reference/cli/index.md
related_commands:
  - hv train --algorithm-override
  - hv backtest
---

# Algorithm Override Files Pattern

## Overview

Algorithm override files allow you to customize training behavior without modifying algorithm JARs. They're JSON files that override settings in the algorithm-definition.json packaged within the JAR.

**Merge semantics**: Override files are applied as a recursive merge overlay on top of the definition in the JAR. If a key is missing from the override file, it stays as-is from the base definition (i.e., missing keys are not deleted).

## Basic Structure

```json
{
  "hyperparameter_version": "custom-experiment",
  "hotvect_execution_parameters": {
    "performance-test": {"enabled": false},
    "encode": {"enabled": true},
    "train": {"enabled": true},
    "predict": {"enabled": true},
    "evaluate": {"enabled": true}
  },
  "dependencies": {
    "child-algorithm-name": {
      "number_of_training_days": 2,
      "hotvect_execution_parameters": {
        "performance-test": {"enabled": false}
      }
    }
  }
}
```

### What is hyperparameter_version?

The `hyperparameter_version` field is a string label that gets appended to your algorithm's output directory name. It ensures that outputs from different configurations don't overwrite each other.

**How it works:**
- Without hyperparameter_version: `my-algorithm@1.0.0/last_test_date_2025-08-09/`
- With `"2day"`: `my-algorithm@1.0.0-2day/last_test_date_2025-08-09/`

**Why it matters:** When you use overrides to change training behavior (e.g., 2 days instead of 7, disabled performance tests), each configuration gets its own directory. This lets you:
- Compare results between configurations side-by-side
- Keep default and experimental outputs separate
- Avoid accidentally overwriting important results

**Common values:**
- `"1day"` - Standard 1-day training
- `"2day"` - Fast 2-day training for iteration
- `"fast"` - Minimal configuration for quick testing
- Any custom label you choose

## Usage

```bash
hv train \
  --algorithm-name my-algorithm \
  --algorithm-override /path/to/override.json \
  --data-base-dir /path/to/data \
  --output-base-dir ./output \
  --algorithm-jar algorithm.jar \
  --last-test-time 2025-08-09
```

## Common Override Patterns

### 1. Fast Training (2-Day Override)

For quick iteration during development:

```json
{
  "hyperparameter_version": "2day",
  "hotvect_execution_parameters": {
    "performance-test": {"enabled": false}
  },
  "dependencies": {
    "my-model-algorithm": {
      "number_of_training_days": 2,
      "hotvect_execution_parameters": {
        "performance-test": {"enabled": false}
      }
    }
  }
}
```

**When to use**:
- Testing algorithm changes locally
- Debugging training issues
- Quick validation before full training

**Time savings**: ~10x faster (2 days vs 7-30 days)

### 2. Skip Performance Tests

Disable performance benchmarking to speed up training:

```json
{
  "hotvect_execution_parameters": {
    "performance-test": {"enabled": false}
  }
}
```

**Time savings**: ~10-30% faster depending on algorithm

### 3. Reuse Existing Parameters

Skip training and use pre-trained model:

```json
{
  "dependencies": {
    "my-model": {
      "hotvect_execution_parameters": {
        "with_parameter": "s3://example-bucket/path/parameters.zip"
      }
    }
  }
}
```

**When to use**:
- Evaluating same model on different test sets
- A/B testing with known good parameters
- Reproducing production results offline

See [Reuse Existing Outputs](../../reuse-outputs/index.md) for details.

### 4. Custom Hyperparameters

Override ML training hyperparameters:

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

**When to use**: Hyperparameter tuning experiments

### 5. Deterministic / ordered decoding (algorithm-specific)

Some algorithms expose an `ordering` flag in `train_decoder_parameters` to force deterministic example ordering when decoding training data. This is useful when you want to compare two patch versions on the same `last_test_time` and remove “different file iteration order” as a variable.

Example (composite algorithm style):

```json
{
  "hyperparameter_version": "ordered",
  "dependencies": {
    "example-parent-algorithm-child-model": {
      "train_decoder_parameters": {
        "ordering": "ordered"
      }
    }
  }
}
```

Notes:
- This is **not** a global hotvect feature; the algorithm/decoder must support it.
- Do not confuse this with:
  - `--number-of-runs` (how many `last_test_time` days `hv backtest` runs)
  - `number_of_training_days` (how many training partitions are read)
  - `transformer_parameters.writer_num_shards` (encoding output sharding; in v10 it lives under `transformer_parameters`, not `train_decoder_parameters`)

### 6. Partial Pipeline Execution

Run only specific steps:

```json
{
  "hotvect_execution_parameters": {
    "encode": {"enabled": false},
    "train": {"enabled": false},
    "predict": {"enabled": true},
    "evaluate": {"enabled": true}
  }
}
```

**When to use**: Reusing encoded data or trained models

## hotvect_execution_parameters Reference

### Common Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `performance-test.enabled` | `true` | Run performance benchmarks |
| `encode.enabled` | `true` | Encode training data |
| `train.enabled` | `true` | Train ML model |
| `predict.enabled` | `true` | Generate predictions |
| `evaluate.enabled` | `true` | Calculate evaluation metrics |
| `generate-state.enabled` | Varies | Generate state files (for TopK algorithms) |

### Cache Parameters

```json
{
  "hotvect_execution_parameters": {
    "train": {
      "cache": "s3://example-bucket/path/parameters.zip"
    }
  }
}
```

**Behavior**:
- If cache exists: Use it (skip training)
- If cache missing: Train and upload to cache location

**vs with_parameter**: `cache` falls back to training; `with_parameter` fails if missing.

## Dependencies Section

The `dependencies` section specifies overrides for child algorithms:

```json
{
  "dependencies": {
    "child-algorithm-name": {
      "number_of_training_days": 2,
      "training_lag_days": 1,
      "train_decoder_parameters": {...},
      "hotvect_execution_parameters": {...}
    }
  }
}
```

**Important**: Override files are designed for **parent algorithms**. The parent cascades these settings to children automatically.

## Best Practices

### 1. Use Descriptive hyperparameter_version

```json
{
  "hyperparameter_version": "2day-no-perf-test"
}
```

**Why**: Outputs are organized by version, making it easy to track experiments.

### 2. Store Override Files in Version Control

```
project/
├── algorithm/
│   └── src/...
├── overrides/
│   ├── 2day-override.json
│   ├── prod-override.json
│   └── debug-override.json
└── README.md
```

**Why**: Reproducibility and collaboration.

### 3. Document Override Purpose

Add comments (though JSON doesn't support them officially, use documentation):

```json
{
  "_comment": "Fast training for local testing - 2 days, no performance tests",
  "hyperparameter_version": "2day",
  ...
}
```

### 4. Start Small, Scale Up

Development workflow:
1. Test with 1-day override
2. Validate with 2-day override
3. Full training with 7+ days

### 5. Keep Overrides Minimal

Only override what you need to change. Inherit defaults for everything else.

## Common Mistakes

### Mistake 1: Applying Parent Override to Child

```bash
# WRONG
hv train --algorithm-name child-algorithm --algorithm-override parent-override.json
```

**Problem**: Parent's `dependencies` section makes child depend on itself.

**Solution**: Always apply override to parent, which cascades to children.

### Mistake 2: Forgetting to Update hyperparameter_version

**Problem**: Outputs overwrite previous experiments with same version.

**Solution**: Use unique `hyperparameter_version` for each experiment.

### Mistake 3: Disabling Required Steps

```json
{
  "hotvect_execution_parameters": {
    "encode": {"enabled": false},
    "train": {"enabled": true}  // Will fail - needs encoded data
  }
}
```

**Solution**: Understand step dependencies:
- `train` requires `encode`
- `predict` requires `train`
- `evaluate` requires `predict`

## Override File Locations

Common locations to keep override files:

**Project Structure**:
```
/path/to/algorithm-project/
├── overrides/
│   ├── 2day.json
│   ├── 7day.json
│   └── prod.json
└── ...

/path/to/workdir/  # Work directory
├── algorithm-override.json  # Local experiments
└── training-output/
```

**Usage**:
```bash
cd /path/to/workdir
hv train \
  --algorithm-override /path/to/algorithm-project/overrides/2day.json \
  ...
```

## Advanced: Dynamic Override Generation

Generate override files programmatically for hyperparameter sweeps:

```python
import json

for lr in [0.01, 0.05, 0.1]:
    override = {
        "hyperparameter_version": f"lr-{lr}",
        "dependencies": {
            "my-model": {
                "train_decoder_parameters": {
                    "learning_rate": lr
                }
            }
        }
    }
    with open(f"override-lr-{lr}.json", "w") as f:
        json.dump(override, f, indent=2)
```

Then train each:
```bash
for override in override-lr-*.json; do
    hv train --algorithm-override $override ...
done
```

## See Also

- [Parent-Child Algorithms](../parent-child/index.md) - Understanding dependencies
- [Data Dependencies](../data-dependencies/index.md) - Calculating training dates
- [Reuse Previous Outputs](../../reuse-outputs/index.md) - Using with_parameter and cache
- [CLI Reference: hv train](../../../reference/cli/index.md) - Full command documentation
