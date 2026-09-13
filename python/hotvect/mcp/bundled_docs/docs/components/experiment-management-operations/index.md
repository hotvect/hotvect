---
title: EMS operations and state transitions
description: Release, slot, experiment, variant, assignment, and history operations exposed by the EMS API
tags: [components, ems, api, operations, lifecycle, state]
related_docs:
  - ../experiment-management-service/index.md
  - ../experiment-management-domain/index.md
  - ../experiment-assignment/index.md
  - ../ems-clients-and-tools/index.md
  - ../../reference/python-ems-client/index.md
---

# EMS operations and state transitions

EMS mutations change the control-plane state that online runtimes will install on a later refresh. They do not upload
artifacts, retrain an algorithm, or synchronously update every serving process.

Use the Python client from reviewed automation when possible. The endpoint map below explains the server behavior;
the deployment owner's generated OpenAPI document remains authoritative for endpoint, payload, and response schemas.

## Release an algorithm runtime

An algorithm becomes eligible for a slot through this sequence:

```text
publish JAR
  → create algorithm record (NEW)
  → publish parameter ZIP
  → create parameter record
  → activate algorithm (ACTIVE)
  → promote it or connect it to a variant
```

Creating an algorithm requires an immutable absolute JAR location and training-image name. Creating a parameter
requires a globally unique parameter ID, an existing algorithm name/version, and an immutable `absolute_s3_path`.
Neither API uploads or verifies the artifact bytes.

The algorithm state machine is intentionally small:

| From | To | Condition |
| --- | --- | --- |
| creation | `NEW` | The algorithm record was accepted. |
| `NEW` | `ACTIVE` | At least one parameter record exists. |
| `ACTIVE` | `INACTIVE` | No active variant uses the algorithm. |
| `INACTIVE` | `ACTIVE` | At least one parameter record exists. |

Activation fails unless at least one parameter record exists. Deactivation fails while any non-terminated variant
references that algorithm. Repeating the current state is not a valid transition.

## Understand “latest parameter”

Variants reference an algorithm name and version, not a pinned parameter ID. The serving snapshot resolves the latest
parameter for that algorithm by creation time, using parameter ID as the tie-break.

This has an important operational consequence:

!!! warning "Parameter registration changes serving selection"

    Registering a newer parameter record changes the resolved runtime for every active default or experiment variant
    that references that algorithm version after each online client successfully refreshes. No variant update is
    required.

Publish and verify the parameter ZIP before registration, then read the algorithm and affected slot snapshots back.
Use a new algorithm version when a parameter change must not affect all variants that share the existing version.

## Create a slot

Slot creation requires:

- a name containing lowercase letters, digits, and hyphens;
- between 1 and 100 shards;
- an `ACTIVE` algorithm with at least one parameter.

The transaction creates the slot, its initial active salt, numbered shards starting at 1, and a default variant for
the supplied algorithm. There is no general slot resize operation. The online refresher also treats the shard count
from its first successful load as immutable for that process lifetime.

## Promote a default algorithm

Promotion requires an `ACTIVE` algorithm. It terminates the slot's current default variant and creates a new default
variant pointing to the promoted algorithm.

Terminating the old variant removes any user forced assignments that pointed to it. Promotion does not terminate the
slot's active experiments; their variants continue to reference their configured algorithms. Read the complete slot
snapshot after promotion before treating it as effective.

## Create and operate an experiment

Experiment creation requires:

- at least one variant;
- exactly one control variant;
- an `ACTIVE` algorithm for every variant;
- a positive allocation ratio for every variant, defaulting to `1`;
- a shard reservation count from 0 to 100 that does not exceed the currently free shards;
- a ramp-up percentage from 0 to 100.

Creation reserves currently free shards and records the initial ramp-up state. The allocation ratios divide traffic
inside the experiment cohort; ramp-up independently determines how much of each selected variant is exposed rather
than sent to the slot default.

Changing ramp-up is allowed only while the experiment is active and appends to ramp-up history. Termination:

1. releases the experiment's shards;
2. terminates all of its variants;
3. deletes user forced assignments targeting those variants;
4. records the terminal ramp-up and shard state.

A terminated experiment cannot be ramped or terminated again.

## Change a variant's algorithm

An active default or experiment variant can be moved to another algorithm with
`PATCH /slots/{slotName}/variants/{variantId}/updateAlgorithm`.

The target algorithm must be `ACTIVE` and have a parameter record. The operation rejects a terminated variant and
records a variant-algorithm history entry when the identity changes. Sending the algorithm already connected to the
variant is an idempotent no-op.

Because variants resolve the target algorithm's latest parameter, this operation changes both code identity and the
parameter selected for that code line.

## Force one assignment key

`PUT /slots/{slotName}/userForcedAssignments/{userId}` creates or replaces one slot-local assignment. The selected
variant must belong to that slot and must not be terminated. The same key can have a different forced assignment in a
different slot.

The runtime checks this mapping before shard, experiment, allocation, and ramp-up logic. Delete it explicitly when the
verification is complete. EMS also removes it automatically if its target variant is terminated.

The API field is named `userId` for compatibility, but the runtime compares it with the exact assignment-key string
supplied by the containing application. EMS does not validate whether that key represents a customer, session, device,
or another domain identity.

## Rotate a slot salt

Salt refresh terminates the active salt and creates a new one. The deployment must explicitly enable
`custom-properties.slot-salts-refresh-enabled`; otherwise the mutation returns a conflict.

Changing the salt redistributes all non-forced assignment hashes after runtimes refresh. It is not a routine cache
refresh and should be treated as a coordinated traffic operation.

## HTTP resource map

| Resource | Read operations | Mutations |
| --- | --- | --- |
| Algorithms | `GET /algorithms`, `/active`, `/with-active-variants`, `/{name}/{version}`, latest parameter | `POST /algorithms`, `PATCH /algorithms/{name}/{version}`, `PUT .../promote` |
| Parameters | `GET /algorithm-parameters`, `/{id}`, `/by-algorithm/{name}/{version}` | `POST /algorithm-parameters` |
| Slots | `GET /slots`, `/{slot}`, active serving snapshot, salts | `POST /slots`, `PATCH /slots/{slot}/salts/refresh` |
| Experiments | list and get below `/slots/{slot}/experiments` | create, change ramp-up, terminate |
| Variants | list all/active and get below `/slots/{slot}/variants` | update algorithm |
| Forced assignments | list and get below `/slots/{slot}/userForcedAssignments` | upsert and delete |
| Shards | list and get below `/slots/{slot}/shards` | Allocated indirectly through experiment operations |

The online Java client centers on:

```text
GET /slots/{slotName}/defaultVariantAndActiveExperiments
```

That endpoint is the denormalized serving snapshot: active salt, fixed shard count, default variant, active
experiments and variants, forced assignments, and resolved latest artifact locations.

## Audit history

EMS records control-plane transitions separately from the current resource view:

| History | Endpoint | Appended when |
| --- | --- | --- |
| Algorithm state | `GET /algorithm-state-logs` | Algorithm creation and each accepted state change |
| Variant algorithm | `GET /slots/{slot}/variantAlgorithmLogs` | Variant creation and algorithm change |
| Shard allocation | `GET /slots/{slot}/shard-logs` | Shard creation, experiment allocation, and release |
| Experiment ramp-up | `GET /slots/{slot}/experimentRampUpLog` | Experiment creation, ramp-up change, and termination |
| Slot salts | `GET /slots/{slot}/salts` | Initial salt and each rotation, including termination times |

These histories explain control-plane changes; they do not prove when every serving process installed the new
snapshot. Correlate them with runtime refresh health and request-level variant, algorithm, and parameter attribution.

Continue with [Traffic assignment and ramp-up](../experiment-assignment/index.md) for request-time selection or the
[Python EMS client](../../reference/python-ems-client/index.md) for typed operations.
