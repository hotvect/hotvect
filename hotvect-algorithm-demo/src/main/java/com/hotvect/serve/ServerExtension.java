package com.hotvect.serve;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface ServerExtension extends AutoCloseable {
    default void initialize(ServeApplication runtime) {
    }

    default void addMetadata(ObjectNode metadata) {
    }

    default void onStarted(String baseUrl) {
    }

    @Override
    default void close() {
    }
}
