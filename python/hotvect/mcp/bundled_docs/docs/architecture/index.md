---
title: Architecture overview
description: How Hotvect turns an algorithm project into offline evidence and a callable application component
tags: [architecture, modules, runtime, lifecycle]
---

# Architecture overview

Hotvect is a framework inside an algorithm project and a containing application—not a separate prediction service.
An algorithm author implements a typed decision contract and builds an algorithm package. Offline tooling can train or
generate state, build a separate parameter package, and evaluate the result. A Java application then loads the same
packages and calls the resulting algorithm object.

The easiest way to understand the architecture is to keep three activities separate:

| Activity | Runs when | Produces or owns |
| --- | --- | --- |
| **Build the algorithm** | Compile time | Algorithm package with implementation, embedded definition, and packaged assets |
| **Prepare and evaluate it** | Offline, before release | Optional parameter package, predictions, evaluation results, and performance evidence |
| **Execute decisions** | In a job or containing application | A loaded `AlgorithmInstance` called through a public algorithm shape |

The Python workflow coordinates offline work. It is not part of the request-time call path, and it does not replace the
Java runtime embedded by an application.

## System architecture at a glance

<div class="hv-system-map" aria-label="Hotvect system architecture across offline, control, and online planes">
  <section class="hv-system-map__plane">
    <header class="hv-system-map__header">
      <span>01</span>
      <div><strong>Offline plane</strong><small>build · train · evaluate</small></div>
    </header>
    <div class="hv-system-map__node">
      <strong>Algorithm project</strong>
      <small>code · embedded definition · explicit override</small>
    </div>
    <div class="hv-system-map__down">↓</div>
    <div class="hv-system-map__node hv-system-map__node--accent">
      <strong>Hotvect offline workflow</strong>
      <small>Python orchestration · JVM stages</small>
    </div>
    <div class="hv-system-map__down">↓</div>
    <div class="hv-system-map__node">
      <strong>Developer-chosen training</strong>
      <small>JVM · Python · native ML tools</small>
    </div>
    <div class="hv-system-map__output">
      <span>OUTPUT</span>
      <strong>packages + evidence</strong>
    </div>
  </section>

  <div class="hv-system-map__handoff"><span>publish + register</span><b>→</b></div>

  <section class="hv-system-map__plane hv-system-map__plane--control">
    <header class="hv-system-map__header">
      <span>02</span>
      <div><strong>Artifact + experiment control plane</strong><small>optional release · select · inspect</small></div>
    </header>
    <div class="hv-system-map__node">
      <strong>Trusted artifact store</strong>
      <small>algorithm package · parameter package</small>
    </div>
    <div class="hv-system-map__down">↕</div>
    <div class="hv-system-map__node hv-system-map__node--accent">
      <span class="hv-system-map__badge">EXTERNAL TODAY</span>
      <strong>Experiment Management Service (EMS)</strong>
      <small>slots · variants · experiments · package metadata</small>
    </div>
    <div class="hv-system-map__down">↓</div>
    <div class="hv-system-map__node">
      <strong><code>hv-exp</code> inspection</strong>
      <small>read-only configuration and online results</small>
    </div>
    <div class="hv-system-map__output">
      <span>OUTPUT</span>
      <strong>selected package identities</strong>
    </div>
  </section>

  <div class="hv-system-map__handoff"><span>optional select + load</span><b>→</b></div>

  <section class="hv-system-map__plane hv-system-map__plane--online">
    <header class="hv-system-map__header">
      <span>03</span>
      <div><strong>Online application plane</strong><small>contain · assemble · decide</small></div>
    </header>
    <div class="hv-system-map__node">
      <strong>Containing application</strong>
      <small>request boundary · runtime selection</small>
    </div>
    <div class="hv-system-map__down">↓</div>
    <div class="hv-system-map__node hv-system-map__node--accent">
      <strong>Artifact loader</strong>
      <small>direct files or EMS-selected repository</small>
    </div>
    <div class="hv-system-map__down">↓</div>
    <div class="hv-system-map__node">
      <strong><code>AlgorithmInstance</code></strong>
      <small>typed request → decision</small>
    </div>
    <div class="hv-system-map__runtimes">
      <div><strong>JVM components</strong><small>features · rules · models</small></div>
      <div><strong>Python / native workers</strong><small>explicit optional integration</small></div>
    </div>
  </section>
</div>

