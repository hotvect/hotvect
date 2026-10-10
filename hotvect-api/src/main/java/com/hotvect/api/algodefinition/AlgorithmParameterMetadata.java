package com.hotvect.api.algodefinition;

import java.time.Instant;
import java.util.Optional;

/**
 * Identity and provenance read from one algorithm namespace in a parameter package.
 *
 * @param algorithmId algorithm code identity expected to consume the parameters
 * @param parameterId immutable parameter identity
 * @param ranAt time when the producing pipeline run began
 * @param lastTestTime logical date anchoring the data used by the run, when recorded
 */
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
