package com.eshioji.hotvect.api.data.hashed;

import com.eshioji.hotvect.api.data.SparseVector;
import org.junit.jupiter.api.Test;
import org.quicktheories.api.Pair;
import org.quicktheories.core.Gen;

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
            HashedValue subject = HashedValue.singleCategorical(x);
            assertEquals(x, subject.getSingleCategorical());
            assertThrows(IllegalStateException.class, subject::getSingleNumerical);
            assertThrows(IllegalStateException.class, subject::getNumericalValues);

            SparseVector actualVector = subject.asSparseVector();
            assertEquals(new SparseVector(new int[]{x}), actualVector);
        });
    }

    @Test
    void singleNumerical() {
        qt().forAll(doubles().any()).checkAssert(x -> {
            HashedValue subject = HashedValue.singleNumerical(x);
            assertEquals(x, subject.getSingleNumerical());
            assertThrows(IllegalStateException.class, subject::getSingleCategorical);
            assertArrayEquals(new int[]{0}, subject.getNumericalIndices());

            SparseVector actualVector = subject.asSparseVector();
            assertEquals(new SparseVector(new int[]{0}, new double[]{x}), actualVector);
        });
    }

    @Test
    void categoricals() {
        qt().forAll(categoricalVectors()).checkAssert(x -> {
            HashedValue subject = HashedValue.categoricals(x);
            assertArrayEquals(x, subject.getCategoricalIndices());
            assertThrows(IllegalStateException.class, subject::getSingleNumerical);
            if (x.length != 1) {
                assertThrows(IllegalStateException.class, subject::getSingleCategorical);
            }

            assertThrows(IllegalStateException.class, subject::getNumericalValues);

            SparseVector actualVector = subject.asSparseVector();
            assertEquals(new SparseVector(x), actualVector);
        });
    }

    @Test
    void numericals() {
        Gen<Pair<int[], double[]>> data = categoricalVectors()
                .mutate((is, rand) -> Pair.of(is, doubleArrays(constant(is.length), doubles().any()).generate(rand)));
        qt().forAll(data).checkAssert(x -> {

            int[] names = x._1;
            double[] values = x._2;
            assert names.length == values.length;

            HashedValue subject = HashedValue.numericals(names, values);
            assertArrayEquals(names, subject.getNumericalIndices());
            assertThrows(IllegalStateException.class, subject::getSingleCategorical);
            if (names.length != 1) {
                assertThrows(IllegalStateException.class, subject::getSingleNumerical);
            }

            assertArrayEquals(values, subject.getNumericalValues());

            SparseVector actualVector = subject.asSparseVector();
            var expected = new SparseVector(names, values);
            assertEquals(expected, actualVector);

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
            HashedValue xp = HashedValue.singleCategorical(x);
            HashedValue yp = HashedValue.singleCategorical(y);
            assertEquals(x.equals(y), xp.equals(yp));
            if (x.equals(y)){
                assertEquals(xp.hashCode(), yp.hashCode());
            }
        };

        qt().forAll(integers().all(), integers().all())
                .assuming((x, y) -> !x.equals(y))
                .checkAssert(assertSingleCategorical);

        Gen<Pair<Integer, Integer>> equalInts = generateEquals(integers().all());
        qt().forAll(equalInts)
                .checkAssert(x -> assertSingleCategorical.accept(x._1, x._2));

        // Single numerical
        BiConsumer<Double, Double> assertSingleNumerical = (x, y) -> {
            HashedValue xp = HashedValue.singleNumerical(x);
            HashedValue yp = HashedValue.singleNumerical(y);
            assertEquals(x.equals(y), xp.equals(yp));
            if (x.equals(y)){
                assertEquals(xp.hashCode(), yp.hashCode());
            }
        };

        qt().forAll(discreteDoubles(), discreteDoubles())
                .assuming((x, y) -> !x.equals(y))
                .checkAssert(assertSingleNumerical);

        Gen<Pair<Double, Double>> equalDoubles = generateEquals(discreteDoubles());
        qt().forAll(equalDoubles)
                .checkAssert(x -> assertSingleNumerical.accept(x._1, x._2));


        // Categoricals
        BiConsumer<int[], int[]> assertCategoricals = (x, y) -> {
            HashedValue xp = HashedValue.categoricals(x);
            HashedValue yp = HashedValue.categoricals(y);
            assertEquals(Arrays.equals(x, y), xp.equals(yp));
            if (Arrays.equals(x, y)){
                assertEquals(xp.hashCode(), yp.hashCode());
            }
        };

        qt().forAll(categoricalVectors(), categoricalVectors())
                .assuming((x, y) -> !Arrays.equals(x, y))
                .checkAssert(assertCategoricals);

        Gen<Pair<int[], int[]>> generateEquals = generateEquals(categoricalVectors());
        qt().forAll(generateEquals)
                .checkAssert(x -> assertCategoricals.accept(x._1, x._2));

        // Numericals
        Gen<Pair<int[], double[]>> data = testVectors();
        BiConsumer<Pair<int[], double[]>, Pair<int[], double[]>> assertNumericals = (x, y) -> {
            HashedValue xp = HashedValue.numericals(x._1, x._2);
            HashedValue yp = HashedValue.numericals(y._1, y._2);

            boolean equal = Arrays.equals(x._1, y._1) && Arrays.equals(x._2, y._2);
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