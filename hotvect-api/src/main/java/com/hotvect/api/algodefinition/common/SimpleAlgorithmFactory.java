package com.hotvect.api.algodefinition.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algorithms.Algorithm;

import java.util.Optional;
import java.util.function.Function;

public interface SimpleAlgorithmFactory<ALGO extends Algorithm> extends Function<Optional<JsonNode>, ALGO> {
    @Override
    ALGO apply(Optional<JsonNode> hyperparameter);
}
