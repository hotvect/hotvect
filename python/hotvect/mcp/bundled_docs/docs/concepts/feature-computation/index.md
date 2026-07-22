---
title: Feature computation
description: The current Hotvect model for request, candidate, interaction, feature-store, and injected-algorithm values
tags: [concepts, features, transformer, processor, injection]
---

# Feature computation

Hotvect keeps algorithm-specific request-time transformations close to the algorithm runtime. The recommended v10 path
uses generated ranking transformers built from typed Java methods.

## Transformer or vectorizer?

Both contracts produce one model input per ranking action, but they expose different levels of information:

| Contract | Output for each action | Choose it when |
| --- | --- | --- |
| `RankingTransformer` | A namespaced record of typed feature values | You want generated feature graphs, backend-specific schemas, feature audits, or named values that remain inspectable |
| `RankingVectorizer` | An already flattened `SparseVector` with positional numeric values | A custom model deliberately consumes a small fixed-order numerical vector and owns that ordering contract |

The definition selects one through `transformer_factory_classname` or `vectorizer_factory_classname`. If both are
present, the current loader selects the vectorizer, so a new definition should declare only the contract it uses.
Current `hv audit` supports ranking transformers and rejects vectorizers. Prefer a transformer for new feature-rich
algorithms; use a vectorizer only when the flattened numerical boundary is intentional.

## Request and candidate values

A ranking request contains:

- one shared request object;
- a list of available actions/candidates;
- optional feature-store responses attached to the request path.

Generated ranking transformers distinguish:

| Computation | Typical input | Scope |
| --- | --- | --- |
| Shared feature | Shared request object or shared context | Once per request |
| Action feature | Candidate/action and optional shared values | Once per candidate |
| Interaction feature | Shared and candidate values together | Once per request-candidate pair |

`@SharedFeature` declares reusable request-level values. `@Feature` declares values emitted to the selected model
backend or used by downstream feature methods.

## Two forms of injection

- `@Inject("feature-name")` injects another feature or intermediate computation.
- `@InjectAlgorithm("dependency-name")` injects a typed algorithm dependency declared in the logical algorithm graph.

The annotation processor validates the reachable feature graph, missing dependencies, dependency direction, and
backend type compatibility during compilation. It generates the transformer implementation and an inspection report.

## Feature-store values

Generated transformers receive an explicit `FeatureStoreRetriever`. It fetches named `FeatureStoreResponse` values for
the request and exposes them through the shared context. The algorithm-side factory passes that retriever to the
generated transformer. If the containing application owns the underlying client, the algorithm factory must bridge
the host binding into the retriever explicitly; the Hotvect loader does not inject it. Hotvect does not prescribe the
feature-store product or network transport.

## Namespaces

Namespaces identify features and intermediate values. Runtime maps use canonical namespace handles, so equal text is
not enough when two noncanonical objects represent the same name. Read [Namespace identity](../namespaces/index.md)
before implementing custom transformer wiring.

## Backends

Feature methods are backend-neutral Java code. The generated-transformer annotation selects a backend such as CatBoost
or TensorFlow, and `transformer_parameters.features` declares the ordered output contract interpreted by that backend.

See [Simple ranking transformer](../../guides/simple-ranking-transformer/index.md) for implementation and
[Generated transformer backends](../../reference/generated-transformer-backends/index.md) for exact type grammars.
