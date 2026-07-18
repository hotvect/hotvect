package com.hotvect.api.transformation;

import com.fasterxml.jackson.databind.JsonNode;
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
     * @deprecated Use create(...).
     */
    @Deprecated(forRemoval = true, since = "10.40.0")
    TRANSFORMER apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters, Map<String, AlgorithmInstance<?>> algorithmDependencies);

    default TRANSFORMER create(
            ExecutionContext executionContext,
            Optional<JsonNode> hyperparameters,
            Map<String, InputStream> parameters,
            Map<String, AlgorithmInstance<?>> algorithmDependencies
    ) {
        return apply(hyperparameters, parameters, algorithmDependencies);
    }

    SortedSet<? extends Namespace> getUsedFeatures(Optional<JsonNode> transformerHyperparameters);
}
