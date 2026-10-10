---
title: EMS server API
description: Static OpenAPI reference for the standalone Experiment Management Service
tags: [reference, ems, api, openapi]
---

# EMS server API

This is a complete static export of the standalone EMS OpenAPI contract. It is bundled with the Hotvect
docsite for convenient, version-matched reference; it does not move server implementation, release, or
deployment ownership from the EMS server repository.

- OpenAPI version: `3.1.0`
- EMS API version: `2.0.0`
- Contract title: `Experiment Management Service`
- Machine-readable source: [openapi.yaml](openapi.yaml)

A deployed EMS instance remains authoritative for its own API identity, server URLs, OAuth scopes, and exact
runtime contract.

Hotvect's Python and Java EMS clients ignore additional response fields, including fields in nested
objects, so additive server changes do not require a client upgrade. Known fields still follow this
contract's validation rules. Ignored fields are not retained or sent back in requests.
Python models used only for request bodies continue to reject unknown fields.

Refresh this static export from the generated EMS `api.yaml` whenever the server API changes:

```shell
cd python
python scripts/export_ems_openapi_docs.py /path/to/ems-server/docs/api.yaml
```

## Service endpoints

## `GET /slots/{slotName}/userForcedAssignments/{userId}`

Retrieve one user forced assignment by user id for a specific slot

| Property | Value |
| --- | --- |
| Operation ID | `getOneByUserId` |
| Tags | `User Forced Assignments` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `slotName` | `path` | Yes | `string` | Slot Name |
| `userId` | `path` | Yes | `string` | User Id |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | User forced assignment retrieved for specified slot | `application/json`: `UserForcedAssignment` |
| `404` | User forced assignment not found for specified slot | `application/json`: `string` |

## `PUT /slots/{slotName}/userForcedAssignments/{userId}`

Add/update a user forced assignment in a specific slot

| Property | Value |
| --- | --- |
| Operation ID | `upsert` |
| Tags | `User Forced Assignments` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `slotName` | `path` | Yes | `string` | Slot Name |
| `userId` | `path` | Yes | `string` | User Id |

### Request body

| Required | Content | Description |
| --- | --- | --- |
| Yes | `application/json`: `UserForcedAssignmentInput` | — |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `201` | User force assignment created for the specified slot and variant | `application/json`: `object` |
| `204` | User force assignment updated for the specified slot and variant | `application/json`: `object` |
| `404` | Variant not found for specified slot | `application/json`: `string` |
| `409` | Specified variant is not active | `application/json`: `string` |

## `DELETE /slots/{slotName}/userForcedAssignments/{userId}`

Delete a user forced assignment in a specific slot for the specified user id

| Property | Value |
| --- | --- |
| Operation ID | `delete` |
| Tags | `User Forced Assignments` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `slotName` | `path` | Yes | `string` | Slot Name |
| `userId` | `path` | Yes | `string` | User Id |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `204` | User force assignment successfully deleted for the specified slot and user id | `application/json`: `object` |
| `404` | User id not fount for specified slot | `application/json`: `string` |

## `PUT /slots/{slotName}/experiments/{experimentId}/terminate`

Terminate an experiment for a specific slot

| Property | Value |
| --- | --- |
| Operation ID | `terminate` |
| Tags | `Experiments` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `slotName` | `path` | Yes | `string` | Slot Name |
| `experimentId` | `path` | Yes | `integer` (`int32`) | Experiment Id |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `204` | Experiment terminated for specified slot | `*/*`: `object` |
| `404` | Experiment not found for specified slot | `application/json`: `Problem` |

## `PUT /algorithms/{algorithmName}/{algorithmVersion}/promote`

Promote an algorithm for a specific slot

| Property | Value |
| --- | --- |
| Operation ID | `promote` |
| Tags | `Algorithms` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `algorithmName` | `path` | Yes | `string` | Algorithm Name |
| `algorithmVersion` | `path` | Yes | `string` | Algorithm Version |

### Request body

