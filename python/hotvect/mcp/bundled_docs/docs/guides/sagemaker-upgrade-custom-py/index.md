---
title: How to upgrade Hotvect on SageMaker with custom.py
description: Run a caller-owned custom.py payload in a Hotvect SageMaker training image. Script mode is a narrow dispatch contract, not a managed runtime-upgrade feature.
tags: [sagemaker, script-mode, custom.py, upgrade, hotvect, advanced]
difficulty: advanced
prerequisites:
  - A Hotvect SageMaker training image that includes python/bin/sagemaker-entrypoint
  - A tested payload ZIP and a SageMaker execution role that can read it from S3
related_docs:
  - ../sagemaker-backtests/index.md
  - ../../design/sagemaker-configuration/index.md
  - ../patterns/override-files/index.md
---

# How to: use `custom.py` on SageMaker

Script mode is an escape hatch for a caller-owned SageMaker payload. A runtime upgrade is one possible use, but
Hotvect does not provide a wheelhouse format, an installer, or a second pipeline runner for it.

!!! warning "Execution contract"
    Treat everything after `custom.py` starts as payload-owned. Do not assume that Hotvect downloads a wheelhouse,
    creates a virtual environment, installs a different Hotvect version, invokes the normal pipeline, or uploads
    your payload's output. Implement and test those steps in the payload that needs them.

## Exact framework contract

The image entrypoint, `python/bin/sagemaker-entrypoint`, selects its mode from SageMaker hyperparameters:

| Hyperparameters | What the entrypoint runs |
| --- | --- |
| `s3_uri_custom_jar` is present | `SageMakerScriptExecutor` (script mode) |
| No `s3_uri_custom_jar`, `hotvect_task` is present | The one-shot task handler |
| Neither is present | The normal `SagemakerAlgorithmPipelineRebuilder` flow |

`s3_uri_custom_jar` takes precedence if both keys are present. Its historical name is misleading: it must point to a
**ZIP archive**, not a JAR.

In script mode, Hotvect does exactly this:

1. downloads the S3 object named by `s3_uri_custom_jar`;
2. unpacks it as a ZIP into a temporary directory;
3. writes a JSON file containing the job hyperparameters, plus `custom_jar_path` and `input_dir`;
4. runs `python <temporary-directory>/custom.py <hyperparameters-json-path>`.

Therefore the ZIP must contain `custom.py` at its root. Any additional dependency files, environment variables,
launchers, or package layouts belong to that payload; Hotvect does not define them.

## Decide whether script mode is appropriate

Use regular SageMaker execution when the image already contains the runtime and dependencies you need. It is the
supported `hv backtest` / `hv train` path and Hotvect owns the pipeline lifecycle.

Use script mode only when the job needs caller-controlled behavior before or instead of that lifecycle, for example
installing a separately built runtime, running a custom UDF, or invoking a project-specific launcher. The payload is
then responsible for its dependencies, compatibility checks, output contract, and failure reporting.

There is no first-class `hv` option that builds or uploads a script payload. Supply the S3 ZIP URI in the submitted
training-job definition yourself.

## Configure a payload

Package the minimum valid archive with `custom.py` at its root:

```bash
mkdir payload
cp custom.py payload/custom.py
(cd payload && zip -r ../custom-payload.zip .)
aws s3 cp custom-payload.zip s3://<bucket>/<prefix>/custom-payload.zip
```

Add the URI as a job-definition fragment. With `hv backtest`, put this in an algorithm override JSON or in the
SageMaker template; Hotvect preserves the field while it adds the standard backtest hyperparameters.

```json
{
  "sagemaker_training_job_definition": {
    "HyperParameters": {
      "s3_uri_custom_jar": "s3://<bucket>/<prefix>/custom-payload.zip"
    }
  }
}
```

If `custom.py` needs environment variables—for example, a project-specific wheelhouse URI—put them in the SageMaker
job's `Environment` block and document them with the payload. Hotvect does not interpret upgrade-specific environment
variables for script mode.

## Implement a runtime-upgrade payload

An upgrade payload may download wheels, create a virtual environment, install a pinned Hotvect version, and launch
the desired code. Those are implementation choices, not a Hotvect protocol. Keep the payload's dependency manifest,
wheel source, and version validation together and test the exact archive against the target training image.

If the payload's goal is to run the ordinary Hotvect pipeline after installing a different runtime, it must invoke the
rebuilder from that runtime itself:

```python
from hotvect.sagemaker import SagemakerAlgorithmPipelineRebuilder

SagemakerAlgorithmPipelineRebuilder().rebuild_pipeline_and_run()
```

Do not invoke `python/bin/sagemaker-entrypoint` again: the unchanged `s3_uri_custom_jar` hyperparameter would select
script mode again.

The normal rebuilder requires `s3_uri_algorithm_jar` and `s3_uri_algorithm_definition`; the latter is required in v10,
not an optional preference or a legacy `_algo_def_*` fallback. A standard `hv backtest` submission also supplies the
result, metadata, pipeline-parameter, and pipeline-context hyperparameters that the rebuilt pipeline uses. If your
payload bypasses that rebuilder, it must define and implement its own result and metadata handling.

### Output tar files

`SAGEMAKER_TAR_INCLUDE_OUTPUT` and `SAGEMAKER_TAR_INCLUDE_METADATA` are read by the normal
`SagemakerAlgorithmPipelineRebuilder`, both defaulting to false. They control where that rebuilder writes its output
and metadata so SageMaker can include them in `output.tar.gz`; they do not automatically collect arbitrary
`custom.py` files or output. A payload that does not call the rebuilder owns its SageMaker output paths entirely.

## Verify and debug

After submission, inspect the training job:

```bash
aws sagemaker describe-training-job --training-job-name <job-name> \
  --query '{status:TrainingJobStatus,custom:HyperParameters.s3_uri_custom_jar,result:HyperParameters.s3_uri_result_file,metadata:HyperParameters.s3_uri_metadata}' \
  --output json
```

- A present `custom` value means the entrypoint selected script mode.
- Start with CloudWatch logs: payload failures happen before a normal pipeline run exists.
- If the payload calls the standard rebuilder, use `s3_uri_result_file` and `s3_uri_metadata` as normal. A URI can be
  present even when the payload failed before writing the object.
- If the payload does not call the rebuilder, use the output and logging contract implemented by that payload.

Common failures are a ZIP with `custom.py` below a top-level directory, an unreadable S3 object, or a payload whose
runtime/dependencies do not match the selected training image.

Related: [Run backtests on AWS SageMaker](../sagemaker-backtests/index.md) for the containing workflow and the
[custom.py payload contract](../../design/script-mode-standardization/index.md) for the rebuild boundary. For
distributed record transformation rather than runtime replacement, use the separate
[FlatMap UDF API](../sagemaker-udf/index.md).
