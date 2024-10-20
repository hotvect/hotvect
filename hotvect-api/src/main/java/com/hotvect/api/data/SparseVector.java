package com.hotvect.api.data;

import java.util.Arrays;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A sparse vector representation that keeps categorical and numerical features separately (cat boost friendly)
 */
public class SparseVector {
    private static final int[] EMPTY_IDX = new int[0];
    private static final double[] EMPTY_VAL = new double[0];

    private final int[] categoricalIndices;
    private final int[] numericalIndices;
    private final double[] numericalValues;

    public SparseVector(int[] categoricalIndices, int[] numericalIndices, double[] numericalValues) {
        checkNotNull(categoricalIndices);
        checkNotNull(numericalIndices);
        checkArgument(numericalIndices.length == numericalValues.length);
        this.categoricalIndices = categoricalIndices;
        this.numericalIndices = numericalIndices;
        this.numericalValues = numericalValues;
    }

    public SparseVector(int[] categoricalIndices) {
        checkNotNull(categoricalIndices);
        //Special case of Sparse Vector where there are only categorical features
        this.categoricalIndices = categoricalIndices;
        this.numericalIndices = EMPTY_IDX;
        this.numericalValues = EMPTY_VAL;
    }

    public SparseVector(double[] numericalValues) {
        checkNotNull(numericalValues);
        //Special case of Sparse Vector where there are only categorical features
        this.categoricalIndices = EMPTY_IDX;
        int[] indices = new int[numericalValues.length];
        for (int i = 0; i < numericalValues.length; i++) {
            indices[i] = i;
        }
        this.numericalIndices = indices;
        this.numericalValues = numericalValues;
    }


    public SparseVector(int[] numericalIndices, double[] numericalValues) {
        checkNotNull(numericalIndices);
        checkNotNull(numericalValues);
        checkArgument(numericalIndices.length == numericalValues.length);
        //Special case of Sparse Vector where there are only categorical features
        this.categoricalIndices = EMPTY_IDX;
        this.numericalIndices = numericalIndices;
        this.numericalValues = numericalValues;
    }


    public int[] getCategoricalIndices() {
        return categoricalIndices;
    }

    public int[] getNumericalIndices() {
        return numericalIndices;
    }

    public double[] getNumericalValues() {
        return numericalValues;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SparseVector that = (SparseVector) o;
        return Arrays.equals(categoricalIndices, that.categoricalIndices) && Arrays.equals(numericalIndices, that.numericalIndices) && Arrays.equals(numericalValues, that.numericalValues);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(categoricalIndices);
        result = 31 * result + Arrays.hashCode(numericalIndices);
        result = 31 * result + Arrays.hashCode(numericalValues);
        return result;
    }

    @Override
    public String toString() {
        return "SparseVector{" +
                "categoricalIndices=" + Arrays.toString(categoricalIndices) +
                ", numericalIndices=" + Arrays.toString(numericalIndices) +
                ", numericalValues=" + Arrays.toString(numericalValues) +
                '}';
    }
}
