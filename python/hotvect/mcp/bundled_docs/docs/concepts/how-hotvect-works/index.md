---
title: How Hotvect works
description: Follow one decision algorithm from source code through training, evaluation, and application integration
tags: [concepts, lifecycle, runtime, beginner]
---

# How Hotvect works

Hotvect is a framework for implementing and operating **decision algorithms**. A decision algorithm accepts a typed
request and returns a decision such as an ordering, a set of selected items, or a set of scores. It can include feature
calculation, one or more models, rules, child algorithms, and final decision policy.

Hotvect is not a hosted prediction service. Algorithm authors build an algorithm package; offline tools exercise that
package and create a parameter package when needed; a containing application loads the selected packages and calls
the algorithm.

This page follows an illustrative `example-document-ranker` through that system.

## The four things to keep separate

| Part | What it is | Who changes it |
| --- | --- | --- |
| Hotvect framework | Public APIs, runtime loaders, offline commands, and workflow orchestration | Framework maintainers |
| Algorithm package | The ranker's implementation, embedded definition, and packaged assets | Algorithm authors |
| Parameter package | Trained model files or generated state needed at inference time | An offline run |
| Execution environment | Either an offline job or the application serving real requests | Workflow or application owners |

The algorithm package says **how the algorithm behaves**. The parameter package contains **what a particular training
or state-generation run produced**. Keeping them separate allows parameters to change without rebuilding the
algorithm implementation. Today, the physical formats are a JVM JAR and a ZIP respectively.

## 1. Define the decision boundary

The author first chooses the public shape that matches the caller's need. For a ranker, the boundary is:

```text
RankingRequest<Shared, Action>
  → Ranker.rank(...)
  → RankingResponse<Action>
```

The request carries one shared context and a list of candidate actions with stable IDs. The response carries ordered
decisions. The `Shared` and `Action` payloads are application types; Hotvect supplies the envelope and lifecycle
contract.

Other shapes include bulk scoring, TopK selection, and themed TopK selection. Single-record `Scorer` is normally a
child capability or a custom-host contract because the built-in task and local-server surfaces do not dispatch it
directly. State generation is an offline workflow role rather than a current public decision shape. Read
[Requests, decisions, and examples](../data-model/index.md) before choosing a shape.

## 2. Implement and describe the algorithm

The algorithm project contains two complementary kinds of source:

- Java code implements request decoding, feature computation, factories, model integration, and decision policy.
- `<algorithm-name>-algorithm-definition.json` describes identity, selected factories, child dependencies, data inputs,
  training behavior, and runtime settings.

The definition is configuration, not an alternative implementation. A factory named in the definition must exist in
the current JAR-based algorithm package and implement the corresponding Hotvect API.

An algorithm can be simple or composite. A simple ranker constructs its decision logic directly. A composite ranker
receives named child `AlgorithmInstance` objects—for example, a scorer and a policy component—through its factory.

## 3. Build the algorithm package

The project build combines the implementation, definition resource, and packaged assets into the algorithm package.
Hotvect loads the definition by algorithm name, then instantiates the factory named by that definition.

The current outer package is a JVM JAR. It is more than a library of model code: it is a loadable application component
with an explicit public shape and configuration contract. It can package assets or integrate code executed by a
managed Python worker; that does not turn Python into a second public algorithm-authoring API.

<div class="hv-flow">
  <div class="hv-flow__step"><span>01</span><strong>Source</strong><small>Java · definition</small></div>
  <div class="hv-flow__step"><span>02</span><strong>Build</strong><small>algorithm project</small></div>
  <div class="hv-flow__step"><span>03</span><strong>Algorithm package</strong><small>implementation · contract</small></div>
  <div class="hv-flow__step"><span>04</span><strong>Parameter package</strong><small>optional trained state</small></div>
  <div class="hv-flow__step"><span>05</span><strong>Instance</strong><small>ready to call</small></div>
</div>

## 4. Prepare parameters offline

If the algorithm trains a model, Hotvect's Python workflow coordinates the preparation of its inference artifact:

1. Resolve the effective definition, including any explicit override.
2. Prepare child algorithms and declared data dependencies.
3. Decode source rows and, where configured, generate state.
4. Transform and encode training examples.
5. Run the algorithm's declared training command.
6. Package the model files, state, and parameter metadata into a parameter package.

Not every algorithm uses every stage. A parameterless rules algorithm can skip training. A state-producing definition
may only generate and package state. A composite can prepare several children before constructing its outer artifact.

Prediction, evaluation, and performance testing then load the algorithm package and, when that path requires it, the
parameter package. Their outputs are evidence about that exact runtime identity; they are not new deployable
algorithms. The Python lifecycle normally still produces a predict-parameters ZIP for a non-state pipeline even when
it contains only metadata or packaged child artifacts.

## 5. Load the algorithm in an application

In the online path, the containing application owns transport and operations: HTTP or event handling, authentication,
traffic management, observability, and surrounding service clients. Hotvect owns loading and constructing the selected
algorithm.

The runtime path is:

1. Resolve algorithm metadata: algorithm name, algorithm version, and parameter ID.
2. Obtain the matching algorithm and parameter packages. The current `AlgorithmRepository` resolves these as a JAR
   and ZIP and requires a parameter identity; direct `AlgorithmInstanceFactory` use can load a parameterless algorithm
   with no ZIP.
3. Read the embedded definition and resolve declared children or host-provided bindings.
4. Create an `AlgorithmInstance` with an online `ExecutionContext`.
5. Cache and reuse that instance while it remains active.
6. Pass typed requests to its public algorithm and return the resulting decisions.

The application does not call the offline CLI for each request. It embeds the Hotvect online runtime and calls the
loaded Java object.

## What is shared—and what is not

| Shared across offline and online use | Environment-specific |
| --- | --- |
| Algorithm package and embedded definition | How raw input reaches the algorithm |
| Parameter package | Offline files versus live application objects |
| Public request/response shape | Batch versus realtime runtime configuration |
| Declared logical dependency graph | Concrete child objects, host bindings, and resource ownership |
| Algorithm and parameter identity | Scheduling, traffic, authentication, and monitoring |

This is why Hotvect improves online/offline consistency without claiming automatic parity. The algorithm packages can
be identical while inputs, bindings, and execution settings still differ.

## A useful way to read any Hotvect algorithm

When entering an unfamiliar project, trace it in this order:

1. **Public shape:** What decision does the outer algorithm expose?
2. **Definition:** Which factories, children, data, and workflow stages are declared?
3. **Implementation:** Where are features, inference, and policy implemented?
4. **Artifacts:** Does it need generated state or trained parameters?
5. **Evidence:** Which prediction, evaluation, and performance outputs validate it?
6. **Integration:** Which application loads it and which dependencies does that application provide?

Continue with [Complete algorithms](../complete-algorithm/index.md) for the algorithm boundary. Then either
[build your first algorithm](../../guides/first-algorithm/index.md) or
[tour an existing algorithm](../../guides/first-workflow/index.md).
