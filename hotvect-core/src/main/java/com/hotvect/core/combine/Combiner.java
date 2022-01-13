package com.hotvect.core.combine;

import com.hotvect.api.data.DataRecord;
import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.data.SparseVector;
import com.hotvect.api.data.HashedValue;

import java.util.function.Function;

/**
 * Interface for classes that constructs a feature vector from hashed records
 */
public interface Combiner<FEATURE extends Enum<FEATURE> & FeatureNamespace>
        extends Function<DataRecord<FEATURE, HashedValue>, SparseVector> {

    /**
     *
     * @param toCombine hashed {@link DataRecord} to construct feature vector with
     * @return the constructed feature vector
     */
    @Override
    SparseVector apply(DataRecord<FEATURE, HashedValue> toCombine);
}
