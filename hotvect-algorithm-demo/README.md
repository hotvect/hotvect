# Hotvect Algorithm Demo UI

Browser UI extension for the headless `hotvect-algorithm-serve` HTTP server.

## Usage

Build:

```bash
mvn -q -DskipTests package
```

Run the demo UI:

```bash
java -cp target/hotvect-algorithm-demo-*-jar-with-dependencies.jar \
  com.hotvect.algorithmdemo.Main \
  --algorithm-jar /path/to/algorithm.jar \
  --algorithm-name <algorithm_name> \
  --parameter-path /path/to/parameters.zip \
  --ui \
  --source-path /path/to/examples_dir \
  --action-metadata-path /path/to/action_metadata_dir \
  --default-select-json-path .shared.query \
  --max-request-mib 256
```

Or load multiple local runtimes from JSON and switch between them in the UI:

```bash
java -cp target/hotvect-algorithm-demo-*-jar-with-dependencies.jar \
  com.hotvect.algorithmdemo.Main \
  --local-runtime-config /path/to/local-runtimes.json \
  --ui \
  --source-path /path/to/examples_dir
```

Headless API-only mode:

```bash
java -cp ../hotvect-algorithm-serve/target/hotvect-algorithm-serve-*-jar-with-dependencies.jar \
  com.hotvect.algorithmserver.Main \
  --algorithm-jar /path/to/algorithm.jar \
  --algorithm-name <algorithm_name> \
  --parameter-path /path/to/parameters.zip \
  --max-request-mib 256
```

For headless local debugging, use `hotvect-algorithm-serve`; this module adds the browser UI to that same local
debugging core.

When `--local-runtime-config` contains multiple runtimes, the UI exposes one algorithm comparison view per
`algorithm_runtime_id`. One available view renders one result column; two selected views render a side-by-side
comparison. The compatibility `/api/demo/*` routes are:

- `GET /api/demo/examples`
- `GET /api/demo/examples/{example_index}`
- `POST /api/demo/run`
- `POST /api/demo/compare`
- `POST /api/demo/predict`

The raw execution route remains available at `POST /api/run` (and compatibility alias `POST /api/demo/run`) and selects a runtime via `algorithm_runtime_id`.
All examples must be decoder-runnable by the selected runtime. The compare UI does not support raw serving logs that
omit the feature payload required by the algorithm decoder.

When a selected example contains a string at `.shared.query`, the demo exposes it as a compact **Query** field. Enter
runs the example; the generic JSON-path editor remains available for advanced fields. Examples without that field show
no query control.

Default compare selection prefers an algorithm runtime against a recorded comparison view, then two runtimes, then one
runtime in a single column.

## JSON-in-string fields

Some example JSONs contain fields that are themselves JSON objects encoded as strings (for example, a `"data"` field containing a serialized JSON object, or a `debug_data` field containing a JSON object).

The demo UI automatically detects these (string values that resemble JSON objects/arrays) and exposes a *virtual* sibling field named `<field>__json` (example: `data__json`) containing the parsed JSON object/array.

Behavior:

- If you edit values under `<field>__json` and click Run, the server serializes `<field>__json` back into the original `<field>` string before decoding/running the algorithm.
- The server removes the virtual `<field>__json` field before decode/run, so posted UI payloads and raw API payloads follow the same execution path.

## SQLite cache (`--demo-sqlite-path`)

The demo UI stores examples and action metadata in a local SQLite DB to avoid keeping large datasets in memory.

- Default cache location: deterministic path under `${java.io.tmpdir}/hv-demo-ui-sqlite-cache/` (derived from `--source-path` and `--action-metadata-path`).
- You can override the path with `--demo-sqlite-path /path/to/demo.db`.
- If the DB does not exist, it is built on startup (the first run may take a while).

`hv serve --ui` uses the demo JAR copied into the Python package, not the JAR directly under this module's `target/`
directory. After changing the Java UI, run `make quick` from `python/` before testing through `hv serve` so the bundled
JAR matches the source.

## Online views

Recorded comparison views are debug projections from proper decoder-runnable examples. The demo derives them from
the selected runtime's decoded outcomes (`additional_properties.online.*`) and still executes the backing runtime when
you run an online view, ignoring the algorithm response ranking for rendering.

Raw serving logs that cannot be decoded and executed by an algorithm runtime are out of scope for this UI.

## Action details (action metadata)

If you start the demo UI with `--action-metadata-path`, the UI can render result cards using
`action_image_url` from the metadata when it is present.

- Clicking a result card opens an “action details” view.
- The view is driven entirely by the action metadata JSON object keyed by `action_id`.
- Only `action_id` is required. `action_name` and `action_image_url` are optional display metadata, and
  any additional fields are displayed generically as JSON.
- When `action_name` is missing, the UI falls back to `action_id` for display.
