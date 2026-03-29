# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## NO. FUCKING. DEFENSIVE. PROGRAMMING!!!!

**JUST FUCKING FAIL**

DO NOT:
- Catch exceptions "just in case"
- Return fallback values when operations fail
- Add if/else checks for "safety"
- Silently handle errors
- Use try/except unless absolutely necessary

INSTEAD:
- Let exceptions bubble up
- Fail fast and loudly
- Assume things work as expected
- Only catch exceptions when you can meaningfully handle them

If something should work, assume it works. Don't implement "just in case" paths.

**FAIL LOUDLY. FAIL FAST. NO DEFENSIVE BULLSHIT.**

## Repository Structure

HotVect is a feature engineering and ML serving library for machine learning applications, especially for personalization and recommendation systems. It's a hybrid Java/Python project with the following structure:

**Core Components:**
- `hotvect-api/` - Core API definitions and interfaces
- `hotvect-core/` - Core feature transformation and processing logic  
- `hotvect-vw/` - Vowpal Wabbit integration (logistic regression)
- `hotvect-catboost/` - CatBoost integration (gradient boosting)
- `hotvect-online-util/` - Online serving utilities and hot-deployment
- `hotvect-offline-util/` - Offline CLI tools and batch processing
- `hotvect-integration-test/` - Integration tests
- `python/` - Python CLI interface and utilities

**Key Architecture Patterns:**
- **Multi-module Maven project**: Java modules with version 9.26.0
- **Python CLI wrapper**: Provides `hv` and `hv-ext` commands that invoke Java utilities
- **Algorithm hot-deployment**: Loads algorithm JARs dynamically for flexible model serving
- **Feature transformation pipeline**: Shared code between offline training and online serving
- **ML library integration**: Abstracts Vowpal Wabbit, CatBoost, and other ML backends

## Development Commands

### Java Build (Maven)
```bash
# Full build with tests
mvn clean install

# Fast build without tests/javadocs  
mvn clean install -DskipTests -Dmaven.javadoc.skip=true

# Build specific module
mvn clean package -pl hotvect-offline-util -am -DskipTests

# Integration tests
mvn clean install -Pintegration-test
```

### Python Environment Setup
```bash
cd python/

# Initialize with all dependencies
uv sync --all-extras

# Quick build (compiles Java and copies JAR)
make quick

# Full test (includes Java integration tests)
make test

# Install hotvect Python package in development mode
uv pip uninstall hotvect && uv pip install -e .
```

**Critical Python Setup Notes:**
- Always activate the virtual environment: `source .venv/bin/activate`
- Use `uv sync --extra sagemaker --group dev` for full dependency installation
- After Java changes, run: `make quick` followed by `uv pip uninstall hotvect && uv pip install -e .`
- The Python package wraps Java utilities in `hotvect-offline-util-9.26.0-jar-with-dependencies.jar`

### Version Management
Version updates must be coordinated across:
- Java `pom.xml` files (parent and all modules)
- Python `pyproject.toml` and `uv.lock`  
- `delivery.yaml` HOTVECT_VERSION variable

After version updates:
1. Rebuild Java modules: `mvn clean package -pl hotvect-offline-util -am -DskipTests`
2. Copy updated JAR: `python scripts/copy_jar.py`

### Algorithm Version Compatibility

**CRITICAL**: Hotvect is backward compatible across versions. Algorithm JARs built with different hotvect versions work fine with any hotvect CLI version.

**Examples that work:**
- Algorithm built with hotvect 9.29.1, CLI is 9.32.0 ✅
- Algorithm built with hotvect 9.28.0, CLI is 10.0.0 ✅
- Works across major versions ✅

**Never rebuild algorithms to match hotvect versions.** Just use the currently installed hotvect version for all operations.

## Core CLI Tools

### `hv` Command (Primary Interface)
Main operations for ML algorithm development and testing:

```bash
# Generate feature audit (debug feature transformations)
hv audit --algorithm-jar algo.jar --algorithm-name my-algo --source-path data.jsonl --dest-path audit.jsonl

# Performance benchmarking
hv performance-test --algorithm-jar algo.jar --algorithm-name my-algo --source-path data.jsonl

# Data encoding for training
hv encode --algorithm-jar algo.jar --algorithm-name my-algo --source-path data.jsonl --dest-path encoded.data

# Generate predictions
hv predict --algorithm-jar algo.jar --algorithm-name my-algo --source-path data.jsonl --dest-path predictions.jsonl --parameter-path model.params

# List available transformations
hv list-transformations --algorithm-jar algo.jar --algorithm-name my-algo

# Train ML model with full pipeline
hv train --algorithm-name my-algo --data-base-dir /path/to/data --output-base-dir /path/to/output --algorithm-jar algo.jar --last-test-time 2025-08-09

# Run backtest comparison
hv backtest --git-reference main --git-reference feature-branch --algo-repo-url https://github.com/company/algo.git --data-base-dir /path/to/data --output-base-dir /path/to/output --scratch-dir /tmp/backtest --last-test-time 2025-08-09
```

