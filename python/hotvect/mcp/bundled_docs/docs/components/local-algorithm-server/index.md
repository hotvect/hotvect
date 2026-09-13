---
title: Local algorithm server and debugger
description: What hotvect-algorithm-serve and hv algorithm serve provide, their artifact and EMS modes, and why they are not production hosting
tags: [components, local, server, debugging, hv-serve]
related_docs:
  - ../../guides/local-algorithm-debugging/index.md
  - ../../architecture/online-runtime/index.md
  - ../ems-runtime-client/index.md
  - ../../reference/cli/index.md
---

# Local algorithm server and debugger

`hotvect-algorithm-serve` exposes a loaded Hotvect algorithm over a small HTTP API. The Python `hv algorithm serve` command starts
that JVM server and optionally adds recorded examples, display metadata, and the browser debugger.

This is a development component. It is not Hotvect's production hosting model.

## Runtime modes

| Mode | Inputs | Selection behavior |
| --- | --- | --- |
| Single local runtime | Algorithm JAR, algorithm name, parameter ZIP, optional definition override | Every request uses that runtime |
| Multiple local runtimes | Local runtime configuration listing artifacts | Caller selects a configured runtime ID |
| EMS-backed debug runtime | EMS URL, slot, assignment key, credentials, scratch directory | Uses the online runtime client to refresh and select a variant locally |

Local artifact mode and EMS mode are mutually exclusive. EMS mode still runs the selected algorithm inside the local
server process; it does not proxy prediction requests to EMS.

The current local server does not configure a runtime-local state root. An algorithm that declares
`requires_local_state_storage: true` must be exercised in a containing runtime that supplies that capability.

## HTTP contract

The headless server exposes:

- `GET /health` and `GET /api/health`;
- `GET /api/metadata` and `GET /api/config`;
- `POST /predict`.

The response includes the selected algorithm, parameter, and runtime identities. EMS mode also includes variant identity
and current control-plane metadata.

`POST /predict` accepts one JSON object in the format handled by the selected definition's
`decoder_factory_classname` and `test_decoder_parameters`. The decoder must return exactly one example with a nonblank
example ID. The server dispatches only `Ranker` and `TopK`/`ThemedTopK` algorithms.

```bash
curl -sS \
  -H 'content-type: application/json' \
  --data-binary @example.json \
  'http://127.0.0.1:12000/predict' | jq .
```

A ranker response is a serving projection in final rank order:

```json
{
  "type": "ranker",
  "example_id": "example-001",
  "decisions": [
    {
      "rank": 0,
      "action_id": "candidate-b",
      "score": 0.91,
      "additional_properties": {}
    }
  ],
  "additional_properties": {},
  "algorithm_id": "example-ranker@1.2.0",
  "parameter_id": "parameter-001",
  "algorithm_runtime_id": "example-ranker@1.2.0@parameter-001"
}
```

TopK uses `type: "topk"`. A `ThemedTopKResponse` uses `type: "themed_topk"` and also returns
`action_list_id` and `action_list_metadata`. Decisions include `probability` when the algorithm supplies it. Optional
action names and image URLs come from decision/action properties first and the UI action-metadata lookup second.

This is not the offline `hv algorithm predict` schema: offline ranking rows use `result` aligned to request-action order and
carry final position in each `rank`. See
[Ranking and prediction contracts](../../reference/ranking-and-prediction-contracts/index.md).

In multiple-local-runtime mode, pass `algorithm_runtime_id` as a query parameter. Without it, the server selects the
first runtime after sorting the loaded runtime IDs lexicographically; it does not preserve configuration-file order as
a default policy. An unknown ID returns a contract error listing the available IDs. EMS mode rejects explicit runtime
ID selection because the configured assignment key selects the variant.

`GET /api/metadata` and its `/api/config` alias expose the selected runtime identity, effective framework provenance,
parameter time metadata, all loaded local runtimes, and action-metadata status. EMS mode additionally exposes its slot,
assignment key, refresh settings, current shard count, snapshot update time, and selected variant attributes.

The request body is buffered up to `--max-request-mib` (`256` by default, valid range `1..512`). Error responses use:

```json
{
  "error": {
    "message": "Request body must be a JSON object",
    "details": null
  }
}
```

Invalid JSON and algorithm contract violations return `400`, an oversized body returns `413`, and an unhandled
algorithm failure returns `500`. This debug server does not define an application-specific production error contract.

With `--ui`, the demo extension adds a browser for loading, editing, running, and comparing recorded examples.

## What it is useful for

Use the local server to:

- prove that a selected JAR and parameter ZIP load together;
- exercise raw example decoding and algorithm execution;
- inspect runtime identity and output metadata;
- compare more than one local runtime;
- reproduce the EMS client path against a configured non-production environment;
- connect another local process to a stable HTTP debugging boundary.

## What it does not prove

A successful `hv algorithm serve` run does not prove that a production containing application has correct request adaptation,
host-provided dependencies, concurrency, authentication, traffic policy, or failure behavior.

The server defaults to loopback and does not provide the production access controls, rollout integration, or
operational contract of an application-owned serving API. Binding it to another interface exposes an unauthenticated
debugging surface and should be limited to an isolated development environment.

Production use embeds [the online runtime](../../architecture/online-runtime/index.md) in the containing Java application.

Continue with [Debug an algorithm in the browser](../../guides/local-algorithm-debugging/index.md) for the walkthrough.
