package com.hotvect.api.algodefinition.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.storage.LocalStateStorage;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.execution.ExecutionContext;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public interface CompositeAlgorithmFactory<ALGO extends Algorithm> extends AlgorithmFactory {
    /**
     * @deprecated Implement the {@link AlgorithmDependencies} overload. Retained so algorithm JARs
     *     compiled against the published v10 singleton dependency contract remain executable.
     */
    @Deprecated(forRemoval = true, since = "10.40.0")
    default ALGO apply(
            Optional<JsonNode> hyperparameters,
            Map<String, InputStream> parameters,
            Map<String, AlgorithmInstance<?>> algorithmDependencies) {
        throw new UnsupportedOperationException(
                "Composite factory must implement create(..., AlgorithmDependencies)");
    }

    /**
     * @deprecated Implement the {@link AlgorithmDependencies} overload.
     */
    @Deprecated(forRemoval = true, since = "10.49.0")
    default ALGO create(
            ExecutionContext executionContext,
            Optional<JsonNode> hyperparameters,
            Map<String, InputStream> parameters,
            Map<String, AlgorithmInstance<?>> algorithmDependencies) {
        return apply(hyperparameters, parameters, algorithmDependencies);
    }

    /**
     * @deprecated Implement the {@link AlgorithmDependencies} overload.
     */
    @Deprecated(forRemoval = true, since = "10.49.0")
    default ALGO create(
            ExecutionContext executionContext,
            Optional<LocalStateStorage> localStateStorage,
            Optional<JsonNode> hyperparameters,
            Map<String, InputStream> parameters,
            Map<String, AlgorithmInstance<?>> algorithmDependencies) {
        return create(executionContext, hyperparameters, parameters, algorithmDependencies);
    }

    /**
     * Constructs a newly owned algorithm from the runtime-resolved named dependencies.
     *
     * <p>The result must not be a dependency instance. Dependencies are borrowed: the graph controls
     * their lifetime, so the returned algorithm must not close them.</p>
     */
    default ALGO create(
            ExecutionContext executionContext,
            Optional<LocalStateStorage> localStateStorage,
            Optional<JsonNode> hyperparameters,
            Map<String, InputStream> parameters,
            AlgorithmDependencies algorithmDependencies) {
        return create(
                executionContext,
                localStateStorage,
                hyperparameters,
                parameters,
                algorithmDependencies.asMap());
    }
}
