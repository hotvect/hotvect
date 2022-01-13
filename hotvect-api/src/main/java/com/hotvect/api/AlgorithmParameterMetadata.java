package com.hotvect.api;

import java.time.Instant;

public class AlgorithmParameterMetadata {
    private final String parameterId;
    private final Instant ranAt;
    private final String algorithmName;

    public AlgorithmParameterMetadata(String parameterId, Instant ranAt, String algorithmName) {
        this.parameterId = parameterId;
        this.ranAt = ranAt;
        this.algorithmName = algorithmName;
    }

    public String getParameterId() {
        return parameterId;
    }

    public Instant getRanAt() {
        return ranAt;
    }

    public String getAlgorithmName() {
        return algorithmName;
    }
}
