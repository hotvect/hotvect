package com.eshioji.hotvect.core.util;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.Namespace;
import com.eshioji.hotvect.api.data.raw.RawValue;

import java.util.function.Function;

/**
 * TODO
 */
public interface DataRecordEncoder<V> extends Function<V, String> {
    /**
     * @param toEncode {@link DataRecord} to be encoded
     * @return the encoded {@link String}
     */
    @Override
    String apply(V toEncode);
}
