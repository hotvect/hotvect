---
title: Prepare a local development environment
description: Prepare an existing algorithm, its declared data dependencies, and a small local backtest
tags: [local, development, setup, data-dependency]
difficulty: intermediate
estimated_time: 30-90 minutes
prerequisites:
  - JDK 21 installed
  - Maven installed
  - Python 3.11–3.13 installed
  - Hotvect source checkout available
  - Access to the algorithm repository and its data source
related_docs:
  - ../quickstart/index.md
  - ../first-workflow/index.md
  - ../patterns/data-dependencies/index.md
  - ../../reference/cli/index.md
related_commands:
  - hv-ext config init
  - hv-ext show-data-dependency
  - hv-ext data-dependency
  - hv backtest
---

# Prepare a local development environment

Use this guide when you are ready to build an existing algorithm repository, inspect its declared data dependencies,
download a small local sample, and run a smoke backtest. If you are still learning the runtime contract, complete the
[example product algorithms](../first-run/index.md) and [tour an existing algorithm](../first-workflow/index.md) first.

The first local setup should use a small sampled data download. `--sample-ratio` samples files inside each required date
partition, so `0.001` means roughly 0.1% of files per date directory. It is not row-level sampling.

## Before you start

You need:

- JDK 21, Maven, Git, Python 3.11–3.13, the AWS CLI, and a Hotvect source checkout;
- access to the algorithm repository and any Maven dependencies it needs;
- the base S3 location and credentials for the algorithm's declared data;
- enough local disk for the inspected download plan, output artifacts, and build scratch space.

This workflow validates local wiring. A small file sample is not evidence of production model quality.

## Inputs

Fill in these values before running commands:

```bash
HOTVECT_REPO=/path/to/hotvect
ALGO_REPO_URL=https://github.com/example-org/example-algorithm.git
GIT_REF=main
LAST_TEST_TIME=2000-02-01
S3_BASE_DIR=s3://example-bucket/tables/
WORK_DIR=/tmp/hotvect-local-dev
DATA_BASE_DIR="$WORK_DIR/data"
OUTPUT_BASE_DIR="$WORK_DIR/output"
SCRATCH_DIR="$WORK_DIR/scratch"
SAMPLE_RATIO=0.001
```

`2000-02-01` is a deliberately synthetic example date. Replace it with the historical test date that your algorithm
and local data support.

Notes:

- `ALGO_REPO_URL` can also be a local checkout path when validating an unpushed branch.
- Use a commit SHA for reproducible setup. Use a branch only when you explicitly want the current branch state.
- `S3_BASE_DIR` is required by `hv-ext data-dependency`. If dependencies declare their own `s3_uri`, Hotvect uses the
  resolved dependency URI; otherwise it appends each `data_prefix` to this base directory.
- Keep `WORK_DIR` outside the Hotvect and algorithm source checkouts. Training outputs and downloaded data are large.

## 1. Install and verify Hotvect

From a Hotvect source checkout:

```bash
cd "$HOTVECT_REPO/python"
make init
source .venv/bin/activate
hv --version
hv-ext --help
```

Keep this virtual environment active for the commands below. Backtests launch helper binaries such as trainer scripts
from `PATH`, so the shell should resolve them from the same Hotvect installation.

Expected:

- `hv --version` prints both the Python package version and the bundled Hotvect JAR version.
- `hv-ext --help` lists `config`, `show-data-dependency`, and `data-dependency`.

If `hv` fails with a missing Hotvect JAR, rebuild and copy the JARs:

```bash
cd "$HOTVECT_REPO/python"
make quick
source .venv/bin/activate
hv --version
```

## 2. Create local directories and CLI config

```bash
mkdir -p "$DATA_BASE_DIR" "$OUTPUT_BASE_DIR" "$SCRATCH_DIR"
test ! -e ~/.hotvect/config.json
```

If the second command succeeds, initialize the config:

```bash
hv-ext config init \
  --data-base-dir "$DATA_BASE_DIR" \
  --output-base-dir "$OUTPUT_BASE_DIR" \
  --scratch-dir "$SCRATCH_DIR"
```

The config supplies defaults when a command omits a directory. `hv-ext config init` refuses to overwrite an existing
file; inspect and edit that file, or pass `--force` only when replacing the complete configuration is intentional.

## 3. Verify the algorithm builds and exposes dependencies

Run `show-data-dependency` first. It clones the algorithm, builds the JAR, loads the algorithm definition, and writes the
declared data dependencies without enumerating S3 objects or downloading data.

```bash
hv-ext show-data-dependency \
  --repo-url "$ALGO_REPO_URL" \
  --git-reference "$GIT_REF" \
  --scratch-dir "$SCRATCH_DIR/show-data-dependency" \
  --last-test-time "$LAST_TEST_TIME" \
  --output "$WORK_DIR/dependencies.json"
```

Expected:

- The command exits with code `0`.
- `$WORK_DIR/dependencies.json` contains a `git_references` object keyed by the requested git reference.
- Each git reference entry contains `algorithm_name`, `algorithm_version`, and `dependencies`.
- Each dependency entry includes `data_prefix`. Resolved URI fields can vary depending on whether the dependency declares
  an explicit URI.

If this fails, fix the build or algorithm definition before trying to download data. Common causes are missing Maven
credentials, an invalid `GIT_REF`, an unsupported Java version, or an algorithm definition that cannot be loaded. For
git-ref builds, produce an attached Shade JAR named `<artifactId>-<version>-shaded.jar`; Hotvect selects it over the
thin JAR and fails deliberately if several runtime classifier JARs leave no unambiguous choice.

