---
title: CLI reference
description: Canonical hv command tree and compatibility command reference
tags: [cli, reference, commands, usage, tools]
difficulty: intermediate
prerequisites:
  - hotvect Python package installed
  - Algorithm JAR available (for most commands)
  - Basic understanding of hotvect concepts
related_docs:
  - ../../guides/hv-qa-release-validation/index.md
  - ../../guides/feature-audits/index.md
  - ../../guides/debug-feature-engineering/index.md
  - ../../concepts/index.md
related_commands:
  - hv qa candidate start
  - hv qa evaluate
  - hv qa criteria
  - hv algorithm audit
  - hv algorithm train
  - hv algorithm predict
  - hv algorithm backtest
  - hv-ext compare-jsonl
next_steps:
  - Run your first audit
  - Train a model
  - Compare algorithm versions
  - Validate a release with hv qa
---

# CLI reference

The human-facing command is `hv`. Its top-level namespaces represent user domains rather than internal Python
packages:

```text
hv
├── algorithm audit|encode|predict|evaluate|generate-state|train|backtest|performance-test|serve
├── worker serve
├── status sagemaker|backtest
├── qa
│   ├── candidate start|resume|status
│   ├── evaluate
│   ├── criteria list|describe
│   └── utils compare-predictions
├── ems slot|experiment|default-variant|algorithm ...
├── docs list|search|read
├── config show|init
├── metrics compare-quality|compare-system|export|plot
├── results ls|download
└── data
    └── dependencies inspect|download
```

`hv-mcp` remains a separate MCP-server integration entrypoint. `catboost_train` and `sagemaker-entrypoint` remain
container/runtime entrypoints.

!!! note "Compatibility commands"
    Existing scripts continue to work with direct algorithm commands (`hv audit`, `hv train`, and so on) and the
    `hv-qa`, `hv-exp`, and migrated `hv-ext` commands, but those invocations print a warning with their canonical
    replacement. New guides and the detailed references below use the canonical forms above. `hv-ext compare-jsonl`
    and `hv-ext catboost-convert` remain low-level extension commands without a replacement under `hv`.
    `hv-qa criteria evaluate` remains available only as a legacy diagnostic interface; ordinary QA workflows use
    `hv qa candidate start --criteria ...` or `hv qa evaluate --criteria ...`.

Use `<cli> --help` and `<cli> <command> --help` as the exact reference for the installed checkout. This page explains
the contracts and output shapes that help text alone does not capture.

This is a lookup page, not a first-run tutorial. If Hotvect is new to you, run the
[example product algorithms](../../guides/first-run/index.md) first. Then return here for one command's exact contract.

## Command map

- **`hv algorithm`**: Algorithm development, pipeline execution, backtests, and local serving.
- **`hv worker`**: Worker-runtime HTTP debugging utilities.
- **`hv status`**: Monitor remote SageMaker jobs using lifecycle state and structured CloudWatch progress events.
- **`hv qa`**: Durable release-quality validation and release-evidence decisions.
- **`hv ems`**: Read-only Experiment Management System inspection and online-result retrieval.
- **`hv docs`**: Search and read the bundled Hotvect docs as JSON.
- **`hv config`**: Show or initialize `~/.hotvect/config.json`.
- **`hv metrics`**: Compare, export, and plot evaluation and system metrics.
- **`hv results`**: Inventory and download local or S3 `result.json` artifacts.
- **`hv qa utils compare-predictions`**: Check prediction score and ranking equivalence.
- **`hv-ext compare-jsonl`**: Low-level generic JSONL structural comparison.
- **`hv data dependencies inspect|download`**: Inspect declared datasets or materialize them locally.