| Required | Content | Description |
| --- | --- | --- |
| Yes | `application/json`: `SlotInput` | — |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `204` | Algorithm promoted for the specified slot | `*/*`: `object` |
| `404` | Algorithm not found | `application/json`: `Problem` |

## `GET /slots`

Retrieve a list of all slots

| Property | Value |
| --- | --- |
| Operation ID | `list` |
| Tags | `Slots` |
| Security | `OAuth2` (`uid`) |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | All slots retrieved | `application/json`: array of `Slot` |

## `POST /slots`

Create a new slot

| Property | Value |
| --- | --- |
| Operation ID | `add` |
| Tags | `Slots` |
| Security | `OAuth2` (`uid`) |

### Request body

| Required | Content | Description |
| --- | --- | --- |
| Yes | `application/json`: `SlotInput` | — |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `201` | Slot created | `application/json`: `Slot` |

## `GET /slots/{slotName}/experiments`

Get all the experiments of a slot with variant details

| Property | Value |
| --- | --- |
| Operation ID | `getAll` |
| Tags | `Experiments` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `slotName` | `path` | Yes | `string` | Slot Name |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | Experiment list for the given slot. | `application/json`: `SlotAllExperimentsWithVariantDetailsResponse` |
| `404` | Slot specified doesn't exit. | `application/json`: — |

## `POST /slots/{slotName}/experiments`

Add one single experiment in a specific slot

| Property | Value |
| --- | --- |
| Operation ID | `add_1` |
| Tags | `Experiments` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `slotName` | `path` | Yes | `string` | Slot Name |

### Request body

| Required | Content | Description |
| --- | --- | --- |
| Yes | `application/json`: `ExperimentCreate` | — |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `201` | Experiment created for the specified slot | `application/json`: `Experiment` |

## `GET /algorithms`

Retrieve all algorithms

| Property | Value |
| --- | --- |
| Operation ID | `list_1` |
| Tags | `Algorithms` |
| Security | `OAuth2` (`uid`) |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | All algorithms retrieved | `application/json`: array of `AlgorithmWithLatestParameter` |

## `POST /algorithms`

Add one single algorithm

| Property | Value |
| --- | --- |
| Operation ID | `add_2` |
| Tags | `Algorithms` |
| Security | `OAuth2` (`uid`) |

### Request body

| Required | Content | Description |
| --- | --- | --- |
| Yes | `application/json`: `AlgorithmCreate` | — |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `201` | Algorithm created | `application/json`: `AlgorithmWithLatestParameter` |

## `GET /algorithm-parameters`

Retrieve all algorithm parameters

| Property | Value |
| --- | --- |
| Operation ID | `list_2` |
| Tags | `Algorithm Parameters` |
| Security | `OAuth2` (`uid`) |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | All algorithm parameters retrieved | `application/json`: array of `AlgorithmParameter` |

## `POST /algorithm-parameters`

Add one single algorithm parameter

| Property | Value |
| --- | --- |
| Operation ID | `add_3` |
| Tags | `Algorithm Parameters` |
| Security | `OAuth2` (`uid`) |

### Request body

| Required | Content | Description |
| --- | --- | --- |
| Yes | `application/json`: `AlgorithmParameter` | — |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `201` | Algorithm parameter created | `application/json`: `AlgorithmParameter` |

## `PATCH /slots/{slotName}/variants/{variantId}/updateAlgorithm`

Update a variant's algorithm

| Property | Value |
| --- | --- |
| Operation ID | `updateAlgorithm` |
| Tags | `Variants` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `slotName` | `path` | Yes | `string` | Slot Name |
| `variantId` | `path` | Yes | `integer` (`int32`) | Variant Id |

### Request body

| Required | Content | Description |
| --- | --- | --- |
| Yes | `application/json`: `VariantUpdateAlgorithmInput` | — |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | Variant's algorithm updated | `application/json`: `Variant` |
| `404` | Specified algorithm not found | `application/json`: `string` |
| `409` | Specified algorithm does not have any algorithm parameters | `application/json`: `string` |

## `PATCH /slots/{slotName}/salts/refresh`

Refresh a slot's salt

