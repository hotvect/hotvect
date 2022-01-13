package com.hotvect.onlineutils.hotdeploy;

import com.hotvect.api.AlgorithmDefinition;
import com.hotvect.api.AlgorithmParameterMetadata;
import com.hotvect.api.scoring.Scorer;

public class AlgorithmInstance<R> {
    private final AlgorithmDefinition algorithmDefinition;
    private final AlgorithmParameterMetadata algorithmParameterMetadata;
    private final Scorer<R> algorithm;

    public AlgorithmInstance(AlgorithmDefinition algorithmDefinition, AlgorithmParameterMetadata algorithmParameterMetadata, Scorer<R> algorithm) {
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

    public Scorer<R> getAlgorithm() {
        return algorithm;
    }
}
