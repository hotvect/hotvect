package com.hotvect.api.algodefinition.ranking;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.common.SimpleAlgorithmFactory;
import com.hotvect.api.algorithms.Ranker;

import java.util.Optional;

public interface SimpleRankerFactory<SHARED, ACTION> extends SimpleAlgorithmFactory<Ranker<SHARED, ACTION>> {
    @Override
    Ranker<SHARED, ACTION> apply(Optional<JsonNode> hyperparameter);
}
