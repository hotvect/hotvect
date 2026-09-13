package com.hotvect.onlineutils.serving;

import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.AlgorithmParameterMetadata;
import com.hotvect.api.algodefinition.AlgorithmRuntimeId;
import com.hotvect.api.algodefinition.ParameterizedAlgorithmId;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.onlineutils.experimentmanagement.algodownload.AlgorithmDownloader;
import com.hotvect.onlineutils.experimentmanagement.algodownload.DownloadedAlgorithmArtifact;
import com.hotvect.onlineutils.experimentmanagement.algodownload.DownloadedAlgorithmParameters;
import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import com.hotvect.onlineutils.hotdeploy.AlgorithmArtifactProvider;
import com.hotvect.onlineutils.hotdeploy.AlgorithmGraph;
import com.hotvect.onlineutils.hotdeploy.AlgorithmGraphDependencies;
import com.hotvect.onlineutils.hotdeploy.AlgorithmGraphInspection;
import com.hotvect.onlineutils.hotdeploy.SharedNodeInterner;
import com.hotvect.onlineutils.hotdeploy.util.AlgorithmUtils;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;
import com.hotvect.onlineutils.util.Closeables;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shares algorithm resources between serving generations.
 *
 * <p>Callers serialize preparation. The lifecycle lock guards registry membership, owner reference
 * counts and lease release together; retired owners are removed before closing their resources
 * outside the lock. Downloads and graph construction also run outside the lock.
 *
 * <p>Prediction reads already prepared graph leases without taking this lock. The serving generation
 * keeps those leases open until its last invocation finishes, then releases them on a cleanup thread.
 */
