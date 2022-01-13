package com.hotvect.api.data;

/**
 * An enum indicating the type of a {@link RawValue}
 */
public enum RawValueType implements ValueType {
    /**
     * A single string
     */
    SINGLE_STRING(false),

    /**
     * Multiple strings
     */
    STRINGS(false),

    /**
     * A map from strings to numerical values
     */
    STRINGS_TO_NUMERICALS(true),

    /**
     * A single numerical value
     */
    SINGLE_NUMERICAL(true),

    /*
     * Equivalents in ProcessedData
     */
    SINGLE_CATEGORICAL(false),
    CATEGORICALS(false),
    CATEGORICALS_TO_NUMERICALS(true);

    private final boolean hasNumericValues;

    RawValueType(boolean hasNumericValues) {
        this.hasNumericValues = hasNumericValues;
    }

    @Override
    public boolean hasNumericValues() {
        return this.hasNumericValues;
    }
}
