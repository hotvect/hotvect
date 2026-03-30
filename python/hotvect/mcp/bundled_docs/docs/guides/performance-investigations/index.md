---
title: How to Investigate Performance Regressions
description: A checklist for isolating Hotvect performance changes without mixing them up with workload, configuration, or artifact mismatches
tags: [performance, benchmarking, regression, sagemaker, catboost]
difficulty: intermediate
estimated_time: 30 minutes
prerequisites:
  - A reproducible baseline and treatment
  - Access to result artifacts and logs
  - hotvect CLI installed
related_docs:
  - ../../reference/cli/index.md
  - ../feature-audits/index.md
  - ../score-equivalence/index.md
  - ../sagemaker-backtests/index.md
related_commands:
  - hv backtest
  - hv audit
  - hv encode
  - hv performance-test
  - hv-ext compare-jsonl
next_steps:
  - Re-run with tighter controls
  - Isolate the affected stage
  - Add a regression test or runbook note
---

# How to: Investigate Performance Regressions

## Overview

Performance debugging goes wrong when the comparison contract is loose.

Before concluding that a code change made Hotvect faster or slower, first prove that:

- the **workload** is the same,
- the **runtime contract** is the same,
- the **consumer** read the same data,
- and the metric you are comparing actually measures the thing you care about.

Most false perf conclusions come from accidentally changing something that also changes model quality or training difficulty.

This guide is a reusable checklist for avoiding false conclusions during local and SageMaker performance work.

## 1. Freeze the Comparison Contract

Record these fields for both baseline and treatment before you compare anything:

- algorithm version / git reference
- Hotvect version
- algorithm override files
- last test time / date range
- number of training days
- train/test data prefixes
- instance type / machine shape
- spot vs on-demand
- container image
- `writer_num_shards`
- sample size / target RPS / warmup settings

If any of those differ, you are not measuring a pure software delta.

For SageMaker jobs, do not trust the harness alone. Inspect the submitted job and the effective algorithm definition that actually ran.

## 1a. Do Not Change Model-Quality-Affecting Knobs by Accident

If the question is "did this runtime or implementation change affect performance?", keep these identical unless they are the explicit subject of the experiment:

- training data window / number of training days
- train/test date boundaries
- sampling ratio or number of examples
- feature set
- feature-store inputs
- model hyperparameters
- training command
- parameter reuse / `with_parameter` behavior
- ordered vs unordered execution when that changes model input order

Otherwise, you are not isolating software performance. You are benchmarking a different model-training problem.

This matters because changes like:

- fewer training days,
- different CatBoost iterations or learning rate,
- a different feature set,
- or a different encoded row order

can change both runtime and model quality at the same time.

## 2. Measure the Right Metric

Keep these metric families separate:

- **SageMaker wall clock**: `TrainingStartTime -> TrainingEndTime`
- **Algorithm stage timings**: `result.json` values such as `prepare_dependencies`, `encode`, `train`, `predict`
- **Perf-test metrics**: throughput, latency percentiles, memory

Do not compare:

- provisioning time against algorithm runtime,
- train-only timings against full backtest runtime,
- one-shot `performance-test` latency against pipeline wall clock.

When the goal is algorithm runtime, prefer `result.json` stage timings. When the goal is real operational duration, use SageMaker wall clock.

## 3. Verify the Effective Runtime, Not Just the Intended Runtime

Especially on SageMaker, prove the thing you changed was actually used.

Examples of what to verify:

- the intended container image was selected,
- the intended wheelhouse or custom payload was installed,
- the intended executable was on `PATH`,
- the effective algorithm definition contains the expected overrides,
- the output directory layout matches what the downstream step expects.

For example, replacing a Python wheel does not automatically guarantee that a shell entrypoint such as `catboost_train` was overridden.

## 4. Verify Data Parity at the Right Layer

When the regression appears in training, compare data in stages:

1. **Feature audit parity**
   - use `hv audit`
   - compare JSONL output with `hv-ext compare-jsonl`
2. **Encoded data parity**
   - compare row counts
   - compare encoded row multiset, not just file bytes
   - compare row order separately
3. **Trainer input parity**
   - confirm the trainer consumed all encoded shards / files
   - confirm it did not silently drop shards or select only the first file

It is common for encoded row values to match while row order differs. Some trainers are order-sensitive enough that this still changes runtime or model output.

## 5. Treat Output Layout as a Consumer Contract

If a producer writes a sharded directory, the downstream consumer must explicitly support that layout.

Before changing `writer_num_shards` or unordered/ordered behavior, verify:

- how many shard files were written,
- how many rows were written,
- how the downstream stage resolves input files,
- whether it consumes all shards or expects a single file.

Do not assume “multiple files” is automatically faster overall. Benchmark the full pipeline, not just the encode stage.

## 6. Localize the Regression Before Re-running Expensive Jobs

Use local or small-sample replays to answer narrower questions:

- Are feature values different?
- Are encoded rows different?
- Is only row order different?
- Does the trainer behave differently on the same encoded data?

Useful patterns:

- pin a known parameters ZIP to isolate predict/evaluate from train,
- reuse cached outputs when you want to localize one stage,
- run a reduced local replay on hydrated real data before launching more SageMaker jobs.

## 7. Repeat Runs and Use Statistics When the Signal Is Small

If the observed difference is small enough to be within normal noise, do not rely on one run.

At minimum:

- repeat both sides on the same contract,
- pair runs by date or workload slice where possible,
- record mean and spread,
- use a simple statistical test if the result will drive a release decision.

Single-run conclusions are acceptable only when the effect size is obviously large and the contract is clearly controlled.

## 8. Archive the Evidence

For every benchmark, keep:

- job ids,
- git refs,
- effective algorithm definitions,
- `result.json`,
- failure logs,
- the exact comparison script or command,
- a short summary of what was actually proven.

This prevents rediscovering the same dead end later.

## A Good Investigation Output

A useful perf investigation summary should say:

- what changed,
- what was held constant,
- what metric was compared,
- whether data parity was verified,
- whether the change actually took effect at runtime,
- and whether the result is statistically credible or only directional.

## Related Workflows

- Use [Feature audits](../feature-audits/index.md) when you suspect feature-value drift.
- Use [Score equivalence](../score-equivalence/index.md) when you need predict parity with fixed parameters.
- Use [SageMaker backtests](../sagemaker-backtests/index.md) when you need real cloud-scale measurements.
