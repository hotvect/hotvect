---
title: Release QA validation with hv qa
description: Automate objective-driven release validation for agentic Hotvect engineering loops.
tags: [qa, validation, release, automation, agents, criteria, backtest, performance, uncertainty]
difficulty: intermediate
estimated_time: 30 minutes
prerequisites:
  - Hotvect Python package installed
  - Fixed control and treatment references
  - An algorithm repository available locally or by Git URL
  - Data and remote-execution access required by the requested stages
related_docs:
  - ../validate-and-investigate/index.md
  - ../../reference/evaluation-metrics/index.md
  - ../../reference/cli/index.md
  - ../../reference/config/index.md
  - ../local-backtest/index.md
  - ../performance-benchmarking/index.md
  - ../score-equivalence/index.md
  - ../change-to-live-experiment/index.md
related_commands:
  - hv qa candidate start
  - hv qa candidate resume
  - hv qa candidate status
  - hv qa evaluate
  - hv qa criteria
next_steps:
  - Configure qa.run defaults for your environment
  - Start a multi-day noninferiority run
  - Inspect the automated verdict and supporting evidence
  - Follow the live-experiment guide after QA passes
---

# Release QA validation with `hv qa`

`hv qa` is the automated release evaluator in an agentic Hotvect engineering loop. After an engineer or coding agent
produces a candidate revision, `hv qa`:

1. freezes the control, candidate, data window, and release objective;
2. derives the required validation stages from that objective;
3. executes the applicable parity, quality, and system-performance checks; and
4. returns a machine-readable release-readiness verdict.

A failed verdict feeds the next implementation iteration. A passing verdict is the handoff to release review and
rollout. The engineer or agent chooses the objective, not an ad hoc list of low-level tests.

The frozen plan and evidence make the verdict reproducible and auditable. That provenance supports the engineering
loop; it is not the purpose of the tool.

`hv qa` stops at the release-evidence boundary. It can read the current production default with
`--prod-default-of-slot-as-control`, but it never publishes an artifact or changes experiment-management state. After
the QA decision passes, use [Take a change to a live experiment](../change-to-live-experiment/index.md) for the
separate publication, registration, and rollout workflow.

## Objective determines the validation flow

With `hv qa`, you do not assemble a workflow by choosing individual tests. You declare the release objective with a
criteria policy, provide the control and treatment sources, and `hv qa` derives and runs the stages required to support
that decision. The resulting run records the inputs, stages, evidence, and criteria judgment together.

- `exact` adds fixed-parameter audit, encode, and prediction parity before the quality and system gates.
- `noninferiority` runs the safety-oriented quality and system-performance gates; it is the default.
- `superiority` runs the same safety gates and additionally requires statistically supported quality improvement.

The `--until` and `--stage` options control how far an eligible run proceeds; they do not define a different validation
model. Use them to stage or resume work when needed.

The standalone [score equivalence](../score-equivalence/index.md), [local backtest](../local-backtest/index.md), and
[performance benchmarking](../performance-benchmarking/index.md) commands remain useful for focused investigation or
diagnosis outside a release decision. They are not alternative paths through an `hv qa` run.

## Command surface

```bash
hv qa <command> ...
```

| Command | Purpose |
| --- | --- |
| `hv qa candidate start` | Create and execute a staged QA run for one control and one or more treatments. |
| `hv qa candidate resume` | Continue a durable run without redefining its control, treatments, dates, or criteria. |
| `hv qa candidate status` | Print machine-readable state for a run. |
| `hv qa evaluate` | Judge a pre-existing proof directory without creating QA-run state. |
| `hv qa criteria` | List or describe a built-in release criterion. |

