package com.hotvect.catboost;

import com.hotvect.api.data.HashedValueType;
import com.hotvect.api.data.ValueType;

public enum CatBoostFeatureType implements ValueType {
    CATEGORICAL,
    NUMERICAL,
    GROUP_ID,
    TEXT,
    EMBEDDING;

    public HashedValueType toHashedValueType(){
        if (this == CATEGORICAL || this == TEXT || this == GROUP_ID){
            return HashedValueType.CATEGORICAL;
        } else {
            return HashedValueType.NUMERICAL;
        }

    }

    @Override
    public boolean hasNumericValues() {
        return toHashedValueType().hasNumericValues();
    }
}
