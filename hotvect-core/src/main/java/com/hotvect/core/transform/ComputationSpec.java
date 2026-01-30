package com.hotvect.core.transform;

/**
 * Specifies the computation strategy for the registered computation.
 */
public enum ComputationSpec {
    /**
     * Result of computation is memoized (cached). This is advantageous if the computation is expensive. However, it
     * introduces additional inter-thread synchronization, and thus should be used sparingly.
     * The computation is executed lazily (at a later stage as possible, and typically using computation optimized
     * threads like a ForkJoinPool).
     */
    LAZY_MEMOIZED,

    /**
     * Result of computation is not memoized. This is advantageous if the computation is cheap, as it avoids the
     * synchronization overhead introduced by memoization (caching). The computation is executed lazily
     * (at a later stage as possible, and typically using computation optimized threads like a ForkJoinPool).
     */
    LAZY_ON_DEMAND,

    /**
     * The computation is pre-executed (so that the result of the computation is stored directly as a value at the
     * time the algorithm is instantiated).
     * As a result, the computation cannot depend on any request inputs.
     */
    PRECOMPUTED
}
