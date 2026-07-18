---
title: Migrate from Hotvect v9 to v10
description: Breaking API, artifact, execution, and output-layout changes when moving an algorithm to Hotvect v10
tags: [migration, v9, v10]
---

# Migrate from Hotvect v9 to v10

This guide covers the breaking changes and behavioral changes when upgrading from Hotvect v9 to Hotvect v10.

## TL;DR

- Hotvect v10 requires **Java 21** (runtime + build).
- `--metadata-path` is now a **directory** everywhere (v9 used a single `metadata.json` file path in several places).
- Java runner flag is renamed: `--meta-data` → `--metadata-path` (and `--meta-data` is removed).
- `hv encode` output is now **directory-based and sharded**: `--dest-path` is a directory, and outputs are written as `part-00000<ext>`, `part-00001<ext>`, ...
- Current v10 encoders should provide a file extension via `ExampleEncoder.encodedFileExtension()`. A narrow v9
  string-encoder bridge can still write extensionless part files with a warning; do not rely on that legacy layout.
- Transformer construction now fails fast on **non-canonical namespaces** (you may need to register namespace enums during wiring).
- Logs are now persisted more consistently:
  - `hv.log` for Python orchestration (CLI + pipelines/backtests)
  - `stdout-stderr.log` for raw subprocess output (CLI and pipeline stages)
- `hv compare-jsonl` moved to `hv-ext compare-jsonl`.
- `hv --algorithm-name` no longer accepts JSON paths; use `--algorithm-override` (or call Java directly with a full algorithm-definition JSON file).

## At-a-Glance: What Changed

| Area | v9 | v10 |
| --- | --- | --- |
| Java baseline | Java 17 | Java 21 |
| Java metadata flag | `--meta-data <metadata.json>` (file path) | `--metadata-path <dir>` (directory) |
| Python `hv --metadata-path` default | `<algo>.<cmd>.metadata.json` (file) | `<algo>.<cmd>.metadata` (directory) |
| Metadata artifact | `<file>.json` | `<dir>/metadata.json` |
| Encoding output (`encode`) | `--dest` often treated as file/pattern | `--dest`/`--dest-path` is a directory; outputs are `part-00000<ext>`, `part-00001<ext>`, ... |
| JVM default memory | `-Xmx32g` (if not provided) | `-XX:MaxRAMPercentage=80` (if not provided) |
| Namespace wiring | Identity mismatches could slip through | Transformer build fails fast on non-canonical namespaces |
| Maven artifacts | `hotvect-vw` present | `hotvect-vw` removed; `hotvect-tensorflow` added |
| JSONL compare | `hv compare-jsonl ...` | `hv-ext compare-jsonl ...` |

## Compatibility Notes

- Hotvect v10 can often **run algorithm JARs built against hotvect v9.x** (especially for `predict`/`audit`), but:
  - You must run everything on **Java 21**.
- If you use `encode`, migrate/rebuild legacy encoders so they implement `ExampleEncoder.encodedFileExtension()` (and
  you may need to adjust `writer_num_shards`; see below). V10 can adapt the narrow v9 `ExampleEncoder` string-output
  contract, but that bridge can emit extensionless part files and is not a general v9-compatibility promise.
  - If you hit namespace validation errors, you will need to update algorithm wiring to register/declare namespaces canonically (see below).
- You **must** update any scripts that call the Java runner directly (see below), and any scripts that treat `--metadata-path` as a file.
- **CatBoost parameter ZIP layout** – Current v10 bulk scorer factories accept both the v10 entry
  `model_parameter/model.parameter` and the legacy root entry `model.parameter`. `CatBoostGreedyRankerFactory`
  requires the v10 entry; use a parameters artifact produced for that factory instead of silently repacking one.

## Backward Incompatible Changes

### 0) Java 21 is required (build + runtime)

Hotvect v10 is compiled for Java 21. If you run it on an older JVM you will typically see an error like:

```
java.lang.UnsupportedClassVersionError: ... class file version 65.0 ...
```

Fix:

- Upgrade your runtime to **Java 21** (JRE/JDK).
- If you build from source, ensure Maven uses **JDK 21** (`JAVA_HOME` points to a JDK 21 install).

### 1) `--metadata-path` is now a directory (not a JSON file)

In v9, the Java runner treated `--meta-data` as a **metadata file path**, defaulting to `metadata.json` (and many wrappers followed this convention).

In v10:

- `--metadata-path` is a **directory**.
- Java writes metadata to `--metadata-path/metadata.json`.
- Passing a path ending in `.json` is rejected.

#### v9 → v10 CLI default change

- v9 `hv` defaulted to: `<algorithm>.<command>.metadata.json` (a file)
- v10 `hv` defaults to: `<algorithm>.<command>.metadata` (a directory)

Update any scripts that assume `--metadata-path` is a file. If you used to read:

```bash
cat myalgo.predict.metadata.json
```

You now want:

```bash
cat myalgo.predict.metadata/metadata.json
```

#### Migrating existing v9 metadata files

If you have saved v9 artifacts named like `*.metadata.json` and want to adopt the v10 directory layout:

