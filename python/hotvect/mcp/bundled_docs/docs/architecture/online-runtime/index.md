---
title: Online runtime integration
description: Embed Hotvect in a serving application to resolve, load, bind, cache, select, and execute algorithms
tags: [components, architecture, online, runtime, embedding, loading]
---

# Online runtime integration

Online runtime integration is a set of Java libraries embedded by a containing application. In production,
the containing service owns HTTP or event handling,
authentication, traffic management, and observability; Hotvect supplies the loaded decision algorithm executed inside
that service.

The `hv algorithm serve` command is a local debugging surface, not the production hosting model.

## Resolution and loading flow

<div class="hv-flow">
  <div class="hv-flow__step"><span>01</span><strong>Resolve</strong><small>algorithm · optional parameters</small></div>
  <div class="hv-flow__step"><span>02</span><strong>Download</strong><small>JAR · optional parameter ZIP</small></div>
  <div class="hv-flow__step"><span>03</span><strong>Bind</strong><small>children · host objects</small></div>
  <div class="hv-flow__step"><span>04</span><strong>Create</strong><small>owned AlgorithmGraph</small></div>
  <div class="hv-flow__step"><span>05</span><strong>Execute</strong><small>inside the application</small></div>
</div>

`AlgorithmDownloader` obtains the algorithm JAR and, when selected metadata contains both a parameter ID and path, its
parameter ZIP. Parameter metadata must contain both values or neither. A parameterless algorithm is loaded without a
ZIP and receives `NA` as the parameter component of its runtime ID. The downloader creates an
`AlgorithmInstanceFactory` with `REALTIME` workload mode and `ONLINE` input semantics. The factory reads the embedded
definition, resolves children, loads parameter streams, and constructs an owned `AlgorithmGraph`. Its root
`AlgorithmInstance` is only the constructed algorithm value and declared type contract; graph topology, recursive
identity, classloaders, and lifecycle remain on the graph owner. This repository flow
does not accept an outer definition override. Live EMS serving also rejects `hyperparameter_version` anywhere in the
resolved graph: commit an accepted offline configuration, publish it under a new algorithm version, and build its
parameters when it has any. A caller using `AlgorithmInstanceFactory` directly can instead supply an already effective
definition, including explicit child patches, for offline work.

Snapshot preparation indexes the definitions packaged by every selected EMS artifact. Private dependencies remain in
their immediate parent's artifact and classloader. A shared dependency names an exact `AlgorithmId`; all referring
artifacts resolve its code to one canonical provider artifact and classloader selected deterministically by artifact
source. That code provider wins even if another artifact packages different hyperparameters for the same
`name@version`.

Parameter selection is independent of code selection. Every refresh compares the nested `algorithm-parameters.json`
for each current artifact that packages the shared `name@version`. The candidate with the latest `last_test_time` wins;
`ran_at` breaks ties between reruns for the same logical data date. A parameter-bearing shared namespace must contain
`last_test_time` because the runtime cannot otherwise compare its data recency.

Parsed metadata is cached by immutable root parameter identity, so an unchanged refresh does not download every large
candidate ZIP again. Before inspecting a complete refresh, the repository evicts metadata for parameter archives that
are absent from that refresh; old serving generations do not need it. A later rollback downloads and inspects the old
ZIP once again. The selected ZIP is staged when a new shared instance must be constructed. The new serving generation
constructs one shared instance from the canonical code provider and those selected parameters. Calling roots'
parameter IDs do not create separate instances. When the selected shared parameter revision changes, the new
generation receives a new shared instance; the previous generation retains its old instance until in-flight work
releases it. A shared parent's revision includes its transitive shared children, so changing a child rebuilds the
parent for the new generation as well. Slot-backed dependencies below a shared node are rejected because their
root-specific selection is incompatible with the shared node's global `name@version` identity. Different algorithm
versions may coexist normally.

The runtime always supplies a lazy private-directory allocator to algorithm factories. It stages parameter archives
and allocates runtime state below `scratch/algorithm-state` by default; applications may set `localStateRoot(...)` to
place that state on another volume. Allocated state belongs to the factory or algorithm and must be removed after
failed construction or from `Algorithm.close()`.

## Runtime-owned graph reuse

`HotvectServingRuntime` owns artifact and graph reuse internally:

