package com.hotvect.core.transform.ranking;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Spliterator;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListBatchingSpliteratorTest {
    @Test
    void batchesListUsingExplicitBatchSize() {
        ListBatchingSpliterator<Integer> spliterator =
                new ListBatchingSpliterator<>(List.of(0, 1, 2, 3, 4, 5, 6), 3);
        List<List<Integer>> batches = new ArrayList<>();

        assertEquals(3, spliterator.estimateSize());
        assertTrue(spliterator.tryAdvance(batches::add));
        assertEquals(List.of(0, 1, 2), batches.get(0));
        assertEquals(2, spliterator.estimateSize());

        spliterator.forEachRemaining(batches::add);

        assertEquals(List.of(
                List.of(0, 1, 2),
                List.of(3, 4, 5),
                List.of(6)
        ), batches);
        assertEquals(0, spliterator.estimateSize());
        assertFalse(spliterator.tryAdvance(batches::add));
    }

    @Test
    void rejectsNonPositiveExplicitBatchSize() {
        assertThrows(IllegalArgumentException.class, () -> new ListBatchingSpliterator<>(List.of(1), 0));
        assertThrows(IllegalArgumentException.class, () -> new ListBatchingSpliterator<>(List.of(1), -1));
    }

    @Test
    void trySplitReturnsPrefixAndKeepsBatchSize() {
        List<Integer> source = IntStream.range(0, 10).boxed().toList();
        ListBatchingSpliterator<Integer> spliterator = new ListBatchingSpliterator<>(source, 4);

        Spliterator<List<Integer>> prefix = spliterator.trySplit();
        assertEquals(3, prefix.estimateSize() + spliterator.estimateSize());
        assertEquals(List.of(List.of(0, 1, 2, 3)), prefixToList(prefix));
        assertEquals(List.of(List.of(4, 5, 6, 7), List.of(8, 9)), prefixToList(spliterator));
    }

    @Test
    void repeatedSplitsPreserveBatchBoundariesAndEstimates() {
        ListBatchingSpliterator<Integer> spliterator =
                new ListBatchingSpliterator<>(IntStream.range(0, 25).boxed().toList(), 4);

        assertEquals(
                List.of(
                        List.of(0, 1, 2, 3),
                        List.of(4, 5, 6, 7),
                        List.of(8, 9, 10, 11),
                        List.of(12, 13, 14, 15),
                        List.of(16, 17, 18, 19),
                        List.of(20, 21, 22, 23),
                        List.of(24)
                ),
                collectBySplitting(spliterator)
        );
    }

    @Test
    void splitsAfterPartialConsumptionAtRemainingBatchBoundary() {
        ListBatchingSpliterator<Integer> spliterator =
                new ListBatchingSpliterator<>(IntStream.range(0, 10).boxed().toList(), 4);
        assertTrue(spliterator.tryAdvance(batch -> assertEquals(List.of(0, 1, 2, 3), batch)));

        Spliterator<List<Integer>> prefix = spliterator.trySplit();

        assertEquals(List.of(List.of(4, 5, 6, 7)), prefixToList(prefix));
        assertEquals(List.of(List.of(8, 9)), prefixToList(spliterator));
    }

    @Test
    void parallelCollectionPreservesSequentialBatches() {
        List<Integer> source = IntStream.range(0, 10).boxed().toList();
        List<List<Integer>> sequential = StreamSupport
                .stream(new ListBatchingSpliterator<>(source), false)
                .toList();

        List<List<Integer>> parallel = StreamSupport
                .stream(new ListBatchingSpliterator<>(source), true)
                .toList();

        assertEquals(sequential, parallel);
    }

    @Test
    void defaultConstructorComputesBatchSizeWithinConfiguredBounds() {
        ListBatchingSpliterator<Integer> spliterator = new ListBatchingSpliterator<>(List.of(1, 2, 3));

        int batchSize = spliterator.computeBatchSize(3);
        assertEquals(4, batchSize);
        int targetBatchCount = ForkJoinPool.getCommonPoolParallelism() * 3;
        assertEquals(256, spliterator.computeBatchSize(256 * targetBatchCount + 1));
        assertEquals(List.of(List.of(1, 2, 3)), prefixToList(spliterator));
    }

    @Test
    void exposesExpectedCharacteristics() {
        ListBatchingSpliterator<Integer> spliterator = new ListBatchingSpliterator<>(List.of(1), 1);

        assertEquals(
                Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED | Spliterator.NONNULL,
                spliterator.characteristics()
        );
    }

    private static <T> List<List<T>> prefixToList(Spliterator<List<T>> spliterator) {
        List<List<T>> batches = new ArrayList<>();
        if (spliterator != null) {
            spliterator.forEachRemaining(batches::add);
        }
        return batches;
    }

    private static <T> List<List<T>> collectBySplitting(Spliterator<List<T>> spliterator) {
        long before = spliterator.estimateSize();
        Spliterator<List<T>> prefix = spliterator.trySplit();
        if (prefix == null) {
            return prefixToList(spliterator);
        }

        assertEquals(before, prefix.estimateSize() + spliterator.estimateSize());
        List<List<T>> batches = new ArrayList<>(collectBySplitting(prefix));
        batches.addAll(collectBySplitting(spliterator));
        return batches;
    }
}
