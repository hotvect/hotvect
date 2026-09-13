---
title: Generated transformer backends
description: Compile-time backend selection and feature type rules for @GenerateSimpleRankingTransformer
tags: [reference, annotation-processor, generated-transformer, backends, catboost, tensorflow]
related_docs:
  - ../../guides/simple-ranking-transformer/index.md
  - ../algorithm-definition/index.md
  - ../troubleshooting/index.md
---

# Generated transformer backends

`@GenerateSimpleRankingTransformer` generates a `StreamingRankingTransformer` from annotated Java feature methods.
The generated namespaces must use backend-specific `ValueType`s. A generated transformer backend is the small
compile-time strategy that maps each generated output feature to one of those `ValueType`s.

Hotvect ships two generated transformer backends:

| Backend | Class literal | Module |
|---|---|---|
| CatBoost | `com.hotvect.catboost.CatBoostBackend.class` | `hotvect-catboost` |
| TensorFlow | `com.hotvect.tensorflow.TensorFlowBackend.class` | `hotvect-tensorflow` |

## Backend-looking fields

There are three fields that often get confused. They are separate contracts.

| Location | Type | Used by | Meaning |
|---|---|---|---|
| `@GenerateSimpleRankingTransformer.backend` | Java class literal | Annotation processor | Selects the generated transformer backend at compile time |
| `transformer_parameters.features[].type` | Optional string | Selected generated transformer backend | Pins one output feature's backend-specific type |
| `algorithm_parameters.backend` | String | Python worker runtime | Selects a serving/training worker backend such as `tensorflow` or `torch` |

Do not put generated transformer backend selection in the algorithm definition. There is no
`transformer_parameters.backend` contract for the annotation processor.

## Annotation contract

Select the generated transformer backend on the annotation:

```java
@GenerateSimpleRankingTransformer(
    sharedType = RequestContext.class,
    actionType = Candidate.class,
    features = { SharedFeatures.class, ActionFeatures.class },
    backend = com.hotvect.tensorflow.TensorFlowBackend.class,
    algorithmDefinitionResource = "example-ranker-algorithm-definition.json"
)
public final class ExampleTransformerFactory {
}
```

Next, [generate a ranking transformer](../../guides/simple-ranking-transformer/index.md) or return to
[Feature computation](../../concepts/feature-computation/index.md) for the transformer/vectorizer decision.

`backend` is declared as `Class<? extends GeneratedTransformerBackend>`, so javac checks that the selected class
implements the generated transformer backend SPI. The processor then loads that class from the annotation processor
path and calls it once for each generated output feature.

## Build wiring

The backend module must be present twice:

- on the compile classpath, because the annotation references `CatBoostBackend.class` or `TensorFlowBackend.class`
- on the annotation processor path, because `hotvect-processor` loads the backend while javac is running

Maven example for CatBoost:

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

For TensorFlow, use `hotvect-tensorflow` in both places instead of `hotvect-catboost`.

Gradle example:

```kotlin
dependencies {
    implementation("com.hotvect:hotvect-core:$hotvectVersion")
    implementation("com.hotvect:hotvect-tensorflow:$hotvectVersion")

    annotationProcessor("com.hotvect:hotvect-processor:$hotvectVersion")
    annotationProcessor("com.hotvect:hotvect-tensorflow:$hotvectVersion")
}
```

## Algorithm definition feature entries

The processor reads only `transformer_parameters.features` from `algorithmDefinitionResource`.

Each entry is either:

- a string feature name, which asks the backend to infer the type from the Java return type
- an object with `name` and optional `type`, where `type` is interpreted by the selected backend

```json
{
  "transformer_parameters": {
    "features": [
      "feature_categorical_01",
      "numeric_signal_01",
      { "name": "vector_signal_01", "type": "float32[8]" }
    ]
  }
}
```

Feature order is preserved. Duplicate feature names are ignored after the first occurrence and reported as a
compiler warning.

## CatBoost type rules

CatBoost accepts these explicit `type` values. Values are case-insensitive in the algorithm definition.

