package com.hotvect.onlineutils.hotdeploy;

import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.AlgorithmRuntimeId;
import com.hotvect.api.algorithms.Algorithm;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One explicitly owned, fully constructed algorithm graph.
 *
 * <p>The graph owns every node constructed while resolving its root, but never application-supplied
 * dependency bindings. Closing it releases dependents before dependencies and closes an aliased
 * algorithm object at most once.</p>
 *
 * @param <ALGO> root algorithm type
 */
public final class AlgorithmGraph<ALGO extends Algorithm> implements AutoCloseable {
    private final AlgorithmInstance<ALGO> root;
    private final AlgorithmRuntimeId runtimeId;
    private final ClassLoader rootArtifactClassLoader;
    private final RuntimeGraphOwnership ownership;

    AlgorithmGraph(
            AlgorithmInstance<ALGO> root,
            AlgorithmRuntimeId runtimeId,
            ClassLoader rootArtifactClassLoader,
            RuntimeGraphOwnership ownership) {
        this.root = Objects.requireNonNull(root, "root must not be null");
        this.runtimeId = Objects.requireNonNull(runtimeId, "runtimeId must not be null");
        this.rootArtifactClassLoader = Objects.requireNonNull(
                rootArtifactClassLoader,
                "rootArtifactClassLoader must not be null");
        this.ownership = Objects.requireNonNull(ownership, "ownership must not be null");
    }

    /** Returns the root node while this graph remains open. */
    public AlgorithmInstance<ALGO> root() {
        ownership.requireOpen();
        return root;
    }

    /** Returns the root algorithm while this graph remains open. */
    public ALGO algorithm() {
        return root().algorithm();
    }

    /** Returns the precomputed recursive identity of this graph. */
    public AlgorithmRuntimeId runtimeId() {
        ownership.requireOpen();
        return runtimeId;
    }

    /** Returns the loader of the artifact whose factory constructed the root node. */
    public ClassLoader rootArtifactClassLoader() {
        ownership.requireOpen();
        return rootArtifactClassLoader;
    }

    void addOwnedResource(AutoCloseable resource) {
        ownership.addOwnedResource(resource);
    }

    @Override
    public void close() throws Exception {
        ownership.close();
    }
}

/** Collects created nodes until a graph is published or construction fails. */
final class RuntimeGraphBuilder {
    private final List<AlgorithmInstance<?>> ownedNodes = new ArrayList<>();
    private final List<AutoCloseable> ownedResources = new ArrayList<>();
    private final List<Object> retainedReferences = new ArrayList<>();
    private boolean built;

    void addOwnedNode(AlgorithmInstance<?> instance) {
        if (built) {
            throw new IllegalStateException("Cannot add nodes after publishing a runtime graph");
        }
        ownedNodes.add(Objects.requireNonNull(instance, "instance must not be null"));
    }

    void retain(Object reference) {
        if (built) {
            throw new IllegalStateException("Cannot retain references after publishing a runtime graph");
        }
        retainedReferences.add(Objects.requireNonNull(reference, "reference must not be null"));
    }

    void addOwnedResource(AutoCloseable resource) {
        if (built) {
            throw new IllegalStateException("Cannot add resources after publishing a runtime graph");
        }
        ownedResources.add(Objects.requireNonNull(resource, "resource must not be null"));
    }

    RuntimeGraphOwnership build() {
        if (built) {
            throw new IllegalStateException("Runtime graph ownership was already published");
        }
        built = true;
        return new RuntimeGraphOwnership(ownedNodes, ownedResources, retainedReferences);
    }

    void closeAfterFailure(Throwable failure) {
        if (built) {
            throw new IllegalStateException("Cannot clean up a published runtime graph", failure);
        }
        built = true;
        try {
            new RuntimeGraphOwnership(ownedNodes, ownedResources, retainedReferences).close();
        } catch (Throwable closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}

/** Shared close implementation for algorithm and feature-extraction graph handles. */
final class RuntimeGraphOwnership implements AutoCloseable {
    private final List<AlgorithmInstance<?>> ownedNodes;
    private final List<AutoCloseable> ownedResources;
    private final List<Object> retainedReferences;
    private boolean closed;

    RuntimeGraphOwnership(
            List<AlgorithmInstance<?>> ownedNodes,
            List<? extends AutoCloseable> ownedResources,
            List<?> retainedReferences) {
        this.ownedNodes = List.copyOf(Objects.requireNonNull(ownedNodes, "ownedNodes must not be null"));
        this.ownedResources = new ArrayList<>(Objects.requireNonNull(
                ownedResources,
                "ownedResources must not be null"));
        this.retainedReferences = new ArrayList<>(Objects.requireNonNull(
                retainedReferences,
                "retainedReferences must not be null"));
    }

    synchronized void addOwnedResource(AutoCloseable resource) {
        requireOpen();
        ownedResources.add(Objects.requireNonNull(resource, "resource must not be null"));
    }

    synchronized void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Runtime graph is closed");
        }
    }

    @Override
    public void close() throws Exception {
        List<AutoCloseable> resources;
        List<Object> retained;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            resources = List.copyOf(ownedResources);
            retained = List.copyOf(retainedReferences);
            retainedReferences.clear();
        }

        try {
            Throwable failure = null;
            Set<Algorithm> closedAlgorithms = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int index = ownedNodes.size() - 1; index >= 0; index--) {
                Algorithm algorithm = ownedNodes.get(index).algorithm();
                if (!closedAlgorithms.add(algorithm)) {
                    continue;
                }
                failure = close(algorithm, failure);
            }
            for (int index = resources.size() - 1; index >= 0; index--) {
                failure = close(resources.get(index), failure);
            }
            if (failure != null) {
                rethrow(failure);
            }
        } finally {
            Reference.reachabilityFence(retained);
        }
    }

    private static Throwable close(AutoCloseable closeable, Throwable failure) {
        try {
            closeable.close();
        } catch (Throwable closeFailure) {
            if (failure == null) {
                return closeFailure;
            }
            failure.addSuppressed(closeFailure);
        }
        return failure;
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new RuntimeException(failure);
    }
}