| Property | Value |
| --- | --- |
| Operation ID | `refreshSalt` |
| Tags | `Slots` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `slotName` | `path` | Yes | `string` | Slot Name |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | Slot's salt refreshed | `application/json`: `Slot` |
| `404` | Slot not found | `application/json`: `string` |
| `409` | Salt refreshing not enabled | `application/json`: `string` |

## `PATCH /slots/{slotName}/experiments/{experimentId}/changeRampUpPercentage`

Change an experiment's ramp up percentage

| Property | Value |
| --- | --- |
| Operation ID | `changeRampUpPercentage` |
| Tags | `Experiments` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `slotName` | `path` | Yes | `string` | Slot Name |
| `experimentId` | `path` | Yes | `integer` (`int32`) | Experiment Id |

### Request body

| Required | Content | Description |
| --- | --- | --- |
| Yes | `application/json`: `ChangeRampUpPercentageInput` | — |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | Experiment's ramp up percentage changed | `application/json`: `Experiment` |
| `404` | Experiment not found for specified slot | `application/json`: `Problem` |

## `GET /algorithms/{algorithmName}/{algorithmVersion}`

Retrieve algorithm by name and version

| Property | Value |
| --- | --- |
| Operation ID | `getOne` |
| Tags | `Algorithms` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `algorithmName` | `path` | Yes | `string` | Algorithm Name |
| `algorithmVersion` | `path` | Yes | `string` | Algorithm Version |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | Algorithm retrieved | `application/json`: `AlgorithmWithLatestParameter` |
| `404` | Algorithm not found | `application/json`: `Problem` |

## `PATCH /algorithms/{algorithmName}/{algorithmVersion}`

Patch an algorithm

| Property | Value |
| --- | --- |
| Operation ID | `patch` |
| Tags | `Algorithms` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `algorithmName` | `path` | Yes | `string` | Algorithm Name |
| `algorithmVersion` | `path` | Yes | `string` | Algorithm Version |

### Request body

| Required | Content | Description |
| --- | --- | --- |
| Yes | `application/json`: `AlgorithmPatch` | — |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | Algorithm patched | `*/*`: `object` |
| `404` | Algorithm not found | `application/json`: `Problem` |
| `409` | Not possible to transition to the new state. | `application/json`: `Problem` |

## `GET /slots/{slotName}`

Retrieve a slot by name

| Property | Value |
| --- | --- |
| Operation ID | `getOneByName` |
| Tags | `Slots` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `slotName` | `path` | Yes | `string` | Slot Name |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | Slot retrieved | `application/json`: `Slot` |
| `404` | Slot not found | `application/json`: `Problem` |

## `GET /slots/{slotName}/variants`

Retrieve a list of all variants for a specific slot

| Property | Value |
| --- | --- |
| Operation ID | `listAll` |
| Tags | `Variants` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `slotName` | `path` | Yes | `string` | slot Name |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | All variants for specified slot retrieved | `application/json`: array of `Variant` |

## `GET /slots/{slotName}/variants/{variantId}`

Retrieve the variant with the specified ID

| Property | Value |
| --- | --- |
| Operation ID | `getVariant` |
| Tags | `Variants` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `slotName` | `path` | Yes | `string` | slot Name |
| `variantId` | `path` | Yes | `integer` (`int32`) | Variant Id |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | Variant details | `application/json`: `Variant` |

## `GET /slots/{slotName}/variants/active`

Retrieve a list of active variants for a specific slot

| Property | Value |
| --- | --- |
| Operation ID | `listActive` |
| Tags | `Variants` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `slotName` | `path` | Yes | `string` | slot Name |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | All non-terminated variants for specified slot retrieved | `application/json`: array of `Variant` |

## `GET /slots/{slotName}/variantAlgorithmLogs`

Retrieve a list of variant algorithm logs for a specific slot

| Property | Value |
| --- | --- |
| Operation ID | `listAllBySlot` |
| Tags | `Variant Algorithm Logs` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `slotName` | `path` | Yes | `string` | Slot Name |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | All variant algorithm logs for the specified slot retrieved | `application/json`: array of `VariantAlgorithmLog` |

