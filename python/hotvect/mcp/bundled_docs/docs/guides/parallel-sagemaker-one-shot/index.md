# Parallel SageMaker One-Shot Runs

Hotvect supports fan-out execution for one-shot SageMaker commands:

- `hv audit --sagemaker`
- `hv predict --sagemaker`
- `hv encode --sagemaker`

This mode is intended for batch workloads where splitting one large input into multiple SageMaker jobs reduces total wall-clock time.

`--no-wait` is also available for ordinary one-shot SageMaker runs without `--job-parallelism`, but in that case it is only a convenience flag because single-job one-shot submission already returns immediately after submission.

## Public vs Managed Output

### `--dest-path`

This is the public output prefix.

Parallel shard jobs write directly here:

```text
s3://example-bucket/output/predict/dt=2000-02-01/
  _SUBMISSION.json
  part-00000-00000.jsonl
  part-00000-00001.jsonl
  part-00001-00000.jsonl
  ...
  _SUCCESS
```

- `_SUBMISSION.json` appears at submission time.
- `_SUCCESS` appears only when the full run is verified complete.
- `predict` and `audit` publish flat zero-padded `part-<worker>-<localshard>.jsonl[.gz]` files under the public prefix, for example `part-00003-00012.jsonl.gz`.

Partial part files may be visible before the run is complete. That is expected.

### `--s3-output-base`

This is the Hotvect-managed control-plane prefix.

Hotvect stores:

- **immutable plan manifest** — shard plans, source keys, expected outputs (written once at submission start)
- **mutable submission state** — per-shard SageMaker job names and output URIs (updated after each shard submission)
- SageMaker job bookkeeping
- verification state

## Start Modes

### Default: submit, wait, and finalize

```bash
hv predict \
  --sagemaker \
  --algorithm-jar my-algo.jar \
  --algorithm-name my-algo \
  --parameter-s3-uri s3://example-bucket/params.zip \
  --source-s3-uri s3://example-bucket/input/dt=2000-02-01/ \
  --dest-path s3://example-bucket/output/predict/dt=2000-02-01/ \
  --s3-output-base s3://example-bucket/hv-managed/ \
  --sagemaker-job-prefix example-job-parallel \
  --job-parallelism 8
```

With no `--no-wait`, Hotvect:

- submits the shard jobs
- keeps polling SageMaker
- writes `_SUCCESS` if everything completes

If the local process is interrupted, the remote SageMaker jobs continue running.

### Submit and exit immediately

```bash
hv predict \
  --sagemaker \
  --no-wait \
  --algorithm-jar my-algo.jar \
  --algorithm-name my-algo \
  --parameter-s3-uri s3://example-bucket/params.zip \
  --source-s3-uri s3://example-bucket/input/dt=2000-02-01/ \
  --dest-path s3://example-bucket/output/predict/dt=2000-02-01/ \
  --s3-output-base s3://example-bucket/hv-managed/ \
  --sagemaker-job-prefix example-job-parallel \
  --job-parallelism 8
```

In this mode Hotvect:

- submits the shard jobs
- writes `_SUBMISSION.json`
- prints the exact `--verify` command
- exits without waiting

## Verify / Finalize

Later, run:

```bash
hv predict \
  --sagemaker \
  --verify \
  --dest-path s3://example-bucket/output/predict/dt=2000-02-01/ \
  --s3-output-base s3://example-bucket/hv-managed/
```

Hotvect will:

- read `_SUBMISSION.json` from `--dest-path`
- load the stored manifest from `--s3-output-base`
- check all shard SageMaker jobs
- write `_SUCCESS` if all shard jobs completed successfully

If the run is still incomplete or failed, `--verify` exits with an error and does **not** write `_SUCCESS`.

## Compression

`--compression` is supported only for:

- `predict`
- `audit`

Allowed values:

- `none` (default)
- `gzip`

Examples:

```bash
hv predict ... --job-parallelism 8 --compression gzip
hv audit --parameter-s3-uri s3://example-bucket/params.zip ... --job-parallelism 4 --compression none
```

`encode` does not support `--compression`.

## Constraints

- Parallel one-shot mode requires `--sagemaker`.
- `--job-parallelism` is supported only for `audit`, `predict`, and `encode`.
- `--verify` is supported only for parallel one-shot SageMaker runs.
- `--no-wait` requires `--sagemaker`. In single-job one-shot mode it has no additional effect because submission is already asynchronous.
- Parallel one-shot is an unordered-only mode. `--ordered` cannot be combined with `--job-parallelism`; `--unordered` is allowed but redundant.
- `--writer-num-shards` is supported in parallel one-shot mode and applies within each shard job. The effective
  algorithm definition may also set its writer shard count.
- If the effective algorithm definition or override explicitly requires ordered output, parallel submission fails fast instead of silently switching to unordered output.
- `--sagemaker-job-prefix` is still required and still overrides any `TrainingJobName` present in the SageMaker template.

Related: [Run backtests on AWS SageMaker](../sagemaker-backtests/index.md) for full lifecycle runs and the
[CLI reference](../../reference/cli/index.md) for every one-shot option.
