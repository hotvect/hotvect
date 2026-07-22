---
title: Compose your first algorithm
description: Split a ranker into a public policy and a named child scorer, then load the complete graph
tags: [getting-started, algorithm, java, composition, dependencies]
difficulty: intermediate
prerequisites:
  - Completed Build your first algorithm
  - Familiarity with requests, factories, definitions, JARs, and parameter ZIPs
related_docs:
  - ../first-algorithm/index.md
  - ../../concepts/dependencies-and-bindings/index.md
  - ../patterns/parent-child/index.md
---

# Compose your first algorithm

This tutorial turns the policy-only ranker from [Build your first algorithm](../first-algorithm/index.md) into a
two-part algorithm:

- `example-title-length-scorer` transforms and scores every document;
- `example-composite-ranker` calls that scorer, sorts by score, and owns the public ranking response.

The purpose is the composition contract, not the scoring rule. The finished request follows this path:

```text
JSON example
  → parent decoder
  → typed ranking request
  → child transformer
  → child BulkScorer
  → parent ordering policy
  → ranking response
```

The code, unit test, recursive artifact load, and HTTP response below were verified against this Hotvect version.

## Before you start

Complete [Build your first algorithm](../first-algorithm/index.md). Reuse that project's `pom.xml`, `Document`,
`QueryContext`, and `DocumentExampleDecoderFactory`. Replace its simple ranker factory, definition, and test with the
files below.

The finished source tree is:

```text
src/
├── main/
│   ├── java/org/example/ranker/
│   │   ├── CompositeDocumentRankerFactory.java
│   │   ├── Document.java
│   │   ├── DocumentExampleDecoderFactory.java
│   │   ├── DocumentFeature.java
│   │   ├── QueryContext.java
│   │   ├── TitleLengthScorerFactory.java
│   │   └── TitleLengthTransformerFactory.java
│   └── resources/
│       ├── example-composite-ranker-algorithm-definition.json
│       └── example-title-length-scorer-algorithm-definition.json
└── test/java/org/example/ranker/
    └── CompositeDocumentRankerFactoryTest.java
```

## 1. Define the child's feature boundary

Create `src/main/java/org/example/ranker/DocumentFeature.java`:

```java
package org.example.ranker;

import com.hotvect.api.data.Namespace;

public enum DocumentFeature implements Namespace {
    TITLE_LENGTH
}
```

Create `src/main/java/org/example/ranker/TitleLengthTransformerFactory.java`:

```java
package org.example.ranker;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import com.hotvect.api.algodefinition.ranking.RankingTransformerFactory;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.common.NamespacedRecordImpl;
import com.hotvect.api.data.ranking.RankingRequest;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

public final class TitleLengthTransformerFactory
        implements RankingTransformerFactory<QueryContext, Document> {

    @Override
    @SuppressWarnings("removal")
    public RankingTransformer<QueryContext, Document> apply(
            Optional<JsonNode> configuration,
            Map<String, InputStream> parameters) {
        return new RankingTransformer<>() {
            @Override
            @SuppressWarnings("removal")
            public List<NamespacedRecord<Namespace, Object>> apply(
                    RankingRequest<QueryContext, Document> request) {
                var records = new ArrayList<NamespacedRecord<Namespace, Object>>(
                        request.actions().size());
                for (var available : request.actions()) {
                    NamespacedRecord<Namespace, Object> record = new NamespacedRecordImpl<>();
                    record.put(DocumentFeature.TITLE_LENGTH, available.action().title().length());
                    records.add(record);
                }
                return records;
            }

            @Override
            public SortedSet<? extends Namespace> getUsedFeatures() {
                return new TreeSet<>(List.of(DocumentFeature.TITLE_LENGTH));
            }
        };
    }
}
```

The transformer preserves request order: its first feature record belongs to the first available action. The scorer
can therefore return one decision per input action without joining by position or title.

The current interfaces still require the deprecated `apply(...)` methods implemented above. Keep the warning
suppression local; `create(...)` delegates to this implementation when Hotvect constructs the child.

## 2. Make the child a scorer

Create `src/main/java/org/example/ranker/TitleLengthScorerFactory.java`:

