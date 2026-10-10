package com.hotvect.onlineutils.hotdeploy;

import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.AlgorithmRuntimeId;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Already-constructed algorithm graphs supplied for named external dependency edges. */
public final class AlgorithmGraphDependencies {
    private static final AlgorithmGraphDependencies EMPTY = new AlgorithmGraphDependencies(Map.of());

    private final Map<String, Binding> bindings;

    public AlgorithmGraphDependencies(
            Map<String, ? extends AlgorithmGraph<?>> dependencies) {
        Objects.requireNonNull(dependencies, "dependencies must not be null");
        TreeMap<String, Binding> copied = new TreeMap<>();
        dependencies.forEach((dependencyName, graph) -> {
            if (dependencyName == null || dependencyName.isBlank()) {
                throw new IllegalArgumentException("dependency name must not be blank");
            }
            AlgorithmGraph<?> resolvedGraph = Objects.requireNonNull(
                    graph,
                    "dependency algorithm graph must not be null");
            copied.put(
                    dependencyName,
                    new Binding(resolvedGraph, resolvedGraph.root(), resolvedGraph.runtimeId()));
        });
        this.bindings = Collections.unmodifiableMap(copied);
    }

    public static AlgorithmGraphDependencies empty() {
        return EMPTY;
    }

    Map<String, Binding> asMap() {
        return bindings;
    }

    java.util.Set<String> names() {
        return bindings.keySet();
    }

    record Binding(
            AlgorithmGraph<?> graph,
            AlgorithmInstance<?> instance,
            AlgorithmRuntimeId runtimeId) {
        Binding {
            Objects.requireNonNull(graph, "graph must not be null");
            Objects.requireNonNull(instance, "instance must not be null");
            Objects.requireNonNull(runtimeId, "runtimeId must not be null");
        }
    }
}
