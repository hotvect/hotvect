---
title: Hotvect Versions and Compatibility
description: How Hotvect semantic versioning works, which components need to match, and how to reason about compatible combinations
tags: [reference, versions, compatibility, semver]
difficulty: intermediate
estimated_time: 15 minutes
related_docs:
  - ../../concepts/jar-loading/index.md
  - ../../guides/sagemaker-backtests/index.md
  - ../../guides/sagemaker-upgrade-custom-py/index.md
  - ../../guides/patterns/branching-and-versioning/index.md
  - ../faq/index.md
related_commands:
  - hv
  - hv backtest
  - hv serve
  - hv-exp
---

# Hotvect Versions and Compatibility

## Summary

If you only remember six things, remember these:

1. Hotvect itself uses **semantic versioning**: major means breaking changes, minor means new features or improvements, patch means bug fixes.
2. Do **not** confuse the **Hotvect framework version** with the **algorithm version** in an algorithm repository. They solve different problems.
3. Compatibility depends on **what you are combining**: CLI/runner, online serving runtime, offline training runtime, training container, or algorithm-bundled modules.
4. **Same major** is the only universal safe baseline.
5. The most common mixed-major direction is **newer runner/CLI with older algorithm JAR**, not the other way around.
6. Mixing **minor** and **patch** versions is generally fine. The installed `hotvect` Python package plus `hotvect-api`, `hotvect-online-util`, and `hotvect-offline-util` are runtime-side components, while `hotvect-core`, backend modules, and the `hotvect-python` Java integration module usually live with the algorithm JAR. That split is why some combinations are much more flexible than others.

## 1. Hotvect Itself Uses Semantic Versioning

For the Hotvect framework itself, semantic versioning has its usual meaning:

- **major**: breaking changes
- **minor**: new features or improvements
- **patch**: bug fixes

That is different from the versioning of many algorithm repositories built on top of Hotvect. Some algorithm repositories use major versions mainly as operational release lines. Hotvect itself does not: its major versions are about framework-level compatibility.

## 2. What Components Exist?

At the source level, Hotvect has both JVM modules and a Python distribution.

The parent POM defines the main JVM modules:

- `hotvect-api`
- `hotvect-processor`
- `hotvect-core`
- `hotvect-catboost`
- `hotvect-tensorflow`
- `hotvect-python`
- `hotvect-online-util`
- `hotvect-offline-util`
- `hotvect-algorithm-demo`

In addition, there is the installable Python package:

- `hotvect`

That Python package is not just documentation or glue code. It is the user-facing CLI and orchestration library. It installs entrypoints such as `hv`, `hv-ext`, `hv-exp`, `hv-mcp`, `catboost_train`, and `sagemaker-entrypoint`, and it bundles the offline/demo JARs used by those commands.

Those components do not all play the same role in compatibility.

> **Important naming distinction**
>
> `hotvect-python` is a **JVM module** used for Python runtime integration from Java.
> `hotvect` is the **Python package** users install locally or into training environments.

> **Build-time-only note**
>
> `hotvect-processor` is a build-time annotation processor. It matters for compilation, but it is not part of the runtime ownership split discussed below.

## 3. The Important Split: Runtime-Side vs Algorithm-Side

The most important compatibility distinction in Hotvect is **where a module lives at runtime**.

### Runtime-side modules

These are effectively part of the runner or container, or they orchestrate that runtime from Python:

- `hotvect-api`
- `hotvect-online-util`
- `hotvect-offline-util`
- the `hotvect` Python package
- the CLI entrypoints it installs, such as `hv`, `hv-ext`, `hv-exp`, and `hv-mcp`
- the bundled `hotvect-offline-util-...-jar-with-dependencies.jar`
- the training container image used for backtests or training jobs

Hotvect effectively has two runner contexts:

- an **online runner**, usually embedded into a real-time serving application or API
- an **offline runner**, used by the training pipeline, backtests, and batch inference flows

These runtime-side components are shared by whichever runner process is executing the algorithm, or they are the tooling layer that launches that runner.

### Algorithm-side modules

These are typically bundled into the algorithm JAR itself:

- `hotvect-core`
- `hotvect-catboost`
- `hotvect-tensorflow`
- `hotvect-python` (the Java module, when your algorithm uses its factories or direct-worker integrations)
- your own shared algorithm libraries

