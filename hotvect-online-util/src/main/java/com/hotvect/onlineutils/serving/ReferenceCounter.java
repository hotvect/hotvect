package com.hotvect.onlineutils.serving;

import java.util.concurrent.atomic.AtomicInteger;

/** Starts with one reference; zero is terminal and elects exactly one final releaser. */
final class ReferenceCounter {
    private final AtomicInteger references = new AtomicInteger(1);

    boolean tryRetain() {
        while (true) {
            int observed = references.get();
            if (observed == 0) {
                return false;
            }
            if (observed == Integer.MAX_VALUE) {
                throw new IllegalStateException("Resource has too many active leases");
            }
            if (references.compareAndSet(observed, observed + 1)) {
                return true;
            }
        }
    }

    boolean release() {
        while (true) {
            int observed = references.get();
            if (observed == 0) {
                throw new IllegalStateException("Resource was released too many times");
            }
            if (references.compareAndSet(observed, observed - 1)) {
                return observed == 1;
            }
        }
    }
}
