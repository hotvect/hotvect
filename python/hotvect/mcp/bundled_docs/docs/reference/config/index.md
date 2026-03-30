---
title: Configuration reference
description: Reference for ~/.hotvect/config.json used by hv/hv-ext
tags: [reference, config]
---

# Configuration reference (`~/.hotvect/config.json`)

Hotvect CLIs (`hv`, `hv-ext`) read `~/.hotvect/config.json` for common defaults (paths, SageMaker template location).

If a CLI flag is provided, it takes precedence over config.

## Create the file (recommended)

The easiest way to create a valid config is:

```bash
hv-ext config init
```

You can also run it non-interactively by passing all three directories:

```bash
hv-ext config init \
  --data-base-dir /path/to/data \
  --output-base-dir /path/to/output \
  --scratch-dir /path/to/scratch
```

You can optionally also configure experiment-management (formerly “EMS”) defaults at the same time:

```bash
hv-ext config init \
  --data-base-dir /path/to/data \
  --output-base-dir /path/to/output \
  --scratch-dir /path/to/scratch \
  --experiment-management-url https://ems.example.com \
  --experiment-management-token-provider-command "bash -lc 'echo $EMS_TOKEN'" \
  --experiment-management-token-provider-ttl-ms 3600000
```

!!! note "Config existence"
    `hv train` and `hv backtest` do **not** behave the same here.

    - `hv train` uses `~/.hotvect/config.json` only as a fallback when `--data-base-dir` or `--output-base-dir` is missing.
    - `hv backtest` still loads `~/.hotvect/config.json` unconditionally today, even if you pass all three directory flags explicitly.

    Creating the file via `hv-ext config init` avoids that backtest footgun and keeps CLI behavior predictable across commands.

## Accepted JSON shapes

Hotvect accepts either:

1) The config object directly:

```json
{
  "directories": {
    "data_base_dir": "/path/to/data",
    "output_base_dir": "/path/to/output",
    "scratch_dir": "/path/to/scratch"
  }
}
```

2) A wrapper object with top-level key `config` (some tools output this shape):

```json
{
  "config": {
    "directories": {
      "data_base_dir": "/path/to/data",
      "output_base_dir": "/path/to/output",
      "scratch_dir": "/path/to/scratch"
    }
  }
}
```

## Minimal example

```json
{
  "directories": {
    "data_base_dir": "/path/to/data",
    "output_base_dir": "/path/to/output",
    "scratch_dir": "/path/to/scratch"
  }
}
```

## `experiment_management` (optional)

Used when Hotvect needs to call the experiment-management service (formerly “EMS”) and you want to provide
an external token provider (a command that prints a bearer token).

Example:

```json
{
  "experiment_management": {
    "url": "https://ems.example.com",
    "token_provider_command": "bash -lc 'echo $EMS_TOKEN'",
    "token_provider_ttl_ms": 3600000
  }
}
```

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

- `AWS_DEFAULT_REGION`: required when using SageMaker APIs (typical: `eu-central-1`)
- `AWS_PROFILE`: select a named profile (if you have multiple roles/accounts configured)
