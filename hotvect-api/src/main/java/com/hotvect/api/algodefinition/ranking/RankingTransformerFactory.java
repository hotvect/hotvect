package com.hotvect.api.algodefinition.ranking;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.execution.ExecutionContext;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public interface RankingTransformerFactory<SHARED, ACTION> {
    /**
     * @deprecated Use create(...).
     */
    @Deprecated(forRemoval = true, since = "10.40.0")
    RankingTransformer<SHARED, ACTION> apply(Optional<JsonNode> hyperparameter, Map<String, InputStream> parameter);

    default RankingTransformer<SHARED, ACTION> create(
            ExecutionContext executionContext,
            Optional<JsonNode> hyperparameter,
            Map<String, InputStream> parameter
    ) {
        return apply(hyperparameter, parameter);
    }
}
