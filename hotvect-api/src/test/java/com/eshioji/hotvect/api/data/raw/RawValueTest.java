package com.eshioji.hotvect.api.data.raw;

import com.eshioji.hotvect.api.data.SparseVector;
import org.junit.jupiter.api.Test;
import org.quicktheories.api.Pair;

import java.util.Arrays;
import java.util.function.BiConsumer;

import static com.eshioji.hotvect.testutils.TestUtils.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.quicktheories.QuickTheory.qt;
import static org.quicktheories.generators.Generate.constant;
import static org.quicktheories.generators.Generate.intArrays;
import static org.quicktheories.generators.SourceDSL.*;

class RawValueTest {
    @Test
    void singleCategorical() {
        qt().forAll(integers().all()).checkAssert(x -> {
            var subject = RawValue.singleCategorical(x);
            assertEquals(x, subject.getSingleCategorical());
            assertThrows(IllegalStateException.class, subject::getSingleNumerical);
            assertThrows(IllegalStateException.class, subject::getNumericals);

            var actualVector = subject.getCategoricalsToNumericals();
            assertEquals(new SparseVector(new int[]{x}, new double[]{1.0}), actualVector);
        });
    }

    @Test
    void singleString() {
        qt().forAll(strings().allPossible().ofLengthBetween(0, 3)).checkAssert(x -> {
            var subject = RawValue.singleString(x);
            assertEquals(x, subject.getSingleString());
            assertThrows(IllegalStateException.class, subject::getSingleNumerical);
            assertThrows(IllegalStateException.class, subject::getNumericals);
        });
    }

    @Test
    void singleNumerical() {
        qt().forAll(doubles().any()).checkAssert(x -> {
            var subject = RawValue.singleNumerical(x);
            assertEquals(x, subject.getSingleNumerical());
            assertThrows(IllegalStateException.class, subject::getSingleCategorical);
            assertThrows(IllegalStateException.class, subject::getCategoricals);

            var actualVector = subject.getCategoricalsToNumericals();
            assertEquals(new SparseVector(new int[]{0}, new double[]{x}), actualVector);
        });
    }

    @Test
    void categoricals() {
        qt().forAll(intArrays(integers().between(0, 3), integers().all())).checkAssert( x -> {
            var subject = RawValue.categoricals(x);
            assertArrayEquals(x, subject.getCategoricals());
            assertThrows(IllegalStateException.class, subject::getSingleNumerical);
            if (x.length != 1) {
                assertThrows(IllegalStateException.class, subject::getSingleCategorical);
            }

            var expectedNumericals = new double[x.length];
            Arrays.fill(expectedNumericals, 1.0);
            var actualVector = subject.getCategoricalsToNumericals();
            assertEquals(new SparseVector(x, expectedNumericals), actualVector);
        });
    }

    @Test
    void numericals() {
        var data = intArrays(integers().between(0, 3), integers().all())
                .mutate((is, rand) -> Pair.of(is, doubleArrays(constant(is.length), doubles().any()).generate(rand)));
        qt().forAll(data).checkAssert(x -> {

            var names = x._1;
            var values = x._2;
            assert names.length == values.length;

            var subject = RawValue.categoricalsToNumericals(names, values);
            assertArrayEquals(names, subject.getCategoricals());
            assertThrows(IllegalStateException.class, subject::getSingleCategorical);
            if (names.length != 1) {
                assertThrows(IllegalStateException.class, subject::getSingleNumerical);
            }

            assertArrayEquals(values, subject.getNumericals());

            var actualVector = subject.getCategoricalsToNumericals();
            assertEquals(new SparseVector(names, values), actualVector);

        });
    }

