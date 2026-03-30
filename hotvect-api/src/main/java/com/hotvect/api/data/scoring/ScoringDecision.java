package com.hotvect.api.data.scoring;

import com.hotvect.api.data.Decision;
import com.google.common.collect.ImmutableMap;

import java.util.Map;
import java.util.Objects;

/**
 * A scoring decision containing the score for an action.
 * Used by bulk scoring algorithms.
 *
 * @param <ACTION> the type of action being scored
 */
public record ScoringDecision<ACTION>(
        ACTION action,
        Double score,
        Map<String, Object> additionalProperties
) implements Decision<ACTION> {

    public ScoringDecision {
        Objects.requireNonNull(additionalProperties, "additionalProperties cannot be null");
    }

    /**
     * Backwards-compatible constructor with 2 parameters.
     * Delegates to canonical constructor with empty additionalProperties.
     */
    public ScoringDecision(ACTION action, Double score) {
        this(action, score, ImmutableMap.of());
    }

    @Override
    public String actionId() {
        return null; // ScoringDecision doesn't have actionId
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
     */
    public static <ACTION> ScoringDecision<ACTION> of(ACTION action, double score) {
        return new ScoringDecision<>(action, Double.valueOf(score), ImmutableMap.of());
    }

    /**
     * Creates a ScoringDecision with the given action, score, and additional properties.
     *
     * @param action the action
     * @param score the score
     * @param additionalProperties additional metadata to preserve through the scoring pipeline
     * @param <ACTION> the action type
     * @return a new ScoringDecision
     */
    public static <ACTION> ScoringDecision<ACTION> of(ACTION action, double score, Map<String, Object> additionalProperties) {
        return new ScoringDecision<>(action, Double.valueOf(score), additionalProperties);
    }
}
