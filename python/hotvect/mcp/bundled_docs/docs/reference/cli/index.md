---
title: Using the Hotvect Command-Line Interface
description: Complete reference for hv, hv-ext, and hv-exp CLI commands
tags: [cli, reference, commands, usage, tools]
difficulty: beginner
estimated_time: 45 minutes (reference)
prerequisites:
  - hotvect Python package installed
  - Algorithm JAR available (for most commands)
  - Basic understanding of hotvect concepts
related_docs:
  - ../../guides/feature-audits/index.md
  - ../../guides/debug-feature-engineering/index.md
  - ../../concepts/index.md
related_commands:
  - hv audit
  - hv train
  - hv predict
  - hv backtest
  - hv-ext compare-jsonl
next_steps:
  - Run your first audit
  - Train a model
  - Compare algorithm versions
---

# Using the Hotvect Command-Line Interface

The Hotvect Command-Line Interface (CLI) tool provides a convenient way to perform various operations such as encoding, predicting, auditing, comparing JSONL files, and evaluating predictions. This guide will walk you through how to use the CLI tool and its available subcommands.

## Overview

The CLI tool, named `hv`, is designed to interact with your algorithms and data using a simple and consistent command-line interface. It supports the following operations:

- **audit**: Generate human-readable audit data showing feature transformations and calculations.
- **performance-test**: Benchmark algorithm performance and measure throughput/latency.
- **encode**: Transform input data into ML-ready format for training.
- **predict**: Generate model predictions on test/validation data.
- **generate-state**: Generate state files for algorithms that require state generation.
- **list-transformations**: Display all feature transformations defined in the algorithm.
- **evaluate**: Calculate performance metrics from model predictions.
- **train**: Train a machine learning model using the hotvect pipeline.
- **backtest**: Run backtest on git references to compare algorithm performance.
- **serve**: Serve the full algorithm over HTTP for local debugging. Add `--ui` to enable the browser debugger on the same server.
- **worker**: Worker-runtime HTTP debugging utilities for LitServe-backed worker endpoints.

