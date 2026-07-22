---
title: Take a change to a live experiment
description: Follow one decision-system change from definition or override through training, backtesting, EMS inspection, local serving, and online results
tags: [workflow, train, backtest, experimentation, ems, serving]
---

# Take a change to a live experiment

This guide follows one candidate continuously from a source change to an Experiment Management Service (EMS)-selected
runtime and its online results. EMS is the external control plane that maps a slot and assignment key to a variant
referencing exact algorithm and parameter packages.
The goal is not merely to run every command. It is to preserve enough identity and evidence to answer:

- what code and effective definition were evaluated;
- which algorithm and parameter packages were published;
- which EMS variant selected those packages;
- which runtime handled a local or online decision;
- which online result belongs to that variant.

Hotvect supplies the local workflow, package contracts, EMS client integration, and read-only inspection CLI. Artifact
publication and EMS mutation are external control-plane operations today. Those boundaries are marked explicitly below.

## The continuous path

<div class="hv-flow">
  <div class="hv-flow__step"><span>01</span><strong>Change</strong><small>definition · override</small></div>
  <div class="hv-flow__step"><span>02</span><strong>Train</strong><small>candidate packages</small></div>
  <div class="hv-flow__step"><span>03</span><strong>Backtest</strong><small>baseline · candidate</small></div>
  <div class="hv-flow__step"><span>04</span><strong>Release</strong><small>publish · register</small></div>
  <div class="hv-flow__step"><span>05</span><strong>Observe</strong><small>serve · online results</small></div>
</div>

Use synthetic values while learning the workflow, then replace every placeholder with values from your project:

```bash
export ALGORITHM_NAME=example-ranker
export ALGORITHM_REPO=/path/to/algorithm-checkout
export BASELINE_REF='<baseline-commit-sha>'
export CANDIDATE_REF='<candidate-commit-sha>'
export CANDIDATE_VERSION=1.2.3
export ALGORITHM_JAR_RELATIVE_PATH=ranker/target/example-ranker-1.2.3-shaded.jar
export TEST_DATE=2000-01-08
export DATA_DIR=/path/to/data
export TRAIN_OUTPUT=/path/to/train-output
export BACKTEST_OUTPUT=/path/to/backtest-output
export BACKTEST_SCRATCH=/path/to/backtest-scratch

export EMS_URL=https://experiments.example.com
export EMS_SLOT=example-slot
export EMS_ASSIGNMENT_KEY=example-assignment
export EXPERIMENT_ID=42

one_file() {
  matches="$(find "$1" -type f -name "$2" -print | sort)"
  test -n "$matches"
  test "$(printf '%s\n' "$matches" | wc -l | tr -d ' ')" -eq 1
  printf '%s\n' "$matches"
}
```

You also need the algorithm project's build prerequisites, an activated Hotvect installation, data matching the
definition, and credentials for the external systems you are permitted to inspect.

## 1. Make one reviewable change

Change the embedded definition when the candidate is intended to become a released default. Use an override when you
want to iterate on one explicit configuration patch without repeatedly editing the source definition:

```json
{
  "catboost_options": {
    "iterations": 300
  }
}
```

Pass that file to an exploratory run with `--algorithm-override /path/to/candidate-override.json`. Hotvect records the
complete effective definition beside the run metadata.

!!! warning "Promotion boundary for overrides"

    A parameter ZIP does not reactivate the override that produced it. EMS-backed serving also rejects
    `--algorithm-override`. Before continuing to a live experiment, put the accepted values in the embedded algorithm
    definition, bump the algorithm version, commit the change, and build that exact commit. The released algorithm
    package must contain the definition that its parameters expect.

The remainder of this guide treats `CANDIDATE_REF` as that fixed, release-candidate commit. Use the project build to
create its algorithm package. For a Maven algorithm project, this is typically:

