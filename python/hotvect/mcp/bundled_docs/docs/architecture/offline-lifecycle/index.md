---
title: Offline lifecycle architecture
description: How Hotvect's Python orchestration and JVM tasks prepare dependencies, parameters, predictions, and evidence
tags: [architecture, offline, lifecycle, pipeline, sagemaker]
---

# Offline lifecycle architecture

The offline lifecycle has two cooperating layers:

- the Python package plans runs, prepares algorithm dependencies, manages caches, invokes training, submits SageMaker
  jobs, and records results;
- the JVM offline runtime loads the algorithm contract and performs state generation, feature auditing, encoding,
  prediction, and performance testing.

## Artifact flow

<div class="hv-flow">
  <div class="hv-flow__step"><span>01</span><strong>Prepare</strong><small>data · child artifacts</small></div>
  <div class="hv-flow__step"><span>02</span><strong>Transform</strong><small>state · encoded examples</small></div>
  <div class="hv-flow__step"><span>03</span><strong>Train or generate</strong><small>model · state</small></div>
  <div class="hv-flow__step"><span>04</span><strong>Package parameters</strong><small>versioned state</small></div>
  <div class="hv-flow__step"><span>05</span><strong>Validate</strong><small>predict · evaluate · performance</small></div>
</div>

Stages are enabled according to the effective algorithm definition and requested target. A parameterless algorithm can
skip training, but the Python lifecycle still normally runs parameter packaging for a non-state pipeline. The resulting
parameter package is currently a ZIP and may contain only metadata or packaged child artifacts. A child can train,
generate state, reuse pinned parameters, or require no parameter work.

## Dependency preparation

Before the outer algorithm continues, the Python pipeline recursively prepares declared children. Each child retains
its own definition, data requirements, dates, parameter identity, and result metadata. The outer run consumes the
prepared dependency artifacts according to its factories.

Data dependency discovery follows the same effective definition and target as the planned operation. It is separate
from request-time algorithm binding.

## Execution context

Normal offline prediction uses `BATCH` workload mode with `OFFLINE` input semantics. Performance testing defaults to
`REALTIME` workload mode over `OFFLINE` examples so the serving-oriented runtime configuration can be measured without
live traffic.

See [Execution contexts](../../concepts/execution-contexts/index.md).

## Local and SageMaker execution

Local and SageMaker paths operate the same logical algorithm and workflow contract, but they do not share a process or
filesystem:

- local runs use local data, scratch, cache, and output paths;
- SageMaker runs package the operation into a remote training job with S3 input/output channels;
- local submission manifests record the intended remote jobs and result locations;
- a submitted SageMaker job continues independently if the local process exits.

Remote SageMaker execution means the whole offline job runs remotely. It is not a remote binding for one child in the
request-time algorithm graph.

## Evidence and logs

Pipeline-backed train and backtest runs record metadata and logs alongside their primary outputs. Their `result.json`
summarizes the run, but source artifacts, per-stage metadata, and logs remain necessary when diagnosing configuration,
packaging, or infrastructure failures. Standalone commands have their own output contracts; for example, local
`hv evaluate` writes the requested evaluation JSON rather than a pipeline `result.json`.

Continue with [Pipeline stages](../../guides/pipeline-stages/index.md) for exact outputs and
[Generate runtime state](../../guides/state-generation/index.md) for a complete state-producing example, or
[SageMaker backtests](../../guides/sagemaker-backtests/index.md) for remote operation.
