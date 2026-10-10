---
title: Python EMS client
description: Read and intentionally mutate a configured Experiment Management Service through Hotvect's generated typed Python client
tags: [reference, python, ems, experimentation]
---

# Python EMS client

`hotvect.experiment_management` provides a synchronous typed client for a configured Experiment Management Service
(EMS) endpoint. Its models and internal operation surface are generated bindings for the
[EMS server API](../ems-server-api/index.md); it is not an EMS release artifact or
server. Every variant has one required `algorithm`; plural algorithm selections and slot roles are not part of this
contract. Missing required fields and malformed known fields are rejected. Additional response fields are ignored,
including fields in nested objects, so additive EMS changes do not require a client upgrade.

## Create a client

Pass the service root explicitly and choose an authentication adapter. This example reads both values from the
environment and sets separate connection and response timeouts:

```python
import os

from hotvect.experiment_management import (
    ExperimentManagementClient,
    TokenProviderAuth,
)

client = ExperimentManagementClient(
    base_url=os.environ["EMS_URL"],
    auth=TokenProviderAuth(lambda: os.environ["EMS_TOKEN"]),
    connect_timeout=5.0,
    read_timeout=15.0,
)
```

`ExperimentManagementClient` owns HTTP transport and is the only public operation surface. It delegates its named
methods to generated internals. Requests raise the normal `requests` HTTP exception for every non-success response.

## Read surface

| Concern | Methods |
| --- | --- |
| Slots and serving state | `get_slots`, `get_slot`, `get_default_variant_and_active_experiments`, `get_slot_salts` |
| Experiments | `get_experiment`, `get_experiments`, `get_experiment_ramp_up_logs` |
| Algorithms and parameters | `get_algorithms`, `get_algorithm`, `get_active_algorithms`, `get_algorithms_with_active_variants`, `get_latest_algorithm_parameter`, `get_algorithm_parameters`, `get_algorithm_parameters_by_algorithm`, `get_algorithm_parameter`, `get_algorithm_state_logs` |
| Variants and assignment history | `get_variants`, `get_variant`, `get_active_variants`, `get_variant_algorithm_logs`, `get_user_forced_assignments`, `get_user_forced_assignment` |
| Shards | `get_shards`, `get_shard`, `get_shard_logs` |

`hv exp` exposes inspection as JSON, handles configured online-result partitions, and provides typed mutation
commands with an EMS approval workflow. Prefer those commands for operator shell workflows.

`get_algorithm(name, version)` returns `AlgorithmWithLatestParameter`. Its `latest_algorithm_parameter` field is
always present: either an `AlgorithmParameterSummary` containing `algorithm_parameter_id` and
`absolute_s3_algorithm_parameter_path`, or `None`. `parameter_mode` distinguishes an untrained `REQUIRED` algorithm
from a `NONE` algorithm. Use `get_latest_algorithm_parameter` when the full parameter record is needed; it returns
`None` for `NONE` algorithms and raises an HTTP error when a `REQUIRED` algorithm has no parameter yet.

This contract requires a compatible EMS release that serves the registration responses described above.
Existing EMS clients retain the single-algorithm response shape; algorithm writers still need the coordinated
parameter-mode migration described in the EMS server release notes.

## Mutation surface

These calls change configured control-plane state. The library methods send native requests and propagate EMS errors,
including `428 Precondition Required`; they do not register or replay approval requests. Use the
[`hv exp` approval workflow](../../guides/ems-dual-approval/index.md) to request and inspect protected writes, then
review and apply them in EMS UI.
Its operator inputs follow the defaults and constraints of the EMS release that supports dual approval, while these generated library models remain pinned to the
released contract described above.

| Change | Methods |
| --- | --- |
| Experiment lifecycle | `create_experiment`, `terminate_experiment`, `change_ramp_up_percentage` |
| Algorithm release state | `create_algorithm`, `update_algorithm`, `create_algorithm_parameter` |
| Slot and variant configuration | `create_slot`, `refresh_slot_salt`, `update_variant_algorithm`, `promote_algorithm` |
| Forced assignments | `upsert_user_forced_assignment`, `delete_user_forced_assignment` |