```bash
mkdir -p myalgo.predict.metadata
mv myalgo.predict.metadata.json myalgo.predict.metadata/metadata.json
```

### 2) Java CLI flag rename: `--meta-data` → `--metadata-path`

The Java runners now accept **only** `--metadata-path` (directory). The old `--meta-data` flag is removed.

Update any scripts that call:

- `com.hotvect.offlineutils.commandline.Main`
- `com.hotvect.offlineutils.commandline.util.flatmap.FlatMapFile`

Example (v10 Java runner):

```bash
java -cp hotvect-offline-util-*-jar-with-dependencies.jar \
  com.hotvect.offlineutils.commandline.Main \
  --metadata-path myrun.metadata \
  ...
```

#### v9 → v10 example (feature audits)

Many v9 scripts/docs used a `.json` file for metadata:

```bash
# v9
... --meta-data audit_metadata.1.2.3.json ...
```

In v10 this becomes:

```bash
# v10
... --metadata-path audit_metadata.1.2.3.metadata ...
```

### 3) Encoding output is now directory-based and sharded (`encode`)

This is the other major “paths are no longer files” change in v10.

In v9, `encode` commonly treated the destination as a file path (single-file mode) or a user-controlled shard pattern (e.g. `data_%d.tfrecord`).

In v10:

- Java `encode` **always treats `--dest` as a directory**.
- Encoded outputs are written as **shards inside that directory**:
  - `<dest>/part-00000<ext>`, `<dest>/part-00001<ext>`, ...
  - The file extension `<ext>` comes from the encoder (`ExampleEncoder.encodedFileExtension()`).
- For `hv encode`, this means `--dest-path` is a **directory**, not a file.

#### What you need to update

- If downstream code expects a single file, update it to read from `.../part-00000<ext>` or glob `.../part-*<ext>`.
- If you need a single part file, set `writer_num_shards` to `1` (see below) or run in ordered mode; output is still a directory, but it will contain only `part-00000<ext>`.

For CatBoost training specifically, `catboost_train` can merge multiple encoded shards into a single TSV with the system `cat` command before fitting. This is a convenience path, not a proven performance improvement.

Historical benchmarking did not show a meaningful improvement from encoding many shards and merging them before
CatBoost training. Treat shard count as an output-layout and concurrency control, not an assumed performance
optimization; measure it against the workload you intend to run.

#### Algorithm definition change: `writer_num_shards`

In v10, `writer_num_shards` is read from:

- `transformer_parameters.writer_num_shards`

If you had it under `train_decoder_parameters` in v9, move it.

#### Algorithm code change: current encoders should provide an extension

Current v10 train encoders should provide a file extension so consumers can identify encoded part files reliably.

V10 also carries a narrow bridge for legacy v9 `ExampleEncoder` implementations that return strings. That bridge
allows the run to continue but writes extensionless part files with a warning. It does not accept bare `Function`
encoders or arbitrary legacy output types, so migrate the encoder instead of treating the bridge as a permanent
contract.

Example:

```java
@Override
public String encodedFileExtension() {
    return ".tfrecord"; // or ".tsv", ".jsonl", ...
}
```

### 4) Namespace canonicalization is now enforced at transformer build time

Hotvect now validates that every namespace used in a transformer is the canonical singleton registered in `Namespaces`.

Symptoms include errors like:

- `Non-canonical namespace detected in ...`
- `Namespace "..." is not canonical: never registered`

Fix (algorithm code): use enum constants directly or reuse the canonical handles returned by
`Namespaces.declareNamespace(...)`. Register an enum explicitly only when you need it to claim a name before a
string-based declaration.

See `concepts/namespaces/index.md` for details and examples.

### 5) Maven module changes: `hotvect-vw` removed, `hotvect-tensorflow` added

- If your builds depend on `com.hotvect:hotvect-vw`, you must remove that dependency or stay on hotvect v9; v10 no longer ships the VW module.
- TensorFlow integration lives in `com.hotvect:hotvect-tensorflow` in v10.

### 6) `hv compare-jsonl` moved to `hv-ext compare-jsonl`

If you used `hv compare-jsonl` in v9, use:

```bash
hv-ext compare-jsonl file1.jsonl file2.jsonl -o . -c config.json
```

### 7) `hv --algorithm-name` no longer accepts JSON paths

In v9 it was common to pass a JSON file path as the algorithm identifier (especially when replaying/debugging).

In v10 `hv`:

- `--algorithm-name` must be an **algorithm name**, not a file path.
- To apply partial overrides, use `--algorithm-override`. `hv` will materialize an effective full definition into:
  - `--metadata-path/effective_algorithm_definition.json`

If you really need to pass a full algorithm-definition JSON file, call Java directly and pass:

```bash
--algorithm-definition /path/to/full-algorithm-definition.json
```

## Behavioral Changes (Not Strictly Breaking, But You’ll Notice)

### JVM default memory flag changed

If you relied on the implicit v9 default `-Xmx32g`, note that v10 defaults to `-XX:MaxRAMPercentage=80` (unless you pass an explicit `-Xmx...` or `-XX:MaxRAMPercentage=...`).

