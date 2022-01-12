package com.eshioji.hotvect.api.algodefinition.scoring;

import com.eshioji.hotvect.api.algodefinition.common.VectorizerFactory;
import com.eshioji.hotvect.api.vectorization.ScoringVectorizer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

public interface ScoringVectorizerFactory<RECORD> extends VectorizerFactory<ScoringVectorizer<RECORD>> {
    @Override
    ScoringVectorizer<RECORD> apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters);
}
