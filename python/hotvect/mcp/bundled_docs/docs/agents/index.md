---
title: Agent Playbook
description: Agent-first runbooks and contracts for working with hotvect safely and deterministically
tags: [agents, runbook, contracts]
---

# Agent Playbook

This docsite is optimized for coding agents. It is still readable for humans, but it prioritizes:

- **Deterministic contracts** (what inputs/outputs mean, what files exist, how overrides merge)
- **Copy/paste runbooks** (commands + expected artifacts/log lines)
- **Fast retrieval** (short pages, stable section headings, minimal narrative)

<div class="hv-cta-row" markdown>
[Contracts](contracts/index.md){ .hv-btn }
[Runbooks](#start-here-agent-path){ .hv-btn }
[Debugging & logs](runbooks/debugging/index.md){ .hv-btn }
</div>

## Start here (agent path)

1. Read **Contracts**: `agents/contracts/index.md`
2. Pick a **Runbook**:
   - [Local backtest](runbooks/local-backtest/index.md)
   - [Local train](runbooks/local-train/index.md)
   - [SageMaker backtest](runbooks/sagemaker-backtest/index.md)
   - [Docs build & preview](runbooks/docs-build/index.md)
   - [Debugging & logs](runbooks/debugging/index.md)

## High-signal invariants (do not guess)

- **Overrides are recursive merges**. If you need to override a dependency algorithm, it must be nested under
  `dependencies.<dependency_algorithm_name>`. (See [Override files](../guides/patterns/override-files/index.md) and
  [Parent/child algorithms](../guides/patterns/parent-child/index.md).)
- **Caching is keyed by `parameter_version`** (default `last_test_date_YYYY-MM-DD`). If `--last-test-time` changes,
  you should expect a cache miss.
- **SageMaker caching must use `s3://example-bucket`**. Local paths inside the container do not persist across jobs.
- **SageMaker execution is supported by `hv train` and `hv backtest`** (use `--sagemaker` + `--sagemaker-job-prefix` and provide a base job definition via `--sagemaker-config` or `~/.hotvect/config.json`).

## Common entry points (quick links)

<div class="grid cards" markdown>

-   **Caching**

    Reuse encode/train artifacts (local or `s3://example-bucket`) to speed up iteration.

    [Open caching guide](../guides/caching/index.md){ .hv-btn }

-   **SageMaker backtests**

    Run backtests at scale and inspect job outputs.

    [Open SageMaker guide](../guides/sagemaker-backtests/index.md){ .hv-btn }

-   **Overrides**

    Change behavior deterministically without forking definitions.

    [Open override pattern](../guides/patterns/override-files/index.md){ .hv-btn }

-   **Parent/child algorithms**

    Understand dependency composition and override propagation.

    [Open parent/child pattern](../guides/patterns/parent-child/index.md){ .hv-btn }

-   **CLI reference**

    Commands, flags, outputs, and common patterns.

    [Open CLI reference](../reference/cli/index.md){ .hv-btn }

</div>
