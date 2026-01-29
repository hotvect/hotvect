package com.hotvect.api.data;

import net.jqwik.api.*;
import net.jqwik.api.Tuple.Tuple2;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SparseVectorTest {

    @Provide
    Arbitrary<Double> doubles() {
        return Arbitraries.of(Double.MIN_VALUE, -1.0, 0.0, 1.0, Double.MAX_VALUE);
    }

    @Provide
    Arbitrary<Tuple2<int[], double[]>> vectors() {
        Arbitrary<int[]> intArrays = Arbitraries.integers()
                .array(int[].class)
                .ofMinSize(0)
                .ofMaxSize(3);

        return intArrays.flatMap(is -> {
            int length = is.length;
            Arbitrary<double[]> doubleArrays = doubles()
                    .array(double[].class)
                    .ofSize(length);
            return doubleArrays.map(ds -> Tuple.of(is, ds));
        });
    }

    @Property
    void correctlyInitializes(@ForAll("vectors") Tuple2<int[], double[]> x) {
        int[] names = x.get1();
        double[] values = x.get2();
        assertEquals(names.length, values.length);
        SparseVector subject = new SparseVector(names, values);
        assertArrayEquals(names, subject.getNumericalIndices());
        assertArrayEquals(values, subject.getNumericalValues());
    }

    @Provide
    Arbitrary<int[]> intArrays() {
        return Arbitraries.integers()
                .array(int[].class)
                .ofMinSize(0)
                .ofMaxSize(3);
    }

    @Property
    void correctlyInitializesWithNamesOnly(@ForAll("intArrays") int[] x) {
        SparseVector subject = new SparseVector(x);
        assertArrayEquals(x, subject.getCategoricalIndices());
    }

    @Example
    void invalidInputs() {
        assertThrows(NullPointerException.class, () -> new SparseVector((int[]) null));
        assertThrows(NullPointerException.class, () -> new SparseVector((double[]) null));
        assertThrows(NullPointerException.class, () -> new SparseVector(null, null));
        assertThrows(IllegalArgumentException.class, () -> new SparseVector(new int[1], new double[2]));
    }

    @Property
    void equality(@ForAll("vectors") Tuple2<int[], double[]> x, @ForAll("vectors") Tuple2<int[], double[]> y) {
        SparseVector xp = new SparseVector(x.get1(), x.get2());
        SparseVector yp = new SparseVector(y.get1(), y.get2());
        boolean arraysEqual = Arrays.equals(x.get1(), y.get1()) && Arrays.equals(x.get2(), y.get2());
        if (arraysEqual) {
            assertEquals(xp, yp);
            assertEquals(xp.hashCode(), yp.hashCode());
        } else {
            assertNotEquals(xp, yp);
        }
    }
}