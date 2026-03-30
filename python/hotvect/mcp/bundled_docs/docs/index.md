# Hotvect

Build, backtest, and train ML algorithms with a JVM feature pipeline and a Python CLI — optimized for agents.

<div class="hv-cta-row" markdown>
[Agent Playbook](agents/index.md){ .hv-btn }
[Quickstart](guides/quickstart/index.md){ .hv-btn }
[CLI reference](reference/cli/index.md){ .hv-btn }
</div>

## Pick a workflow

<div class="grid cards" markdown>

-   **Run a local backtest**

    Copy/paste `hv backtest` and validate outputs + logs.

    [Local backtest runbook](agents/runbooks/local-backtest/index.md){ .hv-btn }

-   **Run a local train**

    Train end-to-end with caching and inspect produced artifacts.

    [Local train runbook](agents/runbooks/local-train/index.md){ .hv-btn }

-   **Run on SageMaker**

    Submit backtests at scale, with S3-backed caching and CloudWatch logs.

    [SageMaker backtest runbook](agents/runbooks/sagemaker-backtest/index.md){ .hv-btn }

-   **Enable caching**

    Speed up repeated runs by reusing state/encode/train artifacts (local or `s3://example-bucket`).

    [Caching guide](guides/caching/index.md){ .hv-btn }

</div>

## What does it do?

Hotvect is an open source library for developing real-time and batch machine learning applications, especially personalized content re-rankers.

It supports:

- Feature engineering code that runs in both offline and online environments
- Integration of ML libraries (e.g., Vowpal Wabbit, CatBoost) into a consistent pipeline
- Packaging models/policies into modular units that can be combined and deployed
- Offline testing and hyperparameter optimization, including bookkeeping of results
- SageMaker integration for scaling offline tests and hyperparameter optimization

## What does it not provide?
It does not provide:

- ML algorithms themselves (Hotvect plugs into existing ML libraries)
- Pipeline orchestration (use Airflow, etc.)
- Model/policy lifecycle management (use your model registry / experiment tracking)
- Online experimentation (use your experimentation platform)
- Monitoring and online experiment evaluation (provided separately)

## Notes
Hotvect is designed to be library-agnostic:

- Feature engineering runs in the **JVM** (Java/Kotlin/Scala), so offline and online feature computation can share the same code.
- Model inference can run in the JVM (e.g. JNI, or pure-Java implementations like [xgboost-predictor](https://github.com/h2oai/xgboost-predictor)), **or** in a separate Python process via direct workers (v10+).

See:

- [Direct Python Workers (UDS IPC)](design/direct-python-workers/index.md)
- [`hv serve` (full algorithm HTTP)](reference/cli/index.md#10-serve)
- [`hv serve --ui` (browser debugger on the same server)](reference/cli/index.md#10-serve)
- [`hv worker serve` (worker-only HTTP)](reference/cli/index.md#11-worker-serve)

Feature engineering is written in a JVM language (Java/Kotlin/Scala). Operational workflows (training, backtests, etc.) are exposed through the Python CLI and library.
