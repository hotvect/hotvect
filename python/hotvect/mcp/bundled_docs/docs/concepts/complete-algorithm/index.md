---
title: Complete algorithms
description: What Hotvect includes in an executable decision algorithm and how public and child algorithms relate
tags: [concepts, algorithms, ranker, topk, state]
---

# Complete algorithms

A production decision is usually broader than a model invocation. It may decode a request, obtain or compute features,
call one or more models, apply rules, rank or select candidates, and construct a response. Hotvect treats that complete
path as the algorithm.

## The executable contract

A Hotvect algorithm combines three kinds of information:

| Part | Owns |
| --- | --- |
| Runtime implementation | Request-time transformations, inference integration, rules, ranking or selection policy |
| Algorithm definition | Identity, factories, declared children, feature/output configuration, runtime and workflow settings |
| Parameter artifact, when needed | Trained model files, generated state, and other inference-time data |

The runtime implementation, embedded definition, and packaged assets ship in the **algorithm package**. The
**parameter package** remains separate so parameters or generated state can change without rebuilding the algorithm
implementation. The current physical formats are a JVM JAR and a ZIP respectively. Some algorithms are parameterless.

See [Artifacts and identity](../artifacts-and-identity/index.md) for the complete artifact and identifier model.

## Public algorithm shapes

Choose the public contract by the decision the caller needs:

| Shape | Responsibility |
| --- | --- |
| Ranker | Score and order the candidates in a request |
| Scorer | Score one record; commonly used as a narrow child capability |
| Bulk scorer | Score candidates while another component owns final ordering or policy |
| TopK | Own sourcing and selecting up to `k` actions |
| ThemedTopK | Return TopK decisions plus an action-list ID and string list metadata |

State generation is a separate offline lifecycle role declared with a state-generator factory. The legacy `State`
marker interface is deprecated for removal and should not be chosen as a new public decision shape.

These shapes do not prescribe the model library or deployment topology. A ranker can be parameterless, call a JVM
model, delegate inference to a managed Python worker, or depend on another algorithm.

## Python and model libraries

The algorithm contract does not require model training or inference to be implemented in Java. During offline
preparation, Hotvect can run the training command declared by the algorithm inside the selected Python environment.
That command can use any library installed in that environment. Backend-specific encoding, packaging, and online
integration are available only where documented.

| Technology | Offline preparation | Online execution |
| --- | --- | --- |
| CatBoost | Dedicated feature encoding and the `catboost_train` command | Dedicated in-JVM scorers |
| TensorFlow | Generated-transformer backend, TFRecord encoding, optional dependencies, and CPU/GPU images | Managed Python worker for SavedModel inference |
| PyTorch | Optional dependencies and CPU/GPU images for algorithm-owned training | Supported through algorithm-owned managed workers; not a generic PyTorch model adapter |
| Other Python libraries | Usable from a declared training command when installed in the selected environment | Require an algorithm-owned integration or worker |

Vowpal Wabbit support was removed in Hotvect 10 and is not a current backend.

## Which surfaces execute each shape

An API shape does not imply that every Hotvect command or local server dispatches it directly.

| Shape | Offline `predict` and performance test | Local `hv serve` | Typical role |
| --- | --- | --- | --- |
| Ranker | Supported | Supported | Public ordering decision |
| Bulk scorer | Supported | Not dispatched | Batch-aligned candidate scores |
| TopK | Supported | Supported | Retrieval and selection |
| ThemedTopK | Supported through TopK dispatch | Supported through TopK dispatch | Selection with list identity and metadata |
| Scorer | Not dispatched directly | Not dispatched | Narrow child capability or custom host integration |

The current `audit` path targets a configured ranking transformer rather than dispatching the public shape; it rejects
ranking vectorizers. Training and backtesting follow the effective algorithm definition and can prepare child
pipelines before executing the outer algorithm.

## Outer and child algorithms

The **outer algorithm** is the public entrypoint used by the containing application or offline command. It owns the
public request/response contract and commonly owns the final decision policy.

A **child algorithm** owns a narrower capability such as transformation, scoring, state, or another decision. Children
can have children of their own, so composition forms a graph rather than a fixed two-level hierarchy.

Nesting describes ownership, not a mandatory execution order. The factories and runtime wiring determine how a parent
uses each child.

## An instantiated algorithm

At runtime, Hotvect represents a loaded algorithm as an `AlgorithmInstance`. It keeps together:

- the effective `AlgorithmDefinition`; composite instances include their resolved child definitions;
- parameter metadata, when parameters exist;
- the instantiated `Algorithm` object.

The instance is `AutoCloseable`, allowing model runtimes and other resources to be released with the algorithm.

## What “complete” does not mean

- It does not mean one physical file: the algorithm and parameter packages remain separate artifacts.
- It does not mean all work occurs in one process.
- It does not mean Hotvect owns data pipelines, registries, experiments, or monitoring.
- It does not mean every algorithm trains a model or generates parameters.

“Complete” means the behavior that turns a request into a decision has one explicit logical contract and composition.

## Next steps

- [Dependencies and bindings](../dependencies-and-bindings/index.md)
- [Develop a Hotvect algorithm](../../guides/develop-algorithms/index.md)
- [Algorithm definition reference](../../reference/algorithm-definition/index.md)
