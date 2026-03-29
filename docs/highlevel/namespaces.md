---
title: Namespace Identity and Canonicalization
description: How hotvect manages namespace singletons for efficient feature computation and data flow
tags: [namespaces, architecture, performance, identity, advanced]
difficulty: advanced
estimated_time: 25 minutes
prerequisites:
  - Understanding of hotvect concepts
  - Familiarity with Java IdentityHashMap
  - Knowledge of enum-based patterns
related_docs:
  - ./concepts.md
  - ../howto/develop-a-re-ranker-with-hotvect.md
  - ../howto/debug-feature-engineering.md
related_commands:
  - hv audit
  - hv list-transformations
next_steps:
  - Implement namespace enums correctly
  - Use canonicalization helpers
  - Validate namespace usage in tests
---

# Namespace Identity and Canonicalization

Hotvect uses *namespaces* to describe every intermediate column, feature, or computation in the pipeline. Namespaces flow through Java and Python APIs, drive memoized computations, and back feature logging. Because the runtime stores those mappings inside `IdentityHashMap`s, **the exact object instance—not just its textual name—must be reused** whenever data is written or read. This document explains how the namespace registry works, how to canonicalize handles, and how to verify that every algorithm uses the canonical singleton at build time.

## Registry Fundamentals

All namespace handles are managed by `hotvect-core/src/main/java/com/hotvect/core/transform/Namespaces.java`. Key ideas:

- **One singleton per textual name.** `Namespaces.declareNamespace(...)` registers the first object it sees for a given name and always returns that instance afterwards. Composite namespaces (`declareNamespace(nsA, nsB)`) build a new `NamespaceId` and also cache it.
- **Identity-first lookups.** Data structures such as `NamespacedRecordImpl` (`hotvect-api/src/main/java/com/hotvect/api/data/common/NamespacedRecordImpl.java`) and the internal `Mapping` helper store keys in `IdentityHashMap`s for constant-time performance. If writers and readers use different namespace objects—even with matching `toString()`—the lookup silently misses.
- **Canonicalization helpers.** `Namespaces.register(Class<? extends Enum & Namespace>)` preloads enum constants into the registry, while `Namespaces.assertCanonical(Namespace)` validates that a handle is the registered singleton. These helpers back the build-time checks in `StandardRankingTransformer`.

## Working with Enum Namespaces

Enums remain the most ergonomic way to reference namespaces in request extractors or feature factories. To keep them safe:

1. **Register once at wiring time.**
   ```java
   public final class RequestNamespaces {
       static {
           Namespaces.register(RequestAttribute.class);
       }
       private RequestNamespaces() {}
   }
   ```
   Registration ensures later string-based declarations return the enum instance.
2. **Cache the enum constant you need.** Store them in `static final` fields or inject them via constructors so you never construct ad-hoc `NamespaceId`s on the hot path.
3. **Optionally assert during tests.** Call `Namespaces.assertCanonical(RequestAttribute.sales_channel_id)` in unit or integration tests to fail fast if someone forgets to register the enum.
4. **Rely on transformer validation.** `StandardRankingTransformer` now validates every namespace set it receives (shared, action, interaction, bulk scorers, precomputed values, and features). Transformer construction fails if any enum constant was not registered, preventing production regressions.

> **Tip:** Registration is idempotent. It is safe to register the same enum class from multiple modules; hotvect simply verifies every constant matches the existing singleton.

## String-Declared and Composite Namespaces

Configuration-driven workflows (JSON algorithm definitions, Deeplearning encoder factories, etc.) frequently start from strings. The pattern is straightforward:

```java
Namespace vocabNs = Namespaces.declareNamespace("sales_channel_id");
record.put(vocabNs, index);
```

Subsequent calls with the same string reuse the singleton. If you later introduce an enum with the same textual name, register the enum *before* any string declaration so both paths converge on the enum instance.

Composite namespaces continue to work as before: pass multiple `Namespace` objects to `Namespaces.declareNamespace(nsA, nsB)` or `declareFeatureNamespace(valueType, nsA, nsB)` to get a deterministic `NamespaceId`. Canonicalization and validation apply equally to those handles.

## Where Namespaces Appear in Hotvect