### `hv-ext` Command (Extended Utilities)
Utility operations for data analysis and result management:

```bash
# Compare performance test results
hv-ext perf-compare baseline.json treatment.json --format table

# Compare JSONL files with field renaming
hv-ext jsonl-compare old.jsonl new.jsonl -c renamings.json

# Download SageMaker backtest results
hv-ext download-results --s3-base-prefix s3://bucket/results/ --dest-base-dir ./results --from-date 2025-06-01 --to-date 2025-06-15

# Download training data dependencies
hv-ext download-data-dependency --repo-url https://github.com/company/algo.git --git-reference v77.0.0 --s3-base-dir s3://bucket/tables --local-data-dir ./data --scratch-dir ./temp --last-test-time 2025-08-09

# Convert CatBoost data formats
hv-ext catboost-convert --schema-file model.schema --encoded-file data.tsv --output data.jsonl

# Compare ML evaluation results
hv-ext compare-evaluations baseline/result.json treatment/result.json
```

### `hv-assist` Command (Agentic Assistant)
Interactive AI assistant for ML backtesting with persistent sessions:

```bash
# Launch interactive ML backtest assistant
hv-assist
```

Features:
- Persistent chat sessions with conversation history
- Algorithm comparison and backtest automation
- Smart data dependency management
- Configurable themes and AI model selection
- HotVect brand styling (🌶️ chili pepper emoji, Miramare color palette)

## Key Algorithms and Feature Engineering

### Algorithm Definition Structure
Algorithms are defined through factory classes implementing core interfaces:
- `AlgorithmFactory` - Main algorithm construction
- `VectorizerFactory` - Feature transformation pipeline
- `ExampleEncoderFactory` - Data encoding for ML libraries
- `RewardFunctionFactory` - Learning objectives

### Feature Transformation Pipeline
1. **Raw Data Input** - JSON records with nested feature namespaces
2. **Computing Transformations** - Memoized feature calculations with dependencies
3. **Interaction Features** - Cross-namespace feature combinations
4. **Vectorization** - Sparse vector generation with hashing
5. **ML Library Encoding** - VW/CatBoost format output

### Typical Algorithm Development Workflow
1. **Define feature transformations** in Java (Computing* classes)
2. **Create algorithm definition JSON** specifying components
3. **Build algorithm JAR** with Maven
4. **Run audit** to debug feature calculations: `hv audit`
5. **Generate training data** with encoding: `hv encode`  
6. **Train model** using ML library integration: `hv train`
7. **Run predictions** for evaluation: `hv predict`
8. **Compare algorithm versions** with backtesting: `hv backtest`

## Docker and Deployment

### Training Image
The project builds Docker images for SageMaker training:
- Base image: `python:3.11-slim`
- Includes Java 17, Maven, Python dependencies, and hotvect JAR
- Tags: `{team}/hotvect:{version}` for releases, `{team}/hotvect:{version}.dev{counter}` for PRs

### CI/CD Pipeline (delivery.yaml)
- **Linting**: Pre-commit hooks and version validation
- **Java Build**: Maven compilation and testing
- **Python Testing**: PyTest with hypothesis property testing
- **Documentation**: MkDocs site generation
- **Docker Images**: Training container builds for staging/production
- **Publishing**: Maven artifacts to Zalando Nexus, Python packages to internal PyPI

## Testing Strategy

### Java Testing
- **Unit tests**: JUnit 5 with Mockito mocking
- **Property testing**: jqwik for randomized testing
- **Integration tests**: Full pipeline testing with real algorithm JARs

### Python Testing  
- **Unit tests**: PyTest with Hypothesis property testing
- **JAR Integration**: Tests invoke Java CLI through subprocess
- **Fixture Management**: Test data in `test/unit/testfiles/`

