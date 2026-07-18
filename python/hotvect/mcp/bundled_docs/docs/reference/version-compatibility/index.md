---
title: Version compatibility
description: Decide whether a Hotvect runner, algorithm JAR, parameters archive, and training image form a supported runtime combination
tags: [reference, versions, compatibility]
related_docs:
  - ../../concepts/jar-loading/index.md
  - ../../migrations/v9-to-v10/index.md
  - ../../guides/sagemaker-backtests/index.md
---

# Version compatibility

Hotvect does not have one central compatibility check for every runner/JAR/artifact combination. Use one release as a
unit when possible, and validate any mixed-version path on the exact command it must support.

## Keep the two version systems separate

- The **Hotvect version** identifies the framework modules, Python package, CLI, and runtime JARs.
- The **algorithm version** identifies an algorithm artifact and its release line.

An algorithm version such as `1.2.3` does not imply a Hotvect version. Read the algorithm build file or JAR contents
to determine which framework modules it was compiled and packaged with.

## Components in a run

| Component | Usually supplied by |
| --- | --- |
| `hv`, `hv-ext`, `hv-exp`, pipeline orchestration | Installed `hotvect` Python package |
| Offline, serve, and demo JARs | Bundled with that Python package |
| `hotvect-api` and runner utilities | Runtime or application |
| `hotvect-core` and backend implementations | Algorithm JAR |
| Training runtime | Selected SageMaker image |
| Model/state files | Parameters ZIP |

`hotvect-python` is also the name of a Java module; it is distinct from the installable Python package named
`hotvect`.

## Conservative compatibility rules

1. Keep the installed Python package and its bundled runtime JARs on the same release.
2. Prefer an algorithm JAR built for the same Hotvect major as the runner or training image.
3. Package the algorithm-side modules the JAR directly uses; keep `hotvect-api` runtime-owned.
4. Treat the parameters ZIP layout as part of the algorithm/backend contract.
5. Do not infer support from a successful JAR load. Exercise the required predict, audit, train, or serve path.

A newer runner can sometimes execute an older algorithm JAR because algorithm implementation modules are packaged in
the JAR and the runtime APIs remain compatible. The reverse direction is more likely to fail because an older runtime
cannot provide newer API methods or classes. Neither direction is a blanket guarantee.

## Choose the validation by execution path

### Local CLI or offline task

Run the smallest command that crosses the boundary you care about:

```bash
hv --version
hv predict --algorithm-jar /path/to/algorithm.jar --algorithm-name <name> ...
```

For feature-only validation, use a bounded ordered audit. For training compatibility, a predict smoke test is not
enough; run a small local train or backtest through encode, train, and parameter packaging.

### Online integration

Validate the application runtime, algorithm JAR, and parameters ZIP together. The online loader isolates the
algorithm's `Namespaces` registry, but most classes still use parent-first loading. A parent-provided incompatible API
can therefore fail even when the algorithm class itself loads.

### SageMaker training or backtest

The training image supplies the Hotvect runtime; the algorithm JAR is uploaded separately. Confirm the submitted
`AlgorithmSpecification.TrainingImage`, then inspect the job's effective algorithm definition and logs. Updating a
local CLI does not change an already selected training image.

## Common failure signals

| Failure | Likely boundary |
| --- | --- |
| `UnsupportedClassVersionError` | Java runtime older than the compiled classes |
| `ClassNotFoundException` | Missing algorithm-side module or older runtime API |
| `NoSuchMethodError` | Binary API mismatch between parent runtime and algorithm classes |
| Missing model/state entry | Parameters ZIP layout does not match the loaded factory |
| Encoder output or metadata path error | Artifact contract changed between releases |

## v9 and v10

Hotvect v10 requires Java 21 and changed metadata and encoding output layouts. It also contains narrow compatibility
paths for some v9 APIs, but those do not establish general v9-on-v10 support. Follow the
[v9 to v10 migration guide](../../migrations/v9-to-v10/index.md) and validate the exact operation instead of
depending on an informal version matrix.
