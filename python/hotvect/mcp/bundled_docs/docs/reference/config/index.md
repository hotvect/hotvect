---
title: Configuration reference
description: Reference for ~/.hotvect/config.json used by the unified hv CLI and compatibility commands
tags: [reference, config]
---

# Configuration reference (`~/.hotvect/config.json`)

The unified `hv` CLI reads `~/.hotvect/config.json` for common defaults such as paths, SageMaker template location,
experiment-management settings, and QA workflow defaults. The legacy `hv-ext` and `hv-qa` commands read
the same file; migrated invocations print a warning to use the canonical `hv` command.

If a CLI flag is provided, it takes precedence over config.

## Create the file (recommended)

For a new config, initialize all directory values together:

```bash
test ! -e ~/.hotvect/config.json
hv config init \
  --data-base-dir /path/to/data \
  --output-base-dir /path/to/output \
  --scratch-dir /path/to/scratch
```

`hv config init` refuses to overwrite an existing file. Inspect and edit that file, or pass `--force` only when you
intend to replace the whole configuration; the command does not merge. Running `config init` without the three
directory flags prompts for them interactively.

You can optionally also configure experiment-management defaults at the same time:

```bash
hv config init \
  --data-base-dir /path/to/data \
  --output-base-dir /path/to/output \
  --scratch-dir /path/to/scratch \
  --experiment-management-url https://experiments.example.com \
  --experiment-management-token-provider-command "printenv EXAMPLE_TOKEN" \
  --experiment-management-token-provider-ttl-ms 3600000 \
  --experiment-management-online-results-slot example-slot-a=s3://example-bucket-a/path/to/results/ \
  --experiment-management-online-results-slot example-slot-b=s3://example-bucket-b/path/to/results/
```

!!! note "Config existence"
    `hv algorithm train` and `hv algorithm backtest` use `~/.hotvect/config.json` only when a required directory is not supplied explicitly.
    `hv algorithm train` can read `data_base_dir` and `output_base_dir`; `hv algorithm backtest` can also read `scratch_dir`. No config file is
    required when every required directory is present on the command line.

## Canonical JSON shape

Use the config object directly. This is required when `hv algorithm train` or `hv algorithm backtest` must resolve a SageMaker template
from `sagemaker.sagemaker_config_template`:

```json
{
  "directories": {
    "data_base_dir": "/path/to/data",
    "output_base_dir": "/path/to/output",
    "scratch_dir": "/path/to/scratch"
  }
}
```

`hv config show` returns a status envelope for machine-readable display. It is not a shared config file; use
`hv config init` to create the file.

## `experiment_management` (optional)

Used when Hotvect needs to call an experiment-management service and you want to provide
an external token provider (a command that prints a bearer token). `hv exp experiment results ...` also uses
this section to resolve slot-specific online-results S3 prefixes.

Example:

```json
{
  "experiment_management": {
    "url": "https://experiments.example.com",
    "token_provider_command": "printenv EXAMPLE_TOKEN",
    "token_provider_ttl_ms": 3600000,
    "connect_timeout_seconds": 5.0,
    "read_timeout_seconds": 15.0,
    "online_results": {
      "slots": {
        "example-slot-a": {
          "s3_base_prefix": "s3://example-bucket-a/path/to/results/"
        },
        "example-slot-b": {
          "s3_base_prefix": "s3://example-bucket-b/path/to/results/"
        }
      }
    }
  }
}
```

The timeout fields are optional. If omitted, Hotvect uses a 5 second connect timeout and a 15 second read timeout
for service calls.

The `online_results.slots` mapping is optional unless you want to use `hv exp experiment results ...` without
passing `--s3-base-prefix`. In that case, Hotvect resolves `experiment_id -> slot ->
experiment_management.online_results.slots.<slot>.s3_base_prefix`.

## `directories`

- `data_base_dir`: used by local execution (`hv algorithm train`, local `hv algorithm backtest`) to locate training/test data
- `output_base_dir`: used as default output destination for local runs
- `scratch_dir`: used by `hv algorithm backtest` for temp checkouts/builds and staging artifacts

## `sagemaker`

Optional SageMaker defaults:

- `sagemaker.sagemaker_config_template`: default path used when `--sagemaker-config` is omitted in `hv algorithm train --sagemaker` / `hv algorithm backtest --sagemaker`.

Example:

```json
{
  "directories": {
    "data_base_dir": "/path/to/data",
    "output_base_dir": "/path/to/output",
    "scratch_dir": "/path/to/scratch"
  },
  "sagemaker": {
    "sagemaker_config_template": "~/.hotvect/sagemaker-template.json"
  }
}
```

## `qa` (optional)