If you want v9-style behavior, pass explicit JVM args:

```bash
# Java-backed one-shot commands: JVM args are passed through as extra args
hv predict ... -- -Xmx32g -XX:+ExitOnOutOfMemoryError

# Pipelines/backtests: use --extra-jvm-args (comma-separated)
hv backtest ... --extra-jvm-args "-Xmx32g,-XX:+ExitOnOutOfMemoryError"
```

## Logging and Output Artifacts (What’s New)

Hotvect now persists more of what you previously only saw in the terminal.

### Non-pipeline Java-backed `hv` commands (`audit|encode|predict|...`)

Within `--metadata-path/` you will now find:

- `metadata.json` (Java task metadata)
- `hotvect-offline-utils.log` (Java SLF4J/logback logs)
- `hv.log` (Python CLI logs)
- `stdout-stderr.log` (raw stdout/stderr from the subprocess)

Example layout:

```
myrun.metadata/
├── metadata.json
├── hotvect-offline-utils.log
├── hv.log
└── stdout-stderr.log
```

### Pipelines/backtests (`AlgorithmPipeline`)

For each algorithm run, `hv backtest` writes these under `meta/<algorithm>@<version>/last_test_date_YYYY-MM-DD/`:

- `hv.log` (Python orchestration logs)
- `result.json` (comprehensive pipeline output)
- `<stage>/hotvect-offline-utils.log` (Java logs)
- `<stage>/stdout-stderr.log` (raw stage subprocess output)

Direct `hv train` uses the same per-run contents under
`metadata/<algorithm>@<version>/last_test_date_YYYY-MM-DD/` instead.

Example layout (parent algorithm; child is analogous):

```
${output_base_dir}/meta/my-algorithm@1.0.0/last_test_date_2000-01-15/
├── hv.log
├── result.json
├── algorithm_definition.json
├── predict/
│   ├── metadata.json
│   ├── hotvect-offline-utils.log
│   └── stdout-stderr.log
└── evaluate/
    ├── metadata.json
    ├── hotvect-offline-utils.log
    └── stdout-stderr.log
```

This is intentionally redundant:

- If it’s in `hotvect-offline-utils.log`, it came from Java logging.
- If it’s in `stdout-stderr.log`, it came from raw stdout/stderr (often the missing piece).
- If it’s in `hv.log`, it came from Python orchestration (what ran, cache decisions, etc.).

## What To Update

### Mechanical search/replace

1. Ensure your JVM and build toolchain use **Java 21**.
2. Replace Java invocations using `--meta-data` with `--metadata-path`.
3. Replace `*.metadata.json` paths (files) with `*.metadata` (directories) and adjust reads to `.../metadata.json`.
4. Update any `encode` consumers to treat `--dest`/`--dest-path` as a directory and read from `part-*<ext>`.
5. If you have custom encoders, implement `ExampleEncoder.encodedFileExtension()` and rebuild algorithm JARs.
6. If you hit namespace canonicalization errors, reuse enum constants or canonical objects returned by `Namespaces`.

### Log collectors/parsers

If you ingest logs or parse artifacts, add support for:

- `hv.log` (Python orchestration logs)
- `stdout-stderr.log` (raw subprocess output)

### Recommended quick checks

After upgrading:

1. Run `hv predict ...` once and confirm `--metadata-path/` contains:
   - `metadata.json`, `hv.log`, `hotvect-offline-utils.log`, `stdout-stderr.log`
2. Run a local backtest and confirm each algorithm run contains:
   - `meta/.../hv.log`
   - `meta/.../<stage>/stdout-stderr.log`

## Troubleshooting

### “UnsupportedClassVersionError … class file version 65.0”

- Fix: run with **Java 21**.

### “metadata-path must be a directory” / “path ends with .json”

- Fix: change your value to a directory name (often switching `*.metadata.json` → `*.metadata`), and read metadata from `.../metadata.json`.

### “--algorithm-name must be an algorithm name (not a file path)”

- Fix: use `--algorithm-override` for JSON changes (partial overlay), or call Java directly with `--algorithm-definition /path/to/full-algorithm-definition.json`.

### Extensionless legacy encoder output

- Fix: update your train encoder to implement `ExampleEncoder.encodedFileExtension()` (return `".tfrecord"`,
  `".tsv"`, etc), rebuild the algorithm JAR, and rerun encoding. This restores a stable part-file extension for
  downstream consumers.

### “Non-canonical namespace detected ...” / “Namespace ... is not canonical”

- Fix: use namespace enum constants directly or reuse the canonical objects returned by
  `Namespaces.declareNamespace(...)`. Register the enum explicitly only to establish name ownership before a
  string-based declaration.

## Related documentation

- [Version compatibility](../../reference/version-compatibility/index.md) defines the supported runtime boundaries.
- [Hotvect v10](../../whats-new/v10/index.md) summarizes the release without the migration sequence.
- [Troubleshooting](../../reference/troubleshooting/index.md) covers failures after the mechanical upgrade.
