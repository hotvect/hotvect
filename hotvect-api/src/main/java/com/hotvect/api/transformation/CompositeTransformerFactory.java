package com.hotvect.api.transformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.common.TransformerFactory;
import com.hotvect.api.data.FeatureNamespace;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;

public interface CompositeTransformerFactory <TRANSFORMER> extends TransformerFactory {
    TRANSFORMER apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters, Map<String, AlgorithmInstance<?>> algorithmDependencies);

    SortedSet<? extends FeatureNamespace> getUsedFeatures(Optional<JsonNode> transformerHyperparameters);
}
