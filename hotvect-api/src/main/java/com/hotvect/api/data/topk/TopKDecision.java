package com.hotvect.api.data.topk;

import com.google.common.collect.ImmutableMap;

import java.util.Map;

public class TopKDecision<ACTION> {
    private final String actionId;

    private final Double probability;
    private final Double score;
    private final ACTION action;

    private final Map<String, Object> additionalProperties;

    private TopKDecision(String actionId, Double score, ACTION action, Double probability, Map<String, Object> additionalProperties) {
        this.actionId = actionId;
        this.score = score;
        this.action = action;
        this.probability = probability;
        this.additionalProperties = additionalProperties;
    }


    public Map<String, Object> getAdditionalProperties() {
        return additionalProperties;
    }

    public String getActionId() {
        return actionId;
    }

    public Double getScore() {
        return score;
    }

    public ACTION getAction() {
        return action;
    }

    public Double getProbability() {
        return probability;
    }


    private boolean isSameDouble(Double d1, Double d2) {
        if (d1 == null || d2 == null) {
            return d1 == d2;
        }
        return Double.compare(d1, d2) == 0;
    }

    public static <ACTION> TopKDecisionBuilder<ACTION> builder(String actionId, ACTION action){
        return new TopKDecisionBuilder<>(actionId, action);
    }


    public static class TopKDecisionBuilder<ACTION> {
        private final String actionId;
        private final ACTION action;
        private Double score;
        private Double probability;

        private Map<String, Object> additionalProperties = ImmutableMap.of();

        private TopKDecisionBuilder(String actionId, ACTION action){
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

        public TopKDecisionBuilder<ACTION> withAdditionalProperties(Map<String, Object>additionalProperties) {
            this.additionalProperties = additionalProperties;
            return this;
        }

        public TopKDecision<ACTION> build() {
            return new TopKDecision<>(actionId, score, action, probability, additionalProperties);
        }
    }
}
