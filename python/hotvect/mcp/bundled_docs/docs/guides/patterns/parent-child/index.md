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
  - ../../../concepts/index.md
  - ../../develop-algorithms/index.md
  - ../../../reference/faq/index.md
  - ../override-files/index.md
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

See [Override Files Pattern](../override-files/index.md) for more details.

## Data Requirements

Both parent and child need their own data dependencies:

**Parent Data**:
- Test data for evaluation
- Specified in parent's `test_data_spec` (or `test_data_prefix`)
- Example: `evaluation_test_data`

**Child Data**:
- Training data for ML model
- Test data for child-level evaluation
- Specified in child's `train_data_spec` / `test_data_spec` (or `train_data_prefix` / `test_data_prefix`)
- Example: `example_training_data`

All data must be available for dates calculated based on:
- `--last-test-time`
- `training_lag_days` in algorithm definitions
- `number_of_training_days` (from override or algorithm definition)

See [Data Dependencies Pattern](../data-dependencies/index.md) for date calculation details.

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

## Advanced: Sharing computed values across the dependency DAG (Computing + memoization)

Parent/child composition is not just about *training orchestration*; it also affects *inference-time computation*.
In Hotvect, an algorithm can call into dependency algorithms, and the whole structure can form a dependency **DAG**
(parents → children → grandchildren …).

### The key idea: pass a `ComputingRankingRequest` down the DAG

Hotvect supports two ways of invoking a dependency scorer:
- With a plain `RankingRequest` (no shared compute context is reused across boundaries)
- With a `ComputingRankingRequest` (reuses the existing `Computing` contexts)

When you call a dependency algorithm with a `ComputingRankingRequest`, the dependency algorithm sees the same
`Computing.shared()` instance as its caller. This enables “compute once, reuse everywhere” across the whole DAG.

### Memoized (lazy) computations: compute once by `Namespace`

Transformations are addressed by `Namespace`. If a transformation is registered as memoized, then:
- it will run at most once per request, and
- the computed value is cached inside `Computing`.

Any code (parent, child, grandchild) that calls `shared.compute(namespace)` will reuse that cached value.

This is the preferred pattern for sharing derived values across dependency boundaries.

### Eager transformations: prefetch once, then reuse

Some work is intentionally eager (run up-front), for example:
- fetching Feature Store views (one network call, available for multiple downstream computations)

Hotvect supports registering one or more eager steps on a `StandardRankingTransformer` via:

```java
builder.withEagerTransformation(eagerId, eagerTransformation);
```

Where:
- `eagerId` is a marker `Namespace` (not a feature namespace; it has no feature `ValueType`) used only to store `true` and indicate the eager step already ran.
- `eagerTransformation` returns a map of namespaces (also not feature namespaces) containing “prefetched” values (e.g. Feature Store responses) for downstream computations.

When a dependency algorithm is prepared using `prepare(ComputingRankingRequest)`:
- for each registered eager step (in insertion order):
  - if `eagerId` is already present as a shared precalculated value, the eager step is skipped
  - otherwise, the eager step is executed, its results are stored as shared precalculated values, and `eagerId` is set to `true`

Fail-fast behavior:
- registering the same `eagerId` twice throws
- if an eager step attempts to write to a namespace that already exists as a shared precalculated value, Hotvect throws
- eager steps cannot produce ML feature namespaces directly (register a memoized/lazy computation that reads the eager output)

#### Note on correctness: “same namespace” must mean “same value”

Hotvect reuses eager results by checking whether `eagerId` is already present. This assumes the eager step’s output is
correct for all downstream consumers (child algorithms, grandchildren, etc.). If the output content depends on
configuration (for example: a Feature Store response that depends on which feature names were requested), then callers
must ensure either:
- all consumers agree on the same configuration, or
- the eager producer computes a superset that satisfies all consumers.

### Practical guidance

- If you are writing a wrapper ranker/scorer that needs expensive shared data (e.g. Feature Store), fetch it once in the
  wrapper and call the dependency scorer using the *Computing* APIs so reuse is possible.
- Prefer memoized transformations for derived values and eager transformations for batch/prefetch-style external calls.
- If you see duplicate external calls across dependency layers, check whether you are accidentally invoking a dependency
  algorithm with a plain `RankingRequest` instead of a `ComputingRankingRequest`.

## See Also

- [Override Files Pattern](../override-files/index.md) - Configure parent-child training
- [Data Dependencies Pattern](../data-dependencies/index.md) - Calculate required data dates
- [FAQ: Outer (parent) vs inner (child) algorithms](../../../reference/faq/index.md#q-whats-the-difference-between-outer-parent-and-inner-child-algorithms-and-which-cli-commands-can-i-run-on-each)
- [Troubleshooting: TRAIN-003](../../../reference/troubleshooting/index.md#train-003-duplicate-key-algorithm-parametersjson)
