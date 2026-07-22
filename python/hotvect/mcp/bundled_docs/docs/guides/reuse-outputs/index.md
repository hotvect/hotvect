---
title: Reuse parameters or cached stages
description: Choose between pinning one exact parameter ZIP and reusing compatible pipeline outputs from a cache
tags: [parameters, caching, training, pipeline]
difficulty: intermediate
related_docs:
  - ../caching/index.md
  - ../develop-algorithms/index.md
  - ../../concepts/artifacts-and-identity/index.md
---

# Reuse parameters or cached stages

Hotvect has two mechanisms with different meanings. Choose by the claim you need the run to support:

| Mechanism | Contract | Use it when |
| --- | --- | --- |
| `with_parameter` | Load this exact existing parameter ZIP or fail | Isolate inference from training, reproduce a deployed artifact, or compare code with fixed parameters |
| Cache | Reuse a compatible stage output when the cache key matches; otherwise run the stage and populate it | Shorten iterative train/backtest workflows without pinning one model |

Do not use a cache hit as a substitute for an explicit parameter pin when exact artifact identity is the point of the
experiment.

## Pin one exact parameter artifact

Set `hotvect_execution_parameters.with_parameter` on the algorithm that owns the artifact. It accepts an S3 URI or a
local file path:

```json
{
  "dependencies": {
    "example-model": {
      "hotvect_execution_parameters": {
        "with_parameter": "s3://example-bucket/hotvect-cache/example-model@1.2.3/runs/last_test_date_2000-01-15/train/predict-parameters.zip"
      }
    }
  }
}
```

When the file exists, Hotvect skips state generation, encode, and train for that algorithm and uses the selected ZIP.
When it does not exist, the run fails. Record the ZIP URI and parameter ID with the result so the inference comparison
remains reproducible.

Apply the fragment through `--algorithm-override`; do not copy experiment-only pins into the committed default
definition.

## Reuse compatible stage outputs

For a backtest or training loop, enable the cache at the command line:

```bash
hv backtest \
  --git-reference v1.1.0 \
  --algo-repo-url https://github.com/example-org/example-algorithm.git \
  --output-base-dir /tmp/example-output \
  --scratch-dir /tmp/example-scratch \
  --last-test-time 2000-01-07 \
  --cache s3://example-bucket/hotvect-cache/ \
  --cache-scope hyperparam
```

Use an S3 cache for SageMaker because container-local paths do not persist across jobs. Local workflows can use a
filesystem cache.

Depending on the effective cache mode, Hotvect can reuse generated state, encode parameters, encoded data, or packaged
model parameters. Prediction, evaluation, and performance testing consume those artifacts but are not themselves
cached.

Cache keys include the algorithm/configuration scope and parameter version. Date-partition encode caching is the
separate mechanism that lets adjacent training windows reuse successfully completed overlapping partitions.

Read [Caching](../caching/index.md) for cache modes, scope, layout, partition success markers, refresh behavior, and
how to verify a hit in `result.json`.

## Verify what happened

After either mechanism:

1. inspect `result.json` for `with_parameter`, cache-hit, run, and skipped-stage records;
2. confirm the effective definition contains the intended pin or cache settings;
3. record the parameter runtime identity used by prediction;
4. keep quality and performance claims separate from the artifact-reuse claim.
