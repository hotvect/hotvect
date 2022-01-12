package com.eshioji.hotvect.core.transform.regression;


import com.eshioji.hotvect.api.data.RawValue;

import java.util.function.Function;

/**
 * Interface that defines a transformation for a single output value
 * @param <RECORD>
 */
public interface RecordTransformation<RECORD> extends Function<RECORD, RawValue> {

    /**
     * Generates a new value using the specified record
     * @param toTransform the input record
     * @return a single value
     */
    @Override
    RawValue apply(RECORD toTransform);
}
