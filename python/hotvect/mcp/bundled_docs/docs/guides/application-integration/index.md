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

This guide shows the two current loading paths:

| Path | Use it when | What it adds |
| --- | --- | --- |
| `AlgorithmInstanceFactory` | The application already has a local JAR and optional parameter ZIP | Definition loading, child resolution, parameter streams, object construction |
| `AlgorithmRepository` | The application selects versioned JAR and parameter metadata from artifact storage | Download, factory reuse, live-instance reuse, and cleanup registration |

Both paths ultimately create an `AlgorithmInstance`. The example calls a `Ranker`; use the same pattern with the
public shape declared by your algorithm.

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

## Path 1: load local artifacts directly

Use `AlgorithmInstanceFactory` when artifact selection and download happen elsewhere. Create the instance once, reuse
it for decisions, and close it with the containing component:

```java
package org.example.application;

import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.onlineutils.hotdeploy.AlgorithmInstanceFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.example.contract.Document;
import org.example.contract.QueryContext;

public final class DocumentRankingComponent implements AutoCloseable {
    private final AlgorithmInstance<Ranker<QueryContext, Document>> instance;

    public DocumentRankingComponent(Path algorithmJar, Path parameterZip) {
        var factory = new AlgorithmInstanceFactory(
                algorithmJar.toFile(),
                DocumentRankingComponent.class.getClassLoader(),
                ExecutionContext.realtime(InputSemantic.ONLINE),
                true);

        this.instance = factory.load(
                "example-document-ranker",
                parameterZip.toFile(),
                Map.of());
    }

    public RankingResponse<Document> rank(
            String requestId,
            QueryContext context,
            List<AvailableAction<Document>> candidates) {
        var request = RankingRequest.ofAvailableActions(requestId, context, candidates);
        return instance.algorithm().rank(request);
    }

    @Override
    public void close() throws Exception {
        instance.close();
    }
}
```

The constructor arguments have specific meanings:

- `ExecutionContext.realtime(InputSemantic.ONLINE)` tells factories that this is a latency-sensitive call over online
  input. It does not make an implementation thread-safe or impose a timeout.
- `true` enables strict algorithm-version checking between the embedded definition and parameter metadata.
- `Map.of()` means there are no application-provided dependency bindings. A composite host passes bindings by the
  dependency names in its definition.

For a genuinely parameterless algorithm, the direct `load` method accepts `null` instead of a parameter file. Do this
only when the selected factories do not require parameter streams:

```java
this.instance = factory.load("example-document-ranker", null, Map.of());
```

### Supply runtime-local storage when required

If the definition declares `requires_local_state_storage: true`, use the constructor that supplies a local-state root:

```java
var factory = new AlgorithmInstanceFactory(
        algorithmJar.toFile(),
        DocumentRankingComponent.class.getClassLoader(),
        ExecutionContext.realtime(InputSemantic.ONLINE),
        true,
        java.util.Optional.of(localStateRoot));
```

This makes a storage allocator available to constructed factories. A factory should allocate only when it needs
runtime-local files and must reject `Optional.empty()` when its definition says the capability is required.

The code calls `rank` directly. The containing application is responsible for translating its transport input into
`QueryContext` and `AvailableAction<Document>` values, then translating `RankingResponse` into its own output.
Offline example decoders are not part of this request path.

### Direct-path lifecycle

Do not load a new JAR and construct a new algorithm for every request. Keep the `AlgorithmInstance` for the intended
application lifetime or rollout lifetime, subject to the algorithm backend's concurrency contract. Closing the
instance calls `close()` on its contained outer algorithm. Child and application-provided resource ownership must be
defined by the composite implementation and host; closing the outer instance does not generically traverse every
dependency.

## Path 2: resolve versioned artifacts with `AlgorithmRepository`

`AlgorithmRepository` is useful when the application receives immutable algorithm metadata and needs to download and
reuse the corresponding artifacts. The repository retains factories by algorithm ID and keeps weak references to
instances by algorithm ID plus parameter ID.

The online utility module includes an S3 download client. Inject an application-managed `S3AsyncClient`, keep it alive
for as long as the repository may load new artifacts, and provide a writable scratch directory:

```java
package org.example.application;

import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.onlineutils.experimentmanagement.algodownload.AlgorithmDownloader;
import com.hotvect.onlineutils.experimentmanagement.algodownload.AlgorithmRepository;
import com.hotvect.onlineutils.experimentmanagement.algodownload.S3AlgorithmDownloadClient;
import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import java.nio.file.Path;
import org.example.contract.Document;
import org.example.contract.QueryContext;
import software.amazon.awssdk.services.s3.S3AsyncClient;

public final class VersionedAlgorithms {
    private final AlgorithmRepository repository;

    public VersionedAlgorithms(
            S3AsyncClient s3Client,
            Path scratchDirectory,
            Path localStateRoot) {
        var downloads = new S3AlgorithmDownloadClient(s3Client);
        var downloader = new AlgorithmDownloader(
                downloads,
                scratchDirectory,
                java.util.Optional.of(localStateRoot),
                VersionedAlgorithms.class.getClassLoader(),
                true);
        this.repository = new AlgorithmRepository(downloader);
    }

    @SuppressWarnings("unchecked")
    public AlgorithmInstance<Ranker<QueryContext, Document>> loadDocumentRanker(
            AlgorithmMetadata metadata) {
        return (AlgorithmInstance<Ranker<QueryContext, Document>>)
                repository.getAlgorithmInstance(metadata);
    }
}
```

The selection layer supplies exact artifact metadata:

