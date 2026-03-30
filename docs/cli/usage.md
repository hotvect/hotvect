---
title: Using the Hotvect Command-Line Interface
description: Complete reference for hv and hv-ext CLI commands
tags: [cli, reference, commands, usage, tools]
difficulty: beginner
estimated_time: 45 minutes (reference)
prerequisites:
  - hotvect Python package installed
  - Algorithm JAR available (for most commands)
  - Basic understanding of hotvect concepts
related_docs:
  - ../howto/run-and-compare-feature-audits.md
  - ../howto/debug-feature-engineering.md
  - ../highlevel/concepts.md
related_commands:
  - hv audit
  - hv train
  - hv predict
  - hv backtest
  - hv-ext jsonl-compare
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
- **list-transformations**: Display all feature transformations defined in the algorithm.
- **evaluate**: Calculate performance metrics from model predictions.
- **train**: Train a machine learning model using the hotvect pipeline.
- **backtest**: Run backtest on git references to compare algorithm performance.

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

See the [Parent-Child Algorithm Pattern](../patterns/parent-child-algorithms.md) for more details on composite algorithm architecture.

## Usage

The general syntax for using the `hv` tool is:

```bash
hv <command> [options]
```

To see the list of available commands, run:

```bash
hv -h
```

To get help on a specific command, use:

```bash
hv <command> -h
```

## Subcommands and Options

Below is a detailed explanation of each subcommand and its options.

### 1. `audit`

**Description**: Generate human-readable audit data showing feature transformations and calculations. Perform feature transformation on input data and save results as human-readable JSONL format. This is useful for debugging algorithms, inspecting calculated features, and understanding how input data is transformed by the feature engineering pipeline.

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

**Description**: Benchmark algorithm performance and measure throughput/latency. Execute performance benchmarks on the specified algorithm to measure prediction throughput, latency, and resource utilization. Results help optimize algorithm performance and identify bottlenecks.

**Usage**:

```bash
hv performance-test --algorithm-jar <path_to_jar> --algorithm-name <algorithm_name> [options]
```

**Options**: Same as the common Java command options (see Common Options section below).

### 3. `encode`

**Description**: Transform input data into ML-ready format for training. Perform feature transformation on input data and encode it in the binary format expected by the machine learning library (e.g., Vowpal Wabbit or CatBoost). This step is required before training and produces the encoded dataset used for model training.

**Usage**:

```bash
hv encode --algorithm-jar <path_to_jar> --algorithm-name <algorithm_name> [options]
```

**Options**: Same as the common Java command options, with `--dest-schema-path` being particularly relevant for encoding operations.

### 4. `predict`

**Description**: Generate model predictions on test/validation data. Use a trained model to generate predictions on input data. Requires a parameter file from a previous training run. Output includes prediction scores and can be used for evaluation or serving.

**Usage**:

```bash
hv predict --algorithm-jar <path_to_jar> --algorithm-name <algorithm_name> [options]
```

**Options**: Same as the common Java command options (see Common Options section below).

### 5. `list-transformations`

**Description**: Display all feature transformations defined in the algorithm. List all feature transformations, encoders, and data processing steps available in the specified algorithm. Use --verbose for detailed information about each transformation including parameters and data types.

**Usage**:

```bash
hv list-transformations --algorithm-jar <path_to_jar> --algorithm-name <algorithm_name> [options]
```

**Options**:

- `--algorithm-jar`: **(required)** Path to the JAR file containing the algorithm implementation.
- `--algorithm-name`: **(required)** Name of the algorithm to execute (e.g., `product-ranker`).
- `--metadata-path`: Path where the operation's metadata will be saved (optional, auto-generated if not specified).
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
- `--data-base-dir`: Base directory containing training data.
- `--output-base-dir`: Base directory where training outputs will be saved.
- `--algorithm-jar`: Path to the JAR file containing the algorithm implementation.
- `--last-test-time`: Last test time in YYYY-MM-DD format (e.g., "2025-04-30").

**Optional Options**:

- `--algorithm-override`: Path to JSON file containing algorithm configuration overrides.
- `--extra-jvm-args`: Additional JVM arguments for training, comma-separated (e.g., "-Xmx64g,-XX:+UseG1GC").

