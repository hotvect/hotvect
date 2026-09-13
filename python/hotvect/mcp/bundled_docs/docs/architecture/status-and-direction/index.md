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
| Host-provided dependencies | Named `Algorithm` implementations supplied and owned by the containing application |
| Offline lifecycle | State, encode, train, predict, evaluate, audit, performance test, and backtest workflows |
| Online application integration | Dynamic algorithm and optional parameter loading through the online utility module |
| Configuration provenance | Embedded definitions, explicit overrides, saved effective definitions, and separate parameter identity |
| Experiment Management Service (EMS) runtime integration | Configured EMS reads, slot snapshots, deterministic variant assignment, periodic refresh, and algorithm loading |
| EMS control-plane server | A standalone EMS repository provides the Spring Boot persistence, migrations, REST API, and deployment |
| Experiment inspection | Read-only `hv exp` queries for slots, experiments, ramp-up, algorithms, parameters, and online results |
| Python inference workers | Managed local subprocesses over Unix domain sockets |
| Runtime-local filesystem state | Lazy private-directory allocation; algorithm factories own cleanup |
| Remote offline work | Whole train/backtest/one-shot jobs submitted to SageMaker |

## Bounded or local surfaces

- `hv algorithm serve` and its UI are local full-algorithm debugging tools, not production hosting.
- `hv worker serve` is a local worker-only HTTP debugger, not the direct-worker transport.
- `hv algorithm serve` supplies lazy runtime-local state beneath the system temporary directory.
- Metadata-selection clients do not turn Hotvect into a production hosting service; the containing application owns
  selection and traffic behavior.
- The EMS server is maintained and deployed outside Hotvect. Hotvect documents the contract and provides compatible
  clients; the EMS deployment owns PostgreSQL, OAuth token introspection, artifact publication, and operations.
  `hv exp` intentionally exposes inspection, not experiment mutations.
- Host-provided external bindings can wrap clients, but Hotvect does not supply a generic remote proxy.
- A host binding replaces construction of the declared dependency by name; packaged definitions may still be inspected
  to discover reachable EMS slots.
- EMS metadata represents a parameterless algorithm by omitting both the parameter ID and parameter path.

## Direction

- Preserve one logical algorithm graph across a broader set of explicit local and remote bindings.
- Make execution topology, batching, failure, and resource semantics part of versioned integration contracts.
- Support reproducible, inspectable algorithm iteration through stable lifecycle interfaces.
- Keep EMS release identities, experiments, assignment, and inspection coherent across the standalone server and
  Hotvect clients without coupling their source repositories.
- Build governed research and validation workflows on top of the runtime without redefining the core algorithm model.

## Not the framework boundary

Hotvect's intended experimentation scope is configuration, released-runtime selection, controlled assignment, and
traceability. It is not intended to become a general scheduler, feature store, service mesh, traffic proxy, or
monitoring product. Those systems remain surrounding infrastructure or explicit algorithm dependencies.

## Continue from the boundary you care about

- [Architecture overview](../index.md) places these capabilities in the build, offline, and application lifecycle.
- [Runtime topologies](../runtime-topologies/index.md) distinguishes current in-process, worker, and remote-job paths.
- [Dependencies and bindings](../../concepts/dependencies-and-bindings/index.md) explains the logical graph and its
  current loading limits.
- [Configuration and experimentation](../../concepts/configuration-and-experimentation/index.md) connects definitions,
  parameters, EMS selection, and the server deployment boundary.
