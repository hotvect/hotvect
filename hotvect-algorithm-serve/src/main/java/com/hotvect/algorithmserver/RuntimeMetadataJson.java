package com.hotvect.algorithmserver;

import com.fasterxml.jackson.databind.node.ObjectNode;

final class RuntimeMetadataJson {
    private RuntimeMetadataJson() {
    }

    static void addRuntimes(ObjectNode root, Iterable<AlgorithmRuntime> runtimes) {
        var runtimesNode = root.putArray("runtimes");
        for (AlgorithmRuntime runtime : runtimes) {
            ObjectNode runtimeNode = runtimesNode.addObject();
            AlgorithmServerApp.addRuntimeMetadata(runtimeNode, runtime);
        }
    }
}
