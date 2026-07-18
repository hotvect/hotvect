---
title: What's New in Hotvect v10
description: Release overview for Hotvect v10 (highlights + upgrade pointers)
tags: [release-notes, v10]
---

# What’s New in Hotvect v10

Hotvect v10 focuses on **predictability**, **scale**, and **Python interop**. This release standardizes artifact handling,
improves debugging context, and introduces a managed direct Python-worker runtime.

For migration details, see:
- [Migrating from Hotvect v9 to Hotvect v10](../../migrations/v9-to-v10/index.md)

## 1. Breaking Changes (Critical)

### Java 21 Baseline
Hotvect v10 requires **Java 21** for both build and runtime. Older JVMs will fail with `UnsupportedClassVersionError` (class file version 65.0).

### Strict Null Checks in Decision APIs
Core data records (`RankingDecision`, `ScoringDecision`, `TopKDecision`, `RankingResponse`) now strictly forbid `null` for `additionalProperties` in their constructors.
- **Impact**: Code instantiating these with `null` will throw `NullPointerException`.
- **Fix**: Pass `Collections.emptyMap()` instead.

### Removal of `hotvect-vw`
The `hotvect-vw` module has been removed. Support for Vowpal Wabbit models is discontinued. `hotvect-tensorflow` has been added.

### Path and Artifact Layout Changes
- **`--metadata-path` is always a directory**: Metadata is now written to `metadata.json` *inside* the specified directory. The flag `--meta-data` has been renamed to `--metadata-path`.
- **`encode` output is directory-based**: The destination is treated as a directory, and output is written as part files (`part-00000.ext`, `part-00001.ext`, ...).

## 2. Major New Features

### Direct Python Workers
Hotvect v10 supports long-lived Python worker processes connected to the JVM via **Unix Domain Sockets** (UDS). UDS
is a lower-overhead alternative for JVM-integrated Python algorithms (for example PyTorch or Transformers); the
HTTP/LitServe worker surface remains available for local worker debugging.
- [Design: Direct Python Workers](../../design/direct-python-workers/index.md)

### Generated Ranking Transformers
`@GenerateSimpleRankingTransformer` can generate a `StreamingRankingTransformer` from annotated Java feature methods.
Generated namespace typing is selected with a backend class literal such as `CatBoostBackend.class` or
`TensorFlowBackend.class`, while individual output feature types remain in `transformer_parameters.features[].type`.

- [Guide: Simple ranking transformer](../../guides/simple-ranking-transformer/index.md)
- [Reference: Generated transformer backends](../../reference/generated-transformer-backends/index.md)

### Pipeline Caching Controls (CLI + Algorithm Definition)
Hotvect v10 includes a caching layer for backtests and training runs. It can shorten repeat runs when their expensive
outputs remain valid for reuse.

- CLI:
  - `hv train --cache ...`
  - `hv backtest --cache ...`
  - `--cache-scope major|minor|patch|hyperparam` controls sharing across **algorithm versions**
  - `--cache-refresh` ignores cache reads and writes fresh run-level cache results (requires an effective `cache_base_dir` and effective cache mode `run`)
- Algorithm definition:
  - `hotvect_execution_parameters.cache_base_dir`
  - `hotvect_execution_parameters.cache_scope`
  - `hotvect_execution_parameters.cache_refresh`

For details and recommended usage patterns, see:
- [Guide: Caching](../../guides/caching/index.md)

### SageMaker One-Shot & Train Support
`hv train` and `hv backtest` can submit pipeline jobs to SageMaker. `audit`, `predict`, `encode`, `evaluate`, and
`performance-test` also support one-shot SageMaker execution; audit, predict, and encode can fan out across multiple
jobs.

### Interactive Debugging Tools
- **`hv serve`**: Serves the full Java algorithm runtime over HTTP for easy testing and manual inspection.
- **`hv serve --ui`**: Exposes the browser debugger on the same algorithm server, including action details and offline example browsing.
- **`hv worker serve`**: Serves the worker runtime only over HTTP using the configured worker backend.

### Metrics Suite (`hv-ext metrics`)
A comprehensive CLI suite for evaluating and comparing model performance:
- `hv-ext metrics compare-quality`: Compare offline metrics (ROC-AUC, NDCG, etc.) between runs.
- `hv-ext metrics compare-system`: Compare system performance (latency, throughput, memory).
- `hv-ext metrics plot`: Generate PDF reports with comparative plots.

## 3. Operational Improvements

### Cleaner Runtime/Module Packaging
The Hotvect runner utilities are intentionally kept “thin”:

- The offline/online utilities should not bundle `hotvect-core` / `hotvect-catboost` / `hotvect-tensorflow`.
- Those runtime modules are supplied by the **algorithm JAR** (the algorithm is built with the Hotvect version it was trained with).

This avoids subtle runtime mismatches when running older production algorithms on newer Hotvect runner versions.

### Fail-Fast Namespaces
Transformer construction now validates namespace canonicalization immediately. This prevents subtle "drift" bugs where non-canonical namespaces (e.g., duplicate strings) could cause silent failures in feature extraction.

### Standardized Log Persistence
Every run now produces a consistent set of log files in the metadata directory, aiding debugging and automated analysis:
- `hv.log`: Python orchestration logs.
- `stdout-stderr.log`: Raw subprocess output.
- `hotvect-offline-utils.log`: Java application logs.
- `metadata.json`: Structured task metadata.

### CLI Refinements
- `--algorithm-name` is strictly a name (not a path).
- `--algorithm-override` generates an `effective_algorithm_definition.json` in the metadata directory for traceability.
- JSONL comparison is available as `hv-ext compare-jsonl`.

## Upgrade Pointers

- **JVM Memory**: v10 defaults to `-XX:MaxRAMPercentage=80`. Set explicit `-Xmx` if you need v9-style fixed heap behavior.
- **CatBoost Models / Parameters ZIP Layout**: The parameter ZIP layout is determined by the Hotvect modules bundled inside the **algorithm JAR** (e.g. `hotvect-catboost`).
  - If you run a legacy algorithm that was built/trained with Hotvect v9, its own `hotvect-catboost` code will read the v9-era layout it produced.
  - Newer Hotvect v10-era CatBoost tooling may write/read the model under `model_parameter/model.parameter`.
  - When v10 packages a model for an algorithm whose definition resolves to Hotvect 9.x, it writes the CatBoost model at the legacy root `model.parameter` path. Hotvect 10.x algorithms keep the v10 `model_parameter/model.parameter` layout.
