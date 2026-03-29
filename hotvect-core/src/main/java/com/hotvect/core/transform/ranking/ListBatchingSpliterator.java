package com.hotvect.core.transform.ranking;

import java.util.List;
import java.util.Spliterator;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;

/**
 * Spliterator that emits list subranges as batches and splits until the remaining size is
 * below {@code batchSize}.
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

    private ListBatchingSpliterator(List<T> source, int origin, int fence) {
        this.source = source;
        this.index = origin;
        this.fence = fence;
        this.batchSize = computeBatchSize(source.size());
    }

    @Override
    public void forEachRemaining(Consumer<? super List<T>> action) {
        while (index < fence) {
            int end = Math.min(index + batchSize, fence);
            action.accept(source.subList(index, end));
            index = end;
        }
    }

    @Override
    public boolean tryAdvance(Consumer<? super List<T>> action) {
        if (index >= fence) return false;
        
        int end = Math.min(index + batchSize, fence);
        action.accept(source.subList(index, end));
        index = end;
        
        return true;
    }

    @Override
    public Spliterator<List<T>> trySplit() {
        int remaining = fence - index;
        if (remaining <= batchSize) return null;
        
        int lo = index, mid = (lo + fence) >>> 1;
        return (lo >= mid) ? null : new ListBatchingSpliterator<>(source, lo, index = mid);
    }

    @Override
    public long estimateSize() {
        return (fence - index + batchSize - 1) / batchSize;
    }

    @Override
    public int characteristics() {
        return Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED | Spliterator.NONNULL;
    }

    public int computeBatchSize(int listSize) {
        int parallelism = ForkJoinPool.getCommonPoolParallelism();

        int targetBatchCount = parallelism * BATCHES_PER_THREAD;

        int batchSize = (listSize + targetBatchCount - 1) / targetBatchCount;
        if (batchSize < MIN_BATCH_SIZE) return MIN_BATCH_SIZE;

        return Math.min(batchSize, MAX_BATCH_SIZE);
    }
}
