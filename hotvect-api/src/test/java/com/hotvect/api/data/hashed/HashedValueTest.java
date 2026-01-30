package com.hotvect.api.data.hashed;

import com.hotvect.api.data.HashedValue;
import com.hotvect.api.data.HashedValueType;
import com.hotvect.api.data.SparseVector;
import net.jqwik.api.*;
import net.jqwik.api.Tuple.Tuple2;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class HashedValueTest {

    @Property
    void singleCategorical(@ForAll int x) {
        HashedValue subject = HashedValue.singleCategorical(x);
        assertEquals(x, subject.getSingleCategorical());
        assertThrows(IllegalStateException.class, subject::getSingleNumerical);
        assertThrows(IllegalStateException.class, subject::getNumericalValues);
        SparseVector actualVector = subject.asSparseVector();
        assertEquals(new SparseVector(new int[]{x}), actualVector);
    }

    @Property
    void singleNumerical(@ForAll double x) {
        HashedValue subject = HashedValue.singleNumerical(x);
        assertEquals(x, subject.getSingleNumerical());
        assertThrows(IllegalStateException.class, subject::getSingleCategorical);
        assertArrayEquals(new int[]{0}, subject.getNumericalIndices());
        SparseVector actualVector = subject.asSparseVector();
        assertEquals(new SparseVector(new int[]{0}, new double[]{x}), actualVector);
    }

    @Property
    void categoricals(@ForAll("categoricalVectors") int[] x) {
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

    @Property
    void numericals(@ForAll("numericalPairs") Tuple2<int[], double[]> x) {
        int[] names = x.get1();
        double[] values = x.get2();
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

    @Example
    void invalidInputs() {
        assertThrows(NullPointerException.class, () -> HashedValue.categoricals(null));
        assertThrows(NullPointerException.class, () -> HashedValue.numericals(null, null));
        assertThrows(IllegalArgumentException.class, () -> HashedValue.numericals(new int[1], new double[2]));
    }

    @Example
    void getValueType() {
        assertEquals(HashedValueType.CATEGORICAL, HashedValue.singleCategorical(1).getValueType());
        assertEquals(HashedValueType.CATEGORICAL, HashedValue.categoricals(new int[0]).getValueType());
        assertEquals(HashedValueType.NUMERICAL, HashedValue.singleNumerical(0.0).getValueType());
        assertEquals(HashedValueType.NUMERICAL, HashedValue.numericals(new int[0], new double[0]).getValueType());
    }

    @Property
    void equalitySingleCategorical(@ForAll int x, @ForAll int y) {
        Assume.that(x != y);
        HashedValue xp = HashedValue.singleCategorical(x);
        HashedValue yp = HashedValue.singleCategorical(y);
        assertEquals(x == y, xp.equals(yp));
        if (x == y) {
            assertEquals(xp.hashCode(), yp.hashCode());
        }
    }

    @Property
    void equalitySingleCategoricalEquals(@ForAll("equalInts") Tuple2<Integer, Integer> pair) {
        int x = pair.get1();
        int y = pair.get2();
        HashedValue xp = HashedValue.singleCategorical(x);
        HashedValue yp = HashedValue.singleCategorical(y);
        assertEquals(x == y, xp.equals(yp));
        if (x == y) {
            assertEquals(xp.hashCode(), yp.hashCode());
        }
    }

    @Property
    void equalitySingleNumerical(@ForAll double x, @ForAll double y) {
        Assume.that(Double.compare(x, y) != 0);
        HashedValue xp = HashedValue.singleNumerical(x);
        HashedValue yp = HashedValue.singleNumerical(y);
        assertEquals(Double.compare(x, y) == 0, xp.equals(yp));
        if (Double.compare(x, y) == 0) {
            assertEquals(xp.hashCode(), yp.hashCode());
        }
    }

    @Property
    void equalitySingleNumericalEquals(@ForAll("equalDoubles") Tuple2<Double, Double> pair) {
        double x = pair.get1();
        double y = pair.get2();
        HashedValue xp = HashedValue.singleNumerical(x);
        HashedValue yp = HashedValue.singleNumerical(y);
        assertEquals(Double.compare(x, y) == 0, xp.equals(yp));
        if (Double.compare(x, y) == 0) {
            assertEquals(xp.hashCode(), yp.hashCode());
        }
    }

    @Property
    void equalityCategoricals(@ForAll("categoricalVectors") int[] x, @ForAll("categoricalVectors") int[] y) {
        Assume.that(!Arrays.equals(x, y));
        HashedValue xp = HashedValue.categoricals(x);
        HashedValue yp = HashedValue.categoricals(y);
        assertEquals(Arrays.equals(x, y), xp.equals(yp));
        if (Arrays.equals(x, y)) {
            assertEquals(xp.hashCode(), yp.hashCode());
        }
    }

    @Property
    void equalityCategoricalsEquals(@ForAll("equalIntArrays") Tuple2<int[], int[]> pair) {
        int[] x = pair.get1();
        int[] y = pair.get2();
        HashedValue xp = HashedValue.categoricals(x);
        HashedValue yp = HashedValue.categoricals(y);
        assertEquals(Arrays.equals(x, y), xp.equals(yp));
        if (Arrays.equals(x, y)) {
            assertEquals(xp.hashCode(), yp.hashCode());
        }
    }

    @Property
    void equalityNumericals(@ForAll("numericalPairs") Tuple2<int[], double[]> x,
                            @ForAll("numericalPairs") Tuple2<int[], double[]> y) {
        boolean arraysEqual = Arrays.equals(x.get1(), y.get1()) && Arrays.equals(x.get2(), y.get2());
        Assume.that(!arraysEqual);

        HashedValue xp = HashedValue.numericals(x.get1(), x.get2());
        HashedValue yp = HashedValue.numericals(y.get1(), y.get2());
        assertEquals(arraysEqual, xp.equals(yp));
        if (arraysEqual) {
            assertEquals(xp.hashCode(), yp.hashCode());
        }
    }

    @Property
    void equalityNumericalsEquals(@ForAll("equalNumericalPairs") Tuple2<int[], double[]> x) {
        HashedValue xp = HashedValue.numericals(x.get1(), x.get2());
        HashedValue yp = HashedValue.numericals(x.get1(), x.get2());
        assertEquals(xp, yp);
        assertEquals(xp.hashCode(), yp.hashCode());
    }

    // Provide the required arbitraries
    @Provide
    Arbitrary<int[]> categoricalVectors() {
        return Arbitraries.integers()
                .array(int[].class)
                .ofMaxSize(0)
                .ofMaxSize(10); // Adjust as necessary
    }

    @Provide
    Arbitrary<Tuple2<int[], double[]>> numericalPairs() {
        return categoricalVectors().flatMap(ints -> {
            int length = ints.length;
            Arbitrary<double[]> doublesArray = Arbitraries.doubles()
                    .array(double[].class)
                    .ofSize(length);
            return doublesArray.map(ds -> Tuple.of(ints, ds));
        });
    }

    @Provide
    Arbitrary<Tuple2<Integer, Integer>> equalInts() {
        return Arbitraries.integers().map(i -> Tuple.of(i, i));
    }

    @Provide
    Arbitrary<Tuple2<Double, Double>> equalDoubles() {
        return Arbitraries.doubles().map(d -> Tuple.of(d, d));
    }

    @Provide
    Arbitrary<Tuple2<int[], int[]>> equalIntArrays() {
        return categoricalVectors().map(arr -> Tuple.of(arr, arr));
    }

    @Provide
    Arbitrary<Tuple2<int[], double[]>> equalNumericalPairs() {
        return numericalPairs();
    }
}