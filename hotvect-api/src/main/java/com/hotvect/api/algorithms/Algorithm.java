package com.hotvect.api.algorithms;

public interface Algorithm extends AutoCloseable {
    @Override
    default void close() throws Exception {
        // Nothing to do
    }
}
