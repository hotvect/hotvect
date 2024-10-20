package com.hotvect.api.transformation.ranking;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.transformation.memoization.InteractingComputation;
import com.hotvect.api.transformation.memoization.Computation;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public interface MemoizedTransformationFactory<SHARED, ACTION> {
    Map<? extends Namespace, Computation<SHARED, ?>> sharedTransformations(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters);
    Map<? extends Namespace, Computation<ACTION, ?>> actionTransformations(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters);
    Map<? extends Namespace, InteractingComputation<SHARED, ACTION, ?>> interactionTransformations(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters);
}
