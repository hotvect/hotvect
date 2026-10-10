---
title: EMS clients and operator tools
description: Choose between the Java runtime client, Python EMS client, hv exp, and the deployed OpenAPI contract
tags: [components, ems, clients, cli, python]
related_docs:
  - ../experiment-management-service/index.md
  - ../ems-runtime-client/index.md
  - ../../reference/python-ems-client/index.md
  - ../../reference/cli/index.md
---

# EMS clients and operator tools

EMS exposes one control-plane API to several clients with different responsibilities. Choose the narrowest client that
matches the operation and authorization level you need.

## Client matrix

| Surface | Intended use | Reads | Mutations | Executes algorithms |
| --- | --- | --- | --- | --- |
| Java EMS runtime client in `hotvect-online-util` | Refresh online serving state | Configured slot snapshots | No | Through the containing application after local selection |
| Python `ExperimentManagementClient` | Typed release and experiment automation | Yes | Yes, when authorized | No |
| `hv exp` | Human and agent inspection and operations | Yes | Typed mutations with EMS approval | No |
| Deployed OpenAPI document | Contract discovery and generated clients | Describes API | Describes API | No |

The standalone EMS server is not a client SDK. Use a Hotvect client to call an existing deployment; build and deploy
the server from the
EMS server repository.

## Java runtime client

The Java client reads `defaultVariantAndActiveExperiments` for configured slots and converts the response into a local
serving snapshot. It is part of the online request infrastructure, but it does not perform control-plane mutations.

Read [EMS runtime client, refresh, and assignment](../ems-runtime-client/index.md).

## Python EMS client

The Python client exposes typed reads and mutations for release and experiment automation. Depending on authorization,
it can manage algorithm records, parameter records, slots, variants, experiments, ramp-up, promotion, and explicit user
assignments.

Use it from reviewed automation or an operator workflow with scopes limited to the intended environment. It does not
upload artifact bytes; publish the JAR or parameter ZIP first, then register its absolute path.

Read the [Python EMS client reference](../../reference/python-ems-client/index.md).

## `hv exp`

`hv exp` inspects active algorithms, slot state, experiment details, ramp-up history, registered parameter records,
and online results. Ten typed write commands create a server-stored approval request when EMS requires dual
approval. Use `approval get` and `approval list` to inspect requests. Another authenticated operator reviews and
applies the record in EMS UI.

```bash
hv exp slot get --slot-name example-slot
hv exp algorithm list-in-use --slot-name example-slot
hv exp experiment get --experiment-id 42
hv exp algorithm parameter list \
  --algorithm-name example-ranker \
  --algorithm-version 1.2.0
```

Read the [`hv exp` command reference](../../reference/cli/index.md#hv-exp) and
[dual-approved EMS mutations guide](../../guides/ems-dual-approval/index.md).

## OpenAPI

The deployed EMS service publishes its generated OpenAPI document, including endpoint schemas, deployment-specific API
identity, contact, server URLs, and OAuth flow.

Treat that generated document as the HTTP contract for the deployed version. A static copy should be regenerated from
the exact server artifact in CI rather than maintained independently by hand.

For convenient reference with this Hotvect release, the docsite bundles a complete static export of the EMS
[endpoint and schema contract](../../reference/ems-server-api/index.md). The deployed EMS document remains
authoritative for its deployment-specific identity, servers, and OAuth settings.

## Authentication

All clients connect to a configured EMS URL and supply credentials for that environment. Runtime clients normally need
only read access. Release automation and operator clients should receive write access only for the operations they own.

Operators use their own credentials for administration. EMS derives requester and approver identities from OAuth
`uid` and enforces independent approval, expiry, and one-time consumption of the exact stored operation.
