package com.hotvect.api.data;

import java.util.Map;

/**
 * An enum indicating the type of a {@link RawValue}
 */
public enum RawValueType implements ValueType {
    /**
     * A single string
     */
    SINGLE_STRING(false, String.class),

    /**
     * Multiple strings
     */
    STRINGS(false, String[].class),

    /**
     * A map from strings to numerical values
     */
    STRINGS_TO_NUMERICALS(true, Map.class),

    /**
     * A single numerical value
     */
    SINGLE_NUMERICAL(true, double.class),

    /*
     * Equivalents in ProcessedData
     */
    SINGLE_CATEGORICAL(false, int.class),
    CATEGORICALS(false, int[].class),
    @Deprecated
    CATEGORICALS_TO_NUMERICALS(true, Map.class),
    SPARSE_VECTOR(true, SparseVector.class),
    DENSE_VECTOR(true, double[].class),
    ;

    private final boolean hasNumericValues;
    private final Class<?> javaType;

    RawValueType(boolean hasNumericValues, Class<?> javaType) {
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
