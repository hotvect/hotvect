package com.eshioji.hotvect.api.data.hashed;

import com.eshioji.hotvect.api.data.SparseVector;

import java.util.Arrays;
import java.util.Objects;

import static com.google.common.base.Preconditions.*;

/**
 * Containers for feature values after hashing
 */
public class HashedValue {
    /**
     * If a feature is a scalar feature ( it has a single value within its namespace),
     * the value is stored at with feature name = 0
     */
    private static final int[] DEFAULT_NAME = {0};

    /**
     * If a feature is categorical, it gets the default value 1.0
     */
    private static final double[] DEFAULT_VALUE = {1.0};

    private final SparseVector values;
    private final HashedValueType valueType;

    private HashedValue(int[] names, double[] values, HashedValueType valueType) {
        checkArgument(names.length == values.length,
                "Feature index and values must have the same length");
        this.values = new SparseVector(names, values);
        this.valueType = valueType;
    }

    private HashedValue(int[] names) {
        checkNotNull(names);
        this.values = new SparseVector(names);
        this.valueType = HashedValueType.CATEGORICAL;
    }

    // Wrapping methods
    public static HashedValue singleCategorical(int i) {
        return new HashedValue(new int[]{i}, DEFAULT_VALUE, HashedValueType.CATEGORICAL);
    }

    public static HashedValue singleNumerical(double numerical) {
        return new HashedValue(DEFAULT_NAME, new double[]{numerical}, HashedValueType.NUMERICAL);
    }

    public static HashedValue categoricals(int[] names) {
        return new HashedValue(names);
    }

    public static HashedValue numericals(int[] names, double[] vals) {
        return new HashedValue(names, vals, HashedValueType.NUMERICAL);
    }

    // Getters
    public int[] getCategoricals() {
        return this.values.indices();
    }

    public int getSingleCategorical() {
        checkState(valueType == HashedValueType.CATEGORICAL && values.indices().length == 1,
                String.format("Tried to retrieve a single categorical value from type:%s length:%s",
                        valueType, values.indices().length));
        return values.indices()[0];
    }

    public double[] getNumericals() {
        return this.values.values();
    }

    public double getSingleNumerical() {
        checkState(valueType == HashedValueType.NUMERICAL &&
                        values.indices().length == 1 &&
                        values.indices() == DEFAULT_NAME,
                String.format("Tried to retrieve a single numerical value from value of type:%s indices:%s",
                        valueType, Arrays.toString(values.indices())));
        return values.values()[0];
    }

    public SparseVector getCategoricalsToNumericals() {
        return values;
    }

    public HashedValueType getValueType() {
        return valueType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HashedValue value = (HashedValue) o;
        return values.equals(value.values) &&
                valueType == value.valueType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(values, valueType);
    }

    @Override
    public String toString() {
        return "HashedValue{" +
                "values=" + values +
                ", valueType=" + valueType +
                '}';
    }
}
