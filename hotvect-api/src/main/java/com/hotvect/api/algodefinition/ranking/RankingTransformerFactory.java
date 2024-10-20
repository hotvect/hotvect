package com.hotvect.api.algodefinition.ranking;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public interface RankingTransformerFactory<SHARED, ACTION> {
    RankingTransformer<SHARED, ACTION> apply(Optional<JsonNode> hyperparameter, Map<String, InputStream> parameter);
}
