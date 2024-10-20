package com.hotvect.core.combine;

import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.data.HashedValue;
import com.hotvect.api.data.SparseVector;
import com.hotvect.api.data.common.NamespacedRecord;

import java.util.function.Function;

/**
 * Interface for classes that constructs a feature vector from hashed records
 */
public interface Combiner<FEATURE extends FeatureNamespace> extends Function<NamespacedRecord<FEATURE, HashedValue>, SparseVector> {

    /**
     *
     * @param toCombine hashed {@link com.hotvect.api.data.DataRecord} to construct feature vector with
     * @return the constructed feature vector
     */
    @Override
    SparseVector apply(NamespacedRecord<FEATURE, HashedValue> toCombine);
}
