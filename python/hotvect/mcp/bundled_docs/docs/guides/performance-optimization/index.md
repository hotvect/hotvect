---
title: Optimize Hotvect performance
description: Localize a measured bottleneck, choose a matching optimization lever, and confirm it end to end
tags: [performance, optimization, sagemaker, workers, training]
difficulty: advanced
related_docs:
  - ../performance-benchmarking/index.md
  - ../performance-investigations/index.md
  - ../score-equivalence/index.md
  - ../../design/direct-python-workers/index.md
---

# Optimize Hotvect performance

Optimize only after a controlled comparison shows a real bottleneck. Use
[Reliable performance benchmarking](../performance-benchmarking/index.md) to define the comparison contract and
[Performance investigations](../performance-investigations/index.md) to localize a regression.

## Match the metric to the goal

| Goal | Primary measurement |
| --- | --- |
| Shorter train/backtest cycle | Full wall clock plus `result.json` stage timings |
| Lower serving latency | Fixed-load `performance-test` percentiles |
| More serving capacity | Warmup/capacity sweep, separate from fixed-load latency |
| Faster narrow code path | A focused local benchmark, followed by an integrated run |

A parser, codec, or worker microbenchmark cannot establish end-to-end improvement. A pipeline wall-clock result cannot
isolate one hot method.

## Localize before changing anything

For an offline pipeline, start with:

- `prepare_dependencies`;
- dependency `encode_parameter`, `encode`, and `train` timings;
- `package_predict_params`;
- top-level `predict`, `evaluate`, and `performance_test`.

`prepare_dependencies` already includes nested dependency work. Do not add it to the nested timings again. Use the
Pipeline Performance breakdown from `hv-ext metrics plot` when a dependency graph makes manual aggregation ambiguous.

For inference, separate:

- request decode;
- feature and external-data preparation;
- JVM-to-worker transport;
- model execution;
- ranking and response construction.

## Choose a lever for the measured stage

### Data read and encode

Test one variable at a time:

- input file count and size;
- reader and worker thread limits;
- ordered versus unordered processing when semantics allow it;
- writer shard count;
- serialization format.

A format comparison is invalid if the logical rows or shard layout also changed. If the trainer consumes encoded
data, prove that it reads every part file and compare encoded content before attributing a train-time difference to the
trainer.

### Training

- Keep training data, order policy, hyperparameters, and encoded inputs fixed when testing runtime or machine changes.
- Compare instance family and size as explicit experimental variables.
- Measure cost as well as elapsed time.
- Use caching only when artifact reuse is part of the declared comparison contract.

If two candidates are supposed to produce the same model input, fingerprint or otherwise compare the encoded rows
before explaining a training delta.

### JVM feature work

- Use the generated-transformer report to find unexpectedly materialized dependencies.
- Remove outputs the model does not consume.
- Reuse expensive request-level values through canonical namespaces and `Computing`.
- Profile allocations and CPU only after a repeatable integrated regression exists.

### Python workers

Worker-only and integrated benchmarks answer different questions. A direct UDS worker removes HTTP from the
JVM-integrated path; LitServe provides the local worker HTTP debugging surface. The transport design suggests a lower
overhead UDS path, but the algorithm-level effect must be measured with the same backend, model, inputs, concurrency,
and load.

Tune only documented runtime controls such as worker count, queue size, request timeout, and device assignment. Keep
the selected `realtime` or `batch` scope fixed.

### Machine and concurrency

Instance type, thread count, process count, worker count, and queue depth interact. Change one main variable per run
and inspect CPU utilization, memory, I/O wait, GC, and queueing before increasing concurrency again.

More parallelism can increase throughput while worsening tail latency or memory. Report both when both matter.

## Preserve correctness separately

Performance evidence does not establish behavior parity. Pair it with the narrow correctness check that matches the
change:

| Change | Correctness evidence |
| --- | --- |
| Feature/runtime refactor with fixed model | [Predict score equivalence](../score-equivalence/index.md) |
| Feature transform change | [Feature audits](../feature-audits/index.md) |
| Training/runtime change | Comparable multi-day quality results plus encoded-input checks |
| Online request-path change | [Online/offline parity](../online-offline-parity/index.md) |

Do not dismiss ranking-metric movement as noise without evidence. If a difference is allowed, define the tolerance
before reading the result.

## Promotion sequence

1. State one bottleneck and one hypothesis.
2. Define the performance and correctness success criteria.
3. Run the smallest benchmark that isolates the mechanism.
4. Confirm the effect in the complete intended pipeline or serving path.
5. Repeat independent jobs when cloud or tail-latency noise is material.
6. Archive the benchmark contract, effective definition, results, and commands.

Promote the change only when the integrated result improves the intended metric without violating the declared quality
or equivalence contract.
