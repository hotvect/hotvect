package com.hotvect.api.data.scoring;

import com.hotvect.api.data.Decision;
import com.google.common.collect.ImmutableMap;

import java.util.Map;

/**
 * A scoring decision containing the score for an action.
 * Used by bulk scoring algorithms.
 *
 * @param <ACTION> the type of action being scored
 */
public record ScoringDecision<ACTION>(
        ACTION action,
        Double score
) implements Decision<ACTION> {

    @Override
    public String actionId() {
        return null; // ScoringDecision doesn't have actionId
    }

    @Override
    public Double probability() {
        return null; // Not used in scoring decisions
    }

    @Override
    public Map<String, Object> additionalProperties() {
        return ImmutableMap.of();
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
        return new ScoringDecision<>(action, Double.valueOf(score));
    }
}