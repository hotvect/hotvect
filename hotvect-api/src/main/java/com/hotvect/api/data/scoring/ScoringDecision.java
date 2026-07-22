package com.hotvect.api.data.scoring;

import com.hotvect.api.data.Decision;
import com.google.common.collect.ImmutableMap;

import java.util.Map;
import java.util.Objects;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * A scoring decision containing the score for an action.
 * Used by bulk scoring algorithms.
 *
 * @param <ACTION> the type of action being scored
 */
public record ScoringDecision<ACTION>(
        String actionId,
        ACTION action,
        Double score,
        Map<String, Object> additionalProperties
) implements Decision<ACTION> {

    public ScoringDecision {
        checkArgument(actionId == null || !actionId.isBlank(), "actionId cannot be blank");
        Objects.requireNonNull(additionalProperties, "additionalProperties cannot be null");
    }

    /**
     * Backwards-compatible constructor with 2 parameters.
     * Delegates to canonical constructor with empty additionalProperties.
     *
     * @deprecated Use {@link #ScoringDecision(String, Object, Double, Map)} with a stable action id.
     */
    @Deprecated(forRemoval = true)
    public ScoringDecision(ACTION action, Double score) {
        this(null, action, score, ImmutableMap.of());
    }

    /**
     * Backwards-compatible constructor without action id.
     *
     * @deprecated Use {@link #ScoringDecision(String, Object, Double, Map)} with a stable action id.
     */
    @Deprecated(forRemoval = true)
    public ScoringDecision(ACTION action, Double score, Map<String, Object> additionalProperties) {
        this(null, action, score, additionalProperties);
    }

    @Override
    public Double probability() {
        return null; // Not used in scoring decisions
    }

    /**
     * Creates a ScoringDecision with the given action and score.
     *
     * @param action the action
     * @param score the score
     * @param <ACTION> the action type
     * @return a new ScoringDecision
     * @deprecated Use {@link #of(String, Object, double)} with a stable action id.
     */
    @Deprecated(forRemoval = true)
    public static <ACTION> ScoringDecision<ACTION> of(ACTION action, double score) {
        return new ScoringDecision<>(null, action, Double.valueOf(score), ImmutableMap.of());
    }

    /**
     * Creates a ScoringDecision with the given action id, action, and score.
     *
     * @param actionId the stable action id
     * @param action the action
     * @param score the score
     * @param <ACTION> the action type
     * @return a new ScoringDecision
     */
    public static <ACTION> ScoringDecision<ACTION> of(String actionId, ACTION action, double score) {
        return new ScoringDecision<>(actionId, action, Double.valueOf(score), ImmutableMap.of());
    }

    /**
     * Creates a ScoringDecision with the given action, score, and additional properties.
     *
     * @param action the action
     * @param score the score
     * @param additionalProperties additional metadata to preserve through the scoring pipeline
     * @param <ACTION> the action type
     * @return a new ScoringDecision
     * @deprecated Use {@link #of(String, Object, double, Map)} with a stable action id.
     */
    @Deprecated(forRemoval = true)
    public static <ACTION> ScoringDecision<ACTION> of(ACTION action, double score, Map<String, Object> additionalProperties) {
        return new ScoringDecision<>(null, action, Double.valueOf(score), additionalProperties);
    }

    /**
     * Creates a ScoringDecision with the given action id, action, score, and additional properties.
     *
     * @param actionId the stable action id
     * @param action the action
     * @param score the score
     * @param additionalProperties additional metadata to preserve through the scoring pipeline
     * @param <ACTION> the action type
     * @return a new ScoringDecision
     */
    public static <ACTION> ScoringDecision<ACTION> of(String actionId, ACTION action, double score, Map<String, Object> additionalProperties) {
        return new ScoringDecision<>(actionId, action, Double.valueOf(score), additionalProperties);
    }
}
