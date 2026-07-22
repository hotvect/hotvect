package com.hotvect.algorithmserver;

import com.fasterxml.jackson.databind.node.ObjectNode;

interface AlgorithmRuntimeProvider extends AutoCloseable {
    default RuntimeSelection selectRuntime() {
        return selectRuntime(null);
    }

    /**
     * Selects the runtime that should handle the current request.
     *
     * <p>{@code algorithmRuntimeIdOrNull} is only meaningful for providers that expose multiple local runtimes.
     * EMS-backed providers reject explicit runtime selection.</p>
     */
    RuntimeSelection selectRuntime(String algorithmRuntimeIdOrNull);

    void addMetadata(ObjectNode root, RuntimeSelection selection);

    @Override
    void close();
}
