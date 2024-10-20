package com.hotvect.api.data.ranking;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class RankingResponse<ACTION> {
    private final List<RankingDecision<ACTION>> rankingDecisions;
    private final Map<String, Object> additionalProperties;

    private RankingResponse(List<RankingDecision<ACTION>> rankingDecisions, Map<String,Object> additionalProperties){
        this.rankingDecisions = rankingDecisions;
        this.additionalProperties = additionalProperties;
    }
    public Map<String, Object> getAdditionalProperties() {
        return additionalProperties;
    }

    public List<RankingDecision<ACTION>> getRankingDecisions() {
        return rankingDecisions;
    }

    public static <ACTION> RankingResponse<ACTION> newResponse(List<RankingDecision<ACTION>> rankingDecisions){
        return new RankingResponse<>(rankingDecisions, Collections.emptyMap());
    }

    public static <ACTION> RankingResponse<ACTION> newResponse(List<RankingDecision<ACTION>> rankingDecisions, Map<String, Object> additionalProperties){
        return new RankingResponse<>(rankingDecisions, additionalProperties);
    }

    @Override
    public String toString() {
        return "RankingResponse{" +
                "rankingDecisions=" + rankingDecisions +
                ", additionalProperties=" + additionalProperties +
                '}';
    }
}
