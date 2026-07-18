---
title: Dependencies and bindings
description: Distinguish Hotvect algorithm composition, injected values, data inputs, build dependencies, and runtime bindings
tags: [concepts, dependencies, composition, bindings, architecture]
---

# Dependencies and bindings

Hotvect uses “dependency” in several domains. Keeping them distinct is necessary when reasoning about composition,
training inputs, feature calculation, and runtime placement.

## Dependency vocabulary

| Kind | Declared or supplied through | Meaning |
| --- | --- | --- |
| Algorithm dependency | `dependencies` in the algorithm definition | A named child algorithm in the logical composition graph |
| Injected algorithm | `@InjectAlgorithm` or a composite factory dependency map | A typed child capability used by transformation or decision code |
| Feature dependency | `@Inject` and a feature namespace | One computed feature or intermediate value used by another computation |
| Data dependency | Train/test/state data specifications | Offline data required to prepare or evaluate an algorithm |
| Build dependency | Maven or Gradle | A library needed to compile or package the algorithm JAR |
| External runtime binding | Host-supplied `AlgorithmInstance` | An application object or proxy whose value wins in the composite factory map |

“Remote execution” in the offline guides means running a whole job on SageMaker. It does not mean placing one runtime
dependency on another machine.

## Declared child algorithms

An algorithm definition names children under `dependencies`. Hotvect resolves their definitions and parameter artifacts
recursively, then supplies instantiated children to composite factories by algorithm name. A useful composite definition
also makes its public shape and offline example contract visible:

```json
{
  "hotvect_version": "10.43.1",
  "algorithm_name": "example-ranker",
  "algorithm_version": "1.0.0",
  "dependencies": {
    "candidate-scorer": {}
  },
  "decoder_factory_classname": "org.example.ranking.ExampleRankingDecoderFactory",
  "reward_function_factory_classname": "org.example.ranking.ExampleRewardFunctionFactory",
  "algorithm_factory_classname": "org.example.ranking.ExampleRankerFactory",
  "test_data_spec": {
    "data_prefix": "ranking_examples"
  }
}
```

The dependency key is an identifier, not a declared Java type. It must exactly match the key the composite factory
requests. That factory is where the parent states how the child is used:

```java
public final class ExampleRankerFactory
        implements CompositeRankerFactory<Query, Candidate> {
    @Override
    @SuppressWarnings("unchecked")
    public Ranker<Query, Candidate> apply(
            Optional<JsonNode> configuration,
            Map<String, InputStream> parameters,
            Map<String, AlgorithmInstance<?>> dependencies
    ) {
        BulkScorer<Query, Candidate> scorer = (BulkScorer<Query, Candidate>) dependencies
                .get("candidate-scorer")
                .algorithm();
        return new BulkScoreGreedyRanker<>(scorer);
    }
}
```

Here the definition tells Hotvect to resolve and instantiate `candidate-scorer`; the `CompositeRankerFactory` tells
the reader and compiler that it must be a `BulkScorer<Query, Candidate>`. The parent owns the order in which it calls
children and any policy it applies around them. Add another declared child only when the factory explicitly consumes it.

The dependency graph must be acyclic. A direct or indirect cycle cannot produce a finite construction or preparation
order; the current loaders do not provide a useful general cycle-resolution mechanism.

Use [Parent and child algorithms](../../guides/patterns/parent-child/index.md) for targeting, overrides, offline
preparation, and artifact inspection.

## Host-provided bindings

The containing application can supply named `AlgorithmInstance` overrides when it creates the algorithm runtime.
Hotvect first loads the dependencies declared by the algorithm definition, then overlays the supplied map by name. The
supplied value is therefore the one passed to the composite factory, but it does **not** currently prevent the declared
child from being loaded.

`AlgorithmInstance.externalAlgorithm(...)` creates the metadata wrapper used for an external object that implements
the Hotvect `Algorithm` marker interface.

This allows an application to bind dependencies such as a feature-store adapter or an application-owned client without
packaging that object inside the algorithm JAR.

The host owns the external object's transport, discovery, authentication, batching, retries, and resource lifecycle.
The displaced loaded child is not generically closed by the override operation. Hotvect also does not currently
generate a remote proxy or declaratively place arbitrary children on other machines.

## Injected algorithms in generated transformers

For generated ranking transformers, `@InjectAlgorithm("dependency-name")` requests a typed algorithm dependency in a
feature method. The annotation processor adds an explicit constructor argument to the generated transformer. The
composite factory receives the named `AlgorithmInstance` map and supplies the correct dependency.

This is different from `@Inject("feature-name")`, which refers to a feature or intermediate value in the computation
graph.

## Current placement and direction

Current Hotvect supports:

- child algorithms loaded into the application JVM;
- host-provided external objects or proxies bound by name;
- model inference delegated to managed local Python worker processes;
- whole offline jobs submitted to SageMaker.

Nested composite children have a current artifact boundary: the loader constructs them with an empty parameter-stream
map. Do not design a nested composite child that expects its own parameter files until that loader path supports them.

The architectural direction is to preserve the logical dependency graph as more implementations move to specialized
processes or services. That direction does not make distribution operationally invisible: serialization, batching,
latency, timeouts, failures, and resource limits must remain explicit and testable.

See [Runtime topologies](../../architecture/runtime-topologies/index.md) for the supported topology matrix.
