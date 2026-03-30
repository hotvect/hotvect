# Script-mode standardization (SageMaker)

## Summary
Standardize SageMaker execution around a script-mode payload (`custom.py` + `run_pipeline.py`), support Hotvect upgrade/downgrade via a wheelhouse, and transport the effective algorithm definition via S3 (like the algorithm JAR).

This document describes the **runner contract** so that:

- launchers (`hv backtest`, internal tooling, custom scripts) can submit compatible jobs
- training images can evolve without breaking existing workloads
- script-mode payloads are interchangeable as long as they implement the contract

## Algorithm definition transport
- Upload the effective algorithm definition JSON to S3.
- Pass the URI via hyperparameter `s3_uri_algorithm_definition` (mirrors `s3_uri_algorithm_jar`).

## Script-mode runner contract

### When script-mode is active

Script-mode is active when the SageMaker training job hyperparameters include `s3_uri_custom_jar`.

Despite the name, `s3_uri_custom_jar` points to a **zip payload** (not a JAR) whose root must contain:

- `custom.py` (**required**) – entrypoint for script-mode
- `run_pipeline.py` (**recommended**) – a stable, testable runner invoked by `custom.py`

### Required hyperparameters

A script-mode runner must treat the following hyperparameters as source-of-truth pointers:

- `s3_uri_custom_jar` – S3 URI to the script-mode payload zip (used only to bootstrap execution)
- `s3_uri_algorithm_jar` – S3 URI to the algorithm JAR to execute
- `s3_uri_algorithm_definition` – S3 URI to the effective algorithm-definition JSON for this run
- `s3_uri_result_file` – S3 URI where `result.json` must be written
- `s3_uri_metadata` – S3 URI prefix where `meta/` must be uploaded (logs + effective config)

### Runner responsibilities

At minimum, the runner must:

1. **Download and unpack** `s3_uri_custom_jar` and execute `custom.py`.
2. **Resolve the algorithm inputs**:
   - download `s3_uri_algorithm_jar` to a local file
   - download `s3_uri_algorithm_definition` to a local JSON file
3. **Execute the pipeline** defined by the effective algorithm definition:
   - rebuild the pipeline from hyperparameters (or from a pipeline config file produced by the launcher)
   - run training/evaluation/predict stages as requested by the job
4. **Write results and metadata**:
   - produce `result.json` locally and upload it to `s3_uri_result_file`
   - upload `meta/` (stage logs, effective algodef, resolved overrides, timing info, etc.) to `s3_uri_metadata`
5. **Fail fast with clear errors** on missing required inputs (including missing wheels in the wheelhouse, invalid S3 URIs, or malformed algorithm definitions).

### Optional: local tracing archive (OpenTelemetry + Jaeger)

Some runners support capturing OpenTelemetry traces during a SageMaker job by starting a local Jaeger collector inside the container and exporting spans to it.

When supported, enable it via hyperparameters:

- `otel_trace_mode=local_jaeger`
- `s3_uri_jaeger_all_in_one` – Jaeger all-in-one binary (downloaded at runtime)
- `s3_uri_otel_javaagent` – OpenTelemetry Java agent jar (downloaded at runtime)
- `otel_service_name` (optional) – service name to emit
- `otel_trace_ratio` (optional) – sampling ratio `0..1` (default depends on the runner)

Expected output:

- The runner uploads a trace archive under the metadata prefix, typically `otel/jaeger-traces.tgz` next to `meta/`.

### Optional: Java Flight Recorder (JFR) for performance-test

Some runners support collecting Java Flight Recorder output for performance profiling during the **performance-test** stage only.

When supported, enable it via hyperparameter:

- `jfr_enabled=true` (runner-defined; intentionally off by default)
- `jfr_settings` (optional) – JFR settings, for example `profile`

Expected output:

- One or more `.jfr` files under the metadata prefix, typically `meta/.../jfr/performance-test-*.jfr`.

### Backward compatibility: algorithm-definition shapes

Script-mode must remain compatible with older algorithm-definition JSON shapes (within reason). In practice this means:

- **Treat unknown fields as pass-through** (don’t validate against a single pinned schema in the runner).
- **Be tolerant of common structural variants**, for example:
  - `dependencies` represented as a list or a map
  - top-level identity fields such as `algorithm_name`/`algorithm_version` vs `name`/`version`
- **Prefer** `s3_uri_algorithm_definition` when present, but for legacy launchers it is reasonable to fall back to embedded/legacy hyperparameters (for example `_algo_def_*`) when that URI is missing.

## Output tar inclusion (breaking change)
Replace `WRITE_OUTPUT_TO_S3` / `WRITE_METADATA_TO_S3` with:
- `SAGEMAKER_TAR_INCLUDE_OUTPUT` (default: false)
- `SAGEMAKER_TAR_INCLUDE_METADATA` (default: false)

`meta/` is still uploaded directly to `s3_uri_metadata`; tar inclusion is optional and only for bundling.

## Hotvect versioning
- Optional `HOTVECT_VERSION`.
- If `HOTVECT_VERSION` is set and differs from the preinstalled version, `INTERNAL_WHEELS_S3_URI` is required.
- Hotvect installs from `INTERNAL_WHEELS_S3_URI` (wheelhouse).
- Public deps can be installed from default PyPI; no explicit public index env vars.
