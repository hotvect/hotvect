package com.hotvect.api.data.ranking;

import com.google.common.collect.ImmutableMap;
import com.hotvect.api.data.Decision;

import java.util.Map;
import java.util.Objects;

/**
 * A record representing a ranking decision, which includes an action index, a probability, a score,
 * an ACTION, and additional properties.
 *
 * @param <ACTION> the type parameter for the associated action
 */
public record RankingDecision<ACTION>(
        String actionId,
        int actionIndex,
        Double score,
        ACTION action,
        Double probability,
        Map<String, Object> additionalProperties
) implements Decision<ACTION> {

    public RankingDecision {
        Objects.requireNonNull(additionalProperties, "additionalProperties cannot be null");
    }

    public int getActionIndex() {
        return actionIndex;
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
    // Builder pattern for convenience.
    // -------------------------------------------------------------------------
    public static <ACTION> RankingDecisionBuilder<ACTION> builder(String actionId, int actionIndex, ACTION action) {
        return new RankingDecisionBuilder<>(actionId, actionIndex, action);
    }

    public static <ACTION> RankingDecisionBuilder<ACTION> builder(int actionIndex, ACTION action) {
        return new RankingDecisionBuilder<>(null, actionIndex, action);
    }

    public static class RankingDecisionBuilder<ACTION> {
        private final String actionId;
        private final int actionIdx;
        private final ACTION action;
        private Double score;
        private Double probability;
        private Map<String, Object> additionalProperties = ImmutableMap.of();

        private RankingDecisionBuilder(String actionId, int actionIdx, ACTION action) {
            this.actionId = actionId;
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

        public RankingDecisionBuilder<ACTION> withAdditionalProperties(
                Map<String, Object> additionalProperties
        ) {
            this.additionalProperties = additionalProperties;
            return this;
        }

        public RankingDecision<ACTION> build() {
            return new RankingDecision<>(actionId, actionIdx, score, action, probability, additionalProperties);
        }
    }

    // ---------------------------------------------------------------------
    // Binary–compatibility constructors (will be removed in a future release)
    // ---------------------------------------------------------------------

    @Deprecated(forRemoval = true)
    public RankingDecision(int actionIndex, ACTION action) {
        this(null, actionIndex, null, action, null, ImmutableMap.of());
    }

    @Deprecated(forRemoval = true)
    public RankingDecision(int actionIndex, Double score, ACTION action) {
        this(null, actionIndex, score, action, null, ImmutableMap.of());
    }

    @Deprecated(forRemoval = true)
    public RankingDecision(int actionIndex, ACTION action, Double probability) {
        this(null, actionIndex, null, action, probability, ImmutableMap.of());
    }

    @Deprecated(forRemoval = true)
    public RankingDecision(int actionIndex, Double score, ACTION action, Double probability) {
        this(null, actionIndex, score, action, probability, ImmutableMap.of());
    }
}
