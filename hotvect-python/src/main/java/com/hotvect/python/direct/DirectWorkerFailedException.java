package com.hotvect.python.direct;

/**
 * Signals that a direct python worker process/protocol failed.
 * <p>
 * This exception is used by {@link DirectWorkerManager} to decide whether a request should be retried.
 */
public final class DirectWorkerFailedException extends RuntimeException {
    public DirectWorkerFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
