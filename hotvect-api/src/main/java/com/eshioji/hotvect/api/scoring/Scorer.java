package com.eshioji.hotvect.api.scoring;

import java.util.function.ToDoubleFunction;

/**
 * Interface for classes that scores an input
 */
public interface Scorer<R> extends ToDoubleFunction<R> {

    /**
     * Score the given record
     * @param record the record to be scored
     * @return the score
     */
    @Override
    double applyAsDouble(R record);
}
