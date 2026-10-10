---
title: Embed Hotvect in a Java application
description: Load a Hotvect algorithm directly or through the versioned repository and call its typed decision API
tags: [serving, integration, java, online, runtime]
difficulty: intermediate
prerequisites:
  - A built algorithm JAR with an embedded algorithm definition
  - A matching parameter ZIP, unless using the direct path with a parameterless algorithm
  - The algorithm's public request and response types
related_docs:
  - ../../architecture/online-runtime/index.md
  - ../../concepts/jar-loading/index.md
  - ../../concepts/dependencies-and-bindings/index.md
  - ../../concepts/artifacts-and-identity/index.md
---

# Embed Hotvect in a Java application

A containing application loads a Hotvect algorithm as a Java object and calls its public decision interface. Hotvect
does not create the application's HTTP endpoint, event consumer, authentication, or traffic policy.

This guide shows the two supported integration paths:

| Path | Use it when | What it adds |
| --- | --- | --- |
| `AlgorithmInstanceFactory` | The application already has a local JAR and optional parameter ZIP | Definition loading, child resolution, parameter streams, and an explicitly owned graph |
| `HotvectServingRuntime` | EMS selects versioned artifacts and variants | Atomic refresh, graph reuse, assignment, invocation, and deterministic cleanup |

The direct path exposes an explicitly owned graph. The EMS path keeps graph ownership internal and exposes only
synchronous invocation. The examples call a `Ranker`; use the same pattern with the public shape declared by your
algorithm.

## Before you load anything

You need a built algorithm JAR, its parameter ZIP when it has one, and the Java types at its public request/response
boundary. The examples below use `QueryContext` and `Document` from a small `example-document-contracts` artifact
shared by the algorithm and application.

If you came from [Build your first algorithm](../first-algorithm/index.md), its records currently live inside the
single tutorial project. Before embedding that algorithm:

1. move the two records into a small JAR under `org.example.contract`;
2. make both projects depend on exactly that artifact version;
3. include the contracts JAR in the algorithm's shaded runtime JAR and on the application classpath;
4. update the algorithm imports and rebuild its JAR.

Because ordinary loading is parent-first, the containing application's contract classes become the shared boundary.
Do not create unrelated application classes that merely have the same names as classes private to an algorithm JAR.

The application runtime needs `hotvect-api` and `hotvect-online-util` at the same Hotvect version used to build the
algorithm. Hotvect deliberately marks SLF4J, Guava, and Jackson as runtime-provided, so the application must also own
those dependencies:

```xml
<dependency>
  <groupId>com.hotvect</groupId>
  <artifactId>hotvect-api</artifactId>
  <version>${hotvect.version}</version>
</dependency>
<dependency>
  <groupId>com.hotvect</groupId>
  <artifactId>hotvect-online-util</artifactId>
  <version>${hotvect.version}</version>
</dependency>
<dependency>
  <groupId>org.slf4j</groupId>
  <artifactId>slf4j-api</artifactId>
  <version>2.0.17</version>
</dependency>
<dependency>
  <groupId>com.google.guava</groupId>
  <artifactId>guava</artifactId>
  <version>33.5.0-jre</version>
</dependency>
<dependency>
  <groupId>com.fasterxml.jackson.core</groupId>
  <artifactId>jackson-core</artifactId>
  <version>2.21.1</version>
</dependency>
<dependency>
  <groupId>com.fasterxml.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
  <version>2.21.1</version>
</dependency>
<dependency>
  <groupId>org.example</groupId>
  <artifactId>example-document-contracts</artifactId>
  <version>1.0.0</version>
</dependency>
```

Add the application's chosen SLF4J implementation as its logging backend. Keep `hotvect-api` runtime-owned. The
algorithm project normally declares it with `provided` scope and packages its own implementation modules and selected
backends. See [Algorithm JAR loading](../../concepts/jar-loading/index.md) for the complete dependency boundary.

## Load local artifacts directly

Use `AlgorithmInstanceFactory` when artifact selection and download happen elsewhere. Create one graph, reuse it for
decisions, and close both the graph and its file-owning factory with the containing component:

