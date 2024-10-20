package com.hotvect.api.algodefinition;

import com.hotvect.api.algorithms.Algorithm;

public class AlgorithmInstance<ALGO extends Algorithm> implements AutoCloseable {
    private final AlgorithmDefinition algorithmDefinition;
    private final AlgorithmParameterMetadata algorithmParameterMetadata;
    private final ALGO algorithm;

    public AlgorithmInstance(AlgorithmDefinition algorithmDefinition, AlgorithmParameterMetadata algorithmParameterMetadata, ALGO algorithm) {
        this.algorithmDefinition = algorithmDefinition;
        this.algorithmParameterMetadata = algorithmParameterMetadata;
        this.algorithm = algorithm;
    }

    public AlgorithmDefinition getAlgorithmDefinition() {
        return algorithmDefinition;
    }

    public AlgorithmParameterMetadata getAlgorithmParameterMetadata() {
        return algorithmParameterMetadata;
    }

    public ALGO getAlgorithm() {
        return this.algorithm;
    }

    @Override
    public String toString() {
        return "AlgorithmInstance{" +
                "algorithmDefinition=" + algorithmDefinition +
                ", algorithmParameterMetadata=" + algorithmParameterMetadata +
                ", algorithm=" + algorithm +
                '}';
    }

    @Override
    public void close() throws Exception {
        this.algorithm.close();
    }
}
