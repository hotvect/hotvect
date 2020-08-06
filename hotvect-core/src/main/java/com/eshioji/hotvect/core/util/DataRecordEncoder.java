package com.eshioji.hotvect.core.util;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.Namespace;
import com.eshioji.hotvect.api.data.raw.RawValue;

import java.util.function.Function;

/**
 * Interface for classes that encode {@link DataRecord} into a String
 * @param <K>
 */
public interface DataRecordEncoder<K extends Enum<K> & Namespace> extends Function<DataRecord<K, RawValue>, String> {
    /**
     * @param toEncode {@link DataRecord} to be encoded
     * @return the encoded {@link String}
     */
    @Override
    String apply(DataRecord<K, RawValue> toEncode);
}
