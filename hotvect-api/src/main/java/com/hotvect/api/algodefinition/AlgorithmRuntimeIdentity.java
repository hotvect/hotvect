package com.hotvect.api.algodefinition;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

public record AlgorithmRuntimeIdentity(
        String algorithmName,
        String algorithmVersion,
        String hyperparameterVersion,
        String parameterId) {
    public static final String NO_PARAMETER_ID = "NA";

    public AlgorithmRuntimeIdentity {
        algorithmName = requireNonBlank(algorithmName, "algorithmName");
        algorithmVersion = requireNonBlank(algorithmVersion, "algorithmVersion");
        hyperparameterVersion = normalizeBlankToNull(hyperparameterVersion);
        parameterId = requireNonBlank(parameterId, "parameterId");
    }

    public static AlgorithmRuntimeIdentity from(
            final AlgorithmDefinition algorithmDefinition,
            final AlgorithmParameterMetadata parameterMetadata) {
        Objects.requireNonNull(algorithmDefinition, "algorithmDefinition must not be null");
        final AlgorithmId algorithmId = algorithmDefinition.algorithmId();
        if (parameterMetadata != null
                && !Objects.equals(parameterMetadata.algorithmId().algorithmName(), algorithmId.algorithmName())) {
            throw new IllegalArgumentException(
                    "Parameter metadata algorithm name "
                            + parameterMetadata.algorithmId().algorithmName()
                            + " does not match algorithm definition "
                            + algorithmId.algorithmName());
        }
        return new AlgorithmRuntimeIdentity(
                algorithmId.algorithmName(),
                algorithmId.algorithmVersion(),
                hyperparameterVersionFrom(algorithmDefinition.rawAlgorithmDefinition()),
                parameterMetadata == null ? NO_PARAMETER_ID : parameterMetadata.parameterId());
    }

    public String algorithmId() {
        return algorithmName + "@" + algorithmVersion;
    }

    public String hyperparameterId() {
        if (hyperparameterVersion == null) {
            return algorithmId();
        }
        return algorithmId() + "-" + hyperparameterVersion;
    }

    public String algorithmRuntimeId() {
        return hyperparameterId() + "@" + parameterId;
    }

    private static String hyperparameterVersionFrom(final JsonNode rawAlgorithmDefinition) {
        if (rawAlgorithmDefinition == null) {
            return null;
        }
        final JsonNode node = rawAlgorithmDefinition.get("hyperparameter_version");
        if (node == null || node.isNull()) {
            return null;
        }
        return normalizeBlankToNull(node.asText());
    }

    private static String requireNonBlank(final String value, final String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static String normalizeBlankToNull(final String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
