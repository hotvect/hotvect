---
title: Train your first model-backed algorithm
description: Build a synthetic ranker, train parameters locally, package them, and load them for bounded inference
tags: [getting-started, algorithm, training, parameters, ranker]
difficulty: intermediate
prerequisites:
  - Completed Build your first algorithm
  - Hotvect 10.43.1 installed from source
  - JDK 21 and Maven available
related_docs:
  - ../first-algorithm/index.md
  - ../local-train/index.md
  - ../pipeline-stages/index.md
  - ../../concepts/artifacts-and-identity/index.md
---

# Train your first model-backed algorithm

This tutorial continues from [Build your first algorithm](../first-algorithm/index.md). It adds the smallest useful
training lifecycle: one numerical feature, a reward, a training encoder, a model-producing command, and a factory that
loads the resulting parameter.

The model is intentionally elementary. It fits one coefficient through the origin with least squares and scores a
candidate as `coefficient × signal`. The point is to make every Hotvect boundary visible, not to recommend this model
for a real ranking problem. The complete example was compiled, trained, packaged, and used for prediction with
Hotvect 10.43.1.

```text
labeled JSONL
    → decode → vectorize → encode TSV
    → training_command → model_parameter
    → Hotvect parameter package

unlabelled JSONL + algorithm package + parameter package
    → decode → vectorize → load model_parameter → rank → prediction JSONL
```

In the current implementation, the algorithm package is a JAR and the parameter package is a ZIP.

## Before you start

Complete the first-algorithm tutorial and activate the same Hotvect Python environment. You also need JDK 21, Maven,
`rg`, `unzip`, and `jq`. This tutorial uses only synthetic data and the local filesystem.

Create a fresh project with the same `pom.xml` as the first tutorial, but set its artifact ID to
`trainable-document-ranker`:

```bash
mkdir -p /tmp/hv-first-trainable-algorithm/src/main/java/org/example/trainable
mkdir -p /tmp/hv-first-trainable-algorithm/src/main/resources
cd /tmp/hv-first-trainable-algorithm
```

Copy the earlier POM, then change only its artifact ID:

```bash
cp /tmp/hv-first-algorithm/pom.xml pom.xml
```

```xml
<artifactId>trainable-document-ranker</artifactId>
```

Keep the dependencies unchanged. No additional test source is required here because the last two sections exercise
the real training and prediction paths.

## 1. Define the application data

Create `src/main/java/org/example/trainable/QueryContext.java`:

```java
package org.example.trainable;

public record QueryContext(String query) {}
```

Create `src/main/java/org/example/trainable/Document.java`:

```java
package org.example.trainable;

public record Document(String title, double signal) {}
```

`signal` is the example's only model feature. Keeping it explicit makes it possible to follow the value through both
training and inference.

## 2. Decode labelled and unlabelled examples

Create `src/main/java/org/example/trainable/DocumentExampleDecoderFactory.java`:

```java
package org.example.trainable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotvect.api.algodefinition.ranking.RankingExampleDecoderFactory;
import com.hotvect.api.codec.ranking.RankingExampleDecoder;
import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.ranking.OfflineRankingRequest;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.data.ranking.RankingOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class DocumentExampleDecoderFactory
        implements RankingExampleDecoderFactory<QueryContext, Document, Double> {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    @SuppressWarnings("removal")
    public RankingExampleDecoder<QueryContext, Document, Double> apply(
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
                            new Document(
                                    action.required("title").asText(),
                                    action.required("signal").asDouble())));
                }

                var request = OfflineRankingRequest.ofAvailableActions(
                        exampleId,
                        shared,
                        actions,
                        FeatureStoreResponseContainer.empty());
                var outcomes = new ArrayList<RankingOutcome<Double, Document>>();
                if (root.has("rewards")) {
                    JsonNode rewards = root.required("rewards");
                    if (rewards.size() != actions.size()) {
                        throw new IllegalArgumentException("rewards must align with actions");
                    }
                    for (int index = 0; index < actions.size(); index++) {
                        var action = actions.get(index);
                        var decision = RankingDecision.builder(
                                        action.actionId(),
                                        index,
                                        action.action())
                                .build();
                        outcomes.add(new RankingOutcome<>(decision, rewards.get(index).asDouble()));
                    }
                }
                return List.of(new RankingExample<>(exampleId, request, outcomes));
            } catch (Exception error) {
                throw new IllegalArgumentException("Invalid example JSON", error);
            }
        };
    }
}
```

