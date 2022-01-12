package com.eshioji.hotvect.onlineutils.hotdeploy.common;

import com.eshioji.hotvect.api.algodefinition.AlgorithmDefinition;
import com.eshioji.hotvect.api.algodefinition.AlgorithmParameterMetadata;
import com.eshioji.hotvect.api.algorithms.Algorithm;

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
