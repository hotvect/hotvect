package com.hotvect.serve;

import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.AlgorithmRuntimeId;

import java.util.Objects;

/**
 * A runtime selected by the Hotvect algorithm demo for one invocation.
 *
 * <p>The containing server retains lifecycle ownership of the algorithm instance. Extensions may use the
 * instance during the invocation but must not close it.</p>
 */
public record SelectedRuntime(
        AlgorithmInstance<?> algorithmInstance,
        AlgorithmRuntimeId identity,
        ClassLoader rootArtifactClassLoader
) {
    public SelectedRuntime {
        algorithmInstance = Objects.requireNonNull(algorithmInstance);
        identity = Objects.requireNonNull(identity);
        rootArtifactClassLoader = Objects.requireNonNull(rootArtifactClassLoader);
    }
}
