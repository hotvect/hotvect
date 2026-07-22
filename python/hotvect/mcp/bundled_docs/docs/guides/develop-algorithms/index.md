---
title: Develop a Hotvect algorithm
description: Design, package, and validate a Hotvect algorithm after the first runnable example
tags: [development, algorithms, java, v10]
difficulty: intermediate
prerequisites:
  - Completed Build your first algorithm, or inherited an existing algorithm repository
  - Familiarity with requests, decisions, examples, algorithm packages, and parameter packages
related_docs:
  - ../first-algorithm/index.md
  - ../../concepts/complete-algorithm/index.md
  - ../../reference/algorithm-definition/index.md
---

# Develop a Hotvect algorithm

A Hotvect algorithm project turns application-specific data and decision logic into a versioned artifact the same
runtime can execute offline or inside an application. The minimum artifact is an algorithm package containing the
implementation and embedded definition. Feature-based or trained algorithms also produce a parameter package.
Composite algorithms declare and receive child algorithm instances. The current package formats are a JVM JAR and a
ZIP respectively.

If you have not built one before, complete [Build your first algorithm](../first-algorithm/index.md). It shows the full
contract without training or composition. This guide explains how to extend that structure deliberately.

For a complete executable extension, follow [Train your first model-backed algorithm](../first-trainable-algorithm/index.md)
or [Compose your first algorithm](../first-composite-algorithm/index.md) before using this page as a design checklist.
If the algorithm owns candidate selection rather than ordering caller-provided candidates, use
[Build a TopK algorithm](../topk-algorithms/index.md).

## The project anatomy

A typical repository has these responsibilities even when its exact packages differ:

```text
algorithm project
├── application data types       # shared request data, candidate data, outcomes
├── decoder factory              # external example data → typed offline examples
├── feature code                 # optional transformation or vectorization
├── algorithm factory            # creates the public Ranker, BulkScorer, or TopK
├── child algorithms             # optional reusable capabilities
├── algorithm definition JSON    # identity, factories, dependencies, data, stages
└── tests                        # policy, decoding, features, packaging, parity
```

The definition is a runtime manifest, not a replacement for the Java implementation. It tells Hotvect which classes
to instantiate and which lifecycle to orchestrate; the classes contain the typed behavior.

## Choose the smallest useful design

Most algorithms fit one of three levels:

| Level | Use it when | Artifacts |
| --- | --- | --- |
| Policy-only | Rules or deterministic logic can make the decision directly | Algorithm package; no learned parameters |
| Feature-based | A model consumes generated features or vectors | Algorithm package + trained parameter package |
| Composite | An outer policy coordinates reusable child algorithms | Outer packages + declared child packages |

Start at the lowest level that represents the problem. A child dependency is valuable when it has an independent
contract, artifact lifecycle, or deployment location—not merely to divide one class into smaller classes.

## 1. Define the public decision

Choose the shape application code will call:

| Shape | Request and result |
| --- | --- |
| `Ranker` | Receives candidate actions and returns an ordered decision for them |
| `BulkScorer` | Receives candidates and returns scores without owning final ordering policy |
| `TopK` | Selects items without receiving a candidate list in the request |
| `ThemedTopK` | Adds an action-list ID and string metadata to a TopK response |

`Scorer` is useful as a narrow child capability, but the built-in task and local-serving surfaces do not dispatch it
directly. State generation is an offline lifecycle role configured with a generator factory, not a new public use of
the deprecated `State` marker.