These are algorithm-specific implementation modules.

## 4. Why This Split Exists

Hotvect loads algorithm JARs dynamically in both online and offline flows, but the strongest isolation mechanism is in the **online hot-deploy path**, which uses a child-first classloader for selected classes.

That isolation matters because it allows different algorithms in the same long-lived process to carry different versions of algorithm-side modules such as `hotvect-core`.

This design is reflected both in code and in the migration history:

- `HotvectFactory` builds a `StrictChildFirstClassLoader` for algorithm JARs
- the child-first loader explicitly forces some classes, such as `com.hotvect.core.transform.Namespaces`, to be loaded from the algorithm JAR
  so each algorithm keeps its own isolated namespace registry instead of sharing identity-based namespace singletons across algorithms
- the offline utility also loads algorithm JARs dynamically, but it does so with its own `URLClassLoader` rather than the strict child-first loader
- the v7→v8 migration notes explain that `hotvect-core` was removed from offline and online runtime modules so that it is only provided by the algorithm JAR

That is why compatibility is more flexible for algorithm-side modules than for runtime-side modules.

## 5. What Usually Needs To Match?

Use this as the default mental model:

| Component pair | Default rule |
|---|---|
| Hotvect runtime-side modules inside one Hotvect release | Match the same Hotvect framework version |
| Installed `hotvect` Python package vs its bundled offline/demo JARs | Prefer the same packaged Hotvect release; treat one install as a unit rather than hand-mixing pieces from different installs |
| Runner/CLI vs algorithm JAR | Same major is the safe baseline |
| Newer runner vs older algorithm JAR | Usually the best mixed-major direction |
| Older runner vs newer algorithm JAR | Not guaranteed |
| `hotvect-core` / backend modules inside algorithm JAR vs runner | Exact minor or patch match usually not required if APIs still line up |
| Training container vs algorithm JAR | Same major is the safe baseline |
| Local CLI vs old output files | Often works, but depends on whether the relevant file formats changed |

In general, you do **not** need to line up every minor and patch version across the whole system. The major version is the main compatibility boundary.

For local tooling, `hv --version` is a useful sanity check because it prints both the installed Python package version and the bundled Hotvect JAR version.

## 6. Situation A: CLI / Runner / Local Tooling

For the CLI and local-tooling case, the relevant components are:

- the `hotvect` Python package
- `hv`
- `hv-ext`
- `hv-exp`
- the bundled `hotvect-offline-util-...-jar-with-dependencies.jar`

This combination is comparatively lax.

As a practical check, `hv --version` prints both the Python package version and the bundled JAR version.

### Safe rule

- **same major** is the safe baseline

### Commonly working mixed-major direction

- **newer** CLI or runner with an **older** algorithm JAR

This is the normal direction because the newer runner usually still understands older APIs and older file layouts.

### Not guaranteed

- **older** CLI or runner with a **newer** algorithm JAR

This is where `ClassNotFoundException`, `NoSuchMethodError`, or similar runtime issues become much more likely.

### Why the CLI story is lax

The CLI mainly orchestrates Java tasks and processes offline artifacts. For some tasks, it can process outputs or files from much older versions as long as the relevant file formats and metadata shapes have not changed in incompatible ways.

So for CLI compatibility, the practical answer is:

- same major is the guarantee
- newer runner with older artifacts often works
- beyond that, validate on the specific task you care about

## 7. Situation B: Online Inference and Predict Paths

This is the case where you already have a parameter ZIP and want to run the algorithm:

- in a long-lived online process
- in a batch prediction path
- or in another runner that behaves like online inference

This path is stricter than the general CLI case because long-lived online systems may need to host multiple algorithm lines at once.

### Safe rule

- **same major** between the runtime and the algorithm line

### Online runner guarantee

For the **online runner**, Hotvect guarantees that a runner on major `X` can continue running algorithms built against `X-1`.

This matters because online systems often need multiple algorithm lines to coexist in one process during rollout or migration.

### Practical rule

Use this hierarchy:

1. same major
2. for the **online runner**, a runner on major `X` is guaranteed to run algorithm JARs built against `hotvect-api` major `X-1`
3. anything looser only after explicit validation

Do **not** plan migration around the reverse direction. An older runner loading a newer algorithm JAR is not the path Hotvect is optimized for.

