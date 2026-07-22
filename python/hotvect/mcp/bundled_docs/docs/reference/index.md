---
title: Hotvect reference
description: Exact command, API, configuration, schema, compatibility, and troubleshooting contracts
tags: [reference, cli, java, configuration]
---

# Hotvect reference

Reference pages describe exact interfaces and supported fields. Start with [Concepts](../concepts/index.md) when you
need the mental model, or [Build and run](../guides/index.md) when you need a task workflow.

<div class="grid cards" markdown>

-   **Glossary**

    Concise definitions of algorithm shapes, artifacts, identities, lifecycle stages, and runtime terms.

    [Open the glossary](glossary/index.md){ .hv-btn }

-   **Command-line interfaces**

    Commands, flags, outputs, and remote-execution behavior for `hv`, `hv-ext`, and `hv-exp`.

    [Open CLI reference](cli/index.md){ .hv-btn }

-   **Java API**

    Public modules, algorithm shapes, factories, instances, execution context, data contracts, and Javadocs.

    [Open Java API map](java-api/index.md){ .hv-btn }

-   **Python EMS client**

    Typed read and mutation methods for an external Experiment Management Service, with explicit authorization
    boundaries.

    [Open Python EMS client](python-ems-client/index.md){ .hv-btn }

-   **Algorithm definition**

    Definition fields, override semantics, runtime settings, worker configuration, and dependency overrides.

    [Open definition contract](algorithm-definition/index.md){ .hv-btn }

-   **Configuration**

    User-level CLI configuration and environment variables.

    [Open configuration reference](config/index.md){ .hv-btn }

-   **Compatibility**

    Framework, algorithm package, parameter package, Java, and training-image compatibility boundaries.

    [Open compatibility reference](version-compatibility/index.md){ .hv-btn }

-   **Generated transformer backends**

    CatBoost and TensorFlow feature type contracts and custom-backend extension points.

    [Open backend reference](generated-transformer-backends/index.md){ .hv-btn }

</div>

## Find an answer

- Start with [FAQ](faq/index.md) for recurring setup, data, artifact, caching, and composition questions.
- Use [Troubleshooting](troubleshooting/index.md) for concrete failure signals and log locations.
- Use `hv docs search "<question>"` to search the documentation bundled with the installed runtime.
