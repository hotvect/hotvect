package com.hotvect.catboost;

import com.hotvect.api.data.HashedValueType;
import com.hotvect.api.data.ValueType;

public enum CatBoostFeatureType implements ValueType {
    CATEGORICAL(String.class),
    NUMERICAL(double.class),
    GROUP_ID(String.class),
    TEXT(String[].class),
    EMBEDDING(float[].class);

    CatBoostFeatureType(Class<?> javaType) {
        this.javaType = javaType;
    }

    public HashedValueType toHashedValueType(){
        if (this == CATEGORICAL || this == TEXT || this == GROUP_ID){
            return HashedValueType.CATEGORICAL;
        } else {
            return HashedValueType.NUMERICAL;
        }

    }
    private final Class<?> javaType;

    @Override
    public boolean hasNumericValues() {
        return toHashedValueType().hasNumericValues();
    }

    @Override
    public Class<?> getJavaType() {
        return javaType;
    }

    /**
     * Maps a plain Java type to the corresponding CatBoostFeatureType.
     * <p>
     * Rules:
     * • String                 → CATEGORICAL<br>
     * • Integer / int          → CATEGORICAL<br>
     * • Double / double        → NUMERICAL<br>
     * • Float  / float         → NUMERICAL<br>
     * • String[]               → TEXT<br>
     * • float[]                → EMBEDDING<br>
     * • double[]               → not supported – CatBoost accepts only float[]
     */
    public static ValueType fromJavaType(Class<?> javaType) {
        // single-value types ----------------------------------------------------
        if (javaType == String.class  || javaType == Boolean.class || javaType == boolean.class) {
            return CATEGORICAL;
        }
        if (javaType == Integer.class || javaType == int.class || javaType == Long.class || javaType == long.class) {
            return CATEGORICAL;
        }
        if (javaType == Double.class  || javaType == double.class
                || javaType == Float.class || javaType == float.class) {
            return NUMERICAL;
        }

        // array types -----------------------------------------------------------
        if (javaType.isArray()) {
            Class<?> component = javaType.getComponentType();

            if (component == String.class) {
                return TEXT;
            }
            if (component == float.class || component == Float.class) {
                return EMBEDDING;
            }
            if (component == double.class || component == Double.class) {
                throw new IllegalArgumentException(
                        "CatBoost supports embeddings only as float[], not as double[]");
            }
        }

        // If we reach here, the type is not supported as a catboost feature
        return null;
    }

}
