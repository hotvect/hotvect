# Guides

Task-focused guides for common hotvect workflows.

<div class="grid cards" markdown>

-   **Quickstart**

    Set up a minimal project and run your first workflow.

    [Open quickstart](quickstart/index.md){ .hv-btn }

-   **Pipeline stages**

    Learn what each offline stage does and when to run it directly.

    [Open guide](pipeline-stages/index.md){ .hv-btn }

-   **Develop algorithms**

    Build a re-ranker with a JVM feature pipeline and the Python CLI.

    [Open guide](develop-algorithms/index.md){ .hv-btn }

-   **Caching**

    Speed up repeated runs by reusing artifacts (local or `s3://example-bucket`).

    [Open guide](caching/index.md){ .hv-btn }

-   **Performance and optimization**

    Measure wall clock correctly, benchmark safely, and choose optimization levers that actually move the pipeline.

    [Open guide](performance-optimization/index.md){ .hv-btn }

-   **SageMaker backtests**

    Submit and monitor backtests at scale.

    [Open guide](sagemaker-backtests/index.md){ .hv-btn }

-   **Performance investigations**

    Debug speed regressions without mixing them up with workload or artifact mismatches.

    [Open guide](performance-investigations/index.md){ .hv-btn }

-   **Debug feature engineering**

    IDE debugging workflow for JVM feature engineering.

    [Open guide](debug-feature-engineering/index.md){ .hv-btn }

-   **Feature audits**

    Run and compare audits to validate feature behavior over time.

    [Open guide](feature-audits/index.md){ .hv-btn }

-   **Score equivalence**

    Compare prediction outputs and validate scoring equivalence.

    [Open guide](score-equivalence/index.md){ .hv-btn }

-   **Performance benchmarking**

    Run `hv performance-test` comparisons that hold up under repeated measurement and statistical checks.

    [Open guide](performance-benchmarking/index.md){ .hv-btn }

-   **Parameterized SLA monitoring**

    Build a read-only SLA chart for currently in-use algorithms with configurable cutoff/timezone.

    [Open guide](parameterized-sla-monitoring/index.md){ .hv-btn }

-   **Docs MCP (Codex)**

    Use the bundled docs MCP for fast retrieval and agent workflows.

    [Open guide](docs-mcp/index.md){ .hv-btn }

</div>

## Patterns

- [Data dependencies](patterns/data-dependencies/index.md)
- [Branching and versioning](patterns/branching-and-versioning/index.md)
- [Override files](patterns/override-files/index.md)
- [Parent/child algorithms](patterns/parent-child/index.md)
