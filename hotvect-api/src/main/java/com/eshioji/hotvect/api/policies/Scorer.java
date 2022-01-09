package com.eshioji.hotvect.api.policies;

import java.util.function.ToDoubleFunction;

/**
 * Interface for classes that scores an input
 */
public interface Scorer<RECORD> extends ToDoubleFunction<RECORD> {

    /**
     * Score the given record
     * @param record the record to be scored
     * @return the score
     */
    @Override
    double applyAsDouble(RECORD record);
}