**Example**:

```bash
hv train --algorithm-name product-reranking-algorithm \
         --data-base-dir /path/to/training/data \
         --output-base-dir /path/to/output \
         --algorithm-jar /path/to/algorithm.jar \
         --last-test-time 2025-04-30 \
         --algorithm-override /path/to/override.json \
         --extra-jvm-args "-Xmx64g,-XX:+UseG1GC"
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

- `--algorithm-override`: Path to JSON file containing algorithm configuration overrides for the preceding `--git-reference`.
- `--number-of-runs`: Number of backtest runs to execute (default: 1).
- `--extra-jvm-args`: Additional JVM arguments, comma-separated (e.g., "-Xmx176g,-XX:+UseG1GC").
- `--sagemaker-config`: Path to JSON file containing SageMaker training job configuration.
- `--role-arn`: AWS role ARN to assume for SageMaker execution.
- `--n-process`: Number of parallel processes for local execution (default: 1).
- `--max-threads-per-process`: Maximum threads per process for local execution (default: auto-calculated).
- `--clean`: Clean output directories before starting backtest.
- `--no-performance-test`: Disable system performance testing.

**Examples**:

**Basic Local Backtest**:
```bash
hv backtest --git-reference main --git-reference feature-branch \
           --algo-repo-url https://github.com/company/algorithm.git \
           --data-base-dir /path/to/data \
           --output-base-dir /path/to/output \
           --scratch-dir /tmp/backtest \
           --last-test-time 2025-08-05 \
           --n-process 4
```

**SageMaker Backtest**:
```bash
hv backtest --git-reference main \
           --algo-repo-url https://github.com/company/algorithm.git \
           --data-base-dir /path/to/data \
           --output-base-dir /path/to/output \
           --scratch-dir /tmp/backtest \
           --last-test-time 2025-08-05 \
           --sagemaker-config sagemaker-config.json \
           --role-arn arn:aws:iam::123456789012:role/example-role
