---
title: Train an algorithm locally
description: Run one built Hotvect algorithm through its local parameter-preparation lifecycle and inspect the result
tags: [train, local, parameters, workflow]
---

# Train an algorithm locally

Use `hv train` when you already have an algorithm JAR and want to prepare its state or parameters from local data. Use
a backtest instead when the primary question is how source revisions compare on historical outcomes.

## You need

- an activated Hotvect installation;
- the algorithm JAR and algorithm name;
- local directories matching the definition's data prefixes and date partitions;
- a test-date anchor and an empty output directory;
- any credentials or executables required by the declared training command.

Before running, inspect the effective definition. It determines which data, children, and stages are required.

Choose the target that matches the intended output:

| Target | Result |
| --- | --- |
| `parameters` | Prepare and package parameters without prediction or evaluation |
| `predict` | Prepare parameters and publish predictions from `prediction_spec` |
| `evaluate` | Prepare parameters, predict the historical test slice, evaluate it, and normally performance-test it |

The smallest first training run uses `parameters`. `<test-date>` must use `YYYY-MM-DD` format; examples elsewhere use
clearly synthetic `2000-01-xx` dates.

## Run one local training lifecycle

```bash
hv train \
  --algorithm-name <algorithm-name> \
  --algorithm-jar /path/to/algorithm.jar \
  --data-base-dir /path/to/data \
  --output-base-dir /path/to/output \
  --last-test-time <test-date> \
  --target parameters
```

Hotvect prepares declared children first, then runs the stages enabled by the effective definition and target. A typical
train-and-evaluate flow is:

```text
data → encode → training command → package parameters → predict → evaluate
```

State generation, caching, pinned parameters, missing test data, and disabled stages can change that path. Read the
result rather than assuming every stage ran.

## Apply an explicit definition patch

Use an override for a temporary data window, hyperparameter, runtime setting, or child patch:

```bash
hv train \
  --algorithm-name <algorithm-name> \
  --algorithm-jar /path/to/algorithm.jar \
  --data-base-dir /path/to/data \
  --output-base-dir /path/to/output \
  --last-test-time <test-date> \
  --target parameters \
  --algorithm-override /path/to/override.json
```

An override is a recursive patch over the definition embedded in the JAR. The resulting parameter ZIP does not
automatically activate that override when it is later loaded; the intended runtime must receive the same override or a
JAR containing the released definition.

## Inspect success

Start at:

```text
<output-base-dir>/metadata/<algorithm-id>/<parameter-version>/result.json
```

Confirm:

- the selected algorithm and effective parameter version are expected;
- dependency entries identify the prepared child artifacts;
- each stage says whether it ran, reused output, or was skipped;
- the predict-parameters ZIP exists when the lifecycle packages one;
- evaluation or performance metadata exists only when those stages were requested and completed.

Use `hv.log` for the high-level run and stage logs for a focused failure. A zero exit code proves that the requested
workflow completed; it does not establish model quality or production readiness.

## Next

- [Pipeline stages](../pipeline-stages/index.md) explains each artifact in the chain.
- [Caching](../caching/index.md) explains safe reuse during iteration.
- [Backtest locally](../local-backtest/index.md) compares fixed source revisions or dates.
- [Embed Hotvect in Java](../application-integration/index.md) loads a validated artifact into a containing application.
