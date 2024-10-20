package com.hotvect.api.algodefinition.ranking;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.common.CompositeAlgorithmFactory;
import com.hotvect.api.algorithms.Ranker;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public interface CompositeRankerFactory<SHARED,ACTION> extends CompositeAlgorithmFactory<Ranker<SHARED, ACTION>> {
    @Override
    Ranker<SHARED, ACTION> apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters, Map<String, AlgorithmInstance<?>> algorithmDependencies);
}
