package com.hotvect.api.algodefinition.ranking;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.common.NonCompositeAlgorithmFactory;
import com.hotvect.api.algorithms.BulkScorer;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public interface BulkScorerFactory<DEPENDENCY, SHARED, ACTION> extends NonCompositeAlgorithmFactory<DEPENDENCY, BulkScorer<SHARED, ACTION>> {
    @Override
    BulkScorer<SHARED, ACTION> apply(DEPENDENCY dependency, Map<String, InputStream> parameters, Optional<JsonNode> hyperparameter);
}
