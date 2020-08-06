package com.eshioji.hotvect.api.data.hashed;

import com.eshioji.hotvect.api.data.SparseVector;
import org.junit.jupiter.api.Test;
import org.quicktheories.api.Pair;

import java.util.Arrays;
import java.util.function.BiConsumer;

import static com.eshioji.hotvect.testutils.TestUtils.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.quicktheories.QuickTheory.qt;
import static org.quicktheories.generators.Generate.constant;
import static org.quicktheories.generators.SourceDSL.doubles;
import static org.quicktheories.generators.SourceDSL.integers;

class HashedValueTest {

    @Test
    void singleCategorical() {
        qt().forAll(integers().all()).checkAssert(x -> {
            var subject = HashedValue.singleCategorical(x);
            assertEquals(x, subject.getSingleCategorical());
            assertThrows(IllegalStateException.class, subject::getSingleNumerical);
            assertArrayEquals(new double[]{1.0}, subject.getNumericals());

            var actualVector = subject.getCategoricalsToNumericals();
            assertEquals(new SparseVector(new int[]{x}, new double[]{1.0}), actualVector);
        });
    }

    @Test
    void singleNumerical() {
        qt().forAll(doubles().any()).checkAssert(x -> {
            var subject = HashedValue.singleNumerical(x);
            assertEquals(x, subject.getSingleNumerical());
            assertThrows(IllegalStateException.class, subject::getSingleCategorical);
            assertArrayEquals(new int[]{0}, subject.getCategoricals());

            var actualVector = subject.getCategoricalsToNumericals();
            assertEquals(new SparseVector(new int[]{0}, new double[]{x}), actualVector);
        });
    }

    @Test
    void categoricals() {
        qt().forAll(categoricalVectors()).checkAssert(x -> {
            var subject = HashedValue.categoricals(x);
            assertArrayEquals(x, subject.getCategoricals());
            assertThrows(IllegalStateException.class, subject::getSingleNumerical);
            if (x.length != 1) {
                assertThrows(IllegalStateException.class, subject::getSingleCategorical);
            }

            var expectedNumericals = new double[x.length];
            Arrays.fill(expectedNumericals, 1.0);
            assertArrayEquals(expectedNumericals, subject.getNumericals());

            var actualVector = subject.getCategoricalsToNumericals();
            assertEquals(new SparseVector(x, expectedNumericals), actualVector);
        });
    }

    @Test
    void numericals() {
        var data = categoricalVectors()
                .mutate((is, rand) -> Pair.of(is, doubleArrays(constant(is.length), doubles().any()).generate(rand)));
        qt().forAll(data).checkAssert(x -> {

            var names = x._1;
            var values = x._2;
            assert names.length == values.length;

            var subject = HashedValue.numericals(names, values);
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

    @Test
    void equality(){
        // Single categorical
        BiConsumer<Integer, Integer> assertSingleCategorical = (x, y) -> {
            var xp = HashedValue.singleCategorical(x);
            var yp = HashedValue.singleCategorical(y);
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
            var xp = HashedValue.singleNumerical(x);
            var yp = HashedValue.singleNumerical(y);
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
            var xp = HashedValue.categoricals(x);
            var yp = HashedValue.categoricals(y);
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
            var xp = HashedValue.numericals(x._1, x._2);
            var yp = HashedValue.numericals(y._1, y._2);

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


    }



}