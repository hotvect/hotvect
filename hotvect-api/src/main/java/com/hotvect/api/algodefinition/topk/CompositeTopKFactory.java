package com.hotvect.api.algodefinition.topk;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.common.CompositeAlgorithmFactory;
import com.hotvect.api.algorithms.TopK;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public interface CompositeTopKFactory<SHARED,ACTION> extends CompositeAlgorithmFactory<TopK<SHARED, ACTION>> {
    TopK<SHARED, ACTION> apply(
            Optional<JsonNode> hyperparameters,
            Map<String, InputStream> parameters,
            Map<String, AlgorithmInstance<?>> algorithmDependencies
    );
}
