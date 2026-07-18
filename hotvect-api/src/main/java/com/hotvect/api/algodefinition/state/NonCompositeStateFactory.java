package com.hotvect.api.algodefinition.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.common.AlgorithmFactory;
import com.hotvect.api.algodefinition.storage.LocalStateStorage;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.execution.ExecutionContext;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

public interface NonCompositeStateFactory<ALGO extends Algorithm> extends AlgorithmFactory, BiFunction<Map<String, InputStream>, Optional<JsonNode>, ALGO> {
    /**
     * @deprecated Use create(...).
     */
    @Deprecated(forRemoval = true, since = "10.40.0")
    @Override
    ALGO apply(Map<String, InputStream> parameters, Optional<JsonNode> hyperparameter);

    default ALGO create(ExecutionContext executionContext, Map<String, InputStream> parameters, Optional<JsonNode> hyperparameter) {
        return apply(parameters, hyperparameter);
    }

    default ALGO create(
            ExecutionContext executionContext,
            Optional<LocalStateStorage> localStateStorage,
            Map<String, InputStream> parameters,
            Optional<JsonNode> hyperparameter) {
        return create(executionContext, parameters, hyperparameter);
    }
}
