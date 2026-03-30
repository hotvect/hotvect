---
description: Compare performance test results between two algorithm versions
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


You are executing the perf-compare command to compare performance benchmarks between algorithm versions.

## Purpose

Compare throughput and latency metrics from two performance test runs to assess if changes improved or degraded performance.

## Required Arguments

Parse from user or ask:
- Two performance test result JSON files to compare

## Example Execution

```bash
hv-ext perf-compare \
  baseline-performance.json \
  treatment-performance.json \
  --format table
```

## Input Format

Performance test JSON files from `/performance-test`:
```json
{
  "throughput_records_per_sec": 1523.4,
  "latency_p50_ms": 0.62,
  "latency_p95_ms": 1.24,
  "latency_p99_ms": 2.18,
  "latency_p999_ms": 4.51
}
```

## Output

Comparison showing:
- Throughput difference (higher is better)
- Latency changes for each percentile (lower is better)
- Percentage improvements/regressions

**Example output:**
```
Performance Comparison
=====================

Throughput:
  Baseline:   1523.4 rec/sec
  Treatment:  1687.2 rec/sec
  Change:     +10.7% ✓ IMPROVEMENT

Latency p50:
  Baseline:   0.62 ms
  Treatment:  0.58 ms
  Change:     -6.5% ✓ IMPROVEMENT

Latency p99:
  Baseline:   2.18 ms
  Treatment:  2.89 ms
  Change:     +32.6% ✗ REGRESSION
```

## Interpretation

**Good changes:**
- Throughput increase
- Latency decrease across percentiles

**Bad changes:**
- Throughput decrease
- Latency increase (especially p99, p999)

**Trade-offs:**
- Sometimes latency increases slightly for throughput gains
- Assess if trade-off is acceptable for production

## Use Cases

- Validate optimization improved performance
- Assess performance regression from new features
- Compare different algorithm implementations
- Make go/no-go decisions for deployment

## Next Steps

After comparison:
- If regression: investigate with `/performance-profiling-agent`
- If improvement: document optimization for knowledge sharing
- If trade-off: discuss with team for decision

## Tips

- Run performance tests on same hardware for fair comparison
- Use sufficient samples (100k+) for reliable metrics
- Focus on p99/p999 latency for production systems
- Consider throughput vs latency trade-offs