```java
package org.example.application;

import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.onlineutils.hotdeploy.AlgorithmInstanceFactory;
import com.hotvect.onlineutils.hotdeploy.AlgorithmGraph;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.example.contract.Document;
import org.example.contract.QueryContext;

public final class DocumentRankingComponent implements AutoCloseable {
    private final AlgorithmInstanceFactory factory;
    private final AlgorithmGraph<Ranker<QueryContext, Document>> graph;

    public DocumentRankingComponent(Path algorithmJar, Path parameterZip) {
        this.factory = new AlgorithmInstanceFactory(
                algorithmJar.toFile(),
                DocumentRankingComponent.class.getClassLoader(),
                new AlgorithmInstanceFactory.Options(
                        ExecutionContext.realtime(InputSemantic.ONLINE),
                        true,
                        false,
                        Optional.empty()));

        var definition = factory.readAlgorithmDefinition("example-document-ranker");
        this.graph = factory.loadGraph(
                definition,
                parameterZip.toFile(),
                AlgorithmDependencies.empty());
    }

    public RankingResponse<Document> rank(
            String requestId,
            QueryContext context,
        List<AvailableAction<Document>> candidates) {
        var request = RankingRequest.ofAvailableActions(requestId, context, candidates);
        return graph.algorithm().rank(request);
    }

    @Override
    public void close() throws Exception {
        try {
            graph.close();
        } finally {
            factory.close();
        }
    }
}
```

The constructor arguments have specific meanings:

- The options' `ExecutionContext.realtime(InputSemantic.ONLINE)` tells factories that this is a latency-sensitive call over online
  input. It does not make an implementation thread-safe or impose a timeout.
- `strictAlgorithmVersionCheck = true` checks the embedded definition against parameter metadata.
- `enableFeatureLogging = false` leaves feature logging disabled, and `Optional.empty()` supplies no local-state root.
- `AlgorithmDependencies.empty()` means there are no application-provided dependency bindings. A composite host passes
  bindings by the dependency names in its definition. EMS slot bindings are runtime infrastructure and are not a public
  argument of direct loading.

For a genuinely parameterless algorithm, the direct `loadGraph` method accepts `null` instead of a parameter file. Do this
only when the selected factories do not require parameter streams:

```java
this.graph = factory.loadGraph(
        factory.readAlgorithmDefinition("example-document-ranker"),
        null,
        AlgorithmDependencies.empty());
```

### Set an application state root

Hotvect always supplies a lazy `LocalStateStorage` allocator. Set an explicit root when application operations require
it to be on a particular volume:

```java
var factory = new AlgorithmInstanceFactory(
        algorithmJar.toFile(),
        DocumentRankingComponent.class.getClassLoader(),
        new AlgorithmInstanceFactory.Options(
                ExecutionContext.realtime(InputSemantic.ONLINE),
                true,
                false,
                Optional.of(localStateRoot)));
```

Factories should allocate a directory only when they need runtime-local files. Omitting the explicit root uses the
system temporary directory's `algorithm-state` directory.

The code calls `rank` directly. The containing application is responsible for translating its transport input into
`QueryContext` and `AvailableAction<Document>` values, then translating `RankingResponse` into its own output.
Offline example decoders are not part of this request path.

### Direct-path lifecycle

Do not load a new JAR and construct a new graph for every request. Keep the `AlgorithmGraph` for the intended
application lifetime or rollout lifetime, subject to the algorithm backend's concurrency contract. Closing the graph
closes constructed nodes from dependent to dependency, and then releases its artifact classloader lease.
Factories must return newly owned algorithm instances, not a dependency instance. To forward to a dependency,
return a new wrapper that delegates invocation but does not close the dependency. The graph owns dependency cleanup;
application-provided bindings are borrowed and never closed by the graph.

## Invoke through EMS

Use `HotvectServingRuntime` when the application needs EMS-backed variant assignment and algorithm loading. Direct
loading does not require EMS. The runtime reads a separately deployed EMS server; it does not
publish artifacts, create variants, mutate experiments, or define the application's public routing.

This section shows the smallest API path. Use
[Connect an online runtime to EMS](../connect-online-runtime-to-ems/index.md) for dependency placement, application-owned
bindings, startup readiness, refresh health, verification, and shutdown.

Build one lifecycle-owned runtime for the application's deployment-approved, globally named EMS slots.

```java
import com.google.common.reflect.TypeToken;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.onlineutils.serving.AlgorithmExecution;
import com.hotvect.onlineutils.serving.AlgorithmSelection;
import com.hotvect.onlineutils.serving.HotvectServingRuntime;
import com.hotvect.onlineutils.serving.ServingSlot;
import java.net.URI;
import java.time.Duration;
import java.util.Set;

var runtime = HotvectServingRuntime.builder()
        .ems(URI.create("https://experiments.example.com"))
        .slot(ServingSlot.builder(
                        "catalog",
                        new TypeToken<Ranker<CatalogShared, Article>>() {})
                .touchpoints(Set.of("catalog"))
                .build())
        .slot(ServingSlot.builder(
                        "pdp",
                        new TypeToken<Ranker<PdpShared, Article>>() {})
                .touchpoints(Set.of("pdp"))
                .build())
        .refreshPeriod(Duration.ofSeconds(30))
        .tokenSupplier(() -> System.getenv("EMS_TOKEN"))
        .build();
```

