package com.eshioji.hotvect.onlineutils.hotdeploy.common;

import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmParameterMetadata;
import com.hotvect.api.algorithms.Algorithm;

public class AlgorithmInstance<ALGO extends Algorithm> {
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
}