Workflow-specific defaults for `hv qa` live under `qa.run`. They keep release execution mechanics out of ordinary
algorithm settings. For the workflow and criteria model, see
[Release QA validation with hv qa](../../guides/hv-qa-release-validation/index.md).

This is a safe minimal local configuration:

```json
{
  "directories": {
    "data_base_dir": "/path/to/backtest-data",
    "output_base_dir": "/path/to/hotvect-output",
    "scratch_dir": "/path/to/hotvect-scratch"
  },
  "qa": {
    "run": {
      "defaults": {
        "backtest_days": 7,
        "evaluation_criteria": "noninferiority"
      },
      "execution": {
        "algo_repo_url": "/path/to/algorithm-repository",
        "data_base_dir": "/path/to/backtest-data",
        "scratch_dir": "/path/to/hv-qa-scratch"
      },
      "system_performance": {
        "runner": "local",
        "max_threads": 2,
        "trials": 7
      },
      "backtest": {
        "runner": "local"
      }
    }
  }
}
```

### Defaults and execution context

- `qa.run.defaults.backtest_days`, `evaluation_criteria`: optional defaults for `hv qa candidate start`.
  `backtest_days` otherwise defaults to 7. `hv qa` discovers the latest contiguous control test-data window unless
  `--last-test-date` explicitly selects one. Use the versioned built-in criteria `exact`, `noninferiority`, or
  `superiority`.
- `qa.run.execution.algo_repo_url`: shared default repository. `control_algo_repo_url` and
  `treatment_algo_repo_urls` select per-arm repositories; the treatment list must follow `--treatment` order.
- `qa.run.execution.parameter_source`: shared fixed-parameter source for parameter-backed stages. Do not set it for
  `exact`: that criterion rejects manual/configured parameter sources and derives the latest control parameter.
- `qa.run.execution.algorithm_name`, `encode_algorithm_name`: optional names; `hv-qa` derives them from the control
  artifact where possible.
- `qa.run.execution.source_path`, `predict_source_path`, `performance_source_path`, `encode_source_path`: shared
  input paths. Stage-specific paths fall back to `source_path` and then to the relevant test-data specification.
- `qa.run.execution.data_base_dir`, `scratch_dir`: defaults for local realistic backtests; the top-level
  `directories` values are fallback defaults.

Per-reference parameters and system-performance sources are intentionally CLI-only. Pass
`--control-parameter-source` plus one `--treatment-parameter-source` per treatment, and likewise for
`--control-performance-source-path` / `--treatment-performance-source-path`.

### System performance

`qa.run.system_performance` accepts:

- `runner` (`auto`, `local`, or `sagemaker`), `trials` (default `7`), `max_threads`, and `poll_seconds` (default `60`);
- `sagemaker_job_prefix`, `sagemaker_config`, `role_arn`, `assume_role_arn`, `s3_output_base`, `instance_type`,
  `volume_gb`, `max_runtime_seconds`, and `training_image` for remote execution; and
- `samples`, `sample_pool_size`, `target_rps`, `target_throughput_fraction`, and `workload_mode` only when they
  exactly match each compared algorithm's committed performance-test specification.

The committed algorithm definition remains authoritative for those last five comparison fields. Omit them from
ordinary config so an accidental mismatch fails clearly rather than silently changing the benchmark.

### Realistic backtests

`qa.run.backtest` accepts `runner`, `algorithm_overrides`, `no_performance_test`,
`performance_test_samples`, `performance_test_sample_pool_size`, and the same SageMaker transport fields as system
performance, plus `auto_attach_data_default_s3_base`, `auto_attach_data_environment`, and `poll_seconds`.

For a standard noninferiority or multi-day QA run, leave `no_performance_test` unset (its default is `false`) and do
not set the two performance-test override fields unless they exactly match the committed specification. Backtest
overrides can be omitted, supplied once for every arm, or supplied once per arm in control-then-treatment order.

### Run-state location and precedence

- These values are used by `hv qa candidate start`; `hv qa evaluate` intentionally requires `--proof-dir`,
  `--last-test-date`, `--days`, and `--criteria` explicitly.
- Public `hv qa candidate start` flags override matching defaults, while execution mechanics remain in this namespace.
- Without `--work-dir`, run state is written beneath `directories.output_base_dir` at
  `meta/hv-qa/runs/<run-id>/`.
- Top-level `sagemaker.sagemaker_config_template` is the fallback template when the corresponding QA SageMaker
  template is omitted.

## Environment variables (common in automation)

- `AWS_DEFAULT_REGION`: region used for SageMaker APIs
- `AWS_PROFILE`: select a named profile (if you have multiple roles/accounts configured)

Related: [Install Hotvect](../../guides/quickstart/index.md) for initial setup and
[Command-line interfaces](../cli/index.md) for precedence and command-specific flags.
