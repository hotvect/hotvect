package com.hotvect.api.algodefinition.scoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.common.VectorizerFactory;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public interface ScoringVectorizerFactory<RECORD> extends VectorizerFactory {
    ScoringVectorizer<RECORD> apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters);
}
