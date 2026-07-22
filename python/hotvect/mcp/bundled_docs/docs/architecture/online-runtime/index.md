---
title: Online runtime and application embedding
description: How a containing application resolves, loads, binds, caches, executes, and closes Hotvect algorithm instances
tags: [architecture, online, runtime, embedding, loading]
---

# Online runtime and application embedding

Production use of Hotvect is an application integration. The containing service owns HTTP or event handling,
authentication, traffic management, and observability; Hotvect supplies the loaded decision algorithm executed inside
that service.

The `hv serve` command is a local debugging surface, not the production hosting model.

## Resolution and loading flow

<div class="hv-flow">
  <div class="hv-flow__step"><span>01</span><strong>Resolve</strong><small>algorithm · parameters</small></div>
  <div class="hv-flow__step"><span>02</span><strong>Download</strong><small>algorithm · parameter packages</small></div>
  <div class="hv-flow__step"><span>03</span><strong>Bind</strong><small>children · host objects</small></div>
  <div class="hv-flow__step"><span>04</span><strong>Create</strong><small>AlgorithmInstance</small></div>
  <div class="hv-flow__step"><span>05</span><strong>Execute</strong><small>inside the application</small></div>
</div>

`AlgorithmDownloader` obtains the algorithm and parameter packages described by the selected metadata. Their current
physical formats are a JAR and ZIP. This repository-based path requires a nonempty parameter ID. It creates an
`AlgorithmInstanceFactory` with `REALTIME` workload mode and `ONLINE` input semantics. The factory reads the embedded
definition, resolves children, loads parameter streams, and constructs the public algorithm. This repository flow
does not accept an outer definition override. A caller using `AlgorithmInstanceFactory` directly can instead supply an
already effective definition, including explicit child patches.

Definitions with `requires_local_state_storage: true` also require an explicit runtime capability. A direct loader
receives an optional local-state root. The repository downloader receives separate scratch and local-state roots,
stages the selected parameter archive below the latter, and supplies a private directory allocator to the factory. It
fails when such a definition is selected without that root. Allocated state belongs to the factory or algorithm and
must be removed after failed construction or from `Algorithm.close()`.

## Algorithm repository

`AlgorithmRepository` is the application-facing cache for factories and instances:

- factories are retained by algorithm ID;
- instances are keyed by algorithm ID and parameter ID;
- instances use weak references so an unused parameter version can be collected;
- algorithm resources are closed after the instance becomes unreachable;
- the repository is designed for concurrent access and should normally be shared once per application.

The repository's concurrency does not establish that every user algorithm or backend is thread-safe. Algorithm
implementations must satisfy the concurrency contract of the containing application and selected backend.

## Application-provided dependencies

The application may provide named `AlgorithmInstance` overrides when constructing the repository. These bindings can
represent application-owned objects, feature-store adapters, or clients for external systems.

Hotvect recursively loads declared children, overlays the binding by dependency name, and supplies the resulting map to
the composite factory. The binding wins at the factory boundary, but it does not avoid loading the declared child. The
application remains responsible for network transport, authentication, retry, batching, failure behavior, and the
binding's resource lifecycle.

`AlgorithmInstance.close()` closes its contained outer algorithm. Hotvect does not generically close recursively loaded
children or displaced override values; the factory, algorithm implementation, and host must make ownership explicit.

See [Dependencies and bindings](../../concepts/dependencies-and-bindings/index.md).

## Experiment selection

The online utility module includes an Experiment Management Service (EMS) read client, slot and experiment state types, deterministic variant
assignment, periodic state refresh, and conversion of selected metadata into loaded algorithm instances. For each
configured slot, startup fetches the default variant and active experiments before serving. Later refreshes resolve all
referenced algorithms and atomically replace the immutable serving snapshot.

The containing application chooses the slot and assignment key and owns how refresh or selection failures affect
traffic. Hotvect applies forced assignments first, then the configured shard, experiment, variant-allocation, and
ramp-up rules. The result selects an exact algorithm and parameter identity; it does not change the loading contract
described above.

The EMS server remains external to Hotvect in the current release. `hv-exp` is the read-only inspection CLI, while
`hv serve --ems-url ... --ems-slot ...` exercises the client path locally. Read
[Configuration and experimentation](../../concepts/configuration-and-experimentation/index.md) for the complete model
and current product boundary.

## Local debugging

Use `hv serve` to load the complete algorithm locally and expose `GET /health`, `GET /api/health`, `GET /api/metadata`,
`GET /api/config`, `POST /predict`, and optional browser UI routes. Local artifact mode intentionally uses a batch/offline
execution context and an offline example decoder. EMS mode uses the repository's realtime/online context, but the HTTP
surface remains a local debugger. Neither mode currently configures runtime-local state storage.

Continue with [Serve and integrate](../../guides/serve-and-integrate/index.md) for the available debugging surfaces.
