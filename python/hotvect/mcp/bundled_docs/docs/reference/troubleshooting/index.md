---
title: Troubleshooting
description: Map common Hotvect failures to the artifact, configuration, or runtime boundary that caused them
tags: [troubleshooting, errors, debugging]
related_docs:
  - ../faq/index.md
  - ../../guides/debug-feature-engineering/index.md
  - ../cli/index.md
---

# Troubleshooting

Start from the first failed stage and its authoritative artifact. Do not retry a remote job until the failure reason is
understood.

## Find the evidence first

| Run | Primary metadata root |
| --- | --- |
| `hv train` | `<output-base-dir>/metadata/<algorithm-id>/<parameter-version>/` |
| Local `hv backtest` | `<output-base-dir>/meta/<algorithm-id>/<parameter-version>/` |
| Java-backed one-shot command | The command's `--metadata-path` |
| SageMaker job | CloudWatch `/aws/sagemaker/TrainingJobs` plus `s3_uri_metadata` and `s3_uri_result_file` |

Read `result.json` for stage status, `hv.log` for Python orchestration, and the failing stage's
`stdout-stderr.log`/`hotvect-offline-utils.log` for Java or subprocess failures.

## Quick diagnosis

### A local run fails before a stage starts

Check:

```bash
hv --version
java -version
test -f /path/to/algorithm.jar
```

Then verify the command's algorithm name exists in the JAR and every required base directory is either supplied on the
command line or present in `~/.hotvect/config.json`.

### A stage succeeds but the result is wrong

1. Read the effective algorithm definition from the run metadata.
2. Confirm the JAR, parameters ZIP, source rows, and test date.
3. Run a bounded ordered feature audit.
4. Compare fixed-parameter predictions before starting a retraining comparison.

See [Feature audits](../../guides/feature-audits/index.md) and
[Score equivalence testing](../../guides/score-equivalence/index.md).

### A SageMaker job fails

```bash
aws sagemaker describe-training-job \
  --training-job-name <job-name> \
  --query '{status:TrainingJobStatus,reason:FailureReason,result:HyperParameters.s3_uri_result_file,metadata:HyperParameters.s3_uri_metadata}' \
  --output json
```

If `result.json` was never written, use CloudWatch. An S3 URI in the job hyperparameters is an intended destination,
not proof that the object exists.

## Generated transformer build errors

### Backend class missing from the annotation processor path

The selected backend module must be present twice:

- on the normal compile classpath because the annotation references its class literal;
- on the annotation processor path because `hotvect-processor` loads it during compilation.

