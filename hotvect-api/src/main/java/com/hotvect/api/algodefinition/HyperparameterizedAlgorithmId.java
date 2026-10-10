package com.hotvect.api.algodefinition;

import java.util.Objects;

/** Identifies one algorithm's code and effective hyperparameters. */
public record HyperparameterizedAlgorithmId(
        AlgorithmId algorithmId,
        String hyperparameterVersion) {

    public HyperparameterizedAlgorithmId {
        Objects.requireNonNull(algorithmId, "algorithmId must not be null");
        hyperparameterVersion = normalizeBlankToNull(hyperparameterVersion);
    }

    /** Returns an opaque printable form of the code and hyperparameter identity. */
    public String value() {
        return hyperparameterVersion == null
                ? algorithmId.value()
                : algorithmId.value() + "-" + hyperparameterVersion;
    }

    @Override
    public String toString() {
        return value();
    }

    private static String normalizeBlankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
