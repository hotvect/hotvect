package com.hotvect.onlineutils.util;

/** Closes every owned resource while preserving the first failure and suppressing later failures. */
public final class Closeables {
    private Closeables() {
    }

    /** Closes every resource and throws the first close failure after all resources were attempted. */
    public static void closeAll(String checkedFailureMessage, AutoCloseable... resources) {
        Throwable failure = close(null, resources);
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new RuntimeException(checkedFailureMessage, failure);
        }
    }

    /** Closes every resource and suppresses close failures onto an existing operation failure. */
    public static void closeAfterFailure(Throwable failure, AutoCloseable... resources) {
        close(java.util.Objects.requireNonNull(failure, "failure must not be null"), resources);
    }

    private static Throwable close(Throwable failure, AutoCloseable... resources) {
        Throwable accumulated = failure;
        for (AutoCloseable resource : resources) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Throwable closeFailure) {
                if (accumulated == null) {
                    accumulated = closeFailure;
                } else {
                    accumulated.addSuppressed(closeFailure);
                }
            }
        }
        return accumulated;
    }
}
