---
title: EMS traffic assignment and ramp-up
description: How the online runtime turns slot state and a stable assignment key into a deterministic variant
tags: [components, ems, assignment, shards, ramp-up, forced-assignment]
related_docs:
  - ../experiment-management-domain/index.md
  - ../ems-runtime-client/index.md
  - ../../guides/connect-online-runtime-to-ems/index.md
---

# EMS traffic assignment and ramp-up

EMS publishes the active state of a slot. The online runtime downloads that state during refresh and assigns requests
locally; request processing does not call EMS.

## Assignment order

<div class="hv-flow">
  <div class="hv-flow__step"><span>01</span><strong>Forced override</strong><small>select immediately</small></div>
  <div class="hv-flow__step"><span>02</span><strong>Slot shard</strong><small>free → default</small></div>
  <div class="hv-flow__step"><span>03</span><strong>Experiment</strong><small>reserved shard</small></div>
  <div class="hv-flow__step"><span>04</span><strong>Variant weight</strong><small>deterministic split</small></div>
  <div class="hv-flow__step"><span>05</span><strong>Ramp-up</strong><small>outside → default</small></div>
</div>

The order is significant:

1. A matching user forced assignment selects its variant immediately.
2. Otherwise, the runtime combines the slot salt with the assignment key and hashes into one numbered slot shard.
3. A free shard selects the default variant. A shard reserved by an experiment proceeds into that experiment.
4. The runtime uses a separate deterministic hash for that experiment and selects a variant according to the sum of
   its positive allocation weights.
5. A third deterministic bucket applies the experiment's ramp-up percentage. Requests outside the admitted percentage
   receive the slot default variant.

## Three different traffic controls

Shard reservation, variant allocation, and ramp-up solve different problems:

| Control | Question it answers | Example |
| --- | --- | --- |
| Experiment shards | Which part of slot traffic belongs to this experiment? | Reserve 20 of 100 slot shards |
| Variant allocation ratios | How is the experiment cohort divided? | Control `1`, treatment `1` gives an even split |
| Ramp-up percentage | How much of each chosen experiment variant is actually exposed? | At 10%, 90% still receives the default variant |

Changing one control does not rewrite the other two. This lets operators reserve a stable experiment cohort, maintain
the intended control/treatment proportions, and gradually increase actual exposure.

## Stickiness and salts

For a non-null stable assignment key, assignment is deterministic for a particular slot salt and experiment state. The
containing application decides whether that key represents a customer, session, device, or another domain identity.
Every request path that requires the same assignment must use the same value.

A null assignment key is treated as test traffic and receives a random value for that call, so it is not sticky.

Refreshing the slot salt changes the input to all three hashes and therefore redistributes non-forced traffic. Use salt
rotation only when that redistribution is intentional, and ensure online runtimes have refreshed before evaluating the
new distribution.

## Ramp-up changes

Ramp-up is an integer percentage from zero to one hundred:

- `0` sends the experiment's reserved shards to the default variant;
- intermediate values admit a deterministic percentage of the selected experiment cohorts;
- `100` returns the chosen experiment variant without the ramp-up fallback.

Because ramp-up uses its own stable bucket, increasing the percentage admits a superset of the previously admitted
keys for unchanged salt and experiment identity. Ramp-up changes are recorded in EMS history.

## Runtime consistency

Each refresh resolves every referenced algorithm and parameter package before installing a new immutable serving
snapshot. If a later refresh fails, the previous complete snapshot remains active. This prevents request threads from
observing half of a control-plane update.

Read [Runtime state, refresh, and assignment](../ems-runtime-client/index.md) for artifact loading, failure behavior, and
application lifecycle ownership.