## `GET /slots/{slotName}/userForcedAssignments`

Retrieve a list of all user-based forced assignments for a specific slot

| Property | Value |
| --- | --- |
| Operation ID | `listAllBySlot_1` |
| Tags | `User Forced Assignments` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `slotName` | `path` | Yes | `string` | Slot Name |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | All user-based forced assignments for specified slot retrieved | `application/json`: `UserForcedAssignmentResponse` |

## `GET /slots/{slotName}/shards`

Retrieve a list of shards for a specific slot

| Property | Value |
| --- | --- |
| Operation ID | `list_3` |
| Tags | `Shards` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `slotName` | `path` | Yes | `string` | Slot Name |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | All shards retrieved for the specified slot | `application/json`: array of `Shard` |

## `GET /slots/{slotName}/shards/{shardId}`

Retrieve one single shard for a specific slot

| Property | Value |
| --- | --- |
| Operation ID | `getOne_1` |
| Tags | `Shards` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `slotName` | `path` | Yes | `string` | Slot Name |
| `shardId` | `path` | Yes | `integer` (`int32`) | Shard Id |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | Shard retrieved for specified slot | `application/json`: `Shard` |
| `404` | Shard not found for specified slot | `application/json`: `Problem` |

## `GET /slots/{slotName}/shard-logs`

Retrieve a list of shard logs for a specific slot

| Property | Value |
| --- | --- |
| Operation ID | `list_4` |
| Tags | `Shard Logs` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `slotName` | `path` | Yes | `string` | Slot Name |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | All shard logs for the specified slot retrieved | `application/json`: array of `ShardLog` |

## `GET /slots/{slotName}/salts`

Retrieve all salts for a specific slot

| Property | Value |
| --- | --- |
| Operation ID | `listSalts` |
| Tags | `Slots` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `slotName` | `path` | Yes | `string` | Slot Name |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | All salts retrieved for specified slot | `application/json`: array of `SlotSalt` |
| `404` | Specified slot not found | `application/json`: `Problem` |

## `GET /slots/{slotName}/experiments/{experimentId}`

Retrieve one experiment for a specific slot

| Property | Value |
| --- | --- |
| Operation ID | `getOne_2` |
| Tags | `Experiments` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `slotName` | `path` | Yes | `string` | Slot Name |
| `experimentId` | `path` | Yes | `integer` (`int32`) | Experiment Id |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | Experiment retrieved for specified slot | `application/json`: `Experiment` |
| `404` | Experiment not found for specified slot | `application/json`: `Problem` |

## `GET /slots/{slotName}/experimentRampUpLog`

Retrieve a list of experiment and its rampup logs for a specific slot

| Property | Value |
| --- | --- |
| Operation ID | `listAllByExperiment` |
| Tags | `Experiment Ramp up Log` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `slotName` | `path` | Yes | `string` | slot |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | All experiment ramp up logs for the specified experiment retrieved | `application/json`: array of `ExperimentRampUpLog` |

## `GET /slots/{slotName}/experimentRampUpLog/experiments/{experimentId}`

Retrieve ramp up logs for a specific experiment

| Property | Value |
| --- | --- |
| Operation ID | `listByExperimentId` |
| Tags | `Experiment Ramp up Log` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `slotName` | `path` | Yes | `string` | slot |
| `experimentId` | `path` | Yes | `integer` (`int32`) | experiment id |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | All ramp up logs for the specified experiment retrieved | `application/json`: array of `ExperimentRampUpLog` |

## `GET /slots/{slotName}/defaultVariantAndActiveExperiments`

Retrieve all running experiments and the default variant for the specified slot.

| Property | Value |
| --- | --- |
| Operation ID | `getDefaultVariantAndActiveExperiments` |
| Tags | `Slots` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `slotName` | `path` | Yes | `string` | Slot Name |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | Active experiments and default variant retrieved for specified slot | `application/json`: `SlotActiveInfo` |
| `404` | Slot not found | `application/json`: `Problem` |

