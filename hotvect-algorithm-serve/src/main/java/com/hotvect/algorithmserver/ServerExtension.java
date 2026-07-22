package com.hotvect.algorithmserver;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.config.JavalinConfig;

public interface ServerExtension extends AutoCloseable {
    default void configure(JavalinConfig config) {
    }

    default void registerRoutes(AlgorithmServerApp app) {
    }

    default void addMetadata(ObjectNode metadata) {
    }

    default void onStarted(String baseUrl) {
    }

    @Override
    default void close() {
    }
}
