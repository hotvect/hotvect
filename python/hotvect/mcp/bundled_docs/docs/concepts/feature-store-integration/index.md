---
title: Feature-store integration
description: Bind an application-owned feature store, represent partial failures, and preserve the same inputs offline and online
tags: [concepts, feature-store, integration, parity, dependencies]
related_docs:
  - ../feature-computation/index.md
  - ../dependencies-and-bindings/index.md
  - ../data-model/index.md
  - ../../guides/connect-online-runtime-to-ems/index.md
  - ../../guides/online-offline-parity/index.md
---

# Feature-store integration

Hotvect defines contracts for requesting and carrying feature-store values, but it does not provide a feature-store
service or network client. The containing application owns the real client, credentials, transport, batching, timeout,
and monitoring behavior. An algorithm consumes that capability through an explicit runtime binding.

## The three contracts

| Contract | Responsibility |
| --- | --- |
| `FeatureStore` | Asynchronous application capability that retrieves named features for one view and version |
| `FeatureStoreResponse` | Successfully returned entities plus an optional request-failure description |
| `FeatureStoreRetriever<SHARED, ACTION>` | Algorithm-side adapter that turns one `RankingRequest` into responses keyed by view name |

`FeatureStore.getFeatures(...)` receives:

- a list of entity IDs, where each ID is a map so composite keys are possible;
- the feature view name and integer version;
- the requested feature names.

It returns a `CompletableFuture<FeatureStoreResponse>`. The `FeatureStore` contract is unusual by design: an
implementation must not throw from the call or return a failed future. Infrastructure and partial-data failures are
represented in the completed `FeatureStoreResponse`.

That rule makes the algorithm's data policy explicit. It does not mean a caller should ignore failures.

## Success, failure, and partial success

Use `SimpleFeatureStoreResponse` to represent the three current outcomes:

```java
SimpleFeatureStoreResponse.success(entities);
SimpleFeatureStoreResponse.failure("feature view request timed out");
SimpleFeatureStoreResponse.partial(entities, "two entity lookups failed");
```

Read the response through both dimensions:

```java
FeatureStoreResponse response = featureStore
        .getFeatures(ids, "candidate-features", 3, "signal", "category")
        .join();

Map<Map<String, Object>, Map<String, Object>> available = response.getAllEntities();
Optional<String> requestFailure = response.getRequestFailure();
```

The combinations mean:

| Entities | Request failure | Meaning |
| --- | --- | --- |
| Present or empty | Empty | Request succeeded; an empty map can legitimately mean no entity matched |
| Empty | Present | Failure, or a partial response in which no entity succeeded |
| Present | Present | Partial success; use the returned entities only under an explicit algorithm policy |

`getEntity(id)` returns `null` when that entity is absent. `getEntityOrDefault(...)` is only a convenience lookup; it
does not distinguish a missing entity from a request failure. Check `getRequestFailure()` when that distinction
matters. Do not use the deprecated `isSuccess()` method for new code because it hides partial-success information.

## Bind the application capability

A feature-store client normally lives in the containing application, not the algorithm JAR. Wrap it in an
`AlgorithmInstance` and bind it under the dependency name declared by the algorithm:

```java
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.data.featurestore.FeatureStore;
import java.util.Map;

FeatureStore applicationFeatureStore = new ApplicationFeatureStore(client);

AlgorithmInstance<FeatureStore> binding = AlgorithmInstance.externalAlgorithm(
        "feature-store",
        FeatureStore.class,
        applicationFeatureStore);

AlgorithmDependencies bindings = new AlgorithmDependencies(
        Map.of("feature-store", binding));
```

Pass `bindings` as the application-dependency argument to direct `loadGraph(...)`, or register the same
`FeatureStore.class` contract through `HotvectServingRuntime.Builder.dependency(...)` for EMS loading. The composite
factory receives that named instance and supplies an algorithm-specific `FeatureStoreRetriever` to the generated
ranking transformer. The retriever owns request-to-entity-key mapping, view selection, feature selection, and the
policy for missing or failed values.

The binding name must match the definition and factory lookup exactly. The Hotvect loader does not inspect a
transformer and automatically discover or create a feature-store client.

The containing application retains lifecycle ownership of the bound client. It must keep the binding alive while
loaded algorithms can use it and close the client only after those algorithms and refreshers have stopped.

## Carry responses through a decision

`OfflineRankingRequest`, `BulkScoreResponse`, and `RankingResponse` can carry a
`FeatureStoreResponseContainer`. The container is keyed by view name and preserves the response objects associated
with the decision. It is data, not a live client.

Generated streaming transformers expose the responses obtained during preparation. A scorer can return them in its
response container, and a parent ranker should preserve that container when it converts scores into ranked decisions.
This is how an audit or focused prediction can retain evidence about the external values used for scoring.

For debugging only:

```bash
hv algorithm predict ... --include-feature-store-responses --samples 10 --ordered
```

The formatter writes the container below
`additional_properties.__feature_store_responses`. Feature-store values can be large or sensitive, so do not enable
that option for routine backtests or unrestricted logs.

## Preserve offline and online meaning

The online application may fetch fresh values, while recorded offline examples can carry previously obtained
responses in their `OfflineRankingRequest`. Sharing the Java request and transformer types does not by itself create
parity. Verify all of these explicitly:

1. entity-key construction is identical;
2. view name, view version, and feature names match;
3. missing-entity, timeout, and partial-failure policies match;
4. value types and defaults match;
5. the recorded data corresponds to the decision time being replayed.

An online retriever that silently substitutes defaults while the offline path contains complete historical values is
not a parity-preserving integration.

Continue with [Feature computation](../feature-computation/index.md) for generated transformer wiring or
[Online/offline parity](../../guides/online-offline-parity/index.md) for validation.
