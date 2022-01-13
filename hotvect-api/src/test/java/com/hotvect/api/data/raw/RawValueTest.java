package com.hotvect.api.data.raw;

import com.hotvect.api.data.SparseVector;
import com.hotvect.testutils.TestUtils;
import org.junit.jupiter.api.Test;
import org.quicktheories.api.Pair;
import org.quicktheories.core.Gen;

import java.util.Arrays;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.quicktheories.QuickTheory.qt;
import static org.quicktheories.generators.Generate.constant;
import static org.quicktheories.generators.Generate.intArrays;
import static org.quicktheories.generators.SourceDSL.*;

class RawValueTest {
    @Test
    void singleCategorical() {
        qt().forAll(integers().all()).checkAssert(x -> {
            RawValue subject = RawValue.singleCategorical(x);
            assertEquals(x, subject.getSingleCategorical());
            assertThrows(IllegalStateException.class, subject::getSingleNumerical);
            assertThrows(IllegalStateException.class, subject::getNumericals);

            SparseVector actualVector = subject.getCategoricalsToNumericals();
            assertEquals(new SparseVector(new int[]{x}, new double[]{1.0}), actualVector);
        });
    }

    @Test
    void singleString() {
        qt().forAll(strings().allPossible().ofLengthBetween(0, 3)).checkAssert(x -> {
            RawValue subject = RawValue.singleString(x);
            assertEquals(x, subject.getSingleString());
            assertThrows(IllegalStateException.class, subject::getSingleNumerical);
            assertThrows(IllegalStateException.class, subject::getNumericals);
        });
    }

    @Test
    void singleNumerical() {
        qt().forAll(doubles().any()).checkAssert(x -> {
            RawValue subject = RawValue.singleNumerical(x);
            assertEquals(x, subject.getSingleNumerical());
            assertThrows(IllegalStateException.class, subject::getSingleCategorical);
            assertThrows(IllegalStateException.class, subject::getCategoricals);

            SparseVector actualVector = subject.getCategoricalsToNumericals();
            assertEquals(new SparseVector(new int[]{0}, new double[]{x}), actualVector);
        });
    }

    @Test
    void categoricals() {
        qt().forAll(intArrays(integers().between(0, 3), integers().all())).checkAssert( x -> {
            RawValue subject = RawValue.categoricals(x);
            assertArrayEquals(x, subject.getCategoricals());
            assertThrows(IllegalStateException.class, subject::getSingleNumerical);
            if (x.length != 1) {
                assertThrows(IllegalStateException.class, subject::getSingleCategorical);
            }

            double[] expectedNumericals = new double[x.length];
            Arrays.fill(expectedNumericals, 1.0);
            SparseVector actualVector = subject.getCategoricalsToNumericals();
            assertEquals(new SparseVector(x, expectedNumericals), actualVector);
        });
    }

    @Test
    void numericals() {
        Gen<Pair<int[], double[]>> data = intArrays(integers().between(0, 3), integers().all())
                .mutate((is, rand) -> Pair.of(is, TestUtils.doubleArrays(constant(is.length), doubles().any()).generate(rand)));
        qt().forAll(data).checkAssert(x -> {

            int[] names = x._1;
            double[] values = x._2;
            assert names.length == values.length;

            RawValue subject = RawValue.categoricalsToNumericals(names, values);
            assertArrayEquals(names, subject.getCategoricals());
            assertThrows(IllegalStateException.class, subject::getSingleCategorical);
            if (names.length != 1) {
                assertThrows(IllegalStateException.class, subject::getSingleNumerical);
            }

            assertArrayEquals(values, subject.getNumericals());

            SparseVector actualVector = subject.getCategoricalsToNumericals();
            assertEquals(new SparseVector(names, values), actualVector);

        });
    }

    @Test
    void stringsToNumericals() {
        Gen<Pair<String[], double[]>> data = TestUtils.stringArrays(integers().between(0, 3), strings().allPossible().ofLengthBetween(0, 3))
                .mutate((is, rand) -> Pair.of(is, TestUtils.doubleArrays(constant(is.length), doubles().any()).generate(rand)));
        qt().forAll(data).checkAssert(x -> {

            String[] names = x._1;
            double[] values = x._2;
            assert names.length == values.length;

            RawValue subject = RawValue.stringsToNumericals(names, values);
            assertArrayEquals(names, subject.getStrings());
            assertThrows(IllegalStateException.class, subject::getSingleCategorical);
            assertThrows(IllegalStateException.class, subject::getSingleNumerical);

            assertArrayEquals(values, subject.getNumericals());

            assertThrows(NullPointerException.class, subject::getCategoricalsToNumericals);
        });
    }

    @Test
    void invalidInputs() {
        assertThrows(NullPointerException.class, () -> RawValue.categoricals(null));
        assertThrows(NullPointerException.class, () -> RawValue.categoricalsToNumericals(null, null));
        assertThrows(IllegalArgumentException.class, () -> RawValue.categoricalsToNumericals(new int[1], new double[2]));
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
        assertEquals(RawValueType.CATEGORICALS_TO_NUMERICALS, RawValue.categoricalsToNumericals(new int[0], new double[0]).getValueType());
        assertEquals(RawValueType.SINGLE_STRING, RawValue.singleString("a").getValueType());
        assertEquals(RawValueType.STRINGS_TO_NUMERICALS, RawValue.stringsToNumericals(new String[0], new double[0]).getValueType());
    }

