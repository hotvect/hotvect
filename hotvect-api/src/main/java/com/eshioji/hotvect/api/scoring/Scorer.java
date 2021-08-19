package com.eshioji.hotvect.api.scoring;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.raw.RawNamespace;
import com.eshioji.hotvect.api.data.raw.RawValue;

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
