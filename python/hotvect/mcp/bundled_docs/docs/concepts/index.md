---
title: Hotvect concepts
description: Stable concepts behind Hotvect decision systems, including components, packages, configuration, experimentation, execution, and feature computation
tags: [concepts, algorithms, architecture, configuration, experimentation]
---

# Hotvect concepts

Hotvect lets a team implement, configure, train, evaluate, select, and load a complete decision system under one
explicit interface and package definition. In Hotvect, that decision system is called an **algorithm**. It can include
typed request handling, feature calculation, models, rules, child algorithms, and the final ranking or selection rule.

If Hotvect is new to you, do not begin with the CLI reference. Install Hotvect and run the product example first, then
use this section to understand what you just executed.

## The mental model in one minute

<div class="hv-flow">
  <div class="hv-flow__step"><span>01</span><strong>Define input and result</strong><small>request · decision</small></div>
  <div class="hv-flow__step"><span>02</span><strong>Implement behavior</strong><small>features · selection rules</small></div>
  <div class="hv-flow__step"><span>03</span><strong>Package the implementation</strong><small>code · definition</small></div>
  <div class="hv-flow__step"><span>04</span><strong>Train and save state</strong><small>model · generated data · parameters</small></div>
  <div class="hv-flow__step"><span>05</span><strong>Select and run it</strong><small>version · variant · decision</small></div>
</div>

An **algorithm package** is the versioned implementation package: executable code, an embedded definition, and any
packaged assets. A training or preparation run may add a separate **parameter package** containing trained model files
or generated runtime data. Tools and host applications load the selected packages to construct an `AlgorithmInstance`,
then call its public decision interface.

These names describe responsibilities rather than file extensions. The current algorithm-package format is a JVM JAR,
which can also carry assets and integrations for Python or native runtimes. The current parameter-package format is a
ZIP. Pages about building, loading, or inspecting those files use the concrete JAR and ZIP terms.

The same packages can participate in offline and online paths, but the environments are not identical. File decoders,
application adapters, runtime settings, configuration selection, and host-provided connections remain explicit.

## Recommended reading order

<div class="grid cards" markdown>

-   **1. How Hotvect works**

    Follow one algorithm from source and definition through parameter preparation and application loading.

    [Take the system tour](how-hotvect-works/index.md){ .hv-btn }

-   **2. Requests, decisions, and examples**

    Learn the public data model: shared context, actions, stable IDs, decisions, outcomes, and offline examples.

    [Learn the data model](data-model/index.md){ .hv-btn }

-   **3. Complete algorithms**

    Learn the exact Hotvect term for a decision system: its public interface, runtime behavior, definition, parameters,
    and child algorithms.

    [Read complete algorithms](complete-algorithm/index.md){ .hv-btn }

-   **4. Dependencies and bindings**

    Distinguish child algorithms, injected algorithms, feature and data dependencies, and connections supplied by the
    host application or environment.

    [Read dependencies and bindings](dependencies-and-bindings/index.md){ .hv-btn }

-   **5. Feature computation**

    See how request, candidate, interaction, feature-store, and injected-algorithm values enter generated transformers.

    [Read feature computation](feature-computation/index.md){ .hv-btn }

-   **6. Packages and identity**

    Follow the implementation package, embedded definition, optional parameter package, and the identifiers that
    distinguish one runtime instance from another.

    [Read artifacts and identity](artifacts-and-identity/index.md){ .hv-btn }

-   **7. Configuration and experimentation**

    Distinguish embedded and effective configuration, trained parameters, released runtimes, slots, experiments, and
    variant assignment.

    [Read configuration and experimentation](configuration-and-experimentation/index.md){ .hv-btn }

-   **8. Where it runs**

    Keep live versus batch workload behavior separate from request versus file-based input semantics.

    [Read execution contexts](execution-contexts/index.md){ .hv-btn }

</div>

## Put the concepts into practice

Use one consistent path:

1. If you have not already, [run the example product algorithms](../guides/first-run/index.md) to see one implementation
   package accept a request, rank products, and return a response without external data.
2. Read [How Hotvect works](how-hotvect-works/index.md) and
   [Requests, decisions, and examples](data-model/index.md).
3. Choose your branch: [build a first algorithm](../guides/first-algorithm/index.md), or
   [tour an existing algorithm](../guides/first-workflow/index.md).

## Find a concept by question

| Question | Continue with |
| --- | --- |
| Why is a Hotvect algorithm broader than a model? | [Why Hotvect](motivation/index.md) |
| What do unfamiliar Hotvect terms mean? | [Glossary](../reference/glossary/index.md) |
| How do the framework modules fit together? | [Architecture overview](../architecture/index.md) |
| How are feature values identified? | [Namespace identity](namespaces/index.md) |
| How is an algorithm JAR isolated and loaded? | [Algorithm JAR loading](jar-loading/index.md) |
| How do configuration, parameters, and experiment variants relate? | [Configuration and experimentation](configuration-and-experimentation/index.md) |
| What fields are valid in the definition? | [Algorithm definition reference](../reference/algorithm-definition/index.md) |
| Which exact command should I run? | [CLI reference](../reference/cli/index.md) |
| What is available today versus directional? | [Status and direction](../architecture/status-and-direction/index.md) |

## Boundaries

Hotvect owns how a decision system is connected, configured, packaged, selected, and run. The current release consumes
experiment state from an external Experiment Management Service (EMS) server; bringing that control plane into Hotvect is directional. Hotvect does not
replace data orchestration, model libraries, a feature store, or production monitoring. Those systems can surround or
satisfy dependencies of a Hotvect algorithm while retaining their own operational interfaces.
