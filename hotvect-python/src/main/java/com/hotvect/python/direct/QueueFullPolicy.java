package com.hotvect.python.direct;

public enum QueueFullPolicy {
    /**
     * Fail fast when the submission queue is full.
     */
    REJECT,
    /**
     * Block the caller while waiting for queue capacity.
     */
    CALLER_BLOCKS
}