See [Generated transformer backends](../generated-transformer-backends/index.md#build-wiring).

### Backend configured in the algorithm definition

`transformer_parameters.backend` is not a generated-transformer contract. Select the backend on
`@GenerateSimpleRankingTransformer(backend = ...)`. The definition supplies `transformer_parameters.features` and
optional backend-specific feature types.

### Invalid or incompatible feature type

The selected backend validates `features[].type` against the annotated Java method's return type. Use the backend's
documented type grammar or omit `type` only when that backend can infer it. Fixed-length TensorFlow arrays need an
explicit shape such as `float32[768]`.

### Missing `transformer_parameters.features`

The resource named by `algorithmDefinitionResource` must contain an array:

```json
{
  "transformer_parameters": {
    "features": [
      "feature_categorical_01",
      {"name": "numeric_signal_01", "type": "float32"}
    ]
  }
}
```

## Training and data errors

### Training partition not found

Common causes are a mismatched `last_test_time`, lag/window setting, data prefix, or base directory. Resolve the plan
with the same git reference, override, target, and date as the run:

```bash
hv-ext data-dependency \
  --repo-url <repo-or-local-path> \
  --git-reference <ref> \
  --s3-base-dir s3://example-bucket/tables/ \
  --local-data-dir /path/to/data \
  --scratch-dir /path/to/scratch \
  --last-test-time YYYY-MM-DD
```

Then compare the plan with `/path/to/data/<data-prefix>/dt=YYYY-MM-DD/`. The default command lists; it does not
download.

### Override references an unknown dependency

The key under `dependencies` does not match a child declared by the selected parent definition. Fix the spelling or
apply a child-specific override to the child target. Overrides cannot add dependencies.

### `OutOfMemoryError`

Identify the failing stage and the process/container memory limit before changing flags. Then choose one of these:

- reduce the intended data window or debug sample;
- reduce concurrency or worker count;
- remove unintended features or retained state;
- set one explicit heap cap with `--extra-jvm-args`.

Use `-Xmx...` or `-XX:MaxRAMPercentage=...`, not both. Hotvect already supplies
`-XX:MaxRAMPercentage=80` when no heap cap is configured, so passing the same value is not an increase.

For a reproducible heap dump:

```bash
hv train \
  --extra-jvm-args "-XX:+HeapDumpOnOutOfMemoryError,-XX:HeapDumpPath=/path/to/heap.hprof" \
  ...
```

## Local serve errors

### Address already in use

```bash
lsof -nP -iTCP:<port> -sTCP:LISTEN
```

Stop the existing listener or choose a different nonzero port. `hv serve` and `hv worker serve` reject port `0`.

### Health check times out

Read the startup output in the same terminal. For `hv serve`, increase `--startup-timeout-seconds` only after
confirming the process is still making progress. For `hv worker serve`, inspect the selected scope's
`startup_timeout_ms` and Python imports.

### Worker starts with the wrong Python environment

For `hv worker serve`, interpreter resolution is:

1. `HOTVECT_PYTHON_EXECUTABLE`;
2. the selected `litserve.python_executable`;
3. `sys.executable`.

Set the first explicitly while debugging and verify it can import the backend dependencies.

### No usable worker configuration

`hv worker serve --scope auto` checks `realtime`, then `batch`, for a usable worker runtime. Pass `--scope` explicitly
and make sure the selected scope contains the intended `litserve` configuration. Backend selection lives at
`algorithm_parameters.backend`.

See [Direct Python workers](../../design/direct-python-workers/index.md).

## SageMaker errors

### Credentials expired

Refresh credentials with the environment's normal authentication flow and verify:

```bash
aws sts get-caller-identity
```

### Capacity unavailable

Inspect the submitted job definition. Use `HotvectSubmissionOptions.PreferredInstanceTypes` for an explicit ordered
list or `ResourceConfig.InstanceType` for one fixed type. Do not assume editing a template overrides an
algorithm-owned resource setting.

### Input channel missing or wrong

Check the effective `InputDataConfig`, dependency `data_prefix`, resolved `s3_uri`, environment key, and SageMaker role
permissions. Existing template channels are preserved; auto-attach adds only missing channel names.

### Result URI exists in metadata but S3 returns 404

The job recorded its intended result location before the pipeline completed. Read CloudWatch and `s3_uri_metadata` to
find the first failing stage.

## Cache errors

### Invalid `cache_refresh`

`cache_refresh` requires an effective `cache_base_dir` and cache mode exactly `run`. It does not refresh the encode
partition cache.

```json
{
  "hotvect_execution_parameters": {
    "cache_base_dir": "s3://example-bucket/hotvect-cache/",
    "cache": "run",
    "cache_refresh": true
  }
}
```

### `with_parameter` archive unavailable

`with_parameter` is a strict artifact pin. Verify the local file or S3 object and permissions. Use caching only when a
miss is allowed to recompute.

### No cache hit in a later SageMaker job

A local container path such as `/tmp/cache` is ephemeral. Use an `s3://...` cache base for reuse between jobs, then
confirm the same algorithm cache key, `parameter_version`, and cache mode.

## Prediction and parameter errors

### `requires local state storage but this runtime only provides scratch storage`

The selected definition declares `requires_local_state_storage: true`, but the repository downloader was created
without a local-state root. Configure the containing application with separate scratch and local-state roots and use
the `AlgorithmDownloader` constructor that accepts both. The current `hv serve` modes do not expose this capability, so
use an application integration test for such an algorithm. Do not point the definition at scratch as an implicit
fallback: runtime state has separate ownership and cleanup semantics.

### Required model entry missing from the parameters ZIP

Inspect the archive:

```bash
unzip -l /path/to/parameters.zip
```

The loaded factory defines the required entry. For example, `CatBoostGreedyRankerFactory` expects
`model_parameter/model.parameter`. Use a parameters artifact produced for that factory and Hotvect line; do not
silently repack an archive without proving the layout contract.

### Prediction or audit has unexpected feature values

Run both commands on a small identical source slice:

```bash
hv audit ... --ordered --samples 100 --dest-path ./audit
hv predict ... --ordered --samples 100 --dest-path ./predict
```

If the audit differs, inspect transformation and namespace wiring. If the audit matches but prediction differs,
inspect parameter loading, scorer construction, and action identity.

## Profiling after a regression is confirmed

Record Java Flight Recorder data with an explicit output path:

```bash
hv performance-test ... -- \
  -XX:StartFlightRecording=filename=/path/to/profile.jfr,settings=profile
```

Keep the same source, parameters, load, thread count, and runtime as the confirmed comparison. See
[Reliable performance benchmarking](../../guides/performance-benchmarking/index.md) before interpreting a profile.

## Report a reproducible issue

Include:

- `hv --version` and `java -version`;
- the exact command with secrets removed;
- the effective algorithm definition;
- `result.json` and the first failing stage log;
- JAR, parameter, source, and test-date identities;
- expected and actual behavior.

Open a [Hotvect GitHub issue](https://github.com/zalando/hotvect/issues) for a reproducible framework defect.
Do not attach credentials, proprietary data, private artifact URLs, or unsanitized logs.
