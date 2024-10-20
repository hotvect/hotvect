package com.hotvect.api.algodefinition.ranking;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.common.CompositeVectorizerFactory;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public interface CompositeRankingVectorizerFactory<SHARED, ACTION> extends CompositeVectorizerFactory<RankingVectorizer<SHARED, ACTION>> {
    @Override
    RankingVectorizer<SHARED, ACTION> apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters, Map<String, AlgorithmInstance<?>> algorithmDependencies);
}
