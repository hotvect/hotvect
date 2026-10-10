package com.hotvect.onlineutils.experimentmanagement.algodownload;

import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.onlineutils.hotdeploy.AlgorithmArtifactProvider;
import com.hotvect.onlineutils.hotdeploy.AlgorithmGraph;
import com.hotvect.onlineutils.hotdeploy.AlgorithmGraphDependencies;
import com.hotvect.onlineutils.hotdeploy.AlgorithmGraphInspection;
import com.hotvect.onlineutils.hotdeploy.AlgorithmInstanceFactory;
import com.hotvect.onlineutils.hotdeploy.SharedNodeInterner;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** One downloader-owned staged JAR and its isolated factory classloader. */
public final class DownloadedAlgorithmArtifact implements AutoCloseable {
    private final Object lock = new Object();
    private final AlgorithmInstanceFactory factory;
    private final Path stagedJar;
    private final Map<String, AlgorithmDefinition> definitions = new HashMap<>();
    private boolean closed;

    DownloadedAlgorithmArtifact(AlgorithmInstanceFactory factory, Path stagedJar) {
        this.factory = Objects.requireNonNull(factory, "factory must not be null");
        this.stagedJar = Objects.requireNonNull(stagedJar, "stagedJar must not be null").toAbsolutePath();
    }

    AlgorithmDefinition readAlgorithmDefinition(String algorithmName) {
        synchronized (lock) {
            requireOpen();
            return definitions.computeIfAbsent(algorithmName, factory::readAlgorithmDefinition);
        }
    }

    public AlgorithmGraphInspection inspectGraph(
            AlgorithmDefinition algorithmDefinition,
            AlgorithmDependencies dependencyOverrides) {
        return openFactory().inspectGraph(algorithmDefinition, dependencyOverrides);
    }

    public AlgorithmGraphInspection inspectSharedDependencies(
            AlgorithmDefinition algorithmDefinition,
            AlgorithmDependencies dependencyOverrides) {
        return openFactory().inspectSharedDependencies(algorithmDefinition, dependencyOverrides);
    }

    <ALGO extends Algorithm> AlgorithmGraph<ALGO> loadGraph(
            AlgorithmDefinition algorithmDefinition,
            File parameterFile,
            AlgorithmDependencies dependencyOverrides,
            AlgorithmGraphDependencies slotBindings,
            String rootProviderIdentity,
            Map<AlgorithmId, AlgorithmArtifactProvider> sharedProviders,
            SharedNodeInterner sharedNodeInterner,
            ExecutionContext executionContext) {
        AlgorithmInstanceFactory rootFactory = openFactory();
        if (rootProviderIdentity == null || rootProviderIdentity.isBlank()) {
            throw new IllegalArgumentException("rootProviderIdentity must not be blank");
        }
        Objects.requireNonNull(sharedProviders, "sharedProviders must not be null");
        sharedProviders.forEach((algorithmId, provider) -> {
            Objects.requireNonNull(algorithmId, "shared provider algorithmId must not be null");
            Objects.requireNonNull(
                    provider,
                    "shared provider artifact must not be null");
        });
        return rootFactory.loadGraphFromArtifactSet(
                algorithmDefinition,
                parameterFile,
                dependencyOverrides,
                slotBindings,
                rootProviderIdentity,
                sharedProviders,
                Objects.requireNonNull(sharedNodeInterner, "sharedNodeInterner must not be null"),
                Objects.requireNonNull(executionContext, "executionContext must not be null"));
    }

    /** Returns this artifact's open code factory with the parameters selected for one node. */
    public AlgorithmArtifactProvider asProvider(String source, File parameterFile) {
        return new AlgorithmArtifactProvider(openFactory(), source, parameterFile);
    }

    @Override
    public void close() throws Exception {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
        }

        Throwable failure = null;
        try {
            factory.close();
        } catch (Throwable closeFailure) {
            failure = closeFailure;
        }
        try {
            Files.deleteIfExists(stagedJar);
        } catch (Throwable deleteFailure) {
            if (failure == null) {
                failure = deleteFailure;
            } else {
                failure.addSuppressed(deleteFailure);
            }
        }
        if (failure != null) {
            rethrow(failure);
        }
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

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Downloaded algorithm artifact is closed");
        }
    }

    private AlgorithmInstanceFactory openFactory() {
        synchronized (lock) {
            requireOpen();
            return factory;
        }
    }
}
