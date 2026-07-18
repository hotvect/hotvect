---
title: CLI reference
description: Complete reference for hv, hv-ext, and hv-exp CLI commands
tags: [cli, reference, commands, usage, tools]
difficulty: intermediate
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

# CLI reference

Hotvect installs three command-line interfaces:

| CLI | Purpose |
| --- | --- |
| `hv` | Run algorithms, pipelines, local debug servers, docs lookup, and prompt lookup |
| `hv-ext` | Compare artifacts, report metrics, inspect results, and manage data dependencies |
| `hv-exp` | Read experiment-management state and online evaluation partitions |

Use `<cli> --help` and `<cli> <command> --help` as the exact reference for the installed checkout. This page explains
the contracts and output shapes that help text alone does not capture.

This is a lookup page, not a first-run tutorial. If Hotvect is new to you, run the
[example product algorithms](../../guides/first-run/index.md) first. Then return here for one command's exact contract.

## `hv` command map

`hv` supports these operations:

- **docs**: Search and read the bundled Hotvect docs (JSON output only; scan-based by default).
- **prompts**: List and read the bundled Hotvect prompt templates (JSON output only).
- **audit**: Generate human-readable audit data showing feature transformations and calculations.
- **performance-test**: Benchmark algorithm performance and measure throughput/latency.
- **encode**: Transform input data into ML-ready format for training.
- **predict**: Generate model predictions on test/validation data.
- **generate-state**: Generate state files for algorithms that require state generation.
- **evaluate**: Calculate performance metrics from model predictions.
- **train**: Train a machine learning model using the hotvect pipeline.
- **backtest**: Run backtest on git references to compare algorithm performance.
- **serve**: Serve the full algorithm over HTTP for local debugging. Add `--ui` to enable the browser debugger on the same server.
- **worker**: Worker-runtime HTTP debugging utilities for LitServe-backed worker endpoints.

