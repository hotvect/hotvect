package com.eshioji.hotvect.core.util;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.raw.RawNamespace;
import com.eshioji.hotvect.api.data.raw.RawValue;

import java.util.function.Function;

/**
 * Interface for decoders that deserialize {@link String} into {@link DataRecord}
 * @param <R>
 */
public interface DataRecordDecoder<R extends Enum<R> & RawNamespace> extends Function<String, DataRecord<R, RawValue>> {
    /**
     * Deserialize the input
     * @param input serialized {@link DataRecord}
     * @return deserialized {@link DataRecord}
     */
    @Override
    DataRecord<R, RawValue> apply(String input);
}
