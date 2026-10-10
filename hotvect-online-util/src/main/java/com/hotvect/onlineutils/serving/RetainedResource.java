package com.hotvect.onlineutils.serving;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One publication reference plus independently closeable invocation leases. */
final class RetainedResource<RESOURCE extends AutoCloseable> {
    private static final Logger LOG = LoggerFactory.getLogger(RetainedResource.class);

    private final RESOURCE resource;
    private final ReferenceCounter references = new ReferenceCounter();
    private final String description;
    private final String cleanupThreadName;
    private final Consumer<Throwable> failureHandler;
    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    RetainedResource(
            RESOURCE resource,
            String description,
            String cleanupThreadName,
            Consumer<Throwable> failureHandler) {
        this.resource = Objects.requireNonNull(resource, "resource must not be null");
        this.description = Objects.requireNonNull(description, "description must not be null");
        this.cleanupThreadName = Objects.requireNonNull(cleanupThreadName, "cleanupThreadName must not be null");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler must not be null");
    }

    Lease<RESOURCE> tryAcquire() {
        return references.tryRetain() ? new Lease<>(this) : null;
    }

    RESOURCE resource() {
        return resource;
    }

    CompletableFuture<Void> whenClosed() {
        return completion;
    }

    void release() {
        if (references.release()) {
            scheduleCleanup();
        }
    }

    static final class Lease<RESOURCE extends AutoCloseable> implements AutoCloseable {
        private final RetainedResource<RESOURCE> owner;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(RetainedResource<RESOURCE> owner) {
            this.owner = owner;
        }

        RESOURCE resource() {
            if (released.get()) {
                throw new IllegalStateException("Resource lease is closed");
            }
            return owner.resource;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                owner.release();
            }
        }
    }

    private void scheduleCleanup() {
        try {
            Thread.ofVirtual()
                    .name(cleanupThreadName)
                    .start(this::closeResource);
        } catch (Throwable failure) {
            LOG.error("Failed to schedule cleanup for {}", description, failure);
            reportFailure(failure);
            completion.completeExceptionally(failure);
            rethrow(failure);
        }
    }

    private void closeResource() {
        try {
            resource.close();
            LOG.info("Closed {}", description);
            completion.complete(null);
        } catch (Throwable failure) {
            LOG.error("Failed to close {}", description, failure);
            reportFailure(failure);
            completion.completeExceptionally(failure);
        }
    }

    private void reportFailure(Throwable failure) {
        try {
            failureHandler.accept(failure);
        } catch (Throwable handlerFailure) {
            failure.addSuppressed(handlerFailure);
            LOG.error("Failed to handle cleanup failure for {}", description, handlerFailure);
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new RuntimeException(failure);
    }
}