```bash
cd "$ALGORITHM_REPO"
git switch --detach "$CANDIDATE_REF"
mvn clean package

export ALGORITHM_JAR="$ALGORITHM_REPO/$ALGORITHM_JAR_RELATIVE_PATH"
test -f "$ALGORITHM_JAR"
jar tf "$ALGORITHM_JAR" | grep -Fx "$ALGORITHM_NAME-algorithm-definition.json"
```

Do not use a moving branch name for the final evidence. Record the commit SHA.

## 2. Train and evaluate the exact candidate

Train from the release-candidate package, not from an earlier exploratory JAR:

```bash
hv train \
  --algorithm-name "$ALGORITHM_NAME" \
  --algorithm-jar "$ALGORITHM_JAR" \
  --data-base-dir "$DATA_DIR" \
  --output-base-dir "$TRAIN_OUTPUT" \
  --last-test-time "$TEST_DATE" \
  --target evaluate
```

`evaluate` prepares parameters, predicts the historical test slice, evaluates it, and normally runs the configured
performance test. Start at the generated `result.json` and read the stage outcomes rather than assuming every optional
stage ran:

```bash
export TRAIN_RESULT="$(one_file \
  "$TRAIN_OUTPUT/metadata/$ALGORITHM_NAME@$CANDIDATE_VERSION" \
  result.json)"
jq . "$TRAIN_RESULT"
```

If an earlier exploratory run used an override, compare its `effective_algorithm_definition.json` with the definition
now embedded in the candidate package. They should describe the accepted behavior before you treat the new training
output as its replacement.

## 3. Backtest the fixed baseline and candidate

Run both commits against the same data, test date, and evaluation contract:

```bash
hv backtest \
  --git-reference "$BASELINE_REF" \
  --git-reference "$CANDIDATE_REF" \
  --algo-repo-url "$ALGORITHM_REPO" \
  --data-base-dir "$DATA_DIR" \
  --output-base-dir "$BACKTEST_OUTPUT" \
  --scratch-dir "$BACKTEST_SCRATCH" \
  --last-test-time "$TEST_DATE" \
  --number-of-runs 1 \
  --no-performance-test
```

This first comparison disables performance testing so build, data, dependency, and quality failures remain easy to
interpret. Run a controlled performance comparison separately when latency or throughput is part of the acceptance
contract.

Inspect every result, including a failed or skipped side:

```bash
find "$BACKTEST_OUTPUT/meta" -name result.json -print
```

Confirm that the results identify the expected algorithm versions, input date, dependency packages, predictions, and
evaluation outputs. A successful command is evidence of workflow completion, not proof that the candidate is good
enough to release.

## 4. Identify the two packages

The release is not just “the model.” Record the exact algorithm package that carries code and its embedded definition,
and the exact parameter package produced for it:

```bash
export PARAMETER_PACKAGE="$(one_file \
  "$TRAIN_OUTPUT/$ALGORITHM_NAME@$CANDIDATE_VERSION" \
  "$ALGORITHM_NAME@$CANDIDATE_VERSION@*.parameters.zip")"

test -f "$ALGORITHM_JAR"
test -f "$PARAMETER_PACKAGE"

printf 'algorithm package: %s\n' "$ALGORITHM_JAR"
printf 'parameter package: %s\n' "$PARAMETER_PACKAGE"
```

Inspect the parameter manifest and retain the train/backtest evidence alongside the candidate review:

```bash
unzip -p "$PARAMETER_PACKAGE" \
  "$ALGORITHM_NAME/algorithm-parameters.json" | jq .
```

The algorithm ID and parameter ID are the join keys used later by EMS metadata, serving metadata, and online results.

!!! danger "External mutation 1 — publish artifacts"

    Publish the algorithm and parameter packages through your approved artifact pipeline and trusted store. This is a
    remote mutation, is organization-specific, and has no generic Hotvect command. Record the immutable artifact URIs
    and any integrity metadata produced by that pipeline. Do not substitute a local path in an EMS variant.

