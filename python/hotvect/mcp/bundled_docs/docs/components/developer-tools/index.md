---
title: Hotvect developer tools
description: Choose the Hotvect command-line and agent tools for local development, workflow execution, inspection, and debugging
tags: [components, cli, developer-tools, local, debugging]
related_docs:
  - ../../reference/cli/index.md
  - ../local-algorithm-server/index.md
  - ../../guides/local-development-env/index.md
  - ../../guides/docs-mcp/index.md
---

# Hotvect developer tools

The Hotvect Python distribution installs a tool suite around the Java algorithm runtimes. These tools help authors run
workflows, investigate artifacts, inspect experiment state, and debug a complete algorithm locally. They are development
and automation surfaces; installing them does not deploy an online serving application or EMS.

## Choose a tool

| Tool | Use it for | Main framework connection |
| --- | --- | --- |
| `hv` | Run local and remote algorithm workflows, inspect bundled docs, and start the local debugger | Algorithm SDK, offline workflows, online integration |
| `hv-ext` | Compare generic JSONL output and convert CatBoost models | Supporting utilities |
| `hv ems` | Inspect algorithms, parameters, slots, experiments, ramp-up history, and online results | Experiment management |
| `hv-mcp` | Expose the bundled documentation and reviewed workflow prompts to an MCP-compatible agent | Agent-assisted workflows |

The installed package also contains execution entry points used inside managed jobs. For example,
`sagemaker-entrypoint` boots a submitted workload. Those entry points are runtime plumbing rather than separate components
for algorithm authors.

## `hv`: run and debug algorithms

`hv` is the main workflow command. Its commands cover state generation, encoding, training, prediction, evaluation,
backtesting, audits, performance tests, and local serving. The command selects the appropriate Python orchestration and
Java runtime for the operation.

`hv algorithm serve` is a local development surface. It loads a complete algorithm and exposes a debugging HTTP API and optional
browser UI. It is not the production hosting model; production applications embed the
[online runtime](../../architecture/online-runtime/index.md).

## `hv-ext`: work around the execution boundary

`hv-ext` handles the supporting operations that do not fit a Hotvect domain. Its current commands compare generic JSONL
output and convert CatBoost models.

Keeping these operations separate from `hv` makes the execution boundary explicit: `hv` runs a Hotvect algorithm or
workflow, while `hv-ext` prepares or analyzes its inputs and outputs.

## `hv ems`: inspect the control plane

`hv ems` is the read-only EMS inspection CLI. It is useful for understanding which versions are active, checking a
slot's current experiment state, and examining ramp-up or parameter history without exposing mutation commands.

Experiment creation and release automation use the typed Python EMS client or the deployed HTTP API with appropriate
authorization. Read [EMS clients and operator tools](../ems-clients-and-tools/index.md) for that boundary.

## `hv-mcp`: make the documentation executable

`hv-mcp` exposes the documentation bundled with the installed Hotvect version, together with reviewed prompts for
common development and investigation workflows. This keeps an agent's instructions aligned with the actual installed
CLI and runtime version.

The documentation remains available without MCP through `hv docs`.

## Start by goal

- To build and run locally, prepare the [local development environment](../../guides/local-development-env/index.md).
- To find exact flags, use the [CLI reference](../../reference/cli/index.md) or `<command> --help`.
- To inspect a running algorithm, use the [local algorithm server and debugger](../local-algorithm-server/index.md).
- To run repeatable agent workflows, start with the [agent workflow](../../agents/index.md).
