# Hotvect Changelog

This changelog focuses on user-visible changes. For a complete history, see `git log`.

## Unreleased

- Docs: add a guide for the offline pipeline stages (`generate-state`, `encode`, `train`, `predict`, `evaluate`, `performance-test`).
- Security: remove `shell=True` usage from dependency inspection tooling and avoid shell execution in build helpers.
- Algorithm definitions: reject self-dependencies (fail fast with a clear error).
- Java API: restore `score(ComputingRankingRequest)` on `ComputingBulkScorer`, including CatBoost feature-store response propagation, so downstream algorithms can use the score-first API again without rebuilding `BulkScoreResponse` manually.
- CatBoost: standardize model parameter path resolution (and keep the decompression pipeline consistent).
- CLI: rewire `hv serve` onto the unified algorithm server, add `hv serve --ui` for the browser debugger on the same backend, and add `hv worker serve` for worker-only HTTP debugging.
- Python packaging: remove the unused LitServe-based serving stack and its dependency footprint.
- Docs: add a performance benchmarking and optimization guide covering benchmark taxonomy, calibration-first realtime perf testing, optimization ordering, and machine-type guidance.
- CatBoost: align null TEXT encoding between training and scoring by using the standard missing-text sentinel; retrain CatBoost models with TEXT features if you need exact null-text parity from training through serving.

## 10.5.0

- Added Hotvect MCP stdio server (`hv-mcp`) and expanded MCP tool coverage for `hv`/`hv-ext`.
- Demo UI: major usability improvements (JSON tree rendering/search, better metadata headers, caching, and E2E coverage).
- Python: lazy import of optional SageMaker training dependency, removed deprecated `six`, and various correctness fixes.

## 10.4.8

- Python: add `torch` extra for optional PyTorch dependencies.
- Demo UI: additional UX improvements (expanded views and browsing/search refinements).

## 10.4.7

- Java API: restore `Request.getShared` for backwards compatibility.

## 10.4.6

- `hv list-transformations`: output now includes `name`, `returnTypeHint`, and `featureValueType`, and supports `ComputingRankingTransformer`.
- Build: refresh Python lockfile for the released version.

## 10.4.5

- Docs: reorganize design notes under `docs/design` and fix MkDocs nav/links.
- Build: align parent versions and remove stale/deprecated API surface.

## 10.4.4

- Version bump to 10.4.4 for both the Java JARs and the Python package.
- Dependency bumps: Jackson 2.21.0, Logback 1.5.25, Micrometer 1.16.2, AWS SDK S3 2.41.13, JUnit Jupiter 6.0.2.
- `hv-ext data-dependency`: sanitize git refs when creating per-ref scratch directories (avoids accidental nested paths and temp-dir failures for refs like `feature/foo`).
- Build/test: run Mockito with a `-javaagent` during Maven tests (avoids brittle self-attach on newer JDKs).
- Tests: make the public-S3 downloader test opt-in via `HOTVECT_RUN_NETWORK_TESTS=true` (unit tests run offline by default).

## 10.x (10.0.0 .. 10.4.3)

- Added TensorFlow support (`hotvect-tensorflow`) including TFRecord encoding utilities.
- Added unified Python "direct worker" IPC and related CLI/debugging utilities (including `hv serve` for local debugging).
- Added/expanded CLI features such as `--log-features` (feature auditing during prediction) and improved `hv performance-test`.
- Added/expanded `hv-ext data-dependency` tooling (safe defaults, JSON output, sampling/resume, better concurrency).
- Added/expanded AWS SageMaker backtest/training configuration support driven by algorithm definitions.
- Build/tooling updates: JDK 21 baseline and assorted dependency updates across modules.

## 9.30.0

- v9 line snapshot (historical baseline for the `v10` branch before merging in the v10 series).
