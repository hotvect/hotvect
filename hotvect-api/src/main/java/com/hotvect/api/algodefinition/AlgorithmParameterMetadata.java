package com.hotvect.api.algodefinition;

import java.time.Instant;
import java.util.Optional;

public record AlgorithmParameterMetadata(
        AlgorithmId algorithmId,
        String parameterId,
        Instant ranAt,
        Optional<Instant> lastTestTime
) {
    @Deprecated(forRemoval = true)
    public AlgorithmId getAlgorithmId() {
        return this.algorithmId;
    }

    @Deprecated(forRemoval = true)
    public String getParameterId() {
        return this.parameterId;
    }

    @Deprecated(forRemoval = true)
    public Instant getRanAt() {
        return this.ranAt;
    }

    /**
     * Creates AlgorithmParameterMetadata for external dependencies that are not hotvect algorithms.
     * Uses current time as ranAt and empty lastTestTime since external dependencies have no training.
     *
     * @param algorithmName the name of the external algorithm
     * @return AlgorithmParameterMetadata suitable for external dependencies
     */
    public static AlgorithmParameterMetadata externalAlgorithm(String algorithmName) {
        return new AlgorithmParameterMetadata(
                new AlgorithmId(algorithmName, "NA"),
                "NA",
                Instant.now(),
                Optional.empty()
        );
    }
}
