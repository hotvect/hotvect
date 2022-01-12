package com.eshioji.hotvect.core.transform.regression;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.Namespace;
import com.eshioji.hotvect.api.data.RawValue;

import java.util.function.Function;

/**
 * Interface for classes that transform a {@link DataRecord}
 * @param <RECORD> the input record type
 * @param <FEATURE> the output key of the output {@link DataRecord}
 */
public interface ScoringTransformer<RECORD, FEATURE extends Enum<FEATURE> & Namespace> extends Function<RECORD, DataRecord<FEATURE, RawValue>> {

    /**
     * Transform the given record
     * @param toTransform record to transform
     * @return transformed record
     */
    @Override
    DataRecord<FEATURE, RawValue> apply(RECORD toTransform);
}
