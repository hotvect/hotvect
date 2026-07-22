---
title: Build a TopK algorithm
description: Select actions when the algorithm, rather than the caller, owns the candidate set
tags: [algorithm, java, topk, themed-topk]
difficulty: beginner
prerequisites:
  - Completed Build your first algorithm
  - Familiarity with Hotvect requests, factories, decoders, and definitions
related_docs:
  - ../first-algorithm/index.md
  - ../../concepts/data-model/index.md
  - ../first-composite-algorithm/index.md
---

# Build a TopK algorithm

A ranker answers “in what order should I return these candidates?” A TopK algorithm answers “which actions should I
return?” The distinction is who owns the candidate set.

| Shape | Candidate source | Request | Response |
| --- | --- | --- | --- |
| `Ranker` | The caller | Shared context plus candidate actions | Ordered decisions for those candidates |
| `TopK` | The algorithm or one of its dependencies | Example ID, occurrence time, shared context, and `k` | Selected decisions in output order |
| `ThemedTopK` | The algorithm or one of its dependencies | The same `TopKRequest` | TopK decisions plus an action-list ID and string metadata |

Use TopK when retrieval, generation, filtering, or final selection belongs behind the algorithm boundary. If a caller
already has the candidates and only needs them ordered, use a ranker.

This guide adapts the Maven project from [Build your first algorithm](../first-algorithm/index.md). Keep its POM and
runtime-provided Jackson and Guava dependencies, change the artifact ID to `example-suggestion-topk`, then replace the
ranker classes with the files below.

## 1. Define the application data

Create `src/main/java/org/example/topk/SelectionContext.java`:

```java
package org.example.topk;

public record SelectionContext(String prefix) {}
```

Create `src/main/java/org/example/topk/Suggestion.java`:

```java
package org.example.topk;

public record Suggestion(String id, String label) {}
```

The shared context describes the request. `Suggestion` is the action returned by the algorithm; it is not supplied in
the request.

## 2. Implement selection

Create `src/main/java/org/example/topk/SuggestionTopKFactory.java`:

```java
package org.example.topk;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.topk.SimpleTopKFactory;
import com.hotvect.api.algorithms.TopK;
import com.hotvect.api.data.topk.TopKDecision;
import com.hotvect.api.data.topk.TopKResponse;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class SuggestionTopKFactory
        implements SimpleTopKFactory<SelectionContext, Suggestion> {
    private static final List<Suggestion> CANDIDATES = List.of(
            new Suggestion("item-a", "Alpha"),
            new Suggestion("item-b", "Beta"),
            new Suggestion("item-c", "Gamma"));

    @Override
    @SuppressWarnings("removal")
    public TopK<SelectionContext, Suggestion> apply(Optional<JsonNode> configuration) {
        return request -> {
            if (request.k() < 0) {
                throw new IllegalArgumentException("k must be non-negative");
            }
            List<TopKDecision<Suggestion>> decisions = CANDIDATES.stream()
                    .filter(item -> item.label().startsWith(request.shared().prefix()))
                    .sorted(Comparator.comparing(Suggestion::label))
                    .limit(request.k())
                    .map(item -> TopKDecision.builder(item.id(), item).build())
                    .toList();
            return TopKResponse.newResponse(decisions);
        };
    }
}
```

The in-memory list keeps the example self-contained. A production TopK normally obtains candidates through its own
retrieval code or a declared child algorithm. The public contract remains the same in either case.

The order of `TopKDecision` values is the result order. Each decision needs a stable, non-empty action ID for local
serving and offline outcome matching. Score and probability are optional. Hotvect passes `k` to the algorithm but does
not truncate its response, so the implementation is responsible for returning no more than the requested number.

`SimpleTopKFactory` is appropriate here because the example has no model or child. Use `TopKFactory` when construction
also receives a transformer or vectorizer dependency, and `CompositeTopKFactory` when the selection policy coordinates
declared child algorithms.

## 3. Decode offline and local-server input

Create `src/main/java/org/example/topk/SuggestionExampleDecoderFactory.java`:

```java
package org.example.topk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotvect.api.algodefinition.topk.TopKExampleDecoderFactory;
import com.hotvect.api.codec.topk.TopKExampleDecoder;
import com.hotvect.api.data.topk.OfflineTopKRequest;
import com.hotvect.api.data.topk.TopKExample;
import com.hotvect.api.data.topk.TopKOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class SuggestionExampleDecoderFactory
        implements TopKExampleDecoderFactory<SelectionContext, Suggestion, JsonNode> {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    @SuppressWarnings("removal")
    public TopKExampleDecoder<SelectionContext, Suggestion, JsonNode> apply(
            Optional<JsonNode> configuration) {
        return raw -> {
            try {
                JsonNode root = OBJECT_MAPPER.readTree(raw);
                String exampleId = root.required("example_id").asText();
                var shared = new SelectionContext(
                        root.required("shared").required("prefix").asText());
                var request = OfflineTopKRequest.newOfflineTopKRequest(
                        exampleId,
                        Instant.parse(root.required("occurred_at").asText()),
                        shared,
                        root.required("k").asInt());
                return List.of(new TopKExample<>(
                        exampleId,
                        request,
                        List.<TopKOutcome<JsonNode, Suggestion>>of()));
            } catch (Exception error) {
                throw new IllegalArgumentException("Invalid example JSON", error);
            }
        };
    }
}
```

