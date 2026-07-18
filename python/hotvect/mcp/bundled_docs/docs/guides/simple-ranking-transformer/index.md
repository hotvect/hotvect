---
title: Generate a ranking transformer
description: Generate and validate a StreamingRankingTransformer from typed Java feature methods
tags: [processor, annotation, transformer, ranking, codegen]
difficulty: intermediate
estimated_time: 20 minutes
prerequisites:
  - Completed the first-algorithm tutorial or understand the JAR and definition contract
  - JDK 21 algorithm project using Maven or Gradle
  - Algorithm definition JSON available during compilation
related_docs:
  - ../first-algorithm/index.md
  - ../develop-algorithms/index.md
  - ../../concepts/feature-computation/index.md
  - ../../reference/generated-transformer-backends/index.md
  - ../../reference/algorithm-definition/index.md
---

# Generate a ranking transformer

The [first-algorithm tutorial](../first-algorithm/index.md) builds a small hand-written ranker. This guide is the next
step when an algorithm must compute an ordered set of model features for every candidate.

`@GenerateSimpleRankingTransformer` turns typed, static Java feature methods into a
`StreamingRankingTransformer`. During compilation, the processor reads the intended output features from the
algorithm definition, checks their dependency graph and types, generates Java source, and writes a reviewable report.

It does **not** generate a public Ranker, train a model, or create parameters. The transformer is one component that
your algorithm factory wires into the larger decision path.

## Before you start

You need:

- Java types for the request-level value (`SHARED`) and one candidate (`ACTION`);
- an algorithm definition resource containing `transformer_parameters.features`;
- either the CatBoost or TensorFlow generated-transformer backend;
- a project that already compiles against the matching Hotvect version.

The examples below are synthetic. They compute features for generic candidates and select CatBoost only to keep the
walkthrough concrete.

## 1. Put the processor and backend on the build paths

The backend module has two roles: its class literal is referenced by your source, and the annotation processor loads
it during compilation. Put it on both the compile classpath and the annotation processor path. Put
`hotvect-processor` only on the processor path. Keep the `hotvect-api`, Jackson, and Guava dependencies from the
first-algorithm project; the snippet below shows the additional transformer dependencies.

For Maven with CatBoost:

```xml
<dependencies>
  <dependency>
    <groupId>com.hotvect</groupId>
    <artifactId>hotvect-core</artifactId>
    <version>${hotvect.version}</version>
  </dependency>
  <dependency>
    <groupId>com.hotvect</groupId>
    <artifactId>hotvect-catboost</artifactId>
    <version>${hotvect.version}</version>
  </dependency>
</dependencies>

<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <configuration>
        <annotationProcessorPaths>
          <path>
            <groupId>com.hotvect</groupId>
            <artifactId>hotvect-processor</artifactId>
            <version>${hotvect.version}</version>
          </path>
          <path>
            <groupId>com.hotvect</groupId>
            <artifactId>hotvect-catboost</artifactId>
            <version>${hotvect.version}</version>
          </path>
        </annotationProcessorPaths>
      </configuration>
    </plugin>
  </plugins>
</build>
```

