---
title: Requests, decisions, and examples
description: Understand the typed data contracts used by Hotvect ranking, TopK, offline training, and evaluation
tags: [concepts, data, ranking, topk, examples]
---

# Requests, decisions, and examples

Hotvect separates an application's domain objects from the envelopes needed to execute and evaluate a decision. The
application supplies typed shared and action payloads; Hotvect supplies request, decision, response, example, and
outcome contracts around them.

Understanding this data model makes the rest of the framework much easier to read.

## Ranking: shared context plus candidate actions

A `RankingRequest<SHARED, ACTION>` contains:

| Field | Meaning |
| --- | --- |
| `exampleId` | Stable identifier for this request or offline example |
| `shared` | Data shared by all candidates in the request |
| `actions` | Ordered `AvailableAction<ACTION>` candidates |
| `additionalProperties` | Optional request metadata propagated outside the typed payload |

Each `AvailableAction` contains a stable `actionId`, the application-owned action payload, and optional action metadata.
The contract requires action IDs to be nonblank and unique within a request. The current duplicate-ID check is a Java
assertion, so callers must enforce the contract even when assertions are disabled.

For an illustrative document ranker, `SHARED` could be a query context and `ACTION` could be a document. Hotvect does
not prescribe either class.

```java
var request = RankingRequest.ofAvailableActions(
    "example-001",
    new QueryContext("sample query"),
    List.of(
        AvailableAction.of("doc-a", new Document("First document")),
        AvailableAction.of("doc-b", new Document("Second document"))
    )
);
```

The stable ID is the identity of the candidate in this request. The list position is only a request-local index used
for efficient alignment.

## Ranking decisions and responses

A `Ranker` returns a `RankingResponse<ACTION>`. Its ordered `RankingDecision` values contain:

- the stable action ID;
- the original request-local action index when the builder supplies it;
- the action payload;
- an optional score or probability;
- optional decision metadata.

The response order expresses the ranking. A score alone does not reorder the response for you; the algorithm owns the
final ordering policy.

```java
var decision = RankingDecision.builder("doc-b", 1, secondDocument)
    .withScore(0.82)
    .build();
```

Stable action IDs allow audits, outcomes, tie-breakers, and downstream consumers to refer to a candidate without
depending only on its original position. Rankers should preserve the request-local index when it is available; the
two-argument decision builder otherwise uses `-1`.

## Compare the decision shapes

| Shape | Input | Output and ordering responsibility |
| --- | --- | --- |
| `Scorer<RECORD>` | One application record | One `double`; normally a child capability or custom-host contract |
| `BulkScorer<SHARED, ACTION>` | `RankingRequest` with candidates | `BulkScoreResponse` with one aligned `ScoringDecision` per input action; it preserves request order |
| `Ranker<SHARED, ACTION>` | `RankingRequest` with candidates | Ordered `RankingResponse`; the ranker owns reordering and final policy |
| `TopK<SHARED, ACTION>` | `TopKRequest` without candidates | Up to `k` selected decisions; the algorithm owns candidate sourcing and selection |
| `ThemedTopK` | The same TopK request boundary | TopK decisions plus an action-list ID and string list metadata |

Use bulk scoring when another component deliberately owns final ordering. Use ranking when the caller expects the
algorithm itself to return the ordered decision.

## Offline examples add outcomes

Training and evaluation need more than a live request. A `RankingExample` combines:

```text
example ID + offline ranking request + observed outcomes
```

`OfflineRankingRequest` extends the ranking request with a `FeatureStoreResponseContainer`. This lets recorded examples
carry feature-store results that were obtained outside the offline JVM.

A `RankingOutcome` associates an observed outcome with a ranking decision. Fully labelled examples are positional in
the current contract: outcome position and action ID must agree with the corresponding request action. Some reporting
paths can handle partial or unlabelled examples by action ID, but encoders that require complete labels validate the
full alignment.

This distinction matters:

- a **request** asks the algorithm to decide;
- a **decision** records what the algorithm chose or scored;
- an **outcome** records what was later observed;
- an **example** packages the request and outcomes for offline work.

## Decoders and encoders sit at the boundary

Raw offline rows are not the public algorithm API. An `ExampleDecoder` converts each source representation into one or
more typed examples. An `ExampleEncoder` converts typed examples and transformed values into the format required by a
training library.

```text
JSONL / text / another source format
  → example decoder
  → typed request + outcomes
  → transformer or vectorizer
  → example encoder
  → training-library input
```

The containing online application performs its own adaptation from a live request into the same public request shape.
It normally does not reuse the offline file decoder.

## TopK has a different request boundary

`TopKRequest<SHARED>` contains an example ID, occurrence time, shared context, and `k`. It does **not** contain the
candidate list used by ranking. Candidate retrieval or generation is part of the TopK algorithm's responsibility.

A `TopKResponse` contains selected `TopKDecision` values. `ThemedTopK` narrows that response to
`ThemedTopKResponse`, which also carries an action-list ID and string metadata for the selected list.

Choose ranking when the caller supplies candidates to order. Choose TopK when the algorithm owns selecting up to `k`
actions from its available source.

## Feature-store responses are carried, not fetched by the data model

Offline requests and responses can carry a `FeatureStoreResponseContainer`, keyed by view name. The container is a data
contract; it is not a network client. In generated ranking transformers, algorithm-side factory code passes a
`FeatureStoreRetriever` into the generated transformer. If a containing application owns the client, the algorithm
must explicitly bridge that host dependency into its retriever; the Hotvect loader does not inject one automatically.

## Design rules for algorithm authors

- Use domain-specific Java types for `SHARED`, `ACTION`, and `OUTCOME` rather than runner-specific JSON nodes when a
  stable domain contract exists.
- Assign stable action IDs before calling the algorithm and keep them unique within the request.
- Treat request order and action identity as separate concepts.
- Keep the final ordering or selection policy in the public algorithm, not hidden in a decoder.
- Make offline decoders reproduce the data the algorithm actually needs; do not infer parity merely because the Java
  request class is shared.

Next, read [Feature computation](../feature-computation/index.md) to see how shared and action values become model
features, or [Complete algorithms](../complete-algorithm/index.md) to place these types in the larger runtime contract.
