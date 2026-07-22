---
title: Algorithm JAR loading and class ownership
description: How Hotvect loads algorithm JARs, isolates namespace state, and divides dependencies between the runtime and algorithm artifact
tags: [architecture, class-loading, java, concepts]
difficulty: intermediate
related_docs:
  - ../index.md
  - ../../guides/develop-algorithms/index.md
  - ../../reference/version-compatibility/index.md
---

# Algorithm JAR loading and class ownership

Hotvect loads algorithm code from a JAR at runtime. This lets offline tools and online integrations exercise the same
algorithm implementation, but it does not make their inputs, parameters, or surrounding services identical.

## What the online loader actually does

`HotvectFactory` creates a `StrictChildFirstClassLoader` for the algorithm JAR. Despite the class name, ordinary class
loading remains parent-first. When feature-runtime code requests the following class, it must come from the algorithm
JAR:

```text
com.hotvect.core.transform.Namespaces
```

That rule gives each loaded feature runtime its own namespace registry. An algorithm that uses `Namespaces` must
therefore package `hotvect-core`; a policy-only algorithm that never requests the class can load without it.

For other classes:

1. the parent classloader is consulted first;
2. if the parent does not provide the class, the algorithm JAR may provide it.

Resources use the opposite lookup order: the algorithm JAR is checked before the parent. This allows an embedded
algorithm definition to win over a resource with the same name in the runtime.

## Runtime and algorithm ownership

Treat dependency ownership as part of the artifact contract.

| Owner | Typical contents |
| --- | --- |
| Runtime | `hotvect-api`, online/offline utility classes, shared infrastructure, and native libraries supplied by the application or runner |
| Algorithm JAR | Feature code, factories, the embedded algorithm definition, `hotvect-core`, and backend modules the algorithm directly uses |

The algorithm JAR should normally include modules such as `hotvect-core`, `hotvect-catboost`, `hotvect-tensorflow`, or
the Java `hotvect-python` module when its code references them. Keep `hotvect-api` on the runtime side.

The native CatBoost library and `com.hotvect:hotvect-catboost` are different layers. A runtime-provided native library
does not supply the Hotvect factories and scorers from `hotvect-catboost`.

## Maven shape

```xml
<dependency>
  <groupId>com.hotvect</groupId>
  <artifactId>hotvect-api</artifactId>
  <scope>provided</scope>
</dependency>

<dependency>
  <groupId>com.hotvect</groupId>
  <artifactId>hotvect-core</artifactId>
</dependency>

<dependency>
  <groupId>com.hotvect</groupId>
  <artifactId>hotvect-catboost</artifactId>
</dependency>
```

Include only the backend modules the algorithm uses. Verify the resulting shaded JAR rather than assuming Maven scope
or transitive dependency behavior produced the intended artifact.

## What this buys you

- The same algorithm JAR can be exercised in local, backtest, and serving paths.
- Algorithm-specific classes that are absent from the parent classpath can vary between loaded JARs.
- Namespace identity is isolated per algorithm classloader.
- Algorithm-definition resources are resolved from the algorithm JAR first.

This reduces one source of online/offline drift. It does not prove parity: the request representation, parameters ZIP,
effective definition, external data, and runtime configuration must still match.

## Compatibility is not inferred from loading alone

Successful class loading does not prove that two Hotvect versions are compatible. A JAR can still fail with
`NoSuchMethodError`, `LinkageError`, a changed artifact layout, or a backend-specific contract mismatch.

Use the same Hotvect release for the runner and algorithm when possible. For a mixed-version case, run the smallest
task that exercises the required path and inspect the selected JAR and parameters. See
[Version compatibility](../../reference/version-compatibility/index.md).

## The classloader is not a security boundary

An algorithm JAR executes with the privileges of the host JVM. The special namespace-loading rule isolates namespace
registry state; the overall classloader does not sandbox file, network, process, or reflection access. The current
downloader validates expected files and artifact identity metadata, but it does not verify a cryptographic signature
or content digest.

Only load artifacts from a trusted publishing path. Integrity and authorization controls belong around artifact
publication and download, not in the classloader.

## Troubleshooting

| Failure | Check first |
| --- | --- |
| `ClassNotFoundException` for algorithm code | The class is present in the built algorithm JAR |
| `Namespaces` must load in the child | The JAR contains `hotvect-core` and `com/hotvect/core/transform/Namespaces.class` |
| `NoClassDefFoundError` | A referenced transitive dependency was not packaged and is not runtime-owned |
| `NoSuchMethodError` or `LinkageError` | Parent and child resolved incompatible versions of a shared class |
| Native library already loaded | The algorithm JAR incorrectly packages a native runtime that should be supplied once by the host |

Inspect a JAR directly with `jar tf algorithm.jar` or `unzip -l algorithm.jar` before changing dependency scopes.