## 4. Authenticate for data access

Use your environment's normal AWS login flow, then verify credentials:

```bash
aws sts get-caller-identity
```

Do this before `hv-ext data-dependency`. The default list mode is safe from downloads, but it still enumerates S3 files
to estimate sizes and compare local status, so AWS credentials are required.

## 5. Inspect the exact download plan

List dependencies as JSON before downloading:

```bash
hv-ext data-dependency \
  --repo-url "$ALGO_REPO_URL" \
  --git-reference "$GIT_REF" \
  --s3-base-dir "$S3_BASE_DIR" \
  --local-data-dir "$DATA_BASE_DIR" \
  --scratch-dir "$SCRATCH_DIR/data-dependency-list" \
  --last-test-time "$LAST_TEST_TIME" \
  > "$WORK_DIR/download-plan.json"

python -m json.tool "$WORK_DIR/download-plan.json"
```

Expected:

- The command exits with code `0`.
- The command output contains a `summary.total_size_human` field and a `dependencies` array.
- Each dependency has the expected `data_dates`, `s3_uri`, `local_path`, and `status`.

The JSON plan is written to stdout, while build and progress messages stay on stderr. It is safe to redirect or pipe
stdout as shown above.

If the size is too large for local iteration, keep the first download sampled and consider passing
`--download <data_prefix>` for only the dependency you need. Increase `SAMPLE_RATIO` only after list mode confirms that
the planned file count and size fit the local disk.

If list mode fails with an AWS credential or S3 access error after the algorithm build succeeds, the local
build/dependency-shape check has passed. Refresh credentials before retrying list mode or any sampled download.

## 6. Download a sampled local dataset

Start with a sampled download:

```bash
hv-ext data-dependency --download-all \
  --repo-url "$ALGO_REPO_URL" \
  --git-reference "$GIT_REF" \
  --s3-base-dir "$S3_BASE_DIR" \
  --local-data-dir "$DATA_BASE_DIR" \
  --scratch-dir "$SCRATCH_DIR/data-dependency-download" \
  --last-test-time "$LAST_TEST_TIME" \
  --sample-ratio "$SAMPLE_RATIO" \
  --max-parallel-downloads 2
```

Use `--download <data_prefix>` instead of `--download-all` when you only need one dependency:

```bash
hv-ext data-dependency \
  --download example_training_data \
  --repo-url "$ALGO_REPO_URL" \
  --git-reference "$GIT_REF" \
  --s3-base-dir "$S3_BASE_DIR" \
  --local-data-dir "$DATA_BASE_DIR" \
  --scratch-dir "$SCRATCH_DIR/data-dependency-download-one" \
  --last-test-time "$LAST_TEST_TIME" \
  --sample-ratio "$SAMPLE_RATIO" \
  --max-parallel-downloads 2
```

Expected local layout:

```text
$DATA_BASE_DIR/
  example_training_data/
    dt=2000-01-29/
      part-...
    dt=2000-01-30/
      part-...
  example_test_data/
    dt=2000-02-01/
      part-...
```

Check the layout:

```bash
find "$DATA_BASE_DIR" -maxdepth 3 -type d -name 'dt=*' | sort
```

## 7. Run a local smoke backtest

Use the same directories and `LAST_TEST_TIME` used for the data download:

```bash
hv backtest \
  --git-reference "$GIT_REF" \
  --algo-repo-url "$ALGO_REPO_URL" \
  --data-base-dir "$DATA_BASE_DIR" \
  --output-base-dir "$OUTPUT_BASE_DIR/backtest" \
  --scratch-dir "$SCRATCH_DIR/backtest" \
  --last-test-time "$LAST_TEST_TIME" \
  --number-of-runs 1 \
  --no-performance-test
```

Expected success artifacts:

```text
$OUTPUT_BASE_DIR/backtest/meta/<algorithm_id>/last_test_date_<YYYY-MM-DD>/result.json
$OUTPUT_BASE_DIR/backtest/meta/<algorithm_id>/last_test_date_<YYYY-MM-DD>/hv.log
$OUTPUT_BASE_DIR/backtest/meta/<algorithm_id>/last_test_date_<YYYY-MM-DD>/hv.all.log
```

If the sampled data is too small for meaningful model quality, use it only to validate build, dependency wiring, and
pipeline execution. Use a larger sample or full data for evaluation.

## Verify the setup

The environment is ready for iteration when the dependency plan names the expected algorithm and dates, the sampled
`dt=...` directories exist locally, and the smoke backtest writes `result.json` plus its logs. If the backtest reports
a missing partition, compare `LAST_TEST_TIME` and the local layout with `download-plan.json`; the data and backtest
commands must use the same date and effective definition.

This result proves the build, dependency wiring, and lifecycle can run locally. It does not establish model quality
from the sampled data.

## Next steps

- For date calculation details, see [Data dependencies](../patterns/data-dependencies/index.md).
- For definition override semantics, see [Override files](../patterns/override-files/index.md).
- To compare fixed revisions, see [Backtest an algorithm locally](../local-backtest/index.md).
- To produce a new parameter artifact, see [Train an algorithm locally](../local-train/index.md).
- For full command options, see [CLI reference](../../reference/cli/index.md).
