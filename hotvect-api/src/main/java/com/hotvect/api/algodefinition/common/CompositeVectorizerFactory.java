package com.hotvect.api.algodefinition.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.execution.ExecutionContext;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public interface CompositeVectorizerFactory<VECTORIZER extends Vectorizer> extends VectorizerFactory {
    /**
     * @deprecated Use create(...).
     */
    @Deprecated(forRemoval = true, since = "10.40.0")
    VECTORIZER apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters, Map<String, AlgorithmInstance<?>> algorithmDependencies);

    default VECTORIZER create(
            ExecutionContext executionContext,
            Optional<JsonNode> hyperparameters,
            Map<String, InputStream> parameters,
            Map<String, AlgorithmInstance<?>> algorithmDependencies
    ) {
        return apply(hyperparameters, parameters, algorithmDependencies);
    }
}
