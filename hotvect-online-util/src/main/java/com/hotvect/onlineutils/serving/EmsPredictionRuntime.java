package com.hotvect.onlineutils.serving;

import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.data.Response;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.topk.TopKRequest;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.onlineutils.experimentmanagement.ExperimentManagementStateSource;
import com.hotvect.onlineutils.experimentmanagement.algodownload.AlgorithmDownloadClient;
import com.hotvect.onlineutils.experimentmanagement.httpclient.FileExperimentManagementStateSource;
import com.hotvect.onlineutils.util.Closeables;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * One immutable EMS generation used to route an offline data set record by record.
 * Finish all concurrent task work before closing this runtime; close must not race with use.
 */
public final class EmsPredictionRuntime implements AutoCloseable {
    private final String rootSlot;
    private final AlgorithmRepository algorithmRepository;
    private final ExperimentManagementStateSource stateSource;
    private final AlgorithmDownloadClient downloadClient;
    private final ServingSnapshot snapshot;
    private boolean closed;

    private EmsPredictionRuntime(
            String rootSlot,
            AlgorithmRepository algorithmRepository,
            ExperimentManagementStateSource stateSource,
            AlgorithmDownloadClient downloadClient,
            ServingSnapshot snapshot) {
        this.rootSlot = rootSlot;
        this.algorithmRepository = algorithmRepository;
        this.stateSource = stateSource;
        this.downloadClient = downloadClient;
        this.snapshot = snapshot;
    }

    public static Builder builder() {
        return new Builder();
    }

    public <SHARED, ACTION> AlgorithmExecution<Response<ACTION>> invoke(
            String assignmentKey,
            RankingRequest<SHARED, ACTION> request) {
        SelectedAlgorithmRuntime selected = select(assignmentKey);
        return new AlgorithmExecution<>(
                OfflineAlgorithmInvocation.invoke(selected.context().algorithmInstance().algorithm(), request),
                selected.selection());
    }

    public <SHARED, ACTION> AlgorithmExecution<Response<ACTION>> invoke(
            String assignmentKey,
            TopKRequest<SHARED> request) {
        SelectedAlgorithmRuntime selected = select(assignmentKey);
        return new AlgorithmExecution<>(
                OfflineAlgorithmInvocation.invoke(selected.context().algorithmInstance().algorithm(), request),
                selected.selection());
    }

    /** Returns a borrowed selection, valid until this runtime closes. */
    public SelectedAlgorithmRuntime select(String assignmentKey) {
        requireOpen();
        PreparedComposition composition = snapshot.select(rootSlot, assignmentKey);
        return new SelectedAlgorithmRuntime(
                new AlgorithmRuntimeContext(composition.rootGraph()),
                AlgorithmSelection.from(rootSlot, composition));
    }

    public List<AlgorithmDefinition> rootDefinitions() {
        requireOpen();
        return snapshot.rootDefinitions(rootSlot);
    }

    /** Closes task-owned resources synchronously, after all task work has finished. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Closeables.closeAll(
                "Could not close EMS prediction runtime",
                snapshot,
                algorithmRepository,
                stateSource,
                downloadClient);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("EMS prediction runtime is closed");
        }
    }

    public static final class Builder {
        private String rootSlot;
        private ExperimentManagementStateSource stateSource;
        private final OfflineCompositionLoader.Options options = new OfflineCompositionLoader.Options();

        private Builder() {
        }

        public Builder rootSlot(String rootSlot) {
            if (rootSlot == null || rootSlot.isBlank()) {
                throw new IllegalArgumentException("rootSlot must not be blank");
            }
            this.rootSlot = rootSlot;
            return this;
        }

        /** Sets the state source owned and closed by the runtime. */
        public Builder stateSource(ExperimentManagementStateSource stateSource) {
            this.stateSource = Objects.requireNonNull(stateSource, "stateSource must not be null");
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

        public EmsPredictionRuntime build() throws Exception {
            String selectedRoot = Objects.requireNonNull(rootSlot, "rootSlot is required");
            ExperimentManagementStateSource selectedStateSource = Objects.requireNonNull(
                    stateSource,
                    "stateSource is required");
            try {
                return OfflineCompositionLoader.load(options, (repository, downloadClient, resolveExecutionContext) -> {
                    validatePinnedRoot(selectedRoot, selectedStateSource);
                    ServingSnapshotPreparer preparer = new ServingSnapshotPreparer(
                            java.util.Set.of(selectedRoot),
                            repository,
                            selectedStateSource,
                            (slotName, instance) -> OfflineServingRootValidator.validate(
                                    "EMS prediction root " + slotName, instance));
                    try (ServingSnapshotPreparer.Preparation preparation = preparer.inspect()) {
                        ExecutionContext executionContext = resolveExecutionContext.apply(
                                preparation.rootDefinitions(selectedRoot));
                        ServingSnapshot snapshot = preparer.prepareInspected(preparation, executionContext);
                        try {
                            OfflineServingRootValidator.validateCompatible(
                                    "EMS prediction root " + selectedRoot, snapshot.rootInstances(selectedRoot));
                            validatePinnedSlotClosure(selectedStateSource, snapshot);
                            return new EmsPredictionRuntime(
                                    selectedRoot, repository, selectedStateSource, downloadClient, snapshot);
                        } catch (RuntimeException | Error failure) {
                            Closeables.closeAfterFailure(failure, snapshot);
                            throw failure;
                        }
                    }
                });
            } catch (Exception | Error failure) {
                Closeables.closeAfterFailure(failure, selectedStateSource);
                throw failure;
            }
        }

        private static void validatePinnedRoot(
                String selectedRoot,
                ExperimentManagementStateSource stateSource) {
            if (stateSource instanceof FileExperimentManagementStateSource fileStateSource) {
                fileStateSource.provenance().ifPresent(provenance -> {
                    if (!provenance.requestedRootSlot().equals(selectedRoot)) {
                        throw new IllegalArgumentException(
                                "EMS state was captured for root slot " + provenance.requestedRootSlot()
                                        + " but prediction requested root slot " + selectedRoot);
                    }
                });
            }
        }

        private static void validatePinnedSlotClosure(
                ExperimentManagementStateSource stateSource,
                ServingSnapshot snapshot) {
            if (stateSource instanceof FileExperimentManagementStateSource fileStateSource
                    && !fileStateSource.slotNames().equals(snapshot.assignments().keySet())) {
                throw new IllegalArgumentException(
                        "EMS state slots must equal the slots reachable from the requested root; captured="
                                + fileStateSource.slotNames() + ", reachable=" + snapshot.assignments().keySet());
            }
        }
    }

}
