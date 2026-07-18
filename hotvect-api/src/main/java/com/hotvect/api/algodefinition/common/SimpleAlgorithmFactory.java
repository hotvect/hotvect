package com.hotvect.api.algodefinition.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.storage.LocalStateStorage;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.execution.ExecutionContext;

import java.util.Optional;
import java.util.function.Function;

public interface SimpleAlgorithmFactory<ALGO extends Algorithm> extends Function<Optional<JsonNode>, ALGO> {
    /**
     * @deprecated Use create(...).
     */
    @Deprecated(forRemoval = true, since = "10.40.0")
    @Override
    ALGO apply(Optional<JsonNode> hyperparameter);

    default ALGO create(Optional<JsonNode> hyperparameter) {
        return apply(hyperparameter);
    }

    default ALGO create(
            ExecutionContext executionContext,
            Optional<LocalStateStorage> localStateStorage,
            Optional<JsonNode> hyperparameter) {
        return create(hyperparameter);
    }
}