```java
package org.example.ranker;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.ranking.BulkScorerFactory;
import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.scoring.BulkScoreResponse;
import com.hotvect.api.data.scoring.ScoringDecision;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

public final class TitleLengthScorerFactory
        implements BulkScorerFactory<
                RankingTransformer<QueryContext, Document>, QueryContext, Document> {

    @Override
    @SuppressWarnings("removal")
    public BulkScorer<QueryContext, Document> apply(
            RankingTransformer<QueryContext, Document> transformer,
            Map<String, InputStream> parameters,
            Optional<JsonNode> configuration) {
        return new BulkScorer<>() {
            @Override
            public BulkScoreResponse<Document> score(
                    RankingRequest<QueryContext, Document> request) {
                var transformed = transformer.transform(request);
                var decisions = new ArrayList<ScoringDecision<Document>>(transformed.size());
                for (var action : transformed) {
                    double score = ((Number) action.transformed()
                            .get(DocumentFeature.TITLE_LENGTH)).doubleValue();
                    decisions.add(ScoringDecision.of(
                            action.actionId(),
                            action.action(),
                            score,
                            action.additionalProperties()));
                }
                return BulkScoreResponse.of(
                        decisions,
                        FeatureStoreResponseContainer.empty());
            }
        };
    }
}
```

`BulkScorer` deliberately leaves ordering to its caller. That makes it a narrow child capability: the child owns
feature-to-score behavior, while the parent owns the final decision policy.

## 3. Inject the child into the parent

Create `src/main/java/org/example/ranker/CompositeDocumentRankerFactory.java`:

```java
package org.example.ranker;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.ranking.CompositeRankerFactory;
import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.data.scoring.ScoringDecision;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CompositeDocumentRankerFactory
        implements CompositeRankerFactory<QueryContext, Document> {
    public static final String SCORER_NAME = "example-title-length-scorer";

    @Override
    @SuppressWarnings({"unchecked", "removal"})
    public Ranker<QueryContext, Document> apply(
            Optional<JsonNode> configuration,
            Map<String, InputStream> parameters,
            Map<String, AlgorithmInstance<?>> dependencies) {
        var scorerInstance = (AlgorithmInstance<BulkScorer<QueryContext, Document>>)
                requireNonNull(dependencies.get(SCORER_NAME));

        return request -> {
            var scores = scorerInstance.algorithm().score(request).decisions();
            var ranked = new ArrayList<IndexedScore>(scores.size());
            for (int index = 0; index < scores.size(); index++) {
                ranked.add(new IndexedScore(index, scores.get(index)));
            }
            ranked.sort(Comparator
                    .comparingDouble((IndexedScore value) -> value.decision().score())
                    .reversed()
                    .thenComparing(
                            value -> value.decision().action().title(),
                            String.CASE_INSENSITIVE_ORDER));

            List<RankingDecision<Document>> decisions = new ArrayList<>(ranked.size());
            for (var value : ranked) {
                decisions.add(RankingDecision.builder(
                                value.decision().actionId(),
                                value.originalIndex(),
                                value.decision().action())
                        .withScore(value.decision().score())
                        .build());
            }
            return RankingResponse.newResponse(decisions);
        };
    }

    private record IndexedScore(
            int originalIndex,
            ScoringDecision<Document> decision) {}
}
```

The dependency map is keyed by algorithm name. Hotvect resolves the child definition, constructs its transformer and
scorer, wraps the result in an `AlgorithmInstance`, and supplies that instance to the parent factory. The parent uses
the score as its primary order and the title as a deterministic tie-break.

## 4. Declare both algorithms

Create `src/main/resources/example-composite-ranker-algorithm-definition.json`:

```json
{
  "algorithm_name": "example-composite-ranker",
  "algorithm_version": "1.0.0",
  "decoder_factory_classname": "org.example.ranker.DocumentExampleDecoderFactory",
  "algorithm_factory_classname": "org.example.ranker.CompositeDocumentRankerFactory",
  "dependencies": [
    "example-title-length-scorer"
  ]
}
```

Create `src/main/resources/example-title-length-scorer-algorithm-definition.json`:

```json
{
  "algorithm_name": "example-title-length-scorer",
  "algorithm_version": "1.0.0",
  "transformer_factory_classname": "org.example.ranker.TitleLengthTransformerFactory",
  "algorithm_factory_classname": "org.example.ranker.TitleLengthScorerFactory"
}
```

The parent declares the logical edge. The child definition declares how to construct the node at the other end. Both
definition resources must be at the JAR root so the same classloader can resolve them.

## 5. Test the composition contract

Create `src/test/java/org/example/ranker/CompositeDocumentRankerFactoryTest.java`:

