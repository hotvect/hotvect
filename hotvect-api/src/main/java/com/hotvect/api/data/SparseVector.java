package com.hotvect.api.data;

import java.util.Arrays;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A sparse vector representation
 */
public class SparseVector {
    private final int[] indices;

    // Lazy loaded because categorical features are more frequent,
    // and many operations don't require their values
    private volatile double[] values;

    public SparseVector(int[] indices, double[] values) {
        checkNotNull(indices);
        checkNotNull(values);
        checkArgument(indices.length == values.length);
        this.indices = indices;
        this.values = values;
    }

    public SparseVector(int[] indices) {
        checkNotNull(indices);
        //Special case of Sparse Vector where values are 1.0
        this.indices = indices;
        this.values = null;
    }


    public int[] indices() {
        return indices;
    }

    public double[] values() {
        if (values == null) {
            double[] vals = new double[this.indices().length];
            Arrays.fill(vals, 1.0);
            this.values = vals;
        }
        return values;
    }

    public int size() {
        return this.indices.length;
    }

    @Override
    public String toString() {
        return "SparseVector{" +
                "indices=" + Arrays.toString(indices) +
                ", values=" + Arrays.toString(values()) +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SparseVector that = (SparseVector) o;
        return Arrays.equals(indices, that.indices) &&
                Arrays.equals(values(), that.values());
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(indices);
        result = 31 * result + Arrays.hashCode(values());
        return result;
    }
}
