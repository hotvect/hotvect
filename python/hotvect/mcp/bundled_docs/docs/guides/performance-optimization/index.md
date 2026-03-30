---
title: Measure and Optimize Hotvect Performance
description: A practical guide to measuring Hotvect performance correctly and choosing safe optimization levers
tags: [performance, optimization, benchmark, sagemaker, direct-workers, litserve, training]
difficulty: advanced
estimated_time: 20 minutes
prerequisites:
  - hotvect CLI installed and working
  - Ability to run local benchmarks and SageMaker jobs
  - An algorithm JAR and a representative offline dataset
related_docs:
  - ../../reference/cli/index.md
  - ../sagemaker-backtests/index.md
  - ../score-equivalence/index.md
  - ../../design/direct-python-workers/index.md
related_commands:
  - hv train
  - hv backtest
  - hv predict
  - hv performance-test
  - hv worker serve
next_steps:
  - Add calibrated fixed-load performance tests to your algorithm definition
  - Benchmark one optimization at a time
  - Promote only changes that improve wall clock without unacceptable metric movement
---

# Measure and optimize Hotvect performance

This guide captures the practical rules for performance work in Hotvect:

- how to run a benchmark that is actually comparable
- which metrics to trust for different questions
- which optimization levers are low-risk vs high-risk
- how to reason about end-to-end wall clock instead of isolated microbenchmarks

It intentionally stays generic. Examples use fake identifiers such as `example-123` and synthetic feature names such as
`feature_a` / `feature_b`.

## Start with the right question

Performance work in Hotvect usually falls into one of these buckets:

1. **Train-cycle wall clock**
   - Goal: shorten the total time from `hv train` / `hv backtest` start to usable outputs.
2. **Inference latency / throughput**
   - Goal: lower `predict` or `performance-test` latency or increase sustained throughput.
3. **Single hot-path cost**
   - Goal: speed up one narrow step such as decode, state generation, feature transformation, or worker transport.

Do not use one measurement type to answer another question.

Examples:

- A JSON decoder microbenchmark is useful for **parser throughput**, but it does not answer whether the whole training cycle
  gets faster.
- `hv performance-test` is useful for **integrated inference** benchmarking, but it does not isolate a single decoder or
  worker transport implementation.

## Use the smallest benchmark that can still answer the question

Recommended sequence:

1. **Local microbenchmark**
   - Use when changing one narrow code path.
   - Example: parser speed, tensor encoding speed, worker codec overhead.
2. **1-day SageMaker benchmark**
   - Use to check whether a local win survives the real pipeline.
   - This is the default first end-to-end benchmark.
3. **7-day benchmark**
   - Use only after a 1-day result looks promising.
4. **Full train / full backtest**
   - Use as final confirmation, not as the first experiment.

This keeps iteration fast and reduces cloud cost.

## Keep benchmark conditions fixed

When comparing baseline vs treatment, pin all of the following:

- same algorithm JAR shape
- same parameters ZIP, unless the change is part of training
- same data partition(s)
- same last test day
- same number of runs
- same machine type, unless machine type is the thing being benchmarked
- same execution context (`batch` vs `realtime`)
- same source format (`json`, `json.gz`, `avro`, etc.) unless format is the thing being benchmarked

If you change multiple dimensions at once, you lose attribution.

## For realtime latency work, calibrate once, then pin the load

For regression-style realtime tests, do **two runs**:

1. **Calibration run**
   - Run `hv performance-test` without explicitly setting `samples` or `target_rps`.
   - Use that run to estimate the current max sustainable throughput for the algorithm on the chosen machine type.
2. **Fixed-load comparison run**
   - Set `target_rps` to about **80% of the measured max throughput**.
   - Set `samples` high enough to capture realistic tail latency, but keep each iteration below about **2 minutes**.
     If you omit `samples`, the framework auto-sizes to `max(min(throughput × 120, 200000), 2000)`.

Recommended policy for an explicit override:

- `target_rps = round(0.8 * measured_max_throughput)`
- `samples = max(min(200000, floor(target_rps * 120)), 2000)`

This gives:

- a stable offered load
- enough requests for useful percentiles
- bounded runtime (~2 min per iteration × 5 iterations ≈ 10 min total)

If you care mainly about `p99`, use at least `10000` samples when the runtime budget allows it.

## Pin the calibrated perf-test config in the algorithm definition

Once a family of algorithms has a reasonable calibrated load, pin the values in the algorithm definition and commit
them.

This keeps comparisons across related algorithms reproducible.

Pin:

