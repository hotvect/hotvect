package com.hotvect.core.vectorize.ranking;

import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.data.HashedValueType;
import com.hotvect.api.data.ValueType;

public enum TestFeature implements FeatureNamespace {
    test_shared_feature(HashedValueType.CATEGORICAL),
    test_interaction_feature(HashedValueType.CATEGORICAL),
    test_action_feature(HashedValueType.CATEGORICAL),
    ;

    private final HashedValueType hashedValueType;

    TestFeature(HashedValueType hashedValueType) {
        this.hashedValueType = hashedValueType;
    }

    public HashedValueType getValueType() {
        return this.hashedValueType;
    }

    @Override
    public ValueType getFeatureValueType() {
        return this.hashedValueType;
    }
}