| Component | Role of namespaces |
| --- | --- |
| `NamespacedRecordImpl` (`hotvect-api/.../NamespacedRecordImpl.java`) | Stores memoized computations, precalculated values, and feature registrations in `IdentityHashMap`s. |
| `Mapping` (`hotvect-api/api/core`) | Wraps namespace→computation maps for fast lookup. |
| `StandardRankingTransformer` (`hotvect-core/.../StandardRankingTransformer.java`) | Accepts namespace-keyed computations and features, validates them, and exposes feature logging metadata. |
| `python/hotvect` | Receives canonical namespace names via copied JARs; Python feature extraction relies on the same instances exported from Java. |

Whenever you touch these components, make sure the namespace objects came from the registry (either via enum registration or string/composite declaration) *before* inserting them.

## Build-Time Validation

To avoid runtime overhead, hotvect validates namespaces once per transformer:

1. Builder methods collect every namespace string into a dictionary.
2. During `StandardRankingTransformer` construction, the code converts those names back to `Namespace` objects and calls `Namespaces.assertCanonical(...)` on each set (shared/action/interaction computations, bulk scorers, precomputed values, and used features).
3. If validation fails, the builder throws an `IllegalStateException` that points to the offending set. Fix the wiring by registering the enum or reusing the canonical string declaration.

Because the validation runs during `build()`, most pipelines catch issues during unit tests or CI rather than during live traffic.

## Migration Checklist

Use this checklist when upgrading an existing algorithm or client repository (including `../example-algorithm`).

1. **Inventory namespaces.** List every enum constant and string literal that represents a namespace. For enums, add `Namespaces.register(EnumClass.class)` in a static initializer that runs exactly once during algorithm wiring.
2. **Centralize string declarations.** Ensure configuration loaders call `Namespaces.declareNamespace("name")` immediately and reuse the returned object instead of re-creating `NamespaceId`s downstream.
3. **Update builders/extractors.** When a request extractor (e.g., `RequestAttribute.sales_channel_id`) feeds a transformer, confirm the enum was registered. With the new build-time check, unregistered enums cause `StandardRankingTransformer` construction to fail, so tests should surface any missing registrations.
4. **Add regression coverage.** Write unit tests that register enums, build transformers, and assert that canonicalization errors fail fast when the setup is incomplete. The new `StandardRankingTransformerNamespaceValidationTest` under `hotvect-core` provides a template.
5. **Retest pipelines.** Run `mvn clean install` at the repository root and `cd python && make test` to confirm both Java and Python bindings consume the canonical handles.

## Debugging and Troubleshooting

- **Understand exception messages.** `Namespaces.assertCanonical` throws when a namespace name is unregistered or when a different object already claims that name. The exception includes the name, class, and identities involved—use it to trace whether a string declaration or enum registration happened first.
- **Enable targeted assertions.** You can sprinkle `Namespaces.assertCanonical(...)` in diagnostic code paths (behind a debug flag) if you suspect identity mismatches. Remove or disable them before shipping if the path is latency-sensitive.
- **Inspect the registry in tests.** `Namespaces.clear()` resets the global registry and should be called in `@BeforeEach` blocks when writing unit tests that manipulate namespace declarations.

## Patterns for Python Bindings

Python feature code interacts with namespaces through serialized transformations emitted by the Java build. The key requirement is that the Java side already canonicalized everything before JARs are copied via `python/scripts/copy_jar.py`. When you update namespace registrations, rerun `cd python && make quick` (or `make test`) to refresh the copied artifacts so Python sees the same handles.

## Quick Reference

| Task | API | Notes |
| --- | --- | --- |
| Declare plain namespace | `Namespaces.declareNamespace("name")` | First caller wins; later calls reuse the singleton. |
| Declare composite namespace | `Namespaces.declareNamespace(nsA, nsB, ...)` | Requires ≥2 components. |
| Declare feature namespace | `Namespaces.declareFeatureNamespace(ValueType, ...)` | Enforces value-type consistency. |
| Register enum namespaces | `Namespaces.register(MyEnum.class)` | Call once at startup; idempotent. |
| Validate canonicality | `Namespaces.assertCanonical(namespace)` | Throws if the handle was never registered or conflicts with an existing singleton. |
| Reset registry (tests only) | `Namespaces.clear()` | Clears internal maps and warning counters. |

By consistently registering enums, declaring string-based namespaces up front, and letting `StandardRankingTransformer` validate everything at build time, you can rely on hotvect’s identity-backed data structures without hand-written guards on every map access.
