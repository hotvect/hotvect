package com.hotvect.api.algodefinition.scoring;

import com.hotvect.api.algodefinition.common.VectorizerFactory;
import com.hotvect.api.vectorization.ScoringVectorizer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public interface ScoringVectorizerFactory<RECORD> extends VectorizerFactory<ScoringVectorizer<RECORD>> {
    @Override
    ScoringVectorizer<RECORD> apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters);
}
