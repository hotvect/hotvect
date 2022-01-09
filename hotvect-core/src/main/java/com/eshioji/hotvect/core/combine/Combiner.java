package com.eshioji.hotvect.core.combine;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.api.data.SparseVector;
import com.eshioji.hotvect.api.data.HashedValue;

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