## 8. Situation C: Offline Training, Backtests, and Training Containers

Offline training and backtests are different from online inference because each run happens in its own process.

That changes the operational tradeoff:

- you usually do **not** need many Hotvect majors to coexist inside one JVM
- you can simply run the appropriate offline runtime or training container for the algorithm line you are evaluating

### Safe rule

- **same major** between the offline runtime or training image and the algorithm line

### Why this is enough in practice

Because training jobs are isolated, using the matching major is usually straightforward. You are not forced into multi-major coexistence inside a single long-running application process the way you often are online.

### If you hit a mismatch in SageMaker

The usual fix is:

- update the `training_container` or training image
- do **not** rebuild the algorithm just to "match" the local Hotvect install

That is also the guidance in the SageMaker backtests guide.

## 9. Training Containers Are Special, but the Principle Is the Same

Training containers are still part of the offline story, but the container path has one extra operational layer: the selected `TrainingImage`.

In regular SageMaker mode:

- the training image provides the Hotvect runtime that will execute the job
- the algorithm JAR is **not** baked into that image; it is uploaded separately and passed in via `s3_uri_algorithm_jar`

So the main compatibility question for a training container is whether the Hotvect runtime in the selected image can run the algorithm JAR that the job downloads at runtime.

The main rule is still the same:

- **same major** is the safe baseline

However, there is one practical escape hatch:

- you can sometimes take an older pre-baked container and install a newer Hotvect inside it via script-mode / `custom.py`

That is useful when you want to test a change quickly without building a fresh base image first.

Treat that as a **convenience technique**, not as the primary compatibility guarantee.

## 10. What the Code Actually Checks Today

Not all compatibility policy is enforced centrally. Some of it is a support convention rather than a single hard validator.

A few useful details:

- the parent POM versions the Hotvect framework modules together as one framework release
- the SageMaker executor already contains some fail-fast checks for legacy images that cannot honor newer override behavior

So when deciding whether a combination is "supported," distinguish between:

- **hard runtime checks**
- **documented compatibility expectations**
- **empirically known working combinations**

## 11. Minor and Patch Versions

In general, minor and patch versions are much less important than the major version boundary.

As a rule of thumb:

- mixing different **minor** or **patch** versions is generally fine
- do not assume you need exact `X.Y.Z == X.Y.Z` matching everywhere
- when something fails, the cause is usually a major compatibility issue, classpath ownership issue, or packaging issue, not a harmless patch difference

## 12. How To Reason About a Combination

The safest way to reason about compatibility is to classify the situation first, then apply the version rules.

1. **Identify the execution path**
   - Is this an **online runner**?
   - Is this **local CLI / offline tooling**?
   - Is this a **training container** or other offline job runtime?
2. **Check the Hotvect major boundary**
   - **same major** is the safe baseline everywhere
   - for the **online runner**, major `X` is also guaranteed to run algorithms built against `X-1`
   - the reverse direction, **older runner + newer algorithm**, is not the path Hotvect is designed to support
3. **Check class ownership**
   - the runtime side should provide `hotvect-api`, the runner utilities, and the Python tooling layer
   - the algorithm JAR should provide `hotvect-core` and whichever backend modules it uses
4. **Check whether you are using a standard or custom path**
   - standard online/offline execution should follow the normal rules above
   - custom SageMaker script-mode / `custom.py` flows should be treated as explicit overrides that need validation
5. **Only then worry about minor and patch versions**
   - mixing minor and patch versions is usually fine
   - if something fails, suspect a major mismatch, ownership problem, or packaging issue before suspecting a patch difference

If you are still unsure, run the smallest smoke test that matches your path:

- `hv predict` or `hv audit` for local/offline execution
- a tiny local backtest for the training path
- a minimal SageMaker job for the container path

## 13. Recommended Defaults

Use these defaults unless you have a repository-specific reason not to:

- use the **newest convenient CLI** locally
- prefer **newer runner + older algorithm** over the reverse direction
- keep runtime-side modules on one Hotvect framework version per process
- let algorithms bundle their own algorithm-side modules such as `hotvect-core`, `hotvect-catboost`, `hotvect-python`, and `hotvect-tensorflow`
- treat **same major** as the universal guarantee
- for the **online runner**, also rely on the guaranteed `X -> X-1` compatibility window
- treat anything beyond those guarantees as a validated compatibility case