| `type` | Accepted Java return types | Inferred by default |
|---|---|---|
| `categorical` | `String`, `boolean`/`Boolean`, `int`/`Integer`, `long`/`Long` | yes |
| `numerical` | `float`/`Float`, `double`/`Double` | yes |
| `group_id` | `String` | no |
| `text` | `String[]` | yes |
| `embedding` | `float[]`, `double[]` | yes |

Example:

```java
@Feature("feature_text_01")
public static String featureText(Candidate candidate) {
    return candidate.label();
}

@Feature("numeric_signal_01")
public static double numericSignal(Candidate candidate) {
    return candidate.value();
}

@Feature("vector_signal_01")
public static float[] vectorSignal(SharedContext<RequestContext> context, Candidate candidate) {
    return context.shared().contextVector();
}
```

```json
{
  "transformer_parameters": {
    "features": [
      "feature_text_01",
      { "name": "numeric_signal_01", "type": "numerical" },
      { "name": "vector_signal_01", "type": "embedding" }
    ]
  }
}
```

## TensorFlow type rules

TensorFlow accepts scalar dtypes and fixed-length rank-1 numeric arrays:

| `type` | Accepted Java return types | Inferred by default |
|---|---|---|
| `int64` | `int`/`Integer`, `long`/`Long` | yes |
| `float32` | `float`/`Float`, `double`/`Double` | yes |
| `string` | `String` | yes |
| `int64[N]` | `int[]`, `long[]` | no |
| `float32[N]` | `float[]`, `double[]` | no |

Array features cannot be inferred because the backend needs the fixed sequence length. Mix inferred scalars and
explicit arrays in the same feature list:

```json
{
  "transformer_parameters": {
    "features": [
      "feature_categorical_01",
      "numeric_signal_01",
      "feature_boolean_02",
      { "name": "vector_signal_01", "type": "float32[8]" }
    ]
  }
}
```

## Pair feature generation with encoding and inference

The annotation backend selects generated feature value types only. A complete model-backed algorithm separately
chooses an encoder for offline training data and an algorithm factory for inference.

| Model path | Encoder factory | Inference factory or integration |
| --- | --- | --- |
| Generated CatBoost transformer | `CatBoostStreamingEncoderFactory` | `CatBoostStreamingBulkScorerFactory` |
| Hand-written `ComputingRankingTransformer` with CatBoost | `CatBoostEncoderFactory` | `CatBoostBulkScorerFactory` or `CatBoostGreedyRankerFactory` |
| Generated TensorFlow transformer | `TensorFlowEncoderFactory` | Algorithm-owned managed Python worker |

There is no generic Java TensorFlow scorer in `hotvect-tensorflow`. That module produces TensorFlow-compatible feature
types, schemas, and TFRecord training data. Online SavedModel inference requires an algorithm integration that
materializes the same schema into the managed worker payload and interprets its result.

### CatBoost runtime settings

For the generated streaming path, a definition normally contains:

```json
{
  "transformer_factory_classname": "org.example.CandidateTransformerFactory",
  "encoder_factory_classname": "com.hotvect.catboost.CatBoostStreamingEncoderFactory",
  "algorithm_factory_classname": "com.hotvect.catboost.CatBoostStreamingBulkScorerFactory",
  "algorithm_parameters": {
    "task_type": "classification",
    "parallel_batch_scoring": true
  }
}
```

The parameter package must contain `model_parameter/model.parameter`. `task_type` accepts `classification`,
`regression`, or `learn_to_rank` and defaults to `classification`. Classification applies a sigmoid to the CatBoost raw
prediction; regression and learn-to-rank return the raw prediction. `parallel_batch_scoring` controls whether prepared
streaming batches are consumed through a parallel stream and defaults to `true`.

The computing-transformer scorer has a different concurrency control:
`algorithm_parameters.catboost_scorer.nofork_threshold`, defaulting to `100`. Requests above that candidate count are
recursively split on the shared fork-join pool; a nonpositive threshold disables splitting.

