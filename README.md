# Hotvect

**Build the decision system. Run it from training to serving.**

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
  → application loads AlgorithmInstance
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
- optional read integration with an external Experiment Management Service for refreshed slot state and local variant
  assignment;
- local HTTP and browser debugging surfaces.

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

Use `hv --help`, `hv-ext --help`, and the
[CLI reference](python/hotvect/mcp/bundled_docs/docs/reference/cli/index.md) for exact commands. `hv serve` and
`hv worker serve` are local debugging tools; production use embeds the online runtime in a containing application.

## Project boundaries

Hotvect owns the algorithm contract, artifact loading, feature/runtime integration, and offline lifecycle for one
decision algorithm. It does not replace:

- a general data or job scheduler;
- a feature store or artifact registry;
- a model library;
- the external EMS control-plane server, release governance, or production monitoring;
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
| Local debugging | `hotvect-algorithm-serve`, `hotvect-algorithm-demo` |
| Runnable product search and ranking example | `examples/product-search-and-ranking/` |