!!! note "SageMaker support (quick summary)"
    Hotvect supports running on SageMaker in two ways:

    - **Pipelines:** `hv train` and `hv backtest` can execute on SageMaker (submit jobs and return immediately).
    - **One-shot remote commands:** `hv audit`, `hv predict`, `hv encode`, `hv evaluate`, and `hv performance-test` can also execute on SageMaker via `--sagemaker`.

    For flags and required S3 inputs, see [SageMaker one-shot mode](#sagemaker-one-shot-mode-auditpredictevaluateencodeperformance-test).

## Choose the algorithm target

For a composite, target the algorithm that owns the contract needed by the command. Nesting alone does not imply that
a child trains or a parent only evaluates.

| Command | Works on | Notes |
|---------|----------|-------|
| `audit` | Algorithm with the relevant transformer | Requires a parameters ZIP; the current audit task rejects vectorizer-only definitions |
| `encode` | Algorithm with the encoder | Produces training-library input; training may be a separate stage |
| `generate-state` | Algorithms with state generation | Targets algorithms with `generator_factory_classname`. Can be at parent or child level depending on algorithm design. |
| `train`, `predict`, `performance-test`, `backtest` | Outer or inner algorithm | An outer target prepares its declared dependency graph first |

To discover which algorithms train, inspect the algorithm definition JSON (embedded in algorithm JARs or in source at `src/main/resources/*-algorithm-definition.json`). Look for:
- `training_command` - Definitive indicator the algorithm trains
- `transformer_factory_classname` - For feature audit. A vectorizer may be used by training or inference, but the current `audit` command does not accept it.
- `encoder_factory_classname` - For encoding training data
- `generator_factory_classname` - For state generation

See [Parent and child algorithms](../../guides/patterns/parent-child/index.md).

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
hv audit ... --parameter-path params.zip -- -Xmx8g -Dfoo=bar
```

Passthrough is supported for the Java wrapper commands (`audit`, `encode`, `predict`, `generate-state`, `performance-test`) and for `serve`. `train` and `backtest` use `--extra-jvm-args` instead, and commands like `worker serve` reject passthrough args after `--`.

Choose exactly one heap cap wherever you pass JVM arguments: `-Xmx...` **or** `-XX:MaxRAMPercentage=...`. Hotvect
rejects duplicates and the combination of both styles. If neither is supplied, pipeline and Java wrapper commands use
`-XX:MaxRAMPercentage=80`; runtime commands also add `-XX:+ExitOnOutOfMemoryError`.

## `hv` commands

### `docs`

**Description**: Search and read the bundled Hotvect Markdown docs. Output is always JSON on stdout for scripts and
other automation. By default `hv docs` uses scan-based search and does not create a local SQLite index.

**Usage**:

```bash
hv docs list
hv docs search backtest --limit 5
hv docs read reference/cli/index.md
hv docs read hotvect://docs/reference/cli/index.md
```

**Options**:

- `--sqlite-index`: Opt in to SQLite FTS indexing for faster repeated searches.
- `--sqlite-index-path`: Use an explicit path for the SQLite index file (implies `--sqlite-index`).

**Subcommands**:

- `list`: Return all available docs as JSON (`{ "docs": [...] }`).
- `search <query>`: Return ranked search matches as JSON (`backend`, `query`, `matches`).
- `read <uri-or-relpath>`: Return one markdown page as JSON (`uri`, `mimeType`, `name`, `text`).

**Examples**:

```bash
hv docs search "sagemaker backtest" --limit 3
hv docs read guides/docs-mcp/index.md
```

### `prompts`

**Description**: List and read the bundled Hotvect prompt templates. Output is always JSON on stdout for automation.
This mirrors the prompt catalog exposed by `hv-mcp` without requiring MCP setup.

**Usage**:

```bash
hv prompts list
hv prompts read setup_config
```

**Subcommands**:

- `list`: Return all available prompt templates as JSON (`{ "prompts": [...] }`).
- `read <name>`: Return one prompt template as JSON (`name`, `description`, `text`).

**Examples**:

```bash
hv prompts list
hv prompts read ordered_backtest_with_pinned_parameters
```

### 1. `audit`

**Description**: Generate human-readable audit data showing feature transformations and calculations. Audit requires the
predict-parameters ZIP used by the algorithm, then writes readable JSONL for debugging and comparison.

**SageMaker**: Supported via one-shot mode (`--sagemaker`). See [SageMaker one-shot mode](#sagemaker-one-shot-mode-auditpredictevaluateencodeperformance-test).

**Usage**:

```bash
hv audit --algorithm-jar <path_to_jar> --algorithm-name <algorithm_name> --parameter-path <predict-parameters.zip> [options]
```

**Options**: Same as the common Java command options (see Common Options section below).

**Debug option**:

- `--include-feature-store-responses`: Include feature-store responses in each audit row under
  `additional_properties.__feature_store_responses`. Use this only for focused debugging because it can make audit
  output substantially larger.

**Example**:

```bash
hv audit --algorithm-jar my_algorithm.jar --algorithm-name example-ranker --parameter-path parameters.zip --source-path input_data.jsonl --dest-path audit_output --ordered
```

### 2. `performance-test`

**Description**: Benchmark algorithm performance and measure latency percentiles under a controlled request rate.

**Workload mode**: `hv performance-test` defaults to **realtime** workload mode, even though the input rows are offline. This is intentional: performance-test is generally used to measure serving latency and should normally exercise the algorithm's `realtime` runtime config. Use `--workload-mode batch` only when you explicitly want to benchmark the batch path. `hv predict` continues to use batch workload mode.

**Important**: `hv performance-test` runs a warmup first, then (by default) paces the measurement runs at `0.8 × warmup_mean_throughput` to reduce queueing effects and make p99/p999 more stable across runs. This means the reported throughput is **not** “max throughput” by default.

**Threading default**: if `--max-threads` is omitted, `hv performance-test` defaults to:

- `2` threads on machines with `>=4` physical cores
- otherwise `1`

Pass an explicit `--max-threads` to override that heuristic, or `--max-threads 0` to avoid passing the flag to Java and let the JAR decide.

**SageMaker**: Supported via one-shot mode (`--sagemaker`). See [SageMaker one-shot mode](#sagemaker-one-shot-mode-auditpredictevaluateencodeperformance-test).

**Usage**:

```bash
hv performance-test --algorithm-jar <path_to_jar> --algorithm-name <algorithm_name> [options]
```

**Options**: Same as the common Java command options (see Common Options section below).

**Performance-test-only options**:
- `--target-rps`: Fixed target requests/sec (best for comparing versions under identical load). Overrides `--target-throughput-fraction`.
- `--target-throughput-fraction`: Fraction of warmup mean throughput to use as the target requests/sec when `--target-rps` is not set. Default: `0.8`. Set to `0` to disable pacing.
- `--workload-mode {realtime,batch}`: Select which algorithm workload mode to benchmark. Default: `realtime`.
- `--sample-pool-size`: Number of decoded candidate examples retained in memory for replay. This is separate from
  `--samples`, which controls the number of measured executions. Pin both when comparing runs.

**Benchmarking methodology**: for reliable A/B latency claims, keep runtime and hardware fixed, pin both `--target-rps` and `--samples`, repeat independent jobs, and use statistical tests before calling a `p99`/`p999` regression. See [Reliable Performance Benchmarking](../../guides/performance-benchmarking/index.md).

### 3. `encode`

**Description**: Transform input data into ML-ready format for training. Perform feature transformation on input data and encode it in the binary format expected by the machine learning library (e.g., CatBoost). This step is required before training and produces the encoded dataset used for model training.

**SageMaker**: Supported via one-shot mode (`--sagemaker`). See [SageMaker one-shot mode](#sagemaker-one-shot-mode-auditpredictevaluateencodeperformance-test).

**Output**:
- `--dest-path` is a **directory** (not a file).
- Encoded outputs are written as part files inside that directory: `part-00000<ext>`, `part-00001<ext>`, ...
- `<ext>` is determined by the encoder (e.g., `.tfrecord`, `.tsv`, `.jsonl`).

**Usage**:

```bash
hv encode --algorithm-jar <path_to_jar> --algorithm-name <algorithm_name> [options]
```

**Options**: Same as the common Java command options, with `--dest-schema-path` being particularly relevant for encoding operations.

### 4. `predict`

**Description**: Generate model predictions on test/validation data. Pass a parameter ZIP when the algorithm requires
one; stateless algorithms can predict without it. Output includes prediction scores and can be used for evaluation or
serving.

**SageMaker**: Supported via one-shot mode (`--sagemaker`). See [SageMaker one-shot mode](#sagemaker-one-shot-mode-auditpredictevaluateencodeperformance-test).

**Usage**:

```bash
hv predict --algorithm-jar <path_to_jar> --algorithm-name <algorithm_name> [options]
```

**Options**: Same as the common Java command options (see Common Options section below).

**Additional Options**:
- `--log-features`: Enable feature logging during prediction for debugging composite algorithms (optional; v10+).
- `--include-feature-store-responses`: Include feature-store responses in output rows under
  `additional_properties.__feature_store_responses`. Use it for a small debug sample, not routine backtests.

### 5. `evaluate`

**Description**: Calculate quality metrics from prediction results. The evaluator computes offline metrics and derives
online dimensions only when that dimension is complete across the scored rows.

**Usage**:

```bash
hv evaluate --source-path <predictions_file> --dest-path <evaluation_output> [options]
```

**Options**:

- `--source-path`: **(required)** Path to the prediction results. This can be a single prediction file, or a prediction directory containing supported text outputs such as `part-*`, `part-*.gz`, `shard_*.jsonl`, `*.json`, `*.jsonl`, `*.ndjson`, `*.json.gz`, or `*.jsonl.gz`. Common sidecars such as `_metadata.json` and hidden/underscore files are ignored; matching files are parsed directly during evaluation, so malformed matching files fail the run.
- `--dest-path`: **(required)** Path where the evaluation metrics will be saved as JSON.

**Example**:

```bash
hv evaluate --source-path predictions --dest-path evaluation_results.json
```

### 6. `train`

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
- `--last-test-time`: Last test time in YYYY-MM-DD format (e.g., "2000-01-15").

**Optional Options**:

- `--algorithm-override`: Path to JSON file containing algorithm configuration overrides. Ordinary object fields merge recursively, scalars/arrays replace, `null` deletes a field, and `dependencies.<child>` may only target declared children.
- `--extra-jvm-args`: Additional JVM arguments for training, comma-separated (for example,
  `"-XX:MaxRAMPercentage=80,-XX:+UseG1GC"`). Choose one heap cap only: `-Xmx...` or
  `-XX:MaxRAMPercentage=...`.
- `--max-threads`: Max threads for hotvect encode/predict (0 = don't pass; JAR decides). Standalone `hv performance-test` uses a different default when omitted; see [Common Options](#common-options).
- `--cache`: Enable Hotvect pipeline caching (local path or `s3://...`).
- `--cache-scope`: Cache key scope across algorithm versions (`major|minor|patch|hyperparam`, default: `hyperparam`).
- `--cache-refresh`: Ignore cache reads and write fresh run-level cache results. Requires an effective `cache_base_dir` and effective cache mode `run`.
- `--sagemaker`: Execute training remotely on SageMaker (submits job and returns immediately).
- `--sagemaker-job-prefix`: **(required for remote execution)** Valid SageMaker TrainingJobName prefix (no sanitization; invalid values error).
- `--sagemaker-config`: Path to SageMaker training job definition JSON. Supplying it also activates remote SageMaker
  execution, even without `--sagemaker`.
- `--role-arn`: SageMaker job execution role ARN (CreateTrainingJob.RoleArn) (required for template-free mode).
- `--assume-role-arn`: AWS role ARN to assume for SageMaker submission (optional; defaults to using current AWS credentials).
- `--s3-output-base`: S3 base prefix used in template-free SageMaker mode (`OutputDataConfig.S3OutputPath`).
- `--instance-type`: Primary instance type used in template-free SageMaker mode (`ResourceConfig.InstanceType`). For ordered capacity fallback, use `HotvectSubmissionOptions.PreferredInstanceTypes` in the SageMaker job definition.
- `--volume-gb`: Override EBS volume size in GB (`ResourceConfig.VolumeSizeInGB`, default 30 if missing).
- `--max-runtime-seconds`: Override max runtime seconds (`StoppingCondition.MaxRuntimeInSeconds`, default 86400 if missing).
- `--training-image`: Training image override (`AlgorithmSpecification.TrainingImage`). This is the highest-precedence image setting. Prefer committed `sagemaker_training_job_definition.AlgorithmSpecification.TrainingImage` or an algorithm override JSON for reproducible image changes.
- `--auto-attach-data`: Auto-populate `InputDataConfig` channels from algorithm dependencies (SageMaker mode).
- `--auto-attach-data-default-s3-base`: Default S3 base prefix used when dependencies do not declare explicit `s3_uri`.
- `--auto-attach-data-environment`: Environment key used when dependency `s3_uri` maps are resolved and when
  `prediction_spec` environment maps are selected (default: `production`). `prediction_spec.s3_uri` and
  `prediction_spec.output_uri` require an exact key match.
- `--performance-test-samples`: Pin pipeline perf-test sample size for comparability (passes `--samples` to Java perf-test).
- `--performance-test-sample-pool-size`: Pin the decoded replay pool separately from measured executions. Use it with
  `--performance-test-samples` for comparable pipeline performance tests.

**Target semantics**:

- `--target parameters`: stop after packaging predict parameters
- `--target evaluate`: use `test_data_spec`, write the hv-managed `prediction/` artifact, then evaluate/perf/audit
- `--target predict`: require `prediction_spec`, read prediction input from that spec, and publish the prediction
  artifact to `prediction_spec.output_uri`

When `--target predict` is used:

- Hotvect does **not** fall back to `test_data_spec`
- the final prediction artifact does **not** live under the normal hv-managed `prediction/` path
- metadata and `result.json` still live under the normal hv-managed output directory
- unlabeled prediction output may omit `reward`; downstream consumers must treat `reward` as optional instead of
  assuming a default value
- if `prediction_spec.output_uri` points at S3, the destination prefix must be empty before publish; Hotvect fails
  instead of merging with existing objects
- when `--sagemaker` is used with `--target predict`, `prediction_spec.output_uri` must resolve to an `s3://...`
  destination; local output paths are rejected before submission
- if `prediction_spec.s3_uri` or `prediction_spec.output_uri` is an environment map, the selected environment key must
  match exactly; Hotvect does not lowercase, alias, or fall back across map entries for `prediction_spec`

**Example**:

```bash
hv train --algorithm-name example-document-ranker \
         --data-base-dir /path/to/training/data \
         --output-base-dir /path/to/output \
         --algorithm-jar /path/to/algorithm.jar \
         --last-test-time 2000-01-15 \
         --algorithm-override /path/to/override.json \
         --extra-jvm-args "-XX:MaxRAMPercentage=80,-XX:+UseG1GC"
```

**SageMaker Example** (submits and returns):

```bash
hv train --algorithm-name example-document-ranker \
         --algorithm-jar /path/to/algorithm.jar \
         --last-test-time 2000-01-15 \
         --sagemaker \
         --sagemaker-job-prefix exp-rerank \
         --auto-attach-data \
         --auto-attach-data-default-s3-base s3://example-bucket/tables/ \
         --performance-test-samples 200000
```

**Explicit Inference Example** (`prediction_spec` required):

```bash
hv train --algorithm-name example-document-ranker \
         --data-base-dir /path/to/data \
         --output-base-dir /path/to/output \
         --algorithm-jar /path/to/algorithm.jar \
         --last-test-time 2000-01-15 \
         --target predict
```

That command only works when the algorithm definition (or override file) includes a `prediction_spec` with both:

- the prediction input data specification
- the final `output_uri` for the generated prediction artifact

If either `prediction_spec.s3_uri` or `prediction_spec.output_uri` is an environment map, its keys must match
`--auto-attach-data-environment` exactly.

**Algorithm Override File Example**:

```json
{
    "hyperparameter_version": "1day",
    "hotvect_execution_parameters": {},
    "dependencies": {
        "example-child": {
            "number_of_training_days": 1
        }
    }
}
```

Override notes:

- `dependencies` is a child-override map keyed by existing child algorithm names.
- Unknown child names fail fast.
- Overriding one child preserves unspecified siblings.
- `null` deletes a field from the effective definition.

### 7. `backtest`

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
- `--output-base-dir`: Base directory where backtest results and submission metadata will be saved (**required for both local and SageMaker execution**).
- `--scratch-dir`: Directory for temporary files like JARs and working data during backtest execution.
- `--last-test-time`: Last test time in YYYY-MM-DD format (e.g., "2000-01-04").

**Optional Options**:

- `--algorithm-override`: Path to JSON file containing algorithm configuration overrides (repeatable). If one override is provided, it applies to all git references. If multiple are provided, they apply to git references in order. Overrides use the same patch semantics as `hv train`.
- `--number-of-runs`: Number of consecutive historical test dates to run per Git reference, ending at
  `--last-test-time` (default: 1).
- `--extra-jvm-args`: Additional JVM arguments, comma-separated (for example,
  `"-XX:MaxRAMPercentage=80,-XX:+UseG1GC"`). Choose one heap cap only: `-Xmx...` or
  `-XX:MaxRAMPercentage=...`.
- `--sagemaker`: Execute backtest remotely on SageMaker (submits jobs and returns immediately).
- `--sagemaker-job-prefix`: **(required for remote execution)** Valid SageMaker TrainingJobName prefix (no sanitization; invalid values error).
- `--sagemaker-config`: Path to JSON file containing SageMaker training job configuration. Supplying it also activates
  remote SageMaker execution, even without `--sagemaker`.
- `--role-arn`: SageMaker job execution role ARN (CreateTrainingJob.RoleArn) (required for template-free mode).
- `--assume-role-arn`: AWS role ARN to assume for SageMaker submission (optional; defaults to using current AWS credentials).
- `--s3-output-base`: S3 base prefix used in template-free SageMaker mode (`OutputDataConfig.S3OutputPath`).
- `--instance-type`: Primary instance type used in template-free SageMaker mode (`ResourceConfig.InstanceType`). For ordered capacity fallback, use `HotvectSubmissionOptions.PreferredInstanceTypes` in the SageMaker job definition.
- `--volume-gb`: Override EBS volume size in GB (`ResourceConfig.VolumeSizeInGB`, default 30 if missing).
- `--max-runtime-seconds`: Override max runtime seconds (`StoppingCondition.MaxRuntimeInSeconds`, default 86400 if missing).
- `--training-image`: Training image override (`AlgorithmSpecification.TrainingImage`). This is the highest-precedence image setting. Prefer committed `sagemaker_training_job_definition.AlgorithmSpecification.TrainingImage` or an algorithm override JSON for reproducible image changes.
- `--n-process`: Number of parallel processes for local execution (default: 1).
- `--max-threads-per-process`: Maximum threads per process for local execution (default: auto-calculated).
- `--clean`: Clean output directories before starting backtest.
- `--no-performance-test`: Disable system performance testing.
- `--cache`: Enable Hotvect pipeline caching (local path or `s3://...`). Use `s3://...` for SageMaker runs.
- `--cache-scope`: Cache key scope across algorithm versions (`major|minor|patch|hyperparam`, default: `hyperparam`).
- `--cache-refresh`: Ignore cache reads and write fresh run-level cache results. Requires an effective `cache_base_dir` and effective cache mode `run`.
- `--performance-test-samples`: Pin pipeline perf-test sample size for comparability (passes `--samples` to Java perf-test).
- `--performance-test-sample-pool-size`: Pin the decoded replay pool separately from measured executions. Use it with
  `--performance-test-samples` when system-performance results must be comparable.
- `--auto-attach-data-default-s3-base`: Default S3 base URI used when dependencies do not specify explicit `s3_uri` (auto-attach is always enabled in SageMaker mode).
- `--auto-attach-data-environment`: Preferred environment key when dependency `s3_uri` is a map (default: `production`). Keys are matched case-insensitively with fallbacks (`production`, `prod`, `test`, `staging`, then first map entry).

**SageMaker submission metadata**:

When SageMaker mode is enabled, each `hv backtest` invocation writes local submission metadata under:

```text
<output-base-dir>/meta/_backtest_submissions/<run_id>/
  backtest_submission_manifest.json
  backtest_submission_status.json
```

Each run gets its own `<run_id>`, so repeated backtests do not overwrite prior submission records. `backtest_submission_status.json` is a submission-time snapshot, not a live SageMaker polling result.

**Examples**:

**Basic Local Backtest**:
```bash
hv backtest --git-reference main --git-reference feature-branch \
           --algo-repo-url https://github.com/example-org/example-algorithm.git \
           --data-base-dir /path/to/data \
           --output-base-dir /path/to/output \
           --scratch-dir /tmp/backtest \
           --last-test-time 2000-01-04 \
           --n-process 4
```

**SageMaker Backtest**:
```bash
hv backtest --git-reference main \
           --algo-repo-url https://github.com/example-org/example-algorithm.git \
           --output-base-dir /path/to/output \
           --scratch-dir /tmp/backtest \
           --last-test-time 2000-01-04 \
           --sagemaker \
           --sagemaker-job-prefix example-backtest \
           --sagemaker-config sagemaker-config.json \
           --role-arn arn:aws:iam::123456789012:role/sagemaker-execution-role
```

**Backtest with S3 Cache (recommended for SageMaker)**:
```bash
hv backtest --git-reference main \
           --algo-repo-url https://github.com/example-org/example-algorithm.git \
           --output-base-dir /path/to/output \
           --scratch-dir /tmp/backtest \
           --last-test-time 2000-01-04 \
           --sagemaker \
           --sagemaker-job-prefix example-cache-backtest \
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
           --last-test-time 2000-01-04 \
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
           --last-test-time 2000-01-04
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
                "example-child": {
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
    "HotvectSubmissionOptions": {
        "PreferredInstanceTypes": [
            "ml.m5.2xlarge",
            "ml.r6i.2xlarge"
        ]
    },
    "RoleArn": "arn:aws:iam::123456789012:role/sagemaker-execution-role",
    "StoppingCondition": {
        "MaxRuntimeInSeconds": 86400
    }
}
```

`HotvectSubmissionOptions.PreferredInstanceTypes` is optional. Omit it when you want a single fixed `ResourceConfig.InstanceType`.

### 8. `generate-state`

**Description**: Generate state files required by algorithms that use state generation (must have `generator_factory_classname` in the algorithm definition).

**Usage**:

```bash
hv generate-state --algorithm-jar <path_to_jar> --algorithm-name <algorithm_name> --source-path <state_input_json> [options]
```

**Options**: Same as the common Java command options (see Common Options section below).

**Example**:

```bash
hv generate-state --algorithm-jar my_algorithm.jar --algorithm-name example-ranker --source-path '{"training_data":["file1","file2"]}' --dest-path state.output
```

### `serve`

**Description**: Serve the **full algorithm** over HTTP for local debugging. This runs the Java algorithm runtime, so
request decoding, feature extraction, algorithm wiring, and output formatting happen in the JVM. It does not reproduce
the containing application's request adapter or operational behavior. Headless mode uses the minimal
`hotvect-algorithm-serve` JAR; add `--ui` to use the Demo UI extension on the same serving core.

**Usage**:

```bash
hv serve --algorithm-jar <path_to_jar> --algorithm-name <algorithm_name> --parameter-path <params_zip> --port <port> [options]
hv serve --local-runtime-config <local_runtimes.json> --port <port> [options]
hv serve --ems-url <url> --ems-slot <slot> --port <port> [options]
```

**Required Options**:
- `--port`: Port to bind to (required; must be non-zero).

**Required in single-runtime local mode**:
- `--algorithm-jar`: Path to the algorithm JAR.
- `--algorithm-name`: Algorithm name (matches algorithm definition).
- `--parameter-path`: Path to parameters ZIP from training.

**Alternative local runtime config**:
- `--local-runtime-config`: Path to a JSON file with one or more local runtimes. Each runtime entry uses `algorithm_jar`, `algorithm_name`, optional `algorithm_override`, and `parameter_path`.
- `--local-runtime-config` is mutually exclusive with the single-runtime `--algorithm-jar` / `--algorithm-name` / `--parameter-path` flags.
- `--local-runtime-config` rejects unknown JSON fields and resolves relative runtime paths relative to the config file location.

**Remote metadata mode**:

- `--ems-url` and `--ems-slot` load selected algorithm metadata from an external service instead of a local JAR.
- This mode requires both flags and rejects `--algorithm-jar`, `--algorithm-name`, `--parameter-path`,
  `--algorithm-override`, and `--local-runtime-config`.
- `--ems-assignment-key` controls variant assignment; `--ems-token-env` names the environment variable that carries the
  bearer token.
- `--ems-scratch-dir`, `--ems-refresh-period-seconds`, `--ems-connect-timeout-seconds`, and
  `--ems-read-timeout-seconds` control local download and refresh behavior when the defaults are not appropriate.

**Optional Options**:
- `--host`: Host/interface to bind to (default: `127.0.0.1`).
- `--algorithm-override`: Path to a JSON override applied with the same patch semantics used by train/backtest before serving.
- `--ui`: Enable the browser UI routes and static assets.
- `--source-path`: Directory containing offline examples. Required with `--ui`.
- `--action-metadata-path`: Directory containing action metadata keyed by `action_id` (UI only).
- `--demo-sqlite-path`: SQLite cache path for UI mode.
- `--default-select-json-path`: JSON path preselected in the browser editor (UI only).
- `--max-request-mib`: Maximum accepted request size in MiB (default: `256`; Java enforces `1..512`).
- `--startup-timeout-seconds`: Maximum time to wait for `/health` before the CLI fails startup (default: `120`).

**Runtime defaults**:
- Local artifact modes construct algorithms with `BATCH` workload mode and `OFFLINE` input semantics. EMS mode uses the
  repository's `REALTIME` and `ONLINE` context. Both remain local-debug server modes.
- Neither current mode configures the optional runtime-local state-storage root required by definitions with
  `requires_local_state_storage: true`.
- `hv serve` injects `-XX:MaxRAMPercentage=80` when you do not pass an explicit heap cap (`-Xmx...` or `-XX:MaxRAMPercentage=...`).
- `hv serve` also injects `-XX:+ExitOnOutOfMemoryError` unless that exact flag is already present.
- Extra JVM args must be passed after an explicit `--` separator (for example `hv serve ... -- -Xmx4g`). Use an explicit heap flag when you want to take control of heap sizing.

**Endpoints**:
- `GET /health`
- `GET /api/health`
- `GET /api/metadata`
- `GET /api/config`
- `POST /predict`

In local multi-runtime mode, `POST /predict` also accepts `algorithm_runtime_id` as a query parameter to pick a specific loaded runtime.

With `--ui`, the same process also exposes the interactive UI routes.

- `POST /api/run` keeps raw runtime execution and accepts `algorithm_runtime_id`.
- The browser UI uses these routes:
  - `GET /api/demo/examples`
  - `GET /api/demo/examples/{example_index}`
  - `POST /api/demo/run`
  - `POST /api/demo/compare`
  - `POST /api/demo/predict`
- With `--local-runtime-config`, the UI exposes one algorithm comparison view per `algorithm_runtime_id`.
- Compare-mode defaults prefer algorithm output against the preferred recorded view, then the first available recorded
  view, then another runtime.
- The UI only supports examples that the selected algorithm runtime can decode and execute. Recorded views are debug
  projections from decoded outcome metadata, not arbitrary serving-log inputs.

### `worker serve`

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
- `--algorithm-override`: Path to a JSON override applied with the same patch semantics used by train/backtest before serving.
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

Most Java-based commands (audit, performance-test, encode, predict, generate-state) share common options:

- `--algorithm-jar`: **(required)** Path to the JAR file containing the algorithm implementation.
- `--algorithm-name`: **(required)** Name of the algorithm to execute (e.g., `example-ranker`).
- `--algorithm-override`: Path to a JSON file with algorithm configuration overrides. When set, `hv` writes a complete effective definition JSON to `--metadata-path/effective_algorithm_definition.json` and passes that file to Java. Ordinary object fields merge recursively, scalars/arrays replace, `null` deletes fields, and `dependencies.<child>` may only target declared children.
- `--metadata-path`: Directory where operation artifacts are written (optional, auto-generated if not specified). Files include `metadata.json`, `hv.log`, `hotvect-offline-utils.log`, and `stdout-stderr.log`.
- `--source-path`: Path to the input data source for the operation (optional for some commands).
- `--dest-path`: Destination path for the operation output (optional, auto-generated if not specified). For `encode`, `predict`, and `audit`, this is a **directory** containing `part-*<ext>` files. Ordered `predict` and ordered `audit` produce a single `part-00000.jsonl`.
- `--parameter-path`: Path to the trained model parameter ZIP. Required for `audit`, `performance-test`, `serve`, and
  `worker serve`; optional for `predict` (required only by algorithms that need parameters) and `encode`.
- `--dest-schema-path`: Path where the feature schema description will be saved (optional, used in encoding operations).
- `--samples`: Number of samples to process, useful for testing with smaller datasets (optional).
- `--max-threads`: Max worker threads for Hotvect Java execution. For `hv performance-test`, if omitted, `hv` defaults to `2` threads on machines with `>=4` physical cores (else `1`). Pass an explicit value to override, or `0` to avoid passing `--max-threads` and let the JAR decide.
- `--ordered`, `--unordered`, `--writer-num-shards`: Output controls for `audit`, `predict`, and `encode`.
  `--ordered` preserves input order and writes one part file; `--unordered` permits parallel writing of part files.
  The flags are mutually exclusive, and `--writer-num-shards > 1` cannot be combined with `--ordered`. By default,
  `audit` and `encode` write ordered output, while `predict` writes unordered output; an effective algorithm definition
  can override those task defaults. Pass `--ordered` for a row-for-row predict comparison that needs
  `part-00000.jsonl`.
- `--target-rps`: Performance-test only. Fixed target requests/sec (optional).
- `--target-throughput-fraction`: Performance-test only. Fraction of warmup mean throughput to use as target requests/sec (optional; default `0.8`, `0` disables pacing).
- JVM passthrough: for Java wrapper commands, extra JVM args must follow an explicit `--` separator. Example: `hv predict ... -- -Xmx8g -Dfoo=bar`.

Choose exactly one heap cap: `-Xmx...` or `-XX:MaxRAMPercentage=...`. Passing both (or repeating either style)
fails fast; when neither is set, Hotvect uses `-XX:MaxRAMPercentage=80`.

**Note**: Generally, if the command transforms data, the actual transformed output is stored in the `--dest-path`, while metadata—such as timing information, algorithm version, and other operation details—is stored in `--metadata-path/metadata.json`. For debugging, `--metadata-path/hv.log` contains Python CLI logs, `--metadata-path/hotvect-offline-utils.log` contains Java logs, and `--metadata-path/stdout-stderr.log` contains raw subprocess stdout/stderr.

### SageMaker one-shot mode (audit/predict/evaluate/encode/performance-test)

The following flags enable running these remote commands on SageMaker. Every listed command supports one submitted job
and returns after submission.

- With `--job-parallelism > 1`, Hotvect enables **parallel one-shot mode** for `audit`, `predict`, and `encode` only.
  In that mode:
  - shard jobs write their final files directly under `--dest-path`
  - Hotvect writes `_SUBMISSION.json` under `--dest-path` at submission time
  - Hotvect writes `_SUCCESS` under `--dest-path` only after the full run is verified complete
  - detailed manifests and shard/job bookkeeping live under `--s3-output-base`

- `--sagemaker`: Enable SageMaker execution for `audit`, `predict`, `evaluate`, `encode`, `performance-test`.
- `--sagemaker-job-prefix`: **(required)** Valid SageMaker TrainingJobName prefix (no sanitization; invalid values error).
- `--sagemaker-config`: Path to SageMaker job definition template JSON (optional; otherwise loaded from `~/.hotvect/config.json` when `--sagemaker` is set).
- `--role-arn`: SageMaker job execution role ARN (CreateTrainingJob.RoleArn) (required for template-free mode).
- `--assume-role-arn`: AWS role ARN to assume for SageMaker submission (optional; defaults to using current AWS credentials).
- `--s3-output-base`: S3 base prefix used in template-free mode (`OutputDataConfig.S3OutputPath`).
- `--instance-type`: Primary instance type used in template-free mode (`ResourceConfig.InstanceType`). For ordered capacity fallback, use `HotvectSubmissionOptions.PreferredInstanceTypes` in the SageMaker job definition.
- `--volume-gb`: Override EBS volume size in GB (`ResourceConfig.VolumeSizeInGB`, default 30 if missing).
- `--max-runtime-seconds`: Override max runtime seconds (`StoppingCondition.MaxRuntimeInSeconds`, default 86400 if missing).
- `--training-image`: Training image override (`AlgorithmSpecification.TrainingImage`). This is the highest-precedence image setting. Prefer committed `sagemaker_training_job_definition.AlgorithmSpecification.TrainingImage` or an algorithm override JSON for reproducible image changes.
- `--source-s3-uri`: **(required)** S3 prefix to mount as the `source` channel.
- `--parameter-s3-uri`: S3 URI to a parameters ZIP (typically from `s3_uri_predict_parameters_zip` in a prior
  SageMaker train/backtest run). It is required for `audit` and `performance-test`, optional for `predict` and
  `encode`, and not used by `evaluate`.
- `hv evaluate --sagemaker`: point `--source-s3-uri` at a cached prediction file or part-file directory prefix. Reusing a prior `predict` output is the easiest way to benchmark `evaluate` without paying the `predict` cost again.
- `--job-parallelism`: Parallelize SageMaker execution across `N` shard jobs. Available only for `audit`, `predict`, and
  `encode`.
- `--verify`: Verify/finalize a previously submitted parallel run. Available only for `audit`, `predict`, and `encode`;
  it checks shard jobs and writes `_SUCCESS` when all of them completed successfully.
- `--no-wait`: Available for `audit`, `predict`, `encode`, and `performance-test`. It is a no-op convenience flag for
  a single job, which already returns after submission; for a parallel run, it skips waiting/finalization.
- `--compression`: only for **parallel** `predict` and `audit` runs (`--job-parallelism > 1`). It accepts `none`
  (default) or `gzip`; single-job submissions and `encode` reject non-default compression.

**Parallel one-shot rules**:

- `--job-parallelism > 1` requires `--sagemaker` and one of `audit`, `predict`, or `encode`.
- `--verify` requires `--sagemaker`, a prior parallel submission, and one of `audit`, `predict`, or `encode`.
- `--no-wait` requires `--sagemaker` and is not accepted by `evaluate`.
- `--ordered` cannot be combined with `--job-parallelism`. `--unordered` is allowed but redundant, and
  `--writer-num-shards` is allowed and applies within each parallel shard job.
- For parallel `predict` and `audit`, published files are written as zero-padded `part-<worker>-<localshard>.jsonl[.gz]` files such as `part-00003-00012.jsonl.gz`.

See also: [Parallel SageMaker One-Shot Runs](../../guides/parallel-sagemaker-one-shot/index.md).

## Examples

### Running an Audit

```bash
hv audit --algorithm-jar my_algorithm.jar --algorithm-name example-ranker --parameter-path parameters.zip --source-path data/input.jsonl --dest-path audit_output --ordered
```

This command performs feature transformation on the `input.jsonl` file using `example-ranker`, and saves the human-readable output under `audit_output/`, for example `audit_output/part-00000.jsonl`.

### Generating Predictions

```bash
hv predict --algorithm-jar my_algorithm.jar --algorithm-name example-ranker --source-path data/input.jsonl --dest-path predictions --parameter-path parameters.zip --ordered
```

This command generates predictions for the input data and saves them under `predictions/`, for example `predictions/part-00000.jsonl` in ordered mode.

### Evaluating Predictions

```bash
hv evaluate --source-path predictions --dest-path evaluation_results.json
```

This command evaluates the predictions in `predictions/` (or a single JSONL file) and saves the evaluation metrics to
`evaluation_results.json`. Complete online dimensions are derived from the prediction input automatically.


### Training a Model

```bash
hv train --algorithm-name example-document-ranker \
         --data-base-dir /path/to/training/data \
         --output-base-dir /path/to/output \
         --algorithm-jar my_algorithm.jar \
         --last-test-time 2000-01-15 \
         --algorithm-override config.json \
         --extra-jvm-args "-XX:MaxRAMPercentage=80,-XX:+UseG1GC"
```

This command trains `example-document-ranker` using the specified data, definition override, and explicit JVM
arguments.

### Running a Backtest

```bash
hv backtest --git-reference main --git-reference feature-improved-ranking \
           --algo-repo-url https://github.com/example-org/example-ranking-algorithm.git \
           --data-base-dir /path/to/test/data \
           --output-base-dir /path/to/backtest/results \
           --scratch-dir /tmp/backtest-workspace \
           --last-test-time 2000-01-04 \
           --sagemaker \
           --sagemaker-job-prefix example-backtest \
           --sagemaker-config sagemaker-backtest-config.json \
           --number-of-runs 3
```

This command submits one run for each of `2000-01-04`, `2000-01-03`, and `2000-01-02` per Git reference. It returns
the SageMaker job IDs without waiting for completion.

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
└── meta/my-algorithm@1.0.0/last_test_date_2000-01-15/
    ├── hv.log
    ├── result.json
    └── predict/
        ├── metadata.json
        ├── hotvect-offline-utils.log
        └── stdout-stderr.log
```

## `hv-ext`

The `hv-ext` tool provides extended utility commands for data analysis, format conversion, and result management that complement the core `hv` operations. This CLI is designed for auxiliary tasks that are commonly needed but are separate from the main ML pipeline operations.

### Command map

The `hv-ext` tool supports the following utility operations:

- **metrics**: Metrics utilities (quality + system), export, and plotting
- **catboost-convert**: Convert CatBoost encoded TSV data to JSONL format
- **config**: Show or initialize `~/.hotvect/config.json`
- **compare-jsonl**: Compare two JSONL files and identify differences between them
- **compare-equivalence**: Verify predict score/rank equivalence between two JSONL outputs
- **results**: List and download `result.json` runs from local `meta/` dirs or S3 prefixes (latest-only)
- **data-dependency**: Show or download training data dependencies required for local train/backtest operations
- **show-data-dependency**: Show data dependencies for SageMaker InputDataConfig construction

### Usage

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

### Commands

### 1. `metrics`

**Description**: Metrics utilities (quality + system), plus export and plotting helpers.

**Usage**:

```bash
hv-ext metrics <metrics-command> [options]
```

| Subcommand | Use it for | Output contract |
| --- | --- | --- |
| `compare-quality` | Fast central-value quality comparison, single-day or multi-day | Compares the central metric value; it does not turn confidence intervals into a statistical decision |
| `compare-system` | System-performance comparison | Use only when the benchmark contracts are comparable |
| `export` | Machine-readable evaluation table | Preserves structured metric estimates (`value`, optional `ci95_lower`/`ci95_upper`) |
| `plot` | Human-reviewable PDF and optional table | Requires `--relative-baseline`; includes uncertainty, evaluation/benchmark specification, provenance, and timing/cache information |

**Examples**:

```bash
# Multi-day quality comparison under meta dir
hv-ext metrics compare-quality \
  --output-base-dir ./backtest-results/meta \
  --control my-algorithm@1.0.0 \
  --treatment my-algorithm@1.0.1 \
  --from-test-date 2000-02-01 \
  --to-test-date 2000-02-14 \
  > comparison.json
```

For a reproducible plot, provide explicit result files and a baseline:

```bash
hv-ext metrics plot \
  --result-files baseline/result.json treatment/result.json \
  --relative-baseline <baseline-version> \
  --out comparison.pdf \
  --table-out comparison.json
```

If plotted records have different benchmark specifications, `plot` exits successfully but warns and omits all system
latency/throughput metrics. The remaining report is valid for quality and pipeline inspection, not a system-performance
comparison. See [Evaluation metrics and uncertainty](../evaluation-metrics/index.md).

#### `metrics export`

`export` writes a JSON table and always requires `--out`. Select input in exactly one of these ways:

- `--result-files <result.json...>` or `--result-glob <glob>` for explicit artifacts; or
- `--output-base-dir <meta-or-output-dir>` to discover results, optionally filtered by algorithm/date flags.

Explicit result files/globs take precedence over `--output-base-dir`. Structured quality estimates are preserved rather
than flattened.

```bash
hv-ext metrics export \
  --result-files baseline/result.json treatment/result.json \
  --metrics roc_auc ndcg_at_10 \
  --out metrics.json
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

### `config`

Show or initialize the local CLI configuration used by commands that accept directory defaults:

```bash
hv-ext config show
hv-ext config init \
  --data-base-dir /path/to/data \
  --output-base-dir /path/to/output \
  --scratch-dir /path/to/scratch
```

`config init` refuses to replace `~/.hotvect/config.json` unless `--force` is present. Inspect and edit an existing file,
or use `--force` for an intentional replacement. For a new file, provide all three directory flags for a non-interactive
setup; omit all three to enter values interactively. The initializer also accepts the EMS defaults shown by
`hv-ext config init --help`.

### `compare-equivalence`

Compare two prediction JSONL outputs for score and rank equivalence. The command writes JSON to stdout and exits
nonzero when the equivalence check fails.

```bash
hv-ext compare-equivalence baseline.predict/part-00000.jsonl treatment.predict/part-00000.jsonl \
  --score-eps 1e-6
```

Use `--allow-non-deterministic-tie-breaking` only when tied scores may legitimately change order. Add `--output <dir>`
to write `comparison.json` as an artifact.

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
    "baseline_field_01": "request_field_01",
    "baseline_field_02": "request_field_02",
    "baseline_field_03": "request_field_03",
    "baseline_field_04": "request_field_04"
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
  --from-date 2000-02-10 \
  --to-date 2000-02-15 \
  --algorithm-name-regex "^my-algorithm$" \
  --algorithm-version-regex "^1\\.2\\..*$"

# List S3 runs (latest-only) with job-name filter
hv-ext results ls s3://example-bucket/sagemaker-output/ \
  --from-date 2000-02-10 \
  --to-date 2000-02-15 \
  --algorithm-name-regex "^my-algorithm$" \
  --algorithm-version-regex "^1\\.2\\..*$" \
  --job-name-regex "^example-job-.*$"
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
  --from-date 2000-02-10 \
  --to-date 2000-02-15 \
  --algorithm-name-regex "^my-algorithm$" \
  --algorithm-version-regex "^1\\.2\\..*$"

# Include metadata and output data artifacts
hv-ext results download s3://example-bucket/sagemaker-output/ \
  --dest-base-dir ./results \
  --from-date 2000-02-10 \
  --to-date 2000-02-15 \
  --algorithm-name-regex "^my-algorithm$" \
  --algorithm-version-regex "^1\\.2\\..*$" \
  --job-name-regex "^example-job-.*$" \
  --include-metadata \
  --include-output-data
```

**JSON output schema (`results download`)**:

```json
{
  "s3_prefix": "s3://...",
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
        "s3": "s3://...",
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
  --from-date "2000-02-01" \
  --to-date "2000-02-01"

# 2. Compare performance between two algorithm versions
hv-ext metrics compare-system \
  ./perf-data/baseline/performance.json \
  ./perf-data/experiment/performance.json \
  > performance_comparison.json

# 3. Compare detailed prediction results
hv-ext compare-jsonl \
  ./perf-data/baseline/predictions/part-00000.jsonl \
  ./perf-data/experiment/predictions/part-00000.jsonl
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
- `--repo-url`: Git repository URL for the algorithm (for example, `https://github.com/example-org/example-algorithm.git`)
- `--git-reference`: Git reference (branch/commit) to analyze for data dependencies (single reference only)
- `--s3-base-dir`: S3 base directory where training data is stored (e.g., "s3://example-bucket/tables/")
- `--local-data-dir`: Local directory where data will be downloaded
- `--scratch-dir`: Directory for temporary JAR builds and git checkouts
- `--last-test-time`: Last test time in YYYY-MM-DD format (e.g., "2000-01-08")

**Optional Options - Download Control (mutually exclusive)**:
- `--download-all`: Download all dependencies
- `--download <name>`: Download specific dependency by data_prefix (repeatable)

**Optional Options - Other**:
- `--target {parameters,predict,evaluate}`: Select the dependency target. `evaluate` uses `test_data_spec`
  (default), `predict` uses `prediction_spec`, and `parameters` includes only the dependencies needed to prepare
  parameters.
- `--algorithm-override`: Path to JSON file containing algorithm configuration overrides. Uses the same patch semantics as `hv train` and `hv backtest`.
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
  --git-reference v2.0.0 \
  --s3-base-dir s3://example-bucket/tables \
  --local-data-dir ./training-data \
  --scratch-dir ./temp-build \
  --last-test-time 2000-01-08

# Download all dependencies
hv-ext data-dependency --download-all \
  --repo-url https://github.com/example-org/example-algorithm.git \
  --git-reference v2.0.0 \
  --s3-base-dir s3://example-bucket/tables \
  --local-data-dir ./training-data \
  --scratch-dir ./temp-build \
  --last-test-time 2000-01-08

# Download specific dependency with sampling
hv-ext data-dependency \
  --download example_training_data \
  --repo-url https://github.com/example-org/example-algorithm.git \
  --git-reference v2.0.0 \
  --s3-base-dir s3://example-bucket/tables \
  --local-data-dir ./training-data \
  --scratch-dir ./temp-build \
  --last-test-time 2000-01-08 \
  --sample-ratio 0.01

# Inspect dependencies for explicit prediction instead of evaluation
hv-ext data-dependency \
  --target predict \
  --repo-url https://github.com/example-org/example-algorithm.git \
  --git-reference v2.0.0 \
  --s3-base-dir s3://example-bucket/tables \
  --local-data-dir ./prediction-data \
  --scratch-dir ./temp-build \
  --last-test-time 2000-01-08

# Download with AWS role assumption
hv-ext data-dependency --download-all \
  --repo-url https://github.com/example-org/example-algorithm.git \
  --git-reference main \
  --s3-base-dir s3://example-bucket/tables \
  --local-data-dir ./data \
  --scratch-dir ./temp \
  --last-test-time 2000-01-08 \
  --role-arn arn:aws:iam::123456789012:role/s3-access-role

```

**Key Features**:
- **Safe Default**: Lists dependencies as JSON by default (no accidental downloads)
- **JSON plan**: stdout contains the plan; clone/build progress is written to stderr, so the JSON can be redirected or
  piped to `jq`.
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
- `--target {parameters,predict,evaluate}`: Select whether dependency analysis follows parameter preparation,
  `prediction_spec`, or `test_data_spec` (default: `evaluate`).
- `--algorithm-override`: Path to JSON file containing algorithm configuration overrides (repeatable).
- `-o`, `--output`: Output file path (default: stdout).

**Example**:

```bash
hv-ext show-data-dependency \
  --repo-url https://github.com/example-org/example-algorithm.git \
  --git-reference v2.0.0 \
  --scratch-dir ./temp-build \
  --last-test-time 2000-01-08 \
  -o dependencies.json

# Inspect the input required by prediction_spec
hv-ext show-data-dependency \
  --target predict \
  --repo-url https://github.com/example-org/example-algorithm.git \
  --git-reference v2.0.0 \
  --scratch-dir ./temp-build \
  --last-test-time 2000-01-08
```

## `hv-exp`

`hv-exp` is a small, **read-only** CLI for inspecting an experiment-management service,
such as slots, experiments, algorithms, algorithm parameters, and online evaluation results.

### Usage

```bash
hv-exp <subcommand> [options]
```

Examples:

```bash
hv-exp slot list
hv-exp slot get --slot-name my-slot
hv-exp experiment list --slot-name my-slot
hv-exp experiment results list --experiment-id 42
hv-exp experiment results show --experiment-id 42 --analysis-date 2000-01-15
hv-exp experiment results download --experiment-id 42
hv-exp algorithm list-active
hv-exp algorithm list-in-use
hv-exp algorithm list-in-use --slot-name my-slot
```

### Command map

All `hv-exp` commands emit JSON. Start with the narrowest query that answers the question; these operations are
read-only and make no experiment-management changes.

| Need | Command | Required selector |
| --- | --- | --- |
| Discover slots | `hv-exp slot list` | — |
| Inspect one slot’s default variant and active experiments | `hv-exp slot get` | `--slot-name` |
| List experiments, optionally in one slot | `hv-exp experiment list` | optional `--slot-name` |
| Inspect one experiment | `hv-exp experiment get` | `--experiment-id` |
| Read an experiment’s ramp-up history | `hv-exp experiment rampup-log` | `--experiment-id` |
| List default variants | `hv-exp default-variant list` | optional `--slot-name` |
| List all or active algorithms | `hv-exp algorithm list` / `list-active` | optional `--slot-name` |
| Find algorithms actually in use | `hv-exp algorithm list-in-use` | optional `--slot-name` |
| Read one algorithm definition | `hv-exp algorithm get` | `--algorithm-name`, `--algorithm-version` |
| List an algorithm’s parameter versions | `hv-exp algorithm parameter list` | `--algorithm-name`, `--algorithm-version` |
| Read one parameter artifact record | `hv-exp algorithm parameter get` | `--algorithm-parameter-id` |
| Inspect/download online evaluation partitions | `hv-exp experiment results <list|show|download>` | `--experiment-id` |

```bash
# Inspect an experiment and its current algorithm definition
hv-exp experiment get --experiment-id 42
hv-exp algorithm get --algorithm-name my-algorithm --algorithm-version 1.2.3

# Discover parameter records for that algorithm
hv-exp algorithm parameter list \
  --algorithm-name my-algorithm \
  --algorithm-version 1.2.3
```

### `algorithm list-in-use`

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

### `experiment results`

Inspect or download online evaluation result partitions stored in S3, with partitions under:

```text
experiment_id=<id>/last_date_of_analysis=<YYYY-MM-DD>/part-*.json.gz
```

Usage:

```bash
# list available analysis dates
hv-exp experiment results list --experiment-id 42

# stream one analysis date to stdout (decompressed JSONL)
hv-exp experiment results show --experiment-id 42 --analysis-date 2000-01-15

# download one analysis date
hv-exp experiment results download --experiment-id 42 --analysis-date 2000-01-15

# download all available analysis dates for the experiment
hv-exp experiment results download --experiment-id 42
```

`list` returns JSON with available `analysis_date` values and part counts. `show` writes the selected partition to
stdout as decompressed JSONL. `download` stores raw `part-*.json.gz` files under:

```text
<output-base-dir>/meta/online-evaluation-results/experiment_id=<id>/
```

When `--output-base-dir` is omitted, `download` uses `directories.output_base_dir` from `~/.hotvect/config.json`.
Each experiment download root also contains a `MANIFEST` file with local provenance for the downloaded partitions.
The S3 base prefix must come either from `--s3-base-prefix` or from
`experiment_management.online_results.slots.<slot>.s3_base_prefix` in your hotvect config.

### Authentication and configuration

By default, `hv-exp` reads experiment-management settings from `~/.hotvect/config.json` under the
`experiment_management` section (see the config reference).

You can also override the URL, token provider, and request timeouts on the command line:

```bash
hv-exp \
  --url https://experiments.example.com \
  --token-provider-command "printenv EXAMPLE_TOKEN" \
  --token-provider-ttl-ms 3600000 \
  --connect-timeout-seconds 5 \
  --read-timeout-seconds 15 \
  slot list
```

If no timeout overrides are provided, `hv-exp` uses the values from `~/.hotvect/config.json` when present, and
otherwise uses a 5 second connect timeout and a 15 second read timeout.

## Output and ordering notes

- **Ordering**: `encode` is ordered by default. For `audit` and `predict`, pass `--ordered` when output must preserve
  input order; otherwise output may be unordered and sharded.
- **Defaults**: If certain paths are not provided (like `--metadata-path` or `--dest-path`), the tool uses default paths based on the algorithm name and command.
- **Output Locations**:
    - **Transformed Data**: For commands that transform data (for example `audit`, `encode`, `predict`), output is
      saved under `--dest-path`, which is a directory containing `part-*` files.
    - **Metadata**: Operation metadata, including timing information, algorithm version, and other details, is saved to `--metadata-path/metadata.json` (logs: `hv.log`, `hotvect-offline-utils.log`, `stdout-stderr.log`).
- **Extended Utilities**: The `hv-ext` tool complements `hv` by providing data analysis and management utilities that are commonly needed but separate from core ML pipeline operations.