### Algorithm Audit Comparison
Common workflow for validating algorithm changes:
```bash
# Generate audit for baseline version
hv audit --algorithm-jar v64.4.0.jar --algorithm-name my-algo --source-path test_data.jsonl --dest-path v64-audit.jsonl

# Generate audit for new version  
hv audit --algorithm-jar v77.0.0.jar --algorithm-name my-algo --source-path test_data.jsonl --dest-path v77-audit.jsonl

# Compare with field renaming support
hv-ext jsonl-compare v64-audit.jsonl v77-audit.jsonl -c renamings.json
```

## Performance and Optimization

### JVM Configuration
- **Memory**: `-Xmx256g` default for CLI operations, configurable via `--extra-jvm-args`
- **GC**: `-XX:+UseG1GC` recommended for large datasets
- **OOM Handling**: `-XX:+ExitOnOutOfMemoryError` for fail-fast behavior

### Data Processing
- **Out-of-core**: Streaming processing without loading full datasets
- **Multi-threading**: Configurable parallelism for encoding/prediction
- **Efficient Libraries**: Fastutil collections, parallel gzip compression

### Algorithm Optimization
- **Memoization**: Automatic caching of expensive feature computations
- **Sparse Vectors**: Memory-efficient feature representation
- **Hot Deployment**: Dynamic JAR loading without process restart

## Development Workflow

### Git Commits
**⚠️ ALWAYS ASK BEFORE COMMITTING ⚠️**

- **NEVER commit changes without explicit user approval**
- Always ask the user before running `git commit` or `git push`
- Show what changes will be committed using `git diff --staged` first
- Wait for explicit user confirmation before proceeding with commits
- Only commit when the user specifically requests it

## Coding Principles

### No Defensive Programming
**⚠️ DO NOT CODE DEFENSIVELY ⚠️**

User forbids unreasonable defensive programming. When something fails, FAIL LOUDLY. Do not:

- **Silently return fallback values**: `return "algorithm"` when pom.xml parsing fails ❌
- **Pretend operations succeeded**: "• Detected algorithm name: algorithm" when nothing was detected ❌
- **Hide errors with try/except**: Only catch exceptions when you can meaningfully handle them ❌
- **Use fallback logic**: If something should work, assume it works. Don't implement "just in case" paths ❌

**Fail fast and clearly**:
- `raise FileNotFoundError(f"pom.xml not found at {path}")` ✅
- `raise ValueError(f"No <artifactId> found in {path}")` ✅
- Let XML parsing exceptions bubble up ✅
- Exit with clear error messages ✅

**Documented Defensive Patterns to Remove**:
See `DEFENSIVE_PROGRAMMING_ANALYSIS.md` for complete analysis of defensive patterns in HV-Assist and backtest code that need elimination:

1. **Silent Exception Swallowing in utils.py**: Functions like `is_git_repository()`, `get_git_root()`, `get_git_remote_url()` that return False/None on ANY exception
2. **Config Parsing Fallbacks**: JSON parsing errors returning empty configs instead of failing
3. **Process Monitoring Failures**: Background process monitoring that silently fails with `pass`
4. **Version Sorting Fallbacks**: Semantic version parsing falling back to string sorting
5. **Terminal Operation Fallbacks**: Degraded terminal experience without user notification

**Exception**: Only use defensive programming when explicitly required for legitimate operational reasons (network timeouts, external service failures, etc.).

### Default Arguments
**⚠️ BE VERY WARY OF DEFAULT ARGUMENTS ⚠️**

Default arguments should be used sparingly and only when they represent truly optional parameters with sensible fallback behavior. Avoid defaults that:

- **Assume business logic**: `def process_data(algorithm="example-algorithm")` ❌
- **Hide required context**: `def get_keypress(keys=['y','n'], default='y')` ❌  
- **Make APIs ambiguous**: `def connect(host="localhost", port=8080)` ❌

**Good defaults** (acceptable):
- `def __init__(self, config=None):` ✅ (None with proper fallback)
- `def process(data, timeout=30):` ✅ (Standard timeout value)
- `def log(message, level=INFO):` ✅ (Standard log level)

**General rule**: If you have to think about what the default should be, don't use a default. Make the caller explicit about their intent.

### Method Design
- **Explicit over implicit**: Force callers to be explicit about their requirements
- **Single responsibility**: Each method should do one thing well
- **Clear interfaces**: Method signatures should be self-documenting

### Code Style
- **Use imports over fully qualified names**: Always prefer `import` statements over fully qualified class names in code (e.g., `import java.util.Collections;` then use `Collections.emptyMap()` instead of `java.util.Collections.emptyMap()`)