package com.hotvect.api.algodefinition.topk;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.common.AlgorithmFactory;
import com.hotvect.api.algorithms.TopK;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public interface TopKFactory<DEPENDENCY, SHARED, ACTION> extends AlgorithmFactory {
    TopK<SHARED, ACTION> apply(
            AvailableActionState<SHARED, ACTION> availableActionState,
            DEPENDENCY dependency,
            Map<String, InputStream> parameters,
            Optional<JsonNode> hyperparameter
    );
}
