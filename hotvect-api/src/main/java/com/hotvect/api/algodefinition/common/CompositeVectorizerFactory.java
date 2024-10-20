package com.hotvect.api.algodefinition.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmInstance;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public interface CompositeVectorizerFactory<VECTORIZER extends Vectorizer> extends VectorizerFactory {
    VECTORIZER apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters, Map<String, AlgorithmInstance<?>> algorithmDependencies);
}
