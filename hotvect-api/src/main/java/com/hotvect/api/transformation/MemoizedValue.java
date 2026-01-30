package com.hotvect.api.transformation;

@Deprecated(forRemoval = true)
class MemoizedValue<FUN, OUT> {
    private final FUN computation;
    private volatile Holder<OUT> cache;

    MemoizedValue(FUN computations) {
        this.computation = computations;
    }

    FUN getComputation() {
        return computation;
    }

    Holder<OUT> getCache() {
        return cache;
    }

    void setCache(Holder<OUT> cache) {
        this.cache = cache;
    }
}
