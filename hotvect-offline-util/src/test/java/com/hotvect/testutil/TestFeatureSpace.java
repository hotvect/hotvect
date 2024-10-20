package com.hotvect.testutil;

import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.data.HashedValueType;
import com.hotvect.api.data.ValueType;

public enum TestFeatureSpace implements FeatureNamespace {
    feature1(HashedValueType.CATEGORICAL),
    feature2(HashedValueType.CATEGORICAL),
    feature3(HashedValueType.NUMERICAL)
    ;

    private final HashedValueType hashedValueType;

    TestFeatureSpace(HashedValueType hashedValueType) {
        this.hashedValueType = hashedValueType;
    }

    @Override
    public ValueType getFeatureValueType() {
        return this.hashedValueType;
    }
}
