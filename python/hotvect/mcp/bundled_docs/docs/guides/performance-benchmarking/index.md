---
title: Reliable Performance Benchmarking
description: How to run hotvect performance tests that produce trustworthy latency, throughput, and memory comparisons.
tags: [performance, benchmarking, latency, throughput, statistics, regression]
difficulty: intermediate
estimated_time: 20 minutes
prerequisites:
  - hotvect CLI installed and working
  - Built algorithm JARs for the variants you want to compare
  - A stable input dataset or S3 prefix
  - A stable parameter bundle when the algorithm requires one
related_docs:
  - ../../reference/cli/index.md
  - ../sagemaker-backtests/index.md
  - ../../design/script-mode-standardization/index.md
related_commands:
  - hv performance-test
  - hv-ext metrics compare-system
  - hv-ext results download
next_steps:
  - Profile the slower variant with JFR after you confirm the regression is real
  - Add the benchmark checklist to your release workflow
---

# Reliable performance benchmarking

This guide is about a common failure mode:

- a single `hv performance-test` run appears to show a latency regression,
- especially at `p99` or `p999`,
- but the result does **not** survive a stricter rerun.

If you want a performance claim that will stand up to review, treat benchmarking as an experiment, not a one-off command.

## 1. Fix the comparison contract

Keep these the same between control and treatment:

- input data slice
- logical workload definition such as training horizon, sampled dates or partitions, and input channel wiring
- any setting that can change the learned model or evaluation outcome, such as hyperparameters, sampling ratio, feature toggles, or label construction
- parameter bundle
- training image / runtime version
- instance type and instance count
- volume size
- spot vs on-demand policy
- `--max-threads`
- JVM args
- any algorithm overrides
- cache / reuse policy
- encoded output layout and shard count when you are comparing train time
- compression / file format / source layout

If any of those change, you are no longer measuring just the algorithm or runtime change.

## 2. Decide what question you are asking

Do **not** mix these questions:

- **Same-load latency**: “At the same offered load, which version is faster?”
- **Capacity**: “Which version can sustain a higher request rate?”
- **Wall clock**: “Which end-to-end job finishes sooner?”

For **same-load latency**, use a fixed `--target-rps`.

For **capacity**, compare warmup throughput or run a separate sweep over target load.

Do not compare tail latency from runs that used different effective request rates.

## 3. Separate diagnostic benchmarks from production-like benchmarks

Sometimes you intentionally simplify the pipeline to isolate one stage:

- disable predict / evaluate to study train-only time
- pin a cache to reuse encoded artifacts
- replace the test channel with an empty input

That is useful, but it is a **diagnostic benchmark**, not a production-equivalent benchmark.

Rules:

- for a **production-like** wall-clock claim, keep the workload semantics unchanged
- do **not** change model-quality-affecting settings such as training horizon, hyperparameters, sampling policy, feature switches, or label logic
- do **not** change training horizon, channel wiring, shard count, or reuse policy just to make the benchmark easier
- if you do run a diagnostic benchmark, label it as such and do not reuse the number as a final production-performance claim

## 4. Pin the load and sample size

For version-to-version latency comparisons, prefer:

- `--target-rps <fixed value>`
- `--samples <fixed value>`

Example:

```bash
hv performance-test \
  --algorithm-jar /path/to/treatment.jar \
  --algorithm-name example-ranker \
  --parameter-path /path/to/parameters.zip \
  --source-path /path/to/input.jsonl.gz \
  --metadata-path ./treatment.perf.metadata \
  --target-rps 1500 \
  --samples 1000000
```

If you let each run pace itself from warmup (`--target-throughput-fraction`), then the offered load changes with the candidate. That is useful for capacity exploration, but it is a poor setup for A/B tail-latency claims.

## 5. Verify intermediate artifact equivalence when comparing training

If two variants are supposed to produce the same encoded training data, prove that before explaining any train-time delta.

Recommended workflow:

- persist the encoded output for each candidate
- keep encoded writer shard count fixed across candidates
- compare schema files directly
- fingerprint encoded content in an order-insensitive way if row order is allowed to differ
- if needed, cross-train each runtime on the other runtime's encoded output

Why this matters:

- a train-time gap may come from different encoded artifacts, not the trainer itself
- part-file layout mismatches can accidentally make one runtime consume a different artifact shape
- a harness bug can create an invalid comparison long before the timing numbers look suspicious

## 6. Use enough samples for the percentile you care about

Tail percentiles need a lot of observations.

Practical rule of thumb:

- `p95`: moderate sample sizes are often enough
- `p99`: use large samples and repeated runs
- `p999`: treat short runs with caution; prefer at least `1,000,000` requests per job

Why:

- with `200,000` samples, `p999` is determined by only about `200` requests
- with `1,000,000` samples, `p999` is determined by about `1,000` requests

That does **not** remove noise, but it makes false tail-regression calls less likely.

## 7. Replicate independent jobs

Do not rely on a single job.

Recommended minimum:

- `3` to `5` independent jobs per candidate

Independent here means separate job submissions, not just multiple files inside one run.

Also note that Hotvect itself repeats the measurement loop several times inside one `hv performance-test`
job. Those repeats are useful for diagnostics, but they still come from the same submitted job, process,
and sampled workload. Treat the **job** as the primary independent unit for statistical inference.

Interleave or randomize the submission order of replicated jobs.

Example:

- good: baseline-1, treatment-1, baseline-2, treatment-2
- worse: all baseline jobs first, then all treatment jobs

This reduces the risk that time drift, shared-infrastructure noise, or spot-market behavior gets mistaken for a treatment effect.

For SageMaker one-shot tests, keep the same:

- instance shape
- volume
- spot / on-demand mode
- `target_rps`
- `samples`
- source prefix
- parameter ZIP

