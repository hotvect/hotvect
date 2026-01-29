package com.hotvect.core.transform;

public class MemoizedValue<FUN, OUT> {
    private final FUN computation;
    private volatile Holder<OUT> cache;

    public MemoizedValue(FUN computations) {
        this.computation = computations;
    }

    public FUN getComputation() {
        return computation;
    }

    public Holder<OUT> getCache() {
        return cache;
    }

    public void setCache(Holder<OUT> cache) {
        this.cache = cache;
    }
}
