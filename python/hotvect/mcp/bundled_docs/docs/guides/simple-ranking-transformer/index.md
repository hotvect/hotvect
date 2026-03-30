---
title: How to Use the Simple Ranking Transformer Processor
description: Generate and validate a SimpleRankingTransformer at compile time using the annotation processor
tags: [processor, annotation, transformer, ranking, codegen]
difficulty: intermediate
estimated_time: 10 minutes
prerequisites:
  - Java 21 toolchain
  - Algorithm project using Maven or Gradle
  - Algorithm definition JSON on the classpath
related_docs:
  - ../develop-algorithms/index.md
  - ../../concepts/index.md
next_steps:
  - Review the generated report for dependency issues
  - Wire the generated transformer into your factory
---

# How to: Use the Simple Ranking Transformer processor

## What it does
The processor scans your feature classes, validates dependencies, and generates a `StreamingRankingTransformer` implementation. It also writes a Markdown report with the dependency graph and validation results.

## 1) Add the processor to your build
Add `hotvect-processor` as an annotation processor dependency (not a runtime dependency).

Example (Maven):
```xml
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
    </annotationProcessorPaths>
  </configuration>
</plugin>
```

## 2) Annotate a factory class
Annotate a class (typically your ranking transformer factory) with `@GenerateSimpleRankingTransformer`.

```java
@GenerateSimpleRankingTransformer(
    sharedType = MyShared.class,
    actionType = MyAction.class,
    features = { SharedFeatures.class, ActionFeatures.class, FeatureTransformations.class },
    algorithmDefinitionResource = "my-algorithm-definition.json"
)
public final class SimpleRankingTransformerFactory implements CompositeRankingTransformerFactory<MyShared, MyAction> {
    // ...
}
```

Required fields:
- `sharedType`, `actionType`
- `features` (classes containing `@SharedFeature`/`@Feature` methods)
- `algorithmDefinitionResource` (must include `transformer_parameters.features`)

## 3) Build and review outputs
On compilation, the processor will:
- Generate a transformer class in the standard annotation output folder.
- Write a report to `META-INF/hotvect/reports/<SpecClass>.md` in the class output.

`SharedContext` is `SharedContext<SHARED>` and exposes feature-store responses as a `Map<String, FeatureStoreResponse>` keyed by view name.

## Migration guide (Memoizing/Standard → SimpleRankingTransformer)

This migration keeps feature behavior the same while replacing manual transformer wiring with generated code.

### Concept mapping

| Legacy pattern | SimpleRankingTransformer pattern |
|---|---|
| `MemoizingRankingTransformer` / `StandardRankingTransformer.Builder` wiring | `@GenerateSimpleRankingTransformer` on factory |
| `withSharedComputation(...)` | `@SharedFeature` method |
| `withInteractionComputation(...)` | `@Feature` method (accepting shared + action context) |
| `ComputationSpec.LAZY_MEMOIZED` decisions | handled by generated execution plan |
| custom dependency bookkeeping | compile-time validation report in `META-INF/hotvect/reports/...` |

### Recommended migration sequence

1. Keep the current feature namespace list unchanged in `transformer_parameters.features`.
2. Move existing shared computations into `@SharedFeature` methods.
3. Move existing interaction/action computations into `@Feature` methods.
4. Add `@GenerateSimpleRankingTransformer(...)` to your factory and return the generated transformer.
5. Build and inspect the generated report for missing dependencies or cycles.
6. Validate parity with the playbook in [Predict score equivalence testing](../score-equivalence/index.md).

### Minimal factory shape

```java
@GenerateSimpleRankingTransformer(
    sharedType = MyShared.class,
    actionType = MyAction.class,
    features = {SharedFeatures.class, FeatureTransformations.class},
    algorithmDefinitionResource = "my-algorithm.json"
)
public final class MyTransformerFactory implements CompositeRankingTransformerFactory<MyShared, MyAction> {
    @Override
    public RankingTransformer<MyShared, MyAction> apply(...) {
        return new MyGeneratedStreamingRankingTransformer();
    }
}
```

### Common migration pitfalls

- changing feature names/order during migration (breaks parity checks)
- moving runtime/backend classes to `provided` scope by mistake
- mixing new feature logic into the migration branch (do migration first, feature changes second)
