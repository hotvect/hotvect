package com.hotvect.onlineutils.serving;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetainedResourceTest {
    @Test
    void concurrentFinalReleasesCloseOnceAndCannotReviveTheOwner() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        RetainedResource<AutoCloseable> owner = new RetainedResource<>(
                closes::incrementAndGet, "test resource", "test-resource-cleanup", failure -> {});
        var first = owner.tryAcquire();
        var second = owner.tryAcquire();
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstClose = executor.submit(() -> {
                release.await();
                first.close();
                first.close();
                return null;
            });
            var secondClose = executor.submit(() -> {
                release.await();
                second.close();
                return null;
            });
            owner.release();
            release.countDown();
            firstClose.get(10, TimeUnit.SECONDS);
            secondClose.get(10, TimeUnit.SECONDS);
            owner.whenClosed().get(10, TimeUnit.SECONDS);
        }
        assertEquals(1, closes.get());
        assertNull(owner.tryAcquire());
        assertThrows(IllegalStateException.class, owner::release);
    }

    @Test
    void completionWaitsForTheLastLeaseAndTheEndOfCleanup() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RetainedResource<AutoCloseable> owner = new RetainedResource<>(() -> {
            started.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
        }, "test resource", "test-resource-cleanup", failure -> {});
        RetainedResource.Lease<AutoCloseable> lease = owner.tryAcquire();

        try {
            owner.release();
            assertFalse(owner.whenClosed().isDone());
            lease.close();
            assertTrue(started.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> owner.whenClosed().get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            owner.whenClosed().get(10, TimeUnit.SECONDS);
            assertTrue(owner.whenClosed().isDone());
        } finally {
            lease.close();
            release.countDown();
        }
    }

    @Test
    void completionSurfacesTheOriginalCleanupFailureAfterReportingIt() {
        IllegalStateException expected = new IllegalStateException("cleanup failed");
        AtomicReference<Throwable> reported = new AtomicReference<>();
        RetainedResource<AutoCloseable> owner = new RetainedResource<>(() -> {
            throw expected;
        }, "test resource", "test-resource-cleanup", reported::set);

        owner.release();

        assertSame(expected, assertThrows(CompletionException.class, () -> owner.whenClosed().join()).getCause());
        assertSame(expected, reported.get());
    }
}