Then collect all metadata files and compare the replicated results, not just one “best” run.

## 8. Start with summary diffs, then do statistics

Use `hv-ext metrics compare-system` for a quick metric diff:

```bash
hv-ext metrics compare-system \
  ./baseline.perf.metadata/metadata.json \
  ./treatment.perf.metadata/metadata.json
```

That is a **summary** step, not a statistical conclusion.

For a reliable claim:

- compare the replicated job-level metrics
- if raw repeated perf runs are available in metadata, also compare them at run level
- report both effect size and statistical significance
- use the replicated **job-level** comparison as the primary basis for the claim
- do not pool the internal repeated perf runs as if they were fully independent samples unless you are using
  an explicit hierarchical model

Good default tests:

- **Welch t-test** for unequal-variance mean comparison
- **Permutation test** as a distribution-free check
- **Bootstrap confidence interval** for the effect size

Interpretation:

- if `p99` or `p999` moves but the confidence interval still spans zero, do **not** call it a confirmed regression
- if throughput is flat and latency improves significantly, say that directly
- if a tail metric is noisy, say it is inconclusive rather than forcing a narrative

## 9. Verify custom runtime overrides end to end

If you inject a custom runtime, do not verify only the imported Python package version.

Also verify that the command-line entrypoints used by shell steps come from the same runtime:

- `hv`
- `hv-ext`
- `catboost_train`
- any helper scripts invoked by the pipeline

Otherwise you can create a hybrid benchmark where Python comes from one version and shell entrypoints come from another. Those runs are invalid even if they appear to "work."

## 10. Report the full benchmark contract

A useful benchmark note includes:

- date
- exact candidate names
- runtime version
- instance type
- volume size
- source dataset / partition
- whether the run was production-like or a diagnostic stage-isolation benchmark
- fixed `target_rps`
- `samples`
- number of replicated jobs
- whether replicated jobs were interleaved or randomized
- the latency / throughput / memory metrics you used

Without that context, later readers cannot tell whether two results are comparable.

Hotvect records this run contract in `result.json` as `benchmark_contract` for performance-test runs.
Use it as the source of truth when you compare or archive benchmark results.

Example:

```json
{
  "benchmark_contract": {
    "parameter_s3_uri": "s3://example-bucket/model.parameters.zip",
    "source_s3_uri": "s3://example-bucket/perf-input/",
    "instance_type": "ml.c7i.4xlarge",
    "training_image": "<account-id>.dkr.ecr.<aws-region>.amazonaws.com/hotvect:<HOTVECT_VERSION>",
    "samples": 1000000,
    "sample_pool_size": 50000,
    "target_rps": 1500.0,
    "max_threads": 8,
    "workload_mode": "realtime",
    "execution_command": [
      "java",
      "-Xmx16g",
      "-cp",
      "/path/to/hotvect-offline-util.jar",
      "com.hotvect.offlineutils.commandline.Main",
      "performance-test",
      "..."
    ],
    "output_prefixes": {
      "metadata": "s3://example-bucket/output/job/algo/metadata",
      "result": "s3://example-bucket/output/job/algo/result.json",
      "task_output": "s3://example-bucket/output/job/algo/perf",
      "sagemaker_output": "s3://example-bucket/output/job"
    }
  }
}
```

The same key names are used for local/full-pipeline performance tests and one-shot SageMaker
performance tests, but not every field applies to every execution mode:

- `samples` is the measured execution count. `sample_pool_size` is the retained request pool used
  for replay. Keep them separate when comparing runs.
- `parameter_s3_uri` identifies a remote parameter ZIP. Local runs may instead record
  `parameter_path`.
- One-shot SageMaker runs usually record a single `source_s3_uri`. Full-pipeline runs may record
  local `source_paths` and, for SageMaker backtests, an `input_channels` map because the job can
  mount several channels rather than one source prefix.
- `output_prefixes` contains the known metadata, result, task-output, parameter, and SageMaker
  output locations for the run. Some entries are absent when Hotvect cannot know that location for
  the selected execution mode.
- `execution_command` is the actual Java argv used for the performance test. It includes JVM flags
  and Hotvect offline-util flags. Paths may be local paths for local runs or container paths for
  SageMaker runs.

## 11. Profile only after the regression is real

If the stricter benchmark still shows a real slowdown, then move to profiling:

- Java Flight Recorder (JFR)
- allocation / GC inspection
- request-path instrumentation

Do **not** start by explaining a regression that you have not confirmed statistically.

## Quick checklist

Before you publish a performance conclusion, make sure the answer to all of these is “yes”:

- Did I keep runtime, hardware, data, and parameters fixed?
- Did I also keep training horizon, channel wiring, cache policy, and shard count fixed?
- Did I avoid changing anything that can change model quality, such as hyperparameters, sampling ratio, feature switches, or label construction?
- Did I use fixed `target_rps` for same-load latency comparisons?
- Did I pin `samples`?
- If this was a train-time comparison, did I verify encoded artifacts were equivalent?
- Did I run multiple independent jobs per candidate?
- Did I interleave or randomize replicated jobs?
- Did I avoid over-interpreting a single `p999` number from a short run?
- Did I run at least one statistical test?
- If I used a custom runtime, did I verify both Python imports and CLI entrypoints came from that runtime?
- Did I write down the exact benchmark contract?

If any answer is “no”, rerun before making the claim.

## Next

- [Performance investigations](../performance-investigations/index.md) localizes a confirmed regression to a stage.
- [Evaluation metrics and uncertainty](../../reference/evaluation-metrics/index.md) keeps quality evidence separate
  from latency and throughput evidence.
- [Command-line interfaces](../../reference/cli/index.md) lists the exact performance-test and backtest options.
