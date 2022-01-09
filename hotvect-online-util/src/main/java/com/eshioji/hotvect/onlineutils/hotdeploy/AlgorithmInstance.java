package com.eshioji.hotvect.onlineutils.hotdeploy;

import com.eshioji.hotvect.api.algodefinition.AlgorithmDefinition;
import com.eshioji.hotvect.api.algodefinition.AlgorithmParameterMetadata;
import com.eshioji.hotvect.api.policies.Scorer;

public class AlgorithmInstance<RECORD> {
    private final AlgorithmDefinition algorithmDefinition;
    private final AlgorithmParameterMetadata algorithmParameterMetadata;
    private final Scorer<RECORD> algorithm;

    public AlgorithmInstance(AlgorithmDefinition algorithmDefinition, AlgorithmParameterMetadata algorithmParameterMetadata, Scorer<RECORD> algorithm) {
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

    public Scorer<RECORD> getAlgorithm() {
        return algorithm;
    }
}