- `hotvect_execution_parameters.performance-test.samples`
- `hotvect_execution_parameters.performance-test.target_rps`

Example:

```json
{
  "hotvect_execution_parameters": {
    "performance-test": {
      "enabled": true,
      "samples": 28800,
      "target_rps": 240
    }
  }
}
```

Use the same pinned values when comparing algorithms with similar request shapes and performance characteristics.
If the workload shape changes materially, re-run the calibration step and update the committed values deliberately.

## Distinguish worker-level benchmarks from integrated benchmarks

These are different:

- **Worker-level**
  - compares only the worker transport or worker runtime
  - examples:
    - direct UDS worker
    - standalone LitServe worker
- **Integrated**
  - measures the full algorithm path
  - examples:
    - `hv predict`
    - `hv performance-test`
    - `hv backtest`

Use worker-level benchmarks when you want to answer:

- “Is transport A faster than transport B?”

Use integrated benchmarks when you want to answer:

- “Does the algorithm get faster in the actual pipeline?”

Do not treat them as interchangeable.

## Realtime questions and offline questions are different

For **offline / batch / training-cycle** work, optimize:

- full wall clock
- training-side wall clock
- stage breakdown

For **realtime / serving** work, optimize:

- `p50`
- `p95`
- `p99`
- `p999`
- sustained throughput at a fixed offered load
- memory usage at that load

Do not use an uncapped throughput run as the final regression comparison. Use it only to choose the fixed load for the
real comparison run.

## Measure both full wall clock and stage breakdown

For training or backtest optimization, record both:

- **full tracked wall clock**
- **training-side wall clock**
  - usually: `prepare_dependencies + package_predict_params`
  - excludes `predict` and `evaluate`

Why:

- sometimes an optimization speeds up training but leaves `predict` unchanged
- sometimes a change helps one dependency and hurts another

Also inspect the nested breakdown.

Typical useful stages are:

- top level:
  - `prepare_dependencies`
  - `predict`
  - `package_predict_params`
  - `evaluate`
- nested:
  - state generation (`encode_parameter`)
  - model encode
  - model train

If you only look at one top-level number, you can miss the real bottleneck.

## Treat machine type as a first-class optimization lever

Machine choice is often a larger lever than code changes.

Benchmark different instance families explicitly:

- CPU family changes
- newer vs older generation
- larger vs smaller sizes

Typical questions:

- Does a larger CPU instance reduce `prepare_dependencies` enough to justify the extra cost?
- Does a newer instance family reduce wall clock at the same or lower total run cost?

For CPU training/backtests on SageMaker:

- prefer **Managed Spot Training** for benchmarking and routine runs

For GPU:

- prefer **on-demand** unless you know spot stability is acceptable

## Use replicas when the noise is real

Cloud benchmarking is noisy. Spot interruptions, transient object-store behavior, and one-off JVM failures can distort a
single run.

If a result looks surprising:

- rerun the control
- keep the runtime line identical
- compare multiple replicas
- inspect stage logs for outliers such as:
  - out-of-memory failures
  - repeated retries
  - initialization spikes

Do not average a clearly bad outlier into the final conclusion without checking its cause.

## Prefer parity-safe levers first

Start with changes that should not alter algorithm semantics:

- machine type
- thread count
- queue length
- batching/chunking knobs
- JVM flags
- parser/runtime implementation swaps that preserve object semantics
- input sharding / file layout

Move to higher-risk changes only after these are exhausted.

Examples of parity-safe candidates:

- changing `max_threads`, `queue_length`, or task-specific `batch_size`
- tuning state-generation thread counts
- benchmarking direct workers vs LitServe for the same model backend
- trying a different JSON parser implementation on the same logical schema

## A local microbenchmark must be paired with an end-to-end benchmark

Microbenchmarks are useful, but insufficient on their own.

A change can:

- improve parse speed
- but not improve full wall clock
- or improve one stage while slowing another

For example:

- a parser swap may improve decode throughput
- but if decode is only a small fraction of the full pipeline, overall wall clock may barely move

Use this progression:

1. local microbenchmark
2. 1-day SageMaker benchmark
3. only then decide whether the change is worth carrying forward

## Do not assume a wire-format change will be faster

Changing input format (for example `json.gz` to `avro`) is not automatically a win.

Benchmark it directly.

Why:

- one part of the pipeline may become faster
- another part may become slower
- some code paths are already optimized around a specific reader type

If you change format, compare:

- same logical records
- same shard count
- same machine type
- same day range

Do not mix a format change with a sharding change unless you explicitly want to test both together.

## Data layout can matter more than serialization format

Partitioning and sharding frequently dominate format-level gains.

