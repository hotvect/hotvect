package com.hotvect.onlineutils.util;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * A thread-safe, generic reservoir that keeps <em>k</em> samples from a stream of
 * objects using Vitter’s Algorithm R (reservoir sampling).
 * (Based on https://github.com/dropwizard/metrics/blob/v5.0.0-rc25/metrics-core/src/main/java/io/dropwizard/metrics5/UniformReservoir.java)
 *
 * @param <V> the element type that will be stored in the reservoir
 */
public final class UniformReservoir<V> {

    /** Total number of elements that have ever been fed into {@link #update(Object)}. */
    private final AtomicLong count = new AtomicLong();

    /** The actual reservoir; slots 0‥(k-1). */
    private final AtomicReferenceArray<V> values;

    /**
     * Creates a reservoir with the given capacity {@code size}.
     *
     * @param size must be positive
     * @throws IllegalArgumentException if {@code size <= 0}
     */
    public UniformReservoir(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive");
        }
        this.values = new AtomicReferenceArray<>(size);
    }

    /** Returns the number of <em>valid</em> elements currently held in the reservoir. */
    public int size() {
        long c = count.get();
        return c > values.length() ? values.length() : (int) c;
    }

    /** Feeds one more element into the sampler (may be called concurrently). */
    public void update(V value) {

        long c = count.incrementAndGet();

        if (c <= values.length()) {
            values.set((int) c - 1, value);
        } else {
            long r = ThreadLocalRandom.current().nextLong(c);
            if (r < values.length()) {
                values.set((int) r, value);
            }
        }
    }

    /**
     * Returns a snapshot (shallow copy) of the current reservoir
     * contents. The snapshot is consistent with respect to a single point in
     * time, but the reservoir may keep evolving after the call returns.
     */
    public List<V> getSnapshot() {
        int s = size();
        List<V> copy = new ArrayList<>(s);
        for (int i = 0; i < s; i++) {
            copy.add(values.get(i));
        }
        return copy;
    }
}