---
title: How to Upgrade Hotvect on SageMaker (custom.py)
description: Run a stable base training image and install/override the Hotvect version at runtime via script-mode custom.py
tags: [sagemaker, script-mode, custom.py, upgrade, hotvect, wheelhouse]
difficulty: advanced
prerequisites:
  - AWS credentials / SageMaker execution role with access to your S3 prefixes
  - A base Hotvect SageMaker training image that supports script-mode execution
  - Ability to build Hotvect wheels (and any internal wheel dependencies)
related_docs:
  - ../sagemaker-backtests/index.md
  - ../../design/sagemaker-configuration/index.md
  - ../patterns/override-files/index.md
---

# How to: Upgrade Hotvect on SageMaker using `custom.py` (script mode)

## Why this exists

Sometimes you need to run an algorithm JAR on SageMaker using a **different Hotvect runtime** than what is baked into the training image:

- You want to keep the **base training image stable** (security patches, JVM, system deps, CUDA, etc.)
- You need to pick up a **Hotvect Python/JVM compatibility fix** quickly
- You need a reproducible way to run “Hotvect version X” without building and publishing a brand new Docker image

Hotvect supports this via a **script-mode hook**: when a SageMaker job definition includes a special hyperparameter, the container runs a payload zip containing `custom.py` instead of the default training entrypoint.

## First, clear up the terminology

There is **one SageMaker container entrypoint**, not two separate container entrypoints:

- `python/bin/sagemaker-entrypoint`

That entrypoint dispatches between execution modes based on the job hyperparameters:

- **Regular mode**: no `s3_uri_custom_jar` and no `hotvect_task`; run the normal `SagemakerAlgorithmPipelineRebuilder().rebuild_pipeline_and_run()` flow.
- **Script-mode**: `s3_uri_custom_jar` is present; download a payload zip and run `custom.py`.
- **One-shot task mode**: `hotvect_task` is present; run the dedicated one-shot task handler.

The base training image already contains Hotvect plus the SageMaker entrypoint. Script-mode does **not** install Hotvect into the image itself. Instead, it bootstraps or overrides the Hotvect runtime **inside the running training job container**.

## How Hotvect script-mode works (current implementation)

The training image entrypoint is `python/bin/sagemaker-entrypoint`.

- If hyperparameter `s3_uri_custom_jar` is **not** present, the container runs the normal flow (`SagemakerAlgorithmPipelineRebuilder().rebuild_pipeline_and_run()`).
- If hyperparameter `s3_uri_custom_jar` **is** present, the container switches to **script-mode**:
  1. Download the S3 object at `s3_uri_custom_jar`.
  2. Unpack it as a zip archive.
  3. Execute `custom.py` from the archive.
  4. Pass a single CLI argument: a JSON file path that contains the job hyperparameters (plus a few injected convenience fields).

Important notes:

- Despite the name `s3_uri_custom_jar`, the script-mode payload is a **zip**, not a JAR.
- The zip **must** contain `custom.py` at the archive root.
- `hv backtest` / `hv train` do **not** currently build or upload this payload for you automatically. Script-mode is activated only when your submitted job definition already includes `HyperParameters.s3_uri_custom_jar` (or another launcher/tool sets it).

You can see this logic in:

- `python/bin/sagemaker-entrypoint` (switching on `s3_uri_custom_jar`)
- `python/hotvect/sagemaker_exp.py` (`SageMakerScriptExecutor` downloads + unpacks + runs `custom.py`)

## The “upgrade Hotvect” pattern

The standard approach inside `custom.py` is:

1. **Download a wheelhouse** from S3 (a directory of `.whl` files)
2. Install the desired versions into a **virtualenv**
3. Run the normal Hotvect SageMaker execution (rebuild pipeline from hyperparameters and execute)

This lets you:

- keep `AlgorithmSpecification.TrainingImage` pinned to a known-good base image
- override the Hotvect runtime version via wheels

## When to use regular mode vs script-mode

Use **regular mode** when:

- the Hotvect version baked into the SageMaker training image is the one you want
- you do not need extra Python dependencies beyond what the image already provides
- you want the simplest, most supported `hv backtest` / `hv train` path

Use **script-mode** when:

- you need to run a newer or older Hotvect runtime than the image contains
- you need to keep a stable base image but swap Python-level runtime pieces per job
- you need extra Python packages for the run and do not want to publish a new training image first
- you are working around a legacy image that should keep its OS/JVM/system setup, but must run a different Hotvect runtime
- you are launching a custom SageMaker UDF payload that uses `custom.py` as the runner

Tradeoffs of script-mode:

- you must prepare and upload a payload zip yourself
- you must manage a compatible wheelhouse
- there is no first-class `hv backtest --script-mode-payload ...` workflow yet
- debugging has one extra layer because failures can happen before the normal pipeline rebuild starts

If you are unsure, start with **regular mode**. Only move to script-mode when the image-baked runtime is the problem.

### Standardized contract between job definition and `custom.py` (recommended)

At minimum your job definition should provide:

- `HyperParameters.s3_uri_custom_jar`: S3 URI to the payload zip (must contain `custom.py` at zip root)
- `Environment.INTERNAL_WHEELS_S3_URI`: S3 prefix containing the **internal wheelhouse** (must end with `/`)
- `Environment.HOTVECT_VERSION` (optional; if set, `custom.py` should verify the installed Hotvect version)
- `Environment.SAGEMAKER_TAR_INCLUDE_OUTPUT` (optional; default `false`)
- `Environment.SAGEMAKER_TAR_INCLUDE_METADATA` (optional; default `false`)

Standardize the payload zip contents:

- `custom.py` (**required**)
- `requirements-internal.txt` (**required**; must include `hotvect==<target>` and any other internal wheels)
- `requirements-public.txt` (optional; PyPI requirements)
- `constraints.txt` (optional; pins to make installs reproducible)

And Hotvect still needs the usual hyperparameters for the pipeline rebuild (automatically injected when launching via `hv backtest`):

- `s3_uri_algorithm_jar`
- `s3_uri_algorithm_definition` (**preferred**)
- `s3_uri_result_file`
- `s3_uri_metadata`
- `_algo_def_*` fields (legacy fallback; deprecated)
- `_pipeline_params_*` fields (last_test_time, parameter_version, …)
- `_pipeline_context_*` fields (jvm options, max_threads, …)

### How to tell which mode a job used

After submission, inspect the training job hyperparameters:

- if `HyperParameters.s3_uri_custom_jar` is absent, the job ran in **regular mode**
- if `HyperParameters.s3_uri_custom_jar` is present, the job ran in **script-mode**

The backtest guide shows a `describe-training-job` pattern for this check. See [Run backtests on AWS SageMaker](../sagemaker-backtests/index.md).

## Step-by-step runbook

### 1) Pick a stable base training image

Choose a base image that you trust operationally (IAM auth, SSL, JVM, system libs). This image must already contain the Hotvect SageMaker entrypoint (`python/bin/sagemaker-entrypoint`).

You will keep this image stable and override Hotvect via wheel installation at runtime.

### 2) Build a wheelhouse for internal packages (including Hotvect)

To make upgrades repeatable for agents, split dependencies into **public** and **internal**:

- **Public deps**: installed from a (public) index at runtime (default: PyPI)
- **Internal deps**: installed only from an **S3 wheelhouse** (offline / no-index)

Recommended payload files:

- `requirements-public.txt` (optional): anything you expect to be available from PyPI
- `requirements-internal.txt` (**required**): internal packages + the Hotvect version you want to run (e.g. `hotvect==10.11.0`)
- `constraints.txt` (optional): pins for both public+internal to keep resolution stable

#### Build the internal wheelhouse

Your S3 wheelhouse must contain:

- `hotvect-<VERSION>-...whl` for the target version
- any other internal wheels referenced by `requirements-internal.txt`

#### Wheelhouse gotchas (missing wheels)

The internal wheelhouse is just a directory of `.whl` files. If `requirements-internal.txt` references a package that is not present in `INTERNAL_WHEELS_S3_URI`, installation will fail and the job will exit before running your pipeline.

Recommendations:

- Keep `requirements-internal.txt` minimal and explicit (`hotvect==...` plus any required internal wheels).
- Avoid hard-coded `pip install <internal>` calls in `custom.py`. Prefer declaring dependencies in `requirements-internal.txt` so the contract is explicit and reviewable.
- If a dependency is optional (only needed for some jobs), gate it behind an env var (e.g. `FOO_VERSION`) and/or a separate `requirements-internal-optional.txt` that `custom.py` installs only when present.

