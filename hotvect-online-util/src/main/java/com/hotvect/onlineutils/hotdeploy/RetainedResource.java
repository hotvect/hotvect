package com.hotvect.onlineutils.hotdeploy;

import java.util.Objects;

/** One explicit owner plus independently closeable leases for a shared resource. */
final class RetainedResource implements AutoCloseable {
    private final AutoCloseable resource;
    private int referenceCount = 1;
    private boolean ownerReleased;
    private boolean closed;

    RetainedResource(AutoCloseable resource) {
        this.resource = Objects.requireNonNull(resource, "resource must not be null");
    }

    synchronized Lease acquire() {
        if (ownerReleased || closed) {
            throw new IllegalStateException("Cannot acquire a lease from a retired resource");
        }
        referenceCount++;
        return new Lease(this);
    }

    @Override
    public void close() throws Exception {
        synchronized (this) {
            if (ownerReleased) {
                return;
            }
            ownerReleased = true;
        }
        release();
    }

    private void release() throws Exception {
        AutoCloseable resourceToClose = null;
        synchronized (this) {
            if (referenceCount <= 0) {
                throw new IllegalStateException("Resource lease was released too many times");
            }
            referenceCount--;
            if (referenceCount == 0) {
                closed = true;
                resourceToClose = resource;
            }
        }
        if (resourceToClose != null) {
            resourceToClose.close();
        }
    }

    static final class Lease implements AutoCloseable {
        private final RetainedResource owner;
        private boolean released;

        private Lease(RetainedResource owner) {
            this.owner = owner;
        }

        @Override
        public void close() throws Exception {
            synchronized (this) {
                if (released) {
                    return;
                }
                released = true;
            }
            owner.release();
        }
    }
}
