---
title: Why Hotvect treats the algorithm as the application
description: Understand the design choice behind complete, versioned decision algorithms
tags: [motivation, architecture, algorithms, integration]
---

# Why Hotvect treats the algorithm as the application

A production decision rarely comes from a model call alone. The system must interpret a request, prepare candidates,
compute features, run one or more models, apply policy, and return a ranked or selected result. Hotvect treats that
whole decision path as the algorithm.

This is the central design choice behind the framework. It explains why Hotvect packages an executable implementation,
algorithm definition, assets, optional parameters, and declared child dependencies instead of exposing only a model
file or inference endpoint.

## The problem: one decision, many independently changing parts

In a conventional serving stack, algorithm-specific behavior is often divided between several owners and artifacts:

- a service decodes requests and prepares candidates;
- feature code exists separately for training and request-time inference;
- one or more model endpoints return scores;
- application code applies ranking, selection, or final policy;
- offline jobs reconstruct enough of that behavior to evaluate a change.

Those deployment boundaries can be useful. The problem appears when they also become the algorithm's ownership and
versioning boundaries. A model version then identifies only one part of the behavior that produced a decision.
Comparisons become harder because code, configuration, data windows, child models, and post-processing can change
independently.

## Hotvect's answer: version the complete decision contract

A Hotvect algorithm makes its behavior explicit through a small artifact set:

| Artifact | Responsibility |
| --- | --- |
| Algorithm package | Executable request handling, feature computation, composition, decision logic, and packaged assets |
| Embedded definition | Identity, factories, dependencies, data requirements, and lifecycle configuration |
| Parameter package, when needed | Trained parameters and generated state kept separate from executable code |

The result is an executable contract rather than a passive model artifact. A host can load the algorithm, supply its
parameters and dependencies, and invoke its public shape. Offline workflows can use the same artifact contract for
training, prediction, evaluation, auditing, and backtests.

These are logical package roles. Today, an algorithm package is distributed as a JVM JAR and a parameter package as a
ZIP. The algorithm package can include assets and integrations for non-JVM inference code while the public loading and
calling contract remains JVM-based.

This does not make offline and online environments identical. Inputs, execution context, overrides, and runtime
settings still matter. It does make those differences visible around a shared algorithm identity.

## Why executable JVM code instead of a feature DSL

Request-time algorithms frequently need typed domain objects, ordinary control flow, library calls, and close
integration with a JVM application. Hotvect lets algorithm authors express that logic as Java rather than translating
it into a separate feature language.

That choice provides normal compiler checks, tests, debugging, and refactoring tools. It also creates real
responsibilities: algorithm packages are trusted executable code, API compatibility matters, and artifact identity
must be managed deliberately. Hotvect favors an explicit application boundary over pretending that arbitrary decision
logic is only data.

## Composition is a logical property, not a placement promise

An outer algorithm can declare child algorithms for scoring, transformation, or policy. The definition describes the
logical graph and Hotvect wires named dependencies into their owning factories.

Where a component runs is a separate question. Current integrations can execute algorithm logic in the JVM and can
delegate supported inference work to managed Python workers. A local call and a remote call do not have identical
latency, serialization, batching, timeout, or failure behavior. Hotvect's direction is to preserve the logical
component graph across more runtime placements while keeping those operational differences explicit.

## The lifecycle exists to preserve evidence

Changing an algorithm is useful only when the result can be explained and compared. The `hv` workflows connect
algorithm artifacts to state generation, encoding, training, prediction, evaluation, auditing, performance tests, and
backtests. This keeps the question “what produced this result?” answerable beyond the source commit alone.

The lifecycle is therefore part of the application model, not Hotvect's product boundary. External systems may still
schedule work, store artifacts, register releases, run experiments, and monitor production behavior.

## When this model is a good fit

Hotvect is most useful when a decision algorithm has several of these properties:

- request-time feature or policy logic is substantial;
- multiple models or child algorithms must be composed;
- offline evaluation must stay aligned with request-time behavior;
- algorithm versions need independently inspectable code, definition, and parameters;
- the application benefits from loading typed JVM algorithm components.

For a single stateless model behind a stable endpoint, the full application contract may add unnecessary machinery.
Hotvect also does not replace a data orchestrator, feature store, model registry, experimentation platform, or
monitoring system. It supplies the decision-algorithm layer those systems build, evaluate, and operate.

## Next step

Read [How Hotvect works](../how-hotvect-works/index.md) for one request's path through the runtime, then
[Complete algorithms](../complete-algorithm/index.md) for the public shapes and composition model. The
[architecture overview](../../architecture/index.md) explains how the artifacts and runtimes fit together.