#### Optional internal deps (pattern)

Use this pattern to keep `custom.py` generic and keep dependency intent in files:

- `requirements-internal.txt` (**required**) – mandatory internal wheels (must exist in the wheelhouse)
- `requirements-internal-optional.txt` (optional) – internal wheels needed only for some runs

Recommended gating options:

- **Env var gate:** only install optional requirements when a specific env var is set (for example `FOO_VERSION` or `ENABLE_FOO=true`).
- **File gate:** only install optional requirements when `requirements-internal-optional.txt` is present in the payload.

If a wheel is required but missing from the wheelhouse, fail with a helpful error message that includes:

- the missing requirement(s)
- the wheelhouse source (`INTERNAL_WHEELS_S3_URI`)
- the intended fix (upload the missing wheel(s) or remove the requirement)

Example (local):

```bash
# from the hotvect repo
cd python

# build hotvect wheel from source
python -m pip wheel -w ./wheelhouse .

# OPTIONAL: if you have other internal wheels locally, add them into the wheelhouse directory
# cp /path/to/internal/*.whl ./wheelhouse/

# (optional) keep an audit trail
python -m pip freeze > ./wheelhouse/manifest.pip-freeze.txt
```

Upload the wheelhouse to S3 (prefix must end with `/`):

```bash
aws s3 sync ./wheelhouse s3://example-bucket/<prefix>/wheelhouse/hotvect-10.11.0/ \
  --exclude "*" --include "*.whl" --include "manifest.pip-freeze.txt"
```

Recommendations:

- Keep wheelhouses immutable: use a versioned prefix like `.../wheelhouse/hotvect-10.11.0/`.
- Build wheels for the **exact** runtime (Python version + platform) used by the training image.
- If your SageMaker job runs in a VPC without NAT / egress, PyPI installs will fail; in that case either provide a reachable mirror or also vendor public wheels into the wheelhouse and install everything with `--no-index`.

### 3) Write `custom.py`

To keep agent-driven upgrades reliable, `custom.py` should be deterministic and follow a strict install order:

1. Create a venv
2. Install **public** deps from a public index (default PyPI) using `requirements-public.txt` (optional)
3. Download the internal wheelhouse from S3
4. Install **internal** deps (including the pinned Hotvect version) from the wheelhouse using `requirements-internal.txt` (**required**) with `--no-index`
5. Run the normal Hotvect SageMaker pipeline rebuild+run

#### Script-mode output location (use `SAGEMAKER_TAR_INCLUDE_OUTPUT`)

If you want **all artifacts** (parameters zips, encoded shards, model files, etc.) included in SageMaker’s
`output.tar.gz`, set `SAGEMAKER_TAR_INCLUDE_OUTPUT=true` and ensure your script-mode runner writes output under
`/opt/ml/output/data`. If `SAGEMAKER_TAR_INCLUDE_OUTPUT=false` (default), you can keep large artifacts under `/tmp` and only
upload `meta/` logs.
To include metadata in `output.tar.gz`, set `SAGEMAKER_TAR_INCLUDE_METADATA=true` (default: false).

Example logic inside your script-mode runner:

```python
write_output = os.environ.get("SAGEMAKER_TAR_INCLUDE_OUTPUT", "false").lower() not in ("0", "false")
output_data_dir = Path("/opt/ml/output/data").resolve()
output_data_base_path = output_data_dir if write_output else Path("/tmp/hotvect_out").resolve()
```

Reference implementation (illustrative; customize for your environment):

