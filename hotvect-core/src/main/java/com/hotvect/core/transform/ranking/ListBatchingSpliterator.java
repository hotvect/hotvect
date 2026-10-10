package com.hotvect.core.transform.ranking;

import java.util.List;
import java.util.Spliterator;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;

/**
 * Spliterator that emits list subranges as batches and splits at batch boundaries.
 */
public final class ListBatchingSpliterator<T> implements Spliterator<List<T>> {
    private static final int MIN_BATCH_SIZE = 4;
    private static final int MAX_BATCH_SIZE = 256;
    private static final int BATCHES_PER_THREAD = 3;

    private final List<T> source;
    private final int batchSize;
    private int index;
    private final int fence;

    public ListBatchingSpliterator(List<T> source) {
        this(source, 0, source.size());
    }

    public ListBatchingSpliterator(List<T> source, int batchSize) {
        this(source, 0, source.size(), validateBatchSize(batchSize));
    }

    private ListBatchingSpliterator(List<T> source, int origin, int fence, int batchSize) {
        this.source = source;
        this.index = origin;
        this.fence = fence;
        this.batchSize = batchSize;
    }

    private ListBatchingSpliterator(List<T> source, int origin, int fence) {
        this.source = source;
        this.index = origin;
        this.fence = fence;
        this.batchSize = computeBatchSize(source.size());
    }

    @Override
    public void forEachRemaining(Consumer<? super List<T>> action) {
        while (index < fence) {
            int end = index + Math.min(batchSize, fence - index);
            action.accept(source.subList(index, end));
            index = end;
        }
    }

    @Override
    public boolean tryAdvance(Consumer<? super List<T>> action) {
        if (index >= fence) return false;

        int end = index + Math.min(batchSize, fence - index);
        action.accept(source.subList(index, end));
        index = end;

        return true;
    }

    @Override
    public Spliterator<List<T>> trySplit() {
        int remaining = fence - index;
        long remainingBatches = estimateSize();
        if (remainingBatches <= 1) return null;

        long prefixBatchCount = remainingBatches / 2;
        int prefixSize = (int) Math.min(remaining, prefixBatchCount * batchSize);
        int splitIndex = index + prefixSize;
        int origin = index;
        index = splitIndex;
        return new ListBatchingSpliterator<>(source, origin, splitIndex, batchSize);
    }

    @Override
    public long estimateSize() {
        int remaining = fence - index;
        return remaining / batchSize + (remaining % batchSize == 0 ? 0 : 1);
    }

    @Override
    public int characteristics() {
        return Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED | Spliterator.NONNULL;
    }

    public int computeBatchSize(int listSize) {
        int parallelism = ForkJoinPool.getCommonPoolParallelism();

        int targetBatchCount = parallelism * BATCHES_PER_THREAD;

        int batchSize = listSize / targetBatchCount + (listSize % targetBatchCount == 0 ? 0 : 1);
        if (batchSize < MIN_BATCH_SIZE) return MIN_BATCH_SIZE;

        return Math.min(batchSize, MAX_BATCH_SIZE);
    }

    private static int validateBatchSize(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        return batchSize;
    }
}
