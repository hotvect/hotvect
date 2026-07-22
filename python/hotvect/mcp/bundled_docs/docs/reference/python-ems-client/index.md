---
title: Python EMS client
description: Read and intentionally mutate an external Experiment Management Service through Hotvect's typed Python client
tags: [reference, python, ems, experimentation]
---

# Python EMS client

`hotvect.experiment_management` contains typed Pydantic models, authentication adapters, and a synchronous client for
an external Experiment Management Service (EMS). It is a client library, not an EMS server. Use the read-only `hv-exp`
CLI when inspection is enough; use this API only in authorized application or release automation.

## Create a client

Pass the service root explicitly and choose an authentication adapter. This example reads both values from the
environment and sets separate connection and response timeouts:

```python
import os

from hotvect.experiment_management import (
    ExperimentManagementClient,
    ExperimentManagementConnection,
    TokenProviderAuth,
)

connection = ExperimentManagementConnection(
    environment=os.environ["EMS_URL"],
    connect_timeout=5.0,
    read_timeout=15.0,
    bearer_auth=TokenProviderAuth(lambda: os.environ["EMS_TOKEN"]),
)
client = ExperimentManagementClient(connection)
```

The connection uses the supplied URL verbatim as its API base. Requests raise the normal `requests` HTTP exception for
non-success responses, except methods whose `ignore_404=True` option explicitly returns `None`. Returned resources are
validated into the exported Pydantic model types.

## Read surface

The stable public methods group into these concerns:

| Concern | Methods |
| --- | --- |
| Slots and serving state | `get_slots`, `get_slot`, `get_default_variant_and_active_experiments`, `get_slot_salts` |
| Experiments | `get_experiment`, `get_experiments`, `get_experiment_rampup_logs` |
| Algorithms and parameters | `get_algorithms`, `get_algorithm`, `get_active_algorithms`, `get_algorithms_with_active_variants`, `get_latest_algorithm_parameter`, `get_algorithm_parameters`, `get_algorithm_parameter`, `get_algorithm_state_logs` |
| Variants and assignment history | `get_variants`, `get_variant`, `get_active_variants`, `get_variant_algorithm_logs`, `get_user_forced_assignments`, `get_user_forced_assignment`, `get_campaign_forced_assignments`, `get_campaign_forced_assignment` |
| Shards | `get_shards`, `get_shard`, `get_shard_logs` |

`hv-exp` exposes the inspection subset as JSON and additionally handles configured online-result partitions. Prefer it
for shell workflows so a read task cannot accidentally call a mutation method.

## Mutation surface

These calls change external control-plane state:

| Change | Methods |
| --- | --- |
| Experiment lifecycle | `create_experiment`, `terminate_experiment`, `change_ramp_up_percentage` |
| Algorithm release state | `create_algorithm`, `activate_algorithm`, `update_algorithm`, `promote_algorithm`, `create_algorithm_parameter` |
| Slot and variant configuration | `create_slot`, `refresh_slot_salt`, `update_variant_algorithm` |
| Forced assignments | `upsert_user_forced_assignment`, `delete_user_forced_assignment`, `upsert_campaign_forced_assignment`, `delete_campaign_forced_assignment` |

!!! danger "External mutation"

    Call mutation methods only with explicit authorization for the target EMS environment. Artifact publication is a
    separate operation: registering an algorithm or parameter record does not upload the referenced package. After a
    mutation, read the affected slot, experiment, variant, algorithm, and parameter records back and verify their exact
    identities before changing traffic.

## Model construction

Creation methods accept exported specification models such as `AlgorithmSpec`, `AlgorithmParameterSpec`,
`ExperimentSpec`, and `SlotSpec`. Construct those models explicitly and let Pydantic validate the payload before the
request:

```python
import json
from pathlib import Path

from hotvect.experiment_management import ExperimentSpec

candidate_payload = json.loads(Path("approved-experiment.json").read_text())
spec = ExperimentSpec.model_validate(candidate_payload)
client.create_experiment("example-slot", spec)
```

Use `model_dump()` when release automation needs to retain the exact submitted payload as evidence. The server response
remains the source of truth; read it back after mutation rather than treating the request object as proof of state.

## Boundaries

- The client does not publish JAR or parameter ZIP bytes.
- It does not perform Java runtime assignment; the online Java manager consumes the slot snapshot and assigns locally.
- It does not add retry or traffic policy around requests.
- `hv-exp` is intentionally read-only even though this library also exposes mutation methods.

See [Configuration and experimentation](../../concepts/configuration-and-experimentation/index.md),
[`hv-exp`](../cli/index.md#hv-exp), and
[Take a change to a live experiment](../../guides/change-to-live-experiment/index.md).
