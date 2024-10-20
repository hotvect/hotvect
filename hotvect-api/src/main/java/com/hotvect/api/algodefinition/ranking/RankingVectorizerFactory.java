package com.hotvect.api.algodefinition.ranking;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.common.VectorizerFactory;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public interface RankingVectorizerFactory<SHARED, ACTION> extends VectorizerFactory {
    RankingVectorizer<SHARED, ACTION> apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters);
}
