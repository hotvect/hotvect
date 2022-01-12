package com.eshioji.hotvect.api.algorithms;

import java.util.function.ToDoubleFunction;

/**
 * Interface for classes that scores an input
 */
public interface Scorer<RECORD> extends ToDoubleFunction<RECORD>, Algorithm {

    /**
     * Score the given record
     * @param record the record to be scored
     * @return the score
     */
    @Override
    double applyAsDouble(RECORD record);
}
