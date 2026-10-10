---
title: Connect an online runtime to EMS
description: Embed HotvectServingRuntime in an application-owned Java service
tags: [serving, integration, ems, online, java]
difficulty: advanced
prerequisites:
  - A deployed EMS endpoint exposing slot state
  - Read access to the registered algorithm and parameter artifacts
  - Writable scratch storage in the serving application
related_docs:
  - ../../components/ems-runtime-client/index.md
  - ../../components/experiment-management-service/index.md
  - ../application-integration/index.md
  - ../../architecture/online-runtime/index.md
---

# Connect an online runtime to EMS

`HotvectServingRuntime` owns the EMS client, runtime-wide graph refresh, local variant assignment, algorithm downloads,
request-scoped invocation, and loaded-algorithm lifecycle. The application owns its request schema, touchpoint routing,
assignment-key extraction, response mapping, event publication, and shutdown lifecycle.

EMS is not called for each inference. One runtime owns one refresh scheduler. Startup and each background cycle read
every configured root slot, discover any named dependency slots from the candidate definitions, and then prepare the
complete reachable graph before activating it locally:

```text
GET /slots/{slot}/defaultVariantAndActiveExperiments
```

## Declare root slots

The application declares its globally named root slots, accepted algorithm contracts, and touchpoints directly on the
runtime. Each root slot carries one complete, fully resolved Guava `TypeToken`, such as
`new TypeToken<Ranker<PdpShared, Article>>() {}`. The runtime validates the algorithm interface and every nested type
argument as one contract. Startup or refresh rejects an EMS candidate whose loaded root does not satisfy it.
Root-slot names use the same lower-case EMS grammar as dependency slots (`[a-z0-9-]+`), and each must route at least
one non-blank application touchpoint.

## Own the runtime with the application lifecycle

Build one runtime at application startup and close it on shutdown:

```java
import com.google.common.reflect.TypeToken;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.featurestore.FeatureStore;
import com.hotvect.onlineutils.serving.HotvectServingRuntime;
import com.hotvect.onlineutils.serving.ServingSlot;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

HotvectServingRuntime runtime = HotvectServingRuntime.builder()
        .ems(URI.create("https://experiments.example.com"))
        .slot(ServingSlot.builder(
                        "pdp-ranking",
                        new TypeToken<Ranker<PdpShared, Article>>() {})
                .touchpoints(Set.of("pdp"))
                .build())
        .slot(ServingSlot.builder(
                        "catalog",
                        new TypeToken<Ranker<CatalogShared, Article>>() {})
                .touchpoints(Set.of("catalog"))
                .build())
        .scratchDirectory(Path.of("/var/run/application/hotvect-scratch"))
        .localStateRoot(Path.of("/var/lib/application/hotvect-state"))
        .refreshPeriod(Duration.ofMinutes(5))
        .tokenSupplier(tokenProvider::currentToken)
        .dependency("feature-store", FeatureStore.class, applicationFeatureStore)
        .build();
```

`build()` completes the initial slot reads and algorithm downloads before it returns. The optional named dependencies
are application-owned and remain open when the runtime closes. `dependency(...)` always receives the dependency's
contract: use a `Class` for a non-generic interface such as `FeatureStore`, or a concrete `TypeToken` when the
interface carries type arguments. `localStateRoot(...)` is optional; without it Hotvect uses
`scratchDirectory(...)/algorithm-state`. Scratch files remain under `scratchDirectory(...)`.

## Invoke and log

Invoke each configured application touchpoint locally with the request's stable assignment key:

```java
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.onlineutils.serving.AlgorithmExecution;
import com.hotvect.onlineutils.serving.AlgorithmSelection;

AlgorithmExecution<RankingResponse<Article>> execution = runtime.invoke(
        "pdp",
        assignmentKey,
        request);

RankingResponse<Article> response = execution.result();
AlgorithmSelection selection = execution.selection();
```

The runtime selects and invokes the algorithm while keeping its immutable generation reachable for the complete call.
The algorithm is never returned as a caller-owned handle. The application publishes its own event schema;
`AlgorithmSelection` supplies the root slot, variant assignments, and algorithm identity.
Invocation uses immutable in-memory snapshots, so this path does not call EMS or artifact storage.

The general `TopKRequest` overload returns `TopKResponse` for both `TopK` and `ThemedTopK` touchpoints. Applications can
pattern-match that response, or call `invokeThemedTopK(...)` for a statically typed `ThemedTopKResponse`; the latter
rejects a plain `TopK` touchpoint.

## Monitor the active generation

A refresh that fails, or that returns a candidate violating a declared slot contract, is rejected and the previous
complete generation keeps serving. This is deliberate: a bad EMS state never partially replaces the live graph. The
cost is that the runtime can serve a frozen generation indefinitely while every request still succeeds.

```java
ServingRuntimeStatus status = runtime.status();

Duration activationAge = Duration.between(status.activatedAt(), Instant.now());
ServingRefreshFailure failure = status.lastFailure();
```

`status.generation()` and `status.activatedAt()` identify the locally active graph; `activationAge` is the signal to
alert on. `lastFailure()` reports the latest rejected complete candidate without exposing classloader, artifact, or
graph internals. Call `runtime.refreshNow()` only when an operator explicitly needs a complete refresh; it never
refreshes one slot in isolation.

The local activation boundary does not make EMS reads transactional: the runtime reads EMS slots independently while
constructing the candidate, then either publishes that entire candidate in this JVM or retains the previous one.

## Verification checklist

- every public touchpoint maps to exactly one declared slot;
- each slot declares the complete algorithm invocation type;
- the initial build loads default and active experiment artifacts before readiness;
- invocation for a fixed assignment key is stable and performs no control-plane call;
- runtime generation and activation age are monitored and alert before a frozen generation reaches production impact;
- the application event includes the selected identities;
- shutdown closes the runtime before application-owned dependencies close.
