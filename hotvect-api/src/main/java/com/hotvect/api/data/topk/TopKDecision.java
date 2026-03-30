package com.hotvect.api.data.topk;

import com.google.common.collect.ImmutableMap;
import com.hotvect.api.data.Decision;

import java.util.Map;
import java.util.Objects;

/**
 * A record representing a TopK decision, including an actionId,
 * optional score and probability, the ACTION, and additional properties.
 *
 * @param <ACTION> the type associated with the decision
 */
public record TopKDecision<ACTION>(
        String actionId,
        Double score,
        ACTION action,
        Double probability,
        Map<String, Object> additionalProperties
) implements Decision<ACTION> {

    public TopKDecision {
        Objects.requireNonNull(additionalProperties, "additionalProperties cannot be null");
    }

    @Deprecated(forRemoval = true)
    public String getActionId() {
        return actionId;
    }

    @Deprecated(forRemoval = true)
    public Double getScore() {
        return score;
    }

    @Deprecated(forRemoval = true)
    public ACTION getAction() {
        return action;
    }

    @Deprecated(forRemoval = true)
    public Double getProbability() {
        return probability;
    }

    @Deprecated(forRemoval = true)
    public Map<String, Object> getAdditionalProperties() {
        return additionalProperties;
    }

    // -------------------------------------------------------------------------
    // Builder pattern
    // -------------------------------------------------------------------------
    public static <ACTION> TopKDecisionBuilder<ACTION> builder(String actionId, ACTION action) {
        return new TopKDecisionBuilder<>(actionId, action);
    }

    public static class TopKDecisionBuilder<ACTION> {
        private final String actionId;
        private final ACTION action;
        private Double score;
        private Double probability;
        private Map<String, Object> additionalProperties = ImmutableMap.of();

        private TopKDecisionBuilder(String actionId, ACTION action) {
            this.actionId = actionId;
            this.action = action;
        }

        public TopKDecisionBuilder<ACTION> withScore(Double score) {
            this.score = score;
            return this;
        }

        public TopKDecisionBuilder<ACTION> withProbability(Double probability) {
            this.probability = probability;
            return this;
        }

        public TopKDecisionBuilder<ACTION> withAdditionalProperties(Map<String, Object> additionalProperties) {
            this.additionalProperties = additionalProperties;
            return this;
        }

        public TopKDecision<ACTION> build() {
            return new TopKDecision<>(this.actionId, this.score, this.action, this.probability, this.additionalProperties);
        }
    }
}
