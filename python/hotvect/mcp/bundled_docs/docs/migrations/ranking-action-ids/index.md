---
title: Ranking Action ID Migration
description: What ranking action IDs are, why they are required, and how to update rankers and decoders
tags: [migration, ranking, action-id]
difficulty: intermediate
estimated_time: 10 minutes
related_docs:
  - ../../guides/develop-algorithms/index.md
---

# Ranking Action ID Migration

## What changed

Ranking requests now carry a stable ID for every candidate action. Build requests with
`AvailableAction` and build decisions with `RankingDecision.builder(actionId, action)`.

`TransformedAction` and `ScoringDecision` can also carry an action ID. Hotvect's default and
annotation-generated ranking transformers preserve the ID from `RankingRequest.actions()`, and
CatBoost scoring copies it from the transformed action into the scoring decision.
CatBoost/TFRecord/audit encoders use transformed action IDs when present so reordered transformed
rows keep the correct outcome/reward. Hotvect's default adapters from legacy
`BulkScorer.apply(...)` scores also populate scoring decision IDs from `RankingRequest.actions()`.
Scoring decisions built through old constructors or old factories may still have a `null` action ID,
so rankers should keep using the request action IDs when converting positional score lists into
ranking decisions.

`actionIndex` remains only as deprecated compatibility metadata. Do not use it as action identity.

## Migration contract

The target contract is:

- `RankingRequest.actions()` returns the canonical `List<AvailableAction<ACTION>>`.
- `AvailableAction.actionId()` is non-blank and unique within one request.
- `RankingDecision.actionId()` is the only stable identity for ranked output.
- `TransformedAction.actionId()` and `ScoringDecision.actionId()` should be preserved whenever a
  transformer or scorer creates a new per-action object.
- Training, audit, and evaluation paths join outcomes by `actionId`, not by request position.

Request order still matters as a physical row order for vectorizers and legacy scorers. New code
should not use that order as identity. Bulk scorers must preserve request order. Bulk-score ranker
adapters consume scores by position and then use request action IDs for tie-breaking and final
decisions. With Java assertions enabled, they also validate `ScoringDecision.actionId()` when
present; reordered scorer output and mixed ID/positional scoring output then fail fast.

## Why

Position is not a stable candidate identity. Using request order or Java object hashes for ties can
make equal-score rankings biased or inconsistent across offline and online paths.

Stable action IDs let Hotvect:

- tie-break equal scores deterministically from `exampleId` and `actionId`
- preserve candidate identity after sorting
- emit and evaluate ranking output by action identity instead of position

## What you need to do

For ranking algorithms, migrate in this order:

1. Pick a stable per-request candidate ID from the application's domain object.
2. Ensure the decoder or request builder supplies a non-blank `exampleId`.
3. Build ranking requests from `AvailableAction.of(actionId, action)`.
4. Ensure action IDs are unique within one request.
5. In transformers, preserve the request action ID when building `TransformedAction`.
6. In scorers, preserve the action ID when building `ScoringDecision`.
7. In rankers and decorators, keep the incoming action ID before sorting and use
   `RankingDecision.builder(actionId, action)`.
8. In training examples, ensure outcomes are attached to decisions with the same action IDs as the
   request.
9. Stop using `RankingDecision.builder(index, action)`, `actionIndex()`, and `getActionIndex()` in
   new code.
10. Refresh tests/golden files that assert ranking output; output now includes `action_id`.

Example:

```java
var request = RankingRequest.ofAvailableActions(
    exampleId,
    shared,
    actions.stream()
        .map(action -> AvailableAction.of(action.documentId(), action))
        .toList()
);

var decision = RankingDecision.builder(request.actions().get(i).actionId(), action)
    .withScore(score)
    .build();
```

## API-specific guidance

### Decoders and request builders

Use `RankingRequest.ofAvailableActions(...)` with `AvailableAction.of(actionId, action)`. Do not
put the action ID only into the action payload or `additionalProperties`; Hotvect cannot enforce or
validate those as identity.

