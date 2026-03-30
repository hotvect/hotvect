---
title: Dynamic JAR Loading and Class Isolation
description: How HotVect uses dynamic JAR loading to share code between offline and online environments while allowing algorithm-specific implementations
tags: [architecture, class-loading, hot-deploy, java, concepts]
difficulty: intermediate
estimated_time: 20 minutes
prerequisites:
  - Basic understanding of Java class loading
  - Familiarity with HotVect algorithms
related_docs:
  - ../index.md
  - ../motivation/index.md
  - ../../guides/develop-algorithms/index.md
related_code:
  - hotvect-online-util/src/main/java/com/hotvect/onlineutils/hotdeploy/StrictChildFirstClassLoader.java
  - hotvect-online-util/src/main/java/com/hotvect/onlineutils/hotdeploy/HotvectFactory.java
next_steps:
  - Develop your first algorithm
  - Understand namespace identity
---

# Dynamic JAR Loading and Class Isolation

## Overview

In recommendation systems and ML applications, you need to run the same feature extraction code in at least two different environments:

1. **Offline context**: Training, backtesting, and batch processing
2. **Online context**: Real-time API serving

HotVect uses **dynamic JAR loading** (also called "hot deployment") to ensure that feature engineering code behaves identically in both environments. This eliminates the common problem of offline/online discrepancies that plague many ML systems.

## Why Dynamic JAR Loading?

### The Problem

Traditional approaches to deploying ML models often result in:

- **Code duplication**: Separate implementations for training and serving
- **Language mismatches**: Python for training, Java/C++ for serving
- **Feature drift**: Offline and online feature calculations diverge over time
- **Tight coupling**: Model changes require redeploying the entire application

### The HotVect Solution

HotVect uses Java's dynamic class loading to:

- **Share code**: Same algorithm JAR runs in both offline and online contexts
- **Enable hot deployment**: Load new algorithms without restarting the application
- **Isolate implementations**: Multiple algorithm versions can coexist in the same JVM process
- **Maintain consistency**: Identical feature engineering guarantees offline/online parity

This is the same principle used by big data frameworks like Spark and Hadoop for dynamic job submission.

## Java Class Loading Fundamentals

### Standard Parent-First Delegation

Java's default class loading follows **parent-first delegation**:

```
1. Check if class is already loaded
2. Delegate to parent class loader
3. Only if parent can't find it, load from child
```

This ensures classes from the system classpath (JDK, application framework) are shared across all code.

### Child-First Loading

For isolation, we need **child-first loading**:

```
1. Check if class is already loaded
2. Try to load from child class loader first
3. Only if not found, delegate to parent
```

This allows each algorithm JAR to have its own implementation of shared library classes.

## HotVect's Implementation: StrictChildFirstClassLoader

HotVect implements a custom class loader called `StrictChildFirstClassLoader` that combines both strategies:

### Class Loading Rules

```java
public class StrictChildFirstClassLoader extends URLClassLoader {
    private final Set<String> requiredChildClasses;

    @Override
    protected Class<?> loadClass(String name, boolean resolve) {
        // 1. Check if already loaded
        Class<?> cls = findLoadedClass(name);
        if (cls != null) return cls;

        // 2. Is this class required to be algorithm-specific?
        boolean mustLoadInChild = requiredChildClasses.contains(name);

        if (!mustLoadInChild) {
            // 3. Use parent-first for shared classes
            return super.loadClass(name, resolve);
        }

        // 4. Use child-first for algorithm-specific classes
        try {
            return findClass(name);  // Load from algorithm JAR
        } catch (ClassNotFoundException e) {
            throw new ClassNotFoundException(
                "This class must be loaded in child classloader: " + name, e);
        }
    }
}
```

### Key Insight

The `requiredChildClasses` set determines which classes MUST be loaded from the algorithm JAR. Currently, HotVect enforces this for:

- `com.hotvect.core.transform.Namespaces`: Each algorithm gets its own namespace registry

For all other classes, the decision is made based on **classpath presence**:
- If the class exists in the parent classpath (HotVect framework, common libraries), it's shared
- If the class only exists in the algorithm JAR, it's loaded from there

