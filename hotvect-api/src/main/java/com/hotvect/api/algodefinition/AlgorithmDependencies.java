package com.hotvect.api.algodefinition;

import com.google.common.reflect.TypeToken;
import com.hotvect.api.algorithms.Algorithm;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Runtime-resolved singleton algorithm for every named dependency of one algorithm. */
public final class AlgorithmDependencies {
    private static final AlgorithmDependencies EMPTY = new AlgorithmDependencies(Map.of());

    private final Map<String, AlgorithmInstance<?>> dependencies;

    /** Validates and freezes the dependency-name to actual-algorithm mapping. */
    public AlgorithmDependencies(
            Map<String, ? extends AlgorithmInstance<?>> dependencies) {
        Objects.requireNonNull(dependencies, "dependencies must not be null");
        TreeMap<String, AlgorithmInstance<?>> sorted = new TreeMap<>();
        dependencies.forEach((dependencyName, algorithm) -> {
            if (dependencyName == null || dependencyName.isBlank()) {
                throw new IllegalArgumentException("dependency name must not be blank");
            }
            sorted.put(
                    dependencyName,
                    Objects.requireNonNull(algorithm, "resolved algorithm must not be null"));
        });
        this.dependencies = Collections.unmodifiableMap(sorted);
    }

    public static AlgorithmDependencies empty() {
        return EMPTY;
    }

    private AlgorithmInstance<?> requireDependency(String dependencyName) {
        AlgorithmInstance<?> dependency = dependencies.get(dependencyName);
        if (dependency == null) {
            throw new IllegalArgumentException(
                    "Unknown algorithm dependency " + dependencyName + "; available dependencies: " + names());
        }
        return dependency;
    }

    /**
     * Returns the sole algorithm for a dependency without validating its algorithm contract.
     *
     * <p>The returned type is inferred from the call site. Use {@link #only(String, TypeToken)}
     * to check the algorithm interface and any fully resolved generic contract before activation.
     */
    @SuppressWarnings("unchecked")
    public <ALGORITHM extends Algorithm> ALGORITHM only(String dependencyName) {
        return (ALGORITHM) requireDependency(dependencyName).algorithm();
    }

    /**
     * Returns the sole algorithm for a dependency, rejecting incompatible algorithm interfaces and
     * fully resolved generic contracts. The expected type must be concrete. If the dependency's
     * contract for that interface still contains type variables, generic compatibility is not verified.
     */
    public <ALGORITHM extends Algorithm> ALGORITHM only(
            String dependencyName,
            TypeToken<ALGORITHM> algorithmType) {
        TypeToken<ALGORITHM> expectedType = AlgorithmTypeContract.requireFullyResolved(algorithmType);
        return cast(dependencyName, requireDependency(dependencyName), expectedType);
    }

    @SuppressWarnings("unchecked")
    private static <ALGORITHM extends Algorithm> ALGORITHM cast(
            String dependencyName,
            AlgorithmInstance<?> instance,
            TypeToken<ALGORITHM> expectedType) {
        TypeToken<? extends ALGORITHM> actualType =
                (TypeToken<? extends ALGORITHM>) instance.algorithmType();
        boolean compatible = expectedType.getRawType().isAssignableFrom(actualType.getRawType());
        if (compatible) {
            TypeToken<?> actualContract = actualType.getSupertype(expectedType.getRawType());
            compatible = !AlgorithmTypeContract.isConcrete(actualContract.getType())
                    || expectedType.isSupertypeOf(actualContract);
        }
        if (!compatible) {
            throw new IllegalArgumentException(
                    "Dependency " + dependencyName + " resolved algorithm "
                            + instance.algorithmDefinition().algorithmId().value()
                            + " with contract " + actualType
                            + ", which is not assignable to requested contract " + expectedType);
        }
        return (ALGORITHM) expectedType.getRawType().cast(instance.algorithm());
    }

    /**
     * Returns the immutable raw dependency graph for framework and introspection code.
     *
     * <p>Factory code normally uses {@link #only(String)}. Callers that
     * want eager contract validation can supply a {@link TypeToken} instead.
     */
    public Map<String, AlgorithmInstance<?>> asMap() {
        return dependencies;
    }

    public Set<String> names() {
        return dependencies.keySet();
    }

    public boolean isEmpty() {
        return dependencies.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof AlgorithmDependencies that
                && dependencies.equals(that.dependencies);
    }

    @Override
    public int hashCode() {
        return dependencies.hashCode();
    }

    @Override
    public String toString() {
        return dependencies.toString();
    }
}
