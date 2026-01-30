package com.hotvect.api.algodefinition;

import com.hotvect.api.algorithms.Algorithm;

public record AlgorithmInstance<ALGO extends Algorithm>(
        AlgorithmDefinition algorithmDefinition,
        AlgorithmParameterMetadata algorithmParameterMetadata,
        ALGO algorithm
) implements AutoCloseable {

    @Deprecated(forRemoval = true)
    public ALGO getAlgorithm() {
        return this.algorithm;
    }

    @Deprecated(forRemoval = true)
    public AlgorithmDefinition getAlgorithmDefinition() {
        return this.algorithmDefinition;
    }

    @Deprecated(forRemoval = true)
    public AlgorithmParameterMetadata getAlgorithmParameterMetadata() {
        return this.algorithmParameterMetadata;
    }

    @Override
    public void close() throws Exception {
        this.algorithm.close();
    }

    /**
     * Creates an AlgorithmInstance for external dependencies that are not hotvect algorithms.
     * This is a convenience method that creates both the AlgorithmDefinition and AlgorithmParameterMetadata
     * for external objects like feature stores.
     *
     * @param algorithmName the name of the external algorithm
     * @param externalObject the external object instance
     * @param <T> the type of the external object
     * @return an AlgorithmInstance suitable for external dependencies
     */
    public static <T extends Algorithm> AlgorithmInstance<T> externalAlgorithm(String algorithmName, T externalObject) {
        return new AlgorithmInstance<>(
                AlgorithmDefinition.externalAlgorithm(algorithmName),
                AlgorithmParameterMetadata.externalAlgorithm(algorithmName),
                externalObject
        );
    }
}
