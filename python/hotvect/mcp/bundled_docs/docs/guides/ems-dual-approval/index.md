---
title: Dual-approved EMS mutations
description: Request and inspect protected EMS mutations with hv, then review and execute in EMS UI
tags: [guides, ems, cli, operations, dual-approval]
related_docs:
  - ../../components/ems-clients-and-tools/index.md
  - ../../reference/cli/index.md
  - ../../reference/config/index.md
---

# Dual-approved EMS mutations

`hv exp` provides 10 typed EMS business mutations. Use the write command for the change you need and provide
`--reason`. When EMS protects the write, `hv` creates a server-stored approval request and prints its UUID.
When EMS permits the write directly, the command reports `"status": "executed"`.

The workflow has two distinct people:

1. Requester A runs a typed mutation, for example `hv exp experiment ramp-up`.
2. EMS first rejects the protected write with `428 Precondition Required`; `hv` then stores the exact method, path,
   body, reason, and A's server-derived identity in EMS and prints the approval UUID.
3. Reviewer B opens the approval UUID in EMS UI, checks the stored operation and reason, then approves and executes
   it there. The same record is available for CLI inspection with `hv exp approval get <approval-id>`.

EMS derives both identities from the authenticated OAuth `uid` claim. It rejects an approval where B is the requester,
and it only lets B execute the approved operation. The record is locked while approving and consuming it, so it cannot
be executed twice. An approved request expires after the EMS-configured approval lifetime (30 minutes by default and
at most).

## Configure the CLI

Only the normal EMS connection settings are needed in `~/.hotvect/config.json` (or the file passed through
`hv exp --config-path`). Use your own EMS token provider; the server stores the operation so it can be inspected
from another machine.

```json
{
  "experiment_management": {
    "url": "https://experiments.example.com",
    "token_provider_command": "printenv EXAMPLE_TOKEN",
    "token_provider_ttl_ms": 3600000
  }
}
```

The token must include the EMS `uid` claim. Write operations require the deployment's normal EMS write authorization;
approval inspection needs its read authorization. Tokens for human operators use dual approval for every
business mutation, including experiment termination and parameter registration. EMS may allow service tokens
to publish parameters directly through its configured exact `POST /algorithm-parameters` bypass. The CLI follows the
server's response and supplies no local approval exemption.

## Request a protected mutation

Native write commands accept explicit flags or a typed JSON spec. Specs reject unknown fields, including nested
variant fields, so a misspelled setting fails before any EMS request.

For example, requester A can raise the ramp-up percentage without supplying a slot name: EMS experiment IDs are
globally unique and `hv` resolves the slot internally.

```bash
hv exp experiment ramp-up \
  --experiment-id 42 \
  --percentage 25 \
  --reason 'Increase ramp-up after reviewing the online metrics'
```

On a protected EMS deployment, the result includes `"status": "approval_requested"` and the server-generated
`approval_id`. The operation record includes the canonical request body and digest for review. If the same command is
allowed without approval, it returns `"status": "executed"` instead.

## Review from another machine

Reviewer B does not need access to A's laptop or files. The record is in EMS and can be read by UUID:

```bash
hv exp approval get <approval-id>
```

The output shows the requester identity, exact method and target, JSON body, digest, reason, and lifecycle timestamps.

List pending requests, or include all statuses created since an explicit timestamp:

```bash
hv exp approval list
hv exp approval list --since 2000-10-01T00:00:00+02:00
```

`--since` requires a timezone. History includes approved, executed, rejected, and expired requests; approved requests
are no longer in the pending list.

## Review and apply in EMS UI

Use EMS UI to approve, approve and execute, execute or retry, reject, and handle emergency requests. Review the stored
method, target, body, and reason before applying the change. EMS verifies the reviewer identity, expiry, and exact
request fields, then commits the mutation and approval consumption together.

If a write fails, EMS rolls back both the mutation and approval consumption. The recorded reviewer can retry the
stored operation in EMS UI until the approval expires. To change the method, target, or body, submit a new typed
request for independent review.

After execution, read back the affected EMS state with the inspection commands. Approval history includes the
execution identity and timestamps.

## Native mutation commands

The 10 CLI mutation commands below use the business write endpoints of an EMS release that supports dual approval.
All commands below require `--reason` and request approval when EMS requires it.

| `hv exp` command | EMS method and target |
| --- | --- |
| `experiment create` | `POST /slots/{slot}/experiments` |
| `experiment ramp-up` | `PATCH /slots/{slot}/experiments/{id}/changeRampUpPercentage` |
| `experiment terminate` | `PUT /slots/{slot}/experiments/{id}/terminate` |
| `algorithm create` | `POST /algorithms` |
| `algorithm state` | `PATCH /algorithms/{name}/{version}` |
| `algorithm promote` | `PUT /algorithms/{name}/{version}/promote` |
| `algorithm parameter create` | `POST /algorithm-parameters` |
| `variant algorithm` | `PATCH /slots/{slot}/variants/{id}/updateAlgorithm` |
| `forced-assignment set` | `PUT /slots/{slot}/userForcedAssignments/{user}` |
| `forced-assignment delete` | `DELETE /slots/{slot}/userForcedAssignments/{user}` |

Promotion changes a slot's default variant. EMS supports changing an existing algorithm's state; artifact metadata is
set when registering the version. Slot creation and salt refresh are excluded from the CLI. EMS disables salt refresh
by default.

Use the command help for each native operation's required flags. For complex creates, pass a typed JSON spec:

```bash
hv exp algorithm create \
  --spec-file ./algorithm.json \
  --reason 'Register the reviewed production algorithm artifact'

hv exp experiment create \
  --slot-name my-slot \
  --spec-file ./experiment.json \
  --reason 'Start the reviewed treatment experiment'

hv exp algorithm parameter create \
  --spec-file ./parameter.json \
  --reason 'Register the reviewed parameter artifact'

hv exp experiment terminate --experiment-id 42 --reason 'Stop the experiment after reviewing metrics'
```

An algorithm spec identifies its name, version, and artifact URI:

```json
{
  "algorithm_name": "ranker",
  "algorithm_version": "1.0.0",
  "absolute_s3_jar_path": "s3://example-artifacts/ranker-1.0.0.jar",
  "parameter_mode": "NONE"
}
```

`parameter_mode` is `REQUIRED` by default and may be `NONE` for parameterless algorithms. Null is invalid.
`algorithm_training_image_name` is optional; EMS defaults it to the empty string. The algorithm package declares the
training container.

An experiment spec contains the traffic settings and variants:

```json
{
  "experiment_name": "ranker-treatment",
  "number_of_shards": 10,
  "ramp_up_percentage": 25,
  "variants": [
    {"algorithm_name": "ranker", "algorithm_version": "1.0.0", "is_control": true},
    {"algorithm_name": "ranker", "algorithm_version": "2.0.0", "is_control": false}
  ]
}
```

There must be at least one variant. Each variant's positive `shard_allocation_ratio` defaults to 1; shard counts and
ramp-up percentages are bounded by the EMS request schema.

A parameter spec identifies the algorithm version and parameter artifact:

```json
{
  "algorithm_parameter_id": "ranker-params-1",
  "algorithm": {"algorithm_name": "ranker", "algorithm_version": "1.0.0"},
  "absolute_s3_path": "s3://example-artifacts/ranker-params-1.zip"
}
```

`evaluation_results` is a JSON-encoded string and defaults to `"{}"`. Optional `created_at` must be an ISO-8601
timestamp with a timezone; omit it to let EMS assign the creation time. Registration records the existing artifact;
the command does not upload its contents.
