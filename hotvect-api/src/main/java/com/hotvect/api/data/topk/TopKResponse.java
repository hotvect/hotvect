package com.hotvect.api.data.topk;

import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.Response;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A class representing a TopK response that contains a list of TopKDecisions
 * and any additional properties.
 *
 * @param <ACTION> the type associated with each TopKDecision
 */
public class TopKResponse<ACTION> implements Response<ACTION> {
    private final List<TopKDecision<ACTION>> topKDecisions;
    private final Map<String, Object> additionalProperties;
    private final FeatureStoreResponseContainer featureStoreResponseContainer;

    protected TopKResponse(List<TopKDecision<ACTION>> topKDecisions, FeatureStoreResponseContainer featureStoreResponseContainer, Map<String,Object> additionalProperties){
        this.topKDecisions = Objects.requireNonNull(topKDecisions, "topKDecisions cannot be null");
        this.featureStoreResponseContainer = Objects.requireNonNull(featureStoreResponseContainer, "featureStoreResponseContainer cannot be null");
        this.additionalProperties = Objects.requireNonNull(additionalProperties, "additionalProperties cannot be null");
    }
    
    @Override
    public Map<String, Object> additionalProperties() {
        return additionalProperties;
    }

    @Override
    public List<TopKDecision<ACTION>> decisions() {
        return topKDecisions;
    }

    @Override
    public FeatureStoreResponseContainer featureStoreResponseContainer() {
        return featureStoreResponseContainer;
    }

    @Deprecated(forRemoval = true)
    public Map<String, Object> getAdditionalProperties() {
        return additionalProperties;
    }

    @Deprecated(forRemoval = true)
    public List<TopKDecision<ACTION>> getTopKDecisions() {
        return topKDecisions;
    }

    public static <ACTION> TopKResponse<ACTION> newResponse(List<TopKDecision<ACTION>> topKDecisions){
        return new TopKResponse<>(topKDecisions, FeatureStoreResponseContainer.empty(), Collections.emptyMap());
    }

    public static <ACTION> TopKResponse<ACTION> newResponse(List<TopKDecision<ACTION>> topKDecisions, FeatureStoreResponseContainer featureStoreResponseContainer){
        return new TopKResponse<>(topKDecisions, featureStoreResponseContainer, Collections.emptyMap());
    }

    public static <ACTION> TopKResponse<ACTION> newResponse(List<TopKDecision<ACTION>> topKDecisions, Map<String, Object> additionalProperties){
        return new TopKResponse<>(topKDecisions, FeatureStoreResponseContainer.empty(), additionalProperties);
    }

    public static <ACTION> TopKResponse<ACTION> newResponse(List<TopKDecision<ACTION>> topKDecisions, FeatureStoreResponseContainer featureStoreResponseContainer, Map<String, Object> additionalProperties){
        return new TopKResponse<>(topKDecisions, featureStoreResponseContainer, additionalProperties);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        TopKResponse<?> that = (TopKResponse<?>) o;
        return Objects.equals(topKDecisions, that.topKDecisions) && Objects.equals(additionalProperties, that.additionalProperties) && Objects.equals(featureStoreResponseContainer, that.featureStoreResponseContainer);
    }

    @Override
    public int hashCode() {
        return Objects.hash(topKDecisions, additionalProperties, featureStoreResponseContainer);
    }

    @Override
    public String toString() {
        return "TopKResponse{" +
                "topKDecisions=" + topKDecisions +
                ", additionalProperties=" + additionalProperties +
                ", featureStoreResponseContainer=" + featureStoreResponseContainer +
                '}';
    }
}