!!! danger "External mutation 2 — register and start the experiment"

    In the external EMS control plane, register the algorithm and parameter records, create or update a variant that
    references their exact identities, attach it to the intended slot and experiment, and apply the approved ramp-up.
    Use the authorized EMS UI, API, or automation for your environment. `hv-exp` cannot do this: it is intentionally
    read-only. Starting or ramping an experiment changes live assignment and requires the normal operational approval.

## 5. Read back what EMS will serve

Do not rely on the mutation request alone. Read the external control-plane state back through `hv-exp`:

```bash
hv-exp slot get --slot-name "$EMS_SLOT" | jq .
hv-exp experiment get --experiment-id "$EXPERIMENT_ID" | jq .
hv-exp experiment rampup-log --experiment-id "$EXPERIMENT_ID" | jq .
hv-exp algorithm list-in-use --slot-name "$EMS_SLOT" | jq .
```

Use the algorithm identity returned by EMS to inspect its definition and available parameters:

```bash
hv-exp algorithm get \
  --algorithm-name "$ALGORITHM_NAME" \
  --algorithm-version "$CANDIDATE_VERSION" | jq .

hv-exp algorithm parameter list \
  --algorithm-name "$ALGORITHM_NAME" \
  --algorithm-version "$CANDIDATE_VERSION" | jq .
```

Verify the slot, experiment state, variant allocation, algorithm ID, parameter ID, and published artifact locations.
Stop if they do not resolve to the packages accepted in the previous steps.

## 6. Exercise the EMS-selected runtime locally

`hv serve` can use the same EMS selection and repository-loading path for local debugging. The assignment key is
deterministic, so use a key known to select the variant you intend to inspect:

```bash
export EMS_TOKEN='<bearer-token>'

hv serve \
  --ems-url "$EMS_URL" \
  --ems-slot "$EMS_SLOT" \
  --ems-assignment-key "$EMS_ASSIGNMENT_KEY" \
  --port 8080
```

This connects to EMS and downloads published artifacts. It is an external read with local execution, not a production
deployment. `EMS_TOKEN` is the default bearer-token environment variable; use `--ems-token-env <NAME>` when your
environment exposes it under another name.

In another shell, inspect the selected runtime before sending a representative request:

```bash
curl --fail --silent http://127.0.0.1:8080/api/metadata | jq .

curl --fail --silent \
  --header 'Content-Type: application/json' \
  --data @/path/to/request.json \
  http://127.0.0.1:8080/predict | jq .
```

The metadata and prediction response identify the algorithm, parameters, runtime, and EMS variant used. Match those
identities to the values read with `hv-exp`. Use `--ui --source-path /path/to/examples` when a browser-based inspection
is useful; it exercises the same local serving core.

## 7. Inspect attributed online results

After the online evaluation pipeline has produced result partitions, discover the available analysis dates:

```bash
hv-exp experiment results list --experiment-id "$EXPERIMENT_ID" | jq .
```

Inspect one partition as decompressed JSONL:

```bash
hv-exp experiment results show \
  --experiment-id "$EXPERIMENT_ID" \
  --analysis-date 2000-01-15
```

Or download the retained partitions and manifest:

```bash
hv-exp experiment results download \
  --experiment-id "$EXPERIMENT_ID" \
  --analysis-date 2000-01-15 \
  --output-base-dir /path/to/online-results
```

The configured results source must correspond to the experiment's slot. Interpret the online result only after
confirming its experiment and variant identities, analysis window, allocation/ramp-up history, and metric definition.

## Completion check

The chain is complete when you can point to all of the following without inferring an identity:

- candidate commit and embedded effective definition;
- release-candidate train result and baseline/candidate backtest results;
- immutable algorithm and parameter package locations;
- EMS variant, experiment, slot, allocation, and ramp-up history;
- local EMS-backed serving metadata and one representative response;
- online result partition attributed to the same experiment and variant.

For deeper detail, read [Configuration and experimentation](../../concepts/configuration-and-experimentation/index.md),
[Train locally](../local-train/index.md), [Backtest locally](../local-backtest/index.md), and the
[`hv-exp` reference](../../reference/cli/index.md#hv-exp).
