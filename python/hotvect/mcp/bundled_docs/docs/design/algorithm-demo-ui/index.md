---
title: Algorithm Demo UI
description: Design notes for the local browser UI used to run an algorithm JAR against offline examples
tags: [design, demo-ui, debugging, offline]
difficulty: intermediate
estimated_time: 10 minutes
related_docs:
  - ../../reference/cli/index.md
  - ../../guides/quickstart/index.md
---

# Algorithm Demo UI

Hotvect includes a **local-only** browser UI that lets you run an algorithm JAR against recorded offline examples and
inspect the result interactively.

This is intended for debugging and iteration, not for production serving.

## What it does (implemented)

- Loads an algorithm definition from the algorithm JAR (`--algorithm-name`) and applies an optional override JSON.
- Loads demo examples from a directory (`--source-path`) containing JSONL/JSON files (gzip supported).
- Uses the algorithm's **test decoder** (`decoder_factory_classname` + `test_decoder_parameters`) to decode each example.
- Runs the algorithm and renders:
  - the structured response (decisions, scores, additional_properties)
  - an optional “cards” view using external action metadata (`--action-metadata-path`)

Supported algorithm interfaces:

- `Ranker` (ranking)
- `TopK` / `ThemedTopK`

## Strict contracts (fail-fast)

The demo UI is intentionally strict (no heuristics, no best-effort fallbacks):

- Example files must be valid JSON objects per line (whatever the algorithm’s test decoder expects).
- The decoder must return **exactly one** example for a case.
- Every returned decision must have a **non-empty** `action_id`.
- If `--action-metadata-path` is provided:
  - every action metadata record must have non-empty `action_id`, `action_name`, `action_image_url`
  - every `action_id` returned by the algorithm must exist in the metadata store

## Inputs

### Examples (`--source-path`)

Directory scanned recursively for:

- `*.jsonl`, `*.json`
- `*.jsonl.gz`, `*.json.gz`

The UI lists up to a bounded number of examples (for fast startup) and fetches the raw JSON for a selected case on
demand.

### Action metadata (`--action-metadata-path`, optional)

Directory scanned recursively for action metadata files, including:

- `*.jsonl`, `*.json`
- Spark-style `part-*` files (with or without `.json`)
- gzip variants (e.g. `*.jsonl.gz`, `part-00000.json.gz`)

These records are keyed by `action_id` and are only used for UI rendering; they are not inferred from algorithm
outputs.

**Required fields**:

- `action_id`
- `action_name`
- `action_image_url`

Any additional fields are treated as opaque JSON and displayed generically.

#### Action details view (implemented)

If action metadata is enabled, clicking a result card opens an “action details” view (PDP-like):

- Left: image (from `action_image_url`)
- Right: generic JSON tree view of the full action metadata record

For debugging, the server exposes:

- `GET /api/action-metadata/{action_id}` → returns the full action metadata JSON object for that id.

## JSON-in-string support (implemented)

Some production examples contain JSON objects encoded as strings (for example `"data": "{...}"`).

The demo UI automatically detects string values that look like JSON and exposes a **virtual** sibling field:

- `<field>__json` (example: `data__json`)

When you run the case, the UI collapses `<field>__json` back into the original string field before decoding the
example. The virtual `__json` fields are removed before decode/run (UI-only).

## Execution and packaging

The demo UI lives in the Java module `hotvect-algorithm-demo` and is packaged as a fat JAR:

- `hotvect-algorithm-demo-*-jar-with-dependencies.jar`

There are two ways to run it:

1) **Unified CLI wrapper**: `hv serve --ui ...`
   - This launches the full algorithm server and enables the browser UI on the same process.
   - The same backend also exposes `GET /health` and `POST /predict`.

2) **Direct Java invocation**: run `com.hotvect.algorithmdemo.Main` yourself
   - This exposes the same server directly, including `--ui`, `--demo-sqlite-path`, and `--max-request-mib`.

## SQLite cache (implemented)

To avoid holding large example/metadata datasets in memory, the demo UI uses a local SQLite cache DB:

- default: deterministic path under `/tmp/hv-demo-ui-sqlite-cache/` (derived from inputs)
- override: `--demo-sqlite-path /path/to/demo.db`
