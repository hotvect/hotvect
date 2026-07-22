package com.hotvect.algorithmserver;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Optional;

final class LocalAlgorithmRuntimeProvider implements AlgorithmRuntimeProvider {
    private final LocalRuntimeCatalog runtimeCatalog;

    LocalAlgorithmRuntimeProvider(ServerOptions opts) throws Exception {
        this.runtimeCatalog = LocalRuntimeCatalog.create(opts);
    }

    @Override
    public RuntimeSelection selectRuntime(String algorithmRuntimeIdOrNull) {
        LocalRuntimeCatalog.LoadedLocalRuntime runtime = runtimeCatalog.selectOrDefault(algorithmRuntimeIdOrNull);
        return new RuntimeSelection(runtime.runtime(), Optional.empty());
    }

    @Override
    public void addMetadata(ObjectNode root, RuntimeSelection selection) {
        AlgorithmServerApp.addRuntimesMetadata(root, runtimeCatalog.runtimes());
    }

    @Override
    public void close() {
        runtimeCatalog.close();
    }
}
