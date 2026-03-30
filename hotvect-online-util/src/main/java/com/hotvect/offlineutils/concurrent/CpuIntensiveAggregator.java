package com.hotvect.offlineutils.concurrent;

import com.codahale.metrics.MetricRegistry;

import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Backwards-compatible shim for older algorithm JARs that depend on the pre-v10 package name.
 *
 * This class intentionally ignores the Codahale MetricRegistry and delegates to the current
 * Micrometer-based implementation.
 */
public class CpuIntensiveAggregator<Z, X> {
    private final com.hotvect.onlineutils.concurrency.CpuIntensiveAggregator<Z, X> delegate;

    public CpuIntensiveAggregator(MetricRegistry metricRegistry, Supplier<Z> init, BiFunction<Z, X, Z> merger) {
        this.delegate = new com.hotvect.onlineutils.concurrency.CpuIntensiveAggregator<>(init, merger);
    }

    public Z aggregate(Stream<X> input) {
        return delegate.aggregate(input);
    }
}