```python
#!/usr/bin/env python3
import json
import os
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlparse


def _require_env(name: str) -> str:
    v = os.environ.get(name)
    if not v:
        raise RuntimeError(f"Missing required environment variable: {name}")
    return v


def _parse_s3_uri(uri: str) -> tuple[str, str]:
    p = urlparse(uri)
    if p.scheme != "s3":
        raise ValueError(f"Expected s3:// URI, got {uri!r}")
    return p.netloc, p.path.lstrip("/")


def _sync_wheels_from_s3(s3_uri_prefix: str, dest_dir: Path) -> None:
    # s3_uri_prefix like s3://example-bucket/prefix/wheelhouse/hotvect-10.11.0/
    if not s3_uri_prefix.endswith("/"):
        s3_uri_prefix += "/"
    bucket, prefix = _parse_s3_uri(s3_uri_prefix)

    try:
        import boto3  # type: ignore
    except Exception as e:
        raise RuntimeError(
            "boto3 is required in the base image to download INTERNAL_WHEELS_S3_URI; "
            "either bake boto3 into the image or replace this with an aws-cli based downloader"
        ) from e

    s3 = boto3.client("s3")
    dest_dir.mkdir(parents=True, exist_ok=True)

    paginator = s3.get_paginator("list_objects_v2")
    downloaded = 0
    for page in paginator.paginate(Bucket=bucket, Prefix=prefix):
        for obj in page.get("Contents", []):
            key = obj["Key"]
            if not key.endswith(".whl") and not key.endswith(".txt"):
                continue
            filename = key.rsplit("/", 1)[-1]
            s3.download_file(bucket, key, str(dest_dir / filename))
            downloaded += 1

    if downloaded == 0:
        raise RuntimeError(f"No wheels found under {s3_uri_prefix!r}")


def _pip_install_public(py: Path, requirements: Path, constraints: Path | None) -> None:
    cmd = [str(py), "-m", "pip", "install", "--no-input"]
    if constraints is not None:
        cmd += ["-c", str(constraints)]
    cmd += ["-r", str(requirements)]

    subprocess.run(cmd, check=True)


def _pip_install_internal(py: Path, wheel_dir: Path, requirements: Path, constraints: Path | None) -> None:
    cmd = [
        str(py),
        "-m",
        "pip",
        "install",
        "--no-input",
        "--no-index",
        "--find-links",
        str(wheel_dir),
    ]
    if constraints is not None:
        cmd += ["-c", str(constraints)]
    cmd += ["-r", str(requirements)]

    # Fail-fast: if requirements reference public deps that aren't already installed, pip will error (good signal).
    subprocess.run(cmd, check=True)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: custom.py <hyperparameters.json>")

    hyperparams_path = Path(sys.argv[1])
    _ = json.loads(hyperparams_path.read_text())

    wheelhouse_s3 = _require_env("INTERNAL_WHEELS_S3_URI")
    target_hotvect_version = os.environ.get("HOTVECT_VERSION")

    payload_dir = Path(__file__).resolve().parent
    req_public = payload_dir / "requirements-public.txt"
    req_internal = payload_dir / "requirements-internal.txt"
    constraints = payload_dir / "constraints.txt"

    if not req_internal.exists():
        raise RuntimeError("Payload is missing requirements-internal.txt (required)")

    work_dir = Path("/opt/ml/code")
    venv_dir = work_dir / "venv"
    wheel_dir = work_dir / "wheelhouse"

    work_dir.mkdir(parents=True, exist_ok=True)

    subprocess.run([sys.executable, "-m", "venv", str(venv_dir)], check=True)
    py = venv_dir / "bin" / "python"

    # Keep pip tooling sane.
    subprocess.run([str(py), "-m", "pip", "install", "--no-input", "--upgrade", "pip", "setuptools", "wheel"], check=True)

    cfile = constraints if constraints.exists() else None

    # 1) Public deps (PyPI by default)
    if req_public.exists():
        _pip_install_public(py, req_public, cfile)

    # 2) Internal deps (offline wheelhouse)
    _sync_wheels_from_s3(wheelhouse_s3, wheel_dir)
    _pip_install_internal(py, wheel_dir, req_internal, cfile)

    # Optional sanity checks
    subprocess.run([str(py), "-m", "pip", "check"], check=True)
    if target_hotvect_version:
        subprocess.run(
            [
                str(py),
                "-c",
                (
                    "import hotvect, sys; "
                    "v = hotvect.__version__; "
                    "print('hotvect', v); "
                    f"sys.exit(0 if v == '{target_hotvect_version}' else 1)"
                ),
            ],
            check=True,
        )

    # Run the normal Hotvect SageMaker flow from the upgraded environment.
    # We intentionally do NOT re-run python/bin/sagemaker-entrypoint, because that would re-enter script-mode.
    subprocess.run(
        [
            str(py),
            "-c",
            "from hotvect.sagemaker import SagemakerAlgorithmPipelineRebuilder; "
            "SagemakerAlgorithmPipelineRebuilder().rebuild_pipeline_and_run()",
        ],
        check=True,
    )


if __name__ == "__main__":
    main()
```