Training records include a positional `rewards` array. Prediction records omit it. The decoder produces the same
typed request in both cases, while labelled records additionally carry one outcome per action ID.

## 3. Compute the model feature

Create `src/main/java/org/example/trainable/DocumentVectorizerFactory.java`:

```java
package org.example.trainable;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.ranking.RankingVectorizer;
import com.hotvect.api.algodefinition.ranking.RankingVectorizerFactory;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.SparseVector;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

public final class DocumentVectorizerFactory
        implements RankingVectorizerFactory<QueryContext, Document> {
    private enum DocumentFeature implements Namespace {
        SIGNAL
    }

    private static final SortedSet<DocumentFeature> FEATURES =
            Collections.unmodifiableSortedSet(new TreeSet<>(List.of(DocumentFeature.SIGNAL)));

    @Override
    @SuppressWarnings("removal")
    public RankingVectorizer<QueryContext, Document> apply(
            Optional<JsonNode> configuration,
            Map<String, InputStream> parameters) {
        return new RankingVectorizer<>() {
            @Override
            public java.util.List<SparseVector> apply(
                    com.hotvect.api.data.ranking.RankingRequest<QueryContext, Document> request) {
                return request.actions().stream()
                        .map(action -> new SparseVector(new double[] {action.action().signal()}))
                        .toList();
            }

            @Override
            public SortedSet<? extends Namespace> getUsedFeatures() {
                return FEATURES;
            }
        };
    }
}
```

One input action produces one sparse vector. The same factory is loaded for encoding and prediction, so both paths use
the same feature computation.

