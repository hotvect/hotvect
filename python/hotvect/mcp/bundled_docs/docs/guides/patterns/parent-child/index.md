---
title: Parent and child algorithms
description: Target, configure, and inspect composite Hotvect algorithms without assuming the role of a dependency
tags: [patterns, algorithms, dependencies, architecture]
related_docs:
  - ../../../concepts/index.md
  - ../override-files/index.md
  - ../data-dependencies/index.md
---

# Parent and child algorithms

A composite algorithm declares other algorithms under `dependencies`. The declaring algorithm is the parent (or
outer algorithm); each declared dependency is a child (or inner algorithm).

Nesting describes composition, not behavior. A parent is not necessarily evaluation-only, and a child is not
necessarily a trainable model. Inspect each embedded definition to find the transformer, encoder, training command,
state generator, and execution settings it owns.

## Choose the command target by ownership

| Task | Target |
| --- | --- |
| End-to-end train or backtest | Usually the public parent; Hotvect prepares its dependency graph |
| Feature audit | The algorithm that exposes the relevant transformer; the current audit task rejects vectorizers |
| Encode debugging | The algorithm that exposes the encoder |
| State generation | The algorithm with the state generator |
| Focused child train | The child itself, with a child-specific override |

Start at the parent when the question is about public behavior. Target a child only to isolate a contract that child
owns.

## Training and artifacts

```bash
hv algorithm train \
  --algorithm-name example-ranker \
  --algorithm-jar /path/to/example-ranker.jar \
  --data-base-dir /path/to/data \
  --output-base-dir ./training-output \
  --last-test-time 2000-01-01
```

Hotvect recursively prepares dependencies before the parent continues. A dependency may train, generate state, reuse
pinned parameters, or do no parameter work; read `result.json` instead of assuming every child trained.

For `hv algorithm train`, inspect:

```text
<output-base-dir>/metadata/<algorithm-id>/<parameter-version>/result.json
```

For `hv algorithm backtest`, the corresponding metadata root is `meta`. Parent and child runs each have their own algorithm and
parameter-version directory.

## Override a child through the parent

Apply the override to the parent and nest child fields under the declared child name:

```json
{
  "hyperparameter_version": "2day",
  "hotvect_execution_parameters": {
    "performance-test": {"enabled": false}
  },
  "dependencies": {
    "example-model": {
      "number_of_training_days": 2,
      "hotvect_execution_parameters": {
        "performance-test": {"enabled": false}
      }
    }
  }
}
```

Rules:

- a parent-level field patches the parent;
- `dependencies.<child-name>` patches that child;
- unspecified siblings remain unchanged;
- an unknown child name fails;
- an override cannot add a new dependency.

Do not apply a parent override directly to a child target. Create a fragment whose root fields are valid for that
child.

## Dependency targets and data

By default, a child used as a dependency runs with target `parameters`. It runs with target `evaluate` only when the
child explicitly enables `predict`, `evaluate`, or `performance-test` in its own execution parameters. A parent with a
pre-v10 `training_container` is a compatibility exception that selects child evaluation.

This affects data requirements:

- training and source data are needed to prepare child parameters;
- child test data is needed only when that child is evaluated;
- parent test data remains separate;
- each dependency resolves its dates from the shared `last_test_time` and its own lag/window settings.

Use `hv data dependencies inspect` with the same target and override as the planned run. See
[Data dependencies](../data-dependencies/index.md).

## Multiple children and deeper graphs

`dependencies` can contain multiple children, and those children can declare dependencies of their own. Treat the
result as a dependency graph, not a fixed two-level tree.

```json
{
  "dependencies": {
    "candidate-state": {},
    "example-model": {},
    "policy-model": {}
  }
}
```

The parent packages or consumes dependency artifacts according to its algorithm factories. The presence of three
children does not by itself define their order or data flow; that comes from the definitions and runtime wiring.

Each private nested algorithm receives parameter streams from its own namespace in the parent graph's parameter ZIP.
In online serving, every refresh selects the freshest available static-shared namespace by logical data date
`last_test_time`, using `ran_at` to break ties between reruns of that date. The canonical code provider constructs one
instance from that ZIP for every caller in the new generation; callers' parameter IDs never split it. A
parameter-bearing shared namespace must include `last_test_time`, and a parameterized ZIP must package every node's
files below that node's algorithm name.

## Failure checklist

- Unknown dependency in an override: compare the key with the parent's embedded definition.
- Missing child test data: check whether that child explicitly enables an evaluation stage.
- Missing child artifact: inspect the child's nested entry in the parent `result.json`.
- Nested composite parameter file not found: verify that the ZIP contains the file below the nested algorithm's own
  namespace.
