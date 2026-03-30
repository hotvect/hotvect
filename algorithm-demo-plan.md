# Hotvect Algorithm Demo UI Plan

## Context

We want a local-only tool for algorithm developers to run Hotvect algorithms and inspect results in a browser UI.

Constraints:
- Generic (no domain-specific code or schemas in Hotvect).
- Strict (no heuristics, no best-effort fallbacks; fail-fast on contract violations).
- Works across algorithm repos by pointing at an algorithm JAR + parameters + recorded examples.

## Goal (MVP)

Provide a browser UI that:
- Loads a set of examples from disk (`*.jsonl`/`*.json` and gz variants).
- Lets a user find/select a case via a grep-like filter.
- Runs the selected case through the algorithm (TopK / ThemedTopK / Ranker).
- Renders a grid of result cards with images.
- Shows the raw JSON response (or error) in a single panel.

## Non-Goals

- Domain UI (no dedicated “search query” fields, no campaign-specific rendering).
- Manual full-request JSON authoring in the UI.
- Any form of heuristic extraction (e.g. guessing which field contains an image URL).
- Supporting BulkScorer directly (MVP supports Ranker / TopK / ThemedTopK only).

## Module / Tech Stack

- New Java module in this repo (proposed name: `hotvect-algorithm-demo`), depending on `hotvect-online-util`.
- Java 21.
- Embedded web server: Javalin 7 (Jetty 12 baseline).
- Static frontend served from the JAR (no Node build step).

## Inputs (Examples)

### Source
- A folder containing line-delimited JSON files, scanned recursively at startup:
  - `*.jsonl`, `*.json`, `*.jsonl.gz`, `*.json.gz`
- Examples and action metadata are cached in a local SQLite DB to avoid holding large datasets in RAM.

### Example Format
- Each line is a single JSON object representing an Example in the format understood by the algorithm’s test decoder.
- `example_id` is optional (used only for display/validation if present).

### Override JSON (Optional)
- UI allows providing a small override JSON object.
- The override is deep-merged into the selected example JSON object (override wins; arrays replace).
- If the override is not valid JSON or not a JSON object, fail the request.
- JSON merge uses `com.hotvect.utils.JsonUtils.deepMergeJsonNodeWithArrayReplacement`.

Note: “JSON-in-JSON” (string fields containing embedded JSON) is expected in practice; the UI supports it via `--json-in-string-path`.

## Rendering Contract (Images/Names) via External Metadata

Goal: demo UI must work with production algorithm JARs without requiring algorithm code changes to populate UI-specific
fields. No heuristics.

We use an external "action metadata" store keyed by `action_id` to render cards.

### Action Metadata Source
- `--action-metadata-path <dir>`: directory scanned recursively for action metadata files:
  - `*.jsonl`, `*.json`, `*.ndjson`
  - Spark-style `part-*` files (with or without `.json`)
  - Optional gzip variants for any of the above (suffix `.gz`)
- Files are ingested into a local SQLite cache DB on startup (fail startup on invalid input).
- If omitted, results render without images/names (action_id only).

### Action Metadata Schema (strict)
Each JSONL line must be a JSON object with:
- `action_id`: string, non-empty
- `action_name`: string, non-empty
- `action_image_url`: string, non-empty

Strict rules:
- `action_id` must be unique across the entire loaded dataset.
- Any missing/invalid record aborts startup.

### Algorithm Output Requirement (strict)
For every decision/item we render, we must have a non-empty `action_id` in the algorithm response.
- If any returned item has a missing/empty `action_id`, fail the request.
- If `--action-metadata-path` is provided and an `action_id` is not found in the action metadata store, fail the request.

## UI (Minimal)

- Filter input: substring search across loaded examples (at least `example_id`, optionally also the raw JSON line).
- Example list: shows matching examples (e.g. `example_id` + `relative/path.jsonl:line`), click to select.
- Run button.
- Override JSON textarea (optional).
- Results grid: image + name per item (data from action metadata via `action_id`).
- Raw output panel: shows either response JSON (pretty) or error JSON (pretty) in the same place.

No toggles, no per-card drilldowns in MVP.

## Algorithm Invocation (MVP)

- Load algorithm definition from:
  - algorithm name inside the algorithm JAR, OR
  - explicit algorithm-definition JSON file (common for training outputs).
- Use the algorithm’s `decoder_factory_classname` with `test_decoder_parameters`.
- Decode the selected example into an Example and invoke:
  - Ranker: `rank(request)`
  - TopK/ThemedTopK: `apply(request)`
- Return a JSON response that includes decision/item `action_id` so the UI can join to action metadata.

## Proposed CLI Flags (Draft)

Single executable (jar-with-dependencies) that starts the demo server.

Required:
- `--algorithm-jar <path>`: algorithm JAR to load.
- `--algorithm-name <name>`: algorithm definition is read from the algorithm JAR resources by name.
- `--parameter-path <path>`: parameters ZIP (required; fail fast if missing).
- `--source-path <path>`: directory scanned recursively for `*.jsonl`/`*.json` (and gz variants) examples.

Optional:
- `--algorithm-override <path>`: JSON file with algorithm definition overrides (merged into base definition).
- `--action-metadata-path <path>`: directory containing action metadata `*.jsonl` used for card rendering (recommended).
- `--demo-sqlite-path <path>`: SQLite cache DB file (if missing, it will be built on startup; default is a deterministic path under `/tmp/hv-demo-ui-sqlite-cache/`).
- `--json-in-string-path <path>`: JSON path to a field containing JSON encoded as a string (repeatable); exposes `<field>__json` for browsing/editing in the UI.
- `--host <host>`: bind host (default `127.0.0.1`).
- `--port <port>`: bind port (default `12000`).

Strict startup validation:
- Every example must parse as a JSON object (one object per line).
- Any validation failure aborts startup.

## Open Decisions (Deferred)

- “Quick field overrides” (e.g., set search query) via configured JSON paths and “JSON-in-JSON” path handling.
- Standardizing additional card fields beyond image/name (e.g., score/rank, badges, link URLs).
- Case-insensitive vs case-sensitive filtering (likely case-insensitive).
