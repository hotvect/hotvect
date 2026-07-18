---
title: Configuration and experimentation
description: How Hotvect keeps algorithm configuration, trained parameters, runtime selection, and experiments connected
tags: [concepts, configuration, experimentation, ems, variants]
---

# Configuration and experimentation

A decision system changes along several independent axes. Code can change, definition values can change, a new model
can be trained from the same code, and traffic can be assigned to a different released runtime. Hotvect keeps these
choices separate so that an offline result and an online decision can be traced to the same effective system.

## Three different configuration concerns

| Concern | Hotvect representation | Example question |
| --- | --- | --- |
| Decision behavior and workflow | Embedded algorithm definition plus an optional explicit override | Which factories, features, data window, or trainer settings were used? |
| Trained or generated state | Versioned parameter package | Which model weights or generated lookup data were loaded? |
| Runtime selection | Slot, default variant, experiment, and assigned variant | Which algorithm and parameter identity handled this assignment key? |

These concerns should not be collapsed into one “model version.” An override does not mutate the embedded definition,
a parameter package does not silently activate the configuration that produced it, and an experiment variant selects a
released runtime rather than redefining the algorithm inside the request path.

## From source configuration to a selected runtime

<div class="hv-flow">
  <div class="hv-flow__step"><span>01</span><strong>Base definition</strong><small>versioned with code</small></div>
  <div class="hv-flow__step"><span>02</span><strong>Explicit override</strong><small>one controlled change</small></div>
  <div class="hv-flow__step"><span>03</span><strong>Effective definition</strong><small>saved with evidence</small></div>
  <div class="hv-flow__step"><span>04</span><strong>Released runtime</strong><small>algorithm + parameters</small></div>
  <div class="hv-flow__step"><span>05</span><strong>Variant assignment</strong><small>slot · experiment</small></div>
</div>

The embedded definition is the reviewable default shipped in the algorithm package. An override is a partial JSON
patch applied to one workflow or load. Hotvect materializes the resulting effective definition in workflow metadata so
that a backtest or training result can be inspected later.

Offline workflows then produce a parameter package with its own identity. Publication and registration associate an
algorithm version with available parameter versions. Online selection chooses an exact algorithm and parameter
identity; the runtime loader downloads or reuses those packages and constructs the `AlgorithmInstance`.

Read [Algorithm definitions](../../reference/algorithm-definition/index.md) for patch semantics and
[Packages and identity](../artifacts-and-identity/index.md) for the identifiers carried by each artifact.

## Controlled offline iteration

A configuration experiment should remain reproducible even when it does not require a code change:

1. Start from a committed algorithm definition.
2. Put the intended change in an explicit override, including nested child overrides where needed.
3. Train or backtest with fixed input dates and retain the effective definition, package identities, outputs, and
   metrics.
4. Compare the candidate with its baseline using the same evaluation contract.
5. Register only the algorithm and parameter identity that passed the intended checks.

This is why Hotvect treats backtesting, configuration, and artifact identity as one lifecycle. A metric without its
effective definition and parameter identity is not enough evidence to reproduce the result.

## Online selection with the Experiment Management Service (EMS)

The current online utilities integrate with this external control plane. It models:

- slots with a stable default variant;
- active experiments assigned to shards;
- variants that reference algorithm and parameter metadata;
- allocation ratios and experiment ramp-up;
- explicit forced assignments.

The Java runtime client fetches the current default variant and active experiments for configured slots. On refresh it
resolves every referenced algorithm, downloads or reuses its algorithm and parameter packages, and atomically replaces
the immutable serving snapshot. Assignment is then local and deterministic for the supplied assignment key. Forced
assignments take precedence; otherwise slot shards, experiment allocation, variant allocation, and ramp-up determine
the selected variant.

The containing application still owns the slot and assignment key it supplies, failure behavior, request transport,
and operational controls. Hotvect owns conversion of the selected metadata into loaded algorithm instances.

`hv serve` exposes this path for local debugging through `--ems-url`, `--ems-slot`, and `--ems-assignment-key`. It is not
a production hosting service.

## Inspect EMS with `hv-exp`

`hv-exp` is the current read-only command-line view of experiment configuration. It emits JSON and does not create,
modify, ramp, or terminate experiments.

```bash
# See the default variant and active experiments in one slot
hv-exp slot get --slot-name example-slot

# Resolve the algorithms referenced by defaults and active experiments
hv-exp algorithm list-in-use --slot-name example-slot

# Inspect an experiment, its ramp-up history, and available online results
hv-exp experiment get --experiment-id 42
hv-exp experiment rampup-log --experiment-id 42
hv-exp experiment results list --experiment-id 42

# Inspect the definition and parameter records behind one variant
hv-exp algorithm get --algorithm-name example-ranker --algorithm-version 1.2.0
hv-exp algorithm parameter list --algorithm-name example-ranker --algorithm-version 1.2.0
```

The command tree also includes slot and experiment listing, default-variant listing, active-algorithm listing, parameter
record lookup, and online-result download. Use [the `hv-exp` reference](../../reference/cli/index.md#hv-exp) for the full
map and [Hotvect configuration](../../reference/config/index.md) for EMS connection settings.

## Current boundary and direction

| Surface | Status |
| --- | --- |
| Embedded definitions, explicit overrides, and saved effective definitions | Available now |
| Java EMS read client, state refresh, deterministic assignment, artifact loading, and runtime reuse | Available now |
| Python EMS client models and API client | Available now; connects to the external service |
| `hv-exp` inspection CLI | Available now and intentionally read-only |
| EMS control-plane server and its persistence/API implementation | External to Hotvect today |
| Integrated Hotvect configuration and experimentation server | Direction, not part of the current release |

The Python `ExperimentManagementClient` already contains both read and mutation methods for the external EMS API,
including algorithm and parameter registration, experiment lifecycle and ramp-up, default promotion, and forced
assignments. Those methods are clients of the external control plane; they are not a server implementation. `hv-exp`
deliberately exposes only the inspection subset.

See the [Python EMS client reference](../../reference/python-ems-client/index.md) for the exact read/mutation boundary
and authentication setup.

The direction is to bring the EMS server into Hotvect so algorithm definitions, parameter releases, runtime selection,
experiment configuration, and inspection form one coherent product surface. That should not blur the boundaries above:
effective configuration must remain inspectable, assignments must resolve to exact runtime identities, and online
evaluation must remain attributable to the variant that produced it.

## Next steps

- [Take a change to a live experiment](../../guides/change-to-live-experiment/index.md)
- [Override files](../../guides/patterns/override-files/index.md)
- [Branching and versioning](../../guides/patterns/branching-and-versioning/index.md)
- [Online runtime and application embedding](../../architecture/online-runtime/index.md)
- [`hv-exp` command reference](../../reference/cli/index.md#hv-exp)
