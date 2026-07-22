---
title: Pipeline stages
description: Follow artifacts through dependency preparation, state generation, encode, train, predict, evaluate, and performance testing
tags: [pipeline, training, prediction, evaluation, performance]
difficulty: introductory
estimated_time: 15 minutes
prerequisites:
  - Able to run `hv --help`
  - Familiarity with basic ML terms such as feature, model, and metric
related_docs:
  - ../../concepts/artifacts-and-identity/index.md
  - ../../architecture/offline-lifecycle/index.md
  - ../../reference/algorithm-definition/index.md
  - ../reuse-outputs/index.md
related_commands:
  - hv generate-state
  - hv encode
  - hv train
  - hv predict
  - hv evaluate
  - hv performance-test
---

# Pipeline stages

Hotvect's offline pipeline turns data and an algorithm package into a reusable parameter package, then optionally checks
what the complete algorithm does with it. The easiest way to follow a run is to track the artifacts passed from one
stage to the next.

Before using this page, install the `hv` CLI and identify the algorithm package and algorithm name you intend to run.
The current CLI accepts the package as a JAR. If the terms *algorithm package*, *definition*, and *parameter package*
are unfamiliar, read
[Artifacts and identity](../../concepts/artifacts-and-identity/index.md) first.

## Start with the outcome you need

`hv train` supports three run targets. The target determines where the common preparation path stops.

| Target | Intended result | Inference input | Final stages |
|---|---|---|---|
| `parameters` | Prepare a reusable parameter package (`predict-parameters.zip`) | none | Stops after packaging |
| `predict` | Train or load parameters, then publish batch predictions | `prediction_spec` | Predicts and publishes; does not evaluate or performance-test |
| `evaluate` | Measure a version on historical examples | `test_data_spec` | Predicts, evaluates, and normally performance-tests |

`evaluate` is the default pipeline target. `hv backtest` repeats that historical workflow across selected versions
and dates.

## The common lifecycle

<div class="hv-flow">
  <div class="hv-flow__step"><span>01</span><strong>Prepare</strong><small>children · inputs</small></div>
  <div class="hv-flow__step"><span>02</span><strong>Transform</strong><small>state or encoded data</small></div>
  <div class="hv-flow__step"><span>03</span><strong>Train</strong><small>model files</small></div>
  <div class="hv-flow__step"><span>04</span><strong>Package</strong><small>parameter package</small></div>
  <div class="hv-flow__step"><span>05</span><strong>Validate</strong><small>predict · quality · latency</small></div>
</div>

This flow has two preparation branches:

- a **trainable definition** has `training_command`, so Hotvect encodes training examples and runs the trainer;
- a **state-producing definition** has `generator_factory_classname`, so Hotvect generates files and skips the
  encode/train/inference path for that definition.

A definition with neither field can still describe a parameterless or pre-parameterized algorithm. Hotvect skips
encode and train, packages the available runtime material, and continues according to the selected target.

Packaging is the boundary between preparation and use. The resulting parameter package is currently written as a
predict-parameters ZIP; later prediction, performance testing, and runtime loading consume it.

## Outputs and metadata are separate

A run writes two kinds of information:

- **outputs** are reusable or inspectable artifacts such as generated state, encoded data, model files, parameter
  packages, and predictions;
- **metadata** explains the run: effective configuration, stage logs, timings, skip reasons, and metrics.

The roots depend on the command:

| Command | Output root | Metadata root |
|---|---|---|
| `hv train` | `<output-base-dir>/...` | `<output-base-dir>/metadata/...` |
| `hv backtest` | `<output-base-dir>/out/...` | `<output-base-dir>/meta/...` |

Within those roots, Hotvect groups artifacts by algorithm/hyperparameter identity and parameter version. Use
`result.json` rather than guessing which optional stages ran.

## The test-time anchor

`last_test_time` anchors date-based input selection. It is the date being predicted or evaluated, not the final day of
training.

For this synthetic example:

- `last_test_time = 2000-01-08`
- `training_lag_days = 1`
- `number_of_training_days = 7`

Hotvect selects training partitions from `2000-01-01` through `2000-01-07` and uses `2000-01-08` as the test date.
The same rule lets a backtest reconstruct what data would have been available at each historical anchor.

`prediction_spec` has its own `number_of_days` and `lag_days` because an explicit predict run need not use the
historical test slice.

## Stage by stage

### Prepare dependencies

If the definition declares child algorithms under `dependencies`, Hotvect prepares them recursively before the
parent. A child normally runs only far enough to produce parameters. A child that explicitly enables prediction,
evaluation, or performance testing may run the evaluate path as well.

The parent later packages child artifacts with its own so that the complete composition can be reconstructed. Start a
normal run at the top-level algorithm; target a child directly only when investigating the stage it owns.

<div class="hv-flow">
  <div class="hv-flow__step"><span>01</span><strong>Select</strong><small>top-level algorithm</small></div>
  <div class="hv-flow__step"><span>02</span><strong>Resolve</strong><small>declared children</small></div>
  <div class="hv-flow__step"><span>03</span><strong>Prepare</strong><small>child artifacts</small></div>
  <div class="hv-flow__step"><span>04</span><strong>Build</strong><small>parent artifact</small></div>
  <div class="hv-flow__step"><span>05</span><strong>Run</strong><small>complete algorithm</small></div>
</div>

### `generate-state`

`generate-state` runs only for a definition with `generator_factory_classname`.

- **Input:** paths resolved from the definition's `source_data` entries.
- **Output:** a generated file or directory under the algorithm output root.
- **Next consumer:** parameter packaging, then usually a parent algorithm or runtime factory.

