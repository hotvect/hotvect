package com.eshioji.hotvect.onlineutils.hotdeploy;

import com.eshioji.hotvect.api.algodefinition.AlgorithmDefinition;
import com.eshioji.hotvect.api.algodefinition.AlgorithmParameterMetadata;
import com.eshioji.hotvect.api.policies.Scorer;

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
