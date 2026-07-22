---
title: Serve and integrate
description: Embed Hotvect in an application and use its local debugging and runtime compatibility surfaces
tags: [serving, integration, online, debugging]
difficulty: intermediate
related_docs:
  - ../../architecture/online-runtime/index.md
  - ../../architecture/runtime-topologies/index.md
  - ../../reference/cli/index.md
  - ../local-algorithm-debugging/index.md
  - ../../design/direct-python-workers/index.md
  - ../../concepts/jar-loading/index.md
---

# Serve and integrate

Hotvect is designed to be embedded as the algorithm runtime inside a containing serving application. This section
groups the released loading, compatibility, and local-debugging surfaces for that integration. The current `hv serve`
and `hv worker serve` commands are local debugging tools; they are not production hosting products.

## Choose the narrowest path

<div class="grid cards" markdown>

-   **Embed the online runtime**

    Load a local JAR directly or use the versioned repository from a containing Java application.

    [Open the Java integration guide](../application-integration/index.md){ .hv-btn }
    [Understand the online runtime](../../architecture/online-runtime/index.md){ .hv-btn }

-   **Run a full algorithm locally**

    Exercise a complete algorithm against recorded examples and inspect its HTTP result or browser debugger.

    [Open browser debugging](../local-algorithm-debugging/index.md){ .hv-btn }

-   **Debug a Python worker**

    Run only the configured worker runtime when you need to isolate worker transport or model behavior.

    [Open direct Python workers](../../design/direct-python-workers/index.md){ .hv-btn }

-   **Check loading and compatibility**

    Verify which classes belong to the algorithm JAR versus the runtime before mixing versions or embedding Hotvect.

    [Open JAR loading](../../concepts/jar-loading/index.md){ .hv-btn }
    [Open runtime compatibility](../../reference/version-compatibility/index.md){ .hv-btn }

</div>

## Understand placement before integration

Hotvect currently supports in-JVM composition, managed local Python workers, host-provided external objects, and remote
offline jobs. It does not provide generic declarative remote placement for arbitrary child algorithms.

[Review runtime topologies](../../architecture/runtime-topologies/index.md) before describing an integration as remote
or distributed.

## Keep offline work separate

Training, backtests, and other remote batch jobs belong under [Remote execution](../sagemaker-backtests/index.md).
They share algorithm artifacts with online execution, but they have different inputs, failure modes, and operational
contracts.
