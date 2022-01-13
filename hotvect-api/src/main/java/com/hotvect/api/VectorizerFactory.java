package com.hotvect.api;

import com.hotvect.api.vectorization.Vectorizer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

public interface VectorizerFactory<R> extends BiFunction<Optional<JsonNode>, Map<String, InputStream>, Vectorizer<R>> {
    @Override
    Vectorizer<R> apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters);
}
