---
title: Algorithm SDK and packages
description: Build complete algorithms with the Hotvect Java APIs and package their versioned runtime contract
tags: [components, algorithm, sdk, jar, packaging, dependencies]
related_docs:
  - ../../concepts/jar-loading/index.md
  - ../../concepts/artifacts-and-identity/index.md
  - ../../reference/algorithm-definition/index.md
  - ../../guides/develop-algorithms/index.md
---

# Algorithm SDK and packages

The Algorithm SDK is the Java programming model for complete decision algorithms. An algorithm project uses that SDK
to implement feature computation, composition, training hooks, and the typed decision contract, then produces a
versioned JAR. The JAR is loaded by Hotvect's offline and online runtimes; it is not a deployed service by itself.

The same package can participate in local development, batch training, backtests, and online serving. This reuse is one
of Hotvect's core parity mechanisms, but each execution environment still owns its inputs, infrastructure, and failure
behavior.

## What the package contains

A normal algorithm JAR contains:

- one or more embedded algorithm-definition JSON resources;
- Java implementations of factories, algorithms, transformers, encoders, decoders, generators, and reward functions;
- the public request and response contract used by its containing application;
- `hotvect-core` and any backend modules the implementation directly uses;
- algorithm-owned resources such as schemas or vocabularies.

One JAR may expose several named algorithms. A composite definition names its child dependencies, and those children
retain their own algorithm and parameter identities.

The embedded definition connects the logical algorithm name to its factory classes, child dependencies, data
requirements, workflow configuration, and runtime parameters. Read the
[algorithm-definition reference](../../reference/algorithm-definition/index.md) for the exact fields.

## What the package does not contain

An algorithm package does not own:

- a production HTTP endpoint;
- authentication, traffic routing, retries, or request admission;
- EMS persistence or experiment APIs;
- selection of the active production variant;
- PostgreSQL configuration;
- the artifact-publication workflow;
- the parameter ZIP produced by a later training run.

In particular, the EMS server implementation does not belong in an algorithm project. It is built and deployed from
the dedicated [EMS repository](../experiment-management-service/index.md).

## Dependency ownership

Algorithm and runtime dependencies intentionally meet at a classloader boundary.

| Dependency kind | Typical Maven scope in an algorithm project | Packaged in the algorithm JAR? |
| --- | --- | --- |
| Hotvect public API and runtime-owned utility classes | `provided` | No |
| Offline-only runner contracts used by algorithm code | `provided` | No |
| `hotvect-core` | compile/runtime | Yes, when used |
| Backend modules such as `hotvect-catboost`, `hotvect-tensorflow`, or Java `hotvect-python` | compile/runtime | Yes, when used |
| Algorithm-specific libraries | compile/runtime | Yes, unless explicitly owned by the host |
| EMS server | Not a dependency | No |

Some existing algorithm projects compile against `hotvect-online-util` or `hotvect-offline-util` with `provided`
scope because their implementation uses runtime-supplied concurrency, native-model, or workflow helpers. That does not
turn the algorithm into a serving application and does not embed EMS. The runner or containing application must supply
the matching runtime classes.

Use the same Hotvect release for the algorithm package and its runtime when possible. Inspect the shaded JAR rather than
assuming dependency scopes produced the intended contents. See
[Algorithm JAR loading and class ownership](../../concepts/jar-loading/index.md).

## Build and publication lifecycle

```mermaid
flowchart LR
    source["Algorithm source"] --> test["Tests"]
    test --> package["Build shaded JAR"]
    package --> publish["Publish immutable algorithm version"]
    publish --> offline["Train or backtest"]
    offline --> parameters["Parameter ZIP"]
    parameters --> register["Publish and register runtime identity"]
```

The algorithm CI pipeline normally publishes the JAR to a package repository and an artifact location used by the
runtime. Training is a separate operation that consumes the JAR and produces a parameter package. Release automation
then registers exact immutable locations for both artifacts.

Do not overwrite published bytes under an existing algorithm ID. Online repositories cache factories by algorithm ID,
so changing bytes without changing identity can leave a process serving the earlier package.

## Contract with the containing application

The containing application supplies the runtime, calls the algorithm's typed decision interface, and translates between
its transport models and the algorithm's request and response types. It may also bind application-owned capabilities,
such as a feature-store adapter, under dependency names declared by the algorithm.

Those bindings do not transfer infrastructure ownership into the algorithm package. The application still owns network
clients, credentials, timeouts, batching, observability, and shutdown.

Continue with [Embed Hotvect in a Java application](../../guides/application-integration/index.md) for that integration
boundary, or [Offline workflows](../offline-workflow-runtime/index.md) for training and evaluation.
