package com.hotvect.api.data;

/**
 * An enum indicating whether a {@link HashedValue} value is categorical or numerical
 */
public enum HashedValueType implements ValueType {
    CATEGORICAL(false, int.class), NUMERICAL(true, int.class);

    private final boolean hasNumericValues;
    private final Class<?> javaType;

    HashedValueType(boolean hasNumericValues, Class<?> javaType) {
        this.hasNumericValues = hasNumericValues;
        this.javaType = javaType;
    }

    @Override
    public boolean hasNumericValues() {
        return this.hasNumericValues;
    }

    @Override
    public Class<?> getJavaType() {
        return this.javaType;
    }
}