final class AlgorithmRepository implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(AlgorithmRepository.class);
    private static final String ALGORITHM_NAME_KEY = "AlgorithmName";
    private static final String PARAMETER_ID_KEY = "AlgorithmParameterId";
    private static final String ALGORITHM_METRICS_NAME = "ems.algorithm_parameter.age";

    private final Object lifecycleLock = new Object();
    // Registry entries do not count as leases. Only owners with a positive count are discoverable.
    private final Map<GraphKey, GraphOwner> liveGraphs = new HashMap<>();
    private final Map<SharedNodeRevision, GraphOwner> liveSharedNodes = new HashMap<>();
    private final Map<AlgorithmId, ArtifactHolder> artifactHolders = new HashMap<>();
    private final SharedParameterMetadataCache sharedParameterMetadata = new SharedParameterMetadataCache();
    private final AlgorithmDownloader algorithmDownloader;
    private final AlgorithmDependencies applicationBindings;
    private final AlgorithmAgeMetrics algorithmAgeMetrics;
    private boolean closed;

    AlgorithmRepository(
            AlgorithmDownloader algorithmDownloader,
            MeterRegistry meterRegistry,
            AlgorithmDependencies applicationBindings) {
        this.algorithmDownloader = Objects.requireNonNull(algorithmDownloader, "algorithmDownloader must not be null");
        this.applicationBindings = Objects.requireNonNull(
                applicationBindings,
                "applicationBindings must not be null");
        this.algorithmAgeMetrics = meterRegistry == null ? null : new AlgorithmAgeMetrics(meterRegistry, lifecycleLock);
    }

    /** Inspects one staged artifact while retaining it for the caller's preparation scope. */
    AlgorithmArtifactInspection inspectAlgorithm(AlgorithmMetadata algorithmMetadata) {
        Objects.requireNonNull(algorithmMetadata, "algorithmMetadata must not be null");
        requireOpen();
        ArtifactLease artifact = acquireArtifact(algorithmMetadata);
        try {
            AlgorithmDefinition definition = algorithmDownloader.readAlgorithmDefinition(
                    artifact.artifact(),
                    algorithmMetadata.algorithmName());
            if (!definition.algorithmId().equals(algorithmMetadata.algorithmId())) {
                throw new IllegalStateException(
                        "Algorithm JAR contains " + definition.algorithmId()
                                + " but the configured selection is " + algorithmMetadata.algorithmId());
            }
            AlgorithmGraphInspection inspection = artifact.artifact().inspectGraph(
                    definition,
                    applicationBindings);
            return new AlgorithmArtifactInspection(
                    algorithmMetadata,
                    artifact,
                    inspection.rootDefinition(),
                    inspection.reachableSlotNames(),
                    inspection.packagedDefinitions(),
                    inspection.sharedDependencies());
        } catch (RuntimeException | Error failure) {
            Closeables.closeAfterFailure(failure, artifact);
            throw failure;
        }
    }

    /** Builds the exact canonical shared-provider catalog for one complete resolved graph set. */
    SharedArtifactCatalog sharedArtifactCatalog(Collection<AlgorithmArtifactInspection> inspections) {
        Objects.requireNonNull(inspections, "inspections must not be null");
        requireOpen();
        return SharedArtifactCatalog.create(
                inspections,
                algorithmDownloader,
                sharedParameterMetadata,
                this::acquireSharedNode,
                applicationBindings);
    }

    /** Returns a new lease on the effective graph, reusing an unchanged retained graph. */
    CachedAlgorithmGraph acquireComposedAlgorithm(
            AlgorithmArtifactInspection inspection,
            Map<String, CachedAlgorithmGraph> selectedDependencies,
            SharedArtifactCatalog snapshotSharedArtifacts,
            ExecutionContext executionContext) {
        Objects.requireNonNull(inspection, "inspection must not be null");
        Objects.requireNonNull(executionContext, "executionContext must not be null");
        Objects.requireNonNull(selectedDependencies, "selectedDependencies must not be null");
        Objects.requireNonNull(snapshotSharedArtifacts, "snapshotSharedArtifacts must not be null");
        requireOpen();
        SelectedSharedArtifacts sharedArtifacts = snapshotSharedArtifacts.requiredBy(inspection);
        DownloadedAlgorithmParameters resolvedParameters = inspection.metadata.hasParameter()
                        && inspection.metadata.latestAlgorithmParameter() == null
                ? snapshotSharedArtifacts.stagedParameters(inspection)
                : null;
        AlgorithmMetadata resolvedMetadata = resolveParameterIdentity(inspection, resolvedParameters);
        GraphKey graphKey = graphKey(
                resolvedMetadata,
                inspection.definition,
                selectedDependencies,
                sharedArtifacts.providerSelections());
        synchronized (lifecycleLock) {
            requireOpen();
            GraphOwner cached = liveGraphs.get(graphKey);
            if (cached != null) {
                cached.references++;
                return new CachedAlgorithmGraph(cached);
            }
        }
        CachedAlgorithmGraph loaded = new CachedAlgorithmGraph(loadGraph(
                graphKey,
                inspection,
                resolvedMetadata,
                resolvedParameters == null
                        ? snapshotSharedArtifacts.stagedParameters(inspection)
                        : resolvedParameters,
                selectedDependencies,
                sharedArtifacts,
                executionContext));
        try {
            synchronized (lifecycleLock) {
                requireOpen();
                liveGraphs.put(graphKey, loaded.owner);
            }
            return loaded;
        } catch (RuntimeException | Error failure) {
            Closeables.closeAfterFailure(failure, loaded);
            throw failure;
        }
    }

    /** Stops new preparation; outstanding leases remain responsible for their resources. */
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            liveGraphs.clear();
            liveSharedNodes.clear();
            artifactHolders.clear();
            sharedParameterMetadata.clear();
        }
        if (algorithmAgeMetrics != null) {
            algorithmAgeMetrics.close();
        }
    }

    private GraphOwner loadGraph(
            GraphKey graphKey,
            AlgorithmArtifactInspection inspection,
            AlgorithmMetadata algorithmMetadata,
            DownloadedAlgorithmParameters stagedRootParameters,
            Map<String, CachedAlgorithmGraph> selectedDependencies,
            SelectedSharedArtifacts sharedArtifacts,
            ExecutionContext executionContext) {
        AlgorithmId algorithmId = algorithmMetadata.algorithmId();
        String algorithmParameterId = algorithmMetadata.latestAlgorithmParameter();

        ArtifactLease rootArtifact = inspection.artifact;
        List<ArtifactLease> retainedArtifacts = List.of();
        List<CachedAlgorithmGraph> retainedDependencies = List.of();
        AlgorithmGraph<?> graph = null;
        AlgorithmAgeMetricLease ageMetricLease = AlgorithmAgeMetricLease.NOOP;
        try {
            retainedArtifacts = retainArtifacts(rootArtifact, sharedArtifacts);
            retainedDependencies = retainGraphs(selectedDependencies);
            long loadStartNanos = System.nanoTime();
            graph = algorithmDownloader.loadAlgorithmGraph(
                    algorithmMetadata,
                    rootArtifact.artifact(),
                    stagedRootParameters == null ? null : stagedRootParameters.file(),
                    applicationBindings,
                    algorithmGraphs(selectedDependencies),
                    rootArtifact.source(),
                    sharedArtifacts.providers(),
                    new RuntimeSharedNodeInterner(
                            sharedArtifacts.sharedNodeRevisions()),
                    executionContext);
            validateLoadedGraph(graph, algorithmMetadata);
            if (algorithmMetadata.hasParameter()) {
                ageMetricLease = registerAgeMetric(graph.root(), algorithmMetadata);
            }

            long loadDurationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - loadStartNanos);
            if (algorithmMetadata.hasParameter()) {
                LOG.info(
                        "Loaded algorithm {} parameter {} in {} ms",
                        algorithmId.value(),
                        algorithmParameterId,
                        loadDurationMillis);
            } else {
                LOG.info("Loaded parameterless algorithm {} in {} ms", algorithmId.value(), loadDurationMillis);
            }
            return new GraphOwner(
                    lifecycleLock,
                    liveGraphs,
                    graphKey,
                    graph,
                    retainedDependencies,
                    retainedArtifacts,
                    ageMetricLease);
        } catch (RuntimeException | Error failure) {
            if (graph != null) {
                try {
                    graph.close();
                } catch (Throwable closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            Closeables.closeAfterFailure(
                    failure,
                    ageMetricLease);
            closeAfterFailure(retainedDependencies, failure);
            closeAfterFailure(retainedArtifacts, failure);
            throw failure;
        }
    }

    private static AlgorithmMetadata resolveParameterIdentity(
            AlgorithmArtifactInspection inspection,
            DownloadedAlgorithmParameters stagedParameters) {
        AlgorithmMetadata configured = inspection.metadata;
        if (!configured.hasParameter() || configured.latestAlgorithmParameter() != null) {
            return configured;
        }
        AlgorithmParameterMetadata embedded = AlgorithmUtils.readAlgorithmParameterMetadata(
                configured.algorithmId(),
                Objects.requireNonNull(stagedParameters, "stagedParameters must not be null").file(),
                true);
        return configured.withParameterId(embedded.parameterId());
    }

    private ArtifactLease acquireArtifact(AlgorithmMetadata algorithmMetadata) {
        AlgorithmId algorithmId = algorithmMetadata.algorithmId();
        synchronized (lifecycleLock) {
            requireOpen();
            ArtifactHolder cached = artifactHolders.get(algorithmId);
            if (cached != null) {
                cached.references++;
                return new ArtifactLease(cached);
            }
        }
        LOG.info("Downloading algorithm jar for {}", algorithmId);
        DownloadedAlgorithmArtifact artifact = algorithmDownloader.downloadAlgorithmArtifact(algorithmMetadata);
        ArtifactLease loaded;
        try {
            loaded = new ArtifactLease(new ArtifactHolder(
                    lifecycleLock,
                    artifactHolders,
                    algorithmMetadata,
                    algorithmMetadata.absoluteS3AlgorithmJarPath(),
                    artifact));
        } catch (RuntimeException | Error failure) {
            Closeables.closeAfterFailure(failure, artifact);
            throw failure;
        }
        try {
            synchronized (lifecycleLock) {
                requireOpen();
                artifactHolders.put(algorithmId, loaded.owner);
            }
            return loaded;
        } catch (RuntimeException | Error failure) {
            Closeables.closeAfterFailure(failure, loaded);
            throw failure;
        }
    }

    private static void validateLoadedGraph(AlgorithmGraph<?> graph, AlgorithmMetadata algorithmMetadata) {
        AlgorithmInstance<?> algorithmInstance = graph.root();
        AlgorithmId expectedAlgorithmId = algorithmMetadata.algorithmId();
        if (!algorithmInstance.algorithmDefinition().algorithmId().equals(expectedAlgorithmId)) {
            throw new IllegalStateException("Loaded algorithm graph has wrong algorithm ID");
        }
        if (algorithmMetadata.hasParameter()) {
            if (algorithmInstance.algorithmParameterMetadata() == null) {
                throw new IllegalStateException(
                        "Parameterized algorithm loaded without parameter metadata for " + expectedAlgorithmId);
            }
            if (!algorithmInstance.algorithmParameterMetadata().parameterId()
                    .equals(algorithmMetadata.latestAlgorithmParameter())) {
                throw new IllegalStateException(
                        "Downloaded algorithm parameter has wrong version. Expected: "
                                + algorithmMetadata.latestAlgorithmParameter()
                                + " but file contained "
                                + algorithmInstance.algorithmParameterMetadata().parameterId());
            }
        } else if (algorithmInstance.algorithmParameterMetadata() != null) {
            throw new IllegalStateException(
                    "Parameterless algorithm unexpectedly loaded parameter metadata for " + expectedAlgorithmId);
        }
    }

    private AlgorithmAgeMetricLease registerAgeMetric(
            AlgorithmInstance<?> algorithmInstance,
            AlgorithmMetadata algorithmMetadata) {
        if (algorithmAgeMetrics == null) {
            return AlgorithmAgeMetricLease.NOOP;
        }
        String fullAlgorithmName = String.format(
                "%s@%s",
                algorithmMetadata.algorithmName(),
                algorithmMetadata.algorithmVersion());
        return algorithmAgeMetrics.acquire(
                fullAlgorithmName,
                algorithmMetadata.latestAlgorithmParameter(),
                algorithmInstance.algorithmParameterMetadata().ranAt());
    }

    private static GraphKey graphKey(
            AlgorithmMetadata algorithmMetadata,
            AlgorithmDefinition algorithmDefinition,
            Map<String, CachedAlgorithmGraph> selectedDependencies,
            Map<AlgorithmId, ProviderSelection> sharedArtifacts) {
        if (!algorithmDefinition.algorithmId().equals(algorithmMetadata.algorithmId())) {
            throw new IllegalArgumentException(
                    "Algorithm definition " + algorithmDefinition.algorithmId()
                            + " does not match configured selection " + algorithmMetadata.algorithmId());
        }
        String parameterId = algorithmMetadata.hasParameter()
                ? algorithmMetadata.latestAlgorithmParameter()
                : ParameterizedAlgorithmId.NO_PARAMETER_ID;
        return new GraphKey(
                new ParameterizedAlgorithmId(
                        algorithmDefinition.hyperparameterizedAlgorithmId(),
                        parameterId),
                graphKeys(selectedDependencies),
                sharedArtifacts);
    }

    private static AlgorithmGraphDependencies algorithmGraphs(
            Map<String, CachedAlgorithmGraph> selectedDependencies) {
        if (selectedDependencies.isEmpty()) {
            return AlgorithmGraphDependencies.empty();
        }
        Map<String, AlgorithmGraph<?>> graphs = new java.util.TreeMap<>();
        selectedDependencies.forEach((dependencyName, graph) -> graphs.put(dependencyName, graph.graph()));
        return new AlgorithmGraphDependencies(graphs);
    }

    private static List<CachedAlgorithmGraph> retainGraphs(
            Map<String, CachedAlgorithmGraph> selectedDependencies) {
        LinkedHashMap<GraphOwner, CachedAlgorithmGraph> retained = new LinkedHashMap<>();
        try {
            for (CachedAlgorithmGraph dependency : selectedDependencies.values()) {
                retained.computeIfAbsent(dependency.owner, ignored -> dependency.retain());
            }
            return List.copyOf(retained.values());
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(retained.values(), failure);
            throw failure;
        }
    }

    private static List<ArtifactLease> retainArtifacts(
            ArtifactLease rootArtifact,
            SelectedSharedArtifacts sharedArtifacts) {
        LinkedHashMap<ArtifactHolder, ArtifactLease> retained = new LinkedHashMap<>();
        try {
            retainArtifact(rootArtifact, retained);
            for (ArtifactLease artifact : sharedArtifacts.artifacts().values()) {
                retainArtifact(artifact, retained);
            }
            return List.copyOf(retained.values());
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(retained.values(), failure);
            throw failure;
        }
    }

    private static void retainArtifact(
            ArtifactLease artifact,
            Map<ArtifactHolder, ArtifactLease> retained) {
        retained.computeIfAbsent(artifact.owner, ignored -> artifact.retain());
    }

    private static void closeAfterFailure(
            Collection<? extends AutoCloseable> resources,
            Throwable failure) {
        Closeables.closeAfterFailure(
                failure,
                resources.toArray(AutoCloseable[]::new));
    }

    private static Map<String, GraphKey> graphKeys(
            Map<String, CachedAlgorithmGraph> selectedDependencies) {
        Map<String, GraphKey> keys = new java.util.TreeMap<>();
        selectedDependencies.forEach((dependencyName, graph) -> keys.put(dependencyName, graph.graphKey()));
        return keys;
    }

    private static Map<AlgorithmId, ProviderSelection> immutableProviderSelections(
            Map<AlgorithmId, ProviderSelection> selections) {
        Objects.requireNonNull(selections, "provider selections must not be null");
        java.util.TreeMap<AlgorithmId, ProviderSelection> sorted = new java.util.TreeMap<>(
                Comparator.comparing(AlgorithmId::algorithmName)
                        .thenComparing(AlgorithmId::algorithmVersion));
        sorted.putAll(selections);
        return Collections.unmodifiableMap(sorted);
    }

    private void requireOpen() {
        synchronized (lifecycleLock) {
            if (closed) {
                throw new IllegalStateException("Algorithm repository is closed");
            }
        }
    }

    private CachedSharedNode acquireSharedNode(SharedNodeRevision revision) {
        synchronized (lifecycleLock) {
            requireOpen();
            GraphOwner cached = liveSharedNodes.get(revision);
            if (cached == null) {
                return null;
            }
            cached.references++;
            return new CachedSharedNode(cached);
        }
    }

    private record GraphKey(
            ParameterizedAlgorithmId algorithm,
            Map<String, GraphKey> dependencies,
            Map<AlgorithmId, ProviderSelection> sharedArtifacts) {
        private GraphKey {
            algorithm = Objects.requireNonNull(algorithm, "algorithm must not be null");
            dependencies = immutableGraphKeys(dependencies);
            sharedArtifacts = immutableProviderSelections(sharedArtifacts);
        }

        private static Map<String, GraphKey> immutableGraphKeys(
                Map<String, ? extends GraphKey> dependencies) {
            return Collections.unmodifiableMap(new java.util.TreeMap<>(
                    Objects.requireNonNull(dependencies, "dependencies must not be null")));
        }
    }

    /** One artifact inspection retained while a serving snapshot is prepared. */
    static final class AlgorithmArtifactInspection implements AutoCloseable {
        private final AlgorithmMetadata metadata;
        private final ArtifactLease artifact;
        private final AlgorithmDefinition definition;
        private final Set<String> reachableSlotNames;
        private final Map<AlgorithmId, AlgorithmDefinition> packagedDefinitions;
        private final Set<AlgorithmId> sharedDependencies;

        private AlgorithmArtifactInspection(
                AlgorithmMetadata metadata,
                ArtifactLease artifact,
                AlgorithmDefinition definition,
                Set<String> reachableSlotNames,
                Map<AlgorithmId, AlgorithmDefinition> packagedDefinitions,
                Set<AlgorithmId> sharedDependencies) {
            this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
            this.artifact = Objects.requireNonNull(artifact, "artifact must not be null");
            this.definition = Objects.requireNonNull(definition, "definition must not be null");
            this.reachableSlotNames = Set.copyOf(
                    Objects.requireNonNull(reachableSlotNames, "reachableSlotNames must not be null"));
            this.packagedDefinitions = Map.copyOf(
                    Objects.requireNonNull(packagedDefinitions, "packagedDefinitions must not be null"));
            this.sharedDependencies = Set.copyOf(
                    Objects.requireNonNull(sharedDependencies, "sharedDependencies must not be null"));
        }

        AlgorithmDefinition definition() {
            return definition;
        }

        Set<String> reachableSlotNames() {
            return reachableSlotNames;
        }

        @Override
        public void close() {
            artifact.close();
        }
    }

    /** Snapshot-wide code and freshest-parameter selection for statically shared dependencies. */
    static final class SharedArtifactCatalog implements AutoCloseable {
        private static final Comparator<AlgorithmId> BY_ALGORITHM_ID = Comparator
                .comparing(AlgorithmId::algorithmName)
                .thenComparing(AlgorithmId::algorithmVersion);
        private final Map<AlgorithmId, ArtifactLease> providers;
        private final Map<AlgorithmId, SharedParameterSelection> parameters;
        private final Map<AlgorithmId, ProviderSelection> providerSelections;
        private final Map<AlgorithmId, SharedNodeRevision> sharedNodeRevisions;
        private final Map<AlgorithmId, CachedSharedNode> retainedSharedNodes;
        private final AlgorithmDownloader downloader;
        private final Map<ParameterArchiveKey, DownloadedAlgorithmParameters> ownedParameterArchives;

        private SharedArtifactCatalog(
                Map<AlgorithmId, ArtifactLease> providers,
                Map<AlgorithmId, SharedParameterSelection> parameters,
                Map<AlgorithmId, ProviderSelection> providerSelections,
                Map<AlgorithmId, SharedNodeRevision> sharedNodeRevisions,
                Map<AlgorithmId, CachedSharedNode> retainedSharedNodes,
                AlgorithmDownloader downloader,
                Map<ParameterArchiveKey, DownloadedAlgorithmParameters> ownedParameterArchives) {
            this.providers = retainProviders(providers);
            this.parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
            this.providerSelections = immutableProviderSelections(providerSelections);
            this.sharedNodeRevisions = Map.copyOf(sharedNodeRevisions);
            this.retainedSharedNodes = Map.copyOf(retainedSharedNodes);
            this.downloader = Objects.requireNonNull(downloader, "downloader must not be null");
            this.ownedParameterArchives = new LinkedHashMap<>(ownedParameterArchives);
        }

        private static SharedArtifactCatalog create(
                Collection<AlgorithmArtifactInspection> inspections,
                AlgorithmDownloader downloader,
                SharedParameterMetadataCache metadataCache,
                Function<SharedNodeRevision, CachedSharedNode> acquireSharedNode,
                AlgorithmDependencies applicationBindings) {
            ArrayList<ProviderCandidate> candidates = new ArrayList<>();
            java.util.TreeSet<AlgorithmId> required = new java.util.TreeSet<>(BY_ALGORITHM_ID);
            ArrayList<AlgorithmArtifactInspection> resolvedInspections = new ArrayList<>();
            for (AlgorithmArtifactInspection inspection : inspections) {
                AlgorithmArtifactInspection resolved = Objects.requireNonNull(
                        inspection,
                        "inspections must not contain null");
                resolvedInspections.add(resolved);
                required.addAll(resolved.sharedDependencies);
                resolved.packagedDefinitions.values().forEach(definition -> candidates.add(
                        new ProviderCandidate(resolved.artifact, definition)));
            }
            candidates.sort(Comparator
                    .comparing((ProviderCandidate candidate) -> candidate.artifact().source())
                    .thenComparing(candidate -> candidate.artifact().algorithmId().value())
                    .thenComparing(candidate -> candidate.definition().algorithmId().value()));
            LinkedHashMap<AlgorithmId, List<ProviderCandidate>> candidatesByAlgorithmId = new LinkedHashMap<>();
            for (ProviderCandidate candidate : candidates) {
                candidatesByAlgorithmId.computeIfAbsent(
                                candidate.definition().algorithmId(), ignored -> new ArrayList<>())
                        .add(candidate);
            }

            ArrayList<ParameterArchiveCandidate> parameterArchives = new ArrayList<>();
            LinkedHashMap<ParameterArchiveKey, DownloadedAlgorithmParameters> stagedArchives = new LinkedHashMap<>();
            LinkedHashMap<AlgorithmId, CachedSharedNode> retainedSharedNodes = new LinkedHashMap<>();
            LinkedHashSet<ParameterArchiveKey> activeParameterArchives = new LinkedHashSet<>();
            for (AlgorithmArtifactInspection inspection : resolvedInspections) {
                if (inspection.metadata.hasParameter()
                        && inspection.packagedDefinitions.keySet().stream().anyMatch(required::contains)) {
                    activeParameterArchives.add(ParameterArchiveKey.from(inspection.metadata));
                }
            }
            metadataCache.retainOnly(activeParameterArchives);
            try {
                for (AlgorithmArtifactInspection inspection : resolvedInspections) {
                    if (inspection.metadata.hasParameter()
                            && inspection.packagedDefinitions.keySet().stream().anyMatch(required::contains)) {
                        ParameterArchiveKey key = ParameterArchiveKey.from(inspection.metadata);
                        ParameterArchiveInspection parameterInspection = metadataCache.getOrInspect(
                                key,
                                () -> {
                                    DownloadedAlgorithmParameters archive =
                                            downloader.downloadAlgorithmParameters(inspection.metadata);
                                    stagedArchives.put(key, archive);
                                    return new ParameterArchiveInspection(
                                            inspectParameterMetadata(inspection, archive),
                                            archive);
                                });
                        parameterArchives.add(new ParameterArchiveCandidate(
                                inspection,
                                key,
                                parameterInspection.metadata(),
                                parameterInspection.archive()));
                    }
                }

                LinkedHashMap<AlgorithmId, ArtifactLease> providers = new LinkedHashMap<>();
                LinkedHashMap<AlgorithmId, SharedParameterSelection> parameters = new LinkedHashMap<>();
                LinkedHashMap<AlgorithmId, SharedParameterSelection> selectedParameterCandidates =
                        new LinkedHashMap<>();
                LinkedHashMap<AlgorithmId, ProviderSelection> selections = new LinkedHashMap<>();
                LinkedHashMap<AlgorithmId, ProviderCandidate> selectedProviders = new LinkedHashMap<>();
                for (AlgorithmId requiredId : required) {
                    List<ProviderCandidate> providersForAlgorithm = candidatesByAlgorithmId.get(requiredId);
                    if (providersForAlgorithm == null || providersForAlgorithm.isEmpty()) {
                        throw new IllegalArgumentException("No provider defines shared dependency " + requiredId);
                    }
                    // Code is selected once by name@version from a deterministic provider. Parameter
                    // selection is independent: every refresh chooses the newest logical data date,
                    // then the latest rerun of that date, for this shared algorithm.
                    ProviderCandidate selectedProvider = providersForAlgorithm.getFirst();
                    SharedParameterSelection selectedParameters = freshestParameters(
                            requiredId,
                            parameterArchives);
                    providers.put(requiredId, selectedProvider.artifact());
                    selectedProviders.put(requiredId, selectedProvider);
                    if (selectedParameters != null) {
                        selectedParameterCandidates.put(requiredId, selectedParameters);
                    }
                    ProviderSelection providerSelection = new ProviderSelection(
                            selectedProvider.artifact().algorithmId(),
                            selectedProvider.definition().algorithmId(),
                            Optional.ofNullable(selectedParameters)
                                    .map(SharedParameterSelection::revision));
                    selections.put(requiredId, providerSelection);
                }

                Map<AlgorithmId, SharedNodeRevision> sharedNodeRevisions = sharedNodeRevisions(
                        selectedProviders,
                        selections,
                        applicationBindings);
                for (AlgorithmId requiredId : required) {
                    SharedParameterSelection selectedParameters = selectedParameterCandidates.get(requiredId);
                    SharedNodeRevision revision = sharedNodeRevisions.get(requiredId);
                    CachedSharedNode retainedNode = acquireSharedNode.apply(revision);
                    if (retainedNode != null) {
                        retainedSharedNodes.put(requiredId, retainedNode);
                        if (selectedParameters != null) {
                            parameters.put(requiredId, selectedParameters.withoutArchive());
                        }
                        continue;
                    }
                    if (selectedParameters != null) {
                        DownloadedAlgorithmParameters selectedArchive = selectedParameters.archive();
                        if (selectedArchive == null) {
                            selectedArchive = stagedArchives.computeIfAbsent(
                                    selectedParameters.key(),
                                    ignored -> downloader.downloadAlgorithmParameters(
                                            selectedParameters.sourceMetadata()));
                        }
                        parameters.put(requiredId, selectedParameters.withArchive(selectedArchive));
                    }
                }
                return new SharedArtifactCatalog(
                        providers,
                        parameters,
                        selections,
                        sharedNodeRevisions,
                        retainedSharedNodes,
                        downloader,
                        stagedArchives);
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(retainedSharedNodes.values(), failure);
                closeParameterArchives(stagedArchives.values(), failure);
                throw failure;
            }
        }

        private static Map<AlgorithmId, SharedNodeRevision> sharedNodeRevisions(
                Map<AlgorithmId, ProviderCandidate> selectedProviders,
                Map<AlgorithmId, ProviderSelection> selections,
                AlgorithmDependencies applicationBindings) {
            Objects.requireNonNull(applicationBindings, "applicationBindings must not be null");
            LinkedHashMap<AlgorithmId, Set<AlgorithmId>> directDependencies = new LinkedHashMap<>();
            selectedProviders.forEach((algorithmId, provider) -> directDependencies.put(
                    algorithmId,
                    directSharedDependencies(
                            provider,
                            applicationBindings)));

            LinkedHashMap<AlgorithmId, SharedNodeRevision> revisions = new LinkedHashMap<>();
            for (AlgorithmId algorithmId : selectedProviders.keySet()) {
                resolveSharedNodeRevision(
                        algorithmId,
                        directDependencies,
                        selections,
                        revisions,
                        new LinkedHashSet<>());
            }
            return Map.copyOf(revisions);
        }

        private static Set<AlgorithmId> directSharedDependencies(
                ProviderCandidate provider,
                AlgorithmDependencies applicationBindings) {
            AlgorithmGraphInspection inspection = provider.artifact().artifact().inspectSharedDependencies(
                    provider.definition(),
                    applicationBindings);
            if (!inspection.reachableSlotNames().isEmpty()) {
                String slotName = inspection.reachableSlotNames().iterator().next();
                throw new IllegalArgumentException(
                        "Shared algorithm " + provider.definition().algorithmId()
                                + " cannot contain slot dependency " + slotName
                                + "; a shared instance has one runtime identity across root compositions");
            }
            return Set.copyOf(inspection.sharedDependencies());
        }

        private static SharedNodeRevision resolveSharedNodeRevision(
                AlgorithmId algorithmId,
                Map<AlgorithmId, Set<AlgorithmId>> directDependencies,
                Map<AlgorithmId, ProviderSelection> selections,
                Map<AlgorithmId, SharedNodeRevision> resolved,
                Set<AlgorithmId> activePath) {
            SharedNodeRevision existing = resolved.get(algorithmId);
            if (existing != null) {
                return existing;
            }
            if (!activePath.add(algorithmId)) {
                throw new IllegalArgumentException(
                        "Cyclic shared dependency while selecting shared revisions: " + activePath);
            }
            try {
                ProviderSelection ownSelection = selections.get(algorithmId);
                if (ownSelection == null) {
                    throw new IllegalStateException("No provider selection for shared algorithm " + algorithmId);
                }
                LinkedHashMap<AlgorithmId, ProviderSelection> transitiveSelections = new LinkedHashMap<>();
                transitiveSelections.put(algorithmId, ownSelection);
                for (AlgorithmId dependency : directDependencies.getOrDefault(algorithmId, Set.of())) {
                    SharedNodeRevision dependencyRevision = resolveSharedNodeRevision(
                            dependency,
                            directDependencies,
                            selections,
                            resolved,
                            activePath);
                    transitiveSelections.putAll(dependencyRevision.transitiveSelections());
                }
                SharedNodeRevision revision = new SharedNodeRevision(algorithmId, transitiveSelections);
                resolved.put(algorithmId, revision);
                return revision;
            } finally {
                activePath.remove(algorithmId);
            }
        }

        private static Map<AlgorithmId, AlgorithmParameterMetadata> inspectParameterMetadata(
                AlgorithmArtifactInspection inspection,
                DownloadedAlgorithmParameters archive) {
            LinkedHashMap<AlgorithmId, AlgorithmParameterMetadata> metadata = new LinkedHashMap<>();
            for (AlgorithmId algorithmId : inspection.packagedDefinitions.keySet()) {
                if (containsParameterMetadata(archive.file(), algorithmId)) {
                    metadata.put(
                            algorithmId,
                            AlgorithmUtils.readAlgorithmParameterMetadata(
                                    algorithmId,
                                    archive.file(),
                                    true));
                }
            }
            return Map.copyOf(metadata);
        }

        private static SharedParameterSelection freshestParameters(
                AlgorithmId algorithmId,
                List<ParameterArchiveCandidate> archives) {
            Comparator<SharedParameterSelection> byFreshness = Comparator
                    .comparing(SharedArtifactCatalog::lastTestTime)
                    .thenComparing(selection -> selection.metadata().ranAt())
                    .thenComparing(selection -> selection.metadata().parameterId())
                    .thenComparing(selection -> selection.key().source());
            SharedParameterSelection freshest = null;
            for (ParameterArchiveCandidate candidate : archives) {
                AlgorithmParameterMetadata metadata = candidate.metadata().get(algorithmId);
                if (metadata == null) {
                    continue;
                }
                SharedParameterSelection selection = new SharedParameterSelection(
                        metadata,
                        candidate.inspection().metadata,
                        candidate.archive(),
                        candidate.key());
                lastTestTime(selection);
                if (freshest == null || byFreshness.compare(selection, freshest) > 0) {
                    freshest = selection;
                }
            }
            return freshest;
        }

        private static Instant lastTestTime(SharedParameterSelection selection) {
            return selection.metadata().lastTestTime().orElseThrow(() -> new IllegalArgumentException(
                    "Shared parameter metadata for " + selection.metadata().algorithmId()
                            + " from " + selection.key().source()
                            + " must contain last_test_time"));
        }

        private static boolean containsParameterMetadata(File archive, AlgorithmId algorithmId) {
            try (ZipFile zip = new ZipFile(archive)) {
                return zip.getEntry(algorithmId.algorithmName() + "/algorithm-parameters.json") != null;
            } catch (IOException error) {
                throw new MalformedAlgorithmException(error);
            }
        }

        private static void closeParameterArchives(
                Collection<DownloadedAlgorithmParameters> archives,
                Throwable failure) {
            for (DownloadedAlgorithmParameters archive : archives) {
                try {
                    archive.close();
                } catch (Throwable closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
        }

        private static Map<AlgorithmId, ArtifactLease> retainProviders(
                Map<AlgorithmId, ArtifactLease> providers) {
            LinkedHashMap<AlgorithmId, ArtifactLease> retained = new LinkedHashMap<>();
            try {
                providers.forEach((algorithmId, artifact) ->
                        retained.put(algorithmId, artifact.retain()));
                return Collections.unmodifiableMap(retained);
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(retained.values(), failure);
                throw failure;
            }
        }

        @Override
        public void close() {
            ArrayList<AutoCloseable> resources = new ArrayList<>();
            resources.addAll(ownedParameterArchives.values());
            resources.addAll(retainedSharedNodes.values());
            resources.addAll(providers.values());
            Closeables.closeAll(
                    "Failed to close shared artifact catalog",
                    resources.toArray(AutoCloseable[]::new));
        }

        private DownloadedAlgorithmParameters stagedParameters(
                AlgorithmArtifactInspection inspection) {
            if (!inspection.metadata.hasParameter()) {
                return null;
            }
            return ownedParameterArchives.computeIfAbsent(
                    ParameterArchiveKey.from(inspection.metadata),
                    ignored -> downloader.downloadAlgorithmParameters(inspection.metadata));
        }

        private SelectedSharedArtifacts requiredBy(AlgorithmArtifactInspection inspection) {
            Objects.requireNonNull(inspection, "inspection must not be null");
            LinkedHashMap<AlgorithmId, ArtifactLease> requiredProviders = new LinkedHashMap<>();
            LinkedHashMap<AlgorithmId, SharedParameterSelection> requiredParameters = new LinkedHashMap<>();
            LinkedHashMap<AlgorithmId, ProviderSelection> requiredSelections = new LinkedHashMap<>();
            LinkedHashMap<AlgorithmId, SharedNodeRevision> requiredRevisions = new LinkedHashMap<>();
            java.util.TreeSet<AlgorithmId> requiredIds = new java.util.TreeSet<>(BY_ALGORITHM_ID);
            for (AlgorithmId directDependency : inspection.sharedDependencies) {
                SharedNodeRevision revision = sharedNodeRevisions.get(directDependency);
                if (revision == null) {
                    throw new IllegalStateException(
                            "Shared provider catalog is missing revision " + directDependency);
                }
                requiredIds.addAll(revision.transitiveSelections().keySet());
            }
            requiredIds.forEach(algorithmId -> {
                        ArtifactLease provider = providers.get(algorithmId);
                        ProviderSelection selection = providerSelections.get(algorithmId);
                        if (provider == null || selection == null) {
                            throw new IllegalStateException(
                                    "Shared provider catalog is missing " + algorithmId);
                        }
                        requiredProviders.put(algorithmId, provider);
                        SharedNodeRevision revision = sharedNodeRevisions.get(algorithmId);
                        if (revision == null) {
                            throw new IllegalStateException(
                                    "Shared provider catalog is missing revision " + algorithmId);
                        }
                        requiredRevisions.put(algorithmId, revision);
                        SharedParameterSelection parameterSelection = parameters.get(algorithmId);
                        if (parameterSelection != null) {
                            requiredParameters.put(algorithmId, parameterSelection);
                        }
                        requiredSelections.put(algorithmId, selection);
                    });
            return new SelectedSharedArtifacts(
                    requiredProviders,
                    requiredParameters,
                    requiredSelections,
                    requiredRevisions);
        }
    }

    /** Immutable borrowed selection, valid within its owning catalog's preparation scope. */
    private record SelectedSharedArtifacts(
            Map<AlgorithmId, ArtifactLease> artifacts,
            Map<AlgorithmId, SharedParameterSelection> parameters,
            Map<AlgorithmId, ProviderSelection> providerSelections,
            Map<AlgorithmId, SharedNodeRevision> sharedNodeRevisions) {
        private SelectedSharedArtifacts {
            artifacts = Map.copyOf(artifacts);
            parameters = Map.copyOf(parameters);
            providerSelections = immutableProviderSelections(providerSelections);
            sharedNodeRevisions = Map.copyOf(sharedNodeRevisions);
        }

        private Map<AlgorithmId, AlgorithmArtifactProvider> providers() {
            LinkedHashMap<AlgorithmId, AlgorithmArtifactProvider> resolved = new LinkedHashMap<>();
            artifacts.forEach((algorithmId, holder) -> {
                SharedParameterSelection selection = parameters.get(algorithmId);
                File parameterFile = selection == null || selection.archive() == null
                        ? null
                        : selection.archive().file();
                resolved.put(algorithmId, holder.artifact().asProvider(holder.source(), parameterFile));
            });
            return Map.copyOf(resolved);
        }
    }

    private record ProviderCandidate(
            ArtifactLease artifact,
            AlgorithmDefinition definition) {
        private ProviderCandidate {
            Objects.requireNonNull(artifact, "artifact must not be null");
            Objects.requireNonNull(definition, "definition must not be null");
        }
    }

    private record ParameterArchiveCandidate(
            AlgorithmArtifactInspection inspection,
            ParameterArchiveKey key,
            Map<AlgorithmId, AlgorithmParameterMetadata> metadata,
            DownloadedAlgorithmParameters archive) {
        private ParameterArchiveCandidate {
            Objects.requireNonNull(inspection, "inspection must not be null");
            Objects.requireNonNull(key, "key must not be null");
            metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
        }
    }

    private record ParameterArchiveInspection(
            Map<AlgorithmId, AlgorithmParameterMetadata> metadata,
            DownloadedAlgorithmParameters archive) {
        private ParameterArchiveInspection {
            metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
        }
    }

    private record ParameterArchiveKey(
            AlgorithmId algorithmId,
            Optional<String> expectedParameterId,
            String source) {
        private ParameterArchiveKey {
            Objects.requireNonNull(algorithmId, "algorithmId must not be null");
            Objects.requireNonNull(expectedParameterId, "expectedParameterId must not be null");
            Objects.requireNonNull(source, "source must not be null");
        }

        private static ParameterArchiveKey from(AlgorithmMetadata metadata) {
            return new ParameterArchiveKey(
                    metadata.algorithmId(),
                    Optional.ofNullable(metadata.latestAlgorithmParameter()),
                    metadata.absoluteS3AlgorithmParameterPath());
        }
    }

    private record SharedParameterSelection(
            AlgorithmParameterMetadata metadata,
            AlgorithmMetadata sourceMetadata,
            DownloadedAlgorithmParameters archive,
            ParameterArchiveKey key) {
        private SharedParameterSelection {
            Objects.requireNonNull(metadata, "metadata must not be null");
            Objects.requireNonNull(sourceMetadata, "sourceMetadata must not be null");
            Objects.requireNonNull(key, "key must not be null");
        }

        private SharedParameterSelection withArchive(DownloadedAlgorithmParameters stagedArchive) {
            return new SharedParameterSelection(
                    metadata,
                    sourceMetadata,
                    Objects.requireNonNull(stagedArchive, "stagedArchive must not be null"),
                    key);
        }

        private SharedParameterSelection withoutArchive() {
            return new SharedParameterSelection(
                    metadata,
                    sourceMetadata,
                    null,
                    key);
        }

        private SharedParameterRevision revision() {
            return new SharedParameterRevision(
                    metadata.parameterId(),
                    metadata.lastTestTime().orElseThrow(),
                    metadata.ranAt());
        }
    }

    private record SharedParameterRevision(
            String parameterId,
            Instant lastTestTime,
            Instant ranAt) {
        private SharedParameterRevision {
            Objects.requireNonNull(parameterId, "parameterId must not be null");
            Objects.requireNonNull(lastTestTime, "lastTestTime must not be null");
            Objects.requireNonNull(ranAt, "ranAt must not be null");
        }
    }

    private record ProviderSelection(
            AlgorithmId artifactId,
            AlgorithmId provider,
            Optional<SharedParameterRevision> parameters) {
        private ProviderSelection {
            Objects.requireNonNull(artifactId, "artifactId must not be null");
            Objects.requireNonNull(provider, "provider must not be null");
            Objects.requireNonNull(parameters, "parameters must not be null");
        }
    }

    private record SharedNodeRevision(
            AlgorithmId algorithmId,
            Map<AlgorithmId, ProviderSelection> transitiveSelections) {
        private SharedNodeRevision {
            Objects.requireNonNull(algorithmId, "algorithmId must not be null");
            transitiveSelections = immutableProviderSelections(transitiveSelections);
        }
    }

    /** One independently closeable lease on a cached graph owner. */
    static final class CachedAlgorithmGraph implements AutoCloseable {
        private final GraphOwner owner;
        // Written under the lifecycle lock; prediction only reads it while holding a generation lease.
        private volatile boolean released;

        private CachedAlgorithmGraph(GraphOwner owner) {
            this.owner = Objects.requireNonNull(owner, "owner must not be null");
        }

        CachedAlgorithmGraph retain() {
            synchronized (owner.lock) {
                requireOpen();
                owner.references++;
                return new CachedAlgorithmGraph(owner);
            }
        }

        AlgorithmInstance<?> instance() {
            requireOpen();
            return owner.instance;
        }

        AlgorithmRuntimeId runtimeId() {
            requireOpen();
            return owner.runtimeId;
        }

        AlgorithmGraph<?> graph() {
            requireOpen();
            return owner.graph;
        }

        ClassLoader rootArtifactClassLoader() {
            requireOpen();
            return owner.graph.rootArtifactClassLoader();
        }

        private GraphKey graphKey() {
            requireOpen();
            return (GraphKey) owner.identity;
        }

        @Override
        public void close() {
            synchronized (owner.lock) {
                if (released) {
                    return;
                }
                released = true;
                if (!owner.releaseLocked()) {
                    return;
                }
            }
            owner.closeGraph();
        }

        private void requireOpen() {
            if (released) {
                throw new IllegalStateException("Algorithm graph lease is closed");
            }
        }
    }

    /** Reuses live shared nodes across separate root resolvers. */
    private final class RuntimeSharedNodeInterner implements SharedNodeInterner {
        private final Map<AlgorithmId, SharedNodeRevision> sharedNodeRevisions;

        private RuntimeSharedNodeInterner(
                Map<AlgorithmId, SharedNodeRevision> sharedNodeRevisions) {
            this.sharedNodeRevisions = Map.copyOf(
                    Objects.requireNonNull(sharedNodeRevisions, "sharedNodeRevisions must not be null"));
        }

        @Override
        public SharedNode intern(AlgorithmId algorithmId, Supplier<AlgorithmGraph<?>> graphFactory) {
            requireOpen();
            SharedNodeRevision key = sharedNodeRevisions.get(algorithmId);
            if (key == null) {
                throw new IllegalStateException("No provider selection for shared algorithm " + algorithmId);
            }
            CachedSharedNode cached = acquireSharedNode(key);
            if (cached != null) {
                return cached;
            }
            Supplier<AlgorithmGraph<?>> factory = Objects.requireNonNull(
                    graphFactory,
                    "graphFactory must not be null");
            CachedSharedNode loaded = new CachedSharedNode(createSharedNode(key, factory));
            try {
                synchronized (lifecycleLock) {
                    requireOpen();
                    liveSharedNodes.put(key, loaded.owner);
                }
                return loaded;
            } catch (RuntimeException | Error failure) {
                Closeables.closeAfterFailure(failure, loaded);
                throw failure;
            }
        }
    }

    private GraphOwner createSharedNode(
            Object key,
            Supplier<AlgorithmGraph<?>> graphFactory) {
        AlgorithmGraph<?> graph = null;
        try {
            graph = Objects.requireNonNull(
                    graphFactory.get(),
                    "graphFactory must not return null");
            return new GraphOwner(
                    lifecycleLock,
                    liveSharedNodes,
                    key,
                    graph,
                    List.of(),
                    List.of(),
                    AlgorithmAgeMetricLease.NOOP);
        } catch (RuntimeException | Error failure) {
            Closeables.closeAfterFailure(failure, graph);
            throw failure;
        }
    }

    /** A parent-graph-owned lease on one runtime-shared node. */
    private static final class CachedSharedNode implements SharedNodeInterner.SharedNode {
        private final GraphOwner owner;
        private volatile boolean released;

        private CachedSharedNode(GraphOwner owner) {
            this.owner = Objects.requireNonNull(owner, "owner must not be null");
        }

        @Override
        public AlgorithmInstance<?> instance() {
            requireOpen();
            return owner.instance;
        }

        @Override
        public AlgorithmRuntimeId runtimeId() {
            requireOpen();
            return owner.runtimeId;
        }

        @Override
        public void close() {
            synchronized (owner.lock) {
                if (released) {
                    return;
                }
                released = true;
                if (!owner.releaseLocked()) {
                    return;
                }
            }
            owner.closeGraph();
        }

        private void requireOpen() {
            if (released) {
                throw new IllegalStateException("Shared algorithm graph lease is closed");
            }
        }
    }

    private static final class GraphOwner {
        private final Object lock;
        private final Map<?, GraphOwner> registry;
        private final Object identity;
        private final AlgorithmGraph<?> graph;
        private final AlgorithmInstance<?> instance;
        private final AlgorithmRuntimeId runtimeId;
        private int references = 1;
        private final List<CachedAlgorithmGraph> dependencies;
        private final List<ArtifactLease> artifacts;
        private final AlgorithmAgeMetricLease ageMetricLease;

        private GraphOwner(
                Object lock,
                Map<?, GraphOwner> registry,
                Object identity,
                AlgorithmGraph<?> graph,
                List<CachedAlgorithmGraph> dependencies,
                List<ArtifactLease> artifacts,
                AlgorithmAgeMetricLease ageMetricLease) {
            this.lock = lock;
            this.registry = registry;
            this.identity = Objects.requireNonNull(identity, "identity must not be null");
            this.graph = Objects.requireNonNull(graph, "graph must not be null");
            this.instance = graph.root();
            this.runtimeId = graph.runtimeId();
            this.dependencies = List.copyOf(dependencies);
            this.artifacts = List.copyOf(artifacts);
            this.ageMetricLease = ageMetricLease;
        }

        /** Called with the lifecycle lock; the final releaser closes the graph after unlocking. */
        private boolean releaseLocked() {
            if (--references != 0) {
                return false;
            }
            registry.remove(identity, this);
            return true;
        }

        private void closeGraph() {
            ArrayList<AutoCloseable> resources = new ArrayList<>();
            resources.add(graph);
            resources.add(ageMetricLease);
            resources.addAll(dependencies);
            resources.addAll(artifacts);
            try {
                Closeables.closeAll(
                        "Failed to close algorithm graph " + identity,
                        resources.toArray(AutoCloseable[]::new));
                LOG.info("Closed algorithm graph {}", identity);
            } catch (RuntimeException | Error failure) {
                LOG.error("Failed to close algorithm graph {}", identity, failure);
                throw failure;
            }
        }
    }

    private static final class ArtifactHolder {
        private final Object lock;
        private final Map<AlgorithmId, ArtifactHolder> registry;
        private final AlgorithmId algorithmId;
        private final String source;
        private final DownloadedAlgorithmArtifact artifact;
        private int references = 1;

        private ArtifactHolder(
                Object lock,
                Map<AlgorithmId, ArtifactHolder> registry,
                AlgorithmMetadata metadata,
                String source,
                DownloadedAlgorithmArtifact artifact) {
            this.lock = lock;
            this.registry = registry;
            this.algorithmId = Objects.requireNonNull(metadata, "metadata must not be null").algorithmId();
            this.source = Objects.requireNonNull(source, "source must not be null");
            this.artifact = Objects.requireNonNull(artifact, "artifact must not be null");
        }

        private boolean releaseLocked() {
            if (--references != 0) {
                return false;
            }
            registry.remove(algorithmId, this);
            return true;
        }

        private void closeArtifact() {
            try {
                artifact.close();
                LOG.info("Closed algorithm artifact {}", algorithmId);
            } catch (RuntimeException | Error failure) {
                LOG.error("Failed to close algorithm artifact {}", algorithmId, failure);
                throw failure;
            } catch (Exception failure) {
                throw new RuntimeException("Failed to close algorithm artifact " + algorithmId, failure);
            }
        }
    }

    private static final class ArtifactLease implements AutoCloseable {
        private final ArtifactHolder owner;
        private volatile boolean released;

        private ArtifactLease(ArtifactHolder owner) {
            this.owner = Objects.requireNonNull(owner, "owner must not be null");
        }

        private ArtifactLease retain() {
            synchronized (owner.lock) {
                requireOpen();
                owner.references++;
                return new ArtifactLease(owner);
            }
        }

        private AlgorithmId algorithmId() {
            requireOpen();
            return owner.algorithmId;
        }

        private String source() {
            requireOpen();
            return owner.source;
        }

        private DownloadedAlgorithmArtifact artifact() {
            requireOpen();
            return owner.artifact;
        }

        @Override
        public void close() {
            synchronized (owner.lock) {
                if (released) {
                    return;
                }
                released = true;
                if (!owner.releaseLocked()) {
                    return;
                }
            }
            owner.closeArtifact();
        }

        private void requireOpen() {
            if (released) {
                throw new IllegalStateException("Algorithm artifact lease is closed");
            }
        }
    }

    static final class AlgorithmAgeMetrics implements AutoCloseable {
        private final Object lock;
        private final MeterRegistry registry;
        private final Map<AlgorithmAgeMetricKey, AlgorithmAgeMetricState> states = new LinkedHashMap<>();
        private boolean closed;

        AlgorithmAgeMetrics(MeterRegistry registry, Object lock) {
            this.lock = lock;
            this.registry = Objects.requireNonNull(registry, "registry must not be null");
        }

        AlgorithmAgeMetricLease acquire(
                String algorithmName,
                String parameterId,
                Instant ranAt) {
            synchronized (lock) {
                if (closed) {
                    throw new IllegalStateException("Algorithm age metrics are closed");
                }
                AlgorithmAgeMetricKey key = new AlgorithmAgeMetricKey(algorithmName, parameterId);
                AlgorithmAgeMetricState state = states.get(key);
                if (state == null) {
                    state = new AlgorithmAgeMetricState(ranAt);
                    Gauge gauge = Gauge.builder(ALGORITHM_METRICS_NAME, state, AlgorithmAgeMetricState::ageSeconds)
                            .tags(Tags.of(
                                    ALGORITHM_NAME_KEY,
                                    algorithmName,
                                    PARAMETER_ID_KEY,
                                    parameterId))
                            .register(registry);
                    state.gauge(gauge);
                    states.put(key, state);
                } else if (!Objects.equals(state.ranAt(), ranAt)) {
                    throw new IllegalStateException(
                            "Algorithm parameter identity has inconsistent training timestamps: " + key);
                }
                state.retain();
                return new AlgorithmAgeMetricLease(this, key);
            }
        }

        private void releaseLocked(AlgorithmAgeMetricKey key) {
            if (closed) {
                return;
            }
            AlgorithmAgeMetricState state = states.get(key);
            if (state.release()) {
                states.remove(key);
                registry.remove(state.gauge());
            }
        }

        @Override
        public void close() {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                closed = true;
                states.values().forEach(state -> registry.remove(state.gauge()));
                states.clear();
            }
        }
    }

    private record AlgorithmAgeMetricKey(String algorithmName, String parameterId) {
        private AlgorithmAgeMetricKey {
            Objects.requireNonNull(algorithmName, "algorithmName must not be null");
            Objects.requireNonNull(parameterId, "parameterId must not be null");
        }
    }

    private static final class AlgorithmAgeMetricState {
        private final Instant ranAt;
        private int references;
        private Gauge gauge;

        private AlgorithmAgeMetricState(Instant ranAt) {
            this.ranAt = ranAt;
        }

        private Instant ranAt() {
            return ranAt;
        }

        private void gauge(Gauge registeredGauge) {
            gauge = Objects.requireNonNull(registeredGauge, "registeredGauge must not be null");
        }

        private Gauge gauge() {
            return gauge;
        }

        private void retain() {
            references++;
        }

        private boolean release() {
            references--;
            return references == 0;
        }

        private double ageSeconds() {
            return ranAt == null ? -1 : Instant.now().getEpochSecond() - ranAt.getEpochSecond();
        }
    }

    static final class AlgorithmAgeMetricLease implements AutoCloseable {
        private static final AlgorithmAgeMetricLease NOOP = new AlgorithmAgeMetricLease();

        private final AlgorithmAgeMetrics owner;
        private final AlgorithmAgeMetricKey key;
        private boolean released;

        private AlgorithmAgeMetricLease() {
            owner = null;
            key = null;
        }

        private AlgorithmAgeMetricLease(AlgorithmAgeMetrics owner, AlgorithmAgeMetricKey key) {
            this.owner = Objects.requireNonNull(owner, "owner must not be null");
            this.key = Objects.requireNonNull(key, "key must not be null");
        }

        @Override
        public void close() {
            if (owner == null) {
                return;
            }
            synchronized (owner.lock) {
                if (released) {
                    return;
                }
                released = true;
                owner.releaseLocked(key);
            }
        }
    }

    /**
     * Parsed nested metadata retained by immutable root parameter identity across refreshes.
     *
     * <p>Inspections run on the preparation thread, outside the lifecycle lock. Cache updates and
     * repository shutdown use the lifecycle lock.
     */
    private final class SharedParameterMetadataCache {
        private final Map<ParameterArchiveKey, Map<AlgorithmId, AlgorithmParameterMetadata>> values =
                new LinkedHashMap<>();

        private ParameterArchiveInspection getOrInspect(
                ParameterArchiveKey key,
                Supplier<ParameterArchiveInspection> inspector) {
            Objects.requireNonNull(key, "key must not be null");
            synchronized (lifecycleLock) {
                requireOpen();
                Map<AlgorithmId, AlgorithmParameterMetadata> cached = values.get(key);
                if (cached != null) {
                    return new ParameterArchiveInspection(cached, null);
                }
            }
            ParameterArchiveInspection inspected = Objects.requireNonNull(
                    inspector,
                    "inspector must not be null").get();
            synchronized (lifecycleLock) {
                requireOpen();
                values.put(key, inspected.metadata());
            }
            return inspected;
        }

        private void retainOnly(Set<ParameterArchiveKey> activeKeys) {
            synchronized (lifecycleLock) {
                requireOpen();
                values.keySet().retainAll(Objects.requireNonNull(activeKeys, "activeKeys must not be null"));
            }
        }

        private void clear() {
            synchronized (lifecycleLock) {
                values.clear();
            }
        }
    }

}
