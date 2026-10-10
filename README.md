# Hotvect

**Build complete algorithms. Run them from training to serving.**

Hotvect is an application framework and runtime for the code around developer-chosen ML libraries. Use CatBoost,
TensorFlow, PyTorch, or custom ML code for the model; Hotvect connects it to typed request handling, feature
computation, reusable components, rules, and final ranking or selection logic. Together, those parts form one callable,
versioned algorithm.

Algorithm authors build an **algorithm package** containing the implementation and definition. Offline tooling trains
or generates state and creates a separate **parameter package** when needed. A containing application loads the
selected packages and calls the algorithm. Today, those packages are distributed as a JVM JAR and a ZIP respectively.

## The mental model

```text
algorithm implementation + embedded definition
  → algorithm package
  → optional training, evaluation, or state generation
  → optional parameter package
  → application/runtime owns an AlgorithmGraph
  → request → decision
```

The same implementation is exercised during offline prediction, evaluation, and application integration. Sharing the
algorithm and parameter packages removes one source of offline/online drift; it does not make inputs, external
services, or execution settings automatically identical.

Hotvect currently provides:

- public decision contracts for ranking, bulk scoring, TopK, and themed TopK;
- JVM feature transformation, including compile-time generated ranking transformers;
- simple and composite algorithm factories with named child dependencies;
- integration with CatBoost, TensorFlow, managed Python workers, and algorithm-owned custom runtimes;
- local and SageMaker train, audit, predict, evaluate, performance-test, and backtest workflows;
- dynamic algorithm-package and parameter-package loading for containing Java applications;
- optional integration with a separately deployed Experiment Management Service (EMS) for refreshed slot state,
  release metadata, and local variant assignment;
- local HTTP and browser debugging surfaces.

For targeted serving tests, a containing application can invoke an active EMS root variant by passing
`String rootVariantId` to the `invoke(touchpoint, assignmentKey, rootVariantId, request)` overload, or the
corresponding `invokeThemedTopK` overload when it needs a concrete themed response. The current EMS
lookup converts the string to EMS's integer ID; the runtime API does not expose that representation.
The override selects only the root variant; dependency slots continue to
assign from `assignmentKey`.
The override lasts for one invocation and does not change EMS assignments. The containing application
controls which requests may use it. These APIs first appear in version 11.2.2.

## Start here

You need JDK 21, Maven, Make, Python 3.11–3.13, and `uv`.

From a source checkout:

```bash
cd python
make init
source .venv/bin/activate
hv --version
```

Then follow the documentation in this order:

1. [Install and verify Hotvect](python/hotvect/mcp/bundled_docs/docs/guides/quickstart/index.md)
2. [Run the example product algorithms](python/hotvect/mcp/bundled_docs/docs/guides/first-run/index.md)
3. [Understand how Hotvect works](python/hotvect/mcp/bundled_docs/docs/concepts/how-hotvect-works/index.md)
4. [Build your first algorithm](python/hotvect/mcp/bundled_docs/docs/guides/first-algorithm/index.md), or
   [tour an existing algorithm](python/hotvect/mcp/bundled_docs/docs/guides/first-workflow/index.md)

The complete documentation starts at
[Hotvect documentation](python/hotvect/mcp/bundled_docs/docs/index.md). The same version-matched Markdown is searchable
from the installed CLI:

```bash
hv docs search "build first algorithm" --limit 3
hv docs read concepts/how-hotvect-works/index.md
```

## Typical development loop

For an existing algorithm:

```text
inspect definition and factories
  → build the algorithm package
  → resolve data and child artifacts
  → run the smallest relevant audit, predict, train, or backtest
  → inspect result.json and stage outputs
  → expand only after the bounded proof succeeds
```

Use `hv --help` and the [CLI reference](python/hotvect/mcp/bundled_docs/docs/reference/cli/index.md) for exact
commands. New algorithm commands use `hv algorithm <operation>` (for example, `hv algorithm backtest`); `hv serve`
and the older direct algorithm commands remain available temporarily and print a migration warning. `hv algorithm serve`
and `hv worker serve` are local debugging tools; production use embeds the online runtime in a containing application.

## Locally test training Docker images

Run these commands from the repository root. They build the images in the same dependency order as the CI pipeline: the Hotvect
base image, CPU PyTorch image, then the CPU PyTorch+TensorFlow image. The base image includes the bundled JARs under
`python/hotvect/hotvectjar`, so rebuild and refresh them before testing a Java change:

```bash
mvn package -DskipTests
python3 python/scripts/copy_jar.py
```

The commands below use Docker's default network. If your package index is reachable only through a specific Docker
network, pass `--network=<name>` to each build, or set `DOCKER_NETWORK` when using the helper script.

```bash
docker build --platform=linux/amd64 \
  -t hotvect:base-test .

docker build --platform=linux/amd64 \
  -f Dockerfile.torch \
  -t hotvect:cpu-torch-test \
  --build-arg HOTVECT_BASE_IMAGE=hotvect:base-test \
  --build-arg HOTVECT_TORCH_BACKEND=cpu .

docker build --platform=linux/amd64 \
  -f Dockerfile.torch-tensorflow \
  -t hotvect:cpu-torch-tensorflow-test \
  --build-arg HOTVECT_BASE_IMAGE=hotvect:cpu-torch-test .
```

Validate the final image's resolved environment:

```bash
docker run --rm --platform=linux/amd64 --entrypoint sh hotvect:cpu-torch-tensorflow-test \
  -c 'uv pip check -p /opt/hotvect/.venv/bin/python'
```

For the complete base, TensorFlow, CPU PyTorch, and CPU PyTorch+TensorFlow sequence, use the helper instead:

```bash
DOCKER_NETWORK=bridge scripts/docker-build-images-local.sh
```

On an ARM host, `--platform=linux/amd64` runs the Linux image under emulation. Use the build and `uv pip check` to
validate dependency resolution; framework import smoke tests are more reliable on a native Linux AMD64 host.

## Project boundaries

Hotvect owns the algorithm contract, artifact loading, feature/runtime integration, and offline lifecycle for one
decision algorithm. It does not replace:

- a general data or job scheduler;
- a feature store or artifact registry;
- a model library;
- an EMS deployment's database, identity-provider adapter, artifact-publication workflow, release governance, or
  production monitoring;
- the HTTP, event, authentication, and traffic layers of a serving application.

Those systems can surround Hotvect or satisfy explicit algorithm dependencies while keeping their own operational
contracts.

## Repository modules

| Area | Modules |
| --- | --- |
| Public contracts | `hotvect-api` |
| Feature runtime and code generation | `hotvect-core`, `hotvect-processor` |
| Model backends | `hotvect-catboost`, `hotvect-tensorflow`, `hotvect-python` |
| Online loading | `hotvect-online-util` |
| Offline JVM tasks | `hotvect-offline-util` |
| Python lifecycle and CLIs | `python/` |
| Local algorithm server and debugger | `hotvect-algorithm-demo` |
| Experiment control plane | Standalone EMS service |
| Runnable product search and ranking example | `examples/product-search-and-ranking/` |
