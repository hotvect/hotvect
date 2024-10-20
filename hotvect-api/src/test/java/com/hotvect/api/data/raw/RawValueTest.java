package com.hotvect.api.data.raw;

import com.google.common.collect.ImmutableList;
import com.hotvect.api.data.HashedValue;
import com.hotvect.api.data.RawValue;
import com.hotvect.api.data.RawValueType;
import com.hotvect.api.data.SparseVector;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.quicktheories.api.Pair;
import org.quicktheories.core.Gen;

import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;

import static com.hotvect.testutils.TestUtils.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.quicktheories.QuickTheory.qt;
import static org.quicktheories.generators.Generate.constant;
import static org.quicktheories.generators.Generate.intArrays;
import static org.quicktheories.generators.SourceDSL.*;

class RawValueTest {

    @Property
    void denseVector(@ForAll @Size(max = 5) double[] values){
        double[] copied = Arrays.copyOf(values, values.length);
        var rawValue = RawValue.denseVector(values);
        assertArrayEquals(copied, rawValue.getNumericals());

        List<Executable> notAllowed = ImmutableList.of(
                rawValue::getStrings,
                rawValue::getCategoricals,
                rawValue::getSingleCategorical,
                rawValue::getSingleNumerical,
                rawValue::getSparseVector
        );
        notAllowed.forEach(
                x -> assertThrows(IllegalStateException.class,x)
        );

        var indices = IntStream.range(0, copied.length).toArray();

        assertEquals(HashedValue.denseVector(values), rawValue.getHashedValue());
        assertEquals(RawValueType.DENSE_VECTOR, rawValue.getValueType());

    }
    @Test
    void singleCategorical() {
        qt().forAll(integers().all()).checkAssert(x -> {
            RawValue subject = RawValue.singleCategorical(x);
            assertEquals(x, subject.getSingleCategorical());
            assertThrows(IllegalStateException.class, subject::getSingleNumerical);
            assertThrows(IllegalStateException.class, subject::getNumericals);
            assertThrows(IllegalStateException.class, subject::getSparseVector);
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
            assertThrows(IllegalStateException.class, subject::getSparseVector);
        });
    }

    @Test
    void categoricals() {
        qt().forAll(intArrays(integers().between(0, 3), integers().all())).checkAssert( x -> {
            RawValue subject = RawValue.categoricals(x);
            assertArrayEquals(x, subject.getCategoricals());
            assertThrows(IllegalStateException.class, subject::getSingleNumerical);
            assertThrows(IllegalStateException.class, subject::getSparseVector);
            if (x.length != 1) {
                assertThrows(IllegalStateException.class, subject::getSingleCategorical);
            }
        });
    }

    @Test
    void numericals() {
        Gen<Pair<int[], double[]>> data = intArrays(integers().between(0, 3), integers().all())
                .mutate((is, rand) -> Pair.of(is, doubleArrays(constant(is.length), doubles().any()).generate(rand)));
        qt().forAll(data).checkAssert(x -> {

            int[] names = x._1;
            double[] values = x._2;
            assert names.length == values.length;

            RawValue subject = RawValue.namedNumericals(names, values);
            assertArrayEquals(names, subject.getNumericalIndices());
            assertThrows(IllegalStateException.class, subject::getSingleCategorical);
            if (names.length != 1) {
                assertThrows(IllegalStateException.class, subject::getSingleNumerical);
            }

            assertArrayEquals(values, subject.getNumericals());

            SparseVector actualVector = subject.getSparseVector();
            assertEquals(new SparseVector(names, values), actualVector);

        });
    }

    @Test
    void stringsToNumericals() {
        Gen<Pair<String[], double[]>> data = stringArrays(integers().between(0, 3), strings().allPossible().ofLengthBetween(0, 3))
                .mutate((is, rand) -> Pair.of(is, doubleArrays(constant(is.length), doubles().any()).generate(rand)));
        qt().forAll(data).checkAssert(x -> {

            String[] names = x._1;
            double[] values = x._2;
            assert names.length == values.length;

            RawValue subject = RawValue.stringsToNumericals(names, values);
            assertArrayEquals(names, subject.getStrings());
            assertThrows(IllegalStateException.class, subject::getSingleCategorical);
            assertThrows(IllegalStateException.class, subject::getSingleNumerical);

            assertArrayEquals(values, subject.getNumericals());

            assertThrows(IllegalStateException.class, subject::getSparseVector);
        });
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

        qt().withFixedSeed(10).forAll(integers().all(), integers().all())
                .assuming((x, y) -> !x.equals(y))
                .checkAssert(assertSingleCategorical);

        Gen<Pair<Integer, Integer>> equalInts = generateEquals(integers().all());
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

        qt().withFixedSeed(10).forAll(discreteDoubles(), discreteDoubles())
                .assuming((x, y) -> !x.equals(y))
                .checkAssert(assertSingleNumerical);

        Gen<Pair<Double, Double>> equalDoubles = generateEquals(discreteDoubles());
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

        qt().withFixedSeed(10).forAll(categoricalVectors(), categoricalVectors())
                .assuming((x, y) -> !Arrays.equals(x, y))
                .checkAssert(assertCategoricals);

        Gen<Pair<int[], int[]>> generateEquals = generateEquals(categoricalVectors());
        qt().forAll(generateEquals)
                .checkAssert(x -> assertCategoricals.accept(x._1, x._2));

        // Numericals
        Gen<Pair<int[], double[]>> data = testVectors();
        BiConsumer<Pair<int[], double[]>, Pair<int[], double[]>> assertNumericals = (x, y) -> {
            RawValue xp = RawValue.namedNumericals(x._1, x._2);
            RawValue yp = RawValue.namedNumericals(y._1, y._2);

            boolean equal = Arrays.equals(x._1, y._1) && Arrays.equals(x._2, y._2);
            assertEquals(equal, xp.equals(yp));
            if (equal){
                assertEquals(xp.hashCode(), yp.hashCode());
            }
        };

        qt().withFixedSeed(10).forAll(data, data)
                .assuming((x, y) -> !Arrays.equals(x._1, y._1) || !Arrays.equals(x._2, y._2))
                .checkAssert(assertNumericals);

        qt().forAll(generateEquals(data)).checkAssert(x -> assertNumericals.accept(x._1, x._2));

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

        qt().withFixedSeed(10).forAll(stringArraysWithDefaultContent(), stringArraysWithDefaultContent())
                .assuming((x, y) -> !Arrays.equals(x, y))
                .checkAssert(assertStrings);

        qt().forAll(generateEquals(stringArraysWithDefaultContent())).checkAssert(x -> assertStrings.accept(x._1, x._2));

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

        qt().withFixedSeed(10).forAll(defaultStrings(), defaultStrings())
                .assuming((x, y) -> !x.equals(y))
                .checkAssert(assertSingleString);

        qt().forAll(generateEquals(defaultStrings())).checkAssert(x -> assertSingleString.accept(x._1, x._2));

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

        qt().withFixedSeed(10).forAll(stringToNumericals(), stringToNumericals())
                .assuming((x, y) -> (!Arrays.equals(x._1, y._1)) || (!Arrays.equals(x._2, y._2)))
                .checkAssert(assertStringsToNumericals);

        qt().forAll(generateEquals(stringToNumericals())).checkAssert(x -> assertStringsToNumericals.accept(x._1, x._2));
    }
}