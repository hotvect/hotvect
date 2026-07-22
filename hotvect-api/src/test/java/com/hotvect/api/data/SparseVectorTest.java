package com.hotvect.api.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SparseVectorTest {

    @ParameterizedTest
    @MethodSource("vectors")
    void correctlyInitializes(int[] names, double[] values) {
        assertEquals(names.length, values.length);
        SparseVector subject = new SparseVector(names, values);
        assertArrayEquals(names, subject.getNumericalIndices());
        assertArrayEquals(values, subject.getNumericalValues());
    }

    @ParameterizedTest
    @MethodSource("intArrays")
    void correctlyInitializesWithNamesOnly(int[] x) {
        SparseVector subject = new SparseVector(x);
        assertArrayEquals(x, subject.getCategoricalIndices());
    }

    @Test
    void invalidInputs() {
        assertThrows(NullPointerException.class, () -> new SparseVector((int[]) null));
        assertThrows(NullPointerException.class, () -> new SparseVector((double[]) null));
        assertThrows(NullPointerException.class, () -> new SparseVector(null, null));
        assertThrows(IllegalArgumentException.class, () -> new SparseVector(new int[1], new double[2]));
    }

    @ParameterizedTest
    @MethodSource("vectorPairs")
    void equality(int[] xNames, double[] xValues, int[] yNames, double[] yValues) {
        SparseVector xp = new SparseVector(xNames, xValues);
        SparseVector yp = new SparseVector(yNames, yValues);
        boolean arraysEqual = Arrays.equals(xNames, yNames) && Arrays.equals(xValues, yValues);
        if (arraysEqual) {
            assertEquals(xp, yp);
            assertEquals(xp.hashCode(), yp.hashCode());
        } else {
            assertNotEquals(xp, yp);
        }
    }

    private static Stream<Arguments> vectors() {
        return Stream.of(
                Arguments.of(new int[]{}, new double[]{}),
                Arguments.of(new int[]{0}, new double[]{0.0}),
                Arguments.of(new int[]{-1, 2, 10}, new double[]{Double.MIN_VALUE, -1.0, Double.MAX_VALUE})
        );
    }

    private static Stream<Arguments> intArrays() {
        return Stream.of(
                Arguments.of((Object) new int[]{}),
                Arguments.of((Object) new int[]{0}),
                Arguments.of((Object) new int[]{-1, 2, 10})
        );
    }

    private static Stream<Arguments> vectorPairs() {
        return Stream.of(
                Arguments.of(new int[]{}, new double[]{}, new int[]{}, new double[]{}),
                Arguments.of(new int[]{0}, new double[]{0.0}, new int[]{0}, new double[]{0.0}),
                Arguments.of(new int[]{0}, new double[]{0.0}, new int[]{1}, new double[]{0.0}),
                Arguments.of(new int[]{0}, new double[]{0.0}, new int[]{0}, new double[]{1.0})
        );
    }
}