Notes:

- Use `/opt/ml/code` (or another writeable path) for the venv + wheel cache.
- This requires **network egress** for public installs; if you run without NAT, vendor public wheels into the wheelhouse or bake a pip config into the payload/container.
- Keep it **fail-fast**: missing env vars, missing wheels, or pip failures should raise immediately.

### 4) Package the payload zip

The zip must contain `custom.py` at the top level, and should include your standardized requirements files:

- `requirements-internal.txt` (required)
- `requirements-public.txt` (optional)
- `constraints.txt` (optional)

```bash
mkdir -p payload
cp custom.py payload/custom.py
cp requirements-internal.txt payload/requirements-internal.txt
# optional
cp requirements-public.txt payload/requirements-public.txt
cp constraints.txt payload/constraints.txt

(cd payload && zip -r ../hotvect-script-payload.zip .)
```

### 5) Upload the payload zip and wire it into the job

Upload zip:

```bash
aws s3 cp hotvect-script-payload.zip \
  s3://example-bucket/<prefix>/customjar/hotvect-script-payload.zip
```

Then inject it into the SageMaker training job definition:

```json
{
  "Environment": {
    "INTERNAL_WHEELS_S3_URI": "s3://example-bucket/<prefix>/wheelhouse/hotvect-10.11.0/",
    "HOTVECT_VERSION": "10.11.0"
  },
  "HyperParameters": {
    "s3_uri_custom_jar": "s3://example-bucket/<prefix>/customjar/hotvect-script-payload.zip"
  }
}
```

How you inject this depends on your workflow:

- If you launch via `hv backtest`, the cleanest approach is to use an **algorithm override JSON** (see [Override Files](../patterns/override-files/index.md)) that sets `sagemaker_training_job_definition.Environment` and `sagemaker_training_job_definition.HyperParameters`.
- Alternatively, you can set these fields directly in a per-run copy of your SageMaker template JSON.

### 6) Run a backtest

Run as usual (see [Run Backtests on AWS SageMaker](../sagemaker-backtests/index.md)). The only difference is the extra override/template fields.

## Debugging and validation

### Where to look first

- CloudWatch logs for the training job: your `custom.py` output is the earliest signal.
- The job output S3 prefix (from `OutputDataConfig.S3OutputPath` + `TrainingJobName`).
- `s3_uri_metadata` output: Hotvect writes metadata there during the normal pipeline execution.

### Common failure modes

- **Wrong payload layout**: `custom.py` not at zip root.
- **Missing S3 access**: the SageMaker execution role must read the wheelhouse + payload zip.
- **Dependency resolution failures**: you must include all wheels needed for offline execution (Hotvect + transitive deps).
- **ABI/platform mismatch**: wheels must match the container’s Python and platform (manylinux, etc.).

## How to improve this approach (recommended roadmap)

The current mechanism works, but the ergonomics are rough (manual zip creation, confusing `s3_uri_custom_jar` name, and missing first-class CLI support). Improvements that fit well in Hotvect:

1. **First-class CLI flags** (make it hard to misconfigure)
   - `hv backtest --sagemaker-script-payload-zip <local.zip>`
   - `hv backtest --internal-wheels-s3-uri s3://example-bucket/wheelhouse/.../`
   - Hotvect uploads the zip and sets `HyperParameters.s3_uri_custom_jar` automatically.

2. **Rename the hyperparameter** (clarity)
   - Keep backwards compatibility at the container level, but prefer a new key like `s3_uri_script_payload_zip`.

3. **Provide a generator command**
   - `hv-ext sagemaker-script-payload build ...`
   - Generates `custom.py` + optional helper scripts, embeds version pins, and produces a single zip.

4. **Bootstrap reporting**
   - Have `custom.py` write a small JSON report to `s3_uri_metadata` early (wheel versions, pip output, failures) to simplify debugging.

5. **Safety checks**
   - Validate that the upgraded Hotvect version is compatible with the algorithm JAR (at least major/minor compatibility checks), and fail fast with a clear message.

If you implement the roadmap, you keep the operational benefit (stable base image) while making upgrades repeatable and much less error-prone.
