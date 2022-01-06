package com.eshioji.hotvect.api.algodefinition.ccb;

import com.eshioji.hotvect.api.vectorization.ccb.ActionVectorizer;
import com.eshioji.hotvect.api.vectorization.regression.Vectorizer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

public interface ActionVectorizerFactory<SHARED, ACTION> extends BiFunction<Optional<JsonNode>, Map<String, InputStream>, ActionVectorizer<SHARED, ACTION>> {
    @Override
    ActionVectorizer<SHARED, ACTION> apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters);
}
