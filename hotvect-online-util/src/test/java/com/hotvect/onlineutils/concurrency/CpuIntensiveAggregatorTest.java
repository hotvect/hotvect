package com.hotvect.onlineutils.concurrency;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.util.concurrent.Uninterruptibles;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class CpuIntensiveAggregatorTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void interruptionWaitsForInFlightAlgorithmCalls() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        CpuIntensiveAggregator<Integer, Integer> aggregator = new CpuIntensiveAggregator<>(
                registry, () -> 0, (state, record) -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException error) {
                        interrupted.countDown();
                        Uninterruptibles.awaitUninterruptibly(release);
                    }
                    return state;
                }, 1, 1, 1);
        Thread task = Thread.ofVirtual().start(() -> {
            try {
                aggregator.aggregate(Stream.of(1));
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                interruptPreserved.set(Thread.currentThread().isInterrupted());
                finished.countDown();
            }
        });
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            task.interrupt();
            assertTrue(interrupted.await(10, TimeUnit.SECONDS));
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue(finished.await(10, TimeUnit.SECONDS));
            assertInstanceOf(InterruptedException.class, failure.get().getCause());
            assertTrue(interruptPreserved.get());
        } finally {
            release.countDown();
            task.join(TimeUnit.SECONDS.toMillis(10));
        }
    }

    @Test
    void inputFailureShutsDownTheExecutor() {
        CpuIntensiveAggregator<Integer, Integer> aggregator = new CpuIntensiveAggregator<>(
                registry, () -> 0, (state, record) -> state, 1, 1, 1);
        IllegalArgumentException expected = new IllegalArgumentException("Invalid input");
        assertSame(expected, assertThrows(IllegalArgumentException.class, () -> aggregator.aggregate(
                Stream.generate(() -> { throw expected; }))));
        assertThrows(IllegalStateException.class, () -> aggregator.aggregate(Stream.of(1)));
    }
}