    @Test
    void stringsToNumericals() {
        var data = stringArrays(integers().between(0, 3), strings().allPossible().ofLengthBetween(0, 3))
                .mutate((is, rand) -> Pair.of(is, doubleArrays(constant(is.length), doubles().any()).generate(rand)));
        qt().forAll(data).checkAssert(x -> {

            var names = x._1;
            var values = x._2;
            assert names.length == values.length;

            var subject = RawValue.stringsToNumericals(names, values);
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
            var xp = RawValue.singleCategorical(x);
            var yp = RawValue.singleCategorical(y);
            assertEquals(x.equals(y), xp.equals(yp));
            if (x.equals(y)){
                assertEquals(xp.hashCode(), yp.hashCode());
            }
        };

        qt().forAll(integers().all(), integers().all())
                .assuming((x, y) -> !x.equals(y))
                .checkAssert(assertSingleCategorical);

        var equalInts = generateEquals(integers().all());
        qt().forAll(equalInts)
                .checkAssert(x -> assertSingleCategorical.accept(x._1, x._2));

        // Single numerical
        BiConsumer<Double, Double> assertSingleNumerical = (x, y) -> {
            var xp = RawValue.singleNumerical(x);
            var yp = RawValue.singleNumerical(y);
            assertEquals(x.equals(y), xp.equals(yp));
            if (x.equals(y)){
                assertEquals(xp.hashCode(), yp.hashCode());
            }
        };

        qt().forAll(discreteDoubles(), discreteDoubles())
                .assuming((x, y) -> !x.equals(y))
                .checkAssert(assertSingleNumerical);

        var equalDoubles = generateEquals(discreteDoubles());
        qt().forAll(equalDoubles)
                .checkAssert(x -> assertSingleNumerical.accept(x._1, x._2));


        // Categoricals
        BiConsumer<int[], int[]> assertCategoricals = (x, y) -> {
            var xp = RawValue.categoricals(x);
            var yp = RawValue.categoricals(y);
            assertEquals(Arrays.equals(x, y), xp.equals(yp));
            if (Arrays.equals(x, y)){
                assertEquals(xp.hashCode(), yp.hashCode());
            }
        };

        qt().forAll(categoricalVectors(), categoricalVectors())
                .assuming((x, y) -> !Arrays.equals(x, y))
                .checkAssert(assertCategoricals);

        var generateEquals = generateEquals(categoricalVectors());
        qt().forAll(generateEquals)
                .checkAssert(x -> assertCategoricals.accept(x._1, x._2));

        // Numericals
        var data = testVectors();
        BiConsumer<Pair<int[], double[]>, Pair<int[], double[]>> assertNumericals = (x, y) -> {
            var xp = RawValue.categoricalsToNumericals(x._1, x._2);
            var yp = RawValue.categoricalsToNumericals(y._1, y._2);

            var equal = Arrays.equals(x._1, y._1) && Arrays.equals(x._2, y._2);
            assertEquals(equal, xp.equals(yp));
            if (equal){
                assertEquals(xp.hashCode(), yp.hashCode());
            }
        };

        qt().forAll(data, data)
                .assuming((x, y) -> !Arrays.equals(x._1, y._1) || !Arrays.equals(x._2, y._2))
                .checkAssert(assertNumericals);

        qt().forAll(generateEquals(data)).checkAssert(x -> assertNumericals.accept(x._1, x._2));

        // Strings
        BiConsumer<String[], String[]> assertStrings = (x, y) -> {
            var xp = RawValue.strings(x);
            var yp = RawValue.strings(y);

            var equal = Arrays.equals(x, y);
            assertEquals(equal, xp.equals(yp));
            if (equal){
                assertEquals(xp.hashCode(), yp.hashCode());
            }
        };

        qt().forAll(stringArraysWithDefaultContent(), stringArraysWithDefaultContent())
                .assuming((x, y) -> !Arrays.equals(x, y))
                .checkAssert(assertStrings);

        qt().forAll(generateEquals(stringArraysWithDefaultContent())).checkAssert(x -> assertStrings.accept(x._1, x._2));

        // Single String
        BiConsumer<String, String> assertSingleString = (x, y) -> {
            var xp = RawValue.singleString(x);
            var yp = RawValue.singleString(y);

            var equal = x.equals(y);
            assertEquals(equal, xp.equals(yp));
            if (equal){
                assertEquals(xp.hashCode(), yp.hashCode());
            }
        };

        qt().forAll(defaultStrings(), defaultStrings())
                .assuming((x, y) -> !x.equals(y))
                .checkAssert(assertSingleString);

        qt().forAll(generateEquals(defaultStrings())).checkAssert(x -> assertSingleString.accept(x._1, x._2));

        // String To Numericals
        BiConsumer<Pair<String[], double[]>, Pair<String[], double[]>> assertStringsToNumericals = (x, y) -> {
            var xp = RawValue.stringsToNumericals(x._1, x._2);
            var yp = RawValue.stringsToNumericals(y._1, y._2);

            var equal = Arrays.equals(x._1, y._1) && Arrays.equals(x._2, y._2);
            assertEquals(equal, xp.equals(yp));
            if (equal){
                assertEquals(xp.hashCode(), yp.hashCode());
            }
        };

        qt().forAll(stringToNumericals(), stringToNumericals())
                .assuming((x, y) -> (!Arrays.equals(x._1, y._1)) || (!Arrays.equals(x._2, y._2)))
                .checkAssert(assertStringsToNumericals);

        qt().forAll(generateEquals(stringToNumericals())).checkAssert(x -> assertStringsToNumericals.accept(x._1, x._2));
    }
}