!!! note "SageMaker support (quick summary)"
    Hotvect supports running on SageMaker in two ways:

    - **Pipelines:** `hv train` and `hv backtest` can execute on SageMaker (submit jobs and return immediately).
    - **One-shot Java commands:** `hv audit`, `hv predict`, `hv encode`, and `hv performance-test` can also execute on SageMaker via `--sagemaker`.

    For flags and required S3 inputs, see [SageMaker one-shot mode](#sagemaker-one-shot-mode-auditpredictencodeperformance-test).

## Commands for Training Algorithms

Many Hotvect algorithms are composites where an **outer algorithm** orchestrates one or more **inner algorithms**. Some low-level commands require an algorithm that trains (has `training_command` in its definition), while high-level commands work on any algorithm. Use the following guidance:

| Command | Works on | Notes |
|---------|----------|-------|
| `audit` | Algorithms that train | Targets algorithms with feature transformations (`vectorizer_factory_classname` or `transformer_factory_classname`). Commonly the inner/dependency algorithms. |
| `encode` | Algorithms that train | Encoding requires the algorithm that has `encoder_factory_classname`. Commonly the inner algorithms that train models. |
| `generate-state` | Algorithms with state generation | Targets algorithms with `generator_factory_classname`. Can be at parent or child level depending on algorithm design. |
| `train`, `predict`, `performance-test`, `backtest` | Any algorithm | Can point at outer or inner algorithms. Outer algorithms automatically cascade training through dependencies. |

To discover which algorithms train, inspect the algorithm definition JSON (embedded in algorithm JARs or in source at `src/main/resources/*-algorithm-definition.json`). Look for:
- `training_command` - Definitive indicator the algorithm trains
- `vectorizer_factory_classname` or `transformer_factory_classname` - For feature transformations (audit)
- `encoder_factory_classname` - For encoding training data
- `generator_factory_classname` - For state generation

See the [Parent-Child Algorithm Pattern](../../guides/patterns/parent-child/index.md) for more details on composite algorithm architecture.

## Usage

The general syntax for using the `hv` tool is:

```bash
hv <command> [options]
```

To see the list of available commands, run:

```bash
hv -h
```

To print version information, run:

```bash
hv --version
```

To get help on a specific command, use:

```bash
hv <command> -h
```

Unknown flags are rejected. Commands that support raw JVM passthrough accept it only after an explicit `--` separator:

```bash
hv audit ... -- -Xmx8g -Dfoo=bar
```

Passthrough is supported for the Java wrapper commands (`audit`, `encode`, `predict`, `generate-state`, `list-transformations`, `performance-test`) and for `serve`. `train` and `backtest` use `--extra-jvm-args` instead, and commands like `worker serve` reject passthrough args after `--`.

## Subcommands and Options

Below is a detailed explanation of each subcommand and its options.

### 1. `audit`

**Description**: Generate human-readable audit data showing feature transformations and calculations. Perform feature transformation on input data and save results as human-readable JSONL format. This is useful for debugging algorithms, inspecting calculated features, and understanding how input data is transformed by the feature engineering pipeline.

**SageMaker**: Supported via one-shot mode (`--sagemaker`). See [SageMaker one-shot mode](#sagemaker-one-shot-mode-auditpredictencodeperformance-test).

**Usage**:

```bash
hv audit --algorithm-jar <path_to_jar> --algorithm-name <algorithm_name> [options]
```

**Options**: Same as the common Java command options (see Common Options section below).

**Example**:

```bash
hv audit --algorithm-jar my_algorithm.jar --algorithm-name product-ranker --source-path input_data.jsonl --dest-path audit_output.jsonl
```

### 2. `performance-test`

**Description**: Benchmark algorithm performance and measure latency percentiles under a controlled request rate.

**Workload mode**: `hv performance-test` defaults to **realtime** workload mode, even though the input rows are offline. This is intentional: performance-test is generally used to measure serving latency and should normally exercise the algorithm's `realtime` runtime config. Use `--workload-mode batch` only when you explicitly want to benchmark the batch path. `hv predict` continues to use batch workload mode.

**Important**: `hv performance-test` runs a warmup first, then (by default) paces the measurement runs at `0.8 × warmup_mean_throughput` to reduce queueing effects and make p99/p999 more stable across runs. This means the reported throughput is **not** “max throughput” by default.

**Threading default**: if `--max-threads` is omitted, `hv performance-test` defaults to:

- `2` threads on machines with `>=4` physical cores
- otherwise `1`

Pass an explicit `--max-threads` to override that heuristic, or `--max-threads 0` to avoid passing the flag to Java and let the JAR decide.

**SageMaker**: Supported via one-shot mode (`--sagemaker`). See [SageMaker one-shot mode](#sagemaker-one-shot-mode-auditpredictencodeperformance-test).

**Usage**:

```bash
hv performance-test --algorithm-jar <path_to_jar> --algorithm-name <algorithm_name> [options]
```

**Options**: Same as the common Java command options (see Common Options section below).

**Performance-test-only options**:
- `--target-rps`: Fixed target requests/sec (best for comparing versions under identical load). Overrides `--target-throughput-fraction`.
- `--target-throughput-fraction`: Fraction of warmup mean throughput to use as the target requests/sec when `--target-rps` is not set. Default: `0.8`. Set to `0` to disable pacing.
- `--workload-mode {realtime,batch}`: Select which algorithm workload mode to benchmark. Default: `realtime`.

**Benchmarking methodology**: for reliable A/B latency claims, keep runtime and hardware fixed, pin both `--target-rps` and `--samples`, repeat independent jobs, and use statistical tests before calling a `p99`/`p999` regression. See [Reliable Performance Benchmarking](../../guides/performance-benchmarking/index.md).

### 3. `encode`

**Description**: Transform input data into ML-ready format for training. Perform feature transformation on input data and encode it in the binary format expected by the machine learning library (e.g., CatBoost). This step is required before training and produces the encoded dataset used for model training.

**SageMaker**: Supported via one-shot mode (`--sagemaker`). See [SageMaker one-shot mode](#sagemaker-one-shot-mode-auditpredictencodeperformance-test).

**Output**:
- `--dest-path` is a **directory** (not a file).
- Encoded outputs are written as shard files inside that directory: `shard_0<ext>`, `shard_1<ext>`, ...
- `<ext>` is determined by the encoder (e.g., `.tfrecord`, `.tsv`, `.jsonl`).

**Usage**:

```bash
hv encode --algorithm-jar <path_to_jar> --algorithm-name <algorithm_name> [options]
```

**Options**: Same as the common Java command options, with `--dest-schema-path` being particularly relevant for encoding operations.

### 4. `predict`

**Description**: Generate model predictions on test/validation data. Use a trained model to generate predictions on input data. Requires a parameter file from a previous training run. Output includes prediction scores and can be used for evaluation or serving.

**SageMaker**: Supported via one-shot mode (`--sagemaker`). See [SageMaker one-shot mode](#sagemaker-one-shot-mode-auditpredictencodeperformance-test).

**Usage**:

```bash
hv predict --algorithm-jar <path_to_jar> --algorithm-name <algorithm_name> [options]
```

**Options**: Same as the common Java command options (see Common Options section below).

**Additional Options**:
- `--log-features`: Enable feature logging during prediction for debugging composite algorithms (optional; v10+).

### 5. `list-transformations`

**Description**: Display all feature transformations defined in the algorithm. List all feature transformations, encoders, and data processing steps available in the specified algorithm. Use --verbose for detailed information about each transformation including parameters and data types.

**Usage**:

```bash
hv list-transformations --algorithm-jar <path_to_jar> --algorithm-name <algorithm_name> [options]
```

**Options**:

- `--algorithm-jar`: **(required)** Path to the JAR file containing the algorithm implementation.
- `--algorithm-name`: **(required)** Name of the algorithm to execute (e.g., `product-ranker`).
- `--metadata-path`: Directory where operation artifacts are written (optional, auto-generated if not specified). Files include `metadata.json`, `hv.log`, `hotvect-offline-utils.log`, and `stdout-stderr.log`.
- `--verbose`: Show detailed information about each transformation including parameters and data types (optional).

### 6. `evaluate`

**Description**: Calculate performance metrics from model predictions. Compute evaluation metrics (accuracy, precision, recall, AUC, etc.) from prediction results. Can perform both online/offline analysis to compare model performance across different scenarios and data splits.

**Usage**:

```bash
hv evaluate --source-path <predictions_file> --dest-path <evaluation_output> [options]
```

**Options**:

- `--source-path`: **(required)** Path to the prediction results file (JSONL format) to evaluate.
- `--dest-path`: **(required)** Path where the evaluation metrics will be saved as JSON.
- `--enable-online-offline-analysis`: Enable detailed online vs offline performance analysis (optional).

**Example**:

```bash
hv evaluate --source-path predictions.jsonl --dest-path evaluation_results.json --enable-online-offline-analysis
```

### 7. `train`

**Description**: Train a machine learning model using the hotvect pipeline. Orchestrates the complete ML training pipeline including data encoding, model training, and output generation using AlgorithmPipeline.

**Usage**:

```bash
hv train --algorithm-name <algorithm_name> --data-base-dir <data_dir> --output-base-dir <output_dir> --algorithm-jar <jar_path> --last-test-time <date> [options]
```

**Required Options**:

- `--algorithm-name`: Name of the algorithm to train.
- `--data-base-dir`: Base directory containing training data (**required for local execution**).
- `--output-base-dir`: Base directory where training outputs will be saved.
- `--algorithm-jar`: Path to the JAR file containing the algorithm implementation.
- `--last-test-time`: Last test time in YYYY-MM-DD format (e.g., "2025-04-30").

**Optional Options**:

- `--algorithm-override`: Path to JSON file containing algorithm configuration overrides (merge overlay; missing keys are not deleted from the base definition).
- `--extra-jvm-args`: Additional JVM arguments for training, comma-separated (e.g., "-XX:MaxRAMPercentage=80,-XX:+UseG1GC").
- `--max-threads`: Max threads for hotvect encode/predict (0 = don't pass; JAR decides). Standalone `hv performance-test` uses a different default when omitted; see [Common Options](#common-options).
- `--cache`: Enable Hotvect pipeline caching (local path or `s3://example-bucket`).
- `--cache-scope`: Cache key scope across algorithm versions (`major|minor|patch|hyperparam`, default: `hyperparam`).
- `--cache-refresh`: Ignore cache reads (force recompute) while still writing results to cache. Requires `--cache`.
- `--sagemaker`: Execute training remotely on SageMaker (submits job and returns immediately).
- `--sagemaker-job-prefix`: **(required for SageMaker)** Valid SageMaker TrainingJobName prefix (no sanitization; invalid values error).
- `--sagemaker-config`: Path to SageMaker training job definition JSON (optional; otherwise loaded from `~/.hotvect/config.json` when `--sagemaker` is set).
- `--role-arn`: SageMaker job execution role ARN (CreateTrainingJob.RoleArn) (required for template-free mode).
- `--assume-role-arn`: AWS role ARN to assume for SageMaker submission (optional; defaults to using current AWS credentials).
- `--s3-output-base`: S3 base prefix used in template-free SageMaker mode (`OutputDataConfig.S3OutputPath`).
- `--instance-type`: Instance type used in template-free SageMaker mode (`ResourceConfig.InstanceType`).
- `--volume-gb`: Override EBS volume size in GB (`ResourceConfig.VolumeSizeInGB`, default 30 if missing).
- `--max-runtime-seconds`: Override max runtime seconds (`StoppingCondition.MaxRuntimeInSeconds`, default 86400 if missing).
- `--training-image`: Training image override (`AlgorithmSpecification.TrainingImage`; default from algorithm definition `training_container`).
- `--auto-attach-data`: Auto-populate `InputDataConfig` channels from algorithm dependencies (SageMaker mode).
- `--auto-attach-data-default-s3-base`: Default S3 base prefix used when dependencies do not declare explicit `s3_uri`.
- `--auto-attach-data-environment`: Preferred environment key when dependency `s3_uri` is a map (default: `production`). Keys are matched case-insensitively with fallbacks (`production`, `prod`, `test`, `staging`, then first map entry).
- `--performance-test-samples`: Pin pipeline perf-test sample size for comparability (passes `--samples` to Java perf-test).

**Example**:

```bash
hv train --algorithm-name product-reranking-algorithm \
         --data-base-dir /path/to/training/data \
         --output-base-dir /path/to/output \
         --algorithm-jar /path/to/algorithm.jar \
         --last-test-time 2025-04-30 \
         --algorithm-override /path/to/override.json \
         --extra-jvm-args "-XX:MaxRAMPercentage=80,-XX:+UseG1GC"
```

**SageMaker Example** (submits and returns):

```bash
hv train --algorithm-name product-reranking-algorithm \
         --algorithm-jar /path/to/algorithm.jar \
         --last-test-time 2025-04-30 \
         --sagemaker \
         --sagemaker-job-prefix exp-rerank \
         --auto-attach-data \
         --auto-attach-data-default-s3-base s3://example-bucket/tables/ \
         --performance-test-samples 200000
```

**Algorithm Override File Example**:

```json
{
    "hyperparameter_version": "1day",
    "hotvect_execution_parameters": {},
    "dependencies": {
        "user-preference-model": {
            "number_of_training_days": 1
        }
    }
}
```

### 8. `backtest`

**Description**: Run backtest on git references to compare algorithm performance across different versions or configurations. For SageMaker execution, returns immediately after job submission without waiting for completion.

**Usage**:

```bash
hv backtest (--git-reference <git_ref> | --backtest-config <config_file>) --algo-repo-url <repo_url> --data-base-dir <data_dir> --output-base-dir <output_dir> --scratch-dir <scratch_dir> --last-test-time <date> [options]
```

**Required Options**:

- `--git-reference`: Git reference (branch/commit) to test. Can be specified multiple times for multiple references.
- `--backtest-config`: Alternative to `--git-reference`, path to JSON file containing list of git references and their overrides.
- `--algo-repo-url`: Git repository URL for the algorithm.
- `--data-base-dir`: Base directory containing training and test data (required for local execution, optional for SageMaker - data comes from S3 channels in SageMaker config).
- `--output-base-dir`: Base directory where backtest results will be saved.
- `--scratch-dir`: Directory for temporary files like JARs and working data during backtest execution.
- `--last-test-time`: Last test time in YYYY-MM-DD format (e.g., "2025-08-05").

**Optional Options**:

- `--algorithm-override`: Path to JSON file containing algorithm configuration overrides (repeatable). If one override is provided, it applies to all git references. If multiple are provided, they apply to git references in order (merge overlay; missing keys are not deleted from the base definition).
- `--number-of-runs`: Number of backtest runs to execute (default: 1).
- `--extra-jvm-args`: Additional JVM arguments, comma-separated (e.g., "-XX:MaxRAMPercentage=80,-XX:+UseG1GC").
- `--sagemaker`: Execute backtest remotely on SageMaker (submits jobs and returns immediately).
- `--sagemaker-job-prefix`: **(required for SageMaker)** Valid SageMaker TrainingJobName prefix (no sanitization; invalid values error).
- `--sagemaker-config`: Path to JSON file containing SageMaker training job configuration (optional; otherwise loaded from `~/.hotvect/config.json` when `--sagemaker` is set).
- `--role-arn`: SageMaker job execution role ARN (CreateTrainingJob.RoleArn) (required for template-free mode).
- `--assume-role-arn`: AWS role ARN to assume for SageMaker submission (optional; defaults to using current AWS credentials).
- `--s3-output-base`: S3 base prefix used in template-free SageMaker mode (`OutputDataConfig.S3OutputPath`).
- `--instance-type`: Instance type used in template-free SageMaker mode (`ResourceConfig.InstanceType`).
- `--volume-gb`: Override EBS volume size in GB (`ResourceConfig.VolumeSizeInGB`, default 30 if missing).
- `--max-runtime-seconds`: Override max runtime seconds (`StoppingCondition.MaxRuntimeInSeconds`, default 86400 if missing).
- `--training-image`: Training image override (`AlgorithmSpecification.TrainingImage`). If not set, backtests typically use the algorithm definition `training_container` per git ref unless the base SageMaker config already pins `TrainingImage` (script-mode).
- `--n-process`: Number of parallel processes for local execution (default: 1).
- `--max-threads-per-process`: Maximum threads per process for local execution (default: auto-calculated).
- `--clean`: Clean output directories before starting backtest.
- `--no-performance-test`: Disable system performance testing.
- `--cache`: Enable Hotvect pipeline caching (local path or `s3://example-bucket`). Use `s3://example-bucket` for SageMaker runs.
- `--cache-scope`: Cache key scope across algorithm versions (`major|minor|patch|hyperparam`, default: `hyperparam`).
- `--cache-refresh`: Ignore cache reads (force recompute) while still writing results to cache. Requires `--cache`.
- `--performance-test-samples`: Pin pipeline perf-test sample size for comparability (passes `--samples` to Java perf-test).
- `--auto-attach-data-default-s3-base`: Default S3 base URI used when dependencies do not specify explicit `s3_uri` (auto-attach is always enabled in SageMaker mode).
- `--auto-attach-data-environment`: Preferred environment key when dependency `s3_uri` is a map (default: `production`). Keys are matched case-insensitively with fallbacks (`production`, `prod`, `test`, `staging`, then first map entry).

**Examples**:

**Basic Local Backtest**:
```bash
hv backtest --git-reference main --git-reference feature-branch \
           --algo-repo-url https://github.com/example-org/example-algorithm.git \
           --data-base-dir /path/to/data \
           --output-base-dir /path/to/output \
           --scratch-dir /tmp/backtest \
           --last-test-time 2025-08-05 \
           --n-process 4
```

**SageMaker Backtest**:
```bash
hv backtest --git-reference main \
           --algo-repo-url https://github.com/example-org/example-algorithm.git \
           --data-base-dir /path/to/data \
           --output-base-dir /path/to/output \
           --scratch-dir /tmp/backtest \
           --last-test-time 2025-08-05 \
           --sagemaker-config sagemaker-config.json \
           --role-arn arn:aws:iam::123456789012:role/example-role
```

**Backtest with S3 Cache (recommended for SageMaker)**:
```bash
hv backtest --git-reference main \
           --algo-repo-url https://github.com/example-org/example-algorithm.git \
           --output-base-dir /path/to/output \
           --scratch-dir /tmp/backtest \
           --last-test-time 2025-08-05 \
           --sagemaker-config sagemaker-config.json \
           --auto-attach-data-default-s3-base s3://example-bucket/tables/ \
           --cache s3://example-bucket/hotvect-cache/ \
           --cache-scope hyperparam
```

**Backtest with Local Cache (local execution only)**:
```bash
hv backtest --git-reference main \
           --algo-repo-url https://github.com/example-org/example-algorithm.git \
           --data-base-dir /path/to/data \
           --output-base-dir /path/to/output \
           --scratch-dir /tmp/backtest \
           --last-test-time 2025-08-05 \
           --cache /tmp/hv-cache \
           --cache-scope hyperparam
```

**Config File Approach**:
```bash
hv backtest --backtest-config backtest-refs.json \
           --algo-repo-url https://github.com/example-org/example-algorithm.git \
           --data-base-dir /path/to/data \
           --output-base-dir /path/to/output \
           --scratch-dir /tmp/backtest \
           --last-test-time 2025-08-05
```

**Backtest Configuration File Example** (`backtest-refs.json`):
```json
[
    {
        "git_reference": "main",
        "algorithm_definition_override": null
    },
    {
        "git_reference": "feature-branch",
        "algorithm_definition_override": {
            "hyperparameter_version": "experimental",
            "dependencies": {
                "user-model": {
                    "learning_rate": 0.01
                }
            }
        }
    }
]
```

**SageMaker Configuration File Example** (`sagemaker-config.json`):
```json
{
    "TrainingJobName": "algorithm-backtest",
    "AlgorithmSpecification": {
        "TrainingInputMode": "FastFile",
        "TrainingImage": "123456789012.dkr.ecr.region.amazonaws.com/algorithm:latest"
    },
    "InputDataConfig": [
        {
            "ChannelName": "training",
            "DataSource": {
                "S3DataSource": {
                    "S3DataType": "S3Prefix",
                    "S3Uri": "s3://example-bucket/training-data/"
                }
            },
            "InputMode": "FastFile"
        }
    ],
    "OutputDataConfig": {
        "S3OutputPath": "s3://example-bucket/backtest-output/"
    },
    "ResourceConfig": {
        "InstanceType": "ml.m5.2xlarge",
        "InstanceCount": 1,
        "VolumeSizeInGB": 30
    },
    "RoleArn": "arn:aws:iam::123456789012:role/example-role",
    "StoppingCondition": {
        "MaxRuntimeInSeconds": 86400
    }
}
```

### 9. `generate-state`

**Description**: Generate state files required by algorithms that use state generation (must have `generator_factory_classname` in the algorithm definition).

**Usage**:

```bash
hv generate-state --algorithm-jar <path_to_jar> --algorithm-name <algorithm_name> --source-path <state_input_json> [options]
```

**Options**: Same as the common Java command options (see Common Options section below).

**Example**:

```bash
hv generate-state --algorithm-jar my_algorithm.jar --algorithm-name product-ranker --source-path '{"training_data":["file1","file2"]}' --dest-path state.output
```

### 10. `serve`

**Description**: Serve the **full algorithm** over HTTP for local debugging. This runs the Java algorithm runtime, so request decoding, feature extraction, algorithm wiring, and output formatting all happen in the JVM exactly like the real algorithm runtime. Add `--ui` to expose the browser debugger on the same server.

**Usage**:

```bash
hv serve --algorithm-jar <path_to_jar> --algorithm-name <algorithm_name> --parameter-path <params_zip> --port <port> [options]
```

**Required Options**:
- `--algorithm-jar`: Path to the algorithm JAR.
- `--algorithm-name`: Algorithm name (matches algorithm definition).
- `--parameter-path`: Path to parameters ZIP from training.
- `--port`: Port to bind to (required; must be non-zero).

**Optional Options**:
- `--host`: Host/interface to bind to (default: `127.0.0.1`).
- `--algorithm-override`: Path to a JSON override merged on top of the algorithm definition before serving.
- `--ui`: Enable the browser UI routes and static assets.
- `--source-path`: Directory containing offline examples. Required with `--ui`.
- `--action-metadata-path`: Directory containing action metadata keyed by `action_id` (UI only).
- `--demo-sqlite-path`: SQLite cache path for UI mode.
- `--max-request-mib`: Maximum accepted request size in MiB (default: `256`).

**Runtime defaults**:
- `hv serve` injects `-XX:MaxRAMPercentage=90` when you do not pass an explicit heap cap (`-Xmx...` or `-XX:MaxRAMPercentage=...`).
- `hv serve` also injects `-XX:+ExitOnOutOfMemoryError` unless that exact flag is already present.
- Extra JVM args must be passed after an explicit `--` separator (for example `hv serve ... -- -Xmx4g`). Use an explicit heap flag when you want to take control of heap sizing.

**Endpoints**:
- `GET /health`
- `POST /predict`

With `--ui`, the same process also exposes the interactive UI routes.

### 11. `worker serve`

**Description**: Start the **worker runtime only** over HTTP using LitServe. This bypasses JVM feature extraction and expects **worker-ready feature rows** matching the encoded schema.

**Usage**:

```bash
hv worker serve --algorithm-jar <path_to_jar> --algorithm-name <model_algorithm_name> [options]
```

**Required Options**:
- `--algorithm-jar`: Path to the algorithm JAR.
- `--algorithm-name`: Model algorithm name (matches algorithm definition).
- `--parameter-path`: Path to parameters ZIP from training.

**Optional Options**:
- `--port`: Port to bind to (default: `8000`).
- `--host`: Host/interface to bind to (default: `127.0.0.1`).
- `--algorithm-override`: Path to a JSON override merged on top of the algorithm definition before serving.
- `--scope`: Which LitServe scope to use. `auto` prefers `realtime`, then `batch`.
- `--debug-include-tf-inputs`: Include normalized TensorFlow inputs in the infer response under `debug` for **local TensorFlow** LitServe startup.
- `--keep-temp-dir`: Keep the extracted model/schema temp directory for **local** startup.

**Backend selection**:
- `hv worker serve` reads the backend from `algorithm_parameters.backend`.
- Supported today: `tensorflow`, `torch`.
- Older worker configs that still use `algorithm_parameters.workers.backend` must be updated to `algorithm_parameters.backend` before running `hv worker serve`.
- If you want to swap backend locally, use `--algorithm-override`; there is no user-facing CLI `--backend` flag.

**Scope selection**:
- `hv worker serve` prefers the selected scope's `litserve` block.
- If `litserve` is absent but `direct_workers` is present, `hv worker serve` falls back to `direct_workers` for overlapping runtime knobs.
- `--scope auto` prefers `realtime`, then `batch`.

**Interpreter resolution**:
- `hv worker serve` starts the local LitServe worker with the first available interpreter from `HOTVECT_PYTHON_EXECUTABLE`, then the selected scope's `python_executable`, then the current `sys.executable`.
- If your worker dependencies live in a different environment than the `hv` process, set `HOTVECT_PYTHON_EXECUTABLE` explicitly before starting the server.

**Startup behavior**:
- Local startup reads `startup_timeout_ms` from the selected scope config. Default: `30000` ms.
- Request handling timeout comes from `request_timeout_ms`. Default: `30000` ms.
- Increase `startup_timeout_ms` when model extraction or worker initialization is slow, and prefer passing `--scope` explicitly when you are debugging a specific runtime config.

**Endpoints**:
- `GET /health`
- `POST /predict`
- `GET /v2`
- `GET /v2/health/live`
- `GET /v2/health/ready`
- `GET /v2/models/<model>`
- `GET /v2/models/<model>/ready`
- `POST /v2/models/<model>/infer`

**Request shape**:

```json
{
  "batch": [
    {
      "feature_a": 1.0,
      "feature_b": [1, 2, 3]
    }
  ]
}
```

The native LitServe `POST /predict` response is:

```json
{
  "scores": [0.123]
}
```

When `--debug-include-tf-inputs` is enabled for TensorFlow backends, the response also includes `debug`.

The compatibility `POST /v2/models/<model>/infer` response uses the existing V2 tensor envelope.

For deeper background on worker config layout and local debugging behavior, see [Direct Python Workers](../../design/direct-python-workers/index.md). For common startup failures, see the [Troubleshooting Guide](../troubleshooting/index.md#local-serve-errors).

## Common Options

Most Java-based commands (audit, performance-test, encode, predict, list-transformations) share common options:

- `--algorithm-jar`: **(required)** Path to the JAR file containing the algorithm implementation.
- `--algorithm-name`: **(required)** Name of the algorithm to execute (e.g., `product-ranker`).
- `--algorithm-override`: Path to a JSON file with algorithm configuration overrides. When set, `hv` writes a complete effective definition JSON to `--metadata-path/effective_algorithm_definition.json` and passes that file to Java. Overrides are applied as a merge overlay (missing keys are not deleted from the base definition).
- `--metadata-path`: Directory where operation artifacts are written (optional, auto-generated if not specified). Files include `metadata.json`, `hv.log`, `hotvect-offline-utils.log`, and `stdout-stderr.log`.
- `--source-path`: Path to the input data source for the operation (optional for some commands).
- `--dest-path`: Destination path for the operation output (optional, auto-generated if not specified). For `encode`, this is a **directory** containing `shard_*<ext>` files; for `audit`/`predict` it is typically a single output file.
- `--parameter-path`: Path to the trained model parameter file (required for predict/audit/serve/worker serve, optional for others).
- `--dest-schema-path`: Path where the feature schema description will be saved (optional, used in encoding operations).
- `--samples`: Number of samples to process, useful for testing with smaller datasets (optional).
- `--max-threads`: Max worker threads for Hotvect Java execution. For `hv performance-test`, if omitted, `hv` defaults to `2` threads on machines with `>=4` physical cores (else `1`). Pass an explicit value to override, or `0` to avoid passing `--max-threads` and let the JAR decide.
- `--target-rps`: Performance-test only. Fixed target requests/sec (optional).
- `--target-throughput-fraction`: Performance-test only. Fraction of warmup mean throughput to use as target requests/sec (optional; default `0.8`, `0` disables pacing).
- JVM passthrough: for Java wrapper commands, extra JVM args must follow an explicit `--` separator. Example: `hv predict ... -- -Xmx8g -Dfoo=bar`.

**Note**: Generally, if the command transforms data, the actual transformed output is stored in the `--dest-path`, while metadata—such as timing information, algorithm version, and other operation details—is stored in `--metadata-path/metadata.json`. For debugging, `--metadata-path/hv.log` contains Python CLI logs, `--metadata-path/hotvect-offline-utils.log` contains Java logs, and `--metadata-path/stdout-stderr.log` contains raw subprocess stdout/stderr.

### SageMaker one-shot mode (audit/predict/encode/performance-test)

The following flags enable running these Java-based commands remotely on SageMaker (job is submitted and the CLI returns immediately):

- `--sagemaker`: Enable SageMaker execution for `audit`, `predict`, `encode`, `performance-test`.
- `--sagemaker-job-prefix`: **(required)** Valid SageMaker TrainingJobName prefix (no sanitization; invalid values error).
- `--sagemaker-config`: Path to SageMaker job definition template JSON (optional; otherwise loaded from `~/.hotvect/config.json` when `--sagemaker` is set).
- `--role-arn`: SageMaker job execution role ARN (CreateTrainingJob.RoleArn) (required for template-free mode).
- `--assume-role-arn`: AWS role ARN to assume for SageMaker submission (optional; defaults to using current AWS credentials).
- `--s3-output-base`: S3 base prefix used in template-free mode (`OutputDataConfig.S3OutputPath`).
- `--instance-type`: Instance type used in template-free mode (`ResourceConfig.InstanceType`).
- `--volume-gb`: Override EBS volume size in GB (`ResourceConfig.VolumeSizeInGB`, default 30 if missing).
- `--max-runtime-seconds`: Override max runtime seconds (`StoppingCondition.MaxRuntimeInSeconds`, default 86400 if missing).
- `--training-image`: Training image override (`AlgorithmSpecification.TrainingImage`; default from algorithm definition `training_container`).
- `--source-s3-uri`: **(required)** S3 prefix to mount as the `source` channel.
- `--parameter-s3-uri`: **(required for predict/audit/performance-test)** S3 URI to a parameters zip (typically from `s3_uri_predict_parameters_zip` in a prior SageMaker train/backtest run).

## Examples

### Running an Audit

```bash
hv audit --algorithm-jar my_algorithm.jar --algorithm-name product-ranker --source-path data/input.jsonl --dest-path audit_output.jsonl
```

This command performs feature transformation on the `input.jsonl` file using `product-ranker`, and saves the human-readable output to `audit_output.jsonl`.

### Generating Predictions

```bash
hv predict --algorithm-jar my_algorithm.jar --algorithm-name product-ranker --source-path data/input.jsonl --dest-path predictions.jsonl --parameter-path parameters.json
```

This command generates predictions for the input data and saves them to `predictions.jsonl`.

### Evaluating Predictions

```bash
hv evaluate --source-path predictions.jsonl --dest-path evaluation_results.json --enable-online-offline-analysis
```

This command evaluates the predictions in `predictions.jsonl` and saves the evaluation metrics to `evaluation_results.json`, including both online and offline analysis.


### Training a Model

```bash
hv train --algorithm-name product-reranking-algorithm \
         --data-base-dir /path/to/training/data \
         --output-base-dir /path/to/output \
         --algorithm-jar my_algorithm.jar \
         --last-test-time 2025-04-30 \
         --algorithm-override config.json \
         --extra-jvm-args "-XX:MaxRAMPercentage=80,-XX:+UseG1GC"
```

This command trains the `product-reranking-algorithm` using data from the specified directory, with custom configuration overrides and JVM settings optimized for large datasets.

### Running a Backtest

```bash
hv backtest --git-reference main --git-reference feature-improved-ranking \
           --algo-repo-url https://github.com/example-org/example-ranking-algorithm.git \
           --data-base-dir /path/to/test/data \
           --output-base-dir /path/to/backtest/results \
           --scratch-dir /tmp/backtest-workspace \
           --last-test-time 2025-08-05 \
           --sagemaker-config sagemaker-backtest-config.json \
           --number-of-runs 3
```

This command compares the performance of two git references (`main` vs `feature-improved-ranking`) by running a 3-day backtest using SageMaker for execution. The backtest will submit jobs immediately and return job IDs without waiting for completion.

**Output Structure (local output directory):**

- Data artifacts: `--output-base-dir/out/...`
- Metadata + logs: `--output-base-dir/meta/...`
- Start here: `--output-base-dir/meta/<algorithm>@<version>/last_test_date_YYYY-MM-DD/result.json`
- Python orchestration logs: `--output-base-dir/meta/<algorithm>@<version>/last_test_date_YYYY-MM-DD/hv.log`
- Per-stage logs: `.../<stage>/hotvect-offline-utils.log` (Java) and `.../<stage>/stdout-stderr.log` (raw stdout/stderr)

Example:

```
output-base-dir/
├── out/...
└── meta/my-algorithm@1.0.0/last_test_date_2025-11-15/
    ├── hv.log
    ├── result.json
    └── predict/
        ├── metadata.json
        ├── hotvect-offline-utils.log
        └── stdout-stderr.log
```

# Using the Hotvect Extended Utilities (`hv-ext`)

The `hv-ext` tool provides extended utility commands for data analysis, format conversion, and result management that complement the core `hv` operations. This CLI is designed for auxiliary tasks that are commonly needed but are separate from the main ML pipeline operations.

## Overview

The `hv-ext` tool supports the following utility operations:

- **metrics**: Metrics utilities (quality + system), export, and plotting
- **catboost-convert**: Convert CatBoost encoded TSV data to JSONL format
- **config**: Show or initialize `~/.hotvect/config.json`
- **compare-jsonl**: Compare two JSONL files and identify differences between them
- **results**: List and download `result.json` runs from local `meta/` dirs or S3 prefixes (latest-only)
- **data-dependency**: Show or download training data dependencies required for local train/backtest operations
- **show-data-dependency**: Show data dependencies for SageMaker InputDataConfig construction

## Usage

The general syntax for using the `hv-ext` tool is:

```bash
hv-ext <command> [options]
```

To see the list of available commands, run:

```bash
hv-ext -h
```

To get help on a specific command, use:

```bash
hv-ext <command> -h
```

## Extended Utility Commands

### 1. `metrics`

**Description**: Metrics utilities (quality + system), plus export and plotting helpers.

**Usage**:

```bash
hv-ext metrics <metrics-command> [options]
```

**Subcommands**:
- `compare-quality`: Compare offline quality metrics (single-day or multi-day)
- `compare-system`: Compare performance metrics (latency/throughput/memory) (single-day or multi-day)
- `export`: Export a tidy evaluation table from backtest results
- `plot`: Plot evaluation/performance metrics from backtest results (requires `hotvect[ext-viz]`)

**Examples**:

```bash
# Multi-day quality comparison under meta dir
hv-ext metrics compare-quality \
  --output-base-dir ./backtest-results/meta \
  --control my-algorithm@1.0.0 \
  --treatment my-algorithm@1.0.1 \
  --from-test-date 2025-06-01 \
  --to-test-date 2025-06-14 \
  > comparison.json
```

### 2. `catboost-convert`

**Description**: Convert CatBoost encoded TSV data to JSONL format. Useful for inspecting CatBoost model features and transforming training data for analysis.

**Usage**:

```bash
hv-ext catboost-convert --schema-file <schema_file> --encoded-file <tsv_file> --output <output_file>
```

**Required Options**:
- `-s`, `--schema-file`: Path to the CatBoost schema file
- `-e`, `--encoded-file`: Path to the encoded TSV file
- `-o`, `--output`: Output file path

**Examples**:

```bash
# Convert to JSONL
hv-ext catboost-convert --schema-file model.schema --encoded-file encoded_data.tsv --output data.jsonl
```

### 3. `compare-jsonl`

**Description**: Compare two JSONL files and identify differences between them. Supports field renaming via configuration file to handle schema changes between algorithm versions.

**Usage**:

```bash
hv-ext compare-jsonl <file1> <file2> [options]
```

**Positional Arguments**:
- `file1`: Path to the first JSONL file to compare
- `file2`: Path to the second JSONL file to compare

**Options**:
- `-o`, `--output`: Output directory where comparison result files will be stored (default: current directory)
- `-c`, `--config`: Path to JSON configuration file for field renaming and comparison rules (optional)

**Examples**:

```bash
# Basic comparison with JSON output
hv-ext compare-jsonl predictions_old.jsonl predictions_new.jsonl

# Compare with field renaming configuration
hv-ext compare-jsonl audit_v1.jsonl audit_v2.jsonl -c field_mappings.json

# Save comparison results to specific directory
hv-ext compare-jsonl file1.jsonl file2.jsonl -o comparison_output/
```

**Field Renaming Configuration Example** (`field_mappings.json`):
```json
{
  "rename": {
    "legacy_field_01": "request_field_01",
    "legacy_field_02": "request_field_02",
    "legacy_field_03": "request_field_03",
    "legacy_field_04": "request_field_04"
  }
}
```

### 4. `results`

**Description**: Result inventory utilities. Supports:
- `hv-ext results ls`: list matching `result.json` runs (local `meta/` dir or S3 prefix)
- `hv-ext results download`: download selected S3 runs into a local layout

Both subcommands are **latest-only**:
- for each `(test_date, algorithm_id)`, only the newest run is returned/downloaded.
- S3 freshness is based on S3 `LastModified`; local freshness is based on local `result.json` mtime.

#### 4.1 `results ls`

**Usage**:

```bash
hv-ext results ls <location> [options]
```

- `<location>` can be:
  - local meta directory (for example `./backtest-results/meta`)
  - S3 prefix (for example `s3://example-bucket/sagemaker-output/`)

**Common options**:
- `--from-date`, `--to-date` (inclusive; `YYYY-MM-DD`)
- `--algorithm-name-regex`
- `--algorithm-version-regex`

**S3-only options**:
- `--job-name-regex`
- `--role-arn`

**Behavior**:
- Always emits JSON to stdout.
- Local mode returns filesystem paths under `result_json.path_or_key`.
- S3 mode returns S3 URI under `result_json.path_or_key` and `result_json.last_modified`.

**Examples**:

```bash
# List local meta runs (latest-only)
hv-ext results ls ./backtest-results/meta \
  --from-date 2026-02-10 \
  --to-date 2026-02-15 \
  --algorithm-name-regex "^my-algorithm$" \
  --algorithm-version-regex "^1\\.2\\..*$"

# List S3 runs (latest-only) with job-name filter
hv-ext results ls s3://example-bucket/sagemaker-output/ \
  --from-date 2026-02-10 \
  --to-date 2026-02-15 \
  --algorithm-name-regex "^my-algorithm$" \
  --algorithm-version-regex "^1\\.2\\..*$" \
  --job-name-regex "^ml-exp-.*$"
```

**JSON output schema (`results ls`)**:

```json
{
  "location": "string",
  "filters": {
    "from_date": "YYYY-MM-DD|null",
    "to_date": "YYYY-MM-DD|null",
    "algorithm_name_regex": "string|null",
    "algorithm_version_regex": "string|null",
    "job_name_regex": "string|null"
  },
  "runs": [
    {
      "test_date": "YYYY-MM-DD",
      "algorithm_id": "name@version[-hyperparameter]",
      "algorithm_name": "name",
      "algorithm_version": "version",
      "hyperparameter": "string|null",
      "job_name": "string (S3 only)",
      "result_json": {
        "path_or_key": "local path or s3:// URI",
        "last_modified": "ISO-8601 UTC (S3 only)"
      }
    }
  ]
}
```

#### 4.2 `results download`

**Usage**:

```bash
hv-ext results download <s3_prefix> --dest-base-dir <local_dir> [options]
```

**Required options**:
- `<s3_prefix>`: S3 base prefix where backtest results are stored
- `--dest-base-dir`: local destination root

**Optional options**:
- filters: `--from-date`, `--to-date`, `--algorithm-name-regex`, `--algorithm-version-regex`, `--job-name-regex`
- AWS: `--role-arn`
- extra artifacts: `--include-metadata`, `--include-output-data`
- `--no-skip-existing`: re-download even if `meta/<algorithm_id>/last_test_date_<day>/result.json` already exists

**Behavior**:
- Selection uses the same latest-only semantics as `results ls`.
- Downloads `result.json` for every selected run.
- Optionally downloads and extracts:
  - `output/output.tar.gz` (`--include-metadata`)
  - `output/model.tar.gz` (`--include-output-data`)
- Fails fast if any expected downloaded `result.json` is missing after completion.

**Examples**:

```bash
# Download result.json only
hv-ext results download s3://example-bucket/sagemaker-output/ \
  --dest-base-dir ./results \
  --from-date 2026-02-10 \
  --to-date 2026-02-15 \
  --algorithm-name-regex "^my-algorithm$" \
  --algorithm-version-regex "^1\\.2\\..*$"

# Include metadata and output data artifacts
hv-ext results download s3://example-bucket/sagemaker-output/ \
  --dest-base-dir ./results \
  --from-date 2026-02-10 \
  --to-date 2026-02-15 \
  --algorithm-name-regex "^my-algorithm$" \
  --algorithm-version-regex "^1\\.2\\..*$" \
  --job-name-regex "^ml-exp-.*$" \
  --include-metadata \
  --include-output-data
```

**JSON output schema (`results download`)**:

```json
{
  "s3_prefix": "s3://example-bucket",
  "dest_base_dir": "string",
  "filters": {
    "from_date": "YYYY-MM-DD|null",
    "to_date": "YYYY-MM-DD|null",
    "algorithm_name_regex": "string|null",
    "algorithm_version_regex": "string|null",
    "job_name_regex": "string|null"
  },
  "matches": [
    {
      "test_date": "YYYY-MM-DD",
      "algorithm_id": "name@version[-hyperparameter]",
      "algorithm_name": "name",
      "algorithm_version": "version",
      "hyperparameter": "string|null",
      "job_name": "string",
      "result_json": {
        "s3": "s3://example-bucket",
        "last_modified": "ISO-8601 UTC"
      },
      "skipped_existing": "boolean (present when skipped)"
    }
  ],
  "downloaded": {
    "result_json": { "count": 0 },
    "metadata": { "count": 0 },
    "output_data": { "count": 0 }
  }
}
```

## Extended Utility Examples

### Performance Analysis Workflow

```bash
# 1. Download backtest results for comparison
hv-ext results download s3://example-bucket/performance-tests/ \
  --dest-base-dir "./perf-data" \
  --from-date "2025-06-01" \
  --to-date "2025-06-01"

# 2. Compare performance between two algorithm versions
hv-ext metrics compare-system \
  ./perf-data/baseline/performance.json \
  ./perf-data/experiment/performance.json \
  > performance_comparison.json

# 3. Compare detailed prediction results
hv-ext compare-jsonl \
  ./perf-data/baseline/predictions.jsonl \
  ./perf-data/experiment/predictions.jsonl
```

### Data Format Conversion

```bash
# Convert CatBoost encoded data for analysis
hv-ext catboost-convert \
  --schema-file model_v2.schema \
  --encoded-file training_data.tsv \
  --output analysis_data.jsonl
```

### Algorithm Audit Comparison

```bash
# Compare audit outputs between algorithm versions with field mapping
hv-ext compare-jsonl \
  audit_v76.jsonl audit_v77.jsonl \
  -c field_renamings.json \
  -o audit_comparison_results/
```

### 5. `data-dependency`

**Description**: Show or download training data dependencies required for local train/backtest operations. Default behavior lists all dependencies as JSON (safe, no download). Use `--download-all` or `--download <name>` to download explicitly. Analyzes algorithm repositories to determine exact data requirements and downloads the necessary data from S3 with intelligent skip logic and sampling support.

**Usage**:

```bash
hv-ext data-dependency --repo-url <git_repo_url> --git-reference <git_ref> --s3-base-dir <s3_prefix> --local-data-dir <local_dir> --scratch-dir <temp_dir> --last-test-time <date> [options]
```

**Required Options**:
- `--repo-url`: Git repository URL for the algorithm (e.g., "https://github.com/user/algorithm.git")
- `--git-reference`: Git reference (branch/commit) to analyze for data dependencies (single reference only)
- `--s3-base-dir`: S3 base directory where training data is stored (e.g., "s3://example-bucket/tables/")
- `--local-data-dir`: Local directory where data will be downloaded
- `--scratch-dir`: Directory for temporary JAR builds and git checkouts
- `--last-test-time`: Last test time in YYYY-MM-DD format (e.g., "2025-08-09")

**Optional Options - Download Control (mutually exclusive)**:
- `--download-all`: Download all dependencies
- `--download <name>`: Download specific dependency by data_prefix (repeatable)

**Optional Options - Other**:
- `--algorithm-override`: Path to JSON file containing algorithm configuration overrides (merge overlay; missing keys are not deleted from the base definition)
- `--role-arn`: AWS role ARN to assume for S3 access
- `--sample-ratio`: Fraction of files to download per date directory (e.g., 0.1 = 10%, 0.05 = 5%)
- `--max-parallel-downloads`: Maximum number of concurrent file downloads (default: 8)

**Troubleshooting**:
- If downloads fail with "Too many open files" or "Connection pool is full", reduce `--max-parallel-downloads` and/or use `--sample-ratio` (and optionally narrow scope with `--download <name>`).

**Examples**:

```bash
# List dependencies as JSON (default, safe - no download)
hv-ext data-dependency \
  --repo-url https://github.com/example-org/example-algorithm.git \
  --git-reference v77.0.0 \
  --s3-base-dir s3://example-bucket/tables \
  --local-data-dir ./training-data \
  --scratch-dir ./temp-build \
  --last-test-time 2025-08-09

# Download all dependencies
hv-ext data-dependency --download-all \
  --repo-url https://github.com/example-org/example-algorithm.git \
  --git-reference v77.0.0 \
  --s3-base-dir s3://example-bucket/tables \
  --local-data-dir ./training-data \
  --scratch-dir ./temp-build \
  --last-test-time 2025-08-09

# Download specific dependency with sampling
hv-ext data-dependency \
  --download example_training_data \
  --repo-url https://github.com/example-org/example-algorithm.git \
  --git-reference v77.0.0 \
  --s3-base-dir s3://example-bucket/tables \
  --local-data-dir ./training-data \
  --scratch-dir ./temp-build \
  --last-test-time 2025-08-09 \
  --sample-ratio 0.01

# Download with AWS role assumption
hv-ext data-dependency --download-all \
  --repo-url https://github.com/example-org/example-algorithm.git \
  --git-reference main \
  --s3-base-dir s3://example-bucket/tables \
  --local-data-dir ./data \
  --scratch-dir ./temp \
  --last-test-time 2025-08-09 \
  --role-arn arn:aws:iam::123456789012:role/example-role

# Pipe JSON output to jq for analysis
hv-ext data-dependency \
  --repo-url https://github.com/example-org/example-algorithm.git \
  --git-reference main \
  --s3-base-dir s3://example-bucket/tables \
  --local-data-dir ./data \
  --scratch-dir ./temp \
  --last-test-time 2025-08-09 | jq '.summary.total_size_human'
```

**Key Features**:
- **Safe Default**: Lists dependencies as JSON by default (no accidental downloads)
- **JSON Output**: stdout=JSON (pipeable to jq, etc.), stderr=progress logs
- **Selective Downloads**: Download all (`--download-all`) or specific dependencies (`--download <name>`)
- **Automatic Dependency Analysis**: Clones repositories, builds JARs, and uses hotvect's AlgorithmPipeline to determine exact data requirements
- **Smart Resume**: Automatically resumes incomplete downloads, skips complete date directories
- **Sampling Support**: Download subset of files using `--sample-ratio` for testing and development (critical for large datasets)
- **Parallel Downloads**: Concurrent download of multiple date directories and files within each directory
- **AWS Integration**: Supports role assumption for secure S3 access
- **Algorithm Name Extraction**: Automatically extracts algorithm names from pom.xml artifactId following established patterns

### 6. `show-data-dependency`

**Description**: Show algorithm data dependencies as JSON for SageMaker `InputDataConfig` construction.

**Usage**:

```bash
hv-ext show-data-dependency --repo-url <git_repo_url> --git-reference <git_ref> --scratch-dir <temp_dir> --last-test-time <date> [options]
```

**Optional Options**:
- `--git-reference`: Can be specified multiple times for multiple references.
- `--algorithm-override`: Path to JSON file containing algorithm configuration overrides (repeatable).
- `-o`, `--output`: Output file path (default: stdout).

**Example**:

```bash
hv-ext show-data-dependency \
  --repo-url https://github.com/example-org/example-algorithm.git \
  --git-reference v77.0.0 \
  --scratch-dir ./temp-build \
  --last-test-time 2025-08-09 \
  -o dependencies.json
```

The interactive browser debugger now lives on `hv serve --ui`. See `design/algorithm-demo-ui/index.md`.

# Using the Hotvect Experiment-Management CLI (`hv-exp`)

`hv-exp` is a small, **read-only** CLI for inspecting the experiment-management service (formerly “EMS”),
such as slots, experiments, algorithms, and algorithm parameters.

## Usage

```bash
hv-exp <subcommand> [options]
```

Examples:

```bash
hv-exp slot list
hv-exp slot get --slot-name my-slot
hv-exp experiment list --slot-name my-slot
hv-exp algorithm list-active
hv-exp algorithm list-in-use
hv-exp algorithm list-in-use --slot-name my-slot
```

## `algorithm list-in-use`

List algorithms currently in use, where "in use" means:

- the slot default variant algorithm, and/or
- algorithms referenced by active (non-terminated) experiments.

Usage:

```bash
# all slots
hv-exp algorithm list-in-use

# a single slot
hv-exp algorithm list-in-use --slot-name my-slot
```

Output is JSON and includes per-algorithm `in_use_by` entries with source details
(`default_variant` or `active_experiment`), plus slot/variant/experiment identifiers.

## Authentication and configuration

By default, `hv-exp` reads experiment-management settings from `~/.hotvect/config.json` under the
`experiment_management` section (see the config reference).

You can also override both URL and token provider on the command line:

```bash
hv-exp \
  --url https://ems.example.com \
  --token-provider-command "bash -lc 'echo $EMS_TOKEN'" \
  --token-provider-ttl-ms 3600000 \
  slot list
```

## Additional Information

- **Ordered Processing**: The `hv` tool preserves the order in the input data
- **Defaults**: If certain paths are not provided (like `--metadata-path` or `--dest-path`), the tool uses default paths based on the algorithm name and command.
- **Output Locations**:
    - **Transformed Data**: For commands that transform data (e.g., `audit`, `encode`, `predict`), the transformed output is saved to the path specified by `--dest-path`. For `encode`, `--dest-path` is a directory containing `shard_*<ext>` files.
    - **Metadata**: Operation metadata, including timing information, algorithm version, and other details, is saved to `--metadata-path/metadata.json` (logs: `hv.log`, `hotvect-offline-utils.log`, `stdout-stderr.log`).
- **Extended Utilities**: The `hv-ext` tool complements `hv` by providing data analysis and management utilities that are commonly needed but separate from core ML pipeline operations.
