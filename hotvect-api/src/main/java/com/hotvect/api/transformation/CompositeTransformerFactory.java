package com.hotvect.api.transformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.common.TransformerFactory;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.execution.ExecutionContext;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;

public interface CompositeTransformerFactory <TRANSFORMER> extends TransformerFactory {
    /**
     * @deprecated Implement the {@link AlgorithmDependencies} overload. Retained so algorithm JARs
     *     compiled against the published v10 singleton dependency contract remain executable.
     */
    @Deprecated(forRemoval = true, since = "10.40.0")
    default TRANSFORMER apply(
            Optional<JsonNode> hyperparameters,
            Map<String, InputStream> parameters,
            Map<String, AlgorithmInstance<?>> algorithmDependencies) {
        throw new UnsupportedOperationException(
                "Composite transformer factory must implement create(..., AlgorithmDependencies)");
    }

    /**
     * @deprecated Implement the {@link AlgorithmDependencies} overload.
     */
    @Deprecated(forRemoval = true, since = "10.49.0")
    default TRANSFORMER create(
            ExecutionContext executionContext,
            Optional<JsonNode> hyperparameters,
            Map<String, InputStream> parameters,
            Map<String, AlgorithmInstance<?>> algorithmDependencies) {
        return apply(hyperparameters, parameters, algorithmDependencies);
    }

    /** Constructs a transformer from every runtime-resolved named dependency. */
    default TRANSFORMER create(
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

    SortedSet<? extends Namespace> getUsedFeatures(Optional<JsonNode> transformerHyperparameters);

}
