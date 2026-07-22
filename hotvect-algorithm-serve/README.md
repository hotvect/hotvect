# Hotvect Algorithm Serve

Headless HTTP server for running Hotvect algorithms without the browser demo UI.

```bash
java -cp target/hotvect-algorithm-serve-*-jar-with-dependencies.jar \
  com.hotvect.algorithmserver.Main \
  --algorithm-jar /path/to/algorithm.jar \
  --algorithm-name <algorithm_name> \
  --parameter-path /path/to/parameters.zip
```

Or load multiple local runtimes from JSON:

```bash
java -cp target/hotvect-algorithm-serve-*-jar-with-dependencies.jar \
  com.hotvect.algorithmserver.Main \
  --local-runtime-config /path/to/local-runtimes.json
```

`--local-runtime-config` is strict: unknown JSON fields are rejected, and relative file paths are resolved relative to
the config file location.

This module intentionally excludes demo-only dependencies such as SQLite caching and static UI assets. The browser UI lives in `hotvect-algorithm-demo` as an extension on top of this server.

`GET /api/metadata` exposes one canonical serving identity:

- root: `algorithm_runtime_id`
- `algorithm`: algorithm name/version, hyperparameter version, `algorithm_id`, `hyperparameter_id`, git and Hotvect version metadata
- `parameters`: parameter id and parameter generation timestamps
- `runtimes`: a sorted array of available runtime identities; single-runtime and EMS mode still expose one entry

The old provider-specific public metadata buckets (`algorithm_source`, `local`, `ems`, `selected_algorithm_runtime_id`)
are intentionally not exposed anymore.

Local multi-runtime mode selects the first configured runtime by alphabetical `algorithm_runtime_id` unless the caller passes
`algorithm_runtime_id` explicitly (for example `POST /predict?algorithm_runtime_id=<id>`).

Prediction responses include the serving identity used for that request: `algorithm_id`, `parameter_id`, `algorithm_runtime_id`,
and `variant_id` when applicable.

`--max-request-mib` is validated against an in-memory buffering cap of 512 MiB. Oversized requests still return the
structured JSON `413` response from the server.
