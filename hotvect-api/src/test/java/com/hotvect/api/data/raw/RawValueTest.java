package com.hotvect.api.data.raw;

import com.hotvect.api.data.HashedValue;
import com.hotvect.api.data.RawValue;
import com.hotvect.api.data.RawValueType;
import com.hotvect.api.data.SparseVector;
import net.jqwik.api.*;
import net.jqwik.api.Tuple.Tuple2;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.function.Executable;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class RawValueTest {

    @Property
    void denseVector(@ForAll @Size(max = 5) double[] values) {
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
        int[] indices = IntStream.range(0, copied.length).toArray();
        assertEquals(HashedValue.denseVector(values), rawValue.getHashedValue());
        assertEquals(RawValueType.DENSE_VECTOR, rawValue.getValueType());
    }

    @Property
    void singleCategorical(@ForAll int x) {
        RawValue subject = RawValue.singleCategorical(x);
        assertEquals(x, subject.getSingleCategorical());
        assertThrows(IllegalStateException.class, subject::getSingleNumerical);
        assertThrows(IllegalStateException.class, subject::getNumericals);
        assertThrows(IllegalStateException.class, subject::getSparseVector);
    }

    @Property
    void singleString(@ForAll @StringLength(min = 0, max = 3) String x) {
        RawValue subject = RawValue.singleString(x);
        assertEquals(x, subject.getSingleString());
        assertThrows(IllegalStateException.class, subject::getSingleNumerical);
        assertThrows(IllegalStateException.class, subject::getNumericals);
    }

    @Property
    void singleNumerical(@ForAll double x) {
        RawValue subject = RawValue.singleNumerical(x);
        assertEquals(x, subject.getSingleNumerical());
        assertThrows(IllegalStateException.class, subject::getSingleCategorical);
        assertThrows(IllegalStateException.class, subject::getCategoricals);
        assertThrows(IllegalStateException.class, subject::getSparseVector);
    }

    @Property
    void categoricals(@ForAll @Size(min = 0, max = 3) int[] x) {
        RawValue subject = RawValue.categoricals(x);
        assertArrayEquals(x, subject.getCategoricals());
        assertThrows(IllegalStateException.class, subject::getSingleNumerical);
        assertThrows(IllegalStateException.class, subject::getSparseVector);
        if (x.length != 1) {
            assertThrows(IllegalStateException.class, subject::getSingleCategorical);
        }
    }

    @Property
    void numericals(@ForAll("intDoubleArrayPairs") Tuple2<int[], double[]> x) {
        int[] names = x.get1();
        double[] values = x.get2();
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

    @Provide
    Arbitrary<Tuple2<int[], double[]>> intDoubleArrayPairs() {
        Arbitrary<int[]> intArrays = Arbitraries.integers()
                .array(int[].class)
                .ofMinSize(0)
                .ofMaxSize(3);
        return intArrays.flatMap(ints -> {
            int length = ints.length;
            Arbitrary<double[]> doubleArrays = Arbitraries.doubles()
                    .array(double[].class)
                    .ofSize(length);
            return doubleArrays.map(doubles -> Tuple.of(ints, doubles));
        });
    }

    @Property
    void stringsToNumericals(@ForAll("stringDoubleArrayPairs") Tuple2<String[], double[]> x) {
        String[] names = x.get1();
        double[] values = x.get2();
        assertEquals(names.length, values.length);
        RawValue subject = RawValue.stringsToNumericals(names, values);
        assertArrayEquals(names, subject.getStrings());
        assertThrows(IllegalStateException.class, subject::getSingleCategorical);
        assertThrows(IllegalStateException.class, subject::getSingleNumerical);
        assertArrayEquals(values, subject.getNumericals());
        assertThrows(IllegalStateException.class, subject::getSparseVector);
    }

    @Provide
    Arbitrary<Tuple2<String[], double[]>> stringDoubleArrayPairs() {
        Arbitrary<String[]> stringArrays = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(0).ofMaxLength(3)
                .array(String[].class)
                .ofMinSize(0)
                .ofMaxSize(3);
        return stringArrays.flatMap(strings -> {
            int length = strings.length;
            Arbitrary<double[]> doubleArrays = Arbitraries.doubles()
                    .array(double[].class)
                    .ofSize(length);
            return doubleArrays.map(doubles -> Tuple.of(strings, doubles));
        });
    }

    @Example
    void invalidInputs() {
        assertThrows(NullPointerException.class, () -> RawValue.categoricals(null));
        assertThrows(NullPointerException.class, () -> RawValue.namedNumericals(null, null));
        assertThrows(IllegalArgumentException.class, () -> RawValue.namedNumericals(new int[1], new double[2]));
        assertThrows(NullPointerException.class, () -> RawValue.singleString(null));
        assertThrows(NullPointerException.class, () -> RawValue.strings(null));
        assertThrows(NullPointerException.class, () -> RawValue.stringsToNumericals(null, null));
        assertThrows(IllegalArgumentException.class, () -> RawValue.stringsToNumericals(new String[0], new double[1]));
    }

    @Example
    void getValueType() {
        assertEquals(RawValueType.SINGLE_CATEGORICAL, RawValue.singleCategorical(1).getValueType());
        assertEquals(RawValueType.CATEGORICALS, RawValue.categoricals(new int[0]).getValueType());
        assertEquals(RawValueType.SINGLE_NUMERICAL, RawValue.singleNumerical(0.0).getValueType());
        assertEquals(RawValueType.SPARSE_VECTOR, RawValue.sparseVector(new int[0], new double[0]).getValueType());
        assertEquals(RawValueType.SINGLE_STRING, RawValue.singleString("a").getValueType());
        assertEquals(RawValueType.STRINGS_TO_NUMERICALS, RawValue.stringsToNumericals(new String[0], new double[0]).getValueType());
    }

    @Property
    void equalitySingleCategorical(@ForAll int x, @ForAll int y) {
        Assume.that(x != y);
        RawValue xp = RawValue.singleCategorical(x);
        RawValue yp = RawValue.singleCategorical(y);
        assertEquals(x == y, xp.equals(yp));
        if (x == y) {
            assertEquals(xp.hashCode(), yp.hashCode());
        }
    }

    @Property
    void equalitySingleCategoricalEquals(@ForAll("equalIntPairs") Tuple2<Integer, Integer> pair) {
        int x = pair.get1();
        int y = pair.get2();
        RawValue xp = RawValue.singleCategorical(x);
        RawValue yp = RawValue.singleCategorical(y);
        assertEquals(x == y, xp.equals(yp));
        assertEquals(xp.hashCode(), yp.hashCode());
    }

    @Provide
    Arbitrary<Tuple2<Integer, Integer>> equalIntPairs() {
        return Arbitraries.integers().map(i -> Tuple.of(i, i));
    }

    @Property
    void equalitySingleNumerical(@ForAll double x, @ForAll double y) {
        Assume.that(Double.compare(x, y) != 0);
        RawValue xp = RawValue.singleNumerical(x);
        RawValue yp = RawValue.singleNumerical(y);
        assertEquals(Double.compare(x, y) == 0, xp.equals(yp));
        if (Double.compare(x, y) == 0) {
            assertEquals(xp.hashCode(), yp.hashCode());
        }
    }

    @Property
    void equalitySingleNumericalEquals(@ForAll("equalDoublePairs") Tuple2<Double, Double> pair) {
        double x = pair.get1();
        double y = pair.get2();
        RawValue xp = RawValue.singleNumerical(x);
        RawValue yp = RawValue.singleNumerical(y);
        assertEquals(Double.compare(x, y) == 0, xp.equals(yp));
        assertEquals(xp.hashCode(), yp.hashCode());
    }

    @Provide
    Arbitrary<Tuple2<Double, Double>> equalDoublePairs() {
        return Arbitraries.doubles().map(d -> Tuple.of(d, d));
    }

    @Property
    void equalityCategoricals(@ForAll @Size(min = 0, max = 5) int[] x,
                              @ForAll @Size(min = 0, max = 5) int[] y) {
        Assume.that(!Arrays.equals(x, y));
        RawValue xp = RawValue.categoricals(x);
        RawValue yp = RawValue.categoricals(y);
        assertEquals(Arrays.equals(x, y), xp.equals(yp));
        if (Arrays.equals(x, y)) {
            assertEquals(xp.hashCode(), yp.hashCode());
        }
    }

    @Property
    void equalityCategoricalsEquals(@ForAll("equalIntArrayPairs") Tuple2<int[], int[]> pair) {
        int[] x = pair.get1();
        int[] y = pair.get2();
        RawValue xp = RawValue.categoricals(x);
        RawValue yp = RawValue.categoricals(y);
        assertTrue(Arrays.equals(x, y));
        assertEquals(xp, yp);
        assertEquals(xp.hashCode(), yp.hashCode());
    }

    @Provide
    Arbitrary<Tuple2<int[], int[]>> equalIntArrayPairs() {
        return Arbitraries.integers()
                .array(int[].class)
                .ofMinSize(0).ofMaxSize(5)
                .map(arr -> Tuple.of(arr, arr));
    }

    @Property
    void equalityStrings(@ForAll("stringArrays") String[] x,
                         @ForAll("stringArrays") String[] y) {
        Assume.that(!Arrays.equals(x, y));
        RawValue xp = RawValue.strings(x);
        RawValue yp = RawValue.strings(y);
        assertEquals(Arrays.equals(x, y), xp.equals(yp));
        if (Arrays.equals(x, y)) {
            assertEquals(xp.hashCode(), yp.hashCode());
        }
    }

    @Property
    void equalityStringsEquals(@ForAll("equalStringArrayPairs") Tuple2<String[], String[]> pair) {
        String[] x = pair.get1();
        String[] y = pair.get2();
        RawValue xp = RawValue.strings(x);
        RawValue yp = RawValue.strings(y);
        assertTrue(Arrays.equals(x, y));
        assertEquals(xp, yp);
        assertEquals(xp.hashCode(), yp.hashCode());
    }

    @Provide
    Arbitrary<String[]> stringArrays() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(0).ofMaxLength(5)
                .array(String[].class)
                .ofMinSize(0)
                .ofMaxSize(5);
    }

    @Provide
    Arbitrary<Tuple2<String[], String[]>> equalStringArrayPairs() {
        return stringArrays().map(arr -> Tuple.of(arr, arr));
    }

    @Property
    void equalitySingleString(@ForAll @StringLength(min = 0, max = 5) String x,
                              @ForAll @StringLength(min = 0, max = 5) String y) {
        Assume.that(!x.equals(y));
        RawValue xp = RawValue.singleString(x);
        RawValue yp = RawValue.singleString(y);
        assertEquals(x.equals(y), xp.equals(yp));
        if (x.equals(y)) {
            assertEquals(xp.hashCode(), yp.hashCode());
        }
    }

    @Property
    void equalitySingleStringEquals(@ForAll("equalStrings") Tuple2<String, String> pair) {
        String x = pair.get1();
        String y = pair.get2();
        RawValue xp = RawValue.singleString(x);
        RawValue yp = RawValue.singleString(y);
        assertEquals(x, y);
        assertEquals(xp, yp);
        assertEquals(xp.hashCode(), yp.hashCode());
    }

    @Provide
    Arbitrary<Tuple2<String, String>> equalStrings() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(0).ofMaxLength(5)
                .map(str -> Tuple.of(str, str));
    }

    @Property
    void equalityStringsToNumericals(@ForAll("stringDoubleArrayPairs") Tuple2<String[], double[]> x,
                                     @ForAll("stringDoubleArrayPairs") Tuple2<String[], double[]> y) {
        Assume.that(!Arrays.equals(x.get1(), y.get1()) || !Arrays.equals(x.get2(), y.get2()));
        RawValue xp = RawValue.stringsToNumericals(x.get1(), x.get2());
        RawValue yp = RawValue.stringsToNumericals(y.get1(), y.get2());
        boolean equal = Arrays.equals(x.get1(), y.get1()) && Arrays.equals(x.get2(), y.get2());
        assertEquals(equal, xp.equals(yp));
        if (equal) {
            assertEquals(xp.hashCode(), yp.hashCode());
        }
    }

    @Property
    void equalityStringsToNumericalsEquals(@ForAll("equalStringDoubleArrayPairs") Tuple2<String[], double[]> x) {
        RawValue xp = RawValue.stringsToNumericals(x.get1(), x.get2());
        RawValue yp = RawValue.stringsToNumericals(x.get1(), x.get2());
        assertEquals(xp, yp);
        assertEquals(xp.hashCode(), yp.hashCode());
    }

    @Provide
    Arbitrary<Tuple2<String[], double[]>> equalStringDoubleArrayPairs() {
        return stringDoubleArrayPairs().map(pair -> Tuple.of(pair.get1(), pair.get2()));
    }
}