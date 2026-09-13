package com.hotvect.api.algodefinition;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** The complete recursive identity of one constructed algorithm graph node. */
public record AlgorithmRuntimeId(
        ParameterizedAlgorithmId algorithm,
        Map<String, AlgorithmRuntimeId> dependencies) {

    public AlgorithmRuntimeId {
        Objects.requireNonNull(algorithm, "algorithm must not be null");
        dependencies = immutableDependencies(dependencies);
    }

    public static AlgorithmRuntimeId leaf(ParameterizedAlgorithmId algorithm) {
        return new AlgorithmRuntimeId(algorithm, Map.of());
    }

    /** Builds one node's complete identity from identities computed for its resolved children. */
    public static AlgorithmRuntimeId from(
            AlgorithmInstance<?> algorithmInstance,
            Map<String, AlgorithmRuntimeId> dependencies) {
        Objects.requireNonNull(algorithmInstance, "algorithmInstance must not be null");
        return new AlgorithmRuntimeId(
                ParameterizedAlgorithmId.from(
                        algorithmInstance.algorithmDefinition(),
                        algorithmInstance.algorithmParameterMetadata()),
                dependencies);
    }

    /** Returns an opaque, deterministic printable form of the complete recursive identity. */
    public String value() {
        if (dependencies.isEmpty()) {
            return algorithm.value();
        }
        String renderedDependencies = dependencies.entrySet().stream()
                .map(dependency -> dependency.getKey() + "=" + dependency.getValue().value())
                .reduce((left, right) -> left + ";" + right)
                .orElseThrow();
        return algorithm.value() + "[" + renderedDependencies + "]";
    }

    @Override
    public String toString() {
        return value();
    }

    private static Map<String, AlgorithmRuntimeId> immutableDependencies(
            Map<String, ? extends AlgorithmRuntimeId> dependencies) {
        Objects.requireNonNull(dependencies, "dependencies must not be null");
        TreeMap<String, AlgorithmRuntimeId> sorted = new TreeMap<>();
        dependencies.forEach((dependencyName, runtimeId) -> {
            if (dependencyName == null || dependencyName.isBlank()) {
                throw new IllegalArgumentException("dependency name must not be blank");
            }
            sorted.put(
                    dependencyName,
                    Objects.requireNonNull(runtimeId, "dependency runtime ID must not be null"));
        });
        return Collections.unmodifiableMap(sorted);
    }
}
