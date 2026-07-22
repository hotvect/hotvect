---
title: Java API map
description: Curated entry points to Hotvect's public Java contracts and version-matched Javadocs
tags: [reference, java, api, factories, algorithms]
---

# Java API map

Hotvect's containing applications and algorithm JARs meet at `hotvect-api`. Keep that module runtime-owned and package
algorithm implementation modules in the algorithm JAR.

## Public modules

| Module | Use |
| --- | --- |
| `hotvect-api` | Stable host/algorithm contracts: shapes, data, factories, instances, execution context |
| `hotvect-core` | Algorithm-side feature transformation implementations and annotations |
| `hotvect-processor` | Compile-time generated transformer processor |
| `hotvect-catboost` | CatBoost transformer, encoding, training, and scoring integration |
| `hotvect-tensorflow` | TensorFlow generated transformer and runtime integration |
| Java `hotvect-python` | Managed Python worker runtime integration |
| `hotvect-online-util` | Dynamic online loading and algorithm repository |
| `hotvect-offline-util` | JVM offline tasks and command-line runner |

## Algorithm contracts

| Contract | Purpose |
| --- | --- |
| `com.hotvect.api.algorithms.Algorithm` | Base lifecycle contract; algorithms are `AutoCloseable` |
| `Ranker`, `Scorer`, `BulkScorer`, `TopK`, `ThemedTopK` | Public decision shapes |
| `AlgorithmDefinition` | Resolved identity, factories, configuration, and child definitions |
| `AlgorithmInstance` | Definition, parameter metadata, and instantiated algorithm |
| `AlgorithmRuntimeIdentity` | Canonical algorithm/hyperparameter/parameter identity |
| `ExecutionContext` | Workload mode plus input semantic supplied to factories |

The legacy `State` marker is deprecated for removal. State generation uses the definition's state-generator factory
and offline workflow rather than a new implementation of that marker.

## Factory contracts

`SimpleAlgorithmFactory` receives execution context, optional local-state storage, and algorithm configuration.
Non-composite parameterized factories additionally receive their dependency and parameter streams. Composite factories
additionally receive the named map of child `AlgorithmInstance` objects. Callers should use the longest applicable
`create(...)` overload when they need the execution context or runtime-local storage.
Several v10 factory interfaces retain abstract, deprecated `apply(...)` methods for binary compatibility, so check the
exact interface version before implementing or migrating a factory.

Common entry points include:

- `SimpleAlgorithmFactory` and `CompositeAlgorithmFactory`;
- `RankerFactory`, `CompositeRankerFactory`, and `BulkScorerFactory`;
- `RankingTransformerFactory` and `CompositeRankingTransformerFactory`;
- `StateGeneratorFactory`;
- `LocalStateStorage`, for an opaque private directory allocated by a containing runtime;
- decoder, encoder, vectorizer, transformer, and reward-function factories.

`LocalStateStorage.allocateDirectory()` transfers cleanup ownership to the caller. Delete an allocation when factory
construction fails, or from the returned algorithm's `close()` after successful construction. The algorithm definition
must declare `requires_local_state_storage: true`, and the runtime must supply a storage root; the declaration does not
create storage by itself. A direct factory receives `Optional.empty()` when no root was configured, so an algorithm
that requires storage must reject the missing capability in its factory.

## Data contracts

The API defines request, response, decision, example, and outcome types for ranking and TopK workflows. Containing
applications should adapt their domain objects at the boundary instead of leaking runner-specific JSON or file formats
into the algorithm interface.

## Javadocs

The Maven build attaches a Javadoc artifact for published Java modules. Use the Javadocs matching the exact Hotvect
version in the containing runtime or algorithm build. Do not use a newer API page as evidence that an older runtime
provides the same method.

For loading and module ownership, read [Algorithm JAR loading](../../concepts/jar-loading/index.md) and
[Version compatibility](../version-compatibility/index.md).
