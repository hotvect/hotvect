package com.eshioji.hotvect.api.scoring;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.raw.RawNamespace;
import com.eshioji.hotvect.api.data.raw.RawValue;

import java.util.function.ToDoubleFunction;

/**
 * Interface for classes that score a raw {@link DataRecord}
 * @param <I> The {@link RawNamespace} on which this {@link Scorer} should operate.
 */
public interface Scorer<I extends Enum<I> & RawNamespace> extends ToDoubleFunction<DataRecord<I, RawValue>> {

    /**
     * Score the given record
     * @param record the record to be scored
     * @return the score
     */
    @Override
    double applyAsDouble(DataRecord<I, RawValue> record);
}
