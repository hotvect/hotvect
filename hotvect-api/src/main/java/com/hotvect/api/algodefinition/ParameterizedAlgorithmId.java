package com.hotvect.api.algodefinition;

import java.util.Objects;

/** Identifies one algorithm's code, effective hyperparameters, and parameters. */
public record ParameterizedAlgorithmId(
        HyperparameterizedAlgorithmId hyperparameterizedAlgorithmId,
        String parameterId) {
    public static final String NO_PARAMETER_ID = "NA";

    public ParameterizedAlgorithmId {
        Objects.requireNonNull(
                hyperparameterizedAlgorithmId,
                "hyperparameterizedAlgorithmId must not be null");
        parameterId = requireNonBlank(parameterId, "parameterId");
    }

    /** Derives the identity from the effective definition and optional parameter metadata. */
    public static ParameterizedAlgorithmId from(
            AlgorithmDefinition algorithmDefinition,
            AlgorithmParameterMetadata parameterMetadata) {
        Objects.requireNonNull(algorithmDefinition, "algorithmDefinition must not be null");
        AlgorithmId algorithmId = algorithmDefinition.algorithmId();
        if (parameterMetadata != null
                && !parameterMetadata.algorithmId().algorithmName().equals(algorithmId.algorithmName())) {
            throw new IllegalArgumentException(
                    "Parameter metadata algorithm name "
                            + parameterMetadata.algorithmId().algorithmName()
                            + " does not match algorithm definition "
                            + algorithmId.algorithmName());
        }
        return new ParameterizedAlgorithmId(
                algorithmDefinition.hyperparameterizedAlgorithmId(),
                parameterMetadata == null ? NO_PARAMETER_ID : parameterMetadata.parameterId());
    }

    public AlgorithmId algorithmId() {
        return hyperparameterizedAlgorithmId.algorithmId();
    }

    /** Returns an opaque printable form of the complete dependency-independent identity. */
    public String value() {
        return hyperparameterizedAlgorithmId.value() + "@" + parameterId;
    }

    @Override
    public String toString() {
        return value();
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