Use `hotvect-tensorflow` in both backend positions for TensorFlow. The
[backend reference](../../reference/generated-transformer-backends/index.md#build-wiring) includes Gradle wiring and
the complete type matrices.

## 2. Define the domain inputs and feature methods

Assume these application-owned input types, each in its own Java file:

```java
public record RequestContext(String topic, double referenceValue, float[] contextVector) {}
```

```java
public record Candidate(String label, double value) {}
```

A `@SharedFeature` is computed once for the request. An `@Feature` is computed for each candidate and can become an
output column. `@Inject` connects one feature method to another by name.

```java
import com.hotvect.core.annotation.SharedFeature;
import java.util.Locale;

public final class SharedFeatures {
    private SharedFeatures() {}

    @SharedFeature("normalized_topic")
    public static String normalizedTopic(RequestContext request) {
        return request.topic().toLowerCase(Locale.ROOT).strip();
    }
}
```

```java
import com.hotvect.core.annotation.Feature;
import com.hotvect.core.annotation.Inject;
import com.hotvect.core.transform.ranking.SharedContext;
import java.util.Locale;

public final class CandidateFeatures {
    private CandidateFeatures() {}

    @Feature("candidate_label")
    public static String candidateLabel(Candidate candidate) {
        return candidate.label();
    }

    @Feature("candidate_value")
    public static double candidateValue(Candidate candidate) {
        return candidate.value();
    }

    @Feature("topic_matches_label")
    public static long topicMatchesLabel(
            @Inject("normalized_topic") String normalizedTopic,
            Candidate candidate) {
        return candidate.label().toLowerCase(Locale.ROOT).contains(normalizedTopic) ? 1L : 0L;
    }

    @Feature("value_delta")
    public static double valueDelta(SharedContext<RequestContext> context, Candidate candidate) {
        return candidate.value() - context.shared().referenceValue();
    }

    @Feature("context_vector")
    public static float[] contextVector(SharedContext<RequestContext> context, Candidate candidate) {
        return context.shared().contextVector();
    }
}
```

`SharedContext` exposes the shared object and any feature-store responses returned by the retriever supplied by the
algorithm factory. A feature that needs a child algorithm can declare an `@InjectAlgorithm` parameter; that dependency
then becomes a typed constructor argument on the generated transformer.

## 3. Declare the ordered outputs

Add the output list to the algorithm's existing definition resource. This fragment shows the fields read by the
processor; a loadable algorithm definition also needs the identity and factory fields described in the
[definition reference](../../reference/algorithm-definition/index.md).

```json
{
  "transformer_parameters": {
    "features": [
      "candidate_label",
      {"name": "candidate_value", "type": "numerical"},
      "topic_matches_label",
      {"name": "value_delta", "type": "numerical"},
      {"name": "context_vector", "type": "embedding"}
    ]
  }
}
```

The list is the ordered model input contract. Every entry must resolve to an `@Feature` method; intermediate
`@SharedFeature` methods do not appear in it.

For CatBoost, scalar `String`, integer, Boolean, and floating-point return types can usually be inferred. Explicit
types include `categorical`, `numerical`, `group_id`, `text`, and `embedding`. For TensorFlow, scalar types can be
inferred, while a vector needs a fixed type such as `float32[4]`. The selected backend checks each declared or inferred
type against the Java return type at compile time.

## 4. Annotate and implement the transformer factory

Point the annotation at the domain types, feature classes, backend, and exact classpath resource. Giving the generated
class an explicit name makes the factory wiring easy to read.

```java
import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.ranking.CompositeRankingTransformerFactory;
import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.core.annotation.GenerateSimpleRankingTransformer;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;

@GenerateSimpleRankingTransformer(
    name = "CandidateFeatureTransformer",
    sharedType = RequestContext.class,
    actionType = Candidate.class,
    features = {SharedFeatures.class, CandidateFeatures.class},
    backend = com.hotvect.catboost.CatBoostBackend.class,
    algorithmDefinitionResource = "example-ranker-algorithm-definition.json"
)
public final class CandidateTransformerFactory
        implements CompositeRankingTransformerFactory<RequestContext, Candidate> {

    private CandidateFeatureTransformer newTransformer() {
        return new CandidateFeatureTransformer(request -> Map.of());
    }

    @Override
    public RankingTransformer<RequestContext, Candidate> create(
            ExecutionContext executionContext,
            Optional<JsonNode> hyperparameters,
            Map<String, InputStream> parameters,
            Map<String, AlgorithmInstance<?>> dependencies) {
        return newTransformer();
    }

    @Override
    @SuppressWarnings("removal")
    public RankingTransformer<RequestContext, Candidate> apply(
            Optional<JsonNode> hyperparameters,
            Map<String, InputStream> parameters,
            Map<String, AlgorithmInstance<?>> dependencies) {
        return newTransformer();
    }

    @Override
    public SortedSet<Namespace> getUsedFeatures(Optional<JsonNode> hyperparameters) {
        return newTransformer().getUsedFeatures();
    }
}
```

This example has no feature-store views, so its retriever returns an empty map. Supply the application-owned
`FeatureStoreRetriever<RequestContext, Candidate>` when feature methods consume feature-store responses.

`create(...)` is the execution-context-aware factory method. The current factory interface still requires the
deprecated `apply(...)` method as well, so both paths delegate to one constructor helper.

## 5. Compile and inspect the generated evidence

Compile the algorithm project:

```bash
mvn compile
```

The processor fails compilation when an output is missing, feature names collide, an injected dependency is missing
or has the wrong type, a shared feature depends on an action feature, the graph contains a reachable cycle, or an
output type is incompatible with the selected backend.

It also writes a Markdown report under the class output:

```bash
find target -path '*/META-INF/hotvect/reports/*.md' -print
```

Read that report before wiring the transformer into training. It records the generated class, selected backend,
ordered outputs, feature dependencies, type checks, unused methods, and injected algorithm dependencies.

## 6. Validate the bounded prediction path

First test the generated transformer's output order and values with a small `RankingRequest`. Then exercise the
algorithm path that consumes it with the intended definition and parameter ZIP. For a behavior-preserving change,
use [score equivalence testing](../score-equivalence/index.md) against one shared parameter artifact.

Keep these similarly named settings separate:

- `@GenerateSimpleRankingTransformer.backend` selects namespace types at Java compile time;
- `transformer_parameters.features[].type` optionally pins one output feature's type;
- `algorithm_parameters.backend` configures a Python worker runtime and does not select the generated transformer
  backend.

Continue with [Develop a Hotvect algorithm](../develop-algorithms/index.md) to place the transformer in the complete
artifact and validation workflow.