## Runtime ownership contract (important)

When Hotvect loads an algorithm JAR, class ownership is split between the **runtime** (`hv`/offline util/online util) and the **algorithm JAR**.

### Runtime must provide

- `com.hotvect:hotvect-api` (interfaces and contracts)
- Offline/online utility classes (`hotvect-offline-util`, `hotvect-online-util`)
- shared infrastructure libraries (logging, JSON, etc.)

### Algorithm JAR must provide

- your feature/transformation code
- Hotvect implementation modules used by your algorithm, typically:
  - `com.hotvect:hotvect-core`
  - `com.hotvect:hotvect-catboost` (if using CatBoost factories/encoders/scorers)
  - `com.hotvect:hotvect-vw` / `com.hotvect:hotvect-tensorflow` (if used)

The native CatBoost runtime (`ai.catboost:*`) can still be provided by the runtime classpath, but that does **not** replace Java classes from `com.hotvect:hotvect-catboost`.

### hv9/hv10 compatibility matrix

| Runner (`hv`) | Algorithm JAR build target | Result | Notes |
|---|---|---|---|
| v9 | v9 | ✅ Supported | Standard pairing |
| v10 | v9 | ✅ Usually supported | Keep `hotvect-core`/backend modules in the algorithm JAR |
| v9 | v10 | ❌ Not supported | Older runner misses newer classes/methods |
| v10 | v10 | ✅ Supported | Standard pairing |

If a mixed-major run fails, first verify dependency ownership in the algorithm JAR before debugging feature code.

## What Gets Shared vs. Isolated

### Always Shared (Parent Classpath)

These classes are shared across all algorithms:

1. **HotVect API**: All interfaces and base classes (`hotvect-api` module)
   - `Algorithm`, `AlgorithmFactory`, `Vectorizer`, etc.

2. **Common frameworks**: Standard libraries that should be consistent
   - Jackson (JSON processing)
   - SLF4J (logging)
   - Guava
   - Apache Commons

3. **ML native runtimes**: Libraries that load native code
   - CatBoost (native `.so`/`.dylib` files)
   - These MUST be shared because JVM can only load native libraries once

### Algorithm-Specific (Algorithm JAR)

These go in the algorithm JAR and are isolated per algorithm:

1. **Feature engineering code**: Your custom transformation logic
   - `ComputingXxx` classes
   - Custom vectorizers
   - Ranking policies

2. **Algorithm-specific dependencies**: Libraries used only by your algorithm
   - Custom JSON parsers
   - Domain-specific utilities

3. **Shared algorithm libraries**: Code reused across multiple of YOUR algorithms
   - Even if code is shared between your algorithms, it goes in each JAR
   - This allows different versions to coexist

### Maven Dependency Scopes

Use Maven's `<scope>` to control what's included in your algorithm JAR:

```xml
<!-- Runtime contract - keep as provided -->
<dependency>
    <groupId>com.hotvect</groupId>
    <artifactId>hotvect-api</artifactId>
    <scope>provided</scope>
</dependency>

<!-- Algorithm implementation modules - include in algorithm JAR -->
<dependency>
    <groupId>com.hotvect</groupId>
    <artifactId>hotvect-core</artifactId>
</dependency>

<dependency>
    <groupId>com.hotvect</groupId>
    <artifactId>hotvect-catboost</artifactId>
    <!-- only if your algorithm uses CatBoost factories -->
</dependency>

<!-- Your algorithm-specific code -->
<dependency>
    <groupId>com.example</groupId>
    <artifactId>my-shared-algorithm-code</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Benefits and Use Cases

### 1. Hot Deployment in Production

Without restarting your application:
- Deploy new algorithm versions
- Run A/B tests with different algorithms
- Roll back to previous versions
- Gradually ramp traffic to new models

### 2. Version Coexistence

Multiple algorithm versions can run simultaneously:
```
/api/recommendations?experiment=control  -> algorithm v1.0.0
/api/recommendations?experiment=treatment -> algorithm v2.0.0
```

Both use the same HotVect framework but have isolated feature engineering code.

### 3. Offline/Online Consistency

The exact same JAR runs in:
- SageMaker training jobs
- Local development with `hv train`
- Offline evaluation with `hv predict`
- Production Spring Boot applications

### 4. Independent Algorithm Development

Teams can:
- Develop algorithms independently
- Use different versions of shared libraries
- Deploy on different schedules
- Test in isolation

## Practical Guidelines

### DO: Use Provided Scope for Framework Dependencies

```xml
<dependency>
    <groupId>com.hotvect</groupId>
    <artifactId>hotvect-api</artifactId>
    <scope>provided</scope>
