---
title: Python EMS client
description: Read and intentionally mutate a configured Experiment Management Service through Hotvect's generated typed Python client
tags: [reference, python, ems, experimentation]
---

# Python EMS client

`hotvect.experiment_management` provides a synchronous typed client for a configured Experiment Management Service
(EMS) endpoint. Its models and internal operation surface are generated bindings; it is not an EMS release artifact or
server. Every variant has one required `algorithm`; plural algorithm selections and slot roles are not part of this
contract. Missing and unknown response fields are rejected.

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

`hv exp` exposes the inspection subset as JSON and additionally handles configured online-result partitions. Prefer it
for shell workflows so a read task cannot accidentally call a mutation method.

## Mutation surface

These calls change configured control-plane state:

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
- `hv exp` is intentionally read-only even though this library also exposes mutation methods.

See [Configuration and experimentation](../../concepts/configuration-and-experimentation/index.md),
[`hv exp`](../cli/index.md#hv-exp), and
[Take a change to a live experiment](../../guides/change-to-live-experiment/index.md).
