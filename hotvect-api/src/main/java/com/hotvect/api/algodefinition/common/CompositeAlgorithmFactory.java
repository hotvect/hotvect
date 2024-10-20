package com.hotvect.api.algodefinition.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algorithms.Algorithm;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public interface CompositeAlgorithmFactory<ALGO extends Algorithm> extends AlgorithmFactory {
    ALGO apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters, Map<String, AlgorithmInstance<?>> algorithmDependencies);
}
