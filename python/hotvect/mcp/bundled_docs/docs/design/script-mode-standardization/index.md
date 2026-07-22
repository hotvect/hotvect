---
title: SageMaker custom.py payload contract
description: The small, source-backed contract Hotvect provides when a SageMaker job supplies s3_uri_custom_jar
tags: [design, sagemaker, script-mode, custom.py]
difficulty: advanced
related_docs:
  - ../../guides/sagemaker-upgrade-custom-py/index.md
  - ../../guides/sagemaker-backtests/index.md
  - ../sagemaker-configuration/index.md
---

# SageMaker `custom.py` payload contract

Use script mode only when a SageMaker job needs to run caller-owned code. It is a deliberately small hook, not a
second Hotvect pipeline runner.

!!! warning "Execution rule"
    Treat every action after `custom.py` starts as payload-owned. Hotvect does not automatically rebuild a pipeline,
    install a different runtime, download an algorithm JAR or definition, or publish results and metadata for the
    payload.

## Activation and payload shape

The SageMaker entrypoint selects script mode when the job hyperparameters contain `s3_uri_custom_jar`.

Despite its name, `s3_uri_custom_jar` must point to a **ZIP archive**. Hotvect downloads the archive, unpacks it, and
runs the root-level entrypoint:

```text
payload.zip
└── custom.py
```

There is no required `run_pipeline.py`, wheelhouse, requirements file, or result layout. Add any of those only when
your payload needs them.

## What Hotvect does

For a script-mode job, `SageMakerScriptExecutor` performs exactly these steps:

1. Downloads `s3_uri_custom_jar` from S3.
2. Unpacks it as a ZIP archive into a temporary directory.
3. Writes a temporary JSON file containing the job hyperparameters.
4. Adds two convenience fields to that JSON:
   - `custom_jar_path`: local path of the downloaded ZIP archive
   - `input_dir`: SageMaker's input directory
5. Executes `python <unpacked-dir>/custom.py <hyperparameters-json-path>`.

The JSON argument is the only interface passed to `custom.py`; parse it explicitly and fail fast when your own
required keys are absent.

## What the payload owns

If the payload needs any of the following, it must implement them itself:

- choosing or installing a Hotvect version and dependencies
- downloading an algorithm JAR, algorithm definition, model parameters, or additional inputs
- rebuilding and invoking a Hotvect pipeline
- writing or uploading `result.json`, metadata, logs, models, or any other artifacts
- defining its own archive contents, environment variables, and error reporting

The normal job hyperparameters may include paths such as `s3_uri_algorithm_jar`, `s3_uri_algorithm_definition`,
`s3_uri_result_file`, or `s3_uri_metadata`, but script mode does not give those paths special treatment. They are
ordinary values for `custom.py` to use or ignore.

## Optional local tracing

Hotvect can optionally start a local Jaeger process around `custom.py`. Set:

- `otel_trace_mode=local_jaeger`
- `s3_uri_jaeger_all_in_one`
- `s3_uri_otel_javaagent`
- `s3_uri_metadata`

It downloads the Jaeger binary and OpenTelemetry Java agent, injects the OTLP environment variables (plus optional
`otel_service_name` and `otel_trace_ratio`), and uploads `otel/jaeger-traces.tgz` under `s3_uri_metadata` after the
payload exits. This is the only built-in artifact upload in script mode.

## Choosing the right mode

Use regular SageMaker execution when the image-baked Hotvect runtime and normal pipeline flow are sufficient. Use
script mode only when the job must control its own bootstrap or execute a custom workload. For a payload-owned runtime
upgrade pattern, see [Upgrade Hotvect with `custom.py`](../../guides/sagemaker-upgrade-custom-py/index.md).