For the complete flag reference, use [Command-line interfaces](../../reference/cli/index.md#hv-qa-release-qa).

## Pick a criterion

| Criterion | Use when | Decision contract |
| --- | --- | --- |
| `exact` | A runtime, storage, or dependency change must preserve behavior. | Requires fixed-parameter audit, encode, and predict parity before the quality and system gates. `exact` rejects manual or configured parameter sources; when a parameter-backed stage runs, it derives the latest control parameter from experiment management. |
| `noninferiority` | A candidate must not be materially worse than control. This is the default. | Uses paired multi-day quality and paired system-performance checks. |
| `superiority` | A candidate is expected to improve quality. | Reuses noninferiority safety gates and requires statistically supported quality improvement on the authoritative multi-day stage. |

Inspect the installed policies instead of copying their behavior into automation:

```bash
hv qa criteria list
hv qa criteria describe noninferiority
hv qa criteria describe superiority
```

The raw policy evaluator remains available through the legacy `hv-qa criteria evaluate` command. It is useful for
debugging a saved stage payload, not for replacing an ordinary QA run:

```bash
cat stage-payload.json | hv-qa criteria evaluate noninferiority --pretty
```

## Understand the stages

| Stage | Evidence | Notes |
| --- | --- | --- |
| `audit_parity` | Feature/audit parity with one shared parameter package | Used by `exact`. |
| `encode_parity` | Encoded training/test artifact parity | Used by fixed-parameter paths. |
| `predict_parity` | Prediction-output parity | Used by `exact`. |
| `system_performance` | Replicated latency, throughput, and memory measurements | Requires a compatible committed performance-test specification. |
| `realistic_single_day` | One realistic backtest execution | A smoke check only; it is not a quality judgment. |
| `multi_day_backtest` | Paired realistic backtests across dates | The authoritative offline quality gate. |

`--until <stage>` runs the eligible sequence through that stage. `--stage <stage>` runs exactly one eligible stage.
If the stage needs context that the scenario cannot supply, `hv-qa` fails before launching work.

## Prepare the performance contract

For `noninferiority`, and for every `multi_day_backtest`, each compared algorithm definition must declare the same
enabled `hotvect_execution_parameters.performance-test` specification. It must include:

- `samples`;
- `sample_pool_size`;
- exactly one of `target_rps` or `target_throughput_fraction`; and
- `workload_mode` (`realtime` or `batch`).

The definition is authoritative. `hv-qa` rejects a missing, disabled, or mismatched specification before it submits
work. Do not use `qa.run.system_performance` or backtest flags to replace the load or sample fields; matching values
are accepted, conflicting values fail fast. Likewise, omit `qa.run.backtest.no_performance_test` (or set it to
`false`) for a normal noninferiority or multi-day run: those stages require the backtest performance test to be
enabled.

Use `qa.run.system_performance` for execution mechanics such as the runner, SageMaker settings, and number of trials.
The [performance guide](../performance-benchmarking/index.md) explains why matching specs and independent trials are
necessary before making a performance claim.

## Start a same-repository run

Configure execution defaults once (see [Configuration reference](../../reference/config/index.md#qa-optional)), then
start with the release shape:

```bash
hv qa candidate start \
  --control baseline-ref \
  --treatment candidate-ref \
  --repo /path/to/example-algorithm \
  --criteria noninferiority \
  --until multi_day_backtest
```

Repeat `--treatment` to compare more than one candidate. A repeated per-treatment flag must use the same order as the
corresponding `--treatment` values.

Without `--last-test-date`, `hv-qa` lists the control algorithm's S3 test-data partitions and selects the latest
contiguous window. The window is seven days by default; use `--days` to override it. If a day is missing, the run
fails before execution rather than selecting a shorter, gapped, or older range: when a complete window exists only
behind a gap, the error names both the newer partitions it would have had to skip and the missing dates, so you can
either repair the data or pass `--last-test-date` to choose a window deliberately.

`--control` accepts a fixed Git reference. Alternatively, use a read-only experiment-management lookup:

```bash
hv qa candidate start \
  --prod-default-of-slot-as-control example-slot \
  --treatment candidate-ref \
  --repo /path/to/example-algorithm
```

At start time, that lookup freezes the resolved control reference in the scenario without changing the requested test
window. For each requested UTC date, it runs that algorithm with that date's production parameter: the last one
registered on the date, or the latest earlier one when no rotation occurred. If a date has no resolvable parameter,
the whole run fails and names that date; it never shortens or gaps the test window.

## Compare different repositories safely

Use `--repo` for a shared repository, or give every arm an explicit repository. `--control-repo` applies to control;
repeat `--treatment-repo` once per treatment in treatment order:

```bash
hv qa candidate start \
  --control main \
  --control-repo https://github.com/example-org/baseline-algorithm.git \
  --treatment main \
  --treatment-repo https://github.com/example-org/candidate-algorithm.git \
  --criteria noninferiority
```

Arm identity includes the role and repository. Control and treatment may therefore both spell their ref `main` without
sharing build, artifact, remote-job, or output state. Branch names may include a control version and built artifacts
may share a version; those cases use isolated output state. The narrow early rejection is for two explicit
version-shaped refs that encode the same semantic version, such as `v1.2.3` and `1.2.3`.

For a per-reference system-performance setup, use the same ordered shape for parameters and sources:

```bash
hv qa candidate start \
  --control baseline-ref \
  --control-repo /path/to/baseline-algorithm \
  --control-parameter-source s3://example-bucket/parameters/baseline.zip \
  --control-performance-source-path s3://example-bucket/input/baseline.jsonl.gz \
  --treatment candidate-ref \
  --treatment-repo /path/to/candidate-algorithm \
  --treatment-parameter-source s3://example-bucket/parameters/candidate.zip \
  --treatment-performance-source-path s3://example-bucket/input/candidate.jsonl.gz \
  --stage system_performance
```

Do not combine the shared `--parameter-source` or `--performance-source-path` form with the per-reference form.
`exact` does not accept either parameter-source form because it owns the shared-parameter derivation contract.

## Read the multi-day quality decision

The single-day stage proves that the workflow can execute. It does not accept or reject a quality claim. Quality
inference happens on `multi_day_backtest` and pairs the central `evaluate.<metric>.value` estimate for the same test
date on control and treatment.

Do not pool the marginal `ci95_lower` and `ci95_upper` values from separate result files. Those intervals do not
contain the paired covariance needed for a treatment-versus-control test. Each comparison records this explicitly:

```json
{
  "quality_statistical_basis": {
    "pairing_unit": "test_date",
    "metric_estimate_field": "value",
    "source_confidence_intervals": "not_used_without_paired_covariance"
  }
}
```

This is distinct from the per-result uncertainty displayed by [Evaluation metrics and uncertainty](../../reference/evaluation-metrics/index.md).

The quality gate evaluates only `roc_auc`, `pr_auc`, `map_at_10`, `map_at_50`, `map_at_100`, `map_at_all`,
`ndcg_at_10`, `ndcg_at_50`, `ndcg_at_100`, and `ndcg_at_all` (including a qualified form such as
`algorithm.ndcg_at_50`). Other reported metrics remain evidence but do not affect the gate.

For `superiority`, every gated quality metric for every treatment belongs to one family. The policy applies a
Bonferroni adjustment and records `nominal_alpha`, adjusted `alpha`, `family_size`, and
`multiplicity_adjustment: "bonferroni"` with each quality test. A result can pass the superiority gate only when at
least one quality metric clears its adjusted threshold and the noninferiority safety gates also pass.

## Judge existing proof directories

When control and treatment results already exist, use `hv qa evaluate` rather than creating a new run. The proof
directory must contain one pair per requested date:

```text
<proof-dir>/2000-01-07/control/result.json
<proof-dir>/2000-01-07/treatment/result.json
<proof-dir>/2000-01-08/control/result.json
<proof-dir>/2000-01-08/treatment/result.json
```

```bash
hv qa evaluate \
  --criteria noninferiority \
  --last-test-date 2000-01-08 \
  --days 2 \
  --proof-dir /path/to/proof \
  --output judgment.json \
  --pretty
```

Standalone evaluation requires at least two paired dates. It writes no QA-run state and returns the evidence,
criteria judgment, `quality_statistical_basis`, and any compatible system metrics in the JSON response. A passing
multi-day proof also needs comparable quality estimates, system metrics, and a matching committed performance-test
specification in each result. `evaluate --criteria exact` cannot establish the audit, encode, or predict parity
required for a complete `exact` migration proof.

## Resume and inspect durable runs

By default, `hv-qa` stores state under:

```text
<work-dir>/hv-qa/runs/<run-id>/
```

Key files include:

| Path | Purpose |
| --- | --- |
| `plan.json` | Frozen run plan: control, treatments, repositories, dates, versioned criteria, execution context, and stage options. |
| `status.json` | Current stage status and failure/inconclusive state. |
| `decisions.jsonl` | Criteria decisions as stages complete. |
| `evidence.json` | Current machine-readable evidence bundle, including resolved artifact identities and stage judgments. |
| `tracks/` | Preflight, performance, and quality stage artifacts. |

```bash
hv qa candidate status <run-id>
hv qa candidate resume <run-id> --until multi_day_backtest
hv qa candidate resume <run-id> --stage system_performance
```

Without `--work-dir`, the run root is `<output-base-dir>/meta/hv-qa/runs/<run-id>/`, using
`directories.output_base_dir`. With `--work-dir /path/to/qa-work`, it is
`/path/to/qa-work/hv-qa/runs/<run-id>/`. `resume` reads only the frozen plan; it does not reload configuration or
accept new control, treatment, date, criteria, execution-context, or stage-option values.

## Troubleshooting

**The multi-day stage says the performance test must be enabled**

Remove `qa.run.backtest.no_performance_test`, or set it to `false`. Inspect both algorithm definitions for identical,
enabled committed performance-test specifications.

**The committed performance-test specification conflicts with config**

Remove the conflicting load, sample, pool, or workload value from QA config. The definitions, not the execution
configuration, define the A/B comparison contract.

**`exact` rejects a parameter source**

Remove manual and `qa.run.execution.parameter_source` settings. `exact` derives the latest control parameter through
experiment management when a parameter-backed stage needs one.

**A production parameter cannot be resolved for a date**

Experiment management has no parameter for the resolved algorithm version registered by the end of the named UTC
date. Choose a window beginning on or after the first parameter registration, or specify a fixed `--control` reference.

**A treatment repository, parameter, or source is missing**

For every repeated `--treatment`, provide exactly one corresponding per-treatment flag in the same order, or use the
shared form when the resource is truly common.
