package com.hotvect.api.data.ranking;

import com.google.common.collect.ImmutableMap;

import java.util.Map;
import java.util.Objects;

public class RankingDecision<ACTION> {
    private final int actionIndex;

    private final Double probability;
    private final Double score;
    private final ACTION action;

    private final Map<String, Object> additionalProperties;

    @Deprecated
    public RankingDecision(int actionIndex, ACTION action) {
        this.actionIndex = actionIndex;
        this.score = null;
        this.probability = null;
        this.action = action;
        this.additionalProperties = ImmutableMap.of();
    }

    @Deprecated
    public RankingDecision(int actionIndex, Double score, ACTION action) {
        this.actionIndex = actionIndex;
        this.score = score;
        this.action = action;
        this.probability = null;
        this.additionalProperties = ImmutableMap.of();
    }


    @Deprecated
    public RankingDecision(int actionIndex, Double score, ACTION action, Double probability) {
        this.actionIndex = actionIndex;
        this.score = score;
        this.action = action;
        this.probability = probability;
        this.additionalProperties = ImmutableMap.of();
    }

    @Deprecated
    public RankingDecision(int actionIndex, ACTION action, Double probability) {
        this.actionIndex = actionIndex;
        this.score = null;
        this.action = action;
        this.probability = probability;
        this.additionalProperties = ImmutableMap.of();
    }

    private RankingDecision(int actionIndex, Double score, ACTION action, Double probability, Map<String, Object> additionalProperties) {
        this.actionIndex = actionIndex;
        this.score = score;
        this.action = action;
        this.probability = probability;
        this.additionalProperties = additionalProperties;
    }


    public Map<String, Object> getAdditionalProperties() {
        return additionalProperties;
    }

    public int getActionIndex() {
        return actionIndex;
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

    @Override
    public String toString() {
        return "RankingDecision{" +
                "actionIndex=" + actionIndex +
                ", probability=" + probability +
                ", score=" + score +
                ", action=" + action +
                ", additionalProperties=" + additionalProperties +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RankingDecision<?> that = (RankingDecision<?>) o;
        return actionIndex == that.actionIndex && isSameDouble(that.score, score)
                && Objects.equals(action, that.action) && isSameDouble(that.probability, probability);
    }

    private boolean isSameDouble(Double d1, Double d2) {
        if (d1 == null || d2 == null) {
            return d1 == d2;
        }
        return Double.compare(d1, d2) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(actionIndex, score, action, probability);
    }

    public static <ACTION> RankingDecisionBuilder<ACTION> builder(int actionIndex, ACTION action){
        return new RankingDecisionBuilder<>(actionIndex, action);
    }

    public static class RankingDecisionBuilder<ACTION> {
        private final int actionIdx;
        private final ACTION action;
        private Double score;
        private Double probability;

        private Map<String, Object> additionalProperties = ImmutableMap.of();

        private RankingDecisionBuilder(int actionIdx, ACTION action){
            this.actionIdx = actionIdx;
            this.action = action;
        }


        public RankingDecisionBuilder<ACTION> withScore(Double score) {
            this.score = score;
            return this;
        }

        public RankingDecisionBuilder<ACTION> withProbability(Double probability) {
            this.probability = probability;
            return this;
        }

        public RankingDecisionBuilder<ACTION> withAdditionalProperties(Map<String, Object>additionalProperties) {
            this.additionalProperties = additionalProperties;
            return this;
        }

        public RankingDecision<ACTION> build() {
            return new RankingDecision<>(actionIdx, score, action, probability, additionalProperties);
        }
    }
}
