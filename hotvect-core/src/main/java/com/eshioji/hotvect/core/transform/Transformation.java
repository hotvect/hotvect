package com.eshioji.hotvect.core.transform;


import com.eshioji.hotvect.api.data.raw.RawValue;

import java.util.function.Function;

/**
 * Interface that defines a transformation for a single output value
 * @param <IN>
 * @param <V>
 */
public interface Transformation<R>
        extends Function<R, RawValue> {

    /**
     * Generates a new value using the specified record
     * @param toTransform the input record
     * @return a single value
     */
    @Override
    RawValue apply(R toTransform);
}
