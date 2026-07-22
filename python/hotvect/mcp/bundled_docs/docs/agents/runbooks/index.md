---
title: Agent runbooks
description: Deterministic Hotvect workflows with inputs, commands, artifacts, and verification signals
tags: [agents, runbooks, workflows]
---

# Agent runbooks

Choose a runbook by the artifact or environment you need to validate. Each one is intentionally narrow: fill the
inputs, run the command, inspect the named outputs, and stop on a failed verification signal.

<div class="grid cards" markdown>

-   **Local backtest**

    Compare one or more git references against local data.

    [Open local backtest](local-backtest/index.md){ .hv-btn }

-   **Local train**

    Produce a reusable parameter artifact locally, with cache checks.

    [Open local train](local-train/index.md){ .hv-btn }

-   **SageMaker backtest**

    Submit a remote comparison, locate its manifest, and verify every job.

    [Open SageMaker backtest](sagemaker-backtest/index.md){ .hv-btn }

-   **Debugging and logs**

    Find the authoritative local, S3, or CloudWatch signal before retrying.

    [Open debugging runbook](debugging/index.md){ .hv-btn }

-   **Docs build and preview**

    Build this site strictly and inspect a rendered local preview.

    [Open docs runbook](docs-build/index.md){ .hv-btn }

</div>

For behavior contracts shared by every runbook, read [Contracts](../contracts/index.md) first.
