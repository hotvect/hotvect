package com.hotvect.serve;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmParameterMetadata;
import com.hotvect.api.algodefinition.AlgorithmRuntimeId;
import com.hotvect.api.algodefinition.ParameterizedAlgorithmId;

final class RuntimeMetadataJson {
    private RuntimeMetadataJson() {
    }

    static void addRuntime(ObjectNode root, AlgorithmRuntime runtime) {
        AlgorithmDefinition definition = runtime.getAlgorithmDefinition();
        AlgorithmRuntimeId identity = runtime.identity();
        ParameterizedAlgorithmId algorithm = identity.algorithm();
        root.put("algorithm_runtime_id", identity.value());

        ObjectNode algorithmNode = root.putObject("algorithm");
        algorithmNode.put("name", algorithm.algorithmId().algorithmName());
        algorithmNode.put("version", algorithm.algorithmId().algorithmVersion());
        JsonFieldSupport.putStringOrNull(
                algorithmNode,
                "hyperparameter_version",
                algorithm.hyperparameterizedAlgorithmId().hyperparameterVersion());
        algorithmNode.put("algorithm_id", algorithm.algorithmId().value());
        algorithmNode.put("hyperparameter_id", algorithm.hyperparameterizedAlgorithmId().value());
        JsonFieldSupport.putStringOrNull(
                algorithmNode,
                "git_describe",
                JsonFieldSupport.textFieldOrNull(definition.rawAlgorithmDefinition(), "git_describe"));
        JsonFieldSupport.putStringOrNull(algorithmNode, "hotvect_version", runtime.getHotvectVersionFromMavenOrNull());

        AlgorithmParameterMetadata parameters = runtime.getAlgorithmParameterMetadataOrNull();
        if (parameters == null) {
            root.putNull("parameters");
            return;
        }

        ObjectNode parametersNode = root.putObject("parameters");
        parametersNode.put("parameter_id", algorithm.parameterId());
        parametersNode.put("ran_at", parameters.ranAt().toString());
        if (parameters.lastTestTime().isPresent()) {
            parametersNode.put("last_test_time", parameters.lastTestTime().get().toString());
        } else {
            parametersNode.putNull("last_test_time");
        }
    }

    static void addRuntimes(ObjectNode root, Iterable<AlgorithmRuntime> runtimes) {
        var runtimesNode = root.putArray("runtimes");
        for (AlgorithmRuntime runtime : runtimes) {
            ObjectNode runtimeNode = runtimesNode.addObject();
            addRuntime(runtimeNode, runtime);
        }
    }
}