## `GET /algorithms/{algorithmName}/{algorithmVersion}/latest-algorithm-parameter`

Retrieve the latest algorithm parameter. Algorithms with parameter_mode=NONE return no content.

| Property | Value |
| --- | --- |
| Operation ID | `getLatestAlgorithmParameter` |
| Tags | `Algorithms` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `algorithmName` | `path` | Yes | `string` | Algorithm Name |
| `algorithmVersion` | `path` | Yes | `string` | Algorithm Version |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | Algorithm parameter retrieved | `application/json`: `AlgorithmParameter` |
| `204` | The algorithm has parameter_mode=NONE; no parameter applies | — |
| `404` | Algorithm not found, or a REQUIRED algorithm has no parameter | `application/json`: `Problem` |

## `GET /algorithms/with-active-variants`

Retrieve a list of algorithms that have an active variant.

| Property | Value |
| --- | --- |
| Operation ID | `listActiveWithVariants` |
| Tags | `Algorithms` |
| Security | `OAuth2` (`uid`) |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | List of algorithms with the ids of its active variants. | `application/json`: array of `AlgorithmWithActiveVariantsResponse` |

## `GET /algorithms/active`

Retrieve a list of active algorithms

| Property | Value |
| --- | --- |
| Operation ID | `listActive_1` |
| Tags | `Algorithms` |
| Security | `OAuth2` (`uid`) |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | All active algorithms retrieved | `application/json`: array of `AlgorithmWithLatestParameter` |

## `GET /algorithm-state-logs`

Retrieve all algorithm state logs

| Property | Value |
| --- | --- |
| Operation ID | `list_5` |
| Tags | `Algorithm State Logs` |
| Security | `OAuth2` (`uid`) |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | All algorithm state logs retrieved | `application/json`: array of `AlgorithmStateLog` |

## `GET /algorithm-parameters/{algorithmParameterId}`

Retrieve algorithm parameter by ID

| Property | Value |
| --- | --- |
| Operation ID | `getOne_3` |
| Tags | `Algorithm Parameters` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `algorithmParameterId` | `path` | Yes | `string` | Algorithm Parameter Id |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | Algorithm parameter retrieved | `application/json`: `AlgorithmParameter` |
| `404` | Algorithm parameter not found | `application/json`: `Problem` |

## `GET /algorithm-parameters/by-algorithm/{algorithmName}/{algorithmVersion}`

Retrieve algorithm parameters for a specific algorithm

| Property | Value |
| --- | --- |
| Operation ID | `getByAlgorithm` |
| Tags | `Algorithm Parameters` |
| Security | `OAuth2` (`uid`) |

### Parameters

| Name | In | Required | Schema | Description |
| --- | --- | --- | --- | --- |
| `algorithmName` | `path` | Yes | `string` | Algorithm name |
| `algorithmVersion` | `path` | Yes | `string` | Algorithm version |
| `top` | `query` | No | `integer` (`int32`) | Maximum number of results (optional) |
| `minCreatedAt` | `query` | No | `string` (`date-time`) | Minimum creation date in ISO-8601 format (optional) |

### Responses

| Status | Description | Content |
| --- | --- | --- |
| `200` | Algorithm parameters retrieved | `application/json`: array of `AlgorithmParameter` |

## Schemas

### Schema: UserForcedAssignmentInput

User force assignment to be added

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `variant_id` | `integer` (`int32`) | Yes | — | — |

### Schema: Problem

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `instance` | `string` (`uri`) | No | — | — |
| `type` | `string` (`uri`) | No | — | — |
| `parameters` | `object` | No | — | — |
| `title` | `string` | No | — | — |
| `detail` | `string` | No | — | — |
| `status` | `StatusType` | No | — | — |

### Schema: StatusType

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `statusCode` | `integer` (`int32`) | No | — | — |
| `reasonPhrase` | `string` | No | — | — |

### Schema: SlotInput

