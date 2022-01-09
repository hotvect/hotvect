package com.eshioji.hotvect.api.algodefinition.ranking;

import com.eshioji.hotvect.api.vectorization.ranking.Vectorizer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

public interface VectorizerFactory<SHARED, ACTION> extends BiFunction<Optional<JsonNode>, Map<String, InputStream>, Vectorizer<SHARED, ACTION>> {
    @Override
    Vectorizer<SHARED, ACTION> apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters);
}
