hotvect
=======
A feature engineering and ML serving library for machine learning applications, especially for personalization and recommendation systems.

Hotvect allows you to:
1. Develop feature engineering code that can be shared across offline and online environments.
2. Integrate machine learning libraries like CatBoost and TensorFlow into ML applications.
3. Define ML-enabled models and policies, packaging them into reusable, modular forms that can easily be shared, combined, and deployed into production.
4. Perform offline testing and hyperparameter optimization of models and policies, with built-in bookkeeping of test results.
5. Integrate with Amazon SageMaker for running offline tests and hyperparameter optimization at scale.

The same data transformation code will be used for training and prediction, ensuring consistency without discrepancies.

Hotvect has characteristics that work well with typical machine learning use cases:
1. **Out-of-core:** Processing happens without reading all data into memory, allowing processing of large datasets.
2. **Multi-threaded:** Processing is multi-threaded, reducing processing time.
3. **Efficient:** The library is coded with efficiency in mind. Using the JVM makes it easy to write efficient feature transformations.

Feature interaction is natively supported, although exploring interaction features requires a separate step.

## Getting Started

### 1. Install dependencies

- Java 21
- Maven
- Python 3.11+
- `uv`

### 2. Build and install Hotvect (source checkout)

```bash
cd python
make init
source .venv/bin/activate
```

For faster iteration while developing locally (skips Java tests):

```bash
cd python
make quick
```

### 3. Configure local paths (optional, but recommended)

Hotvect uses `~/.hotvect/config.json` to find default base directories.

```bash
hv-ext config init
```

### 4. Use the CLIs

```bash
hv --help
hv-ext --help
```

Two local HTTP debugging surfaces are available:

```bash
# Full algorithm HTTP server (Java runtime, including feature extraction)
hv serve --algorithm-jar /path/to/algo.jar --algorithm-name my-algo --parameter-path /path/to/params.zip --port 8080

# Same server, with browser UI enabled
hv serve --ui --algorithm-jar /path/to/algo.jar --algorithm-name my-algo --parameter-path /path/to/params.zip --source-path /path/to/examples --port 8080

# Worker-only HTTP server (LitServe-backed worker runtime, expects worker-ready feature rows)
# Native LitServe `/predict` and compatibility `/v2/...` endpoints are both exposed.
# Use --algorithm-override when the algorithm definition does not already declare backend
# and a scoped litserve block.
hv worker serve --algorithm-jar /path/to/algo.jar --algorithm-name my-model --parameter-path /path/to/params.zip --port 8081 --algorithm-override worker-http-override.json
```

Example: download a specific algorithm version's SageMaker backtest results (regex filters):

```bash
hv-ext results download \
  "s3://example-bucket/temp/<user>/sagemaker_output/" \
  --dest-base-dir "./results" \
  --from-date "2026-02-15" --to-date "2026-02-15" \
  --algorithm-name-regex "example-algorithm" \
  --algorithm-version-regex "74\\.4\\..*" \
  --job-name-regex "ml-exp-.*" \
  --include-metadata
```

### 5. (Optional) Use the docs MCP server

Hotvect ships a **read-only** MCP server for documentation search and retrieval over stdio (NDJSON JSON-RPC).

If you installed Hotvect, run:

```bash
hv-mcp --help
```

From a source checkout, run it using the repo venv Python:

```bash
"$(pwd)/python/.venv/bin/python" "$(pwd)/python/bin/hv-mcp" --help
```

See `python/hotvect/mcp/bundled_docs/docs/guides/docs-mcp/index.md` for details and Codex integration.

## What does it not provide?

Hotvect does not include:

1. **Machine learning algorithms themselves** - It is intended to be combined with existing ML libraries.
2. **Orchestration of ML pipelines** - Requires other frameworks like Airflow.
3. **Life-cycle management of models and policies** - Supported by an external Experiment Management Service.
4. **Creation, management, and execution of online experiments** - Provided by the Experiment Management Service.
5. **Monitoring of ML applications and evaluation of online experiment results** - Requires separate solutions.

## Notes

Hotvect is designed to be library-agnostic:

- Feature engineering runs in the **JVM** (Java/Kotlin/Scala), so offline and online feature computation can share the same code.
- Model inference can run in the JVM (e.g. JNI, or pure-Java implementations like [H2O.ai's xgboost-predictor](https://github.com/h2oai/xgboost-predictor)), **or** in a separate Python process via direct workers (v10+).

Feature engineering should be implemented in a JVM language (e.g., Java, Kotlin, Scala), while APIs for triggering tasks like offline testing are provided as a Python library.