`build()` performs the initial slot reads and resolves all referenced algorithm packages. Invoke a configured
application touchpoint directly:

```java
AlgorithmExecution<RankingResponse<Article>> execution = runtime.invoke(
        "catalog",
        assignmentKey,
        request);

RankingResponse<Article> response = execution.result();
AlgorithmSelection selection = execution.selection();
```

`invoke(...)` overloads on `RankingRequest` and `TopKRequest`. The `TopKRequest` overload serves both `TopK` and
`ThemedTopK` slots through their common `TopKResponse` type. Because a `TopKRequest` does not contain the output action
type, assign its `AlgorithmExecution<TopKResponse<ACTION>>` result to the intended action type explicitly.
Call `invokeThemedTopK(...)` when the touchpoint is known to serve `ThemedTopK` and the caller needs a statically typed
`ThemedTopKResponse`; it rejects a plain `TopK` slot. Callers using the general overload can instead pattern-match the
returned `TopKResponse`.

Assignment uses the latest immutable in-memory slot snapshots, so EMS and artifact storage are not called in this
request path. The runtime owns selection, invocation, and graph lifetime. The application owns typed decoding,
response mapping, and logging. Close the runtime during application shutdown.

## Bind application-owned dependencies

Both integration paths can pass named application-owned values to a composite factory. In the direct path they are the
third argument to `loadGraph`; in the EMS path register the implementation with `HotvectServingRuntime.Builder.dependency`.

A binding can wrap an application service client behind the algorithm interface expected by the parent. The
application still owns its network protocol, credentials, latency controls, failure behavior, and shutdown. A matching
host binding satisfies the declared edge directly, so the bound child artifact is not constructed. Read
[Dependencies and bindings](../../concepts/dependencies-and-bindings/index.md) before using this path.

Bindings registered on `HotvectServingRuntime.Builder` are available to every configured root and canonical shared
dependency. Each resolved graph uses only matching declared names, so one global registration may be irrelevant to
some roots without causing validation failure.

Wrap an application-owned implementation with a synthetic identity, then bind it under the exact dependency name:

```java
ExternalCandidateScorer client = new ExternalCandidateScorer(httpClient);
AlgorithmInstance<ExternalCandidateScorer> binding =
        AlgorithmInstance.externalAlgorithm(
                "candidate-scorer",
                ExternalCandidateScorer.class,
                client);

try (AlgorithmGraph<?> parent = factory.loadGraph(
        factory.readAlgorithmDefinition("example-document-ranker"),
        parameterZip.toFile(),
        new AlgorithmDependencies(Map.of("candidate-scorer", binding)))) {
    // Use parent.algorithm() while the graph remains open.
}
```

Here `ExternalCandidateScorer` is application code that implements the algorithm interface the parent expects. Its
transport and lifecycle remain application-owned. The parent definition must still declare `candidate-scorer`.
It does not need to package a `candidate-scorer` algorithm definition when the application supplies that binding.

## Establish the trust boundary

Loading an algorithm JAR executes its code with the containing JVM's file, network, process, and reflection access.
Classloader isolation is for class and namespace ownership, not security isolation.

The current downloader checks that downloaded files exist, and strict loading checks identity metadata. It does not
verify a cryptographic signature or content digest. The application must authorize artifact selection, use a trusted
publication path, and perform any required integrity verification before handing an artifact to Hotvect. Apply the
same trust policy to parameter ZIPs: algorithm code reads their contents during construction.

## Verify the integration

Before routing real traffic, test the containing application with the exact JAR, effective definition, parameter ZIP,
and host-provided dependencies intended for the rollout. Verify:

1. the loaded runtime identity;
2. request adaptation and stable action IDs;
3. response adaptation and failure mapping;
4. concurrency and resource behavior under the application's execution model;
5. parity against a bounded offline input where that claim matters.

Use [`hv algorithm serve`](../local-algorithm-debugging/index.md) to inspect an artifact locally, but keep that check separate
from the application integration test: the debugger uses a batch/offline execution context and the offline decoder.
The project-specific serving application uses the online repository context.