!!! note "SageMaker support (quick summary)"
    Hotvect supports running on SageMaker in two ways:

    - **Pipelines:** `hv algorithm train` and `hv algorithm backtest` can execute on SageMaker (submit jobs and return immediately).
      A backtest using `--prewarm` submits all cache-prewarm jobs together and waits for them before it submits normal
      backtest jobs.
    - **One-shot remote commands:** `hv algorithm audit`, `hv algorithm predict`, `hv algorithm encode`, `hv algorithm evaluate`, and `hv algorithm performance-test` can also execute on SageMaker via `--sagemaker`.

    For flags and required S3 inputs, see [SageMaker one-shot mode](#sagemaker-one-shot-mode-auditpredictevaluateencodeperformance-test).

### Remote execution status

`hv status` combines SageMaker lifecycle state with structured `HOTVECT_STATUS` events emitted to CloudWatch by
Hotvect offline tasks. It does not parse ordinary application logs and does not poll metadata in S3. The same events
are archived in `progress.jsonl` with the job metadata when the job finishes; that file is not the live transport.

Monitor one SageMaker training job:

```bash
hv status sagemaker --job-name ml-exp-example --follow
```

Monitor every job recorded by a remote backtest submission:

```bash
hv status backtest \
  --submission output/meta/_backtest_submissions/<run-id>/backtest_submission_manifest.json \
  --follow
```

Both commands accept `--region`, `--assume-role-arn`, `--poll-seconds`, and `--output human|json`. JSON output is a
single snapshot and therefore cannot be combined with `--follow`. Full unstructured job output remains available
through the SageMaker console and standard AWS CloudWatch tooling. The selected AWS identity needs
`sagemaker:DescribeTrainingJob`, `logs:StartQuery`, and `logs:GetQueryResults` access.

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
hv <namespace> <operation> [options]
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
hv <namespace> <operation> -h
```

Unknown flags are rejected. Commands that support raw JVM passthrough accept it only after an explicit `--` separator:

```bash
hv algorithm audit ... --parameter-path params.zip -- -Xmx8g -Dfoo=bar
```

Passthrough is supported for the Java wrapper commands under `hv algorithm` (`audit`, `encode`, `predict`,
`generate-state`, `performance-test`) and for `hv algorithm serve`. `train` and `backtest` use `--extra-jvm-args`
instead, and `hv worker serve` rejects passthrough arguments after `--`.

Choose exactly one heap cap wherever you pass JVM arguments: `-Xmx...` **or** `-XX:MaxRAMPercentage=...`. Hotvect
rejects duplicates and the combination of both styles. If neither is supplied, pipeline and Java wrapper commands use
`-XX:MaxRAMPercentage=80`; runtime commands also add `-XX:+ExitOnOutOfMemoryError`.

## `hv qa` release QA

`hv qa` is the automated evaluator in an engineering loop: it derives validation stages from the selected release
objective, executes them, and emits a machine-readable release-readiness verdict. It does not publish artifacts or
mutate an experiment-management service. Use the
[release QA guide](../../guides/hv-qa-release-validation/index.md) for the stage model, statistical interpretation, and
rollout boundary.

| Command | Contract |
| --- | --- |
| `hv qa candidate start` | Create a run for exactly one control source and one or more treatments, then execute requested stages. |
| `hv qa candidate resume <run-id>` | Reuse the frozen scenario through `--until <stage>` or exactly one `--stage <stage>`. It cannot change refs, dates, or criteria. |
| `hv qa candidate status <run-id>` | Print machine-readable run state. |
| `hv qa evaluate` | Judge a pre-existing multi-day proof directory without run state or config lookup. |
| `hv qa criteria list|describe` | Inspect built-in release criteria. |

Start requires exactly one control source and at least one treatment:

```bash
hv qa candidate start \
  --control baseline-ref \
  --treatment candidate-ref \
  --repo /path/to/algorithm-repository \
  --criteria noninferiority \
  --until multi_day_backtest
```

| Option group | Flags | Contract |
| --- | --- | --- |
| Control | `--control <ref>` or `--prod-default-of-slot-as-control <slot>` | Exactly one. The latter reads and freezes the current production default. |
| Treatments | Repeat `--treatment <ref>` | One or more, in the order used by every repeated per-treatment flag. |
| Repositories | `--repo`, `--control-repo`, repeat `--treatment-repo` | `--repo` is the shared default. Without it, specify control plus exactly one treatment repository for every treatment. |
| Shared parameter/source | `--parameter-source`, `--performance-source-path` | Use only when the resource is truly common. Do not combine with the per-reference form. `exact` rejects manual parameter sources. |
| Per-reference parameter/source | `--control-parameter-source`, repeat `--treatment-parameter-source`; `--control-performance-source-path`, repeat `--treatment-performance-source-path` | Every repeated value must match treatment order exactly. Per-reference parameters are for `system_performance`. |
| Offline decision | `--last-test-date`, `--days`, `--criteria` | `--last-test-date` defaults to the latest contiguous control test-data window. `--days` defaults to 7 and can be overridden in `qa.run.defaults`. The default criterion is `noninferiority`. |
| Execution boundary | `--until`, `--stage`, `--work-dir` | `--until` defaults to `multi_day_backtest`; `--stage` executes exactly one eligible stage. |

Control and treatment may both use the ref name `main` when their repositories differ. `hv qa` isolates their builds
and output state by role and repository.

`hv qa evaluate` always judges a multi-day proof and requires at least two dates:

```bash
hv qa evaluate \
  --criteria noninferiority \
  --last-test-date 2000-01-08 \
  --days 2 \
  --proof-dir /path/to/proof \
  --output /path/to/judgment.json \
  --pretty
```

The proof layout is `<proof-dir>/<date>/control/result.json` and
`<proof-dir>/<date>/treatment/result.json` for every requested date. It pairs `evaluate.<metric>.value` by date;
separate result-level confidence intervals are not pooled. See the guide for the `quality_statistical_basis` output
and superiority's Bonferroni correction.

## `hv algorithm` commands

The commands below use canonical forms such as `hv algorithm audit`. The direct aliases `hv audit`, `hv encode`,
`hv predict`, `hv generate-state`, `hv evaluate`, `hv train`, `hv backtest`, `hv performance-test`, and `hv serve`
remain supported for existing scripts.

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

### 1. `audit`

**Description**: Generate human-readable audit data showing feature transformations and calculations. Audit requires the
predict-parameters ZIP used by the algorithm, then writes readable JSONL for debugging and comparison.

**SageMaker**: Supported via one-shot mode (`--sagemaker`). See [SageMaker one-shot mode](#sagemaker-one-shot-mode-auditpredictevaluateencodeperformance-test).

**Usage**:

```bash
hv algorithm audit --algorithm-jar <path_to_jar> --algorithm-name <algorithm_name> --parameter-path <predict-parameters.zip> [options]
```

**Options**: Same as the common Java command options (see Common Options section below).

**Debug option**:

- `--include-feature-store-responses`: Include feature-store responses in each audit row under
  `additional_properties.__feature_store_responses`. Use this only for focused debugging because it can make audit
  output substantially larger.

**Example**:

```bash
hv algorithm audit --algorithm-jar my_algorithm.jar --algorithm-name example-ranker --parameter-path parameters.zip --source-path input_data.jsonl --dest-path audit_output --ordered
```

### 2. `performance-test`

**Description**: Benchmark algorithm performance and measure latency percentiles under a controlled request rate.

**Workload mode**: `hv algorithm performance-test` defaults to **realtime** workload mode, even though the input rows are offline. This is intentional: performance-test is generally used to measure serving latency and should normally exercise the algorithm's `realtime` runtime config. Use `--workload-mode batch` only when you explicitly want to benchmark the batch path. `hv algorithm predict` continues to use batch workload mode.

**Important**: `hv algorithm performance-test` runs a warmup first, then (by default) paces the measurement runs at `0.8 × warmup_mean_throughput` to reduce queueing effects and make p99/p999 more stable across runs. This means the reported throughput is **not** “max throughput” by default.

**Threading default**: if `--max-threads` is omitted, `hv algorithm performance-test` defaults to:

- `2` threads on machines with `>=4` physical cores
- otherwise `1`

Pass an explicit `--max-threads` to override that heuristic, or `--max-threads 0` to avoid passing the flag to Java and let the JAR decide.

**SageMaker**: Supported via one-shot mode (`--sagemaker`). See [SageMaker one-shot mode](#sagemaker-one-shot-mode-auditpredictevaluateencodeperformance-test).

**Local runtime state**: Performance tests make private runtime storage available under the JVM temporary directory.
Directories are allocated lazily when an algorithm uses `LocalStateStorage`, and are not written to task metadata or
output directories. Algorithms that depend on this capability must declare `requires_local_state_storage=true`.

**Usage**:

```bash
hv algorithm performance-test --algorithm-jar <path_to_jar> --algorithm-name <algorithm_name> [options]
```

**Options**: Same as the common Java command options (see Common Options section below).

**Performance-test-only options**:
- `--sample-pool-size`: Number of decoded requests to keep in the in-memory sample pool before measured repeats. This
  separates "how many distinct requests are held in RAM" from `--samples`, which controls how many measured requests
  are executed.
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
hv algorithm encode --algorithm-jar <path_to_jar> --algorithm-name <algorithm_name> [options]
```

**Options**: Same as the common Java command options, with `--dest-schema-path` being particularly relevant for encoding operations.

For local multi-output encoding, `--source-dest-mappings <json-file>` replaces both `--source-path` and `--dest-path`.
The file contains an array of mappings, and Hotvect processes them sequentially in one Java process so decoder, encoder,
and feature-dependency initialization happens once:

```json
[
  {"sources": ["data/dt=2000-02-15"], "dest": "encoded/dt=2000-02-15"},
  {"sources": ["data/dt=2000-02-16"], "dest": "encoded/dt=2000-02-16"}
]
```

Each mapping requires a non-empty `sources` array and a unique `dest`. The option is local-only and cannot be combined
with `--source-path`, `--dest-path`, or `--sagemaker`. `--dest-schema-path` remains one shared schema-description output
for the encode invocation.

### 4. `predict`

**Description**: Generate model predictions on test/validation data. Pass a parameter ZIP when the algorithm requires
one; stateless algorithms can predict without it. Output includes prediction scores and can be used for evaluation or
serving.

**SageMaker**: Supported via one-shot mode (`--sagemaker`). See [SageMaker one-shot mode](#sagemaker-one-shot-mode-auditpredictevaluateencodeperformance-test).

**Usage**:

```bash
hv algorithm predict --algorithm-jar <path_to_jar> --algorithm-name <algorithm_name> [options]
```

**Options**: Same as the common Java command options (see Common Options section below).

**Additional Options**:
- `--log-features`: Enable feature logging during prediction for debugging composite algorithms (optional; v10+).
- `--include-feature-store-responses`: Include feature-store responses in output rows under
  `additional_properties.__feature_store_responses`. Use it for a small debug sample, not routine backtests.

Ranking output stays in request-action order and records final position in each result's `rank`; TopK output follows
the returned decision order. See [Ranking and prediction contracts](../ranking-and-prediction-contracts/index.md) for
the exact JSON schema, metadata precedence, and adapter rules.

### 5. `evaluate`

**Description**: Calculate quality metrics from prediction results. The evaluator computes offline metrics and derives
online dimensions only when that dimension is complete across the scored rows.

**Usage**:

```bash
hv algorithm evaluate --source-path <predictions_file> --dest-path <evaluation_output> [options]
```

**Options**:

- `--source-path`: **(required)** Path to the prediction results. This can be a single prediction file, or a prediction directory containing supported text outputs such as `part-*`, `part-*.gz`, `shard_*.jsonl`, `*.json`, `*.jsonl`, `*.ndjson`, `*.json.gz`, or `*.jsonl.gz`. Common sidecars such as `_metadata.json` and hidden/underscore files are ignored; matching files are parsed directly during evaluation, so malformed matching files fail the run.
- `--dest-path`: **(required)** Path where the evaluation metrics will be saved as JSON.

**Example**:

```bash
hv algorithm evaluate --source-path predictions --dest-path evaluation_results.json
```

### 6. `train`

**Description**: Train a machine learning model using the hotvect pipeline. Orchestrates the complete ML training pipeline including data encoding, model training, and output generation using AlgorithmPipeline.

**Usage**:

```bash
hv algorithm train --algorithm-name <algorithm_name> --data-base-dir <data_dir> --output-base-dir <output_dir> --algorithm-jar <jar_path> --last-test-time <date> [options]
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
- `--max-threads`: Max threads for hotvect encode/predict (0 = don't pass; JAR decides). Standalone `hv algorithm performance-test` uses a different default when omitted; see [Common Options](#common-options).
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
hv algorithm train --algorithm-name example-document-ranker \
         --data-base-dir /path/to/training/data \
         --output-base-dir /path/to/output \
         --algorithm-jar /path/to/algorithm.jar \
         --last-test-time 2000-01-15 \
         --algorithm-override /path/to/override.json \
         --extra-jvm-args "-XX:MaxRAMPercentage=80,-XX:+UseG1GC"
```

**SageMaker Example** (submits and returns):

```bash
hv algorithm train --algorithm-name example-document-ranker \
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
hv algorithm train --algorithm-name example-document-ranker \
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

**Description**: Run backtest on git references to compare algorithm performance across different versions or configurations. SageMaker backtests submit normal jobs without waiting for their completion; `--prewarm` submits and waits for all cache-prewarm jobs before normal job submission.

**Usage**:

```bash
hv algorithm backtest (--git-reference <git_ref> | --backtest-config <config_file>) --algo-repo-url <repo_url> --data-base-dir <data_dir> --output-base-dir <output_dir> --scratch-dir <scratch_dir> --last-test-time <date> [options]
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

- `--algorithm-override`: Path to JSON file containing algorithm configuration overrides (repeatable). If one override is provided, it applies to all git references. If multiple are provided, they apply to git references in order. Overrides use the same patch semantics as `hv algorithm train`.
- `--algorithm-override-reason`: Human-readable reason recorded for the corresponding `--algorithm-override`
  (repeatable). Requires `--algorithm-override`; supplying it alone exits with status 2. If one reason accompanies a
  single override that applies to multiple git references, the same reason is recorded for all of them.
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
- `--prewarm`: Pre-populate the encode partition cache before normal backtest jobs are submitted. This is available only
  for remote SageMaker backtests. Without an explicit count, Hotvect automatically selects the minimum required
  compatible encoding-parameter contexts, submits every planned one-instance `encode-cache` job together, then waits
  before submitting the normal backtest. Requires an `s3://` `--cache`; not supported with `--cache-refresh`.
- `--prewarm-instance-count <n>`: Number of one-instance prewarm jobs available per git reference. Requires `--prewarm`. A
  count below the required compatible contexts fails before submission; a larger count may use additional compatible
  contexts. Hotvect submits every planned job together.
- `--prewarm-instance-type`: SageMaker instance type for encode partition cache prewarm jobs. Requires `--prewarm`.
  It replaces any configured prewarm `PreferredInstanceTypes` list; prewarm uses that type only.
- `--performance-test-samples`: Pin pipeline perf-test sample size for comparability (passes `--samples` to Java perf-test).
- `--performance-test-sample-pool-size`: Pin the decoded replay pool separately from measured executions. Use it with
  `--performance-test-samples` when system-performance results must be comparable.
- `--auto-attach-data-default-s3-base`: Default S3 base URI used when dependencies do not specify explicit `s3_uri` (auto-attach is always enabled in SageMaker mode).
- `--auto-attach-data-environment`: Preferred environment key when dependency `s3_uri` is a map (default: `production`). Keys are matched case-insensitively with fallbacks (`production`, `prod`, `test`, `staging`, then first map entry).

**SageMaker submission metadata**:

When SageMaker mode is enabled, each `hv algorithm backtest` invocation writes local submission metadata under:

```text
<output-base-dir>/meta/_backtest_submissions/<run_id>/
  backtest_submission_manifest.json
  backtest_submission_status.json
```

Each run gets its own `<run_id>`, so repeated backtests do not overwrite prior submission records. `backtest_submission_status.json` is a submission-time snapshot, not a live SageMaker polling result.

**Examples**:

**Basic Local Backtest**:
```bash
hv algorithm backtest --git-reference main --git-reference feature-branch \
           --algo-repo-url https://github.com/example-org/example-algorithm.git \
           --data-base-dir /path/to/data \
           --output-base-dir /path/to/output \
           --scratch-dir /tmp/backtest \
           --last-test-time 2000-01-04 \
           --n-process 4
```

**SageMaker Backtest**:
```bash
hv algorithm backtest --git-reference main \
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
hv algorithm backtest --git-reference main \
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

**SageMaker Backtest with Encode Partition Prewarm**:
```bash
hv algorithm backtest --git-reference main \
           --algo-repo-url https://github.com/example-org/example-algorithm.git \
           --output-base-dir /path/to/output \
           --scratch-dir /tmp/backtest \
           --last-test-time 2000-08-05 \
           --sagemaker-config sagemaker-config.json \
           --auto-attach-data-default-s3-base s3://example-bucket/tables/ \
           --cache s3://example-bucket/hotvect-cache/ \
           --cache-scope hyperparam \
           --prewarm
```

**Backtest with Local Cache (local execution only)**:
```bash
hv algorithm backtest --git-reference main \
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
hv algorithm backtest --backtest-config backtest-refs.json \
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
hv algorithm generate-state --algorithm-jar <path_to_jar> --algorithm-name <algorithm_name> --source-path <state_input_json> [options]
```

**Options**: Same as the common Java command options (see Common Options section below).

**Example**:

```bash
hv algorithm generate-state --algorithm-jar my_algorithm.jar --algorithm-name example-ranker --source-path '{"training_data":["file1","file2"]}' --dest-path state.output
```

### `serve`

**Description**: Serve the **full algorithm** over HTTP for local debugging. This runs the Java algorithm runtime, so
request decoding, feature extraction, algorithm wiring, and output formatting happen in the JVM. It does not reproduce
the containing application's request adapter or operational behavior. Headless mode uses the minimal
`hotvect-algorithm-serve` JAR; add `--ui` to use the Demo UI extension on the same serving core.

**Usage**:

```bash
hv algorithm serve --algorithm-jar <path_to_jar> --algorithm-name <algorithm_name> --parameter-path <params_zip> --port <port> [options]
hv algorithm serve --local-runtime-config <local_runtimes.json> --port <port> [options]
hv algorithm serve --ems-url <url> --ems-slot <slot> --port <port> [options]
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

- `--ems-url` and `--ems-slot` load selected algorithm metadata from a configured EMS endpoint instead of a local JAR.
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
- `hv algorithm serve` injects `-XX:MaxRAMPercentage=80` when you do not pass an explicit heap cap (`-Xmx...` or `-XX:MaxRAMPercentage=...`).
- `hv algorithm serve` also injects `-XX:+ExitOnOutOfMemoryError` unless that exact flag is already present.
- Extra JVM args must be passed after an explicit `--` separator (for example `hv algorithm serve ... -- -Xmx4g`). Use an explicit heap flag when you want to take control of heap sizing.

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
- `--max-threads`: Max worker threads for Hotvect Java execution. For `hv algorithm performance-test`, if omitted, `hv` defaults to `2` threads on machines with `>=4` physical cores (else `1`). Pass an explicit value to override, or `0` to avoid passing `--max-threads` and let the JAR decide.
- `--ordered`, `--unordered`, `--writer-num-shards`: Output controls for `audit`, `predict`, and `encode`.
  `--ordered` preserves input order and writes one part file; `--unordered` permits parallel writing of part files.
  The flags are mutually exclusive, and `--writer-num-shards > 1` cannot be combined with `--ordered`. By default,
  `audit` and `encode` write ordered output, while `predict` writes unordered output; an effective algorithm definition
  can override those task defaults. Pass `--ordered` for a row-for-row predict comparison that needs
  `part-00000.jsonl`.
- `--target-rps`: Performance-test only. Fixed target requests/sec (optional).
- `--target-throughput-fraction`: Performance-test only. Fraction of warmup mean throughput to use as target requests/sec (optional; default `0.8`, `0` disables pacing).
- JVM passthrough: for Java wrapper commands, extra JVM args must follow an explicit `--` separator. Example: `hv algorithm predict ... -- -Xmx8g -Dfoo=bar`.

Choose exactly one heap cap: `-Xmx...` or `-XX:MaxRAMPercentage=...`. Passing both (or repeating either style)
fails fast; when neither is set, Hotvect uses `-XX:MaxRAMPercentage=80`.

**Note**: Generally, if the command transforms data, the actual transformed output is stored in the `--dest-path`, while metadata—such as timing information, algorithm version, and other operation details—is stored in `--metadata-path/metadata.json`. Encode metadata records encoder and decoder implementation class names: named classes use canonical names, while lambda and anonymous implementations use their JVM binary names. For debugging, `--metadata-path/hv.log` contains Python CLI logs, `--metadata-path/hotvect-offline-utils.log` contains Java logs, and `--metadata-path/stdout-stderr.log` contains raw subprocess stdout/stderr.

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
- `hv algorithm evaluate --sagemaker`: point `--source-s3-uri` at a cached prediction file or part-file directory prefix. Reusing a prior `predict` output is the easiest way to benchmark `evaluate` without paying the `predict` cost again.
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
hv algorithm audit --algorithm-jar my_algorithm.jar --algorithm-name example-ranker --parameter-path parameters.zip --source-path data/input.jsonl --dest-path audit_output --ordered
```

This command performs feature transformation on the `input.jsonl` file using `example-ranker`, and saves the human-readable output under `audit_output/`, for example `audit_output/part-00000.jsonl`.

### Generating Predictions

```bash
hv algorithm predict --algorithm-jar my_algorithm.jar --algorithm-name example-ranker --source-path data/input.jsonl --dest-path predictions --parameter-path parameters.zip --ordered
```

This command generates predictions for the input data and saves them under `predictions/`, for example `predictions/part-00000.jsonl` in ordered mode.

### Evaluating Predictions

```bash
hv algorithm evaluate --source-path predictions --dest-path evaluation_results.json
```

This command evaluates the predictions in `predictions/` (or a single JSONL file) and saves the evaluation metrics to
`evaluation_results.json`. Complete online dimensions are derived from the prediction input automatically.


### Training a Model

```bash
hv algorithm train --algorithm-name example-document-ranker \
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
hv algorithm backtest --git-reference main --git-reference feature-improved-ranking \
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

## Utility commands

Metrics, result management, prediction comparison, and data-dependency commands are canonical `hv` commands.
Migrated `hv-ext` commands remain available for existing scripts but print a warning with their canonical replacement.
`hv-ext compare-jsonl` and `hv-ext catboost-convert` remain low-level extension commands.

### Command map

The `hv` tool supports the following utility operations:

- **metrics**: Metrics utilities (quality + system), export, and plotting
- **config**: Show or initialize `~/.hotvect/config.json`
- **hv qa utils compare-predictions**: Verify predict score/rank equivalence between two JSONL outputs
- **hv-ext compare-jsonl**: Compare two arbitrary JSONL files and identify structural differences
- **results**: List and download `result.json` runs from local `meta/` dirs or S3 prefixes
- **data dependencies inspect|download**: Inspect or materialize training data required for local train/backtest operations

### Usage

The general syntax for using the utility commands is:

```bash
hv <command> [options]
```

To see the list of available commands, run:

```bash
hv --help
```

To get help on a specific command, use:

```bash
hv <command> -h
```

### Commands

### 1. `metrics`

**Description**: Metrics utilities (quality + system), plus export and plotting helpers.

**Usage**:

```bash
hv metrics <metrics-command> [options]
```

| Subcommand | Use it for | Output contract |
| --- | --- | --- |
| `compare-quality` | Fast central-value quality comparison, single-day or multi-day | Compares the central metric value; it does not turn confidence intervals into a statistical decision |
| `compare-system` | System-performance comparison | Use only when the benchmark contracts are comparable |
| `export` | Machine-readable evaluation table | Preserves structured metric estimates (`value`, optional `ci95_lower`/`ci95_upper`) |
| `plot` | Human-reviewable PDF and optional table | Requires `--relative-baseline`; includes uncertainty, evaluation/benchmark specification, provenance, and timing/cache information |

Directory discovery for all four subcommands can be narrowed with `--algorithm-name-pattern`,
`--algorithm-version-pattern`, `--from-test-date`, and `--to-test-date`. `export` additionally accepts `--versions`
and `plot` additionally accepts `--algorithm-ids`; each filters the records and defines their display order. Use
`--metrics` to select exact metric names.

For `plot`, `--relative-baseline` accepts either a plotted version or `online:<dimension>`. Optional
`--baseline-description <text>` and repeatable `--treatment-description VERSION=TEXT` values are rendered in the PDF
summary; they annotate the report and do not change the input data or calculations.

**Examples**:

```bash
# Multi-day quality comparison under meta dir
hv metrics compare-quality \
  --output-base-dir ./backtest-results/meta \
  --control my-algorithm@1.0.0 \
  --treatment my-algorithm@1.0.1 \
  --from-test-date 2000-02-01 \
  --to-test-date 2000-02-14 \
  > comparison.json

# Plot exact algorithm_ids against a chosen baseline
hv metrics plot \
  --result-glob './backtest-results/meta/**/result.json' \
  --algorithm-ids control@1.0.0 candidate@1.0.0 \
  --relative-baseline control@1.0.0 \
  --treatment-description candidate@1.0.0='Candidate with reranking enabled' \
  --metrics roc_auc p95 \
  --out metrics-plots.pdf
```

For a reproducible plot, provide explicit result files and a baseline:

```bash
hv metrics plot \
  --result-files baseline/result.json treatment/result.json \
  --relative-baseline <baseline-version> \
  --out comparison.pdf \
  --table-out comparison.json
```

If plotted records have different benchmark specifications, `plot` exits successfully but warns and omits all system
latency/throughput metrics. The remaining report is valid for quality and pipeline inspection, not a system-performance
comparison. See [Evaluation metrics and uncertainty](../evaluation-metrics/index.md).

`--assemble-latest-sections` builds a compare dataset from the latest valid quality, system-performance, and pipeline
sections for each algorithm/day under `--output-base-dir`. It is intended for `hv results download` destinations,
especially the default `runs-with-links` layout:

```bash
hv metrics plot \
  --output-base-dir ./backtest-results \
  --assemble-latest-sections \
  --algorithm-ids my-algorithm@1.0.0 my-algorithm@1.0.1 \
  --relative-baseline my-algorithm@1.0.0 \
  --out compare.pdf
```

#### `metrics export`

`export` writes a JSON table and always requires `--out`. Select input in exactly one of these ways:

- `--result-files <result.json...>` or `--result-glob <glob>` for explicit artifacts; or
- `--output-base-dir <meta-or-output-dir>` to discover results, optionally filtered by algorithm/date flags.

Explicit result files/globs take precedence over `--output-base-dir`. Structured quality estimates are preserved rather
than flattened.

```bash
hv metrics export \
  --result-files baseline/result.json treatment/result.json \
  --metrics roc_auc ndcg_at_10 \
  --out metrics.json
```

The report retains the source and override metadata for each selected section. Final validity is conservative across
all contributing sections: any supplied or missing override provenance, dirty code, or non-exact git tag makes the
algorithm result non-final. If contributing sections report different code revisions or algorithm parameters, report
generation fails instead of combining incompatible subjects.

### Low-level `hv-ext catboost-convert`

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

### `config` → `hv config`

Show or initialize the local CLI configuration used by commands that accept directory defaults:

```bash
hv config show
hv config init \
  --data-base-dir /path/to/data \
  --output-base-dir /path/to/output \
  --scratch-dir /path/to/scratch
```

`config init` refuses to replace `~/.hotvect/config.json` unless `--force` is present. Inspect and edit an existing file,
or use `--force` for an intentional replacement. For a new file, provide all three directory flags for a non-interactive
setup; omit all three to enter values interactively. The initializer also accepts the EMS defaults shown by
`hv config init --help`.

### `hv qa utils compare-predictions`

Compare two prediction JSONL outputs for score and rank equivalence. The command writes JSON to stdout and exits
nonzero when the equivalence check fails.

Each `result` item must contain a finite `score`, integer `rank`, and an action ID. Use top-level `action_id` for new
output; the comparator also accepts the older `additional_properties.action_id` shape.

```bash
hv qa utils compare-predictions baseline.predict/part-00000.jsonl treatment.predict/part-00000.jsonl \
  --score-eps 1e-6
```

Use `--allow-non-deterministic-tie-breaking` only when tied scores may legitimately change order. Add `--output <dir>`
to write `comparison.json` as an artifact.

### `hv-ext compare-jsonl`

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
- `hv results ls`: list matching `result.json` runs (local `meta/` dir or S3 prefix)
- `hv results download`: download selected S3 runs into a local layout

`hv results ls` is **latest-only**:
- for each `(test_date, algorithm_id)`, only the newest run is returned.
- S3 freshness is based on S3 `LastModified`; local freshness is based on local `result.json` mtime.

`hv results download` is also **latest-only for selection**:
- for each `(test_date, algorithm_id)`, only the newest matching S3 run is selected.
- selected runs are materialized in the `runs-with-links` layout.

#### 4.1 `results ls`

**Usage**:

```bash
hv results ls <location> [options]
```

- `<location>` can be:
  - local meta directory (for example `./backtest-results/meta`)
  - S3 prefix (for example `s3://example-bucket/sagemaker-output/`)

**Common options**:
- `--from-date`, `--to-date` (inclusive; `YYYY-MM-DD`)
- `--algorithm-name-regex`
- `--algorithm-version-regex`

Regex filters use search semantics on the parsed field value. Anchors therefore apply to that field alone; for example,
`^my-algorithm$` matches the exact algorithm name rather than the surrounding S3 result path.

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
hv results ls ./backtest-results/meta \
  --from-date 2000-02-10 \
  --to-date 2000-02-15 \
  --algorithm-name-regex "^my-algorithm$" \
  --algorithm-version-regex "^1\\.2\\..*$"

# List S3 runs (latest-only) with job-name filter
hv results ls s3://example-bucket/sagemaker-output/ \
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
hv results download <s3_prefix> --dest-base-dir <local_dir> [options]
```

**Required options**:
- `<s3_prefix>`: S3 base prefix where backtest results are stored
- `--dest-base-dir`: local destination root

**Optional options**:
- filters: `--from-date`, `--to-date`, `--algorithm-name-regex`, `--algorithm-version-regex`, `--job-name-regex`
- AWS: `--role-arn`
- extra artifacts: `--include-metadata`, `--include-output-data`
- `--no-skip-existing`: re-download even if the canonical local `result.json` already exists

**Behavior**:
- Selection uses the same latest-only semantics as `results ls`.
- `runs-with-links` is the only supported local layout; `--layout` is not accepted.
- Canonical run data lives under `runs/<job>/...`.
- `meta/<algorithm_id>/last_test_date_<day>/result.json` points to the newest downloaded local run for that day.
- `meta/<algorithm_id>/last_test_date_<day>/runs/<job>` points to each downloaded run for that `(algorithm_id, test_date)`.
- The downloader fails fast when the destination contains the former one-result-per-day materialization.
- Downloads `result.json` for every selected run.
- Optionally downloads and extracts:
  - `output/output.tar.gz` (`--include-metadata`)
  - `output/model.tar.gz` (`--include-output-data`)
- Fails fast if any expected downloaded `result.json` is missing after completion.

**Examples**:

```bash
# Download result.json only
hv results download s3://example-bucket/sagemaker-output/ \
  --dest-base-dir ./results \
  --from-date 2000-02-10 \
  --to-date 2000-02-15 \
  --algorithm-name-regex "^my-algorithm$" \
  --algorithm-version-regex "^1\\.2\\..*$"

# Include metadata and output data artifacts
hv results download s3://example-bucket/sagemaker-output/ \
  --dest-base-dir ./results \
  --from-date 2000-02-10 \
  --to-date 2000-02-15 \
  --algorithm-name-regex "^my-algorithm$" \
  --algorithm-version-regex "^1\\.2\\..*$" \
  --job-name-regex "^example-job-.*$" \
  --include-metadata \
  --include-output-data
```

**Materialized layout**:

```text
./results/
├── meta/
│   └── <algorithm>@<version>(-<hyperparameter>)/
│       └── last_test_date_YYYY-MM-DD/
│           ├── latest -> ../../../runs/<job>/meta/<algorithm>@<version>(-<hyperparameter>)/last_test_date_YYYY-MM-DD
│           ├── result.json -> ../../../runs/<job>/meta/<algorithm>@<version>(-<hyperparameter>)/last_test_date_YYYY-MM-DD/result.json
│           └── runs/
│               └── <job> -> ../../../../runs/<job>/meta/<algorithm>@<version>(-<hyperparameter>)/last_test_date_YYYY-MM-DD
└── runs/
    └── <job>/
        └── meta/
            └── <algorithm>@<version>(-<hyperparameter>)/
                └── last_test_date_YYYY-MM-DD/
                    └── result.json
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
hv results download s3://example-bucket/performance-tests/ \
  --dest-base-dir "./perf-data" \
  --from-date "2000-02-01" \
  --to-date "2000-02-01"

# 2. Compare performance between two algorithm versions
hv metrics compare-system \
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

### `hv data dependencies inspect|download`

**Description**: Resolve the datasets required by one algorithm revision for a target date. `inspect` is read-only:
by default it reports the declaration, `--remote` resolves the declared production S3 URI, and `--local-dir` compares
local files with those remote objects. `download` is the only operation that materializes data locally.

Remote locations come from each dependency's production `s3_uri` declaration. For dependencies without that declaration,
`--s3-base-dir` provides a fallback location by appending the dependency's `data_prefix`. A declared URI always takes
precedence; if neither is available, the command fails clearly.

**Shared required options**:

- `--repo-url`: Git repository URL for the algorithm.
- `--git-reference`: One branch, tag, or commit to inspect.
- `--scratch-dir`: Directory for temporary JAR builds and git checkouts.
- `--last-test-time`: Last test time in YYYY-MM-DD format.

**Inspect options**:

- `--remote`: Resolve each declared production S3 URI. It does not list objects.
- `--local-dir <path>`: Compare local files to the resolved remote objects. Requires `--remote` and AWS credentials.
- `--format json|sagemaker`: Emit dependency metadata (default) or a SageMaker `InputDataConfig` object. SageMaker
  format requires `--remote`.

**Download options**:

- `--local-dir <path>`: Destination directory for materialized data.
- Exactly one of `--all` or repeatable `--name <data-prefix>` selects dependencies to download.
- `--sample-ratio` and `--max-parallel-downloads` tune downloads only.

`--target`, `--algorithm-override`, `--s3-base-dir`, and `--role-arn` are available on both operations.

**Examples**:

```bash
# Inspect the declared dependency contract; no S3 access or download
hv data dependencies inspect \
  --repo-url https://github.com/example-org/example-algorithm.git \
  --git-reference v2.0.0 \
  --scratch-dir ./temp-build \
  --last-test-time 2000-01-08

# Resolve the production S3 locations, or emit InputDataConfig-shaped JSON
hv data dependencies inspect --remote --format sagemaker \
  --repo-url https://github.com/example-org/example-algorithm.git \
  --git-reference v2.0.0 \
  --scratch-dir ./temp-build \
  --last-test-time 2000-01-08

# Compare an existing local mirror against remote objects
hv data dependencies inspect --remote --local-dir ./training-data \
  --repo-url https://github.com/example-org/example-algorithm.git \
  --git-reference v2.0.0 \
  --scratch-dir ./temp-build \
  --last-test-time 2000-01-08

# Download all dependencies, or narrow the selection and sample it
hv data dependencies download --all --local-dir ./training-data \
  --s3-base-dir s3://example-bucket/tables \
  --repo-url https://github.com/example-org/example-algorithm.git \
  --git-reference v2.0.0 \
  --scratch-dir ./temp-build \
  --last-test-time 2000-01-08

hv data dependencies download --name example_training_data --local-dir ./training-data \
  --repo-url https://github.com/example-org/example-algorithm.git \
  --git-reference v2.0.0 \
  --scratch-dir ./temp-build \
  --last-test-time 2000-01-08 \
  --sample-ratio 0.01
```

## `hv ems`

`hv ems` is a small, **read-only** CLI for inspecting an experiment-management service,
such as slots, experiments, algorithms, algorithm parameters, and online evaluation results.

`hv-exp` remains available for existing scripts and prints a warning to use `hv ems`.

### Usage

```bash
hv ems <subcommand> [options]
```

Examples:

```bash
hv ems slot list
hv ems slot get --slot-name my-slot
hv ems experiment list --slot-name my-slot
hv ems experiment results list --experiment-id 42
hv ems experiment results show --experiment-id 42 --analysis-date 2000-01-15
hv ems experiment results download --experiment-id 42
hv ems algorithm list-active
hv ems algorithm list-in-use
hv ems algorithm list-in-use --slot-name my-slot
```

### Command map

All `hv ems` commands emit JSON. Start with the narrowest query that answers the question; these operations are
read-only and make no experiment-management changes.

| Need | Command | Required selector |
| --- | --- | --- |
| Discover slots | `hv ems slot list` | — |
| Inspect one slot’s default variant and active experiments | `hv ems slot get` | `--slot-name` |
| List experiments, optionally in one slot | `hv ems experiment list` | optional `--slot-name` |
| Inspect one experiment | `hv ems experiment get` | `--experiment-id` |
| Read an experiment’s ramp-up history | `hv ems experiment rampup-log` | `--experiment-id` |
| List default variants | `hv ems default-variant list` | optional `--slot-name` |
| List all or active algorithms | `hv ems algorithm list` / `list-active` | optional `--slot-name` |
| Find algorithms actually in use | `hv ems algorithm list-in-use` | optional `--slot-name` |
| Read one algorithm definition | `hv ems algorithm get` | `--algorithm-name`, `--algorithm-version` |
| List an algorithm’s parameter versions | `hv ems algorithm parameter list` | `--algorithm-name`, `--algorithm-version` |
| Read one parameter artifact record | `hv ems algorithm parameter get` | `--algorithm-parameter-id` |
| Inspect/download online evaluation partitions | `hv ems experiment results <list|show|download>` | `--experiment-id` |

```bash
# Inspect an experiment and its current algorithm definition
hv ems experiment get --experiment-id 42
hv ems algorithm get --algorithm-name my-algorithm --algorithm-version 1.2.3

# Discover parameter records for that algorithm
hv ems algorithm parameter list \
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
hv ems algorithm list-in-use

# a single slot
hv ems algorithm list-in-use --slot-name my-slot
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
hv ems experiment results list --experiment-id 42

# stream one analysis date to stdout (decompressed JSONL)
hv ems experiment results show --experiment-id 42 --analysis-date 2000-01-15

# download one analysis date
hv ems experiment results download --experiment-id 42 --analysis-date 2000-01-15

# download all available analysis dates for the experiment
hv ems experiment results download --experiment-id 42
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

By default, `hv ems` reads experiment-management settings from `~/.hotvect/config.json` under the
`experiment_management` section (see the config reference). Pass `--config-path <file>` before the subcommand to use a
different complete Hotvect config for that invocation.

You can also override the URL, token provider, and request timeouts on the command line:

```bash
hv ems \
  --config-path ./hotvect-config.json \
  --url https://experiments.example.com \
  --token-provider-command "printenv EXAMPLE_TOKEN" \
  --token-provider-ttl-ms 3600000 \
  --connect-timeout-seconds 5 \
  --read-timeout-seconds 15 \
  slot list
```

If no timeout overrides are provided, `hv ems` uses the values from `~/.hotvect/config.json` when present, and
otherwise uses a 5 second connect timeout and a 15 second read timeout.

## Output and ordering notes

- **Ordering**: `encode` is ordered by default. For `audit` and `predict`, pass `--ordered` when output must preserve
  input order; otherwise output may be unordered and sharded.
- **Defaults**: If certain paths are not provided (like `--metadata-path` or `--dest-path`), the tool uses default paths based on the algorithm name and command.
- **Output Locations**:
    - **Transformed Data**: For commands that transform data (for example `audit`, `encode`, `predict`), output is
      saved under `--dest-path`, which is a directory containing `part-*` files.
    - **Metadata**: Operation metadata, including timing information, algorithm version, and other details, is saved to `--metadata-path/metadata.json` (logs: `hv.log`, `hotvect-offline-utils.log`, `stdout-stderr.log`).
- **Utility commands**: `hv` includes data analysis and management commands alongside core ML pipeline operations.
