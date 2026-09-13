---
title: Ranking and prediction contracts
description: Exact ordering, identity, tie-breaking, metadata, and JSON-output rules for rankers and bulk scorers
tags: [reference, ranking, scoring, prediction, action-id, output]
related_docs:
  - ../../concepts/data-model/index.md
  - ../java-api/index.md
  - ../cli/index.md
  - ../../guides/score-equivalence/index.md
  - ../../components/local-algorithm-server/index.md
---

# Ranking and prediction contracts

Ranking crosses three representations with different ordering rules:

| Surface | Collection order means |
| --- | --- |
| `RankingRequest.actions()` | Caller-supplied candidate order |
| `BulkScoreResponse.decisions()` | The same order as `RankingRequest.actions()` |
| `RankingResponse.decisions()` | Final rank order chosen by the algorithm |
| Ranking `hv algorithm predict` row | Original request-action order; each entry's `rank` carries the final position |
| Ranker response from `hv algorithm serve` | Final rank order |

Do not infer identity from any of those positions. `actionId` is the stable identity used to align candidates,
decisions, outcomes, audits, and online evidence.

## Bulk scoring is position-aligned

`BulkScorer<SHARED, ACTION>.score(request)` must return exactly one `ScoringDecision` for every request action, at the
same index and with the same action ID. A bulk scorer computes scores; it does not own the final ordering policy.

Return a `BulkScoreResponse` when the scorer must also preserve:

- feature-store responses associated with the computation;
- response-level additional properties;
- per-decision additional properties.

An implementation that reorders its score decisions has violated the contract even if every action ID is present.

## A ranker owns final order

`Ranker<SHARED, ACTION>.rank(request)` returns `RankingResponse.decisions()` in final rank order. Every decision should
carry the stable request action ID and, when available, its original request-local action index.

Hotvect's offline formatter and ranker-to-scorer adapter require one complete decision per request action. They reject
unknown, duplicate, or missing action IDs. A custom containing application may define a narrower or filtering ranker,
but it cannot then assume those complete-ranking adapters accept that result.

## Standard adapters

### `BulkScoreGreedyRanker`

`BulkScoreGreedyRanker` turns a bulk scorer into a ranker:

1. call the scorer once;
2. require the returned count to match the request action count;
3. build one ranking decision per request position;
4. merge action and scoring-decision metadata;
5. sort by descending score and the deterministic tie policy below;
6. preserve response-level metadata and the feature-store response container.

The adapter's action-ID/order check is assertion-only in the current implementation. Production callers must enforce
the `BulkScorer` alignment contract themselves rather than relying on JVM assertions being enabled.

### `RankerToBulkScorer`

`RankerToBulkScorer` turns a complete ranker into a request-aligned scorer. It re-aligns decisions by action ID, not by
the ranker's output position. It fails when the ranker returns an unknown ID, a duplicate ID, or an incomplete result.
The final scoring decisions are emitted in request order.

`BulkScoreGreedyRanker.close()` closes its wrapped scorer. `RankerToBulkScorer` does not override the no-op
`Algorithm.close()` default, so the caller retains lifecycle ownership of its wrapped ranker. Account for that
difference when adapters are assembled outside an `AlgorithmInstance`.

## Deterministic equal-score ties

`BulkScoreGreedyRanker` sorts by:

1. score descending;
2. an unsigned fixed-seed Murmur3 key derived from `exampleId` and `actionId`;
3. `actionId` lexicographically if the hash keys collide.

This spreads equal-score actions without depending on request order or JVM object hashes and remains stable for a
fixed example ID and action ID. Changing either ID can change an equal-score order. A ranker with its own policy is not
required to use this helper, so record the intended tie policy in tests when equivalence depends on rank order.

## Additional-property precedence

Additional-property maps are shallow-merged. When the same key occurs more than once, the value from the later source
wins.

The standard score/rank adapters merge:

```text
available-action properties → scoring/ranking-decision properties
```

The ranking prediction formatter builds root properties in this order:

```text
ranking-response properties → shared-payload properties → ranking-request properties
```

It builds each result's properties in this order:

```text
outcome properties → action-payload properties → available-action properties → decision properties
```

Keep a key owned by one layer where possible. If two layers intentionally use the same key, test the precedence rather
than assuming maps are nested or combined recursively.

When decision properties contain the reserved `features` map produced by feature logging, the formatter emits it as
the top-level result field `feature_audit` and removes it from `additional_properties`.

## Ranking prediction JSON

`hv algorithm predict` writes one JSON object per decoded example. A ranking row has this shape:

```json
{
  "example_id": "example-001",
  "additional_properties": {
    "request_source": "recorded"
  },
  "result": [
    {
      "action_id": "candidate-a",
      "rank": 1,
      "score": 0.42,
      "reward": 0.0,
      "additional_properties": {
        "action_category": "category-a"
      }
    },
    {
      "action_id": "candidate-b",
      "rank": 0,
      "score": 0.91,
      "reward": 1.0
    }
  ]
}
```

For a ranker, `result` is deliberately aligned to the original request actions. Sort by `rank` when a consumer wants
the final presentation order. This alignment lets a row compare scores, rewards, and recorded online data without
losing the request identity.

Field rules:

| Field | Rule |
| --- | --- |
| `example_id` | Copied from the decoded example |
| `action_id` | Stable candidate identity; unique within the result |
| `rank` | Zero-based final rank |
| `score` | Present when the decision supplies a score; required by the standard evaluator |
| `probability` | Present when the decision supplies one |
| `reward` | Present when the decoded example has an outcome; may be absent for unlabeled prediction |
| `feature_audit` | Present only when feature logging supplied decision-level feature data |
| `additional_properties` | Emitted only when the merged map is nonempty |

With `--include-feature-store-responses`, the root additional properties also contain the reserved
`__feature_store_responses` value. Use this only for focused diagnostics.

## TopK prediction JSON

TopK has no request candidate list to align against. Its `result` array follows the returned decision order and assigns
`rank` from that zero-based position. Outcomes are joined by action ID when available.

`ThemedTopK` adds these root fields:

```json
{
  "action_list_id": "list-001",
  "action_list_metadata": {
    "theme": "example"
  }
}
```

The algorithm must consistently return `ThemedTopKResponse` when it exposes the `ThemedTopK` shape.

## Local server output is a different projection

`hv algorithm serve` accepts one raw decoder-compatible example but returns a serving/debug projection rather than the offline
prediction row. Ranker decisions are in final rank order under `decisions`, rewards are omitted, display metadata may
be added, and the selected algorithm, parameter, and runtime identities are attached.

Do not use the local-server JSON shape as the offline evaluator input. See
[Local algorithm server and debugger](../../components/local-algorithm-server/index.md) for that API.
