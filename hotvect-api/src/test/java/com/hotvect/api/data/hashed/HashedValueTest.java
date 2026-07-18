package com.hotvect.api.data.hashed;

import com.hotvect.api.data.HashedValue;
import com.hotvect.api.data.HashedValueType;
import com.hotvect.api.data.SparseVector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class HashedValueTest {

    private record NumericalPair(int[] names, double[] values) {
    }

    @ParameterizedTest
    @MethodSource("ints")
    void singleCategorical(int x) {
        HashedValue subject = HashedValue.singleCategorical(x);
        assertEquals(x, subject.getSingleCategorical());
        assertThrows(IllegalStateException.class, subject::getSingleNumerical);
        assertThrows(IllegalStateException.class, subject::getNumericalValues);
        SparseVector actualVector = subject.asSparseVector();
        assertEquals(new SparseVector(new int[]{x}), actualVector);
    }

    @ParameterizedTest
    @MethodSource("doubles")
    void singleNumerical(double x) {
        HashedValue subject = HashedValue.singleNumerical(x);
        assertEquals(x, subject.getSingleNumerical());
        assertThrows(IllegalStateException.class, subject::getSingleCategorical);
        assertArrayEquals(new int[]{0}, subject.getNumericalIndices());
        SparseVector actualVector = subject.asSparseVector();
        assertEquals(new SparseVector(new int[]{0}, new double[]{x}), actualVector);
    }

    @ParameterizedTest
    @MethodSource("categoricalVectors")
    void categoricals(int[] x) {
        HashedValue subject = HashedValue.categoricals(x);
        assertArrayEquals(x, subject.getCategoricalIndices());
        assertThrows(IllegalStateException.class, subject::getSingleNumerical);
        if (x.length != 1) {
            assertThrows(IllegalStateException.class, subject::getSingleCategorical);
        }
        assertThrows(IllegalStateException.class, subject::getNumericalValues);
        SparseVector actualVector = subject.asSparseVector();
        assertEquals(new SparseVector(x), actualVector);
    }

    @ParameterizedTest
    @MethodSource("numericalPairs")
    void numericals(NumericalPair x) {
        int[] names = x.names();
        double[] values = x.values();
        assertEquals(names.length, values.length);
        HashedValue subject = HashedValue.numericals(names, values);
        assertArrayEquals(names, subject.getNumericalIndices());
        assertThrows(IllegalStateException.class, subject::getSingleCategorical);
        if (names.length != 1) {
            assertThrows(IllegalStateException.class, subject::getSingleNumerical);
        }
        assertArrayEquals(values, subject.getNumericalValues());
        SparseVector actualVector = subject.asSparseVector();
        SparseVector expected = new SparseVector(names, values);
        assertEquals(expected, actualVector);
    }

    @Test
    void invalidInputs() {
        assertThrows(NullPointerException.class, () -> HashedValue.categoricals(null));
        assertThrows(NullPointerException.class, () -> HashedValue.numericals(null, null));
        assertThrows(IllegalArgumentException.class, () -> HashedValue.numericals(new int[1], new double[2]));
    }

    @Test
    void getValueType() {
        assertEquals(HashedValueType.CATEGORICAL, HashedValue.singleCategorical(1).getValueType());
        assertEquals(HashedValueType.CATEGORICAL, HashedValue.categoricals(new int[0]).getValueType());
        assertEquals(HashedValueType.NUMERICAL, HashedValue.singleNumerical(0.0).getValueType());
        assertEquals(HashedValueType.NUMERICAL, HashedValue.numericals(new int[0], new double[0]).getValueType());
    }

    @ParameterizedTest
    @MethodSource("differentInts")
    void equalitySingleCategorical(int x, int y) {
        HashedValue xp = HashedValue.singleCategorical(x);
        HashedValue yp = HashedValue.singleCategorical(y);
        assertNotEquals(xp, yp);
    }

    @ParameterizedTest
    @MethodSource("ints")
    void equalitySingleCategoricalEquals(int x) {
        HashedValue xp = HashedValue.singleCategorical(x);
        HashedValue yp = HashedValue.singleCategorical(x);
        assertEquals(xp, yp);
        assertEquals(xp.hashCode(), yp.hashCode());
    }

    @ParameterizedTest
    @MethodSource("differentDoubles")
    void equalitySingleNumerical(double x, double y) {
        HashedValue xp = HashedValue.singleNumerical(x);
        HashedValue yp = HashedValue.singleNumerical(y);
        assertNotEquals(xp, yp);
    }

    @ParameterizedTest
    @MethodSource("doubles")
    void equalitySingleNumericalEquals(double x) {
        HashedValue xp = HashedValue.singleNumerical(x);
        HashedValue yp = HashedValue.singleNumerical(x);
        assertEquals(xp, yp);
        assertEquals(xp.hashCode(), yp.hashCode());
    }

    @ParameterizedTest
    @MethodSource("differentCategoricalVectorPairs")
    void equalityCategoricals(int[] x, int[] y) {
        HashedValue xp = HashedValue.categoricals(x);
        HashedValue yp = HashedValue.categoricals(y);
        assertNotEquals(xp, yp);
    }

    @ParameterizedTest
    @MethodSource("categoricalVectors")
    void equalityCategoricalsEquals(int[] x) {
        int[] y = Arrays.copyOf(x, x.length);
        HashedValue xp = HashedValue.categoricals(x);
        HashedValue yp = HashedValue.categoricals(y);
        assertEquals(xp, yp);
        assertEquals(xp.hashCode(), yp.hashCode());
    }

    @ParameterizedTest
    @MethodSource("differentNumericalPairPairs")
    void equalityNumericals(NumericalPair x, NumericalPair y) {
        HashedValue xp = HashedValue.numericals(x.names(), x.values());
        HashedValue yp = HashedValue.numericals(y.names(), y.values());
        assertNotEquals(xp, yp);
    }

    @ParameterizedTest
    @MethodSource("numericalPairs")
    void equalityNumericalsEquals(NumericalPair x) {
        HashedValue xp = HashedValue.numericals(x.names(), x.values());
        HashedValue yp = HashedValue.numericals(
                Arrays.copyOf(x.names(), x.names().length),
                Arrays.copyOf(x.values(), x.values().length)
        );
        assertEquals(xp, yp);
        assertEquals(xp.hashCode(), yp.hashCode());
    }

    private static Stream<Integer> ints() {
        return Stream.of(Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE);
    }

    private static Stream<Double> doubles() {
        return Stream.of(Double.MIN_VALUE, -1.0, 0.0, 1.0, Math.PI, Double.MAX_VALUE);
    }

    private static Stream<Arguments> differentInts() {
        return Stream.of(
                Arguments.of(0, 1),
                Arguments.of(-1, 1),
                Arguments.of(Integer.MIN_VALUE, Integer.MAX_VALUE)
        );
    }

    private static Stream<Arguments> differentDoubles() {
        return Stream.of(
                Arguments.of(0.0, 1.0),
                Arguments.of(-1.0, 1.0),
                Arguments.of(Double.MIN_VALUE, Double.MAX_VALUE)
        );
    }

    private static Stream<Arguments> categoricalVectors() {
        return Stream.of(
                Arguments.of((Object) new int[]{}),
                Arguments.of((Object) new int[]{0}),
                Arguments.of((Object) new int[]{-1, 2, Integer.MAX_VALUE})
        );
    }

    private static Stream<NumericalPair> numericalPairs() {
        return Stream.of(
                new NumericalPair(new int[]{}, new double[]{}),
                new NumericalPair(new int[]{0}, new double[]{0.0}),
                new NumericalPair(new int[]{-1, 2}, new double[]{Double.MIN_VALUE, Math.PI})
        );
    }

    private static Stream<Arguments> differentCategoricalVectorPairs() {
        return Stream.of(
                Arguments.of(new int[]{}, new int[]{0}),
                Arguments.of(new int[]{0}, new int[]{1}),
                Arguments.of(new int[]{1, 2}, new int[]{2, 1})
        );
    }

    private static Stream<Arguments> differentNumericalPairPairs() {
        return Stream.of(
                Arguments.of(new NumericalPair(new int[]{}, new double[]{}), new NumericalPair(new int[]{0}, new double[]{0.0})),
                Arguments.of(new NumericalPair(new int[]{0}, new double[]{0.0}), new NumericalPair(new int[]{0}, new double[]{1.0})),
                Arguments.of(new NumericalPair(new int[]{0}, new double[]{1.0}), new NumericalPair(new int[]{1}, new double[]{1.0}))
        );
    }
}
