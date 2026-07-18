---
title: Status and direction
description: Which parts of the Hotvect application and lifecycle model are available now, bounded, or directional
tags: [architecture, status, direction, roadmap]
---

# Status and direction

This page separates the current framework from its longer-term thesis. “Direction” describes the intended model, not a
promise that the current release implements it.

## Available now

| Capability | Current boundary |
| --- | --- |
| Complete algorithm artifacts | Algorithm package (currently a JAR) and optional parameter package (currently a ZIP) |
| Public decision surfaces | Ranker and TopK in local serving; ranker, bulk scorer, TopK, and ThemedTopK in offline prediction |
| State-generation workflows | Definitions can generate and package lookup data, aggregates, or other state |
| Composite dependency graphs | Recursive child definitions, preparation, loading, and composite factories |
| Host-provided dependencies | Named `AlgorithmInstance` overrides supplied by the containing application |
| Offline lifecycle | State, encode, train, predict, evaluate, audit, performance test, and backtest workflows |
| Online application integration | Dynamic algorithm and parameter loading through the online utility module |
| Configuration provenance | Embedded definitions, explicit overrides, saved effective definitions, and separate parameter identity |
| Experiment Management Service (EMS) client integration | External EMS reads, slot snapshots, deterministic variant assignment, periodic refresh, and algorithm loading |
| Experiment inspection | Read-only `hv-exp` queries for slots, experiments, ramp-up, algorithms, parameters, and online results |
| Python inference workers | Managed local subprocesses over Unix domain sockets |
| Runtime-local filesystem state | Private-directory allocation for explicitly configured containing runtimes; algorithm factories own cleanup |
| Remote offline work | Whole train/backtest/one-shot jobs submitted to SageMaker |

## Bounded or local surfaces

- `hv serve` and its UI are local full-algorithm debugging tools, not production hosting.
- `hv worker serve` is a local worker-only HTTP debugger, not the direct-worker transport.
- Current `hv serve` modes do not configure the local-state root required by definitions with
  `requires_local_state_storage: true`.
- Metadata-selection clients do not turn Hotvect into a production hosting service; the containing application owns
  selection and traffic behavior.
- The EMS control-plane server and its persistence/API implementation are external today. `hv-exp` intentionally
  exposes inspection, not experiment mutations.
- Host-provided external bindings can wrap clients, but Hotvect does not supply a generic remote proxy.
- A host binding wins in the composite factory map only after the declared child has been loaded.
- Nested composite children currently receive no parameter streams from their own packaged parameter namespace.
- `AlgorithmRepository` requires parameter metadata; use direct factory loading for a genuinely parameterless runtime.

## Direction

- Preserve one logical algorithm graph across a broader set of explicit local and remote bindings.
- Make execution topology, batching, failure, and resource semantics part of versioned integration contracts.
- Support reproducible, inspectable algorithm iteration through stable lifecycle interfaces.
- Bring the EMS control-plane server into Hotvect so configuration, releases, experiments, assignment, and inspection
  form one coherent product surface.
- Build governed research and validation workflows on top of the runtime without redefining the core algorithm model.

## Not the product boundary

Hotvect's intended experimentation scope is configuration, released-runtime selection, controlled assignment, and
traceability. It is not intended to become a general scheduler, feature store, service mesh, traffic proxy, or
monitoring product. Those systems remain surrounding infrastructure or explicit algorithm dependencies.

## Continue from the boundary you care about

- [Architecture overview](../index.md) places these capabilities in the build, offline, and application lifecycle.
- [Runtime topologies](../runtime-topologies/index.md) distinguishes current in-process, worker, and remote-job paths.
- [Dependencies and bindings](../../concepts/dependencies-and-bindings/index.md) explains the logical graph and its
  current loading limits.
- [Configuration and experimentation](../../concepts/configuration-and-experimentation/index.md) connects definitions,
  parameters, EMS selection, and the planned control-plane direction.
