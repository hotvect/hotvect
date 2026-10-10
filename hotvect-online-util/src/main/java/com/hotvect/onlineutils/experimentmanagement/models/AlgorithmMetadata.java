package com.hotvect.onlineutils.experimentmanagement.models;

import com.hotvect.api.algodefinition.AlgorithmId;
import java.nio.file.Path;

public record AlgorithmMetadata(
        String algorithmName,
        String algorithmVersion,
        String latestAlgorithmParameter,
        String absoluteS3AlgorithmJarPath,
        String absoluteS3AlgorithmParameterPath) {
    public AlgorithmMetadata {
        if (latestAlgorithmParameter != null && latestAlgorithmParameter.isBlank()) {
            throw new IllegalArgumentException("Algorithm parameter ID must be nonblank when present");
        }
        if (absoluteS3AlgorithmParameterPath != null && absoluteS3AlgorithmParameterPath.isBlank()) {
            throw new IllegalArgumentException("Algorithm parameter path must be nonblank when present");
        }
        if (latestAlgorithmParameter != null && absoluteS3AlgorithmParameterPath == null) {
            throw new IllegalArgumentException(
                    "Algorithm parameter ID requires an algorithm parameter path");
        }
    }

    public String algorithmJarFileName() {
        return Path.of(absoluteS3AlgorithmJarPath).getFileName().toString();
    }

    public AlgorithmId algorithmId() {
        return new AlgorithmId(algorithmName, algorithmVersion);
    }

    public String latestAlgorithmParameterFileName() {
        return Path.of(absoluteS3AlgorithmParameterPath).getFileName().toString();
    }

    public boolean hasParameter() {
        return absoluteS3AlgorithmParameterPath != null;
    }

    public AlgorithmMetadata withParameterId(String parameterId) {
        if (!hasParameter()) {
            throw new IllegalStateException("Cannot set a parameter ID without a parameter path");
        }
        return new AlgorithmMetadata(
                algorithmName,
                algorithmVersion,
                parameterId,
                absoluteS3AlgorithmJarPath,
                absoluteS3AlgorithmParameterPath);
    }
}
