---
title: Build your first algorithm
description: Create, test, package, and call a minimal Hotvect ranker from an empty Maven project
tags: [getting-started, algorithm, java, ranker, beginner]
difficulty: beginner
prerequisites:
  - Hotvect installed from source
  - JDK 21 and Maven available
  - Completed the example product first run
related_docs:
  - ../first-run/index.md
  - ../../concepts/how-hotvect-works/index.md
  - ../develop-algorithms/index.md
---

# Build your first algorithm

This tutorial starts with an empty directory and ends with a running Hotvect algorithm. You will create a small ranker
that orders documents alphabetically by title. It has no model, training data, feature store, child algorithm, or
external service, so every moving part belongs to the basic Hotvect contract.

You will build:

- two application data types: a request context and a candidate document;
- a `Ranker` factory containing the decision policy;
- a decoder that turns JSON into Hotvect's typed request model;
- an algorithm definition that connects the factories to a stable identity;
- a unit test and an algorithm package that the local runtime can load. The current package format is a JAR.

The complete example below was compiled, tested, loaded, and called against this Hotvect version.

## Before you start

Complete [Install and verify Hotvect](../quickstart/index.md) and the
[example product first run](../first-run/index.md). You need JDK 21, Maven, `zip`, `curl`, `rg`, and an activated Hotvect Python
environment.

## 1. Create the project

From any working directory:

```bash
mkdir -p /tmp/hv-first-algorithm/src/{main,test}/java/org/example/ranker
mkdir -p /tmp/hv-first-algorithm/src/main/resources
cd /tmp/hv-first-algorithm
```

Create `pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.example</groupId>
  <artifactId>example-document-ranker</artifactId>
  <version>1.0.0</version>

  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <hotvect.version>10.43.1</hotvect.version>
    <jackson.version>2.21.1</jackson.version>
    <guava.version>33.5.0-jre</guava.version>
    <junit.version>6.0.2</junit.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>com.hotvect</groupId>
      <artifactId>hotvect-api</artifactId>
      <version>${hotvect.version}</version>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
      <version>${jackson.version}</version>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>com.google.guava</groupId>
      <artifactId>guava</artifactId>
      <version>${guava.version}</version>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>${junit.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.14.1</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.5.4</version>
      </plugin>
    </plugins>
  </build>
</project>
```

The version above must match `hv --version` from the source installation. That installation places the artifacts in
your local Maven repository. The runtime libraries use `provided` scope because the Hotvect host supplies them when it
loads the algorithm JAR.

## 2. Define the application data

Create `src/main/java/org/example/ranker/QueryContext.java`:

```java
package org.example.ranker;

public record QueryContext(String query) {}
```

Create `src/main/java/org/example/ranker/Document.java`:

```java
package org.example.ranker;

public record Document(String title) {}
```

`QueryContext` is shared by every candidate in one request. Each `Document` is the action-specific value carried by one
candidate. Hotvect adds the stable action ID around each document when the decoder creates the request.

## 3. Implement the ranker

Create `src/main/java/org/example/ranker/DocumentRankerFactory.java`:

```java
package org.example.ranker;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.common.SimpleAlgorithmFactory;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class DocumentRankerFactory
        implements SimpleAlgorithmFactory<Ranker<QueryContext, Document>> {

    @Override
    @SuppressWarnings("removal")
    public Ranker<QueryContext, Document> apply(Optional<JsonNode> configuration) {
        return request -> {
            var indexed = new ArrayList<IndexedDocument>(request.actions().size());
            for (int i = 0; i < request.actions().size(); i++) {
                indexed.add(new IndexedDocument(i, request.actions().get(i)));
            }
            indexed.sort(Comparator.comparing(
                    value -> value.available().action().title(),
                    String.CASE_INSENSITIVE_ORDER));

            List<RankingDecision<Document>> decisions = new ArrayList<>(indexed.size());
            for (int rank = 0; rank < indexed.size(); rank++) {
                var value = indexed.get(rank);
                decisions.add(RankingDecision.builder(
                                value.available().actionId(),
                                value.originalIndex(),
                                value.available().action())
                        .withScore((double) (indexed.size() - rank))
                        .build());
            }
            return RankingResponse.newResponse(decisions);
        };
    }

    private record IndexedDocument(
            int originalIndex,
            com.hotvect.api.data.AvailableAction<Document> available) {}
}
```

