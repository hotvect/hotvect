---
title: Parent-Child Algorithm Pattern
description: Understanding and working with composite algorithms with parent-child dependencies
tags: [patterns, algorithms, dependencies, architecture, parent-child]
difficulty: intermediate
estimated_time: 20 minutes
prerequisites:
  - Understanding of hotvect algorithms
  - Familiarity with algorithm definitions
  - Basic knowledge of training workflow
related_docs:
  - ../highlevel/concepts.md
  - ../howto/develop-a-re-ranker-with-hotvect.md
  - ../faq.md
  - ./override-files.md
related_commands:
  - hv train
  - hv backtest
---

# Parent-Child Algorithm Pattern

## Overview

Hotvect supports composite algorithms where a **parent algorithm** (also called an **outer algorithm**) orchestrates one or more **child algorithms** (the **inner** or **training** algorithms) as dependencies. This pattern enables separation of concerns between evaluation/testing logic and ML model training.

## Architecture

```
┌─────────────────────────────────────────┐
│   Parent Algorithm                      │
│   - Orchestrates evaluation/testing     │
│   - May have no vectorizer/transformer  │
│   - Declares children in dependencies   │
│   - Uses child outputs for evaluation   │
└───────────────┬─────────────────────────┘
                │
                │ depends on
                │
┌───────────────▼─────────────────────────┐
│   Child Algorithm(s)                    │
│   - Implements ML model training        │
│   - Has vectorizer/transformer          │
│   - Produces prediction parameters      │
│   - Independent training logic          │
└─────────────────────────────────────────┘
```

## Example: product-ranker

**Parent**: `product-ranker`
- **Role**: Orchestrates evaluation and testing
- **Test Data**: `evaluation_test_data`
- **Has Vectorizer**: No (delegates to child)
- **Algorithm Definition**: Contains `dependencies` section

**Child**: `product-ranker-model`
- **Role**: Trains ML model
- **Training Data**: `training_data`
- **Test Data**: `model_test_data`
- **Has Vectorizer**: Yes (implements feature engineering)
- **Algorithm Definition**: No dependencies

### Directory Structure After Training

```
training-output/
├── product-ranker@1.0.0/
│   └── last_test_date_2025-08-09/
│       ├── product-ranker@1.0.0@last_test_date_2025-08-09.parameters.zip
│       ├── evaluation.json
│       └── prediction.jsonl
├── product-ranker-model@1.0.0/
│   └── last_test_date_2025-08-09/
│       ├── product-ranker-model@1.0.0@last_test_date_2025-08-09.parameters.zip
│       ├── model.cbm
│       ├── training.log
│       └── evaluation.json
└── metadata/
    ├── product-ranker@1.0.0/
    └── product-ranker-model@1.0.0/
```

## Training Workflow

### Correct Approach: Train Parent

```bash
hv train \
  --algorithm-name product-ranker \
  --data-base-dir /path/to/data \
  --output-base-dir ./training-output \
  --algorithm-jar ~/.m2/repository/.../product-ranker-1.0.0.jar \
  --last-test-time 2025-08-09
```

**What happens**:
1. Parent algorithm definition is loaded
2. System identifies child dependencies
3. Child algorithm is trained first (automatically)
4. Child parameters are produced
5. Parent uses child parameters for evaluation
6. Parent produces final evaluation results

### Incorrect Approach: Train Child Directly

```bash
# WRONG - causes self-dependency bug
hv train \
  --algorithm-name product-ranker-model \
  --algorithm-override parent-override.json \
  ...
```

**Why this fails**:
- Override file designed for parent contains `dependencies` section
- When applied to child, child thinks it depends on itself
- Results in "Duplicate key algorithm-parameters.json" error

## How to Identify Parent vs Child

### Parent Characteristics
- Has `dependencies` section in algorithm-definition.json
- May lack `vectorizer_factory_classname` (delegates to child)
- Focuses on evaluation and testing logic
- Uses child outputs but doesn't train models itself

**Check if algorithm is parent**:
```bash
unzip -p algorithm.jar '*/algorithm-definition.json' | jq '.dependencies'
# If non-empty, this is a parent
```

### Child Characteristics
- No `dependencies` section (or empty)
- Has `vectorizer_factory_classname` (feature engineering)
- Implements ML model training
- Produces trained model parameters

## Using Override Files with Parent-Child

Override files are designed for **parent algorithms** and specify overrides for children in the `dependencies` section:

```json
{
  "hyperparameter_version": "2day",
  "hotvect_execution_parameters": {
    "performance-test": {"enabled": false}
  },
  "dependencies": {
    "product-ranker-model": {
      "number_of_training_days": 2,
      "hotvect_execution_parameters": {
        "performance-test": {"enabled": false}
      }
    }
  }
}
```

**Key points**:
- Parent-level settings apply to parent
- `dependencies.<child-name>` settings apply to specific child
- Parent automatically passes overrides to children
- Never apply parent's override file to child directly

See [Override Files Pattern](./override-files.md) for more details.

## Data Requirements

Both parent and child need their own data dependencies:

**Parent Data**:
- Test data for evaluation
- Specified in parent's `test_data_spec`
- Example: `evaluation_test_data`

**Child Data**:
- Training data for ML model
- Test data for child-level evaluation
- Specified in child's `data_dependency_spec` and `test_data_spec`
- Example: `training_data`

All data must be available for dates calculated based on:
- `--last-test-time`
- `training_lag_days` in algorithm definitions
- `number_of_training_days` (from override or algorithm definition)

See [Data Dependencies Pattern](./data-dependencies.md) for date calculation details.

## Common Pitfalls

### Pitfall 1: Training Child with Parent's Override

**Symptom**: "Duplicate key algorithm-parameters.json" error

**Cause**: Parent's override file applied to child makes child depend on itself

**Solution**: Always train the parent, which cascades correctly

### Pitfall 2: Missing Child Test Data

**Symptom**: Training completes but no child evaluation

**Cause**: Child's test data not available for required dates

**Solution**: Ensure child's test data exists:
```bash
ls -la /path/to/data-base-dir/child_test_data_prefix/dt=YYYY-MM-DD
```

## Benefits of Parent-Child Pattern

1. **Separation of Concerns**
   - Child focuses on ML model training
   - Parent focuses on evaluation logic
   - Each can be developed/tested independently

2. **Reusability**
   - Same child can be used by multiple parents
   - Child can be tested standalone
   - Parent can swap different child implementations

3. **Flexible Evaluation**
   - Parent can evaluate child on different test sets
   - Parent can combine multiple children (ensembles)
   - Parent can add business logic on top of ML predictions

4. **Clear Dependencies**
   - Explicit dependency declaration in algorithm definition
   - Automatic dependency resolution during training
   - Clear data flow from child to parent

## Advanced: Multiple Children

A parent can have multiple child dependencies:

```json
{
  "dependencies": {
    "child-model-a": {...},
    "child-model-b": {...},
    "child-model-c": {...}
  }
}
```

All children are trained in order, and parent can use outputs from all of them.

## See Also

- [Override Files Pattern](./override-files.md) - Configure parent-child training
- [Data Dependencies Pattern](./data-dependencies.md) - Calculate required data dates
- [FAQ: Parent-Child Algorithms](../faq.md#q-whats-the-difference-between-parent-and-child-algorithms)
- [Troubleshooting: TRAIN-003](../troubleshooting.md#train-003-duplicate-key-algorithm-parametersjson)