</dependency>
```

### DO: Include Shared Algorithm Code in Your JAR

Even if multiple algorithms share the same utility library, include it in each algorithm JAR. This prevents version conflicts.

### DON'T: Confuse native runtime with Hotvect backend modules

`ai.catboost:*` (native runtime) and `com.hotvect:hotvect-catboost` (Java factories/encoders/scorers) are different layers. Your algorithm still needs the Hotvect backend module classes it directly references.

### DON'T: Assume Parent Classes Are Available

Your algorithm should be self-contained except for the HotVect API. If you need a library, include it (unless it's truly framework-level like Jackson).

## Related Frameworks

This pattern is widely used in:

- **Apache Spark**: Job JARs are loaded dynamically into the Spark driver/executors
- **Apache Hadoop**: MapReduce jobs are dynamically loaded
- **OSGi**: Comprehensive module system with class loader isolation
- **Java EE application servers**: WAR files with isolated class loaders

## Technical Details

### Namespace Isolation

The `Namespaces` class is the only class currently forced to be child-first:

```java
Set<String> required = Set.of("com.hotvect.core.transform.Namespaces");
```

This ensures each algorithm has its own namespace registry, preventing namespace collisions when multiple algorithms are loaded.

### Class Loader Hierarchy

```
Bootstrap ClassLoader (JDK classes)
    ↓
System ClassLoader (HotVect framework, shared libraries)
    ↓
StrictChildFirstClassLoader (Algorithm JAR 1)
    ↓
Algorithm-specific classes

System ClassLoader (same)
    ↓
StrictChildFirstClassLoader (Algorithm JAR 2)
    ↓
Algorithm-specific classes (different version OK!)
```

### Thread Safety

The class loader implementation is thread-safe:

```java
synchronized (getClassLoadingLock(name)) {
    // Load class atomically
}
```

This allows concurrent requests to load different classes without conflicts.

## Troubleshooting

### ClassNotFoundException

**Symptom**: `ClassNotFoundException` when loading algorithm

**Cause**: Required class is missing from both algorithm JAR and parent classpath

**Solution**: Add the dependency to your algorithm's `pom.xml` without `provided` scope

### NoClassDefFoundError

**Symptom**: `NoClassDefFoundError` at runtime

**Cause**: Transitive dependency marked as `provided` but not available in parent classpath

**Solution**: Either include the dependency in your JAR, or ensure it's in the framework classpath

### LinkageError / Duplicate Class

**Symptom**: `LinkageError` or classes loading from wrong location

**Cause**: Class exists in both algorithm JAR and parent classpath

**Solution**: Mark framework dependencies as `provided` to exclude them from algorithm JAR

### Native Library Already Loaded

**Symptom**: `UnsatisfiedLinkError: Native library already loaded in another classloader`

**Cause**: Trying to load CatBoost or other native library from algorithm JAR

**Solution**: Mark the native library dependency as `provided` and ensure it's in parent classpath

## Summary

HotVect's dynamic JAR loading enables:

- Code sharing between offline training and online serving
- Hot deployment of new algorithms without restarts
- Isolation between algorithm versions
- Independent algorithm development and deployment

The key is understanding what should be shared (framework, native libraries) vs. isolated (algorithm code, algorithm-specific dependencies).

Use Maven's `provided` scope correctly, and you'll have a robust, maintainable ML serving infrastructure.