    @Test
    void equality(){
        // Single categorical
        BiConsumer<Integer, Integer> assertSingleCategorical = (x, y) -> {
            RawValue xp = RawValue.singleCategorical(x);
            RawValue yp = RawValue.singleCategorical(y);
            assertEquals(x.equals(y), xp.equals(yp));
            if (x.equals(y)){
                assertEquals(xp.hashCode(), yp.hashCode());
            }
        };

        qt().forAll(integers().all(), integers().all())
                .assuming((x, y) -> !x.equals(y))
                .checkAssert(assertSingleCategorical);

        Gen<Pair<Integer, Integer>> equalInts = TestUtils.generateEquals(integers().all());
        qt().forAll(equalInts)
                .checkAssert(x -> assertSingleCategorical.accept(x._1, x._2));

        // Single numerical
        BiConsumer<Double, Double> assertSingleNumerical = (x, y) -> {
            RawValue xp = RawValue.singleNumerical(x);
            RawValue yp = RawValue.singleNumerical(y);
            assertEquals(x.equals(y), xp.equals(yp));
            if (x.equals(y)){
                assertEquals(xp.hashCode(), yp.hashCode());
            }
        };

        qt().forAll(TestUtils.discreteDoubles(), TestUtils.discreteDoubles())
                .assuming((x, y) -> !x.equals(y))
                .checkAssert(assertSingleNumerical);

        Gen<Pair<Double, Double>> equalDoubles = TestUtils.generateEquals(TestUtils.discreteDoubles());
        qt().forAll(equalDoubles)
                .checkAssert(x -> assertSingleNumerical.accept(x._1, x._2));


        // Categoricals
        BiConsumer<int[], int[]> assertCategoricals = (x, y) -> {
            RawValue xp = RawValue.categoricals(x);
            RawValue yp = RawValue.categoricals(y);
            assertEquals(Arrays.equals(x, y), xp.equals(yp));
            if (Arrays.equals(x, y)){
                assertEquals(xp.hashCode(), yp.hashCode());
            }
        };

        qt().forAll(TestUtils.categoricalVectors(), TestUtils.categoricalVectors())
                .assuming((x, y) -> !Arrays.equals(x, y))
                .checkAssert(assertCategoricals);

        Gen<Pair<int[], int[]>> generateEquals = TestUtils.generateEquals(TestUtils.categoricalVectors());
        qt().forAll(generateEquals)
                .checkAssert(x -> assertCategoricals.accept(x._1, x._2));

        // Numericals
        Gen<Pair<int[], double[]>> data = TestUtils.testVectors();
        BiConsumer<Pair<int[], double[]>, Pair<int[], double[]>> assertNumericals = (x, y) -> {
            RawValue xp = RawValue.categoricalsToNumericals(x._1, x._2);
            RawValue yp = RawValue.categoricalsToNumericals(y._1, y._2);

            boolean equal = Arrays.equals(x._1, y._1) && Arrays.equals(x._2, y._2);
            assertEquals(equal, xp.equals(yp));
            if (equal){
                assertEquals(xp.hashCode(), yp.hashCode());
            }
        };

        qt().forAll(data, data)
                .assuming((x, y) -> !Arrays.equals(x._1, y._1) || !Arrays.equals(x._2, y._2))
                .checkAssert(assertNumericals);

        qt().forAll(TestUtils.generateEquals(data)).checkAssert(x -> assertNumericals.accept(x._1, x._2));

        // Strings
        BiConsumer<String[], String[]> assertStrings = (x, y) -> {
            RawValue xp = RawValue.strings(x);
            RawValue yp = RawValue.strings(y);

            boolean equal = Arrays.equals(x, y);
            assertEquals(equal, xp.equals(yp));
            if (equal){
                assertEquals(xp.hashCode(), yp.hashCode());
            }
        };

        qt().forAll(TestUtils.stringArraysWithDefaultContent(), TestUtils.stringArraysWithDefaultContent())
                .assuming((x, y) -> !Arrays.equals(x, y))
                .checkAssert(assertStrings);

        qt().forAll(TestUtils.generateEquals(TestUtils.stringArraysWithDefaultContent())).checkAssert(x -> assertStrings.accept(x._1, x._2));

        // Single String
        BiConsumer<String, String> assertSingleString = (x, y) -> {
            RawValue xp = RawValue.singleString(x);
            RawValue yp = RawValue.singleString(y);

            boolean equal = x.equals(y);
            assertEquals(equal, xp.equals(yp));
            if (equal){
                assertEquals(xp.hashCode(), yp.hashCode());
            }
        };

        qt().forAll(TestUtils.defaultStrings(), TestUtils.defaultStrings())
                .assuming((x, y) -> !x.equals(y))
                .checkAssert(assertSingleString);

        qt().forAll(TestUtils.generateEquals(TestUtils.defaultStrings())).checkAssert(x -> assertSingleString.accept(x._1, x._2));

        // String To Numericals
        BiConsumer<Pair<String[], double[]>, Pair<String[], double[]>> assertStringsToNumericals = (x, y) -> {
            RawValue xp = RawValue.stringsToNumericals(x._1, x._2);
            RawValue yp = RawValue.stringsToNumericals(y._1, y._2);

            boolean equal = Arrays.equals(x._1, y._1) && Arrays.equals(x._2, y._2);
            assertEquals(equal, xp.equals(yp));
            if (equal){
                assertEquals(xp.hashCode(), yp.hashCode());
            }
        };

        qt().forAll(TestUtils.stringToNumericals(), TestUtils.stringToNumericals())
                .assuming((x, y) -> (!Arrays.equals(x._1, y._1)) || (!Arrays.equals(x._2, y._2)))
                .checkAssert(assertStringsToNumericals);

        qt().forAll(TestUtils.generateEquals(TestUtils.stringToNumericals())).checkAssert(x -> assertStringsToNumericals.accept(x._1, x._2));
    }
}