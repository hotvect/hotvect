---
title: Configuration reference
description: Reference for ~/.hotvect/config.json used by hv, hv-ext, and hv-exp
tags: [reference, config]
---

# Configuration reference (`~/.hotvect/config.json`)

Hotvect CLIs (`hv`, `hv-ext`, `hv-exp`) read `~/.hotvect/config.json` for common defaults such as paths,
SageMaker template location, and experiment-management settings.

If a CLI flag is provided, it takes precedence over config.

## Create the file (recommended)

For a new config, initialize all directory values together:

```bash
test ! -e ~/.hotvect/config.json
hv-ext config init \
  --data-base-dir /path/to/data \
  --output-base-dir /path/to/output \
  --scratch-dir /path/to/scratch
```

`hv-ext config init` refuses to overwrite an existing file. Inspect and edit that file, or pass `--force` only when you
intend to replace the whole configuration; the command does not merge. Running `config init` without the three
directory flags prompts for them interactively.

You can optionally also configure experiment-management defaults at the same time:

```bash
hv-ext config init \
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
    `hv train` and `hv backtest` use `~/.hotvect/config.json` only when a required directory is not supplied explicitly.
    `hv train` can read `data_base_dir` and `output_base_dir`; `hv backtest` can also read `scratch_dir`. No config file is
    required when every required directory is present on the command line.

## Canonical JSON shape

Use the config object directly. This is required when `hv train` or `hv backtest` must resolve a SageMaker template
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

`hv-ext config show` returns a status envelope for machine-readable display. It is not a shared config file; use
`hv-ext config init` to create the file.

## `experiment_management` (optional)

Used when Hotvect needs to call an experiment-management service and you want to provide
an external token provider (a command that prints a bearer token). `hv-exp experiment results ...` also uses
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

The `online_results.slots` mapping is optional unless you want to use `hv-exp experiment results ...` without
passing `--s3-base-prefix`. In that case, Hotvect resolves `experiment_id -> slot ->
experiment_management.online_results.slots.<slot>.s3_base_prefix`.

## `directories`

- `data_base_dir`: used by local execution (`hv train`, local `hv backtest`) to locate training/test data
- `output_base_dir`: used as default output destination for local runs
- `scratch_dir`: used by `hv backtest` for temp checkouts/builds and staging artifacts

## `sagemaker`

Optional SageMaker defaults:

- `sagemaker.sagemaker_config_template`: default path used when `--sagemaker-config` is omitted in `hv train --sagemaker` / `hv backtest --sagemaker`.

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

## Environment variables (common in automation)

- `AWS_DEFAULT_REGION`: region used for SageMaker APIs
- `AWS_PROFILE`: select a named profile (if you have multiple roles/accounts configured)

Related: [Install Hotvect](../../guides/quickstart/index.md) for initial setup and
[Command-line interfaces](../cli/index.md) for precedence and command-specific flags.
