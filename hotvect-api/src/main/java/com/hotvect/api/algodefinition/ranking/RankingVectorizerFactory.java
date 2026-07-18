package com.hotvect.api.algodefinition.ranking;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.common.VectorizerFactory;
import com.hotvect.api.execution.ExecutionContext;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public interface RankingVectorizerFactory<SHARED, ACTION> extends VectorizerFactory {
    /**
     * @deprecated Use create(...).
     */
    @Deprecated(forRemoval = true, since = "10.40.0")
    RankingVectorizer<SHARED, ACTION> apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters);

    default RankingVectorizer<SHARED, ACTION> create(
            ExecutionContext executionContext,
            Optional<JsonNode> hyperparameters,
            Map<String, InputStream> parameters
    ) {
        return apply(hyperparameters, parameters);
    }
}