The offline plane produces packages and evidence but does not serve requests. An organization may publish packages
through its own path and keep selection metadata in an external EMS; `hv-exp` only reads that state. The online plane
is always the containing application. It can load already selected local artifacts directly, or resolve an EMS
assignment through the repository, then construct an `AlgorithmInstance`. It may invoke explicit Python or native
integrations behind that instance. When EMS is used, EMS and the artifact store are not in the per-request scoring path
after the serving snapshot is loaded.

## One algorithm, from source to decision

<div class="hv-flow">
  <div class="hv-flow__step"><span>01</span><strong>Implement</strong><small>contract · factories</small></div>
  <div class="hv-flow__step"><span>02</span><strong>Package algorithm</strong><small>implementation · definition</small></div>
  <div class="hv-flow__step"><span>03</span><strong>Train or generate</strong><small>model · state</small></div>
  <div class="hv-flow__step"><span>04</span><strong>Package parameters</strong><small>versioned state</small></div>
  <div class="hv-flow__step"><span>05</span><strong>Execute</strong><small>request → decision</small></div>
</div>

### 1. Implement the decision contract

The public API describes *what decision is made*. A ranker, for example, accepts a
`RankingRequest<SHARED, ACTION>` and returns a `RankingResponse<ACTION>`. The algorithm project supplies the domain
types, feature logic, model integration, rules, and final decision policy behind that boundary.

Factories are the construction boundary. Their inputs depend on the factory family: a simple factory receives
definition configuration, while parameterized and composite forms can additionally receive an `ExecutionContext`,
parameter streams, or named child algorithms. This keeps object construction out of the request loop and makes the
same logical algorithm loadable by offline runners and containing applications.

### 2. Build the algorithm package

The algorithm package contains:

- algorithm and factory classes;
- the `<algorithm-name>-algorithm-definition.json` resource;
- feature-transformer implementations, including any code generated by `hotvect-processor`;
- the Hotvect implementation and backend modules that the algorithm uses.

The definition selects factories and describes configuration, children, and offline workflow settings. It does not
contain an alternative implementation: every named factory must be implemented by a class available to the algorithm
classloader.

The current package format is a JVM JAR. The containing runtime owns `hotvect-api` and the loader. The algorithm JAR
normally treats `hotvect-api` as a provided dependency while packaging implementation modules such as `hotvect-core`
and its selected backends. It can also package assets and integrations used by Python or native inference code. This
division lets the host and loaded object meet at one public contract.

### 3. Prepare parameters and evidence offline

Some algorithms need a trained model or generated state; others are parameterless. For a parameterized algorithm, the
offline lifecycle turns source data and the algorithm definition into a parameter package. That package has its own
identity and can contain model files, generated state, metadata, or packaged child artifacts. Its current physical
format is a ZIP.

The Python package plans and coordinates this lifecycle. JVM tasks load the algorithm package to decode examples, generate
state, transform and encode data, predict, and run performance tests. A declared training command may run between
encoding and prediction. Local and remote jobs use different infrastructure, but they operate on the same artifact
contract.

Predictions, metrics, logs, and manifests are evidence about a particular algorithm package, effective definition,
parameter package, input, and execution context. They are not additional deployable algorithms.

### 4. Assemble a runtime instance

A runner or containing application supplies:

1. an algorithm package;
2. a parameter package when that execution surface requires one;
3. any explicit definition override;
4. named application-provided dependencies;
5. an `ExecutionContext` such as `REALTIME` plus `ONLINE`.

`AlgorithmInstanceFactory` reads the embedded definition, constructs declared children, applies named dependency
bindings, opens parameter streams, and calls the selected factories. The result is an `AlgorithmInstance`: the resolved
definition, parameter metadata, and callable algorithm object.

The repository-based online path adds artifact download and reuse. In the current implementation,
`AlgorithmRepository` downloads JAR and ZIP files, caches JAR factories by algorithm ID, and caches live instances by
algorithm ID plus parameter ID. Direct factory use is the smaller path when the application already has local files.

### 5. Execute inside the environment that owns the request

The containing application adapts its input to the algorithm's public request type and calls `rank`, `score`, `apply`,
or `applyAsDouble`, according to the selected shape. Hotvect returns decision objects; the application decides how
those objects become an HTTP response, event, or downstream call.

