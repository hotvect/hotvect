package com.hotvect.onlineutils.serving;

import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.AlgorithmRuntimeId;
import java.util.Objects;

/**
 * A borrowed view of the root instance, runtime identity, and artifact classloader of one resolved graph.
 *
 * <p>This is a borrowed view, valid only while its owning offline runtime is open. Finish all
 * invocations and decoder use before closing that runtime. Graph topology and classloader
 * ownership remain runtime implementation details rather than components of {@link AlgorithmInstance}.</p>
 */
public final class AlgorithmRuntimeContext {
    private final AlgorithmRepository.CachedAlgorithmGraph graph;

    AlgorithmRuntimeContext(AlgorithmRepository.CachedAlgorithmGraph graph) {
        this.graph = Objects.requireNonNull(graph, "graph must not be null");
    }

    public AlgorithmInstance<?> algorithmInstance() {
        return graph.instance();
    }

    public AlgorithmRuntimeId runtimeId() {
        return graph.runtimeId();
    }

    /** Returns the loader of the artifact whose factory constructed the root algorithm. */
    public ClassLoader rootArtifactClassLoader() {
        return graph.rootArtifactClassLoader();
    }
}
