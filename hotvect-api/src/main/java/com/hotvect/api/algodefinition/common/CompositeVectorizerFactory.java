package com.hotvect.api.algodefinition.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.execution.ExecutionContext;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public interface CompositeVectorizerFactory<VECTORIZER extends Vectorizer> extends VectorizerFactory {
    /**
     * @deprecated Implement the {@link AlgorithmDependencies} overload. Retained so algorithm JARs
     *     compiled against the published v10 singleton dependency contract remain executable.
     */
    @Deprecated(forRemoval = true, since = "10.40.0")
    default VECTORIZER apply(
            Optional<JsonNode> hyperparameters,
            Map<String, InputStream> parameters,
            Map<String, AlgorithmInstance<?>> algorithmDependencies) {
        throw new UnsupportedOperationException(
                "Composite vectorizer factory must implement create(..., AlgorithmDependencies)");
    }

    /**
     * @deprecated Implement the {@link AlgorithmDependencies} overload.
     */
    @Deprecated(forRemoval = true, since = "10.49.0")
    default VECTORIZER create(
            ExecutionContext executionContext,
            Optional<JsonNode> hyperparameters,
            Map<String, InputStream> parameters,
            Map<String, AlgorithmInstance<?>> algorithmDependencies) {
        return apply(hyperparameters, parameters, algorithmDependencies);
    }

    /** Constructs a vectorizer from every runtime-resolved named dependency. */
    default VECTORIZER create(
            ExecutionContext executionContext,
            Optional<JsonNode> hyperparameters,
            Map<String, InputStream> parameters,
            AlgorithmDependencies algorithmDependencies) {
        return create(
                executionContext,
                hyperparameters,
                parameters,
                algorithmDependencies.asMap());
    }
}