Hotvect does not provide the surrounding production server. The application owns transport, authentication,
authorization, request validation, traffic policy, concurrency, failure mapping, and service-level observability. The
algorithm owns its decision behavior and must satisfy the application's concurrency and latency contract.

## Composition is separate from placement

A composite definition names child algorithms. By default, the loader constructs those children in the same JVM and
passes them to the parent factory. A containing application can also bind a named child to an application-owned
`AlgorithmInstance`, such as a client object implementing the expected algorithm interface.

That binding mechanism separates the logical graph from how one dependency was obtained, but it is not generic remote
execution. The host-provided object owns any transport, authentication, timeout, retry, and shutdown behavior. Managed
local Python workers and remote offline jobs are other explicit topologies with their own contracts.

Read [Runtime topologies](runtime-topologies/index.md) before making a placement or distribution claim.

## Ownership and trust boundaries

| Owner | Responsibilities |
| --- | --- |
| Algorithm project | Decision code, embedded definition, implementation dependencies, parameter contract, algorithm-level resource cleanup |
| Offline workflow | Input selection, stage planning, training or state generation, artifact packaging, and evidence |
| Containing application | Artifact selection, host-provided dependencies, request adaptation, operational controls, and application lifecycle |
| Hotvect runtime | Public contracts, definition loading, factory invocation, parameter stream delivery, and instance construction |

An algorithm package is executable code running with the host JVM's privileges. The current JAR classloader controls
class and resource resolution; it is not a security sandbox. The downloader checks that files exist. Repository loading
also checks the JAR definition identity and selected parameter ID; matching the parameter metadata's algorithm version
requires strict algorithm-version checking. None of these checks verifies a cryptographic signature or content digest.
The containing application must obtain algorithm and parameter packages through a trusted, access-controlled
publication path and add any required integrity verification before loading them.

Resource ownership also depends on the loading path. A caller that directly creates an `AlgorithmInstance` closes that
instance when finished. `AlgorithmRepository` reuses live instances and closes their outer algorithms after the
instances become unreachable. Application-provided dependency bindings remain application-owned; closing an outer
instance does not generically close every child or external binding.

## Module map

| Area | Modules or package | Responsibility |
| --- | --- | --- |
| Public contracts | `hotvect-api` | Algorithm shapes, request and decision types, factories, execution context, identity |
| Algorithm implementation | `hotvect-core`, `hotvect-processor` | Runtime feature transformation and compile-time generated transformers |
| Algorithm backends | `hotvect-catboost`, `hotvect-tensorflow`, Java `hotvect-python` | Backend-specific encoding, scoring, and managed Python workers |
| Runtime assembly | `hotvect-online-util` | Dynamic loading, parameter resolution, dependency binding, EMS selection, instance repository |
| Offline execution | `hotvect-offline-util` | Audit, encode, predict, performance test, and state-generation JVM tasks |
| Offline orchestration | Python package `hotvect` | Train and backtest planning, dependency preparation, caching, remote submission, result bookkeeping |
| Local debugging | `hotvect-algorithm-serve`, `hotvect-algorithm-demo` | Local HTTP and browser surfaces for exercising an algorithm |

Java `hotvect-python` is a runtime backend module. It is distinct from the Python orchestration package named
`hotvect`.

## Continue by concern

<div class="grid cards" markdown>

-   **Application integration**

    Load a local artifact directly or use the repository path from a containing Java application.

    [Embed Hotvect in Java](../guides/application-integration/index.md){ .hv-btn }

-   **Online runtime**

    Follow metadata resolution, download, binding, caching, execution, and cleanup.

    [Read online runtime](online-runtime/index.md){ .hv-btn }

-   **Offline lifecycle**

    See how orchestration and JVM tasks produce parameters, predictions, and evidence.

    [Read offline lifecycle](offline-lifecycle/index.md){ .hv-btn }

-   **Artifacts and identity**

    Understand which identity belongs to code, configuration, parameters, and a loaded instance.

    [Read artifacts and identity](../concepts/artifacts-and-identity/index.md){ .hv-btn }

-   **Configuration and experiments**

    Connect effective definitions and parameter releases to EMS slots, variants, assignment, and inspection.

    [Read configuration and experimentation](../concepts/configuration-and-experimentation/index.md){ .hv-btn }

-   **Current boundaries**

    Separate available capabilities from bounded integrations and longer-term direction.

    [Read status and direction](status-and-direction/index.md){ .hv-btn }

</div>
