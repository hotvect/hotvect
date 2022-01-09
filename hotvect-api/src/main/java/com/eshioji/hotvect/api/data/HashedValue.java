package com.eshioji.hotvect.api.data;

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

    private final SparseVector values;
    private final HashedValueType valueType;

    private HashedValue(int[] numericalIndices, double[] numericalValues) {
        this.values = new SparseVector(numericalIndices, numericalValues);
        this.valueType = HashedValueType.NUMERICAL;
    }

    private HashedValue(int[] categoricalIndices) {
        this.values = new SparseVector(categoricalIndices);
        this.valueType = HashedValueType.CATEGORICAL;
    }

    // Wrapping methods
    public static HashedValue singleCategorical(int i) {
        return new HashedValue(new int[]{i});
    }

    public static HashedValue singleNumerical(double numerical) {
        return new HashedValue(DEFAULT_NAME, new double[]{numerical});
    }

    public static HashedValue categoricals(int[] names) {
        return new HashedValue(names);
    }

    public static HashedValue numericals(int[] names, double[] vals) {
        return new HashedValue(names, vals);
    }

    // Getters
    public int[] getCategoricalIndices() {
        checkState(this.valueType == HashedValueType.CATEGORICAL);
        return this.values.getCategoricalIndices();
    }

    public int getSingleCategorical() {
        checkState(valueType == HashedValueType.CATEGORICAL && values.getCategoricalIndices().length == 1,
                String.format("Tried to retrieve a single categorical value from type:%s length:%s",
                        valueType, values.getCategoricalIndices().length));
        return values.getCategoricalIndices()[0];
    }

    public int[] getNumericalIndices(){
        checkState(this.valueType == HashedValueType.NUMERICAL);
        return this.values.getNumericalIndices();
    }
    public double[] getNumericalValues() {
        checkState(this.valueType == HashedValueType.NUMERICAL);
        return this.values.getNumericalValues();
    }

    public double getSingleNumerical() {
        checkState(valueType == HashedValueType.NUMERICAL &&
                        values.getNumericalIndices().length == 1 &&
                        values.getNumericalIndices() == DEFAULT_NAME,
                String.format("Tried to retrieve a single numerical value from value of type:%s indices:%s",
                        valueType, Arrays.toString(values.getNumericalIndices())));
        return values.getNumericalValues()[0];
    }

    public SparseVector asSparseVector() {
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
