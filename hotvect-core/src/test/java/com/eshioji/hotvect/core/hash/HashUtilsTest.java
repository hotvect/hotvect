package com.eshioji.hotvect.core.hash;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.hashed.HashedValue;
import com.eshioji.hotvect.core.TestFeatureNamespace;
import com.eshioji.hotvect.core.combine.FeatureDefinition;
import com.eshioji.hotvect.testutils.TestUtils;
import com.google.common.hash.Hashing;
import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.junit.jupiter.api.Test;
import org.quicktheories.api.Pair;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.quicktheories.QuickTheory.qt;
import static org.quicktheories.generators.SourceDSL.integers;
import static org.quicktheories.generators.SourceDSL.strings;

class HashUtilsTest {
    private static final int BIT_MASK = -1;

    @Test
    void hashInt() {
        qt().forAll(integers().all()).checkAssert(x -> {
            int actual = HashUtils.hashInt(x);
            int expected = Hashing.murmur3_32().hashInt(x).asInt();
            assertEquals(expected, actual);
        });
    }

    @Test
    void hashUnencodedChars() {
        qt().forAll(strings().allPossible().ofLengthBetween(0, 1000)).checkAssert(x -> {
            int actual = HashUtils.hashUnencodedChars(x);
            int expected = Hashing.murmur3_32().hashUnencodedChars(x).asInt();
            assertEquals(expected, actual);
        });
    }

    @Test
    void emptyHashInteractions() {
        FeatureDefinition<TestFeatureNamespace> fd = new FeatureDefinition<>(EnumSet.of(TestFeatureNamespace.single_categorical_1));
        IntSet acc = new IntOpenHashSet();
        DataRecord<TestFeatureNamespace, HashedValue> record = new DataRecord<TestFeatureNamespace, HashedValue>(TestFeatureNamespace.class);
        HashUtils.construct(BIT_MASK, fd, acc, record);
        assertTrue(acc.isEmpty());
    }

    @Test
    void interactionsWithOneAbsentComponent() {
        FeatureDefinition<TestFeatureNamespace> fd = new FeatureDefinition<>(
                EnumSet.of(
                TestFeatureNamespace.single_categorical_1,
                TestFeatureNamespace.single_string_1));

        IntSet acc = new IntOpenHashSet();
        DataRecord<TestFeatureNamespace, HashedValue> record = new DataRecord<TestFeatureNamespace, HashedValue>(TestFeatureNamespace.class);
        record.put(TestFeatureNamespace.single_categorical_1, HashedValue.singleCategorical(1));
        HashUtils.construct(BIT_MASK, fd, acc, record);
        assertTrue(acc.isEmpty());
    }

    @Test
    void interactionsWIthOneEmptyComponent() {
        FeatureDefinition<TestFeatureNamespace> fd = new FeatureDefinition<>(
                EnumSet.of(
                TestFeatureNamespace.single_categorical_1,
                TestFeatureNamespace.single_string_1));

        IntSet acc = new IntOpenHashSet();
        DataRecord<TestFeatureNamespace, HashedValue> record = new DataRecord<TestFeatureNamespace, HashedValue>(TestFeatureNamespace.class);
        record.put(TestFeatureNamespace.single_categorical_1, HashedValue.singleCategorical(1));
        record.put(TestFeatureNamespace.single_string_1, HashedValue.categoricals(new int[0]));
        HashUtils.construct(BIT_MASK, fd, acc, record);
        assertTrue(acc.isEmpty());
    }



    @Test
    void interactionsWithSingleComponentCollision() {
        int numExamples = 10_000_000;
        FeatureDefinition<TestFeatureNamespace> fd = new FeatureDefinition<>(EnumSet.of(TestFeatureNamespace.single_categorical_1));

        IntUnaryOperator hashFun = i -> {
            IntSet acc = new IntOpenHashSet();
            DataRecord<TestFeatureNamespace, HashedValue> record = new DataRecord<TestFeatureNamespace, HashedValue>(TestFeatureNamespace.class);
            record.put(TestFeatureNamespace.single_categorical_1, HashedValue.singleCategorical(i));
            HashUtils.construct(BIT_MASK, fd, acc, record);
            assertEquals(1, acc.size());
            return acc.iterator().nextInt();
        };

        IntSet hashes = new IntOpenHashSet(numExamples);
        IntStream ints = TestUtils.ints().limit(numExamples);
        ints.map(hashFun).forEach(hashes::add);

        double collisionRate = ((double)numExamples - hashes.size()) / numExamples;
        assertTrue(collisionRate < 0.005);
    }

