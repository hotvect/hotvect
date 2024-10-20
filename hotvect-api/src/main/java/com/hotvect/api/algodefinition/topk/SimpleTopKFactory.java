package com.hotvect.api.algodefinition.topk;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.common.AlgorithmFactory;
import com.hotvect.api.algorithms.TopK;

import java.util.Optional;

public interface SimpleTopKFactory<SHARED, ACTION> extends AlgorithmFactory {
    TopK<SHARED, ACTION> apply(
            AvailableActionState<SHARED, ACTION> availableActionState,
            Optional<JsonNode> hyperparameter
    );
}
