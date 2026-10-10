---
title: Local algorithm server and debugger
description: How hotvect-algorithm-demo loads local runtimes and executes recorded offline examples
tags: [components, local, server, debugging, hv-algorithm-serve]
related_docs:
  - ../../guides/local-algorithm-debugging/index.md
  - ../../architecture/online-runtime/index.md
  - ../ems-runtime-client/index.md
  - ../../reference/cli/index.md
---

# Local algorithm server and debugger

The Python `hv algorithm serve` command launches `hotvect-algorithm-demo`. The demo owns its local HTTP lifecycle, algorithm
loading, runtime identity, recorded offline-example decoding, algorithm execution, comparison projections, action
display metadata, and optional browser UI.

This is a development component. It is not Hotvect's production request contract.

## Runtime modes

| Mode | Inputs | Selection behavior |
| --- | --- | --- |
| Single local runtime | Algorithm JAR, algorithm name, parameter ZIP, optional definition override | Every request uses that runtime |
| Multiple local runtimes | Local runtime configuration listing artifacts | Caller selects a configured runtime ID |

The local runtime supplies lazy runtime-local state beneath the system temporary directory's `algorithm-state`
directory.

## Module boundary

`hotvect-algorithm-demo` exposes:

- `GET /health`;
- `GET /api/metadata`;
- local runtime selection through `SelectedRuntime` for demo extensions;
- `POST /predict` for one decoder-runnable offline example;
- recorded-example and comparison routes;
- action metadata and JSON-in-string editing;
- the optional browser UI.

The demo runtime owns the selected `AlgorithmGraph` and exposes its root `AlgorithmInstance` while that graph remains
alive. The demo constructs the algorithm's `ExampleDecoder` itself. The server does not depend on offline `Example`,
`OfflineRequest`, or outcome types.

## What it is useful for

Use `hv algorithm serve` to:

- prove that a selected JAR and parameter ZIP load together;
- exercise recorded example decoding and algorithm execution;
- inspect runtime identity and output metadata;
- compare more than one local runtime.

## What it does not prove

A successful `hv algorithm serve` run does not prove that a production request codec, host-provided dependencies,
concurrency, authentication, traffic policy, or failure behavior are correct.

The debugger defaults to loopback and does not provide a production access-control or operational contract. Binding it
to another interface exposes an unauthenticated development surface.

Continue with [Debug an algorithm in the browser](../../guides/local-algorithm-debugging/index.md) for the walkthrough.
