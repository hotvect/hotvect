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
| `hotvect-tensorflow` | TensorFlow generated feature types, JSON schema generation, and TFRecord encoding; inference is supplied by an algorithm-owned worker integration |
| Java `hotvect-python` | Managed Python worker runtime integration |
| `hotvect-online-util` | Dynamic online loading and algorithm repository |
| `hotvect-offline-util` | JVM offline tasks and command-line runner |

## Algorithm contracts

| Contract | Purpose |
| --- | --- |
| `com.hotvect.api.algorithms.Algorithm` | Base lifecycle contract; algorithms are `AutoCloseable` |
| `Ranker`, `Scorer`, `BulkScorer`, `TopK`, `ThemedTopK` | Public decision shapes |
| `AlgorithmDefinition` | Declarative identity, factories, and configuration; it never owns constructed children |
| `AlgorithmInstance` | One constructed node value: definition, parameter metadata, instantiated algorithm, and declared `TypeToken` contract |
| `HyperparameterizedAlgorithmId` | Algorithm code and effective hyperparameter identity |
| `ParameterizedAlgorithmId` | One algorithm's code, hyperparameters, and parameters, excluding dependencies |
| `AlgorithmRuntimeId` | Parameterized algorithm identity plus recursively resolved child identities |
| `AlgorithmDependencies` | Typed access to each resolved singleton child algorithm through `only(...)` |
| `ExecutionContext` | Workload mode plus input semantic supplied to factories |
| `AlgorithmGraph` (`hotvect-online-util`) | Direct-load owner of graph topology, recursive identity, classloaders, and lifecycle |

The legacy `State` marker is deprecated for removal. State generation uses the definition's state-generator factory
and offline workflow rather than a new implementation of that marker.

## Factory contracts

`SimpleAlgorithmFactory` receives execution context, optional local-state storage, and algorithm configuration.
Non-composite parameterized factories additionally receive their dependency and parameter streams. Composite factories
additionally receive `AlgorithmDependencies`. `only(name)` requires one selected algorithm; `name` is always the
unversioned dependency key. Its return type is inferred from the factory code:

```java
BulkScorer<Query, Candidate> scorer = dependencies.only("candidate-scorer");
```

Callers that want dependency compatibility checks during composition can pass Guava's `TypeToken`, which preserves
the expected type arguments at the dynamic boundary:

```java
import com.google.common.reflect.TypeToken;

BulkScorer<Query, Candidate> scorer = dependencies.only(
        "candidate-scorer",
        new TypeToken<BulkScorer<Query, Candidate>>() {});
```

The typed lookup rejects incompatible algorithm interfaces and, when the dependency's contract for the requested
interface is fully resolved, incompatible generic arguments. Unresolved generic factory arguments are allowed without
verifying generic compatibility. This does not relax serving-root contract validation.

`AlgorithmDependencies` has no `Class`-argument lookup overload. The expected `TypeToken` must always be concrete,
including for non-generic algorithm types. Generated transformers require a concrete algorithm type for
each `@InjectAlgorithm` parameter and emit public `TypeToken` constants for their exact injected contracts. New
composite factories implement the single context- and local-storage-aware `create(...)` method shown above.

The runtime retains the deprecated published-v10 composite factory signatures so existing algorithm JARs whose
factories receive `Map<String, AlgorithmInstance<?>>` remain executable. That contract, like the Stage 2 runtime,
represents exactly one algorithm per dependency.

Common entry points include:

- `SimpleAlgorithmFactory` and `CompositeAlgorithmFactory`;
- `RankerFactory`, `CompositeRankerFactory`, and `BulkScorerFactory`;
- `RankingTransformerFactory` and `CompositeRankingTransformerFactory`;
- `StateGeneratorFactory`;
- `LocalStateStorage`, for an opaque private directory allocated by a containing runtime;
- decoder, encoder, vectorizer, transformer, and reward-function factories.

`LocalStateStorage.allocateDirectory()` transfers cleanup ownership to the caller. Delete an allocation when factory
construction fails, or from the returned algorithm's `close()` after successful construction. Hotvect always supplies
the lazy allocator. A direct factory uses the system temporary directory by default; containing runtimes derive it from
their scratch directory or set an explicit root.

## Data contracts

The API defines request, response, decision, example, and outcome types for ranking and TopK workflows. Containing
applications should adapt their domain objects at the boundary instead of leaking runner-specific JSON or file formats
into the algorithm interface.

Read [Ranking and prediction contracts](../ranking-and-prediction-contracts/index.md) for exact ordering, adapter,
tie-breaking, metadata, and batch-output rules. Read
[Feature-store integration](../../concepts/feature-store-integration/index.md) for the asynchronous capability and
partial-failure contract.

## Javadocs

The Maven build attaches a Javadoc artifact for published Java modules. Use the Javadocs matching the exact Hotvect
version in the containing runtime or algorithm build. Do not use a newer API page as evidence that an older runtime
provides the same method.

For loading and module ownership, read [Algorithm JAR loading](../../concepts/jar-loading/index.md) and
[Version compatibility](../version-compatibility/index.md).
