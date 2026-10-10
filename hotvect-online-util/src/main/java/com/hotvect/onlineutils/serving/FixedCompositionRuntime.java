package com.hotvect.onlineutils.serving;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmRuntimeId;
import com.hotvect.api.data.Response;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.topk.TopKRequest;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.onlineutils.experimentmanagement.algodownload.AlgorithmDownloadClient;
import com.hotvect.onlineutils.util.Closeables;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * One immutable, fixed algorithm composition used for offline execution.
 * Finish all concurrent task work before closing this runtime; close must not race with use.
 */
public final class FixedCompositionRuntime implements AutoCloseable {
    private final FixedAlgorithmComposition composition;
    private final AlgorithmRepository repository;
    private final AlgorithmDownloadClient downloadClient;
    private final AlgorithmRepository.CachedAlgorithmGraph rootGraph;
    private final AlgorithmRuntimeContext context;
    private boolean closed;

    private FixedCompositionRuntime(
            FixedAlgorithmComposition composition,
            AlgorithmRepository repository,
            AlgorithmDownloadClient downloadClient,
            AlgorithmRepository.CachedAlgorithmGraph rootGraph) {
        this.composition = composition;
        this.repository = repository;
        this.downloadClient = downloadClient;
        this.rootGraph = rootGraph;
        this.context = new AlgorithmRuntimeContext(rootGraph);
    }

    public static Builder builder() {
        return new Builder();
    }

    public <SHARED, ACTION> Response<ACTION> invoke(RankingRequest<SHARED, ACTION> request) {
        return OfflineAlgorithmInvocation.invoke(context().algorithmInstance().algorithm(), request);
    }

    public <SHARED, ACTION> Response<ACTION> invoke(TopKRequest<SHARED> request) {
        return OfflineAlgorithmInvocation.invoke(context().algorithmInstance().algorithm(), request);
    }

    /** Returns the borrowed context, valid until this runtime closes. */
    public AlgorithmRuntimeContext context() {
        if (closed) {
            throw new IllegalStateException("Fixed composition runtime is closed");
        }
        return context;
    }

    public AlgorithmDefinition rootDefinition() {
        return context().algorithmInstance().algorithmDefinition();
    }

    public AlgorithmRuntimeId runtimeId() {
        return context().runtimeId();
    }

    public Path compositionSource() {
        return composition.source();
    }

    public JsonNode canonicalComposition() {
        return composition.canonicalDefinition();
    }

    /** Closes task-owned resources synchronously, after all task work has finished. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Closeables.closeAll(
                "Could not close fixed composition runtime",
                rootGraph,
                repository,
                downloadClient);
    }

    public static final class Builder {
        private Path compositionPath;
        private final OfflineCompositionLoader.Options options = new OfflineCompositionLoader.Options();

        private Builder() {
        }

        public Builder composition(Path compositionPath) {
            this.compositionPath = Objects.requireNonNull(compositionPath, "compositionPath must not be null");
            return this;
        }

        /** Sets the artifact client owned and closed by the runtime. */
        public Builder downloadClient(AlgorithmDownloadClient downloadClient) {
            options.downloadClient(downloadClient);
            return this;
        }

        public Builder scratchDirectory(Path scratchDirectory) {
            options.scratchDirectory(scratchDirectory);
            return this;
        }

        public Builder algorithmParentClassLoader(ClassLoader algorithmParentClassLoader) {
            options.algorithmParentClassLoader(algorithmParentClassLoader);
            return this;
        }

        /** Sets the execution context supplied when selected algorithms are constructed. */
        public Builder executionContext(ExecutionContext executionContext) {
            options.executionContext(executionContext);
            return this;
        }

        /** Resolves the final execution context from inspected root definitions before construction. */
        public Builder resolveExecutionContext(
                Function<List<AlgorithmDefinition>, ExecutionContext> executionContextResolver) {
            options.resolveExecutionContext(executionContextResolver);
            return this;
        }

        /** Overrides the default local state directory beneath the scratch directory. */
        public Builder localStateRoot(Path localStateRoot) {
            options.localStateRoot(localStateRoot);
            return this;
        }

        public Builder enableFeatureLogging(boolean enableFeatureLogging) {
            options.enableFeatureLogging(enableFeatureLogging);
            return this;
        }

        public Builder meterRegistry(MeterRegistry meterRegistry) {
            options.meterRegistry(meterRegistry);
            return this;
        }

        public FixedCompositionRuntime build() throws Exception {
            Path selectedCompositionPath = Objects.requireNonNull(compositionPath, "composition is required");
            return OfflineCompositionLoader.load(options, (repository, downloadClient, resolveExecutionContext) -> {
                FixedAlgorithmComposition composition = FixedAlgorithmComposition.read(selectedCompositionPath);
                try (FixedCompositionPreparer preparer = new FixedCompositionPreparer(composition, repository)) {
                    AlgorithmDefinition rootDefinition = preparer.inspect();
                    ExecutionContext executionContext = resolveExecutionContext.apply(List.of(rootDefinition));
                    AlgorithmRepository.CachedAlgorithmGraph rootGraph = preparer.prepareInspected(executionContext);
                    try {
                        OfflineServingRootValidator.validate(
                                "Fixed composition root " + composition.root(), rootGraph.instance());
                        return new FixedCompositionRuntime(composition, repository, downloadClient, rootGraph);
                    } catch (RuntimeException | Error failure) {
                        Closeables.closeAfterFailure(failure, rootGraph);
                        throw failure;
                    }
                }
            });
        }
    }
}
