---
description: Benchmark algorithm throughput and latency for production readiness assessment
---


## 🌶️ STEP 0: READ CONFIG FIRST (MANDATORY)

**Before doing ANYTHING else, read the central configuration:**
```bash
cat ~/.hotvect/config.json
```

Extract and use:
- `directories.data_base_dir` - Where test data lives
- `hotvect_source_dir` - Where hotvect Python venv is
- Then activate: `source ${hotvect_source_dir}/python/.venv/bin/activate`

See `../COMMON_PREAMBLE.md` for full discovery strategies.


You are executing the performance-test command to measure algorithm performance characteristics.

## Purpose

Benchmark algorithm throughput (records/second) and latency percentiles (p50, p95, p99) to assess production readiness and identify performance bottlenecks.

## Required Arguments

Parse from user or ask:
- `--algorithm-jar`: Path to algorithm JAR file
- `--algorithm-name`: Name of the algorithm transformation
- `--source-path`: Input data file for benchmarking

## Optional Arguments

- `--parameter-path`: Path to trained model parameters (required if algorithm uses trained model)
- `--samples`: Limit number of records to process (default: all)
- `--metadata-path`: Path where operation metadata will be saved (auto-generated if not specified)
- `--dest-path`: Path where results will be saved (auto-generated if not specified)

## Example Execution

```bash
# Basic performance test (no trained parameters)
hv performance-test \
  --algorithm-jar ~/.m2/repository/.../algorithm-1.0.0.jar \
  --algorithm-name my-algorithm \
  --source-path /path/to/test-data.jsonl \
  --samples 100000

# With trained parameters
hv performance-test \
  --algorithm-jar ~/.m2/repository/.../algorithm-1.0.0.jar \
  --algorithm-name my-algorithm \
  --source-path /path/to/test-data.jsonl \
  --parameter-path /path/to/trained-model.parameters.zip \
  --samples 100000
```

## What It Does

1. Loads algorithm and parameters
2. Warms up JVM (avoids cold start bias)
3. Processes records and measures timing
4. Calculates throughput and latency percentiles
5. Outputs performance metrics as JSON

## Output Metrics

- **Throughput**: Records processed per second
- **Latency percentiles**:
  - p50 (median)
  - p95 (95th percentile)
  - p99 (99th percentile)
  - p999 (99.9th percentile)

## Production Readiness Criteria

**Good performance (typically):**
- Throughput: >1000 records/second
- p99 latency: <10ms

**Factors affecting performance:**
- Feature complexity
- Number of features
- Model size
- Data encoding overhead

## Next Steps

After performance test:
- If slow: use `/performance-profiling-agent` for detailed analysis
- Compare with baseline: use `/perf-compare`
- Optimize hot paths identified in profiling

## Tips

- Run on representative production data
- Use sufficient samples (100k+) for reliable metrics
- Compare before/after optimization
- Check if performance meets production SLAs