The offline task, performance, and local debug surfaces do not expose every shape in exactly the same way. Check the
[surface matrix](../../concepts/complete-algorithm/index.md#public-algorithm-shapes) before choosing a shape for a new
host integration.

## 2. Make the data boundary explicit

For ranking, define:

- the shared request type, available to every candidate;
- the action type, carried by each candidate;
- a stable action ID for each candidate;
- the outcome type used by offline examples, if the algorithm evaluates or trains.

The decoder translates an external record into a typed offline `Example`. It is part of the algorithm's data contract:
changing field meaning or action identity can change features, labels, and comparisons even when the code still
compiles.

An online JVM host normally creates an online request directly. It does not need to serialize traffic through the
offline example decoder. See [Requests, decisions, and examples](../../concepts/data-model/index.md) for the boundary
between application data and Hotvect's common model.

## 3. Implement the decision path

For a policy-only algorithm, a `SimpleAlgorithmFactory` can return the public algorithm directly. Keep the final
selection or ordering policy in this outer algorithm so its behavior is obvious to callers.

For a model-backed ranker, separate these responsibilities:

```text
typed request
  → transformer or vectorizer
  → encoded model input
  → model inference
  → public ranking policy
  → typed decisions
```

Use `@GenerateSimpleRankingTransformer` when feature methods and a generated streaming transformer match the problem.
The processor validates feature dependencies at compile time and generates the implementation. The
[generated-transformer guide](../simple-ranking-transformer/index.md) covers its build and feature contract.

A custom transformer or vectorizer remains appropriate when its data flow cannot be represented by the annotation
processor. Whichever path you use, give output features stable names and backend types when model compatibility
depends on them.

## 4. Add children only at real boundaries

A composite factory receives dependency instances by the names declared in its definition. The outer algorithm can
call children in process; a host can replace a declared child binding with another instance, including a remote proxy,
while preserving the parent contract.

Design each boundary around a typed capability and explicit ownership. Do not assume an algorithm package is isolated
for security, or that replacing a child transfers its lifecycle automatically. Read
[Dependencies and bindings](../../concepts/dependencies-and-bindings/index.md) before relying on deeper graphs or host
overrides; it records the current nested-factory parameter boundary.

## 5. Write the embedded definition

Place `<algorithm-name>-algorithm-definition.json` at the JAR resource root. At minimum, a decision algorithm defines
its name, version, and algorithm factory. Add only the factories and lifecycle fields the implementation uses:

- decoder for offline example input;
- transformer or vectorizer for feature production;
- encoder and training command for learned parameters;
- generator for state-producing workflows;
- dependencies for composite algorithms;
- train/test data specifications and execution controls for offline orchestration.

The resource name, `algorithm_name`, and name selected by the caller must agree. Treat the definition and code as one
versioned contract. For the complete field matrix, use [Algorithm definition](../../reference/algorithm-definition/index.md).

## 6. Decide whether the algorithm has parameters

A policy-only algorithm can be created directly without parameter streams. A trainable algorithm's pipeline packages
the learned model and `algorithm-parameters.json` into a parameter package. At runtime, the algorithm package supplies
behavior and the parameter package supplies the selected learned state. Those packages are currently a JAR and ZIP.

The current surfaces differ for parameterless algorithms: direct `AlgorithmInstanceFactory` loading can omit a ZIP,
while `AlgorithmRepository` and local `hv serve` require a parameter identity or path. The local tutorial uses a
metadata-only ZIP for that reason. Do not add an artificial model merely to satisfy one host surface.

## 7. Test from the inside out

Use failures that identify one boundary at a time:

1. Unit-test the decision policy with typed requests.
2. Test decoding with a small synthetic record.
3. Build the algorithm package and verify the definition resource is inside its JAR.
4. Load the exact algorithm and parameter packages intended for the next environment.
5. Exercise a supported bounded runtime: local `hv serve` for the minimal policy-only ranker, or audit/predict when
   the definition declares the transformer and reward contracts those tasks require.
6. Train or backtest one fixed date before expanding the range.
7. Evaluate quality, parity, and performance as separate claims.

For a trainable algorithm, a typical artifact loop is:

```text
source + definition
  → algorithm package
  → train
  → parameter package
  → audit / predict
  → evaluate and performance-test
  → result.json
```

Run [local training](../local-train/index.md) or a [local backtest](../local-backtest/index.md) with an explicit data
window. Inspect `result.json` to see which stages ran or reused output; a zero exit code alone does not establish model
quality or online/offline parity.

## 8. Iterate without obscuring identity

Use a definition override for a temporary experiment, then pass that same override to every lifecycle stage that must
see it. Packaging parameters does not make the override become the algorithm package's runtime definition
automatically.

Keep algorithm and parameter IDs immutable: repository loaders cache instances by those IDs. Reusing an ID for
different bytes can make a process continue serving an earlier artifact even though a file changed underneath it.

## Where to go next

- [Understand the example product algorithms](../example-product-algorithms/index.md)
- [Train your first model-backed algorithm](../first-trainable-algorithm/index.md)
- [Compose your first algorithm](../first-composite-algorithm/index.md)
- [Build a TopK algorithm](../topk-algorithms/index.md)
- [Generate runtime state](../state-generation/index.md)
- [Generate a ranking transformer](../simple-ranking-transformer/index.md)
- [Parent-child algorithms](../patterns/parent-child/index.md)
- [Artifacts and identity](../../concepts/artifacts-and-identity/index.md)
- [Train locally](../local-train/index.md)
- [Backtest locally](../local-backtest/index.md)
