---
title: How to Run User Defined Functions on SageMaker
description: Execute custom flatMap functions over large datasets using hotvect's UDF interface
tags: [udf, sagemaker, flatmap, data-processing, advanced]
difficulty: advanced
estimated_time: 30 minutes
prerequisites:
  - UDF JAR implementing FlatMapFunFactory
  - hotvect-offline-util JAR available
  - Test data available locally
  - SageMaker access (for remote execution)
related_docs:
  - ../develop-algorithms/index.md
  - ../../reference/cli/index.md
related_commands:
  - java -cp ... FlatMapFile
next_steps:
  - Deploy UDF to SageMaker for large-scale processing
  - Create reusable UDF library
  - Integrate UDF results into training pipeline
---

# How to: Run User Defined Functions (UDF) on SageMaker

## What can you do with it?

Sometimes it's useful to run an arbitrary function over your data. For example, you might want to:

- compute debugging statistics
- calculate alternative rewards for offline evaluation
- generate derived datasets that reuse the same domain code as your algorithms

You can always do this with Spark, but if your logic is already in JVM code (and depends on the same domain artifacts as your algorithms), running it with Hotvect can be convenient.

## Prerequisite knowledge

Running a function on top of a list of records is called `map`: each input line produces one output line. If you want to skip lines or emit multiple output lines per input line, you need `flatMap`.

Hotvect provides a `flatmap` runner that reads input line-by-line (files in lexicographic order), applies your function, and writes JSONL output line-by-line.

At a high level, your UDF has the shape:

- `Function<String, List<String>>` (Java), i.e. `input_line -> [output_line, ...]`

## How to develop your flatmap function

To run a flatMap function using Hotvect, implement:

- `com.hotvect.offlineutils.commandline.util.flatmap.FlatMapFunFactory`

The factory returns a `Function<String, List<String>>`. The factory receives an `Optional<JsonNode> hyperparameter`, which you can use to configure your UDF at runtime.

## How to run your flatmap function

### Running it locally

Example:

```bash
java -cp ~/.m2/repository/com/hotvect/hotvect-offline-util/x.y.z/hotvect-offline-util-x.y.z-jar-with-dependencies.jar \
  com.hotvect.offlineutils.commandline.util.flatmap.FlatMapFile \
  --jars ./target/my-custom-udf-1.2.3.jar \
  --flatmap-class org.myorg.rewards.MultiRewardsFactory \
  --source ~/somedata/dt=2024-02-02 \
  --dest test-output.jsonl \
  --metadata-path test-output.metadata
```

Apart from the output, it will also write run metadata to `--metadata-path/metadata.json` (by default, `metadata/metadata.json`).


### Running it on SageMaker

Hotvect does not currently expose a dedicated `hv` subcommand for “UDF on SageMaker”. The supported approach is **script-mode** (a `custom.py` payload executed inside the training container), and job submission via an **experimental** helper.

See [How to Upgrade Hotvect on SageMaker (custom.py)](../sagemaker-upgrade-custom-py/index.md) for the script-mode contract (`HyperParameters.s3_uri_custom_jar` and `custom.py` at the archive root).

#### Experimental job submission helper

`hotvect.sagemaker_exp.run_remote_using_git_reference` can submit one-shot SageMaker training jobs from a git reference.

At a high level it:

1. clones and builds the repo at `git_reference`
2. uploads the built artifact to `remote_work_dir`
3. creates SageMaker training jobs with `target_dt` injected into hyperparameters

Your built artifact must be a zip/jar that contains a `custom.py` at the archive root, and that `custom.py` is responsible for invoking your UDF (for example by calling `FlatMapFile` with your UDF JAR on the classpath).

```python
from datetime import date

from hotvect.sagemaker_exp import run_remote_using_git_reference

def main():
    sagemaker_training_job_definition = {
        "TrainingJobName": "my-flatmap-job",
        "AlgorithmSpecification": {
            "TrainingInputMode": "FastFile",
            "TrainingImage": "some_training_image",
        },
        "InputDataConfig": [
            {
                "ChannelName": "input-data",
                "DataSource": {
                    "S3DataSource": {
                        "S3DataType": "S3Prefix",
                        "S3Uri": "s3://example-bucket/udf-input/",
                    }
                },
                "InputMode": "FastFile",
            },
        ],
        "OutputDataConfig": {"S3OutputPath": "s3://example-bucket/udf-output/"},
        "ResourceConfig": {
            "InstanceType": "ml.m5.2xlarge",
            "VolumeSizeInGB": 30,
            "InstanceCount": 1,
        },
        "EnableManagedSpotTraining": True,
        "StoppingCondition": {
            "MaxRuntimeInSeconds": 123,
            "MaxWaitTimeInSeconds": 123,
        },
        "RoleArn": "arn:aws:iam::123456789012:role/example-role",
        "HyperParameters": {},
    }

    run_remote_using_git_reference(
        remote_work_dir="s3://example-bucket/udf-remote-work-dir/",
        local_work_dir="/path/to/my_local_work_dir",
        repo_url="https://github.com/example-org/example-udf-repo.git",
        git_reference="deadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
        sagemaker_training_job_definition=sagemaker_training_job_definition,
        last_target_time=date(2024, 1, 1),
        number_of_runs=1,
        hyperparameters={"some_parameter": "123"},
    )
```

!!! warning "Experimental API"
    `hotvect.sagemaker_exp` is intentionally marked experimental and may change. Prefer `hv backtest` / `hv train` for supported SageMaker workflows.
