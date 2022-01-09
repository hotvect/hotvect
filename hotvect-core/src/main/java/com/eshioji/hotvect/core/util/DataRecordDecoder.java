package com.eshioji.hotvect.core.util;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.RawNamespace;
import com.eshioji.hotvect.api.data.RawValue;

import java.util.function.Function;

/**
 * Interface for decoders that deserialize {@link String} into {@link DataRecord}
 * @param <R>
 */
@Deprecated
public interface DataRecordDecoder<R extends Enum<R> & RawNamespace> extends Function<String, DataRecord<R, RawValue>> {
    /**
     * Deserialize the input
     * @param input serialized {@link DataRecord}
     * @return deserialized {@link DataRecord}
     */
    @Override
    DataRecord<R, RawValue> apply(String input);
}
