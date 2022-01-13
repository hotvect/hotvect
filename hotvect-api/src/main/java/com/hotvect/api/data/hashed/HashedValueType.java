package com.hotvect.api.data.hashed;

import com.hotvect.api.data.ValueType;

/**
 * An enum indicating whether a {@link HashedValue} value is categorical or numerical
 */
public enum HashedValueType implements ValueType {
    CATEGORICAL(false), NUMERICAL(true);

    private final boolean hasNumericValues;

    HashedValueType(boolean hasNumericValues) {
        this.hasNumericValues = hasNumericValues;
    }

    @Override
    public boolean hasNumericValues() {
        return this.hasNumericValues;
    }
}
