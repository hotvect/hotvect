package com.eshioji.hotvect.api;

import com.eshioji.hotvect.api.vectorization.Vectorizer;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.function.Function;

public interface VectorizerFactory<R> extends Function<Optional<JsonNode>, Vectorizer<R>> {
    @Override
    Vectorizer<R> apply(Optional<JsonNode> parameter);
}
