package com.hotvect.api.algodefinition.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.storage.LocalStateStorage;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.execution.ExecutionContext;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public interface CompositeAlgorithmFactory<ALGO extends Algorithm> extends AlgorithmFactory {
    /**
     * @deprecated Use create(...).
     */
    @Deprecated(forRemoval = true, since = "10.40.0")
    ALGO apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters, Map<String, AlgorithmInstance<?>> algorithmDependencies);

    default ALGO create(ExecutionContext executionContext, Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters, Map<String, AlgorithmInstance<?>> algorithmDependencies) {
        return apply(hyperparameters, parameters, algorithmDependencies);
    }

    default ALGO create(
            ExecutionContext executionContext,
            Optional<LocalStateStorage> localStateStorage,
            Optional<JsonNode> hyperparameters,
            Map<String, InputStream> parameters,
            Map<String, AlgorithmInstance<?>> algorithmDependencies) {
        return create(executionContext, hyperparameters, parameters, algorithmDependencies);
    }
}