Benchmark data-layout changes independently:

- number of input files
- file sizes
- shard count
- output shard count

A common mistake is to attribute a win to “format A vs format B” when the real cause was:

- more shards
- better parallel read behavior
- less skew in large input files

Always isolate:

1. layout change only
2. format change only
3. layout + format combined

## Metrics: which ones to trust when parity matters

When deciding whether an optimization is acceptable:

- use the most stable metrics first
- then inspect ranking metrics

In practice:

- aggregate discrimination metrics such as `roc_auc` / `pr_auc` are often more stable than top-k ranking metrics
- ranking metrics such as `map@k` / `ndcg@k` can move slightly due to:
  - tie ordering
  - nondeterministic training order
  - minor floating-point differences

This does **not** mean ranking metrics are unimportant.
It means:

- if `roc_auc` / `pr_auc` are effectively unchanged
- and ranking metrics move only slightly
- the result may still be acceptable

For strict parity work, also compare outputs before training:

- decoded examples
- encoded training data
- predict outputs on a fixed parameter ZIP

See the score-equivalence guide for the exact output-comparison workflow.

## Worker transport: expect UDS to beat HTTP on latency

For Python-backed inference runtimes:

- UDS direct workers generally have lower latency and lower overhead than HTTP
- HTTP workers are useful for debugging, remote deployment, and integration convenience

If latency matters:

- benchmark direct workers vs HTTP directly
- do not assume the more convenient transport will be close enough

If the question is only:

- “Does the worker backend itself behave correctly?”

then a standalone HTTP worker can still be the right tool.

## Good optimization candidates in Hotvect

These are usually worth testing:

- **Parser/runtime hot path**
  - alternative JSON parser
  - fewer intermediate allocations
  - cached readers / codecs
- **Offline task concurrency**
  - `max_threads`
  - `queue_length`
  - task-specific `batch_size`
- **State generation**
  - aggregation threads
  - reader threads
  - input shard count
  - output shard count
- **Inference/runtime**
  - direct worker concurrency
  - feature-transform parallelism
  - predict chunk size
  - transport choice
- **Machine/resource choice**
  - instance family
  - instance size
  - spot vs on-demand where appropriate

## A practical benchmark playbook

### 1. Form one concrete hypothesis

Good:

- “Raising state-generation reader threads will reduce `prepare_dependencies`”
- “Switching the JSON decoder implementation will reduce decode cost”
- “A newer CPU instance family will lower total train-cycle wall clock”

Bad:

- “Make training faster”

### 2. Define success before you run

Example:

- full wall clock improves by at least `3%`
- or training-side wall clock improves by at least `5%`
- metrics remain effectively unchanged

### 3. Run the smallest credible experiment

- local microbenchmark if possible
- then a 1-day SageMaker run

### 4. Inspect the breakdown, not just the headline

Ask:

- which stage changed?
- did one nested dependency get faster while another got slower?
- is the win real, or just noise?

### 5. Promote only the changes that survive the full pipeline

It is common for a local win to disappear end-to-end.

## Example: calibrated fixed-load performance test

```json
{
  "hotvect_execution_parameters": {
    "performance-test": {
      "enabled": true,
      "samples": 28800,
      "target_rps": 240
    }
  }
}
```

Interpret this as:

- a prior calibration run found max throughput near `300 rps`
- the fixed comparison run is pinned to `80%` of that (`240 rps`)
- the auto-sizing policy gives `max(min(200000, 240 × 120), 2000) = 28800` samples — about `120` seconds (`~2 min`) per iteration

Then compare:

- `mean`
- `p50`
- `p95`
- `p99`
- `p999`
- throughput
- max memory

If the implementation changed but the load also changed, the result is not trustworthy.

## Checklist

Before accepting an optimization:

- [ ] benchmark conditions were fixed
- [ ] realtime tests used a calibration run before pinning `target_rps`
- [ ] the final comparison load was committed in the algorithm definition or shared override
- [ ] change was isolated to one main variable
- [ ] local microbenchmark was confirmed by an end-to-end run
- [ ] both wall clock and nested stage breakdown were checked
- [ ] stable quality metrics stayed acceptable
- [ ] ranking metrics stayed acceptable
- [ ] cost and runtime were both considered for SageMaker

## Final rule

The goal is not to make one number smaller.

The goal is to:

- reduce real wall clock
- preserve acceptable model behavior
- keep the system understandable enough that the next optimization is still measurable

If a change makes the pipeline faster but harder to reason about, write down the benchmark method and the exact result next to the code or in the docs, so the next engineer can reproduce it.
