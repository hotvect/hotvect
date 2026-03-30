---
title: What's New in Hotvect v10
description: Release overview for Hotvect v10 (highlights + upgrade pointers)
tags: [release-notes, v10]
---

# What’s New in Hotvect v10

Hotvect v10 focuses on **predictability**, **scale**, and **Python interop**. This release standardizes artifact handling, improves debugging context, and introduces a high-performance Python runtime.

For migration details, see:
- [Migrating from Hotvect v9 to Hotvect v10](../../archive/migrations/v9-to-v10/index.md)

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
- **`encode` output is directory-based**: The destination is treated as a directory, and output is written as sharded files (`shard_0.ext`, `shard_1.ext`, ...).

## 2. Major New Features

### Direct Python Workers (High Performance Runtime)
Hotvect v10 supports long-lived Python worker processes connected to the JVM via **Unix Domain Sockets** (UDS). This replaces HTTP-based interop, significantly reducing overhead for Python-bound algorithms (e.g., PyTorch, Transformers).
- [Design: Direct Python Workers](../../design/direct-python-workers/index.md)

### Pipeline Caching Controls (CLI + Algorithm Definition)
Hotvect v10 includes a first-class caching layer for backtests and training runs, which can dramatically speed up iterative development by reusing intermediate artifacts across runs.

- CLI:
  - `hv train --cache ...`
  - `hv backtest --cache ...`
  - `--cache-scope major|minor|patch|hyperparam` controls sharing across **algorithm versions**
  - `--cache-refresh` ignores cache reads (forces recompute) while still writing fresh results (requires `--cache`)
- Algorithm definition:
  - `hotvect_execution_parameters.cache_base_dir`
  - `hotvect_execution_parameters.cache_scope`
  - `hotvect_execution_parameters.cache_refresh`

For details and recommended usage patterns, see:
- [Guide: Caching](../../guides/caching/index.md)

### SageMaker One-Shot & Train Support
Simplified SageMaker integration allowing single-job execution for training, backtesting, and auditing. Support for "one-shot" tasks reduces the complexity of orchestrating multi-step pipelines in SageMaker.

### Experiment-Management (“EMS”) Tooling
Hotvect v10 vendors an experiment-management (formerly “EMS”) client inside the repo and exposes a small read-only CLI:

- `hv-exp`: Inspect slots, experiments, active algorithms, and parameters (read-only by design).
- `hotvect.ems`: Kept as a thin backwards-compatible wrapper (re-exports) around `hotvect.experiment_management`.

This removes the need for a separate external “ems-client” package at runtime.

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
Every run now produces a consistent set of log files in the metadata directory, aiding debugging and agent-based analysis:
- `hv.log`: Python orchestration logs.
- `stdout-stderr.log`: Raw subprocess output.
- `hotvect-offline-utils.log`: Java application logs.
- `metadata.json`: Structured task metadata.

### CLI Refinements
- `--algorithm-name` is strictly a name (not a path).
- `--algorithm-override` generates an `effective_algorithm_definition.json` in the metadata directory for traceability.
- Utilities like `jsonl-compare` have moved to `hv-ext`.

## Upgrade Pointers

- **JVM Memory**: v10 defaults to `-XX:MaxRAMPercentage=80`. Set explicit `-Xmx` if you need v9-style fixed heap behavior.
- **CatBoost Models / Parameters ZIP Layout**: The parameter ZIP layout is determined by the Hotvect modules bundled inside the **algorithm JAR** (e.g. `hotvect-catboost`).
  - If you run a legacy algorithm that was built/trained with Hotvect v9, its own `hotvect-catboost` code will read the v9-era layout it produced.
  - Newer Hotvect v10-era CatBoost tooling may write/read the model under `model_parameter/model.parameter`.
