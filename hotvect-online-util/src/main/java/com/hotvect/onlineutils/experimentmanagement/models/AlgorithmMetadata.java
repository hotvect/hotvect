package com.hotvect.onlineutils.experimentmanagement.models;

import com.hotvect.api.algodefinition.AlgorithmId;
import java.nio.file.Path;

public record AlgorithmMetadata(
        String algorithmName,
        String algorithmVersion,
        String latestAlgorithmParameter,
        String absoluteS3AlgorithmJarPath,
        String absoluteS3AlgorithmParameterPath) {
    public String algorithmJarFileName() {
        return Path.of(absoluteS3AlgorithmJarPath).getFileName().toString();
    }

    public AlgorithmId algorithmId() {
        return new AlgorithmId(algorithmName, algorithmVersion);
    }

    public String latestAlgorithmParameterFileName() {
        return Path.of(absoluteS3AlgorithmParameterPath).getFileName().toString();
    }
}
