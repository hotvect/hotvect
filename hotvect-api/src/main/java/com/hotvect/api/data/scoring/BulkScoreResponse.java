package com.hotvect.api.data.scoring;

import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.Response;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record BulkScoreResponse<ACTION>(
        List<ScoringDecision<ACTION>> decisions,
        FeatureStoreResponseContainer featureStoreResponseContainer,
        Map<String, Object> additionalProperties
) implements Response<ACTION> {
    public BulkScoreResponse {
        Objects.requireNonNull(decisions, "decisions cannot be null");
        Objects.requireNonNull(featureStoreResponseContainer, "featureStoreResponseContainer cannot be null");
        Objects.requireNonNull(additionalProperties, "additionalProperties cannot be null");
    }

    public static <ACTION> BulkScoreResponse<ACTION> of(
            List<ScoringDecision<ACTION>> decisions,
            FeatureStoreResponseContainer featureStoreResponseContainer
    ) {
        return new BulkScoreResponse<>(decisions, featureStoreResponseContainer, Collections.emptyMap());
    }

    public static <ACTION> BulkScoreResponse<ACTION> of(
            List<ScoringDecision<ACTION>> decisions,
            FeatureStoreResponseContainer featureStoreResponseContainer,
            Map<String, Object> additionalProperties
    ) {
        return new BulkScoreResponse<>(decisions, featureStoreResponseContainer, additionalProperties);
    }
}
