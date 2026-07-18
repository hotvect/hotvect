---
title: Backtest an algorithm locally
description: Build fixed algorithm revisions, run their historical lifecycles locally, and inspect comparable results
tags: [backtest, local, comparison, workflow]
---

# Backtest an algorithm locally

Use `hv backtest` to ask how one or more fixed source revisions behave on one or more historical test dates. A backtest
builds each revision, prepares its dependency graph, and records quality and system evidence from the enabled stages.

## You need

- an activated Hotvect installation;
- an algorithm repository URL or local checkout;
- fixed git references;
- local data matching the effective definitions for the selected dates;
- dedicated output and scratch directories;
- either all three directory flags below or matching defaults in `~/.hotvect/config.json`.

Prepare data with the same reference, override, target, and test date as the intended run. See
[Prepare local development](../local-development-env/index.md) for dependency listing and sampled downloads.
Dates passed to `--last-test-time` use `YYYY-MM-DD` format.

## Configure the local directories once

If no Hotvect config exists:

```bash
test ! -e ~/.hotvect/config.json
hv-ext config init \
  --data-base-dir /path/to/data \
  --output-base-dir /path/to/default-output \
  --scratch-dir /path/to/default-scratch
```

`hv-ext config init` refuses to overwrite an existing file. Inspect and edit that file; use `--force` only when replacing
the complete configuration is intentional.

## Run one fixed revision first

```bash
hv backtest \
  --git-reference <git-reference> \
  --algo-repo-url /path/to/algorithm-checkout \
  --data-base-dir /path/to/data \
  --output-base-dir /path/to/output \
  --scratch-dir /path/to/scratch \
  --last-test-time <test-date> \
  --number-of-runs 1 \
  --no-performance-test
```

Use a commit SHA when the result must remain reproducible. A branch is appropriate only when you intentionally want
its current tip.

Start without performance testing when the first question is build, data, dependency, or quality correctness. Add a
fixed performance contract later; performance numbers are not comparable merely because two backtests completed.

## Compare revisions

After one revision completes, add another fixed reference:

```bash
hv backtest \
  --git-reference <baseline-reference> \
  --git-reference <candidate-reference> \
  --algo-repo-url /path/to/algorithm-checkout \
  --data-base-dir /path/to/data \
  --output-base-dir /path/to/comparison-output \
  --scratch-dir /path/to/comparison-scratch \
  --last-test-time <test-date> \
  --number-of-runs 1 \
  --no-performance-test
```

Keep the data, date, override semantics, and evaluation function fixed unless the experiment is specifically about one
of those inputs.

## Inspect success and comparability

Backtest outputs and metadata use separate roots:

```text
<output-base-dir>/out/...
<output-base-dir>/meta/<algorithm-id>/<parameter-version>/result.json
<output-base-dir>/meta/<algorithm-id>/<parameter-version>/hv.log
<output-base-dir>/meta/<algorithm-id>/<parameter-version>/hv.all.log
```

For every requested reference and date, confirm:

- the process exited successfully;
- `result.json` exists and identifies the expected algorithm and stages;
- dependency and cache behavior is understood;
- evaluation metrics come from the intended prediction artifact;
- any skipped stage has an expected reason.

A failed reference makes the comparison incomplete. Do not summarize only the successful side.

## Next

- [Evaluation metrics and uncertainty](../../reference/evaluation-metrics/index.md) explains quality comparisons.
- [Performance benchmarking](../performance-benchmarking/index.md) explains controlled latency and throughput claims.
- [Score equivalence](../score-equivalence/index.md) is the narrower tool for behavior-preserving changes.
- [Embed Hotvect in Java](../application-integration/index.md) covers the containing-application runtime after the
  artifact is validated.
