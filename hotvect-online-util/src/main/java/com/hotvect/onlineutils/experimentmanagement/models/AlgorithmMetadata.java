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
        if ((latestAlgorithmParameter == null) != (absoluteS3AlgorithmParameterPath == null)) {
            throw new IllegalArgumentException(
                    "Algorithm parameter ID and path must either both be present or both be absent");
        }
        if (latestAlgorithmParameter != null
                && (latestAlgorithmParameter.isBlank() || absoluteS3AlgorithmParameterPath.isBlank())) {
            throw new IllegalArgumentException(
                    "Algorithm parameter ID and path must be nonblank when present");
        }
    }

    public String algorithmJarFileName() {
        return Path.of(absoluteS3AlgorithmJarPath).getFileName().toString();
    }

    public AlgorithmId algorithmId() {
        return new AlgorithmId(algorithmName, algorithmVersion);
    }

    public String latestAlgorithmParameterFileName() {
        if (!hasParameter()) {
            throw new IllegalStateException("Parameterless algorithm has no parameter archive");
        }
        return Path.of(absoluteS3AlgorithmParameterPath).getFileName().toString();
    }

    public boolean hasParameter() {
        return latestAlgorithmParameter != null;
    }
}
