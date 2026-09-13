package com.hotvect.onlineutils.serving;

import com.google.common.reflect.TypeToken;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.algorithms.ThemedTopK;
import com.hotvect.api.algorithms.TopK;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.data.topk.ThemedTopKResponse;
import com.hotvect.api.data.topk.TopKRequest;
import com.hotvect.api.data.topk.TopKResponse;
import com.hotvect.onlineutils.experimentmanagement.algodownload.AlgorithmDownloadClient;
import com.hotvect.onlineutils.experimentmanagement.algodownload.AlgorithmDownloader;
import com.hotvect.onlineutils.experimentmanagement.algodownload.S3AlgorithmDownloadClient;
import com.hotvect.onlineutils.experimentmanagement.httpclient.ExperimentManagementServiceClient;
import com.hotvect.onlineutils.util.Closeables;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Framework-neutral, lifecycle-owned EMS algorithm invocation runtime. */
public final class HotvectServingRuntime implements AutoCloseable {
    private final Map<String, ServingSlot> touchpoints;
    private final LiveSlotGraphResolver resolver;
    private final AlgorithmRepository algorithmRepository;
    private final ExperimentManagementServiceClient emsClient;
    private final AlgorithmDownloadClient downloadClient;

    private HotvectServingRuntime(
            Map<String, ServingSlot> touchpoints,
            AlgorithmRepository algorithmRepository,
            LiveSlotGraphResolver resolver,
            ExperimentManagementServiceClient emsClient,
            AlgorithmDownloadClient downloadClient) {
        this.touchpoints = Map.copyOf(touchpoints);
        this.algorithmRepository = Objects.requireNonNull(algorithmRepository, "algorithmRepository must not be null");
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.emsClient = Objects.requireNonNull(emsClient, "emsClient must not be null");
        this.downloadClient = Objects.requireNonNull(downloadClient, "downloadClient must not be null");
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Selects the active algorithm for one touchpoint and invokes it as a ranker. */
    public <SHARED, ACTION> AlgorithmExecution<RankingResponse<ACTION>> invoke(
            String touchpoint,
            String assignmentKey,
            RankingRequest<SHARED, ACTION> request) {
        String resolvedTouchpoint = requireNonBlank(touchpoint, "touchpoint");
        RankingRequest<SHARED, ACTION> resolvedRequest = Objects.requireNonNull(request, "request must not be null");
        ServingSlot slot = route(resolvedTouchpoint, Ranker.class);
        return resolver.invoke(
                slot,
                requireNonBlank(assignmentKey, "assignmentKey"),
                (Ranker<SHARED, ACTION> ranker) -> ranker.rank(resolvedRequest));
    }

    /**
     * Selects the active algorithm for one touchpoint and invokes it as a TopK algorithm.
     *
     * <p>This also accepts a {@link com.hotvect.api.algorithms.ThemedTopK} slot because its response
     * is a subtype of {@link TopKResponse}. The caller's assignment target supplies the output action
     * type because {@link TopKRequest} contains only the shared-input type. Call
     * {@link #invokeThemedTopK(String, String, TopKRequest)} when the caller needs the concrete themed
     * response type.</p>
     */
    public <SHARED, ACTION> AlgorithmExecution<TopKResponse<ACTION>> invoke(
            String touchpoint,
            String assignmentKey,
            TopKRequest<SHARED> request) {
        String resolvedTouchpoint = requireNonBlank(touchpoint, "touchpoint");
        TopKRequest<SHARED> resolvedRequest = Objects.requireNonNull(request, "request must not be null");
        ServingSlot slot = route(resolvedTouchpoint, TopK.class);
        return resolver.invoke(
                slot,
                requireNonBlank(assignmentKey, "assignmentKey"),
                (TopK<SHARED, ACTION> topK) -> topK.apply(resolvedRequest));
    }

    /** Selects the active algorithm for a ThemedTopK touchpoint and preserves its response type. */
    public <SHARED, ACTION> AlgorithmExecution<ThemedTopKResponse<ACTION>> invokeThemedTopK(
            String touchpoint,
            String assignmentKey,
            TopKRequest<SHARED> request) {
        String resolvedTouchpoint = requireNonBlank(touchpoint, "touchpoint");
        TopKRequest<SHARED> resolvedRequest = Objects.requireNonNull(request, "request must not be null");
        ServingSlot slot = route(resolvedTouchpoint, ThemedTopK.class);
        return resolver.invoke(
                slot,
                requireNonBlank(assignmentKey, "assignmentKey"),
                (ThemedTopK<SHARED, ACTION> topK) -> topK.apply(resolvedRequest));
    }

    /** Refreshes every configured root and recursively reachable dependency slot as one snapshot. */
    public void refreshNow() throws Exception {
        resolver.refreshNow();
    }

    /**
     * Returns the current operational serving status.
     *
     * <p>The status describes local activation only. It does not imply that the independent EMS
     * reads that produced the snapshot were transactional.</p>
     */
    public ServingRuntimeStatus status() {
        return resolver.status();
    }

    /** Stops serving and waits for in-flight invocations and cleanup of every generation. */
    @Override
    public void close() {
        Closeables.closeAll(
                "Failed to close serving runtime",
                resolver,
                algorithmRepository,
                emsClient,
                downloadClient);
    }

    public static final class Builder {
        private URI emsUri;
        private final Map<String, ServingSlot> rootSlots = new LinkedHashMap<>();
        private Path scratchDirectory;
        private Path localStateRoot;
        private Duration refreshPeriod = Duration.ofMinutes(5);
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(30);
        private Supplier<String> tokenSupplier = () -> null;
        private ClassLoader algorithmParentClassLoader = Thread.currentThread().getContextClassLoader();
        private EmsClientFactory emsClientFactory = ExperimentManagementServiceClient::new;
        private Supplier<AlgorithmDownloadClient> algorithmDownloadClientFactory = S3AlgorithmDownloadClient::new;
        private final Map<String, AlgorithmInstance<?>> dependencies = new LinkedHashMap<>();
        private MeterRegistry meterRegistry;

        private Builder() {
        }

        public Builder ems(URI emsUri) {
            this.emsUri = Objects.requireNonNull(emsUri, "emsUri must not be null");
            return this;
        }

        /** Adds a root EMS slot and its application invocation contract. */
        public Builder slot(ServingSlot slot) {
            ServingSlot resolved = Objects.requireNonNull(slot, "slot must not be null");
            if (rootSlots.containsKey(resolved.name())) {
                throw new IllegalArgumentException("Duplicate slot: " + resolved.name());
            }
            rootSlots.put(resolved.name(), resolved);
            return this;
        }

        public Builder scratchDirectory(Path scratchDirectory) {
            this.scratchDirectory = Objects.requireNonNull(
                    scratchDirectory,
                    "scratchDirectory must not be null");
            return this;
        }

        /**
         * Sets the persistent runtime-local root for algorithms that require local state storage.
         *
         * <p>Parameter archives are staged below this root and each loaded artifact receives its
         * own private state directory. The containing application owns the root and its capacity.</p>
         */
        public Builder localStateRoot(Path localStateRoot) {
            this.localStateRoot = Objects.requireNonNull(localStateRoot, "localStateRoot must not be null");
            return this;
        }

        public Builder refreshPeriod(Duration refreshPeriod) {
            this.refreshPeriod = LiveSlotGraphResolver.requireSchedulableRefreshPeriod(refreshPeriod);
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = requirePositive(connectTimeout, "connectTimeout");
            return this;
        }

        public Builder readTimeout(Duration readTimeout) {
            this.readTimeout = requirePositive(readTimeout, "readTimeout");
            return this;
        }

        public Builder tokenSupplier(Supplier<String> tokenSupplier) {
            this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier must not be null");
            return this;
        }

        public Builder algorithmParentClassLoader(ClassLoader algorithmParentClassLoader) {
            this.algorithmParentClassLoader = Objects.requireNonNull(
                    algorithmParentClassLoader,
                    "algorithmParentClassLoader must not be null");
            return this;
        }

        Builder emsClientFactory(EmsClientFactory emsClientFactory) {
            this.emsClientFactory = Objects.requireNonNull(emsClientFactory, "emsClientFactory must not be null");
            return this;
        }

        Builder algorithmDownloadClientFactory(
                Supplier<AlgorithmDownloadClient> algorithmDownloadClientFactory) {
            this.algorithmDownloadClientFactory = Objects.requireNonNull(
                    algorithmDownloadClientFactory,
                    "algorithmDownloadClientFactory must not be null");
            return this;
        }

        /** Registers serving runtime metrics with the supplied application-owned registry. */
        public Builder meterRegistry(MeterRegistry meterRegistry) {
            this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
            return this;
        }

        /** Adds an application-owned algorithm dependency with its complete generic contract. */
        public <ALGORITHM extends Algorithm> Builder dependency(
                String name,
                TypeToken<ALGORITHM> algorithmType,
                ALGORITHM dependency) {
            String dependencyName = requireNonBlank(name, "dependency name");
            AlgorithmInstance<ALGORITHM> instance = AlgorithmInstance.externalAlgorithm(
                    dependencyName,
                    Objects.requireNonNull(algorithmType, "algorithmType must not be null"),
                    Objects.requireNonNull(dependency, "dependency must not be null"));
            if (dependencies.put(dependencyName, instance) != null) {
                throw new IllegalArgumentException("Duplicate dependency: " + dependencyName);
            }
            return this;
        }

        /** Adds an application-owned dependency with a concrete non-generic algorithm class contract. */
        public <ALGORITHM extends Algorithm> Builder dependency(
                String name,
                Class<ALGORITHM> algorithmType,
                ALGORITHM dependency) {
            return dependency(
                    name,
                    TypeToken.of(Objects.requireNonNull(algorithmType, "algorithmType must not be null")),
                    dependency);
        }

        public HotvectServingRuntime build() throws Exception {
            if (algorithmParentClassLoader == null) {
                throw new IllegalStateException(
                        "No thread context class loader was available when this builder was created;"
                                + " call algorithmParentClassLoader(...) explicitly");
            }
            Objects.requireNonNull(emsUri, "ems configuration is required");
            Map<String, ServingSlot> resolvedTouchpoints = resolveTouchpoints();
            Path scratch = scratchDirectory == null
                    ? Path.of(System.getProperty("java.io.tmpdir"))
                    : scratchDirectory;
            Files.createDirectories(scratch);
            Path localState = localStateRoot == null
                    ? scratch.resolve("algorithm-state")
                    : localStateRoot;

            ExperimentManagementServiceClient emsClient = null;
            AlgorithmDownloadClient downloadClient = null;
            AlgorithmRepository repository = null;
            LiveSlotGraphResolver resolver = null;
            try {
                emsClient = Objects.requireNonNull(
                        emsClientFactory.create(emsUri, connectTimeout, readTimeout, tokenSupplier),
                        "emsClientFactory must return an EMS client");
                downloadClient = Objects.requireNonNull(
                        algorithmDownloadClientFactory.get(),
                        "algorithmDownloadClientFactory must return an algorithm download client");
                AlgorithmDependencies applicationBindings = new AlgorithmDependencies(dependencies);
                AlgorithmDownloader downloader = new AlgorithmDownloader(
                        downloadClient,
                        scratch,
                        algorithmParentClassLoader,
                        new AlgorithmDownloader.Options(
                                com.hotvect.api.execution.InputSemantic.ONLINE,
                                true,
                                false,
                                Optional.of(localState)));
                repository = new AlgorithmRepository(downloader, meterRegistry, applicationBindings);
                resolver = new LiveSlotGraphResolver(
                        rootSlots,
                        repository,
                        emsClient,
                        refreshPeriod);
                resolver.startAsync().awaitRunning();
                return new HotvectServingRuntime(
                        resolvedTouchpoints,
                        repository,
                        resolver,
                        emsClient,
                        downloadClient);
            } catch (Exception | Error failure) {
                Closeables.closeAfterFailure(failure, resolver, repository, emsClient, downloadClient);
                throw failure;
            }
        }

        private Map<String, ServingSlot> resolveTouchpoints() {
            if (rootSlots.isEmpty()) {
                throw new IllegalStateException("Serving runtime must declare at least one root slot");
            }
            Map<String, ServingSlot> resolved = new LinkedHashMap<>();
            for (ServingSlot slot : rootSlots.values()) {
                for (String touchpoint : slot.touchpoints()) {
                    ServingSlot previous = resolved.put(touchpoint, slot);
                    if (previous != null) {
                        throw new IllegalStateException("Duplicate touchpoint: " + touchpoint);
                    }
                }
            }
            if (resolved.isEmpty()) {
                throw new IllegalStateException("Serving runtime must declare at least one touchpoint");
            }
            return resolved;
        }

        private static Duration requirePositive(Duration value, String field) {
            Objects.requireNonNull(value, field + " must not be null");
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(field + " must be positive");
            }
            return value;
        }
    }

    @FunctionalInterface
    interface EmsClientFactory {
        ExperimentManagementServiceClient create(
                URI emsUri,
                Duration connectTimeout,
                Duration readTimeout,
                Supplier<String> tokenSupplier);
    }

    private ServingSlot route(String touchpoint) {
        ServingSlot slot = touchpoints.get(touchpoint);
        if (slot == null) {
            throw new IllegalArgumentException("Unknown touchpoint: " + touchpoint);
        }
        return slot;
    }

    private ServingSlot route(
            String touchpoint,
            Class<? extends Algorithm> expectedAlgorithmType) {
        ServingSlot slot = route(touchpoint);
        Class<?> configuredAlgorithmType = slot.algorithmType().getRawType();
        if (!expectedAlgorithmType.isAssignableFrom(configuredAlgorithmType)) {
            throw new IllegalArgumentException(
                    "Touchpoint " + touchpoint + " serves " + configuredAlgorithmType.getSimpleName()
                            + ", not " + expectedAlgorithmType.getSimpleName());
        }
        return slot;
    }

    static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

}
