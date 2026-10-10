package com.hotvect.onlineutils.experimentmanagement.experimentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmRuntimeId;

/** Enforces the committed-definition boundary for algorithms selected by live EMS. */
public final class EmsAlgorithmDefinitionValidator {
    private EmsAlgorithmDefinitionValidator() {
    }

    /**
     * Rejects offline-only hyperparameter identities anywhere in a live EMS algorithm graph.
     *
     * @param runtimeId identity of the resolved algorithm graph selected for online serving
     */
    public static void validateGraph(AlgorithmRuntimeId runtimeId) {
        String hyperparameterVersion = runtimeId.algorithm()
                .hyperparameterizedAlgorithmId()
                .hyperparameterVersion();
        if (hyperparameterVersion != null && !hyperparameterVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "Live EMS algorithm " + runtimeId.algorithm().algorithmId().value()
                            + " declares offline-only hyperparameter_version "
                            + hyperparameterVersion
                            + "; commit the effective definition and publish a new algorithm version before online use");
        }
        runtimeId.dependencies().values().forEach(EmsAlgorithmDefinitionValidator::validateGraph);
    }

    /** Validates one selected definition before a live serving snapshot is constructed. */
    public static void validateDefinition(AlgorithmDefinition algorithmDefinition) {
        validateDefinitionValue(algorithmDefinition);
    }

    private static void validateDefinitionValue(AlgorithmDefinition definition) {
        JsonNode hyperparameterVersion = definition.rawAlgorithmDefinition().get("hyperparameter_version");
        if (hyperparameterVersion != null
                && !hyperparameterVersion.isNull()
                && !hyperparameterVersion.asText().isBlank()) {
            throw new IllegalArgumentException(
                    "Live EMS algorithm " + definition.algorithmId().algorithmName()
                            + "@" + definition.algorithmId().algorithmVersion()
                            + " declares offline-only hyperparameter_version "
                            + hyperparameterVersion.asText()
                            + "; commit the effective definition and publish a new algorithm version before online use");
        }
    }
}
