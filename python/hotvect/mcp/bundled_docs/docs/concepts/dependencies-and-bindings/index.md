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
| Algorithm dependency | `dependencies` in the algorithm definition | A named child selection in the declared composition graph |
| Injected algorithm | `@InjectAlgorithm` or composite-factory `AlgorithmDependencies` | A typed child capability used by transformation or decision code |
| Feature dependency | `@Inject` and a feature namespace | One computed feature or intermediate value used by another computation |
| Data dependency | Train/test/state data specifications | Offline data required to prepare or evaluate an algorithm |
| Build dependency | Maven or Gradle | A library needed to compile or package the algorithm JAR |
| External runtime binding | Host-supplied `Algorithm` | An application object or proxy substituted for a declared dependency |

“Remote execution” in the offline guides means running a whole job on SageMaker. It does not mean placing one runtime
dependency on another machine.

## Declared child algorithms

An algorithm definition names children under `dependencies`. Hotvect resolves their definitions and parameter artifacts
recursively, then supplies instantiated children to composite factories by algorithm name. A useful composite definition
also makes its public shape and offline example contract visible:

```json
{
  "hotvect_version": "10.44.11",
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

The dependency reference is an identifier, not a declared Java type. Its unversioned name is the key the composite
factory requests. That factory is where the parent states how the child is used:

```java
public final class ExampleRankerFactory
        implements CompositeRankerFactory<Query, Candidate> {
    @Override
    public Ranker<Query, Candidate> create(
            ExecutionContext executionContext,
            Optional<LocalStateStorage> localStateStorage,
            Optional<JsonNode> configuration,
            Map<String, InputStream> parameters,
            AlgorithmDependencies dependencies
    ) {
        BulkScorer<Query, Candidate> scorer = dependencies.only("candidate-scorer");
        return new BulkScoreGreedyRanker<>(scorer);
    }
}
```

Here the definition tells Hotvect to resolve and instantiate `candidate-scorer`; the `CompositeRankerFactory` tells
the reader and compiler that it must be a `BulkScorer<Query, Candidate>`. The parent owns the order in which it calls
children and any policy it applies around them. Add another declared child only when the factory explicitly consumes it.

`only(...)` returns the dependency's algorithm and infers its result type from the factory code.
To check dependency compatibility during composition, pass a concrete Guava `TypeToken`, for
example `dependencies.only("candidate-scorer", new TypeToken<BulkScorer<Query, Candidate>>() {})`.
This rejects the wrong algorithm interface and incompatible generic types when the dependency's contract for the
requested interface is fully resolved. Generic factory contracts such as `BulkScorer<SHARED, ACTION>` are allowed;
their unresolved type arguments are not verified. The expected `TypeToken` must still be concrete. Serving-root
registration retains its stricter requirement that the declared algorithm contract satisfy the registered type.

Private dependencies are the default and may be written as an array or object. They must use an unversioned name
because the immediate parent artifact supplies their definition:

```json
{"dependencies": ["private-encoder", "private-policy"]}
```

Online/EMS shared dependencies require an exact published version and permit no parent-specific override. Exact
selection allows multiple versions of the same algorithm name to coexist in an artifact set:

```json
{"dependencies": {"shared-normalizer@2.0.0": {"scope": "shared"}}}
```

In online serving, every reference to one shared `name@version` in a serving generation retains one runtime instance.
The code comes from one canonical artifact. On every refresh, Hotvect independently chooses the freshest available
parameter namespace for that shared algorithm. It compares `last_test_time` first because that is the logical date of
the data used to produce the parameters, then uses `ran_at` to order reruns for the same date. A parameter-bearing
shared namespace without `last_test_time` is rejected. Hotvect uses the selected ZIP to construct the generation's
instance; calling roots' parameter IDs do not create separate shared instances. If a shared algorithm depends on
another shared algorithm, a child revision change also reconstructs the shared parent so the new generation cannot
retain an old child through it. A shared algorithm cannot contain a slot-backed dependency, including below a private
child, because its one runtime identity cannot depend on a root-specific slot selection.

Offline execution does not retain that shared runtime scope. It treats a `scope: "shared"` child as a private child of
the enclosing artifact, so an experiment may provide parent-specific fields such as `algorithm_parameters`. The
dependency suffix may be present, changed, or omitted offline; it is normalized to the child name and does not select
code or a JAR. Select a different child artifact explicitly when an experiment needs different code.

Slot-backed dependencies deliberately omit an algorithm version because EMS selects the actual algorithm. The
dependency key is also the EMS slot name; there is no separate alias. Stage 2 requires every selected variant to
supply exactly one algorithm:

```json
{"dependencies": {"candidate-scorers": {"scope": "slot"}}}
```

The runtime reads the EMS slot `candidate-scorers` and exposes its selected algorithm under the dependency key
`candidate-scorers`. The factory obtains it with `dependencies.only("candidate-scorers")`.

The factory-facing dependency names in these examples are `private-encoder`, `private-policy`, `shared-normalizer`, and
`candidate-scorers`; `@version` is never part of a dependency lookup. It identifies an online shared declaration; in
offline execution it is accepted but does not select code.

The dependency graph must be acyclic. A direct or indirect cycle cannot produce a finite construction or preparation
order; the current loaders do not provide a useful general cycle-resolution mechanism.

Use [Parent and child algorithms](../../guides/patterns/parent-child/index.md) for targeting, overrides, offline
preparation, and artifact inspection.

## Host-provided bindings

The containing application can supply named `Algorithm` bindings when it creates the serving runtime. Hotvect wraps
each application object as an external runtime node and substitutes it by dependency name before constructing the
declared artifact child. The parent must declare the dependency, but its artifact does not need to package a child
definition for a name that the application binds; Hotvect neither reads nor inspects that absent child.

Application bindings are registrations for the complete runtime, not declarations attached to one root graph. Every
root and canonical shared dependency graph can consume a matching registration. A graph that does not declare one of
the registered names simply does not use it; registrations are not required to be reachable from every graph. Host
bindings do not change the runtime identity of a shared algorithm.

This allows an application to bind dependencies such as a feature-store adapter or an application-owned client without
packaging that object inside the algorithm JAR.

A slot-backed dependency is selected by EMS and therefore cannot also have an application binding under the same
dependency key. The runtime rejects that collision instead of choosing one source.

The host owns the external object's transport, discovery, authentication, batching, retries, and resource lifecycle.
Hotvect does not close the binding with its constructed graph. It also does not generate a remote proxy or
declaratively place arbitrary children on other machines.

## Injected algorithms in generated transformers

For generated ranking transformers, `@InjectAlgorithm("dependency-name")` requests a typed algorithm dependency in a
feature method. Stage 2 accepts one concrete `Algorithm` type and requires the dependency to resolve exactly one
algorithm:

```java
@InjectAlgorithm("candidate-scorers") Policy policy
```

The parameter corresponds to `dependencies.only(name, typeToken)`. `typeToken` is always a concrete `TypeToken<X>`,
including when `X` is non-generic; `AlgorithmDependencies` has no `Class`-argument lookup overload. The processor
rejects maps, collections, wildcards, type variables, raw generic algorithms, and other non-concrete shapes. It adds an explicit typed
constructor argument and a public `ALGORITHM_DEPENDENCY_<DEPENDENCY_NAME>_TYPE` constant, where the dependency name
is converted to an uppercase Java identifier. Constants are allocated in dependency-name order; if two names convert
to the same identifier, later constants receive `_2`, `_3`, and so on before `_TYPE`. The composite factory supplies
that generated constant to `only(...)`, rather than recreating a token by hand.

This is different from `@Inject("feature-name")`, which refers to a feature or intermediate value in the computation
graph.

## Current placement and direction

Current Hotvect supports:

- child algorithms loaded into the application JVM;
- host-provided external objects or proxies bound by name;
- model inference delegated to managed local Python worker processes;
- whole offline jobs submitted to SageMaker.

Private children receive parameter streams from their own algorithm namespace in their parent graph's selected
parameter ZIP. An online static shared child instead receives the freshest matching ZIP selected during the current
refresh, independently of its code provider; all parents in that generation use the one constructed instance.
Parameterless graphs can omit ZIPs entirely.

The architectural direction is to preserve the declared dependency graph as more implementations move to specialized
processes or services. That direction does not make distribution operationally invisible: serialization, batching,
latency, timeouts, failures, and resource limits must remain explicit and testable.

See [Runtime topologies](../../architecture/runtime-topologies/index.md) for the supported topology matrix.