!!! danger "Authorized mutation"

    Call mutation methods only with explicit authorization for the target EMS environment. Artifact publication is a
    separate operation: registering an algorithm or parameter record does not upload the referenced package. After a
    mutation, read the affected slot, experiment, variant, algorithm, and parameter records back and verify their exact
    identities before changing traffic.

Mutation names do not expose every cascade. In particular, creating a newer parameter changes all variants that
resolve that algorithm version after refresh, promotion terminates the previous default variant, and terminating a
variant removes its forced assignments. Read
[EMS operations and state transitions](../../components/experiment-management-operations/index.md) before automating
those calls.

## Model construction

Use the canonical generated request models: `AlgorithmCreate`, `AlgorithmParameter`, `SlotInput`, `ExperimentCreate`,
`ExperimentVariantInput`, `AlgorithmPatch`, `ChangeRampUpPercentageInput`, `UserForcedAssignmentInput`, and
`VariantUpdateAlgorithmInput`. Construct a model
explicitly so Pydantic validates it against the pinned EMS schema before it reaches EMS.

Models used only for requests reject unknown fields. Models also used for responses, such as `AlgorithmParameter`
and its nested `Algorithm`, ignore additional fields. Ignored fields are not retained or sent back to EMS.
For example, the `last_test_time` field added in a later EMS release is accepted in responses but is not used or written by
this client version.

`AlgorithmParameter.algorithm` contains the plain `Algorithm` fields. When starting from `get_algorithm`, construct
that value with `Algorithm.model_validate(result.model_dump(exclude={"latest_algorithm_parameter"}))`.
The SageMaker training entrypoint performs this projection before registering a trained parameter and validates
the saved `AlgorithmParameter` returned by EMS. Generated operations serialize request models in JSON mode,
including timestamp fields from the GET response.

Mutation-specific values remain inside their generated request models:

```python
from hotvect.experiment_management import (
    AlgorithmPatch,
    ChangeRampUpPercentageInput,
    SlotInput,
    UserForcedAssignmentInput,
    VariantUpdateAlgorithmInput,
)

client.change_ramp_up_percentage("example-slot", 42, ChangeRampUpPercentageInput(new_ramp_up_percentage=25))
client.promote_algorithm(
    "ranking",
    "4.2.0",
    SlotInput(
        slot_name="example-slot",
        num_shards=100,
        algorithm_name="ranking",
        algorithm_version="4.2.0",
    ),
)
client.update_algorithm("ranking", "4.2.0", AlgorithmPatch(state="ACTIVE"))
client.upsert_user_forced_assignment("example-slot", "user-123", UserForcedAssignmentInput(variant_id=17))
```

Updating a variant replaces its one algorithm:

```python
from hotvect.experiment_management import VariantUpdateAlgorithmInput

replacement = VariantUpdateAlgorithmInput(
    algorithm_name="ranking",
    algorithm_version="4.2.0",
)
client.update_variant_algorithm("example-slot", 17, replacement)
```

Use `model_dump(by_alias=True)` when release automation needs to retain the exact submitted payload as evidence. The
server response remains the source of truth; read it back after mutation rather than treating the request object as
proof of state.

## Boundaries

- The client does not publish JAR or parameter ZIP bytes.
- It does not perform Java runtime assignment; the serving runtime consumes the slot snapshot and assigns locally.
- It does not add retry or traffic policy around requests.
- `hv exp` provides typed mutations and a server-stored approval workflow; native library mutation methods leave
  approval handling to their caller.

See [Configuration and experimentation](../../concepts/configuration-and-experimentation/index.md),
[`hv exp`](../cli/index.md#hv-exp), and
[Take a change to a live experiment](../../guides/change-to-live-experiment/index.md).
