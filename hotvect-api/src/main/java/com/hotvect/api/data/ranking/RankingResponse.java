package com.hotvect.api.data.ranking;

import com.hotvect.api.data.Decision;
import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.Response;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A record representing a ranking response that contains a list of RankingDecisions
 * and any additional properties.
 *
 * @param <ACTION> the type associated with each RankingDecision
 */
public record RankingResponse<ACTION>(
        List<RankingDecision<ACTION>> rankingDecisions,
        FeatureStoreResponseContainer featureStoreResponseContainer,
        Map<String, Object> additionalProperties
) implements Response<ACTION> {
    
    @Override
    public List<RankingDecision<ACTION>> decisions() {
        return rankingDecisions;
    }
    public static <ACTION> RankingResponse<ACTION> newResponse(
            List<RankingDecision<ACTION>> rankingDecisions
    ) {
        return new RankingResponse<>(rankingDecisions, FeatureStoreResponseContainer.empty(), Collections.emptyMap());
    }

    public static <ACTION> RankingResponse<ACTION> newResponse(
            List<RankingDecision<ACTION>> rankingDecisions,
            Map<String, Object> additionalProperties
    ) {
        return new RankingResponse<>(rankingDecisions, FeatureStoreResponseContainer.empty(), additionalProperties);
    }

    public static <ACTION> RankingResponse<ACTION> newResponse(
            List<RankingDecision<ACTION>> rankingDecisions,
            FeatureStoreResponseContainer featureStoreResponseContainer
    ) {
        return new RankingResponse<>(rankingDecisions, featureStoreResponseContainer, Collections.emptyMap());
    }

    public static <ACTION> RankingResponse<ACTION> newResponse(
            List<RankingDecision<ACTION>> rankingDecisions,
            FeatureStoreResponseContainer featureStoreResponseContainer,
            Map<String, Object> additionalProperties
    ) {
        return new RankingResponse<>(rankingDecisions, featureStoreResponseContainer, additionalProperties);
    }

    @Deprecated(forRemoval = true)
    public List<RankingDecision<ACTION>> getRankingDecisions() {
        return rankingDecisions;
    }

    @Deprecated(forRemoval = true)
    public Map<String, Object> getAdditionalProperties() {
        return additionalProperties;
    }

}