```

**Config File Approach**:
```bash
hv backtest --backtest-config backtest-refs.json \
           --algo-repo-url https://github.com/company/algorithm.git \
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
                    "S3Uri": "s3://bucket/training-data/"
                }
            },
            "InputMode": "FastFile"
        }
    ],
    "OutputDataConfig": {
        "S3OutputPath": "s3://bucket/backtest-output/"
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

## Common Options

Most Java-based commands (audit, performance-test, encode, predict, list-transformations) share common options:

- `--algorithm-jar`: **(required)** Path to the JAR file containing the algorithm implementation.
- `--algorithm-name`: **(required)** Name of the algorithm to execute (e.g., `product-ranker`).
- `--metadata-path`: Path where the operation's metadata will be saved (optional, auto-generated if not specified).
- `--source-path`: Path to the input data source for the operation (optional for some commands).
- `--dest-path`: Path where the operation results will be saved (optional, auto-generated if not specified).
- `--parameter-path`: Path to the trained model parameter file (required for predict/audit, optional for others).
- `--dest-schema-path`: Path where the feature schema description will be saved (optional, used in encoding operations).
- `--samples`: Number of samples to process, useful for testing with smaller datasets (optional).

**Note**: Generally, if the command transforms data, the actual transformed output is stored in the `--dest-path`, while metadata—such as timing information, algorithm version, and other operation details—is stored in the `--metadata-path`.

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
         --extra-jvm-args "-Xmx64g,-XX:+UseG1GC"
```

This command trains the `product-reranking-algorithm` using data from the specified directory, with custom configuration overrides and JVM settings optimized for large datasets.

### Running a Backtest

```bash
hv backtest --git-reference main --git-reference feature-improved-ranking \
           --algo-repo-url https://github.com/company/ranking-algorithm.git \
           --data-base-dir /path/to/test/data \
           --output-base-dir /path/to/backtest/results \
           --scratch-dir /tmp/backtest-workspace \
           --last-test-time 2025-08-05 \
           --sagemaker-config sagemaker-backtest-config.json \
           --number-of-runs 3
```

This command compares the performance of two git references (`main` vs `feature-improved-ranking`) by running a 3-day backtest using SageMaker for execution. The backtest will submit jobs immediately and return job IDs without waiting for completion.

# Using the Hotvect Extended Utilities (`hv-extra`)

The `hv-extra` tool provides extended utility commands for data analysis, format conversion, and result management that complement the core `hv` operations. This CLI is designed for auxiliary tasks that are commonly needed but are separate from the main ML pipeline operations.

## Overview

The `hv-extra` tool supports the following utility operations:

- **perf-compare**: Compare performance test results between two JSON files
- **catboost-convert**: Convert CatBoost encoded TSV data to JSON/JSONL format  
- **jsonl-compare**: Compare two JSONL files and identify differences between them
- **download-results**: Download SageMaker backtest results from S3 to local directory
- **compare-evaluations**: Compare ML evaluation results between two JSON files (supports backtest result.json format)
- **download-data-dependency**: Download training data dependencies required for local train/backtest operations

## Usage

The general syntax for using the `hv-extra` tool is:

```bash
hv-extra <command> [options]
```

To see the list of available commands, run:

```bash
hv-extra -h
```

To get help on a specific command, use:

```bash
hv-extra <command> -h
```

## Extended Utility Commands

### 1. `perf-compare`

**Description**: Compare performance test results between two JSON files. Analyzes metrics like throughput, latency, and resource utilization across different algorithm versions or configurations.

**Usage**:

```bash
hv-extra perf-compare <baseline_file> <treatment_file> [options]
```

**Positional Arguments**:
- `baseline_file`: Path to the baseline performance results JSON file
- `treatment_file`: Path to the treatment performance results JSON file

**Options**:
- `-o`, `--output`: Output file path for comparison results (optional)
- `--format`: Output format - `json` (default), `table`, or `summary`

**Examples**:

```bash
# Basic comparison with JSON output
hv-extra perf-compare baseline_perf.json treatment_perf.json

# Generate table format and save to file
hv-extra perf-compare baseline_perf.json treatment_perf.json --format table --output comparison_report.txt

# Summary format for quick overview
hv-extra perf-compare baseline_perf.json treatment_perf.json --format summary
```

### 2. `catboost-convert`

**Description**: Convert CatBoost encoded TSV data to JSON or JSONL format. Useful for inspecting CatBoost model features and transforming training data for analysis.

**Usage**:

```bash
hv-extra catboost-convert --schema-file <schema_file> --encoded-file <tsv_file> --output <output_file> [options]
```

**Required Options**:
- `-s`, `--schema-file`: Path to the CatBoost schema file
- `-e`, `--encoded-file`: Path to the encoded TSV file
- `-o`, `--output`: Output file path

**Optional Options**:
- `--format`: Output format - `json` (default) or `jsonl`

**Examples**:

```bash
# Convert to JSON format (default)
hv-extra catboost-convert -s model.schema -e encoded_data.tsv -o converted_data.json

# Convert to JSONL format
hv-extra catboost-convert --schema-file model.schema --encoded-file encoded_data.tsv --output data.jsonl --format jsonl
```

### 3. `jsonl-compare`

**Description**: Compare two JSONL files and identify differences between them. Supports field renaming via configuration file to handle schema changes between algorithm versions. This command was moved from the main `hv` tool to `hv-extra` as it's primarily used for result analysis rather than core ML operations.

**Usage**:

```bash
hv-extra jsonl-compare <file1> <file2> [options]
```

**Positional Arguments**:
- `file1`: Path to the first JSONL file to compare
- `file2`: Path to the second JSONL file to compare

**Options**:
- `-o`, `--output`: Output directory where comparison result files will be stored (default: current directory)
- `-c`, `--config`: Path to JSON configuration file for field renaming and comparison rules (optional)
- `--format`: Output format - `json` (default) or `summary`

**Examples**:

```bash
# Basic comparison with JSON output
hv-extra jsonl-compare predictions_old.jsonl predictions_new.jsonl

# Compare with field renaming configuration
hv-extra jsonl-compare audit_v1.jsonl audit_v2.jsonl -c field_mappings.json

# Generate human-readable summary
hv-extra jsonl-compare results_baseline.jsonl results_experiment.jsonl --format summary

# Save comparison results to specific directory
hv-extra jsonl-compare file1.jsonl file2.jsonl -o comparison_output/
```

**Field Renaming Configuration Example** (`field_mappings.json`):
```json
{
  "rename": {
    "cos_request_hour": "request_cos_hour",
    "sales_channel_id": "request_sales_channel_id",
    "slot": "request_slot",
    "touchpoint": "request_touchpoint",
    "order_config_id_match_count": "order_config_sku_id_match_count"
  }
}
```

### 4. `download-results`

**Description**: Download SageMaker backtest results from S3 to local directory. Supports date range filtering, role assumption for S3 access, and configurable inclusion of metadata and output data files.

**Usage**:

```bash
hv-extra download-results --s3-base-prefix <s3_prefix> --dest-base-dir <local_dir> --from-date <start_date> --to-date <end_date> [options]
```

**Required Options**:
- `--s3-base-prefix`: S3 base prefix where backtest results are stored (e.g., "s3://bucket/path/")
- `--dest-base-dir`: Local destination directory where results will be downloaded
- `--from-date`: Start date for results to download in YYYY-MM-DD format (inclusive)
- `--to-date`: End date for results to download in YYYY-MM-DD format (inclusive)

**Optional Options**:
- `--role-arn`: AWS role ARN to assume for S3 access
- `--include-metadata`: Include metadata files in download (default: False)  
- `--include-output-data`: Include output data files in download (default: False)
- `--no-skip-existing`: Re-download files even if they already exist locally (default: skip existing)

**Examples**:

```bash
# Basic download for a date range
hv-extra download-results \
  --s3-base-prefix "s3://example-bucket/backtest-results/" \
  --dest-base-dir "./results" \
  --from-date "2025-06-01" \
  --to-date "2025-06-15"

# Download with AWS role assumption and metadata
hv-extra download-results \
  --s3-base-prefix "s3://example-bucket/backtest-results/" \
  --dest-base-dir "/nfs/shared/results" \
  --from-date "2025-06-01" \
  --to-date "2025-06-15" \
  --role-arn "arn:aws:iam::123456789012:role/example-role" \
  --include-metadata \
  --include-output-data

# Force re-download of existing files
hv-extra download-results \
  --s3-base-prefix "s3://example-bucket/results/" \
  --dest-base-dir "./fresh-results" \
  --from-date "2025-06-10" \
  --to-date "2025-06-10" \
  --no-skip-existing
```

## Extended Utility Examples

### Performance Analysis Workflow

```bash
# 1. Download backtest results for comparison
hv-extra download-results \
  --s3-base-prefix "s3://example-bucket/performance-tests/" \
  --dest-base-dir "./perf-data" \
  --from-date "2025-06-01" \
  --to-date "2025-06-01"

# 2. Compare performance between two algorithm versions
hv-extra perf-compare \
  ./perf-data/baseline/performance.json \
  ./perf-data/experiment/performance.json \
  --format table --output performance_comparison.txt

# 3. Compare detailed prediction results
hv-extra jsonl-compare \
  ./perf-data/baseline/predictions.jsonl \
  ./perf-data/experiment/predictions.jsonl \
  --format summary
```

### Data Format Conversion

```bash
# Convert CatBoost encoded data for analysis
hv-extra catboost-convert \
  --schema-file model_v2.schema \
  --encoded-file training_data.tsv \
  --output analysis_data.jsonl \
  --format jsonl
```

### Algorithm Audit Comparison

```bash
# Compare audit outputs between algorithm versions with field mapping
hv-extra jsonl-compare \
  audit_v76.jsonl audit_v77.jsonl \
  -c field_renamings.json \
  --format summary \
  -o audit_comparison_results/
```

### 6. `download-data-dependency`

**Description**: Download training data dependencies required for local train/backtest operations. Analyzes algorithm repositories to determine exact data requirements and downloads the necessary data from S3 with intelligent skip logic and sampling support.

**Usage**:

```bash
hv-extra download-data-dependency --repo-url <git_repo_url> --git-reference <git_ref> --s3-base-dir <s3_prefix> --local-data-dir <local_dir> --scratch-dir <temp_dir> --last-test-time <date> [options]
```

**Required Options**:
- `--repo-url`: Git repository URL for the algorithm (e.g., "https://github.com/user/algorithm.git")
- `--git-reference`: Git reference (branch/commit) to analyze for data dependencies. Can be specified multiple times for multiple algorithms
- `--s3-base-dir`: S3 base directory where training data is stored (e.g., "s3://bucket/tables/")
- `--local-data-dir`: Local directory where data will be downloaded
- `--scratch-dir`: Directory for temporary JAR builds and git checkouts
- `--last-test-time`: Last test time in YYYY-MM-DD format (e.g., "2025-08-09")

**Optional Options**:
- `--number-of-runs`: Number of runs (affects data requirements, default: 1)
- `--algorithm-override`: Path to JSON file containing algorithm configuration overrides
- `--role-arn`: AWS role ARN to assume for S3 access
- `--sample-ratio`: Fraction of files to download per date directory (e.g., 0.1 = 10%, 0.05 = 5%)
- `--no-skip-if-present`: Re-download data even if dt directories already exist locally (default: skip existing)
- `--max-parallel-dates`: Maximum number of date directories to download in parallel (default: 5)
- `--max-parallel-files`: Maximum number of files to download in parallel per date directory (default: 10)

**Examples**:

```bash
# Basic download for single algorithm with sampling
hv-extra download-data-dependency \
  --repo-url https://github.com/company/example-algorithm.git \
  --git-reference v77.0.0 \
  --s3-base-dir s3://bucket/tables \
  --local-data-dir ./training-data \
  --scratch-dir ./temp-build \
  --last-test-time 2025-08-09 \
  --sample-ratio 0.01

# Download for multiple algorithm versions
hv-extra download-data-dependency \
  --repo-url https://github.com/company/example-algorithm.git \
  --git-reference v77.0.0 \
  --git-reference v64.4.0 \
  --s3-base-dir s3://bucket/tables \
  --local-data-dir ./training-data \
  --scratch-dir ./temp-build \
  --last-test-time 2025-08-09

# Force re-download with AWS role assumption
hv-extra download-data-dependency \
  --repo-url https://github.com/company/algorithm.git \
  --git-reference main \
  --s3-base-dir s3://bucket/tables \
  --local-data-dir ./data \
  --scratch-dir ./temp \
  --last-test-time 2025-08-09 \
  --role-arn arn:aws:iam::123456789012:role/example-role \
  --no-skip-if-present

# Download with algorithm overrides
hv-extra download-data-dependency \
  --repo-url https://github.com/company/algorithm.git \
  --git-reference feature-branch \
  --s3-base-dir s3://bucket/tables \
  --local-data-dir ./data \
  --scratch-dir ./temp \
  --last-test-time 2025-08-09 \
  --algorithm-override config-override.json \
  --sample-ratio 0.05
```

**Key Features**:
- **Automatic Dependency Analysis**: Clones repositories, builds JARs, and uses hotvect's AlgorithmPipeline to determine exact data requirements
- **Smart Skip Logic**: Automatically skips existing date directories (dt=YYYY-MM-DD) by default to avoid redundant downloads
- **Sampling Support**: Download subset of files using `--sample-ratio` for testing and development (critical for large datasets)
- **Parallel Downloads**: Concurrent download of multiple date directories and files within each directory
- **Multiple Algorithm Support**: Analyze data requirements across different algorithm versions and git references
- **AWS Integration**: Supports role assumption for secure S3 access
- **Algorithm Name Extraction**: Automatically extracts algorithm names from pom.xml artifactId following established patterns

## Additional Information

- **Ordered Processing**: The `hv` tool preserves the order in the input data
- **Defaults**: If certain paths are not provided (like `--metadata-path` or `--dest-path`), the tool uses default paths based on the algorithm name and command.
- **Output Locations**:
    - **Transformed Data**: For commands that transform data (e.g., `audit`, `encode`, `predict`), the transformed output is saved to the path specified by `--dest-path`.
    - **Metadata**: Operation metadata, including timing information, algorithm version, and other details, is saved to the path specified by `--metadata-path`.
- **Extended Utilities**: The `hv-extra` tool complements `hv` by providing data analysis and management utilities that are commonly needed but separate from core ML pipeline operations.