```java
package org.example.ranker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.ranking.RankingRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class CompositeDocumentRankerFactoryTest {
    @Test
    @SuppressWarnings("removal")
    void ranksWithTheNamedChildScorer() {
        var transformer = new TitleLengthTransformerFactory()
                .apply(Optional.empty(), Map.of());
        var scorer = new TitleLengthScorerFactory()
                .apply(transformer, Map.of(), Optional.empty());
        var scorerInstance = AlgorithmInstance.externalAlgorithm(
                CompositeDocumentRankerFactory.SCORER_NAME,
                scorer);
        var ranker = new CompositeDocumentRankerFactory().apply(
                Optional.empty(),
                Map.of(),
                Map.of(CompositeDocumentRankerFactory.SCORER_NAME, scorerInstance));

        var request = RankingRequest.ofAvailableActions(
                "example-001",
                new QueryContext("sample"),
                List.of(
                        AvailableAction.of("doc-z", new Document("Zen")),
                        AvailableAction.of("doc-g", new Document("Gamma")),
                        AvailableAction.of("doc-a", new Document("Alpha"))));

        var response = ranker.rank(request);

        assertEquals(
                List.of("doc-a", "doc-g", "doc-z"),
                response.decisions().stream().map(decision -> decision.actionId()).toList());
        assertEquals(
                List.of(5.0, 5.0, 3.0),
                response.decisions().stream().map(decision -> decision.score()).toList());
    }
}
```

The test supplies the child explicitly, so a failure identifies the Java composition boundary. The next step verifies
definition discovery and artifact routing as well:

```bash
mvn test
mvn package
jar tf target/example-document-ranker-1.0.0.jar \
  | rg 'example-(composite-ranker|title-length-scorer)-algorithm-definition.json'
```

## 6. Package the graph and call the parent

The current local server expects one parameter ZIP. For a parent with one child, place each algorithm's metadata in a
folder named after that algorithm:

```text
example-composite-ranker.parameters.zip
├── example-composite-ranker/
│   └── algorithm-parameters.json
└── example-title-length-scorer/
    └── algorithm-parameters.json
```

Create the two metadata files with these contents, changing only `algorithm_name` between them:

```json
{
  "algorithm_name": "example-composite-ranker",
  "algorithm_version": "1.0.0",
  "parameter_id": "tutorial",
  "ran_at": "2000-01-01T00:00:00Z"
}
```

```json
{
  "algorithm_name": "example-title-length-scorer",
  "algorithm_version": "1.0.0",
  "parameter_id": "tutorial",
  "ran_at": "2000-01-01T00:00:00Z"
}
```

Put them under `runtime/params/<algorithm-name>/algorithm-parameters.json`, then package both directories:

```bash
(cd runtime/params && \
  zip -qr ../example-composite-ranker.parameters.zip \
    example-composite-ranker example-title-length-scorer)
```

A learned child would place its model files beside the child's `algorithm-parameters.json`. A parameter-bearing
parent would do the same in the parent folder.

Start the local debug server:

```bash
hv serve \
  --algorithm-jar target/example-document-ranker-1.0.0.jar \
  --algorithm-name example-composite-ranker \
  --parameter-path runtime/example-composite-ranker.parameters.zip \
  --port 12003
```

In another terminal:

```bash
curl -sS -X POST http://127.0.0.1:12003/predict \
  -H 'Content-Type: application/json' \
  --data-binary '{
    "example_id": "example-001",
    "shared": {"query": "sample"},
    "actions": [
      {"action_id": "doc-z", "title": "Zen"},
      {"action_id": "doc-g", "title": "Gamma"},
      {"action_id": "doc-a", "title": "Alpha"}
    ]
  }' | python -m json.tool
```

The abridged response should contain this order and these scores:

```json
{
  "type": "ranker",
  "decisions": [
    {"rank": 0, "action_id": "doc-a", "score": 5.0},
    {"rank": 1, "action_id": "doc-g", "score": 5.0},
    {"rank": 2, "action_id": "doc-z", "score": 3.0}
  ],
  "algorithm_id": "example-composite-ranker@1.0.0",
  "parameter_id": "tutorial"
}
```

## What this example establishes

- The parent exposes the public `Ranker`; the child exposes the narrower `BulkScorer` capability.
- `dependencies` records a named graph edge, while the parent factory decides when and how to call the child.
- The JAR carries the code and both definitions; the ZIP carries parameter metadata and any parameter files for both
  algorithms.
- A containing JVM can replace the named child with a host-supplied `AlgorithmInstance`, including a proxy. The current
  loader still constructs the declared child before overlaying that binding, and the host retains lifecycle ownership
  of the replacement. Read [Dependencies and bindings](../../concepts/dependencies-and-bindings/index.md) before using
  overrides.
- This walkthrough proves a one-level graph. Read [Dependencies and bindings](../../concepts/dependencies-and-bindings/index.md)
  before extending it; deeper graphs have a current nested-factory parameter boundary.

Next, use [Parent and child algorithms](../patterns/parent-child/index.md) for lifecycle targeting and definition
overrides, or [Develop a Hotvect algorithm](../develop-algorithms/index.md) to add learned parameters.
