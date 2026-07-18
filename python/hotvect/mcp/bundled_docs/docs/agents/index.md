---
title: Agent Playbook
description: Deterministic runbooks and contracts for coding agents working with Hotvect
tags: [agents, runbook, contracts]
---

# Agent Playbook

Hotvect includes deterministic contracts and runbooks for coding agents. They complement the main human-oriented
concepts, guides, and reference documentation. This section prioritizes:

- **Deterministic contracts** (what inputs/outputs mean, what files exist, how overrides merge)
- **Copy/paste runbooks** (commands + expected artifacts/log lines)
- **Fast retrieval** (short pages, stable section headings, minimal narrative)

## Retrieve before acting

Use the bundled Markdown as the source of truth for the installed Hotvect version:

```bash
hv docs search "sagemaker backtest" --limit 5
hv docs read agents/runbooks/sagemaker-backtest/index.md
```

Use [Docs MCP](../guides/docs-mcp/index.md) when the agent needs the same documents through MCP. Verify commands
against `hv <command> --help` when the working tree and installed package might differ.

<div class="hv-cta-row" markdown>
[Contracts](contracts/index.md){ .hv-btn }
[Runbooks](#start-here-agent-path){ .hv-btn }
[Debugging & logs](runbooks/debugging/index.md){ .hv-btn }
</div>

## Start here (agent path)

1. Read **Contracts**: `agents/contracts/index.md`
2. Pick a **Runbook**:
   - [Prepare local development environment](../guides/local-development-env/index.md)
   - [Local backtest](runbooks/local-backtest/index.md)
   - [Local train](runbooks/local-train/index.md)
   - [SageMaker backtest](runbooks/sagemaker-backtest/index.md)
   - [Docs build & preview](runbooks/docs-build/index.md)
   - [Debugging & logs](runbooks/debugging/index.md)

## High-signal invariants (do not guess)

- **Overrides are recursive merges**. If you need to override a dependency algorithm, it must be nested under
  `dependencies.<dependency_algorithm_name>`. (See [Override files](../guides/patterns/override-files/index.md) and
  [Parent/child algorithms](../guides/patterns/parent-child/index.md).)
- **Run-level caching is keyed by `parameter_version`** (default `last_test_date_YYYY-MM-DD`). A changed
  `--last-test-time` normally changes that key. The date-partition encode cache is different: it intentionally reuses
  overlapping partitions across runs.
- **Use an `s3://...` cache for SageMaker reuse across jobs**. Local cache paths are accepted but disappear with the
  container when the job ends.
- **SageMaker pipeline execution** is supported by `hv train` and `hv backtest`; use `--sagemaker` or
  `--sagemaker-config`, plus `--sagemaker-job-prefix` and a job template or template-free settings. One-shot `audit`, `predict`, `evaluate`,
  `encode`, and `performance-test` also support `--sagemaker`; see the [CLI reference](../reference/cli/index.md).
- **Quality metrics are estimates**. Read the central value from `value` and treat `ci95_lower`/`ci95_upper` as
  optional. See [Evaluation metrics and uncertainty](../reference/evaluation-metrics/index.md).

## Common entry points (quick links)

<div class="grid cards" markdown>

-   **Caching**

    Reuse encode/train artifacts (local or `s3://...`) to speed up iteration.

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
