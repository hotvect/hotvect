package com.hotvect.api.data.topk;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class TopKResponse<ACTION> {
    private final List<TopKDecision<ACTION>> topKDecisions;
    private final Map<String, Object> additionalProperties;

    private TopKResponse(List<TopKDecision<ACTION>> topKDecisions, Map<String,Object> additionalProperties){
        this.topKDecisions = topKDecisions;
        this.additionalProperties = additionalProperties;
    }
    public Map<String, Object> getAdditionalProperties() {
        return additionalProperties;
    }

    public List<TopKDecision<ACTION>> getTopKDecisions() {
        return topKDecisions;
    }

    public static <ACTION> TopKResponse<ACTION> newResponse(List<TopKDecision<ACTION>> topKDecisions){
        return new TopKResponse<>(topKDecisions, Collections.emptyMap());
    }

    public static <ACTION> TopKResponse<ACTION> newResponse(List<TopKDecision<ACTION>> topKDecisions, Map<String, Object> additionalProperties){
        return new TopKResponse<>(topKDecisions, additionalProperties);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TopKResponse<?> that = (TopKResponse<?>) o;
        return Objects.equals(topKDecisions, that.topKDecisions) && Objects.equals(additionalProperties, that.additionalProperties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(topKDecisions, additionalProperties);
    }

    @Override
    public String toString() {
        return "TopKResponse{" +
                "topKDecisions=" + topKDecisions +
                ", additionalProperties=" + additionalProperties +
                '}';
    }
}
