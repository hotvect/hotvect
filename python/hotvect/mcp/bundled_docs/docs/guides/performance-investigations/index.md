---
title: Investigate a performance regression
description: Localize a verified Hotvect performance change to its workload, artifact, stage, or runtime configuration
tags: [performance, benchmarking, regression, investigation]
difficulty: intermediate
prerequisites:
  - A baseline and treatment measured under one fixed benchmark contract
  - Access to result artifacts and logs
related_docs:
  - ../performance-benchmarking/index.md
  - ../performance-optimization/index.md
  - ../feature-audits/index.md
  - ../score-equivalence/index.md
---

# Investigate a performance regression

Start here only after [reliable performance benchmarking](../performance-benchmarking/index.md) has shown that a
repeatable regression exists. That guide owns workload parity, sample size, replication, statistics, and reporting.
This page owns the next question: **where did the extra time or resource use enter the lifecycle?**

## Follow the evidence in order

### 1. Identify the metric that moved

Keep the metric families separate:

| Metric | Answers |
| --- | --- |
| Remote-job wall clock | How long the full managed job occupied runtime capacity |
| `result.json` stage timings | Which lifecycle stage consumed time inside the algorithm workflow |
| `performance-test` latency and throughput | How the loaded request-time algorithm behaved under the pinned load contract |
| Profiler samples | Which code paths consumed CPU or allocation within one already-localized stage |

Do not use a job wall-clock change to claim request latency, or a request benchmark to explain training duration.

### 2. Confirm the treatment actually ran

Inspect the effective definition, selected JAR, parameter ZIP, runtime image, workload mode, and submitted job
configuration. A source or configuration change that never reached the executed artifact cannot explain the result.

For a remote run, compare the submitted job definition rather than only the local command. For a runtime change,
confirm the loaded algorithm runtime ID and execution context.

### 3. Locate the first divergent stage

Use `result.json` and stage logs to compare the lifecycle in order:

```text
prepare children → state/encode → train → package → predict → evaluate → performance-test
```

- Dependency preparation changed: inspect child versions, cache decisions, and downloaded inputs.
- Encode changed: compare decoded counts, feature audits, encoded row counts, and shard layout.
- Train changed: feed the same encoded artifact to both trainer runtimes before blaming feature code.
- Predict or performance-test changed: pin one parameter ZIP and use the same ordered request sample.

Stop at the first material divergence. Later stages can only show its downstream effects.

### 4. Separate value parity from layout parity

Two encoded datasets can contain the same rows but differ in order or sharding. Verify separately:

- feature values with `hv audit` and `hv-ext compare-jsonl`;
- encoded row count and multiset;
- row order;
- number of `part-*` files and whether the consumer reads all of them.

Changing `writer_num_shards` is an end-to-end consumer-contract change, not automatically an optimization.

### 5. Reproduce the narrow stage

Use the smallest replay that still contains the regression:

- a bounded audit for feature work;
- fixed encoded data for training;
- a fixed parameter ZIP and request sample for prediction;
- one workload mode and pinned load for performance testing.

Only after the narrow replay reproduces the change should you profile it. Otherwise the profiler describes a different
problem from the benchmark.

## Investigation output

Record:

- the benchmark contract and repeated result that established the regression;
- the first divergent stage;
- the artifacts compared at that boundary;
- the effective runtime configuration;
- the narrow reproduction command and logs;
- what remains unproven.

Then choose a lever from [Optimize Hotvect performance](../performance-optimization/index.md). Re-run the original
benchmark after any change; a faster isolated stage is useful only if the intended end-to-end metric improves without
breaking correctness.
