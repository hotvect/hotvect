package com.hotvect.api.algodefinition.common;

import com.hotvect.api.vectorization.Vectorizer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

public interface VectorizerFactory<VEC extends Vectorizer> extends BiFunction<Optional<JsonNode>, Map<String, InputStream>, VEC> {
    @Override
    VEC apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters);
}
