package com.hotvect.api.algodefinition.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algorithms.Algorithm;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public interface NonCompositeAlgorithmFactory<DEPENDENCY, ALGO extends Algorithm> extends AlgorithmFactory {
    ALGO apply(DEPENDENCY dependency, Map<String, InputStream> parameters, Optional<JsonNode> hyperparameter);
}