Name of the slot to be created and number of shards allocated to it

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `slot_name` | `string` | Yes | `minLength` = `1`, `pattern` = `[a-z0-9-]+` | — |
| `num_shards` | `integer` (`int32`) | Yes | `maximum` = `100` | — |
| `algorithm_name` | `string` | Yes | `minLength` = `1` | — |
| `algorithm_version` | `string` | Yes | `minLength` = `1` | — |

### Schema: Slot

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `name` | `string` | Yes | `minLength` = `1` | — |
| `created_at` | `string` (`date-time`) | No | — | — |
| `salts` | array of `string` | Yes | — | — |

### Schema: SlotSalt

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `salt` | `string` | No | — | — |
| `slot` | `Slot` | Yes | — | — |
| `created_at` | `string` (`date-time`) | No | — | — |
| `terminated_at` | `string` (`date-time`) | No | — | — |

### Schema: Algorithm

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `algorithm_name` | `string` | Yes | `minLength` = `1` | — |
| `algorithm_version` | `string` | Yes | `minLength` = `1` | — |
| `state` | `string` | No | — | — |
| `absolute_s3_jar_path` | `string` | Yes | `minLength` = `1` | — |
| `created_at` | `string` (`date-time`) | No | — | — |
| `algorithm_training_image_name` | `string` | Yes | — | — |
| `parameter_mode` | `string` — `REQUIRED`, `NONE` | Yes | — | — |

### Schema: Experiment

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `experiment_id` | `integer` (`int32`) | No | — | — |
| `experiment_name` | `string` | Yes | `minLength` = `1` | — |
| `created_at` | `string` (`date-time`) | No | — | — |
| `terminated_at` | `string` (`date-time`) | No | — | — |
| `variants` | array of `Variant` | No | — | — |
| `ramp_up_percentage` | `integer` (`int32`) | Yes | `maximum` = `100` | — |

### Schema: Variant

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `variant_id` | `integer` (`int32`) | No | — | — |
| `algorithm` | `Algorithm` | Yes | — | — |
| `experiment` | `Experiment` | No | — | — |
| `created_at` | `string` (`date-time`) | No | — | — |
| `terminated_at` | `string` (`date-time`) | No | — | — |
| `is_default` | `boolean` | No | — | — |
| `is_control` | `boolean` | No | — | — |
| `shard_allocation_ratio` | `integer` (`int32`) | No | — | — |

### Schema: ExperimentCreate

Experiment to be added

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `variants` | array of `ExperimentVariantInput` | Yes | `minItems` = `1`, `maxItems` = `2147483647` | — |
| `experiment_name` | `string` | Yes | `minLength` = `1` | — |
| `number_of_shards` | `integer` (`int32`) | Yes | `maximum` = `100` | — |
| `ramp_up_percentage` | `integer` (`int32`) | Yes | `maximum` = `100` | — |

### Schema: ExperimentVariantInput

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `algorithm_name` | `string` | Yes | `minLength` = `1` | — |
| `algorithm_version` | `string` | Yes | `minLength` = `1` | — |
| `is_control` | `boolean` | Yes | — | — |
| `shard_allocation_ratio` | `integer` (`int32`) | Yes | — | — |

### Schema: AlgorithmParameterSummary

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `algorithm_parameter_id` | `string` | Yes | — | — |
| `absolute_s3_algorithm_parameter_path` | `string` | Yes | — | — |

### Schema: AlgorithmWithLatestParameter

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `algorithm_name` | `string` | Yes | `minLength` = `1` | — |
| `algorithm_version` | `string` | Yes | `minLength` = `1` | — |
| `state` | `string` | No | — | — |
| `absolute_s3_jar_path` | `string` | Yes | `minLength` = `1` | — |
| `created_at` | `string` (`date-time`) | No | — | — |
| `algorithm_training_image_name` | `string` | Yes | — | — |
| `parameter_mode` | `string` — `REQUIRED`, `NONE` | Yes | — | — |
| `latest_algorithm_parameter` | `any` | Yes | — | — |

### Schema: AlgorithmCreate