Both scorer paths preserve request action order and action IDs in `BulkScoreResponse`. An outer parent can use
`BulkScoreGreedyRanker` when it wants the standard descending-score and deterministic-tie policy.

### CatBoost encoding contract

The CatBoost encoders write tab-separated rows with the reward first and one column per used feature. Their schema
description marks the first column as `Label` and assigns `Num`, `Categ`, `Text`, `GroupId`, or `NumVector` to feature
columns.

Missing values are encoded as `NaN` for numerical/embedding values, `Categ_Null` for categorical/group IDs, and `NA`
for text. A text feature is a `String[]` of tokens; individual tokens must be nonempty and contain no spaces. Tabs,
newlines, carriage returns, and quotes in delimited string fields are escaped with CSV-style quoting.

### TensorFlow encoding contract

`TensorFlowEncoderFactory` writes `.tfrecord` output. Every ranking action becomes one `tf.train.Example` record with
proper TFRecord length and CRC framing. The encoder:

- writes a scalar int64 `Label`, using `1` when reward is greater than zero and `0` otherwise;
- writes transformer features under their exact namespace names;
- requires transformed action count and IDs to align with request actions and outcomes;
- emits a JSON schema through `schemaDescription()` for training and worker materialization.

Keep that label conversion and feature schema aligned with the training command and inference worker. Selecting
`TensorFlowBackend.class` alone does not launch TensorFlow or choose a model-serving implementation.

## Validation behavior

The generated transformer backend validates output features while compiling the algorithm:

- unsupported Java return type: compile error on the feature method
- invalid backend `type` string: compile error on the feature method
- declared `type` incompatible with the Java return type: compile error on the feature method
- backend class missing from the processor path: compile error on the annotated factory class
- duplicate feature in `transformer_parameters.features`: compiler warning, first occurrence wins

The generated report is written to `META-INF/hotvect/reports/<SpecClass>.md` and includes the selected backend plus
the output feature list read from the algorithm definition.

## Implement a backend

A backend implements `GeneratedTransformerBackend` and returns a Java source expression for the backend-specific
`ValueType` initializer.

```java
public final class XGBoostBackend implements GeneratedTransformerBackend {
    @Override
    public Resolution resolve(String declaredType, String returnTypeName) {
        if ("double".equals(returnTypeName) || "float".equals(returnTypeName)) {
            return Resolution.of("com.myorg.xgboost.XGBoostFeatureType.FLOAT32");
        }
        return Resolution.error("Unsupported XGBoost feature return type: " + returnTypeName);
    }
}
```

Backend implementation rules:

- provide a public no-argument constructor
- keep annotation-processing code dependency-light
- parse and validate the backend's own `type` strings inside the backend module
- return fully qualified initializer expressions so the processor does not need backend imports
- do not require processor changes for new backend type grammars

Future backend annotations use the same shape:

```java
@GenerateSimpleRankingTransformer(
    sharedType = RequestContext.class,
    actionType = Candidate.class,
    features = { SharedFeatures.class, ActionFeatures.class },
    backend = com.myorg.hotvect.xgboost.XGBoostBackend.class,
    algorithmDefinitionResource = "example-ranker-algorithm-definition.json"
)
```

```json
{
  "transformer_parameters": {
    "features": [
      { "name": "feature_categorical_01", "type": "int32" },
      { "name": "numeric_signal_01", "type": "float32" }
    ]
  }
}
```

A future backend would own its own grammar. A plain feature list is valid if that backend infers everything it needs
from Java return types:

```java
@GenerateSimpleRankingTransformer(
    sharedType = RequestContext.class,
    actionType = Candidate.class,
    features = { SharedFeatures.class, ActionFeatures.class },
    backend = com.myorg.hotvect.example.CustomBackend.class,
    algorithmDefinitionResource = "example-ranker-algorithm-definition.json"
)
```

```json
{
  "transformer_parameters": {
    "features": [
      "feature_categorical_01",
      "numeric_signal_01",
      "text_signal_02"
    ]
  }
}
```
