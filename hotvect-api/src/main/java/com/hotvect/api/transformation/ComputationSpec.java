package com.hotvect.api.transformation;

/**
 * Specifies the computation strategy for the registered computation.
 */
@Deprecated(forRemoval = true)
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
     * Result of computation is memoized (cached). This is advantageous if the computation is expensive.
     * The computation is executed eagerly (as soon as possible, always using the initial caller thread that invoked
     * the algorithm's entry point). If lazy computations are a dependency of eager computations, they effectively
     * become eager as well.
     */
    EAGER_MEMOIZED,

    // EAGER_ON_DEMAND is missing because it does not make sense (it would make most computations run on the single,
    // caller thread).

    /**
     * The computation is pre-executed (so that the result of the computation is stored directly as a value at the
     * time the algorithm is instantiated).
     * As a result, the computation cannot depend on any request inputs.
     */
    PRECOMPUTED
}