```java
var metadata = new AlgorithmMetadata(
        "example-document-ranker",
        "1.0.0",
        "parameters-001",
        "s3://example-bucket-artifacts/algorithms/example-document-ranker-1.0.0.jar",
        "s3://example-bucket-artifacts/parameters/example-document-ranker-parameters-001.zip");

AlgorithmInstance<Ranker<QueryContext, Document>> loaded =
        algorithms.loadDocumentRanker(metadata);
Ranker<QueryContext, Document> ranker = loaded.algorithm();
```

The URIs above are illustrative. For another artifact store, implement the two methods on `AlgorithmDownloadClient`
instead of using `S3AlgorithmDownloadClient`.

Keep `scratchDirectory` and `localStateRoot` conceptually separate. Scratch holds transient artifact downloads for
ordinary algorithms. For a definition requiring local state, the repository stages its parameter download under the
local-state root and passes a private state allocator to its factory. If that definition is selected without a
configured local-state root, repository loading fails before construction rather than silently using scratch storage.
The factory or constructed algorithm owns each allocated private state directory and must delete it on failure or
close; the repository owns cleanup timing for live algorithm instances.

The unchecked cast is where application-owned selection metadata meets the Java generic contract. Hotvect validates
the algorithm and parameter identities, but erased generic arguments cannot prove that a selected artifact uses the
application's expected `QueryContext` and `Document` types. Treat the algorithm name and version as part of that typed
integration contract and test the exact artifact in the containing application.

### Repository lifecycle and identity

Create one repository per application and share it across request threads. Keep a strong reference to the returned
`AlgorithmInstance` while its algorithm is in use; retaining only `instance.algorithm()` does not keep the wrapper
alive for repository cleanup. Do not close a repository-returned instance per request: the repository may return that
live instance again. When the wrapper becomes unreachable, repository cleanup closes its outer algorithm.

The current repository path requires a nonempty parameter ID and downloads a parameter ZIP. Use the direct path for an
algorithm that truly has no parameter artifact. Treat algorithm IDs and parameter IDs as immutable: publishing
different bytes under an existing ID can leave a cached factory or instance serving the earlier artifact.

`S3AlgorithmDownloadClient` does not close an `S3AsyncClient` supplied to its constructor. The application that created
that client closes it during application shutdown.

## Optional path 3: select a runtime through EMS

Use the Experiment Management Service (EMS) client only when the application needs slot-based variant assignment.
Direct loading and `AlgorithmRepository` do not require EMS. The current Java integration reads an external EMS; it
does not publish artifacts, create variants, or mutate experiments.

Build the repository as above, then create one client and manager for the application's configured slots:

```java
import com.hotvect.onlineutils.experimentmanagement.experimentation.DefaultExperimentationManager;
import com.hotvect.onlineutils.experimentmanagement.httpclient.ExperimentManagementServiceClient;
import com.hotvect.onlineutils.experimentmanagement.models.VariantConfiguration;
import java.net.URI;
import java.time.Duration;
import java.util.Set;

var emsClient = new ExperimentManagementServiceClient(
        URI.create("https://experiments.example.com"),
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        () -> System.getenv("EMS_TOKEN"));

var experimentation = new DefaultExperimentationManager(
        repository,
        Duration.ofSeconds(30),
        emsClient,
        Set.of("example-slot"));

experimentation.startAsync().awaitRunning();
```

Startup performs the first read and resolves all algorithm packages referenced by the current snapshot before the
manager reaches `RUNNING`. For each application request, supply the stable domain identifier chosen by the containing
application as the assignment key:

```java
VariantConfiguration selected =
        experimentation.assignVariant("example-slot", assignmentKey);

int variantId = selected.variant().variantId();
AlgorithmInstance<?> instance = selected.algorithmInstance();
```

The assignment uses the latest immutable in-memory snapshot; EMS and artifact storage are not called in this request
path. Background refresh replaces a slot's snapshot after it has read and resolved the complete new state. Keep a
strong reference to the selected `AlgorithmInstance` for as long as its algorithm is in use.

On application shutdown, stop the manager first, then close `ExperimentManagementServiceClient`, the download client,
and the application-owned `S3AsyncClient`. The manager owns its per-slot refreshers, but it does not own those clients.
Choose and document application behavior for initial-read and refresh failures; Hotvect does not define the host's
traffic fallback policy.

## Bind application-owned dependencies

Both loaders can pass named `AlgorithmInstance` values to a composite factory. In the direct path they are the third
argument to `load`; in the repository path they are supplied to an `AlgorithmRepository` constructor.

A binding can wrap an application service client behind the algorithm interface expected by the parent. The
application still owns its network protocol, credentials, latency controls, failure behavior, and shutdown. Current
loading constructs the declared child before overlaying a binding with the same name, so a binding should not be
described as avoiding declared-child loading. Read [Dependencies and bindings](../../concepts/dependencies-and-bindings/index.md)
before using this path.

Wrap an application-owned implementation with a synthetic identity, then bind it under the exact dependency name:

```java
ExternalCandidateScorer client = new ExternalCandidateScorer(httpClient);
AlgorithmInstance<ExternalCandidateScorer> binding =
        AlgorithmInstance.externalAlgorithm("candidate-scorer", client);

AlgorithmInstance<?> parent = factory.load(
        "example-document-ranker",
        parameterZip.toFile(),
        Map.of("candidate-scorer", binding));
```

Here `ExternalCandidateScorer` is application code that implements the algorithm interface the parent expects. Its
transport and lifecycle remain application-owned. The parent definition must still declare `candidate-scorer`; current
loading constructs that declared child before replacing the value passed to the parent factory.

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

Use [`hv serve`](../local-algorithm-debugging/index.md) to inspect an artifact locally, but keep that check separate
from the application integration test: local artifact mode uses a batch/offline execution context and the offline
decoder, while EMS mode uses the online repository context. Neither current server mode configures runtime-local state
storage.
