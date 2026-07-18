---
title: Validate and investigate
description: Choose the right Hotvect validation or diagnostic workflow before making a quality, parity, or performance claim
tags: [validation, evaluation, debugging, performance]
---

# Validate and investigate

Start with the claim you need to support. Quality, inference equivalence, feature parity, and system performance use
different evidence.

| Question | Start here | Primary evidence |
| --- | --- | --- |
| Did model quality change? | [Evaluation metrics and uncertainty](../../reference/evaluation-metrics/index.md) | Comparable `result.json` quality estimates |
| Did two JARs preserve inference with fixed parameters? | [Score equivalence testing](../score-equivalence/index.md) | Ordered predictions and `comparison.json` |
| Where did feature values diverge? | [Feature audits](../feature-audits/index.md) | Ordered audit part files |
| Is online behavior different from offline replay? | [Online/offline parity](../online-offline-parity/index.md) | Same-version replay and live request evidence |
| Is a system-performance change real? | [Reliable performance benchmarking](../performance-benchmarking/index.md) | Matching benchmark contracts and replicated runs |
| Which stage caused a regression? | [Performance investigations](../performance-investigations/index.md) | Stage timings, artifact parity, and effective runtime |

Do not turn one result into a broader claim. In particular:

- fixed-parameter prediction equivalence does not prove retraining parity;
- quality metrics do not prove latency or throughput parity;
- a local microbenchmark does not prove end-to-end job improvement;
- a mixed benchmark-specification report is not a system-performance comparison.

For command flags and output paths, use the [CLI reference](../../reference/cli/index.md).
