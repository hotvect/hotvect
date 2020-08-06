package com.eshioji.hotvect.core.transform;


import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.DataValue;
import com.eshioji.hotvect.api.data.Namespace;

import java.util.function.Function;

/**
 * Interface that defines a transformation for a single output value
 * @param <IN>
 * @param <V>
 */
public interface Transformation<IN extends Enum<IN> & Namespace, V extends DataValue>
        extends Function<DataRecord<IN, V>, V> {

    /**
     * Generates a new value using the specified record
     * @param toTransform the input record
     * @return a single value
     */
    @Override
    V apply(DataRecord<IN, V> toTransform);
}