Algorithm to be added

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `algorithm_name` | `string` | Yes | `minLength` = `0`, `maxLength` = `255` | — |
| `algorithm_version` | `string` | Yes | `minLength` = `0`, `maxLength` = `255` | — |
| `absolute_s3_jar_path` | `string` | Yes | `minLength` = `1` | — |
| `algorithm_training_image_name` | `string` | Yes | `minLength` = `0`, `maxLength` = `255` | — |
| `parameter_mode` | `string` — `REQUIRED`, `NONE` | No | — | Defaults to REQUIRED when omitted by legacy clients. |

### Schema: AlgorithmParameter

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `algorithm_parameter_id` | `string` | Yes | — | — |
| `algorithm` | `Algorithm` | Yes | — | — |
| `evaluation_results` | `string` | No | — | — |
| `created_at` | `string` (`date-time`) | No | — | — |
| `absolute_s3_path` | `string` | Yes | `minLength` = `1` | — |

### Schema: VariantUpdateAlgorithmInput

New algorithm name and version

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `algorithm_name` | `string` | Yes | `minLength` = `1` | — |
| `algorithm_version` | `string` | Yes | `minLength` = `1` | — |

### Schema: ChangeRampUpPercentageInput

New ramp up percentage

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `new_ramp_up_percentage` | `integer` (`int32`) | Yes | `maximum` = `100` | — |

### Schema: AlgorithmPatch

Updated Algorithm data

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `state` | `string` | Yes | `pattern` = `ACTIVE\|INACTIVE` | — |

### Schema: VariantAlgorithmLog

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `variant_algorithm_log_id` | `integer` (`int32`) | No | — | — |
| `variant` | `Variant` | Yes | — | — |
| `algorithm` | `Algorithm` | Yes | — | — |
| `created_at` | `string` (`date-time`) | No | — | — |

### Schema: UserForcedAssignment

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `user_forced_assignment_id` | `integer` (`int32`) | No | — | — |
| `user_id` | `string` | Yes | `minLength` = `1` | — |
| `variant` | `Variant` | Yes | — | — |
| `created_at` | `string` (`date-time`) | No | — | — |

### Schema: UserForcedAssignmentResponse

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `user_forced_assignments` | array of `UserForcedAssignment` | No | — | — |

### Schema: Shard

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `id` | `integer` (`int32`) | No | — | — |
| `shard_id` | `integer` (`int32`) | Yes | — | — |
| `experiment` | `Experiment` | No | — | — |
| `created_at` | `string` (`date-time`) | No | — | — |

### Schema: ShardLog

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `shard_log_id` | `integer` (`int32`) | No | — | — |
| `shard` | `Shard` | Yes | — | — |
| `experiment` | `Experiment` | No | — | — |
| `created_at` | `string` (`date-time`) | No | — | — |

### Schema: ExperimentWithVariantDetails

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `experiment_id` | `integer` (`int32`) | Yes | — | — |
| `experiment_name` | `string` | Yes | `minLength` = `1` | — |
| `created_at` | `string` (`date-time`) | Yes | — | — |
| `terminated_at` | `string` (`date-time`) | No | — | — |
| `variants` | array of `VariantBasicDetails` | Yes | — | — |
| `ramp_up_percentage` | `integer` (`int32`) | Yes | — | — |

### Schema: SlotAllExperimentsWithVariantDetailsResponse

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `slot_name` | `string` | Yes | `minLength` = `1` | — |
| `experiments` | array of `ExperimentWithVariantDetails` | Yes | — | — |

### Schema: VariantBasicDetails

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `variant_id` | `integer` (`int32`) | No | — | — |
| `algorithm` | `Algorithm` | No | — | — |
| `is_default` | `boolean` | No | — | — |
| `is_control` | `boolean` | No | — | — |
| `shard_allocation_ratio` | `integer` (`int32`) | No | — | — |

### Schema: ExperimentRampUpLog

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `experiment_ramp_up_log_id` | `integer` (`int32`) | No | — | — |
| `experiment` | `Experiment` | Yes | — | — |
| `terminated_at` | `string` (`date-time`) | No | — | — |
| `ramp_up_percentage` | `integer` (`int32`) | Yes | — | — |
| `created_at` | `string` (`date-time`) | No | — | — |