This tutorial uses a vectorizer because its custom model consumes exactly one ordered numerical value. It keeps the
training artifact boundary visible without introducing a backend schema or generated feature graph. For a real
feature-rich algorithm, prefer a `RankingTransformer`: it preserves named typed values and is supported by the current
feature-audit path. See [Feature computation](../../concepts/feature-computation/index.md#transformer-or-vectorizer).

The preferred factory entry point is `create(...)`, but this release still requires implementations of these factory
interfaces to provide the deprecated `apply(...)` method. The local suppression keeps that compatibility warning from
hiding other warnings.

## 4. Define reward and training encoding

Create `src/main/java/org/example/trainable/DocumentRewardFunctionFactory.java`:

```java
package org.example.trainable;

import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algodefinition.common.RewardFunctionFactory;

public final class DocumentRewardFunctionFactory implements RewardFunctionFactory<Double> {
    @Override
    public RewardFunction<Double> get() {
        return reward -> reward;
    }
}
```

The raw outcome already is the numerical training target, so the reward function is the identity function.

Create `src/main/java/org/example/trainable/DocumentTrainingEncoderFactory.java`:

```java
package org.example.trainable;

import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algodefinition.ranking.RankingExampleEncoderFactory;
import com.hotvect.api.algodefinition.ranking.RankingVectorizer;
import com.hotvect.api.codec.ranking.RankingExampleEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class DocumentTrainingEncoderFactory
        implements RankingExampleEncoderFactory<
                RankingVectorizer<QueryContext, Document>, QueryContext, Document, Double> {

    @Override
    @SuppressWarnings("removal")
    public RankingExampleEncoder<QueryContext, Document, Double> apply(
            RankingVectorizer<QueryContext, Document> vectorizer,
            RewardFunction<Double> rewardFunction) {
        return new RankingExampleEncoder<>() {
            @Override
            public String encodedFileExtension() {
                return ".tsv";
            }

            @Override
            public ByteBuffer apply(
                    com.hotvect.api.data.ranking.RankingExample<
                                    QueryContext, Document, Double>
                            example) {
                var vectors = vectorizer.apply(example.request());
                if (example.outcomes().size() != vectors.size()) {
                    throw new IllegalArgumentException(
                            "training examples require one outcome per action");
                }

                var encoded = new StringBuilder();
                for (int index = 0; index < vectors.size(); index++) {
                    var action = example.request().actions().get(index);
                    var outcome = example.outcomes().get(index);
                    if (!action.actionId().equals(outcome.rankingDecision().actionId())) {
                        throw new IllegalArgumentException("outcomes must align with action ids");
                    }
                    double[] values = vectors.get(index).getNumericalValues();
                    if (values.length != 1) {
                        throw new IllegalArgumentException("expected one numerical feature");
                    }
                    encoded.append(values[0])
                            .append('\t')
                            .append(rewardFunction.applyAsDouble(outcome.outcome()))
                            .append('\n');
                }
                return ByteBuffer.wrap(encoded.toString().getBytes(StandardCharsets.UTF_8));
            }
        };
    }
}
```

The encoder turns each `(signal, reward)` pair into one TSV row. It deliberately checks complete positional alignment:
training must not silently attach an outcome to the wrong action.

## 5. Produce and load the model

Create `src/main/java/org/example/trainable/LinearModelTrainer.java`:

```java
package org.example.trainable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LinearModelTrainer {
    private LinearModelTrainer() {}

    public static void main(String[] arguments) throws Exception {
        Path encodedDirectory = Path.of(arguments[0]);
        Path modelPath = Path.of(arguments[1]);
        double sumFeatureReward = 0.0;
        double sumFeatureSquared = 0.0;

        try (var files = Files.walk(encodedDirectory)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String[] fields = line.split("\\t");
                    double feature = Double.parseDouble(fields[0]);
                    double reward = Double.parseDouble(fields[1]);
                    sumFeatureReward += feature * reward;
                    sumFeatureSquared += feature * feature;
                }
            }
        }

        double weight = sumFeatureReward / sumFeatureSquared;
        Files.writeString(modelPath, Double.toString(weight), StandardCharsets.UTF_8);
    }
}
```

This is the executable selected by `training_command`. It reads every encoded shard and writes one model file to the
path supplied by Hotvect. A real algorithm would invoke its chosen trainer at this boundary and could write either a
single file or a parameter directory.

Create `src/main/java/org/example/trainable/DocumentModelFactory.java`:

```java
package org.example.trainable;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.ranking.RankerFactory;
import com.hotvect.api.algodefinition.ranking.RankingVectorizer;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingResponse;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

public final class DocumentModelFactory
        implements RankerFactory<RankingVectorizer<QueryContext, Document>, QueryContext, Document> {

    @Override
    @SuppressWarnings("removal")
    public Ranker<QueryContext, Document> apply(
            RankingVectorizer<QueryContext, Document> vectorizer,
            Map<String, InputStream> parameters,
            Optional<JsonNode> configuration) {
        InputStream model = parameters.get("model_parameter");
        if (model == null) {
            throw new IllegalArgumentException("model_parameter is required");
        }

        final double weight;
        try {
            weight = Double.parseDouble(
                    new String(model.readAllBytes(), StandardCharsets.UTF_8).trim());
        } catch (Exception error) {
            throw new IllegalArgumentException("invalid model_parameter", error);
        }

        return request -> {
            var vectors = vectorizer.apply(request);
            var decisions = new ArrayList<RankingDecision<Document>>(vectors.size());
            for (int index = 0; index < vectors.size(); index++) {
                var available = request.actions().get(index);
                double signal = vectors.get(index).getNumericalValues()[0];
                decisions.add(RankingDecision.builder(
                                available.actionId(), index, available.action())
                        .withScore(weight * signal)
                        .build());
            }
            decisions.sort((left, right) -> {
                int scoreOrder = Double.compare(right.score(), left.score());
                return scoreOrder != 0
                        ? scoreOrder
                        : left.actionId().compareTo(right.actionId());
            });
            return RankingResponse.newResponse(decisions);
        };
    }
}
```

Hotvect supplies parameter streams using their paths inside the algorithm's section of the ZIP. Because the training
stage writes the single file `model_parameter`, the factory loads that exact key. It reads the model once while
constructing the `Ranker`, not once per request.

## 6. Connect the lifecycle

Create `src/main/resources/trainable-document-ranker-algorithm-definition.json`:

```json
{
  "algorithm_name": "trainable-document-ranker",
  "algorithm_version": "1.0.0",
  "decoder_factory_classname": "org.example.trainable.DocumentExampleDecoderFactory",
  "vectorizer_factory_classname": "org.example.trainable.DocumentVectorizerFactory",
  "reward_function_factory_classname": "org.example.trainable.DocumentRewardFunctionFactory",
  "encoder_factory_classname": "org.example.trainable.DocumentTrainingEncoderFactory",
  "algorithm_factory_classname": "org.example.trainable.DocumentModelFactory",
  "train_data_spec": {
    "data_prefix": "training-examples"
  },
  "number_of_training_days": 1,
  "training_lag_days": 1,
  "training_command": "java -cp \"{{ algorithm_jar_path }}\" org.example.trainable.LinearModelTrainer \"{{ encoded_data_file_path }}\" \"{{ parameter_output_path }}\"",
  "train_decoder_parameters": {
    "ordering": "ordered"
  }
}
```

The definition makes the lifecycle explicit:

| Field | Role |
| --- | --- |
| `decoder_factory_classname` | Turns each JSONL record into a typed example |
| `vectorizer_factory_classname` | Computes one candidate feature vector per action for training and inference |
| `reward_function_factory_classname` | Converts an observed outcome into a numerical reward |
| `encoder_factory_classname` | Writes the trainer's input format |
| `training_command` | Produces `parameter_output_path` from the encoded directory |
| `algorithm_factory_classname` | Loads packaged parameters and creates the runtime ranker |

`train_decoder_parameters.ordering` is set to `ordered` only to make this small example produce one predictable
encoded shard. Model training must not generally depend on input record order.

Build the JAR and confirm that its definition is at the JAR root:

```bash
mvn package
jar tf target/trainable-document-ranker-1.0.0.jar \
  | rg 'trainable-document-ranker-algorithm-definition.json'
```

## 7. Train and package parameters

Create one synthetic training partition:

```bash
mkdir -p data/training-examples/dt=2000-01-01
cat > data/training-examples/dt=2000-01-01/examples.jsonl <<'JSONL'
{"example_id":"train-001","shared":{"query":"sample"},"actions":[{"action_id":"doc-low","title":"Low","signal":0.2},{"action_id":"doc-mid","title":"Medium","signal":0.5},{"action_id":"doc-high","title":"High","signal":0.9}],"rewards":[0.4,1.0,1.8]}
JSONL
```

Run the parameter-preparation lifecycle:

```bash
hv train \
  --algorithm-name trainable-document-ranker \
  --algorithm-jar target/trainable-document-ranker-1.0.0.jar \
  --data-base-dir data \
  --output-base-dir output \
  --last-test-time 2000-01-02 \
  --target parameters
```

The synthetic dates are connected by the definition, not by convention hidden in the command. With one training day
and a one-day lag, the `2000-01-02` test anchor selects `dt=2000-01-01` for training.

The run performs these stages:

1. Package any parameters required for feature encoding. This example has none beyond metadata.
2. Decode, vectorize, reward, and encode the training partition.
3. Render and execute `training_command`.
4. Write parameter metadata and package the model for inference.
5. Stop before prediction because the target is `parameters`.

Inspect the concrete result:

```bash
MODEL_DIR=output/trainable-document-ranker@1.0.0/last_test_date_2000-01-02
PARAMETERS="$MODEL_DIR/trainable-document-ranker@1.0.0@last_test_date_2000-01-02.parameters.zip"

cat "$MODEL_DIR/model_parameter"
unzip -l "$PARAMETERS"
jq '{run_target, encode, train, package_predict_params}' \
  output/metadata/trainable-document-ranker@1.0.0/last_test_date_2000-01-02/result.json
```

The model file contains `2.0`: every synthetic reward is twice its signal. The ZIP includes at least these entries:

```text
trainable-document-ranker/algorithm-parameters.json
trainable-document-ranker/algorithm_definition.json
trainable-document-ranker/model_parameter
```

The ZIP is the loadable parameter package. The loose `model_parameter` beside it is a stage output and is useful for
inspection, but inference should consume the package. The packaged definition records provenance; it does not replace
the runtime definition read from the JAR or an explicit override.

## 8. Load the package for bounded inference

Create one unlabelled prediction record:

```bash
mkdir -p data/prediction-examples
cat > data/prediction-examples/examples.jsonl <<'JSONL'
{"example_id":"predict-001","shared":{"query":"sample"},"actions":[{"action_id":"doc-first","title":"First","signal":0.25},{"action_id":"doc-second","title":"Second","signal":0.75}]}
JSONL
```

Use the one-shot offline prediction command. This exercises parameter metadata validation, ZIP extraction, model
loading, decoding, vectorization, and ranking without introducing an HTTP server or containing application:

```bash
hv predict \
  --algorithm-jar target/trainable-document-ranker-1.0.0.jar \
  --algorithm-name trainable-document-ranker \
  --source-path data/prediction-examples/examples.jsonl \
  --dest-path predictions \
  --parameter-path "$PARAMETERS" \
  --metadata-path predict-metadata \
  --ordered
```

Inspect the single output shard:

```bash
cat predictions/part-00000.jsonl | python -m json.tool
```

The result is:

```json
{
  "example_id": "predict-001",
  "result": [
    {"action_id": "doc-first", "rank": 1, "score": 0.5},
    {"action_id": "doc-second", "rank": 0, "score": 1.5}
  ]
}
```

The result array is aligned to the original actions; the `rank` field contains the ranking position. The candidate
with signal `0.75` receives score `2.0 × 0.75 = 1.5` and therefore rank 0. `--ordered` preserves example order when
there are multiple input records; it does not sort this result array by rank.

## What Hotvect did—and did not do

Hotvect owned the artifact and execution lifecycle: it selected the dated data, invoked the same vectorizer for
encoding and inference, executed the declared trainer, recorded provenance, packaged the parameter, checked its
algorithm-name compatibility, and supplied its streams to the factory.

The algorithm still owned all domain decisions: JSON shape, feature meaning, reward semantics, encoded format,
training implementation, model format, and ranking policy. That separation is the central extension point. Replacing
the toy trainer with another backend changes those algorithm-owned pieces without changing the surrounding lifecycle.

This walkthrough proves mechanics, not model quality. Before treating a model as usable, add held-out examples,
quality metrics, edge-case tests, an intentional tie policy, and performance validation.

## Next

- [Compose your first algorithm](../first-composite-algorithm/index.md) adds a named child capability without hiding
  the parent decision policy.
- [Pipeline stages](../pipeline-stages/index.md) explains the generated files and metadata in more detail.
- [Train an algorithm locally](../local-train/index.md) generalizes the command to an existing repository.
- [Artifacts and identity](../../concepts/artifacts-and-identity/index.md) explains why the algorithm package,
  effective definition, and parameter package travel together.
- [Embed Hotvect in Java](../application-integration/index.md) shows how a containing application loads a validated
  algorithm instance.