This decoder creates an unlabelled inference example. Evaluation data can add `TopKOutcome` values; each outcome joins
back to a selected action by the action ID in its `TopKDecision`. A JVM application can skip this serialized boundary
and call the algorithm with a `TopKRequest` directly.

## 4. Add the definition and test the contract

Create `src/main/resources/example-suggestion-topk-algorithm-definition.json`:

```json
{
  "algorithm_name": "example-suggestion-topk",
  "algorithm_version": "1.0.0",
  "decoder_factory_classname": "org.example.topk.SuggestionExampleDecoderFactory",
  "algorithm_factory_classname": "org.example.topk.SuggestionTopKFactory"
}
```

Create `src/test/java/org/example/topk/SuggestionTopKFactoryTest.java` to test the public request without going through
JSON:

```java
package org.example.topk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hotvect.api.data.topk.TopKDecision;
import com.hotvect.api.data.topk.TopKRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SuggestionTopKFactoryTest {
    @Test
    @SuppressWarnings("removal")
    void limitsAndOrdersTheSelection() {
        var request = new TopKRequest<>(
                "example-001",
                Instant.parse("2000-01-01T00:00:00Z"),
                new SelectionContext(""),
                2);

        var response = new SuggestionTopKFactory()
                .apply(Optional.empty())
                .apply(request);

        assertEquals(
                List.of("item-a", "item-b"),
                response.decisions().stream().map(TopKDecision::actionId).toList());
    }
}
```

Build and inspect the JAR as in the first-algorithm tutorial. The decoder accepts input such as:

```json
{
  "example_id": "example-001",
  "occurred_at": "2000-01-01T00:00:00Z",
  "shared": {"prefix": ""},
  "k": 2
}
```

Create a new metadata-only ZIP with the TopK algorithm identity—the ZIP from the ranker tutorial has a different
`algorithm_name` and must not be reused:

```bash
mkdir -p runtime/parameters
cat > runtime/parameters/algorithm-parameters.json <<'JSON'
{
  "algorithm_name": "example-suggestion-topk",
  "algorithm_version": "1.0.0",
  "parameter_id": "tutorial"
}
JSON

(cd runtime/parameters && zip -qr ../example-suggestion-topk.parameters.zip .)
```

Then follow the `hv serve` flow from
[Load and call the algorithm package](../first-algorithm/index.md#7-load-and-call-the-algorithm-package), substituting:

- `target/example-suggestion-topk-1.0.0.jar`;
- algorithm name `example-suggestion-topk`;
- `runtime/example-suggestion-topk.parameters.zip`;
- the TopK request body above.

The abridged response has `type: "topk"` and decisions for `item-a` at rank 0 and `item-b` at rank 1. As with the
parameterless ranker, its runtime parameter identity is `NA`; the metadata-only ZIP satisfies the current local-server
input contract but is not passed to the simple factory as learned state.

## Add list identity with ThemedTopK

Use ThemedTopK when the selected actions belong to a meaningful list whose identity must travel with the decisions.
For example, an application may need to distinguish two lists produced by different selection policies without
encoding that list identity into every action.

The factory interface does not change. Return the narrower `ThemedTopK` type and replace the final response construction
inside the same selection lambda:

```java
@Override
@SuppressWarnings("removal")
public ThemedTopK<SelectionContext, Suggestion> apply(
        Optional<JsonNode> configuration) {
    return request -> {
        // Build and limit decisions exactly as in the TopK implementation above.
        List<TopKDecision<Suggestion>> decisions = select(request);
        return ThemedTopKResponse.newResponse(
                "alphabetical",
                decisions,
                Map.of("strategy", "label-order"));
    };
}
```

Here `select` denotes the selection block extracted into a private method; it is ordinary application code, not a
Hotvect API. The action-list ID is required, and its metadata is `Map<String, String>`. Response-level
`additionalProperties` are a separate `Map<String, Object>` when you need machine-readable output metadata that is
not part of the list identity.

The local server recognizes a `ThemedTopKResponse` and emits the themed response shape. Offline prediction also uses a
separate themed formatter, so a factory returning `ThemedTopK` must consistently return `ThemedTopKResponse`.

Next, read [Compose your first algorithm](../first-composite-algorithm/index.md) if retrieval should be a replaceable
child, or [Embed Hotvect in Java](../application-integration/index.md) to call the typed TopK contract from an
application.
