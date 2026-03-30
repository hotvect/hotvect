---
title: Backport Plan (v10-2025Oct → origin/v9-backport-consolidation-from-v10)
description: Scope, exclusions, and sequencing for consolidating fixes from v10 into the long-lived v9 branch
tags: [design, planning, backport]
difficulty: reference
estimated_time: 5 minutes
related_docs:
  - ../../../design/sagemaker-configuration/index.md
  - ../../tutorials/index.md
---

# Backport Plan (v10-2025Oct → origin/v9-backport-consolidation-from-v10)

## Scope
- **Source branch:** `v10-2025Oct`
- **Target branch:** `origin/v9-backport-consolidation-from-v10`
- **Goal:** backport all reasonable fixes/improvements except TensorFlow support and the “write to multiple files” work.

## Explicit Exclusions
- **TensorFlow module & VW removal** — commits `0546efff`, `0dce2c1e`, `acbd5c36`, `c4922283`, and the TensorFlow-specific portions of `ad5cbc71`. These introduce a new module, remove VW, and depend on JDK 21 features; the user confirmed they should remain v10-only.
- **Multi-file writer / Avro aggregation work** — commits `294686cc`, `c14fedaa`, `8d117702`, `1039990d`, `ba455530`, `c7d7f345`, `ba17b214`, `5972e155`, `dc6f5cd5`. This is the “writing to multiple files” effort (Avro format, sharded writers, related fixes) that should not be backported.

## Candidate Backports

### 1. Prediction transparency & auditing
- **Commits:** `5889d7fb`, `e6bacc24`, `53f329c5`, `e714e00d`, `7cec801b`, `74152529`, `72c84b73`.
- **What:** Adds the `AuditableTransformer` contract and `--log-features` flag so `hv predict` can emit per-feature contributions; improves namespace collision error messaging to point at offending namespaces.
- **Why backport:** Enables parity with v10 debugging workflow and prevents silent namespace misconfiguration in v9 deployments.
- **Notes:** Dependent on core transformer changes only; no TensorFlow or multi-file code involved.

### 2. Namespace canonicalization & schema tooling
- **Commits:** `72ab54c6` (canonicalization + validation), `ad5cbc71` (only the `hotvect-core` JSON schema generator pieces), `145163cb`, `1f536ad3`, `c21ba3fb`.
- **What:** Strict namespace validation, schema generation utilities, and documentation clarifying correct ranker implementation.
- **Why backport:** Prevents mismatched namespace casing/order bugs that currently surface only at runtime in v9. Schema generator is needed for downstream integrations that still rely on v9.
- **Work notes:** Cherry-pick `ad5cbc71` but drop TensorFlow file changes; rest apply cleanly to core/docs.

### 3. HTTP prediction CLI parity
- **Commits:** `d3323fbc`, `8e0e81ab`, `0ae06248`, `768fd927`, `d7d4d7a3`, `b882ae7f`.
- **What:** Replaces the old stdin/stdout predictor with `HttpPredict`, fixes ByteBuffer handling, improves JSON formatting, and stabilizes formatter tests.
- **Why:** v9 users still on CLI prediction need the HTTP endpoint (feature already validated in v10); bug fixes remove crashes when formatters emit `ByteBuffer`.
- **Dependencies:** Adds Jetty dependency to `hotvect-offline-util` only; ensure Maven shading is intact post-backport.

### 4. CLI option & workflow hardening
- **Commits:** `d2548f97`, `22305470`, `6fc16c0e`, `17c72b11`, `06ace749`, `c2dbfbef`, `e64444b2`, `363b0a33`.
- **What:** Better `--source` validation, correct handling of algorithms without encode parameters, packaging optimizations, TopK formatter ByteBuffer change, and CLI doc fixes.
- **Why:** Removes user-facing errors (bad `--source` message, broken state packaging) that still affect active v9 installs.
- **Risk:** Mostly localized to `hotvect-offline-util`; ensure existing integration tests cover encode/predict flows.

### 5. State packaging & caching reliability (Python + Java hotdeploy)
- **Commits:** `2ea70f8e`, `93eb143c`, `eb74b197`, `f18cf782`, `19b157dc`, `cfc8527a`, `0474e543`, `db4c05f3`, `0f28b9b2`, `574732c6`, `059af4f4`, `26b4d86c`.
- **What:** Refactors caching to be directory-based, fixes nested-path loss, ensures cache directories exist, restores files on cache hits, hardens concurrency utilities, and removes destructive S3 delete behavior.
- **Why:** v9’s current caching routinely corrupts or skips state artifacts on reruns, causing expensive rebuilds; these fixes are critical for stability.
- **Notes:** Python and Java pieces are self-contained; apply commits in chronological order to avoid merge conflicts.

### 6. Python CLI/test resiliency
- **Commits:** `565c856c`, `b5b120e4`, `db4c05f3`, `68b63a28`, `0f28b9b2`, `c1d03bc7`.
- **What:** Fixes JSON comparison truncation, event-loop closure, logging defaults, concurrency detection, shell quoting tests, and removes obsolete state fields.
- **Why:** Ensures `python/hotvect` utilities behave deterministically in CI/backfill scenarios that still run on v9.
- **Clarification:** Exclude the Avro/multi-writer commits listed earlier; they touch the same directories but are already in the exclusion list.

### 7. Setup agent & assistant integration alignment (legacy plugin removed)
- **Commits:** `9888cc3e`, `e1c5a908`, `4803155c`, `f2247be6`, `cefb72fc`, `3d9f9924`, `6dde2531`, `012ce1ab`, `8192fffa`, `c72142be`, `8c6e3d2b`, `006aa954`, `a52bfa9f`, `58287b9e`, `23cd4988`, `fbda333f`, `13889903`, `23f2581e`, `ac440b79`, `e961eb61`, `42bf1292`, `6bcd8251`, `68b63a28`.
- **What:** Updates the legacy assistant/plugin guidance, restores MkDocs search, fixes metadata, improves setup agent prompts, and updates FAQ/README guidance.
- **Why:** Maintains documentation parity so users on v9 follow the same workflows and avoid stale instructions.
- **Work notes:** Most changes are docs/JSON files; low risk but should be batched to avoid churn.

### 8. Build & dependency alignment (optional/large)
- **Commits:** `fb5bb60b`, `acbd5c36`, `fb5bb60b` dependencies retrofits.
- **What:** Bumps JVM baseline to 21 and refreshes dependency versions.
- **Why:** Only necessary if v9 deployments must target Java 21 runtimes; otherwise can be deferred.
- **Recommendation:** Treat as optional unless mandated by platform—backport after functional fixes to minimize risk.

## Suggested Backport Order
1. Apply state packaging/caching fixes (Section 5) so subsequent features build reliably.
2. Backport prediction transparency + namespace/schema updates (Sections 1–2).
3. Bring over HTTP prediction and CLI hardening (Sections 3–4).
4. Merge Python resiliency, setup agent, and documentation changes (Sections 6–7).
5. Decide on the Java 21 upgrade (Section 8) separately once functional parity is achieved.

## Verification Checklist
- `mvn clean install` at the repo root (covers Java modules and HTTP predictor changes).
- `cd python && make test` after copying refreshed JARs (validates caching fixes, CLI changes, and pytest suites).
- Manual smoke tests:
  - `java -jar hotvect-offline-util/... --help` (ensure new flags appear).
  - `python -m hotvect.command_line predict --log-features` (feature audit output).
  - Launch `HttpPredict` and hit `/health` and `/predict` endpoints.
