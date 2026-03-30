# Hotvect Algorithm Server + UI

Local HTTP server for running Hotvect algorithms against offline examples. Add `--ui` to enable the browser UI.

## Usage

Build:

```bash
mvn -q -DskipTests package
```

Run:

```bash
java -cp target/hotvect-algorithm-demo-*-jar-with-dependencies.jar \
  com.hotvect.algorithmdemo.Main \
  --algorithm-jar /path/to/algorithm.jar \
  --algorithm-name <algorithm_name> \
  --parameter-path /path/to/parameters.zip \
  --ui \
  --source-path /path/to/examples_dir \
  --max-request-mib 256
```

Headless API-only mode:

```bash
java -cp target/hotvect-algorithm-demo-*-jar-with-dependencies.jar \
  com.hotvect.algorithmdemo.Main \
  --algorithm-jar /path/to/algorithm.jar \
  --algorithm-name <algorithm_name> \
  --parameter-path /path/to/parameters.zip \
  --max-request-mib 256
```

## JSON-in-string fields

Some example JSONs contain fields that are themselves JSON objects encoded as strings (for example, a `"data"` field containing a serialized JSON object, or a `debug_data` field containing a JSON object).

The demo UI automatically detects these (string values that resemble JSON objects/arrays) and exposes a *virtual* sibling field named `<field>__json` (example: `data__json`) containing the parsed JSON object/array.

Behavior:

- If you edit values under `<field>__json` and click Run, the server serializes `<field>__json` back into the original `<field>` string before decoding/running the algorithm.
- The virtual `<field>__json` field is removed before decoding/running the algorithm (it is a UI-only convenience).

## SQLite cache (`--demo-sqlite-path`)

The demo UI stores examples and action metadata in a local SQLite DB to avoid keeping large datasets in memory.

- Default cache location: deterministic path under `/tmp/hv-demo-ui-sqlite-cache/` (derived from `--source-path` and `--action-metadata-path`).
- You can override the path with `--demo-sqlite-path /path/to/demo.db`.
- If the DB does not exist, it is built on startup (the first run may take a while).

## Action details (action metadata)

If you start the demo UI with `--action-metadata-path`, the UI can render result cards with images.

- Clicking a result card opens an “action details” view.
- The view is driven entirely by the action metadata JSON object keyed by `action_id`.
- Only `action_id`, `action_name`, and `action_image_url` are required; any additional fields are displayed generically as JSON.
