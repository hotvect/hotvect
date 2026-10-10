package com.hotvect.onlineutils.hotdeploy;

import com.hotvect.api.algodefinition.AlgorithmDefinition;
import java.io.File;
import java.util.Objects;

/** One loaded code factory together with the parameter archive selected for this graph construction. */
public record AlgorithmArtifactProvider(
        AlgorithmInstanceFactory factory,
        String source,
        File parameterFile) implements AlgorithmGraphResolver.Provider {

    public AlgorithmArtifactProvider {
        factory = Objects.requireNonNull(factory, "factory must not be null");
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
    }

    @Override
    public AlgorithmDefinition readDefinition(String algorithmName) {
        return factory.readAlgorithmDefinition(algorithmName);
    }

    @Override
    public ClassLoader classLoader() {
        return factory.classLoader;
    }
}
