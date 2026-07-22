---
title: Debug an algorithm in the browser
description: Run a local Hotvect artifact against recorded examples and inspect its decisions interactively
tags: [guide, serving, demo-ui, debugging, offline]
difficulty: intermediate
prerequisites:
  - Completed Build your first algorithm, or have an equivalent algorithm JAR and parameter ZIP
  - Hotvect Python environment activated
related_docs:
  - ../first-algorithm/index.md
  - ../application-integration/index.md
  - ../../reference/cli/index.md
---

# Debug an algorithm in the browser

`hv serve --ui` loads a complete algorithm artifact, decodes recorded offline examples, and lets you inspect or edit
those examples in a local browser. It is a bounded debugging tool, not a production server.

This walkthrough continues from [Build your first algorithm](../first-algorithm/index.md). It uses that tutorial's JAR,
metadata-only parameter ZIP, and decoder so every input and expected decision is known.

## Before you start

Confirm that these files exist:

```bash
cd /tmp/hv-first-algorithm

test -f target/example-document-ranker-1.0.0.jar
test -f runtime/example-document-ranker.parameters.zip
```

For another algorithm, substitute its JAR, embedded algorithm name, parameter ZIP, and decoder-compatible examples.
The UI uses the definition's `decoder_factory_classname` and `test_decoder_parameters`; it does not infer an input
schema from the public algorithm interface.

## 1. Add one recorded example

The source path must be a directory. It is scanned recursively for `.json`, `.jsonl`, and their gzip variants.

```bash
mkdir -p runtime/examples

cat > runtime/examples/example.jsonl <<'JSONL'
{"example_id":"example-001","shared":{"query":"sample"},"actions":[{"action_id":"doc-b","title":"Beta"},{"action_id":"doc-a","title":"Alpha"}]}
JSONL
```

This is the same shape accepted by the tutorial decoder. A source line must be a JSON object, and the decoder must
return exactly one example for it.

## 2. Start the browser debugger

```bash
hv serve \
  --ui \
  --algorithm-jar target/example-document-ranker-1.0.0.jar \
  --algorithm-name example-document-ranker \
  --parameter-path runtime/example-document-ranker.parameters.zip \
  --source-path runtime/examples \
  --port 12004
```

Wait for both of these messages:

```text
Demo UI enabled at http://127.0.0.1:12004
Examples loaded: 1
```

Open `http://127.0.0.1:12004/`. The page should show:

- `example-001` in the example list;
- runtime identity `example-document-ranker@1.0.0@NA`;
- `doc-a` at rank 0 with score `2.0`;
- `doc-b` at rank 1 with score `1.0`.

The `NA` parameter ID is expected here: the ZIP satisfies the local-server artifact input, while this tutorial's
factory is parameterless.

## 3. Edit and rerun the example

Select a JSON path, change a value, and choose **Run**. The server sends the edited object through the same decoder and
algorithm runtime; **Reset example** restores the source record.

The UI also exposes the raw input and structured output. Use those views to answer a specific question—such as which
action ID moved or which score changed—rather than treating a visually plausible ordering as a correctness test.

Stop the process with Ctrl+C when finished.

The default host is `127.0.0.1`. Bind another interface only when a local container or another machine must reach the
debugger:

```bash
hv serve ... --host 0.0.0.0 --port 12004
```

That exposes an unauthenticated debugging API, including algorithm metadata and request execution, on every interface.
Use it only on an isolated development network with an external access boundary; do not use this command as a
production server.

## Compare more than one local runtime

Use `--local-runtime-config` instead of the three single-runtime artifact flags when you need side-by-side comparison.
Paths are resolved relative to the configuration file, and unknown fields fail loading.

```json
{
  "runtimes": [
    {
      "algorithm_jar": "../target/example-document-ranker-1.0.0.jar",
      "algorithm_name": "example-document-ranker",
      "parameter_path": "example-document-ranker.parameters.zip"
    },
    {
      "algorithm_jar": "../target/example-document-ranker-1.1.0.jar",
      "algorithm_name": "example-document-ranker",
      "parameter_path": "example-document-ranker-candidate.parameters.zip"
    }
  ]
}
```

```bash
hv serve \
  --ui \
  --local-runtime-config runtime/local-runtimes.json \
  --source-path runtime/examples \
  --port 12004
```

Each entry still needs a JAR, algorithm name, and parameter ZIP. An optional `algorithm_override` path applies the same
definition-patch semantics used by the offline lifecycle.

## Add display metadata only when needed

`--action-metadata-path <directory>` can add names, image URLs, and arbitrary JSON fields to result cards. Each record
must have a non-empty `action_id`, and every action returned by the algorithm must have a matching metadata record.
These values affect display only; they are not algorithm inputs.

The UI stores indexed examples and optional action metadata in a local SQLite cache. By default the cache has a
deterministic path under the operating system's temporary directory. Use `--demo-sqlite-path <file>` when you need to
control or delete that cache explicitly.

## Know what this proves

This workflow proves that the selected artifacts can load, the offline decoder can decode the chosen record, and the
algorithm can produce a result in the local debug runtime. It does not prove that a containing application's request
adapter, live dependencies, concurrency, or failure behavior are correct.

The browser process also exposes `GET /health`, `GET /api/health`, `GET /api/metadata`, `GET /api/config`, and
`POST /predict`. For headless debugging, omit `--ui` and the UI-specific source and metadata flags. Local artifact mode
uses a batch/offline execution context; production execution remains a Java application integration.

Next, [embed Hotvect in a Java application](../application-integration/index.md) or use
[feature audits](../feature-audits/index.md) when the question is about computed feature values rather than the final
decision.
