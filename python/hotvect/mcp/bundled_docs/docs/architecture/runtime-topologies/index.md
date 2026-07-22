---
title: Runtime topologies
description: Current Hotvect process and execution arrangements, their operational boundaries, and the distribution direction
tags: [architecture, runtime, topology, workers, distribution]
---

# Runtime topologies

Hotvect separates logical composition from runtime placement, but it does not pretend that every placement has the same
operational semantics.

## Supported topology matrix

| Topology | Status | What Hotvect supplies | Important boundary |
| --- | --- | --- | --- |
| Parent and child algorithms in one JVM | Available | Recursive loading, composite factories, parameter streams, lifecycle | Calls share the host process and its resources |
| JVM algorithm with managed Python workers | Available | Local worker processes, UDS protocol, worker lifecycle and configuration | Workers run on the same machine; this is not generic remote placement |
| LitServe worker HTTP surface | Local debugging | Worker-only HTTP startup and backend semantics | Bypasses the full JVM feature and decision path |
| Host-provided external algorithm binding | Available, with loader caveat | Named `AlgorithmInstance` value passed to the composite factory | The declared child is still loaded first; the host owns the external object and transport |
| Algorithm with runtime-local filesystem state | Available in an explicitly configured containing runtime | Definition capability flag, private-directory allocator, repository integration | Current `hv serve` modes do not configure the required local-state root; algorithm code owns cleanup |
| SageMaker offline job | Available | Whole-job packaging, submission, channels, manifests, result collection | Remote job execution is not request-time dependency distribution |
| Declarative arbitrary remote child binding | Direction | No generic proxy, discovery, wire protocol, or placement engine today | Must not be documented as a shipped capability |

## Location-independent composition

An algorithm depends on named capabilities rather than hard-coding how the runtime obtained every implementation. A
host can therefore supply a local implementation or a client object behind the same algorithm-facing interface.

This is useful location independence, but the client object still embodies a network boundary. The algorithm and host
must account for:

- serialization and payload size;
- batching and concurrency;
- latency budgets and timeouts;
- partial failure and backpressure;
- resource ownership and shutdown;
- observability and version compatibility.

## Python worker placement

Direct workers are long-lived Python subprocesses managed by the JVM. They connect over Unix domain sockets and use a
framed binary protocol. Realtime and batch scopes can have different worker configurations.

LitServe is a separate HTTP debugging path for the worker runtime. It is not the transport used by direct workers and
does not execute JVM request decoding or feature extraction.

Read [Direct Python workers](../../design/direct-python-workers/index.md) for the exact protocol.

## Framework direction

The direction is one logical algorithm graph with multiple explicit execution topologies, including specialized
external inference or retrieval services. Hotvect should introduce such bindings through versioned contracts with
observable operational behavior—not through implicit fallback or the assumption that a remote call behaves like a
local method.
