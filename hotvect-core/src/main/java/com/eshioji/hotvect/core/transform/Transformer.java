package com.eshioji.hotvect.core.transform;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.Namespace;
import com.eshioji.hotvect.api.data.raw.RawValue;

import java.util.function.Function;

/**
 * Interface for classes that transform a {@link DataRecord}
 * @param <R> the input record type
 * @param <OUT> the output key of the output {@link DataRecord}
 */
public interface Transformer<R, OUT extends Enum<OUT> & Namespace>
        extends Function<R, DataRecord<OUT, RawValue>> {

    /**
     * Transform the given record
     * @param toTransform record to transform
     * @return transformed record
     */
    @Override
    DataRecord<OUT, RawValue> apply(R toTransform);
}