Raw-action request construction is deprecated. It is kept only so older algorithm jars can still run
during migration; Hotvect synthesizes positional IDs (`"0"`, `"1"`, ...) for that path. Those IDs
are still salted with `exampleId` by the stable tie-break hash, so equal-score ties remain
deterministic per example without globally favoring a fixed synthetic index.

Duplicate action-ID validation is assertion-only because it is an O(n) request-construction check.
Run tests and migration validation with Java assertions enabled to catch duplicate IDs early.

### Transformers

Default and annotation-generated ranking transformers preserve IDs automatically. If you implement a
transformer directly, keep the `AvailableAction` wrapper until you construct `TransformedAction`:

```java
AvailableAction<ACTION> input = request.actions().get(i);
return TransformedAction.of(input.actionId(), input.action(), transformedRecord);
```

If a transformer reorders transformed rows, it must carry `TransformedAction.actionId()` so training
and audit encoders can join each row to the correct outcome.

### Scorers and rankers

New scorers should return `ScoringDecision.of(actionId, action, score, additionalProperties)` in
the same order as `RankingRequest.actions()`. Scorers preserve request order; rankers are the layer
that may reorder candidates. Bulk-score rankers do not repair reordered scorer output. With Java
assertions enabled, they validate scoring decision IDs when present. Legacy scorer output with
all-null action IDs is still treated positionally for compatibility.

Rankers should build final decisions from the stable action ID they received, not from the sorted
position:

```java
RankingDecision.builder(actionId, action)
    .withScore(score)
    .build();
```

### Training, audit, and evaluation

Outcomes are keyed by `RankingDecision.actionId()`. Encoders reject duplicate or missing outcome IDs
instead of silently joining by position. If metrics move after migration, first inspect tied-score
examples and then verify that request actions, transformed actions, scores, and outcomes all carry
the same candidate IDs.

## Validation checklist

Before merging a migrated ranking algorithm:

- Request actions use non-blank, unique action IDs.
- Tests run with Java assertions enabled so duplicate request IDs and scorer order mismatches are
  caught before deployment.
- Equal-score tie tests assert deterministic ordering by `exampleId` and `actionId`.
- Custom transformers preserve IDs in `TransformedAction`.
- Custom scorers preserve IDs in `ScoringDecision`.
- Rankers and decorators preserve existing decision IDs when sorting or rewriting decisions.
- Training examples have one outcome for every request action ID.
- Golden output assertions use `action_id`, not `action_index`.
- Legacy raw-action paths are covered only where old algorithm jars must still run.

## Alternatives considered

### Keep positional identity

Rejected. Position is easy to preserve in simple request-order loops, but it is not stable after
sorting, filtering, batching, or joining offline outcomes. It also makes tied-score ordering depend
on incidental request order.

### Change `availableActions()` to return `List<AvailableAction<ACTION>>`

Rejected for this migration. Older algorithm jars call `availableActions()` expecting raw action
payloads. Returning wrappers there would keep the erased JVM return type as `List`, but would change
the runtime contents and break those algorithms. The migration therefore adds `actions()` as the
canonical wrapper-returning method and deprecates raw `availableActions()`.

### Store action IDs in `additionalProperties`

Rejected. `additionalProperties` is untyped, optional, and not validated by the core ranking API.
Action identity needs to be a first-class field so constructors reject missing IDs, assertion-mode
tests catch duplicate IDs, and output joins can use typed identity instead of optional metadata.

### Derive IDs from action payloads or object hashes

Rejected. Payload-derived IDs are algorithm-specific and object hashes are not stable across JVMs,
serialization boundaries, or online/offline runs. The decoder or request builder must provide the
domain identity explicitly.

### Remove all positional compatibility immediately

Rejected for now because older algorithm jars still need to run. The compatibility surface is kept
narrow: raw-action requests synthesize positional IDs, and bulk-score rankers use positional scores
only when every scoring decision has a `null` action ID. New code should not rely on either path.

## Expected impact

Model scores do not change. Ranking order can change only where candidates have exactly equal scores.
If metrics move, the movement should be concentrated in tied-score examples.

Next, read [Requests, decisions, and examples](../../concepts/data-model/index.md) for the current identity contract or
return to the [migration index](../index.md).
