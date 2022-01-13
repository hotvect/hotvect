package com.hotvect.core;

import com.hotvect.api.data.RawNamespace;
import com.hotvect.api.data.RawValueType;

public enum TestRawNamespace implements RawNamespace {
    single_categorical_1(RawValueType.SINGLE_CATEGORICAL),
    categoricals_1(RawValueType.CATEGORICALS),
    single_numerical_1(RawValueType.SINGLE_NUMERICAL),
    categorical_id_to_numericals_1(RawValueType.CATEGORICALS_TO_NUMERICALS),
    single_string_1(RawValueType.SINGLE_STRING),
    strings_1(RawValueType.STRINGS),
    string_to_numericals_1(RawValueType.STRINGS_TO_NUMERICALS);

    private final RawValueType valueType;

    TestRawNamespace(RawValueType valueType) {
        this.valueType = valueType;
    }

    @Override
    public RawValueType getValueType() {
        return this.valueType;
    }
}
