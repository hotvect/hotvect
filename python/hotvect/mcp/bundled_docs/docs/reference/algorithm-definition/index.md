---
title: Algorithm definition contract
description: Agent-first contract for algorithm definition fields and where to override them
tags: [reference, algorithm-definition, overrides]
---

# Algorithm definition contract

Hotvect algorithms are defined by a JSON object (“algorithm definition”) that is typically embedded in the algorithm JAR.

Agents should treat this as a **contract**:

- Hotvect reads it from the JAR (or from `s3_uri_algorithm_definition` in SageMaker mode).
- Override JSON files are **recursive merge overlays** onto this object.

## High-signal top-level fields

- `algorithm_name` (string): the identifier used by `hv` commands
- `algorithm_version` (string): used in output paths and (by default) in cache keys
- `dependencies` (object): child algorithms keyed by dependency algorithm name
- `algorithm_parameters` (object): runtime knobs for worker backend, worker transport, and direct-worker process settings
- `training_command` (string): how training is executed (often via `/bin/bash -c ...`)
- `train_data_prefix`, `test_data_prefix` (string): data prefixes under `data_base_dir`
- `number_of_training_days`, `training_lag_days` (int): training window semantics

## `training_command` templating variables (Hotvect-injected)

`training_command` is treated as a Jinja2 template and rendered by Hotvect right before running the train stage.

Available template variables:

- `algorithm_definition_path`: path to the effective algorithm definition JSON that Hotvect writes into the metadata dir
- `algorithm_jar_path`: path to the algorithm JAR being executed
- `encoded_data_file_path`: path to the **encoded data directory** (trainer resolves shards inside it)
- `encoded_schema_description_file_path`: path to the schema description file emitted by encode
- `parameter_output_path`: path where the training command must write the resulting parameters ZIP
- `scratch_dir`: a scratch directory for the training command
- `python_executable`: resolved Python executable Hotvect is using (useful for `uv run python ...` / virtualenv workflows)
- `encode_parameter_path`: path to the encode parameters ZIP produced by Hotvect’s encode stage
  - This ZIP includes dependency artifacts that were packaged during encode, namespaced by dependency algorithm name.
  - Use this to avoid brittle path-globbing over dependency output directories.

Example snippet:

```json
{
  "training_command": "uv run python -m my_algo.train --tfrecords {{ encoded_data_file_path }} --encode-params {{ encode_parameter_path }} --out {{ parameter_output_path }}"
}
```

## `hotvect_execution_parameters` (runtime controls)

Common subkeys:

- `predict.enabled` / `evaluate.enabled` (bool)
- `performance-test.enabled` (bool)
- `performance-test.workload_mode` (`realtime|batch`)
- Caching:
  - `cache_base_dir` (string, local path or `s3://example-bucket`)
  - `cache_scope` (`major|minor|patch|hyperparam`)
  - `cache_refresh` (bool)

See: [Caching](../../guides/caching/index.md).

### `hotvect_execution_parameters.evaluation_function`

Hotvect supports either:

- a string name:

```json
{
  "hotvect_execution_parameters": {
    "evaluation_function": "standard_evaluation"
  }
}
```

- or an object with `name` plus `arguments`:

```json
{
  "hotvect_execution_parameters": {
    "evaluation_function": {
      "name": "standard_evaluation",
      "arguments": {
        "alpha": 123
      }
    }
  }
}
```

Notes:

- `name` must be a string key known to Hotvect’s evaluation-function registry.
- `arguments` must be an object when present.
- Hotvect passes `arguments` as keyword arguments to the selected evaluation function.

### `hotvect_execution_parameters.jvm_args`

Use this to declare JVM flags in the algorithm definition or an override file:

```json
{
  "hotvect_execution_parameters": {
    "jvm_args": ["-Xmx2g", "-XX:+ExitOnOutOfMemoryError"]
  }
}
```

You can also set task-specific JVM args under the task name:

```json
{
  "hotvect_execution_parameters": {
    "jvm_args": ["-Xmx2g"],
    "predict": {
      "jvm_args": ["-Xmx4g"]
    }
  }
}
```

Resolution order is:

1. `hotvect_execution_parameters.<task>.jvm_args`
2. `hotvect_execution_parameters.jvm_args`
3. CLI / pipeline-context JVM args

Hotvect rejects classpath changes here (`-cp` / `-classpath`).

### `hotvect_execution_parameters.performance-test.samples`

Use this to pin the number of examples used by the performance-test stage:

```json
{
  "hotvect_execution_parameters": {
    "performance-test": {
      "enabled": true,
      "samples": 123
    }
  }
}
```

This is useful when you want comparable perf-test runs across versions or environments without relying only on CLI flags.

### `hotvect_execution_parameters.performance-test.workload_mode`

Use this to choose which algorithm workload mode Hotvect passes into `performance-test`.

Supported values:

- `realtime` — default; use the algorithm's realtime runtime config and latency path
- `batch` — explicitly benchmark the batch path instead

Example:

```json
{
  "hotvect_execution_parameters": {
    "performance-test": {
      "enabled": true,
      "workload_mode": "realtime"
    }
  }
}
```

Notes:

- `hv performance-test` defaults to `realtime` because it is generally used to measure serving latency.
- `hv predict` continues to run with `batch` workload mode.
- CLI `--workload-mode` overrides the algorithm-definition value.

## `algorithm_parameters.direct_workers` (worker runtime knobs)

Hotvect keeps backend selection at the top level and runtime selection per scope:

- `algorithm_parameters.backend`
- `algorithm_parameters.realtime.litserve` or `algorithm_parameters.realtime.direct_workers`
- `algorithm_parameters.batch.litserve` or `algorithm_parameters.batch.direct_workers`

Migration note: older worker configs may still store the backend at `algorithm_parameters.workers.backend`. Move that value to `algorithm_parameters.backend` for the current worker contract and for `hv worker serve`.

Per scope, use the block that matches the runtime you want:

- `litserve`
- `direct_workers`

Example:

```json
{
  "algorithm_parameters": {
    "backend": "tensorflow",
    "realtime": {
      "litserve": {
        "python_executable": "/opt/venvs/my-worker/bin/python",
        "accelerator": "gpu",
        "devices": "auto",
        "workers_per_device": 1,
        "request_timeout_ms": 30000
      }
    },
    "batch": {
      "direct_workers": {
        "python_executable": "/opt/venvs/my-worker/bin/python",
        "accelerator": "cpu",
        "devices": "auto",
        "workers_per_device": 1,
        "startup_timeout_ms": 60000
      }
    }
  }
}
```

When `hv worker serve` starts a local LitServe process, interpreter precedence is:

1. `HOTVECT_PYTHON_EXECUTABLE`
2. the selected `litserve.python_executable`
3. `sys.executable`

See: [Direct Python Workers (UDS IPC)](../../design/direct-python-workers/index.md).

## Dependency overrides (must be nested)

To override a dependency algorithm, nest overrides under:

```
dependencies.<dependency_algorithm_name>
```

See: [Override files](../../guides/patterns/override-files/index.md), [Parent-child algorithms](../../guides/patterns/parent-child/index.md).
