package com.hotvect.api.data.topk;

import com.hotvect.api.data.FeatureStoreResponseContainer;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ThemedTopKResponse<ACTION> extends TopKResponse<ACTION> {
    private final String actionListId;
    private final Map<String, String> actionListMetadata;

    private ThemedTopKResponse(
            String actionListId,
            List<TopKDecision<ACTION>> topKDecisions,
            Map<String, String> actionListMetadata,
            FeatureStoreResponseContainer featureStoreResponseContainer,
            Map<String, Object> additionalProperties) {
        super(topKDecisions, featureStoreResponseContainer, additionalProperties);
        this.actionListId = actionListId;
        this.actionListMetadata = actionListMetadata;
    }

    private ThemedTopKResponse(Builder<ACTION> builder) {
        this(
                builder.actionListId,
                builder.topKDecisions,
                builder.actionListMetadata,
                builder.featureStoreResponseContainer,
                builder.additionalProperties
        );
    }

    public String getActionListId() {
        return actionListId;
    }

    public Map<String, String> getActionListMetadata() {
        return actionListMetadata;
    }

    public static <ACTION> Builder<ACTION> builder(
            String actionListId,
            List<TopKDecision<ACTION>> topKDecisions) {
        return new Builder<>(actionListId, topKDecisions);
    }

    public static <ACTION> ThemedTopKResponse<ACTION> newResponse(
            String actionListId,
            List<TopKDecision<ACTION>> topKDecisions,
            Map<String, String> actionListMetadata) {
        return new ThemedTopKResponse<>(
                Objects.requireNonNull(actionListId, "actionListId cannot be null"),
                Objects.requireNonNull(topKDecisions, "topKDecisions cannot be null"),
                actionListMetadata != null ? actionListMetadata : Collections.emptyMap(),
                FeatureStoreResponseContainer.empty(),
                Collections.emptyMap()
        );
    }

    public static <ACTION> ThemedTopKResponse<ACTION> newResponse(
            String actionListId,
            List<TopKDecision<ACTION>> topKDecisions,
            Map<String, String> actionListMetadata,
            FeatureStoreResponseContainer featureStoreResponseContainer) {
        return new ThemedTopKResponse<>(
                Objects.requireNonNull(actionListId, "actionListId cannot be null"),
                Objects.requireNonNull(topKDecisions, "topKDecisions cannot be null"),
                actionListMetadata != null ? actionListMetadata : Collections.emptyMap(),
                Objects.requireNonNull(featureStoreResponseContainer, "featureStoreResponseContainer cannot be null"),
                Collections.emptyMap()
        );
    }

    public static <ACTION> ThemedTopKResponse<ACTION> newResponse(
            String actionListId,
            List<TopKDecision<ACTION>> topKDecisions,
            Map<String, String> actionListMetadata,
            Map<String, Object> additionalProperties) {
        return new ThemedTopKResponse<>(
                Objects.requireNonNull(actionListId, "actionListId cannot be null"),
                Objects.requireNonNull(topKDecisions, "topKDecisions cannot be null"),
                actionListMetadata != null ? actionListMetadata : Collections.emptyMap(),
                FeatureStoreResponseContainer.empty(),
                additionalProperties != null ? additionalProperties : Collections.emptyMap()
        );
    }

    public static <ACTION> ThemedTopKResponse<ACTION> newResponse(
            String actionListId,
            List<TopKDecision<ACTION>> topKDecisions,
            Map<String, String> actionListMetadata,
            FeatureStoreResponseContainer featureStoreResponseContainer,
            Map<String, Object> additionalProperties) {
        return new ThemedTopKResponse<>(
                Objects.requireNonNull(actionListId, "actionListId cannot be null"),
                Objects.requireNonNull(topKDecisions, "topKDecisions cannot be null"),
                actionListMetadata != null ? actionListMetadata : Collections.emptyMap(),
                Objects.requireNonNull(featureStoreResponseContainer, "featureStoreResponseContainer cannot be null"),
                additionalProperties != null ? additionalProperties : Collections.emptyMap()
        );
    }

    public static class Builder<ACTION> {
        private final String actionListId;
        private final List<TopKDecision<ACTION>> topKDecisions;
        private Map<String, String> actionListMetadata = Collections.emptyMap();
        private FeatureStoreResponseContainer featureStoreResponseContainer = FeatureStoreResponseContainer.empty();
        private Map<String, Object> additionalProperties = Collections.emptyMap();

        private Builder(String actionListId, List<TopKDecision<ACTION>> topKDecisions) {
            this.actionListId = Objects.requireNonNull(actionListId, "actionListId cannot be null");
            this.topKDecisions = Objects.requireNonNull(topKDecisions, "topKDecisions cannot be null");
        }

        public Builder<ACTION> withActionListMetadata(Map<String, String> actionListMetadata) {
            this.actionListMetadata = actionListMetadata;
            return this;
        }

        public Builder<ACTION> withFeatureStoreResponseContainer(FeatureStoreResponseContainer featureStoreResponseContainer) {
            this.featureStoreResponseContainer = Objects.requireNonNull(featureStoreResponseContainer, "featureStoreResponseContainer cannot be null");
            return this;
        }

        public Builder<ACTION> withAdditionalProperties(Map<String, Object> additionalProperties) {
            this.additionalProperties = additionalProperties;
            return this;
        }

        public ThemedTopKResponse<ACTION> build() {
            return new ThemedTopKResponse<>(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        if (!super.equals(o)) return false;

        ThemedTopKResponse<?> that = (ThemedTopKResponse<?>) o;

        return Objects.equals(actionListId, that.actionListId) &&
                Objects.equals(actionListMetadata, that.actionListMetadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), actionListId, actionListMetadata);
    }

    @Override
    public String toString() {
        return "ThemedTopKResponse{" +
                "actionListId='" + actionListId + '\'' +
                ", actionListMetadata=" + actionListMetadata +
                ", topKDecisions=" + decisions() +
                ", additionalProperties=" + additionalProperties() +
                '}';
    }
}