Examples of neutral state artifacts are an ID mapping, aggregate counts, or a directory of fixed lookup files. The
generator's output is an offline lifecycle role; it is not a separate public algorithm request or response type.

If `state_output_filename` is present, it selects the relative file or directory to package. Otherwise the algorithm
output directory is the state root. The definition's own run skips encode, train, predict, evaluate, and
performance-test after generating and packaging the state.

### Prepare encode parameters

Before encoding a trainable algorithm, Hotvect creates an encode-parameters ZIP. It contains material the transformer
or encoder needs during encode, including prepared dependency artifacts. In `result.json`, this step is named
`package_encode_params`.

This is a pipeline bridge rather than a separate model-development stage. You normally inspect it only when child
state or transformer loading fails during encode.

### `encode`

`encode` converts raw offline examples into the format expected by the training command.

- **Input:** training partitions, train decoder, feature transformer or vectorizer, reward function, and encoder.
- **Output:** an `encoded/` directory of `part-*` files plus `encoded-schema-description` when the encoder supplies a
  schema.
- **Next consumer:** the command in `training_command`.

Part-file extensions come from the encoder, for example `.jsonl`, `.tsv`, or `.tfrecord`. Encoded output is a directory,
not one guaranteed file.

### `train`

`train` exists when the algorithm definition contains `training_command`.

- **Input:** the encoded directory, schema description, effective definition, algorithm JAR, encode-parameters ZIP,
  and a scratch directory exposed as template variables.
- **Output:** trainer-owned files under `model_parameter/`.
- **Next consumer:** predict-parameter packaging.

Hotvect renders `training_command` and executes it. The trainer decides the internal model layout; Hotvect does not
infer a model format from the directory contents.

### Package predict parameters

After generation or training, Hotvect packages inference-time files and parameter metadata. In `result.json`, the step
is `package_predict_params`.

The normal filename is:

```text
<hyperparameter_slug>@<parameter_version>.parameters.zip
```

The archive can contain model files, generated state, and child artifacts, each under its algorithm-name namespace.
This package is the reusable result for a `parameters` target and the input to subsequent inference stages.

A state-producing definition can disable its standalone ZIP with
`hotvect_execution_parameters.package_state_as_predict_parameters=false`; parent preparation can still package its raw
state and metadata.

### `predict`

`predict` loads the complete algorithm from the JAR and parameter ZIP, decodes examples, and writes decisions.

- For `target=evaluate`, it reads the historical slice declared by `test_data_spec` and writes the run-local
  `prediction/` directory.
- For `target=predict`, it reads `prediction_spec` and publishes the final prediction artifact to
  `prediction_spec.output_uri`.

Prediction does not retrain the model. Feature transformation happens as part of algorithm execution. Hotvect does not
normally materialize a separate encoded test dataset before prediction.

### `evaluate`

`evaluate` turns historical predictions into quality metrics using the selected Python evaluation function.

- **Input:** the run-local prediction artifact from the evaluate target.
- **Output:** `evaluate/metadata.json` under the metadata root.

Metric names and meanings belong to the configured evaluation function. Read that function's contract before comparing
values across algorithms.

### `performance-test`

`performance-test` loads the same algorithm artifact and repeatedly executes decoded requests.

- **Input:** the test examples plus the JAR and predict-parameters ZIP.
- **Output:** `performance-test/metadata.json` under the metadata root.

The metadata includes response-time percentiles such as `p50`, `p95`, and `p99`, throughput, workload mode, and sample
counts. It describes runtime behavior, not decision quality. The default workload mode is `realtime`; set `batch`
explicitly when that is what you intend to measure.

### Optional post-prediction work

An evaluate run can also include:

- `encode_test`, when test-data encoding was requested; and
- `audit`, when feature audit execution was requested.

These appear as their own entries in `result.json`. They are not prerequisites for the normal quality and performance
path.

## Why stages may be skipped

A skipped stage is often expected. Common reasons are:

- a cached or explicitly supplied parameter ZIP made encode and train unnecessary;
- the definition has no `training_command`;
- the definition is state-producing;
- the selected target is `parameters` or `predict`;
- no dated test partition exists for an evaluate run;
- prediction, evaluation, performance testing, test encoding, or auditing was disabled.

Do not infer the reason from missing files. The stage entry in `result.json` records a `skipped` explanation and the
corresponding timing is normally zero.

## Parameters and hyperparameters

- **Parameters** are files consumed at runtime: learned weights, lookup tables, thresholds, generated state, or other
  model material. Hotvect packages them into the predict-parameters ZIP.
- **Hyperparameters** are choices made before training that influence how those files are produced. They affect run
  identity, but they are not the runtime artifact themselves.
- Definition fields such as `algorithm_parameters` and `transformer_parameters` are JSON construction configuration.
  They are distinct from both learned parameter files and the CLI's hyperparameter-version identity.

## What to inspect after a run

Start with four things:

1. `result.json` — target, effective definition, child results, stage outcomes, skip reasons, and timings.
2. The predict-parameters ZIP — the reusable runtime artifact and its namespaced contents.
3. `prediction/part-*` — decisions written for the historical evaluate slice, when prediction ran.
4. `hv.log` and stage `stdout-stderr.log` files — command output and failure context.

Then inspect `evaluate/metadata.json` for quality or `performance-test/metadata.json` for runtime behavior. Keeping those
questions separate prevents a fast but poor algorithm—or a good but unserviceable one—from looking complete.

## Next steps

- [Train locally](../local-train/index.md)
- [Backtest locally](../local-backtest/index.md)
- [Reuse previous outputs](../reuse-outputs/index.md)
- [Read the algorithm definition reference](../../reference/algorithm-definition/index.md)
