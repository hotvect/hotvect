hotvect
=======
A feature engineering and ML serving library for machine learning applications, especially for personalization and recommendation systems.

Hotvect allows you to:
1. Develop feature engineering code that can be shared across offline and online environments.
2. Integrate machine learning libraries like Vowpal Wabbit, CatBoost, etc. into ML applications.
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

**Note:** This installation method using Claude Code is experimental. The setup agent automates environment configuration and source-based installation.

### 1. Install Claude Code

Claude Code is an AI-powered development assistant.

Install Claude Code from: [https://claude.ai/code](https://claude.ai/code)

### 2. Install Hotvect Claude Plugin

The Hotvect plugin provides specialized agents, skills, and commands for ML algorithm development and backtesting.

**Important:** Do NOT run Claude Code from the hotvect repository directory, as it will create logs and artifacts inside the repo.

**Installation steps:**
```bash
# Navigate to your workspace directory (or create one)
cd ~/workspace  # or wherever you keep your projects
# NOT the hotvect repo directory!

# In Claude Code, add the marketplace from the hotvect repo path:
/plugin add-marketplace /path/to/hotvect

# Then install the plugin:
/plugin install hv
```

The plugin provides:

**4 Specialized Agents:**
- `hotvect-setup` - Automated environment setup with central config management
- `backtest` - Multi-version algorithm comparison with evaluation
- `audit` - Feature audit generation and cross-version comparison
- `performance-test` - Algorithm performance benchmarking

**4 Autonomous Skills:**
- `documentation` - Search hotvect docs and source code
- `training-setup` - Configure and validate training runs
- `data-dependency-download` - Smart data dependency management
- `download-results` - SageMaker result retrieval

**13 Slash Commands:**
- `/hv:train` - Train ML model using complete hotvect pipeline
- `/hv:backtest` - Run backtest to compare algorithm versions
- `/hv:audit` - Generate human-readable feature audit
- `/hv:audit-compare` - Compare feature audits between versions
- `/hv:predict` - Generate model predictions on test data
- `/hv:encode` - Transform raw data into ML library format
- `/hv:evaluate` - Calculate performance metrics from predictions
- `/hv:generate-state` - Generate state files for algorithms
- `/hv:performance-test` - Benchmark algorithm throughput and latency
- `/hv:perf-compare` - Compare performance test results
- `/hv:download-data-dependency` - Download required training/test data
- `/hv:download-backtest-results` - Download SageMaker results from S3
- `/hv:compare-evaluations` - Compare ML evaluation metrics

### 3. Set Up Hotvect Environment

After installing the Claude plugin, use the setup agent to configure your development environment:

**In Claude Code, ask:**
```
Use the hotvect-setup agent to set up my environment
```

Or simply:
```
Set up hotvect
```

Claude will automatically invoke the setup agent when it recognizes the task.

The setup agent will:
1. Verify and install required tools (Java 17-21 for v9, Java 21 for v10, Maven, Python 3.11, uv)
2. Clone the Hotvect repository (asks for location)
3. Build Hotvect from source:
   - Navigates to `python/` directory
   - Runs `make quick` to build Java JARs and prepare Python package
   - Creates Python virtual environment with uv
   - Installs Hotvect in editable mode (`uv pip install -e .`)
4. Create central configuration at `~/.hotvect/config.json`
5. Set up directory structure for training data, outputs, and scratch space
6. Configure AWS credentials for S3 access
7. Validate the installation

**After setup, you'll have access to:**
- `hv` command - Main CLI for ML algorithm operations (audit, train, predict, backtest)
- `hv-ext` command - Extended utilities for data management and analysis
- Centralized configuration shared by all Claude agents and skills

## What does it not provide?

Hotvect does not include:

1. **Machine learning algorithms themselves** - It is intended to be combined with existing ML libraries.
2. **Orchestration of ML pipelines** - Requires other frameworks like Airflow.
3. **Life-cycle management of models and policies** - Supported by an external Experiment Management Service.
4. **Creation, management, and execution of online experiments** - Provided by the Experiment Management Service.
5. **Monitoring of ML applications and evaluation of online experiment results** - Requires separate solutions.

## Notes

Hotvect is designed to be library-agnostic, allowing integration with any ML library. Currently, the library must be accessible from a JVM process (via JNI or Java-compatible implementations like [H2O.ai's xgboost-predictor](https://github.com/h2oai/xgboost-predictor)). Future versions will support inter-process integrations.

Feature engineering should be implemented in a JVM language (e.g., Java, Kotlin, Scala), while APIs for triggering tasks like offline testing are provided as a Python library.
