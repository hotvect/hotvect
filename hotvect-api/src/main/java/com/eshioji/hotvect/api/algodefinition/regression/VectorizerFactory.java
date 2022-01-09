package com.eshioji.hotvect.api.algodefinition.regression;

import com.eshioji.hotvect.api.vectorization.regression.Vectorizer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

public interface VectorizerFactory<RECORD> extends BiFunction<Optional<JsonNode>, Map<String, InputStream>, Vectorizer<RECORD>> {
    @Override
    Vectorizer<RECORD> apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters);
}