The returned `Ranker` receives a typed `RankingRequest`. It preserves each candidate's original position, orders the
candidates, and emits one decision for every action ID. The score is only illustrative; this policy needs no trained
parameters.

The API's preferred factory entry point is `create(...)`, but this release still requires implementations to provide
the deprecated `apply(...)` method. The suppression is local so future compiler warnings remain visible elsewhere.

## 4. Decode input examples

The local prediction server reads the same line-oriented example representation used by offline workflows. Create
`src/main/java/org/example/ranker/DocumentExampleDecoderFactory.java`:

```java
package org.example.ranker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotvect.api.algodefinition.ranking.RankingExampleDecoderFactory;
import com.hotvect.api.codec.ranking.RankingExampleDecoder;
import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.ranking.OfflineRankingRequest;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.data.ranking.RankingOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class DocumentExampleDecoderFactory
        implements RankingExampleDecoderFactory<QueryContext, Document, JsonNode> {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    @SuppressWarnings("removal")
    public RankingExampleDecoder<QueryContext, Document, JsonNode> apply(
            Optional<JsonNode> configuration) {
        return raw -> {
            try {
                JsonNode root = OBJECT_MAPPER.readTree(raw);
                String exampleId = root.required("example_id").asText();
                var shared = new QueryContext(root.required("shared").required("query").asText());
                var actions = new ArrayList<AvailableAction<Document>>();
                for (JsonNode action : root.required("actions")) {
                    actions.add(AvailableAction.of(
                            action.required("action_id").asText(),
                            new Document(action.required("title").asText())));
                }
                var request = OfflineRankingRequest.ofAvailableActions(
                        exampleId,
                        shared,
                        actions,
                        FeatureStoreResponseContainer.empty());
                return List.of(new RankingExample<>(
                        exampleId,
                        request,
                        List.<RankingOutcome<JsonNode, Document>>of()));
            } catch (Exception error) {
                throw new IllegalArgumentException("Invalid example JSON", error);
            }
        };
    }
}
```

The decoder establishes the boundary between external JSON and your application types. It creates an offline request
because local `hv serve` reuses the offline example-decoding path. An application embedding Hotvect for online traffic
can construct an online request directly instead.

`FeatureStoreResponseContainer.empty()` says that this synthetic request carries no prefetched feature-store values.
The empty outcome list makes it an unlabelled inference example; training and evaluation examples would carry observed
outcomes here.

## 5. Connect the factories

Create `src/main/resources/example-document-ranker-algorithm-definition.json`:

```json
{
  "algorithm_name": "example-document-ranker",
  "algorithm_version": "1.0.0",
  "decoder_factory_classname": "org.example.ranker.DocumentExampleDecoderFactory",
  "algorithm_factory_classname": "org.example.ranker.DocumentRankerFactory"
}
```

The filename is part of the runtime lookup contract: `<algorithm-name>-algorithm-definition.json`. The identity inside
the file must match the name passed to Hotvect. This definition selects a simple, parameterless algorithm; trainable
and composite algorithms add the factories and dependencies their lifecycle needs.

## 6. Test the decision policy

Create `src/test/java/org/example/ranker/DocumentRankerFactoryTest.java`:

```java
package org.example.ranker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.ranking.RankingRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class DocumentRankerFactoryTest {
    @Test
    void ordersDocumentsByTitle() {
        var request = RankingRequest.ofAvailableActions(
                "example-001",
                new QueryContext("sample"),
                List.of(
                        AvailableAction.of("doc-b", new Document("Beta")),
                        AvailableAction.of("doc-a", new Document("Alpha"))));

        var response = new DocumentRankerFactory().apply(Optional.empty()).rank(request);

        assertEquals(
                List.of("doc-a", "doc-b"),
                response.decisions().stream().map(decision -> decision.actionId()).toList());
    }
}
```

Build the algorithm package:

```bash
mvn package
jar tf target/example-document-ranker-1.0.0.jar \
  | rg 'example-document-ranker-algorithm-definition.json'
```

The test should pass, Maven should create `target/example-document-ranker-1.0.0.jar`, and `jar tf` should print the
definition resource at the JAR root.

## 7. Load and call the algorithm package

This algorithm has no learned parameters. The current local serve command nevertheless requires a parameter path, so
create a metadata-only ZIP:

```bash
mkdir -p runtime/params/example-document-ranker
cat > runtime/params/example-document-ranker/algorithm-parameters.json <<'JSON'
{
  "algorithm_name": "example-document-ranker",
  "algorithm_version": "1.0.0",
  "parameter_id": "tutorial",
  "ran_at": "2000-01-01T00:00:00Z"
}
JSON
(cd runtime/params && zip -qr ../example-document-ranker.parameters.zip example-document-ranker)
```

Activate the Hotvect Python environment, then start the local debug server:

```bash
source /path/to/hotvect/python/.venv/bin/activate
hv serve \
  --algorithm-jar target/example-document-ranker-1.0.0.jar \
  --algorithm-name example-document-ranker \
  --parameter-path runtime/example-document-ranker.parameters.zip \
  --port 12002
```

In another terminal, from the project directory:

```bash
curl -sS -X POST http://127.0.0.1:12002/predict \
  -H 'Content-Type: application/json' \
  --data-binary '{
    "example_id": "example-001",
    "shared": {"query": "sample"},
    "actions": [
      {"action_id": "doc-b", "title": "Beta"},
      {"action_id": "doc-a", "title": "Alpha"}
    ]
  }' | python -m json.tool
```

The response contains `doc-a` at rank 0 and `doc-b` at rank 1:

```json
{
  "type": "ranker",
  "example_id": "example-001",
  "decisions": [
    {"rank": 0, "action_id": "doc-a", "score": 2.0},
    {"rank": 1, "action_id": "doc-b", "score": 1.0}
  ],
  "algorithm_id": "example-document-ranker@1.0.0",
  "parameter_id": "NA",
  "algorithm_runtime_id": "example-document-ranker@1.0.0@NA"
}
```

The actual response also includes nullable display fields and empty metadata objects. Stop the server with Ctrl+C.

The metadata-only ZIP satisfies the current local-server input contract, but the simple factory creates a
parameterless instance. Its runtime identity therefore uses `parameter_id: "NA"`; the `tutorial` value inside the
fixture does not become a learned-parameter identity.

## What to add next

You now have the minimum complete algorithm package: typed data, a public algorithm, a decoder, a definition, tests,
and a loadable JAR. This policy-only branch can proceed directly toward application integration. Training and backtesting
become useful after an algorithm adds data, reward, encoding, and parameter-production contracts. Add that complexity
only when the problem requires it:

| Need | Next concept |
| --- | --- |
| Compute model features | [Generate a ranking transformer](../simple-ranking-transformer/index.md) |
| Learn and package a model | [Train your first model-backed algorithm](../first-trainable-algorithm/index.md) |
| Build deterministic lookup data or assets | [Generate runtime state](../state-generation/index.md) |
| Compose reusable algorithms | [Compose your first algorithm](../first-composite-algorithm/index.md) |
| Let the algorithm own candidate selection | [Build a TopK algorithm](../topk-algorithms/index.md) |
| Run the full artifact lifecycle | [Develop an algorithm](../develop-algorithms/index.md) |
| Share boundary types and call it from a JVM application | [Embed Hotvect in Java](../application-integration/index.md) |
