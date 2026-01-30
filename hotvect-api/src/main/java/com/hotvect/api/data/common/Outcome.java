package com.hotvect.api.data.common;

import com.hotvect.api.data.Decision;

/**
 * Base interface for all outcome types that combine a decision with an outcome result.
 *
 * @param <OUTCOME> The type of the outcome/result
 * @param <ACTION> The type of the action in the decision
 */
public interface Outcome<OUTCOME, ACTION> {
    /**
     * Returns the decision associated with this outcome.
     *
     * @return the decision
     */
    Decision<ACTION> decision();

    /**
     * Returns the outcome/result associated with this decision.
     *
     * @return the outcome
     */
    OUTCOME outcome();
}