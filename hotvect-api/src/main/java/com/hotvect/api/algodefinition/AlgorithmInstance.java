package com.hotvect.api.algodefinition;

import com.google.common.reflect.TypeToken;
import com.hotvect.api.algorithms.Algorithm;

/** One constructed algorithm together with its declared type contract. */
public record AlgorithmInstance<ALGO extends Algorithm>(
        AlgorithmDefinition algorithmDefinition,
        AlgorithmParameterMetadata algorithmParameterMetadata,
        ALGO algorithm,
        TypeToken<ALGO> algorithmType
) {

    /** Validates one constructed algorithm value. */
    public AlgorithmInstance {
        java.util.Objects.requireNonNull(algorithmDefinition, "algorithmDefinition must not be null");
        java.util.Objects.requireNonNull(algorithm, "algorithm must not be null");
        algorithmType = AlgorithmTypeContract.requireDeclared(algorithmType);
        if (!algorithmType.getRawType().isInstance(algorithm)) {
            throw new IllegalArgumentException(
                    "Algorithm contract " + algorithmType
                            + " is incompatible with implementation " + algorithm.getClass().getName());
        }
    }

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
    public static <T extends Algorithm> AlgorithmInstance<T> externalAlgorithm(
            String algorithmName,
            TypeToken<T> algorithmType,
            T externalObject) {
        return new AlgorithmInstance<>(
                AlgorithmDefinition.externalAlgorithm(algorithmName),
                AlgorithmParameterMetadata.externalAlgorithm(algorithmName),
                externalObject,
                algorithmType
        );
    }

    /** Creates an external dependency with a concrete non-generic algorithm class contract. */
    public static <T extends Algorithm> AlgorithmInstance<T> externalAlgorithm(
            String algorithmName,
            Class<T> algorithmType,
            T externalObject) {
        return externalAlgorithm(algorithmName, AlgorithmTypeContract.fromConcreteClass(algorithmType), externalObject);
    }
}
