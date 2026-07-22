package com.hotvect.api.data.raw;

import com.hotvect.api.data.HashedValue;
import com.hotvect.api.data.RawValue;
import com.hotvect.api.data.RawValueType;
import com.hotvect.api.data.SparseVector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class RawValueTest {

    private record IntDoubleArrayPair(int[] names, double[] values) {
    }

    private record StringDoubleArrayPair(String[] names, double[] values) {
    }

    @ParameterizedTest
    @MethodSource("doubleArrays")
    void denseVector(double[] values) {
        double[] copied = Arrays.copyOf(values, values.length);
        RawValue rawValue = RawValue.denseVector(values);
        assertArrayEquals(copied, rawValue.getNumericals());
        List<Executable> notAllowed = List.of(
                rawValue::getStrings,
                rawValue::getCategoricals,
                rawValue::getSingleCategorical,
                rawValue::getSingleNumerical,
                rawValue::getSparseVector
        );
        notAllowed.forEach(x -> assertThrows(IllegalStateException.class, x));
        assertEquals(HashedValue.denseVector(values), rawValue.getHashedValue());
        assertEquals(RawValueType.DENSE_VECTOR, rawValue.getValueType());
    }

    @ParameterizedTest
    @MethodSource("ints")
    void singleCategorical(int x) {
        RawValue subject = RawValue.singleCategorical(x);
        assertEquals(x, subject.getSingleCategorical());
        assertThrows(IllegalStateException.class, subject::getSingleNumerical);
        assertThrows(IllegalStateException.class, subject::getNumericals);
        assertThrows(IllegalStateException.class, subject::getSparseVector);
    }

    @ParameterizedTest
    @MethodSource("strings")
    void singleString(String x) {
        RawValue subject = RawValue.singleString(x);
        assertEquals(x, subject.getSingleString());
        assertThrows(IllegalStateException.class, subject::getSingleNumerical);
        assertThrows(IllegalStateException.class, subject::getNumericals);
    }

    @ParameterizedTest
    @MethodSource("doubles")
    void singleNumerical(double x) {
        RawValue subject = RawValue.singleNumerical(x);
        assertEquals(x, subject.getSingleNumerical());
        assertThrows(IllegalStateException.class, subject::getSingleCategorical);
        assertThrows(IllegalStateException.class, subject::getCategoricals);
        assertThrows(IllegalStateException.class, subject::getSparseVector);
    }

    @ParameterizedTest
    @MethodSource("intArrays")
    void categoricals(int[] x) {
        RawValue subject = RawValue.categoricals(x);
        assertArrayEquals(x, subject.getCategoricals());
        assertThrows(IllegalStateException.class, subject::getSingleNumerical);
        assertThrows(IllegalStateException.class, subject::getSparseVector);
        if (x.length != 1) {
            assertThrows(IllegalStateException.class, subject::getSingleCategorical);
        }
    }

    @ParameterizedTest
    @MethodSource("intDoubleArrayPairs")
    void numericals(IntDoubleArrayPair x) {
        int[] names = x.names();
        double[] values = x.values();
        assertEquals(names.length, values.length);
        RawValue subject = RawValue.namedNumericals(names, values);
        assertArrayEquals(names, subject.getNumericalIndices());
        assertThrows(IllegalStateException.class, subject::getSingleCategorical);
        if (names.length != 1) {
            assertThrows(IllegalStateException.class, subject::getSingleNumerical);
        }
        assertArrayEquals(values, subject.getNumericals());
        SparseVector actualVector = subject.getSparseVector();
        assertEquals(new SparseVector(names, values), actualVector);
    }

    @ParameterizedTest
    @MethodSource("stringDoubleArrayPairs")
    void stringsToNumericals(StringDoubleArrayPair x) {
        String[] names = x.names();
        double[] values = x.values();
        assertEquals(names.length, values.length);
        RawValue subject = RawValue.stringsToNumericals(names, values);
        assertArrayEquals(names, subject.getStrings());
        assertThrows(IllegalStateException.class, subject::getSingleCategorical);
        assertThrows(IllegalStateException.class, subject::getSingleNumerical);
        assertArrayEquals(values, subject.getNumericals());
        assertThrows(IllegalStateException.class, subject::getSparseVector);
    }

    @Test
    void invalidInputs() {
        assertThrows(NullPointerException.class, () -> RawValue.categoricals(null));
        assertThrows(NullPointerException.class, () -> RawValue.namedNumericals(null, null));
        assertThrows(IllegalArgumentException.class, () -> RawValue.namedNumericals(new int[1], new double[2]));
        assertThrows(NullPointerException.class, () -> RawValue.singleString(null));
        assertThrows(NullPointerException.class, () -> RawValue.strings(null));
        assertThrows(NullPointerException.class, () -> RawValue.stringsToNumericals(null, null));
        assertThrows(IllegalArgumentException.class, () -> RawValue.stringsToNumericals(new String[0], new double[1]));
    }

    @Test
    void getValueType() {
        assertEquals(RawValueType.SINGLE_CATEGORICAL, RawValue.singleCategorical(1).getValueType());
        assertEquals(RawValueType.CATEGORICALS, RawValue.categoricals(new int[0]).getValueType());
        assertEquals(RawValueType.SINGLE_NUMERICAL, RawValue.singleNumerical(0.0).getValueType());
        assertEquals(RawValueType.SPARSE_VECTOR, RawValue.sparseVector(new int[0], new double[0]).getValueType());
        assertEquals(RawValueType.SINGLE_STRING, RawValue.singleString("a").getValueType());
        assertEquals(RawValueType.STRINGS_TO_NUMERICALS, RawValue.stringsToNumericals(new String[0], new double[0]).getValueType());
    }

    @ParameterizedTest
    @MethodSource("differentInts")
    void equalitySingleCategorical(int x, int y) {
        RawValue xp = RawValue.singleCategorical(x);
        RawValue yp = RawValue.singleCategorical(y);
        assertNotEquals(xp, yp);
    }

    @ParameterizedTest
    @MethodSource("ints")
    void equalitySingleCategoricalEquals(int x) {
        RawValue xp = RawValue.singleCategorical(x);
        RawValue yp = RawValue.singleCategorical(x);
        assertEquals(xp, yp);
        assertEquals(xp.hashCode(), yp.hashCode());
    }

    @ParameterizedTest
    @MethodSource("differentDoubles")
    void equalitySingleNumerical(double x, double y) {
        RawValue xp = RawValue.singleNumerical(x);
        RawValue yp = RawValue.singleNumerical(y);
        assertNotEquals(xp, yp);
    }

    @ParameterizedTest
    @MethodSource("doubles")
    void equalitySingleNumericalEquals(double x) {
        RawValue xp = RawValue.singleNumerical(x);
        RawValue yp = RawValue.singleNumerical(x);
        assertEquals(xp, yp);
        assertEquals(xp.hashCode(), yp.hashCode());
    }

    @ParameterizedTest
    @MethodSource("differentIntArrayPairs")
    void equalityCategoricals(int[] x, int[] y) {
        RawValue xp = RawValue.categoricals(x);
        RawValue yp = RawValue.categoricals(y);
        assertNotEquals(xp, yp);
    }

    @ParameterizedTest
    @MethodSource("intArrays")
    void equalityCategoricalsEquals(int[] x) {
        int[] y = Arrays.copyOf(x, x.length);
        RawValue xp = RawValue.categoricals(x);
        RawValue yp = RawValue.categoricals(y);
        assertEquals(xp, yp);
        assertEquals(xp.hashCode(), yp.hashCode());
    }

    @ParameterizedTest
    @MethodSource("differentStringArrayPairs")
    void equalityStrings(String[] x, String[] y) {
        RawValue xp = RawValue.strings(x);
        RawValue yp = RawValue.strings(y);
        assertNotEquals(xp, yp);
    }

    @ParameterizedTest
    @MethodSource("stringArrays")
    void equalityStringsEquals(String[] x) {
        String[] y = Arrays.copyOf(x, x.length);
        RawValue xp = RawValue.strings(x);
        RawValue yp = RawValue.strings(y);
        assertEquals(xp, yp);
        assertEquals(xp.hashCode(), yp.hashCode());
    }

    @ParameterizedTest
    @MethodSource("differentStrings")
    void equalitySingleString(String x, String y) {
        RawValue xp = RawValue.singleString(x);
        RawValue yp = RawValue.singleString(y);
        assertNotEquals(xp, yp);
    }

    @ParameterizedTest
    @MethodSource("strings")
    void equalitySingleStringEquals(String x) {
        RawValue xp = RawValue.singleString(x);
        RawValue yp = RawValue.singleString(x);
        assertEquals(xp, yp);
        assertEquals(xp.hashCode(), yp.hashCode());
    }

    @ParameterizedTest
    @MethodSource("differentStringDoubleArrayPairPairs")
    void equalityStringsToNumericals(StringDoubleArrayPair x, StringDoubleArrayPair y) {
        RawValue xp = RawValue.stringsToNumericals(x.names(), x.values());
        RawValue yp = RawValue.stringsToNumericals(y.names(), y.values());
        assertNotEquals(xp, yp);
    }

    @ParameterizedTest
    @MethodSource("stringDoubleArrayPairs")
    void equalityStringsToNumericalsEquals(StringDoubleArrayPair x) {
        RawValue xp = RawValue.stringsToNumericals(x.names(), x.values());
        RawValue yp = RawValue.stringsToNumericals(
                Arrays.copyOf(x.names(), x.names().length),
                Arrays.copyOf(x.values(), x.values().length)
        );
        assertEquals(xp, yp);
        assertEquals(xp.hashCode(), yp.hashCode());
    }

    private static Stream<Arguments> doubleArrays() {
        return Stream.of(
                Arguments.of((Object) new double[]{}),
                Arguments.of((Object) new double[]{0.0}),
                Arguments.of((Object) new double[]{Double.MIN_VALUE, -1.0, Math.PI})
        );
    }

    private static Stream<Integer> ints() {
        return Stream.of(Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE);
    }

    private static Stream<Double> doubles() {
        return Stream.of(Double.MIN_VALUE, -1.0, 0.0, 1.0, Math.PI, Double.MAX_VALUE);
    }

    private static Stream<String> strings() {
        return Stream.of("", "a", "abc", "with space");
    }

    private static Stream<Arguments> intArrays() {
        return Stream.of(
                Arguments.of((Object) new int[]{}),
                Arguments.of((Object) new int[]{0}),
                Arguments.of((Object) new int[]{-1, 2, Integer.MAX_VALUE})
        );
    }

    private static Stream<Arguments> stringArrays() {
        return Stream.of(
                Arguments.of((Object) new String[]{}),
                Arguments.of((Object) new String[]{""}),
                Arguments.of((Object) new String[]{"a", "bc"})
        );
    }

    private static Stream<IntDoubleArrayPair> intDoubleArrayPairs() {
        return Stream.of(
                new IntDoubleArrayPair(new int[]{}, new double[]{}),
                new IntDoubleArrayPair(new int[]{0}, new double[]{0.0}),
                new IntDoubleArrayPair(new int[]{-1, 2}, new double[]{Double.MIN_VALUE, Math.PI})
        );
    }

    private static Stream<StringDoubleArrayPair> stringDoubleArrayPairs() {
        return Stream.of(
                new StringDoubleArrayPair(new String[]{}, new double[]{}),
                new StringDoubleArrayPair(new String[]{"a"}, new double[]{0.0}),
                new StringDoubleArrayPair(new String[]{"a", "bc"}, new double[]{Double.MIN_VALUE, Math.PI})
        );
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

    private static Stream<Arguments> differentStrings() {
        return Stream.of(
                Arguments.of("", "a"),
                Arguments.of("a", "b"),
                Arguments.of("abc", "with space")
        );
    }

    private static Stream<Arguments> differentIntArrayPairs() {
        return Stream.of(
                Arguments.of(new int[]{}, new int[]{0}),
                Arguments.of(new int[]{0}, new int[]{1}),
                Arguments.of(new int[]{1, 2}, new int[]{2, 1})
        );
    }

    private static Stream<Arguments> differentStringArrayPairs() {
        return Stream.of(
                Arguments.of(new String[]{}, new String[]{""}),
                Arguments.of(new String[]{"a"}, new String[]{"b"}),
                Arguments.of(new String[]{"a", "bc"}, new String[]{"a", "cb"})
        );
    }

    private static Stream<Arguments> differentStringDoubleArrayPairPairs() {
        return Stream.of(
                Arguments.of(
                        new StringDoubleArrayPair(new String[]{}, new double[]{}),
                        new StringDoubleArrayPair(new String[]{"a"}, new double[]{0.0})
                ),
                Arguments.of(
                        new StringDoubleArrayPair(new String[]{"a"}, new double[]{0.0}),
                        new StringDoubleArrayPair(new String[]{"a"}, new double[]{1.0})
                ),
                Arguments.of(
                        new StringDoubleArrayPair(new String[]{"a"}, new double[]{1.0}),
                        new StringDoubleArrayPair(new String[]{"b"}, new double[]{1.0})
                )
        );
    }
}
