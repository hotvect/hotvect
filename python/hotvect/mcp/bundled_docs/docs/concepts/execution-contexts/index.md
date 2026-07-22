---
title: Execution contexts
description: Understand Hotvect workload mode and input semantics without conflating realtime, batch, online, and offline
tags: [concepts, execution, realtime, batch, online, offline]
---

# Execution contexts

Hotvect describes execution with two independent axes. Factories receive an `ExecutionContext` containing both values
so they can select the correct runtime configuration deliberately.

## The two axes

| Axis | Values | Describes |
| --- | --- | --- |
| Workload mode | `REALTIME`, `BATCH` | Latency, throughput, worker, and resource behavior expected from the runtime |
| Input semantic | `ONLINE`, `OFFLINE` | Whether the input comes from a live request contract or an offline example/data path |

“Realtime” does not mean “online,” and “batch” does not mean “offline.” An offline benchmark can intentionally exercise
the realtime runtime configuration.

## Common contexts

| Operation | Workload mode | Input semantic | Reason |
| --- | --- | --- | --- |
| Production request-serving integration | Realtime | Online | Live request with latency-sensitive runtime behavior |
| Offline prediction | Batch | Offline | Dataset-driven inference optimized for offline throughput |
| Default performance test | Realtime | Offline | Recorded examples exercise the serving-oriented runtime configuration |
| Local `hv serve` debugging | Batch | Offline | The current local server decodes recorded/offline examples; it is not production hosting |

Individual commands can expose a workload override. For example, `hv performance-test --workload-mode batch` measures
the batch path explicitly; omitting it normally measures realtime behavior over offline rows.

## What factories should do

Factories should select only behavior that belongs to the execution context, such as worker scope or batching policy.
They should not infer environment from filenames, paths, or whether a request happens to be running on a developer
machine.

The input semantic also does not prove parity. Online and offline inputs may differ in representation, attached data,
or surrounding services even when the same algorithm and parameter packages are loaded.

## Related documentation

- [Online runtime](../../architecture/online-runtime/index.md)
- [Offline lifecycle](../../architecture/offline-lifecycle/index.md)
- [Performance benchmarking](../../guides/performance-benchmarking/index.md)
- [Online/offline parity](../../guides/online-offline-parity/index.md)