### Schema: SlotActiveInfo

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `slot_salt` | `string` | Yes | `minLength` = `1` | — |
| `total_number_of_shards` | `integer` (`int32`) | Yes | — | — |
| `default_variant` | `SlotActiveInfoVariantResponse` | Yes | — | — |
| `experiments` | array of `SlotActiveInfoExperimentResponse` | Yes | — | — |
| `user_forced_assignments` | array of `SlotActiveInfoUserForcedAssignmentResponse` | Yes | — | — |

### Schema: SlotActiveInfoAlgorithmResponse

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `algorithm_name` | `string` | Yes | `minLength` = `1` | — |
| `algorithm_version` | `string` | Yes | `minLength` = `1` | — |
| `latest_algorithm_parameter` | `string` | No | — | — |
| `absolute_s3_algorithm_jar_path` | `string` | Yes | `minLength` = `1` | — |
| `absolute_s3_algorithm_parameter_path` | `string` | No | — | — |

### Schema: SlotActiveInfoExperimentResponse

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `experiment_id` | `integer` (`int32`) | Yes | — | — |
| `experiment_name` | `string` | Yes | `minLength` = `1` | — |
| `variants` | array of `SlotActiveInfoVariantResponse` | Yes | — | — |
| `shards` | array of `SlotActiveInfoShardResponse` | Yes | — | — |
| `ramp_up_percentage` | `integer` (`int32`) | Yes | — | — |
| `created_at` | `string` (`date-time`) | Yes | — | — |

### Schema: SlotActiveInfoShardResponse

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `shard_id` | `integer` (`int32`) | Yes | — | — |
| `created_at` | `string` (`date-time`) | Yes | — | — |

### Schema: SlotActiveInfoUserForcedAssignmentResponse

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `user_id` | `string` | Yes | `minLength` = `1` | — |
| `variant_id` | `integer` (`int32`) | Yes | — | — |

### Schema: SlotActiveInfoVariantResponse

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `variant_id` | `integer` (`int32`) | Yes | — | — |
| `algorithm` | `SlotActiveInfoAlgorithmResponse` | Yes | — | — |
| `created_at` | `string` (`date-time`) | Yes | — | — |
| `is_default` | `boolean` | No | — | — |
| `is_control` | `boolean` | No | — | — |
| `shard_allocation_ratio` | `integer` (`int32`) | No | — | — |

### Schema: AlgorithmWithActiveVariantsResponse

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `algorithms` | array of `AlgorithmsWithVariantsDTO` | No | — | — |

### Schema: AlgorithmsWithVariantsDTO

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `algorithmName` | `string` | No | — | — |
| `algorithmVersion` | `string` | No | — | — |
| `state` | `string` | No | — | — |
| `absoluteS3JarPath` | `string` | No | — | — |
| `createdAt` | `string` (`date-time`) | No | — | — |
| `algorithmTrainingImageName` | `string` | No | — | — |
| `parameterMode` | `string` — `REQUIRED`, `NONE` | No | — | — |
| `latestAlgorithmParameter` | `AlgorithmParameter` | No | — | — |
| `variants` | array of `VariantInfo` | No | — | — |

### Schema: VariantInfo

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `variantId` | `integer` (`int32`) | No | — | — |
| `slotName` | `string` | No | — | — |

### Schema: AlgorithmStateLog

Type: `object`

| Property | Type | Required | Constraints | Description |
| --- | --- | --- | --- | --- |
| `algorithm_stage_log_id` | `integer` (`int32`) | No | — | — |
| `algorithm` | `Algorithm` | Yes | — | — |
| `state` | `string` | Yes | `minLength` = `1` | — |
| `created_at` | `string` (`date-time`) | No | — | — |

## Security schemes

### OAuth2

Type: `oauth2`

Token URL: `https://auth.example.com/oauth2/token`

| Scope | Description |
| --- | --- |
| `ems.read_all` | Grants read access for the Experiment Management Service |
| `ems.write_all` | Grants write access for the Experiment Management Service |
| `uid` | Provides the caller's user identity |