- root graphs are keyed by parameterized algorithm identity, recursive dependency graph, canonical shared code
  providers, and the shared parameter revision selected by the refresh;
- one shared `name@version` resolves to one instance per selected parameter revision, so every composition in one
  generation observes the same state while generation handover remains safe;
- a refresh reuses unchanged nodes instead of reconstructing them;
- active generations and in-flight invocations retain leases on their graphs;
- retired generations close asynchronously after their last invocation lease is released; graphs and artifacts close when their
  own last leases are released, and their registry entries are removed before cleanup starts;
- shared-provider catalogs own preparation leases and staged parameter archives; per-graph selections borrow
  from that scope, while constructed graphs retain the providers they need;
- runtime shutdown waits for invocation leases and cleanup of all current and retired generations;
- no graph handle or ownership token is exposed to the application.

Snapshot preparation is serialized in the background. One repository lifecycle lock protects resource
registries, reference counts, lease release, and metadata bookkeeping. Downloads, graph construction,
and algorithm/resource closure run outside that lock. Final release removes an owner from its registry
before closing it, so a replacement can be prepared even while the old owner's cleanup is blocked.

Prediction acquires an atomic lease on the current immutable generation and invokes its already prepared
algorithm. It never acquires the repository lifecycle lock, including when releasing the last invocation
of a retired generation: that release schedules cleanup on a separate thread. New generations reuse
unchanged graphs and shared resources; their leases keep those resources alive as older generations retire.

The runtime's concurrency does not establish that every user algorithm or backend is thread-safe. Algorithm
implementations must satisfy the concurrency contract of the containing application and selected backend.

## Application-provided dependencies

The application may register named algorithm implementations on `HotvectServingRuntime.Builder`. These bindings can
represent application-owned objects, feature-store adapters, or clients for external systems.

When a binding matches a declared dependency name, Hotvect substitutes it during dependency resolution and neither
requires nor inspects a packaged definition for that child. The application remains responsible for the binding's
complete implementation contract, including any transport, authentication, retry, batching, failure behavior, and
resource lifecycle.

These registrations are runtime-global. Hotvect presents the complete registration set while resolving every root and
canonical shared graph, and each graph consumes only names it declares. An unrelated registration is not an error and
does not become part of a shared node's identity.

An owned `AlgorithmGraph` closes every algorithm node that Hotvect constructed, in dependency-safe order. The online
runtime keeps that graph ownership internal and releases retired graphs asynchronously. Application-provided
bindings are not part of the graph's owned nodes and remain application-owned.

See [Dependencies and bindings](../../concepts/dependencies-and-bindings/index.md).

## Experiment selection

The online utility module includes an Experiment Management Service (EMS) read client, slot and experiment state types,
deterministic variant assignment, periodic state refresh, and conversion of selected metadata into loaded algorithm instances. For each
configured slot, startup fetches the default variant and active experiments before serving. Later refreshes resolve all
referenced algorithms and atomically replace the immutable serving snapshot.

The containing application chooses the slot and assignment key and owns how refresh or selection failures affect
traffic. Hotvect applies forced assignments first, then the configured shard, experiment, variant-allocation, and
ramp-up rules. The result selects an exact algorithm identity and, for parameterized algorithms, a parameter identity;
it does not change the loading contract described above.

The configured EMS endpoint is deployed from the standalone EMS repository. A serving application depends on
`hotvect-online-util`, not the server. `hv exp` is the read-only inspection CLI; the containing application exercises
the client path in its own integration tests and deployments.

Read [EMS runtime client, refresh, and assignment](../../components/ems-runtime-client/index.md) for this component,
[EMS control plane](../../components/experiment-management-service/index.md) for the server boundary, or
[Connect an online runtime to EMS](../../guides/connect-online-runtime-to-ems/index.md) for implementation.

## Local debugging

Use `hv algorithm serve` to load the complete algorithm locally and expose `GET /health`,
`GET /actuator/health`, `GET /api/metadata`, `POST /predict`, and optional browser UI routes. It intentionally uses a
batch/offline execution context and an offline example decoder. It does not configure runtime-local state storage.

Continue with [Local algorithm server and debugger](../../components/local-algorithm-server/index.md) for the component
boundary or [Serve and integrate](../../guides/serve-and-integrate/index.md) for the available workflows.
