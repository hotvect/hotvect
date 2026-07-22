package com.hotvect.api.data.ranking;

import com.google.common.collect.ImmutableMap;
import com.hotvect.api.data.Decision;

import java.util.Map;
import java.util.Objects;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * A ranking decision for one action.
 *
 * <p>{@link #actionIndex()} is a request-local ordinal into the original
 * {@link RankingRequest#actions()} list, retained for efficient alignment with scores, outcomes,
 * rewards, and transformed action arrays.</p>
 *
 * @param actionId stable action id for the decided candidate
 * @param actionIndex request-local ordinal
 * @param score optional decision score
 * @param action decided candidate payload
 * @param probability optional decision probability
 * @param additionalProperties optional additional decision metadata
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
        if (actionId == null) {
            actionId = String.valueOf(actionIndex);
        }
        checkArgument(!actionId.isBlank(), "actionId cannot be null or blank");
        Objects.requireNonNull(additionalProperties, "additionalProperties cannot be null");
    }

    /**
     * @deprecated Use {@link #actionIndex()}.
     */
    @Deprecated(forRemoval = true)
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
    /**
     * @deprecated Use {@link #builder(String, Object)}. Action ids synthesized from the deprecated
     * action-index-only API are kept only for source/binary compatibility during the v10 migration.
     */
    @Deprecated(forRemoval = true)
    public static <ACTION> RankingDecisionBuilder<ACTION> builder(int actionIndex, ACTION action) {
        return builder(String.valueOf(actionIndex), actionIndex, action);
    }

    /**
     * Builds a ranking decision identified by stable action id when no request-local action index is
     * available.
     */
    public static <ACTION> RankingDecisionBuilder<ACTION> builder(String actionId, ACTION action) {
        return builder(actionId, -1, action);
    }

    /**
     * Builds a ranking decision identified by stable action id with its request-local action index.
     */
    public static <ACTION> RankingDecisionBuilder<ACTION> builder(String actionId, int actionIndex, ACTION action) {
        return new RankingDecisionBuilder<>(actionId, actionIndex, action);
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
    // Binary-compatibility constructors (will be removed in a future release)
    // TODO: Remove these constructors after legacy algorithms migrate.
    // ---------------------------------------------------------------------

    @Deprecated(forRemoval = true)
    public RankingDecision(int actionIndex, ACTION action) {
        this(String.valueOf(actionIndex), actionIndex, null, action, null, ImmutableMap.of());
    }

    @Deprecated(forRemoval = true)
    public RankingDecision(int actionIndex, Double score, ACTION action) {
        this(String.valueOf(actionIndex), actionIndex, score, action, null, ImmutableMap.of());
    }

    @Deprecated(forRemoval = true)
    public RankingDecision(int actionIndex, ACTION action, Double probability) {
        this(String.valueOf(actionIndex), actionIndex, null, action, probability, ImmutableMap.of());
    }

    @Deprecated(forRemoval = true)
    public RankingDecision(int actionIndex, Double score, ACTION action, Double probability) {
        this(String.valueOf(actionIndex), actionIndex, score, action, probability, ImmutableMap.of());
    }
}