    @Test
    void interactionsWithTwoSingleComponentCollision() {
        int numExamples = 10_000_000;
        FeatureDefinition<TestFeatureNamespace> fd = new FeatureDefinition<>(EnumSet.of(
                TestFeatureNamespace.categoricals_1,
                TestFeatureNamespace.strings_1));

        IntUnaryOperator hashFun = i -> {
            IntCollection acc = new IntArrayList();
            DataRecord<TestFeatureNamespace, HashedValue> record = new DataRecord<TestFeatureNamespace, HashedValue>(TestFeatureNamespace.class);
            record.put(TestFeatureNamespace.categoricals_1, HashedValue.singleCategorical(i));
            record.put(TestFeatureNamespace.strings_1, HashedValue.singleCategorical(i));
            HashUtils.construct(BIT_MASK, fd, acc, record);
            assertEquals(1, acc.size());
            return acc.iterator().nextInt();
        };

        IntSet hashes = new IntOpenHashSet(numExamples);
        IntStream ints = TestUtils.ints().limit(numExamples);
        ints.map(hashFun).forEach(hashes::add);

        double collisionRate = ((double)numExamples - hashes.size()) / numExamples;
        assertTrue(collisionRate < 0.035);
    }

    @Test
    void interactionsWithTwoMultiComponentCollision() {
        int numExamples = 10_000_000;
        FeatureDefinition<TestFeatureNamespace> fd = new FeatureDefinition<>(EnumSet.of(
                TestFeatureNamespace.categoricals_1,
                TestFeatureNamespace.strings_1));

        LongSet raw = new LongOpenHashSet(numExamples * 3);
        Function<Pair<int[], int[]>, IntCollection> hashFun = xy -> {
            int[] x = xy._1;
            int[] y = xy._2;
            IntCollection acc = new IntArrayList();
            DataRecord<TestFeatureNamespace, HashedValue> record = new DataRecord<TestFeatureNamespace, HashedValue>(TestFeatureNamespace.class);
            record.put(TestFeatureNamespace.categoricals_1, HashedValue.categoricals(x));
            record.put(TestFeatureNamespace.strings_1, HashedValue.categoricals(y));
            HashUtils.construct(BIT_MASK, fd, acc, record);
            assertEquals(x.length * y.length, acc.size());
            for (int xv : x) {
                for (int yv : y) {
                    long concatenated = (((long) xv) << 32) | (yv & 0xffffffffL);
                    raw.add(concatenated);
                }
            }

            return acc;
        };

        IntSet hashes = new IntOpenHashSet(numExamples);
        Stream<Pair<int[], int[]>> testdata = TestUtils.twoIntArrays();
        testdata.limit(numExamples).map(hashFun).forEach(hashes::addAll);

        double collisionRate = ((double)raw.size() - hashes.size()) / raw.size();
        assertTrue(collisionRate < 0.035);
    }

    @Test
    void consistencyOfInteractionHashes() {
        int numExamples = 100_000;
        FeatureDefinition<TestFeatureNamespace> fd = new FeatureDefinition<>(EnumSet.of(
                TestFeatureNamespace.categoricals_1,
                TestFeatureNamespace.strings_1));

        Map<String, IntList> concatenatedToHash = new HashMap<>();

        AtomicInteger sanityCheck = new AtomicInteger();

        Consumer<Pair<int[], int[]>> hashFun = xy -> {
            int[] x = xy._1;
            int[] y = xy._2;

            String concatenated = Arrays.toString(x) + "^" + Arrays.toString(y);

            IntList acc = new IntArrayList();
            DataRecord<TestFeatureNamespace, HashedValue> record = new DataRecord<TestFeatureNamespace, HashedValue>(TestFeatureNamespace.class);

            TestUtils.shuffleArray(x);
            TestUtils.shuffleArray(y);

            record.put(TestFeatureNamespace.categoricals_1, HashedValue.categoricals(x));
            record.put(TestFeatureNamespace.strings_1, HashedValue.categoricals(y));
            HashUtils.construct(BIT_MASK, fd, acc, record);
            assertEquals(x.length * y.length, acc.size());

            acc.sort(Integer::compare);

            concatenatedToHash.merge(concatenated, acc, (previous, current) -> {
                assertEquals(previous, current);
                sanityCheck.incrementAndGet();
                return current;
            });
        };

        // First pass
        TestUtils.twoIntArrays(0).limit(numExamples).forEach(hashFun);

        // Second pass
        TestUtils.twoIntArrays(0).limit(numExamples).forEach(hashFun);

        assertTrue(numExamples <= sanityCheck.get());

    }
}