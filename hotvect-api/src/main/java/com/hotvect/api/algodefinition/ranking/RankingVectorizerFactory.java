package com.hotvect.api.algodefinition.ranking;

import com.hotvect.api.algodefinition.common.VectorizerFactory;
import com.hotvect.api.vectorization.RankingVectorizer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public interface RankingVectorizerFactory<SHARED, ACTION> extends VectorizerFactory<RankingVectorizer<SHARED, ACTION>> {
    @Override
    RankingVectorizer<SHARED, ACTION> apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters);
}
