package com.hotvect.vw.util;

import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.data.hashed.HashedValueType;

public enum TestFeatureNamespace implements FeatureNamespace {
    single_categorical_1(HashedValueType.CATEGORICAL),
    categoricals_1(HashedValueType.CATEGORICAL),
    single_numerical_1(HashedValueType.NUMERICAL),
    categorical_id_to_numericals_1(HashedValueType.NUMERICAL),
    single_string_1(HashedValueType.CATEGORICAL),
    strings_1(HashedValueType.CATEGORICAL),
    string_to_numericals_1(HashedValueType.NUMERICAL),
    parsed_1(HashedValueType.CATEGORICAL);

    private final HashedValueType hashedValueType;

    TestFeatureNamespace(HashedValueType hashedValueType) {
        this.hashedValueType = hashedValueType;
    }

    @Override
    public HashedValueType getValueType() {
        return this.hashedValueType;
    }
}
