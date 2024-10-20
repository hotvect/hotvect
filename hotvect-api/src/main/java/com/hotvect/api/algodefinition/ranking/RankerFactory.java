package com.hotvect.api.algodefinition.ranking;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.common.NonCompositeAlgorithmFactory;
import com.hotvect.api.algorithms.Ranker;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public interface RankerFactory<DEPENDENCY, SHARED, ACTION> extends NonCompositeAlgorithmFactory<DEPENDENCY, Ranker<SHARED, ACTION>> {
    @Override
    Ranker<SHARED, ACTION> apply(DEPENDENCY dependency, Map<String, InputStream> parameters, Optional<JsonNode> hyperparameter);
}
