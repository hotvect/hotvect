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

/**
 * Loads EMS-selected algorithm graphs and executes requests against the current serving snapshot.
 *
 * <h2>Routing and assignment</h2>
 * <p>The application registers each root EMS slot with its full algorithm type and the touchpoints
 * that route to it through {@link Builder#slot(ServingSlot)}. An invocation supplies a touchpoint,
 * an assignment key, and an algorithm request. The assignment key is typically a customer ID:
 * each selected slot uses it for EMS user forced assignments or ordinary bucket assignment.
 * Selection uses the locally loaded snapshot; invoking a request does not fetch fresh EMS state.
 * The request's shared-input and action types must match the registered {@link ServingSlot}.
 *
 * <h2>Explicit root selection</h2>
 * <p>The overloads accepting {@code rootVariantId} replace assignment for the root slot only.
 * The requested variant must be present in that slot's current snapshot, as its default variant
 * or a variant of an active experiment. Dependencies of that variant still use
 * {@code assignmentKey}, including any EMS user forced assignments. An override applies to one
 * invocation, does not change EMS, and never falls back to ordinary root assignment on failure.
 * The application decides which callers may supply an override; this runtime has no test-request
 * or environment gate.
 *
 * <p>Variant IDs are strings in this API. The current EMS integration requires a string that parses
 * as a Java {@code int}, such as {@code "42"}; conversion occurs at the EMS snapshot lookup.
 *
 * <pre>{@code
 * // request is a RankingRequest<Shared, Action> matching the registered "search" touchpoint.
 * var assigned = runtime.invoke("search", "customer-123", request);
 * var overridden = runtime.invoke("search", "customer-123", "42", request);
 * var response = overridden.result();
 * var assignments = overridden.selection().assignments();
 * }</pre>
 *
 * <h2>Results and lifecycle</h2>
 * <p>Every invocation returns an {@link AlgorithmExecution} containing the algorithm response and
 * the actual root and dependency selections. Algorithm exceptions propagate to the caller.
 * Each invocation retains one serving generation until the algorithm returns, so concurrent
 * refreshes cannot replace its graph halfway through execution.
 *
 * <p>{@link Builder#build()} loads the initial snapshot before returning and starts periodic
 * refreshes. A refresh publishes a complete new snapshot atomically; failed preparation leaves
 * the previous generation serving. Use {@link #status()} to inspect activation and refresh
 * failures, and {@link #close()} when the application shuts down. The application owns the runtime's
 * lifetime and any dependencies supplied through the builder.
 */
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

    /**
     * Creates a builder; no clients, artifacts, or background tasks are started yet.
     *
     * @return a new builder requiring an EMS URI and at least one root slot
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Assigns the root and its dependencies through EMS, then ranks the supplied actions.
     *
     * @param <SHARED> shared-input type declared by the serving slot
     * @param <ACTION> action type declared by the serving slot
     * @param touchpoint nonblank application touchpoint registered for a {@link Ranker} slot
     * @param assignmentKey nonblank key used for assignment in the root and dependency slots
     * @param request ranking input, including the available actions
     * @return the ranking response and the selections used to execute it
     * @throws IllegalArgumentException if the touchpoint or key is blank, or the touchpoint is
     *         unknown or does not serve a ranker
     * @throws NullPointerException if {@code request} is null
     * @throws IllegalStateException if no serving snapshot is available or the runtime is closed
     * @see #invoke(String, String, String, RankingRequest)
     */
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
     * Ranks the supplied actions using an explicit root variant from the current snapshot.
     *
     * <p>The override takes precedence over the root's EMS user forced assignment and bucket
     * assignment. Dependencies still use {@code assignmentKey}. See the class-level contract for
     * override scope, authorization, and the current EMS ID representation.
     *
     * @param <SHARED> shared-input type declared by the serving slot
     * @param <ACTION> action type declared by the serving slot
     * @param touchpoint nonblank application touchpoint registered for a {@link Ranker} slot
     * @param assignmentKey nonblank key used to assign dependency slots, even with a root override
     * @param rootVariantId nonblank root-slot variant ID; currently a string parseable as an EMS integer ID
     * @param request ranking input, including the available actions
     * @return the ranking response with the explicit root selection and actual dependency selections
     * @throws IllegalArgumentException if a routing or selection argument is blank or invalid,
     *         the touchpoint does not serve a ranker, or the variant is unavailable in its root slot
     * @throws NullPointerException if {@code request} is null
     * @throws IllegalStateException if the selected graph is unavailable or the runtime is closed
     * @since 11.2.2
     */
    public <SHARED, ACTION> AlgorithmExecution<RankingResponse<ACTION>> invoke(
            String touchpoint,
            String assignmentKey,
            String rootVariantId,
            RankingRequest<SHARED, ACTION> request) {
        String resolvedTouchpoint = requireNonBlank(touchpoint, "touchpoint");
        RankingRequest<SHARED, ACTION> resolvedRequest = Objects.requireNonNull(request, "request must not be null");
        ServingSlot slot = route(resolvedTouchpoint, Ranker.class);
        return resolver.invokeVariant(
                slot,
                requireNonBlank(assignmentKey, "assignmentKey"),
                requireNonBlank(rootVariantId, "rootVariantId"),
                (Ranker<SHARED, ACTION> ranker) -> ranker.rank(resolvedRequest));
    }

    /**
     * Assigns the root and its dependencies through EMS, then selects actions with a TopK algorithm.
     *
     * <p>This also accepts a {@link com.hotvect.api.algorithms.ThemedTopK} slot because its response
     * is a subtype of {@link TopKResponse}. The caller's assignment target supplies the output action
     * type because {@link TopKRequest} contains only the shared-input type. Call
     * {@link #invokeThemedTopK(String, String, TopKRequest)} when the caller needs the concrete themed
     * response type.</p>
     *
     * @param <SHARED> shared-input type declared by the serving slot
     * @param <ACTION> output action type declared by the serving slot
     * @param touchpoint nonblank application touchpoint registered for a {@link TopK} or {@link ThemedTopK} slot
     * @param assignmentKey nonblank key used for assignment in the root and dependency slots
     * @param request shared input and TopK selection options
     * @return the TopK response and the selections used to execute it
     * @throws IllegalArgumentException if the touchpoint or key is blank, or the touchpoint is
     *         unknown or does not serve a TopK algorithm
     * @throws NullPointerException if {@code request} is null
     * @throws IllegalStateException if no serving snapshot is available or the runtime is closed
     * @see #invoke(String, String, String, TopKRequest)
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

    /**
     * Selects actions with an explicit TopK root variant from the current snapshot.
     *
     * <p>Accepts both {@link TopK} and {@link ThemedTopK} slots. Use
     * {@link #invokeThemedTopK(String, String, String, TopKRequest)} to retain the concrete themed
     * response type. Only the root is overridden; its dependencies still use {@code assignmentKey}.
     *
     * @param <SHARED> shared-input type declared by the serving slot
     * @param <ACTION> output action type declared by the serving slot
     * @param touchpoint nonblank application touchpoint registered for a TopK or ThemedTopK slot
     * @param assignmentKey nonblank key used to assign dependency slots, even with a root override
     * @param rootVariantId nonblank root-slot variant ID; currently a string parseable as an EMS integer ID
     * @param request shared input and TopK selection options
     * @return the TopK response with the explicit root selection and actual dependency selections
     * @throws IllegalArgumentException if a routing or selection argument is blank or invalid,
     *         the touchpoint does not serve a TopK algorithm, or the variant is unavailable in its root slot
     * @throws NullPointerException if {@code request} is null
     * @throws IllegalStateException if the selected graph is unavailable or the runtime is closed
     * @since 11.2.2
     */
    public <SHARED, ACTION> AlgorithmExecution<TopKResponse<ACTION>> invoke(
            String touchpoint,
            String assignmentKey,
            String rootVariantId,
            TopKRequest<SHARED> request) {
        String resolvedTouchpoint = requireNonBlank(touchpoint, "touchpoint");
        TopKRequest<SHARED> resolvedRequest = Objects.requireNonNull(request, "request must not be null");
        ServingSlot slot = route(resolvedTouchpoint, TopK.class);
        return resolver.invokeVariant(
                slot,
                requireNonBlank(assignmentKey, "assignmentKey"),
                requireNonBlank(rootVariantId, "rootVariantId"),
                (TopK<SHARED, ACTION> topK) -> topK.apply(resolvedRequest));
    }

    /**
     * Assigns the root and its dependencies through EMS, then returns a concrete themed TopK response.
     *
     * @param <SHARED> shared-input type declared by the serving slot
     * @param <ACTION> output action type declared by the serving slot
     * @param touchpoint nonblank application touchpoint registered for a {@link ThemedTopK} slot
     * @param assignmentKey nonblank key used for assignment in the root and dependency slots
     * @param request shared input and TopK selection options
     * @return the themed response, including theme metadata, and the selections used to execute it
     * @throws IllegalArgumentException if the touchpoint or key is blank, or the touchpoint is
     *         unknown or does not serve a themed TopK algorithm
     * @throws NullPointerException if {@code request} is null
     * @throws IllegalStateException if no serving snapshot is available or the runtime is closed
     * @see #invokeThemedTopK(String, String, String, TopKRequest)
     */
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

    /**
     * Returns a concrete themed TopK response using an explicit root variant from the current snapshot.
     *
     * <p>Only the root is overridden; its dependencies still use {@code assignmentKey}. The result
     * retains the themed response type and records the selections actually used for this invocation.
     *
     * @param <SHARED> shared-input type declared by the serving slot
     * @param <ACTION> output action type declared by the serving slot
     * @param touchpoint nonblank application touchpoint registered for a {@link ThemedTopK} slot
     * @param assignmentKey nonblank key used to assign dependency slots, even with a root override
     * @param rootVariantId nonblank root-slot variant ID; currently a string parseable as an EMS integer ID
     * @param request shared input and TopK selection options
     * @return the themed response with the explicit root selection and actual dependency selections
     * @throws IllegalArgumentException if a routing or selection argument is blank or invalid,
     *         the touchpoint does not serve a themed TopK algorithm, or the variant is unavailable in its root slot
     * @throws NullPointerException if {@code request} is null
     * @throws IllegalStateException if the selected graph is unavailable or the runtime is closed
     * @since 11.2.2
     */
    public <SHARED, ACTION> AlgorithmExecution<ThemedTopKResponse<ACTION>> invokeThemedTopK(
            String touchpoint,
            String assignmentKey,
            String rootVariantId,
            TopKRequest<SHARED> request) {
        String resolvedTouchpoint = requireNonBlank(touchpoint, "touchpoint");
        TopKRequest<SHARED> resolvedRequest = Objects.requireNonNull(request, "request must not be null");
        ServingSlot slot = route(resolvedTouchpoint, ThemedTopK.class);
        return resolver.invokeVariant(
                slot,
                requireNonBlank(assignmentKey, "assignmentKey"),
                requireNonBlank(rootVariantId, "rootVariantId"),
                (ThemedTopK<SHARED, ACTION> topK) -> topK.apply(resolvedRequest));
    }

    /**
     * Fetches EMS state and prepares a replacement snapshot synchronously.
     *
     * <p>All configured roots and their reachable dependencies must load and validate before the
     * new snapshot becomes visible. Preparation failure leaves the previous snapshot serving.
     * In-flight invocations finish against the generation they already acquired.
     *
     * @throws IllegalStateException if the runtime is closed or another refresh is already in progress
     * @throws Exception if fetching, preparing, or publishing the replacement snapshot fails
     */
    public void refreshNow() throws Exception {
        resolver.refreshNow();
    }

    /**
     * Returns local readiness, generation metadata, slot inventory, and the last refresh failure.
     *
     * <p>This is an operational view, not a customer's assignment. Per-invocation attribution is
     * available through {@link AlgorithmExecution#selection()}. A ready runtime may also report a
     * failed refresh while continuing to serve its previous generation. The independent EMS reads
     * that produced a generation are not guaranteed to be transactional.
     *
     * @return the current local serving status; this call does not refresh EMS state
     */
    public ServingRuntimeStatus status() {
        return resolver.status();
    }

    /**
     * Stops refreshing, rejects new invocations, and waits for in-flight invocations and graph cleanup.
     *
     * <p>Closes the runtime-owned algorithm repository, EMS client, and download client. The
     * application remains responsible for dependencies and the metrics registry it supplied to the
     * builder. This call can block until an executing algorithm returns.
     */
    @Override
    public void close() {
        Closeables.closeAll(
                "Failed to close serving runtime",
                resolver,
                algorithmRepository,
                emsClient,
                downloadClient);
    }

    /**
     * Configures the EMS connection, root-slot contracts, storage, and application-owned dependencies.
     * Resources are acquired only by {@link #build()}, which returns an initialized runtime.
     */
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

        /**
         * Sets the required EMS service base URI; slot-specific paths are added by the client.
         *
         * @param emsUri EMS service root URI
         * @return this builder
         */
        public Builder ems(URI emsUri) {
            this.emsUri = Objects.requireNonNull(emsUri, "emsUri must not be null");
            return this;
        }

        /**
         * Registers a root EMS slot, its full algorithm type, and its application touchpoints.
         * Dependency slots are discovered from algorithm definitions during snapshot preparation.
         *
         * @param slot root-slot declaration; at least one is required
         * @return this builder
         * @throws IllegalArgumentException if the slot name is already registered
         * @see ServingSlot#builder(String, TypeToken)
         */
        public Builder slot(ServingSlot slot) {
            ServingSlot resolved = Objects.requireNonNull(slot, "slot must not be null");
            if (rootSlots.containsKey(resolved.name())) {
                throw new IllegalArgumentException("Duplicate slot: " + resolved.name());
            }
            rootSlots.put(resolved.name(), resolved);
            return this;
        }

        /**
         * Sets the directory used to stage downloaded artifacts.
         *
         * @param scratchDirectory staging directory; defaults to {@code java.io.tmpdir}
         * @return this builder
         */
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
         *
         * @param localStateRoot local-state directory; defaults to {@code algorithm-state} under the scratch directory
         * @return this builder
         */
        public Builder localStateRoot(Path localStateRoot) {
            this.localStateRoot = Objects.requireNonNull(localStateRoot, "localStateRoot must not be null");
            return this;
        }

        /**
         * Sets the fixed-rate interval for background refreshes after initial loading.
         *
         * @param refreshPeriod interval of at least one millisecond; defaults to five minutes
         * @return this builder
         * @throws IllegalArgumentException if the interval is shorter than one millisecond
         */
        public Builder refreshPeriod(Duration refreshPeriod) {
            this.refreshPeriod = LiveSlotGraphResolver.requireSchedulableRefreshPeriod(refreshPeriod);
            return this;
        }

        /**
         * Sets the EMS HTTP connection timeout.
         *
         * @param connectTimeout positive timeout; defaults to five seconds
         * @return this builder
         */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = requirePositive(connectTimeout, "connectTimeout");
            return this;
        }

        /**
         * Sets the timeout applied to each EMS HTTP request.
         *
         * @param readTimeout positive timeout; defaults to thirty seconds
         * @return this builder
         */
        public Builder readTimeout(Duration readTimeout) {
            this.readTimeout = requirePositive(readTimeout, "readTimeout");
            return this;
        }

        /**
         * Supplies the bearer token separately for each EMS request, allowing token rotation.
         * A null or blank result omits the Authorization header; the default supplier returns null.
         *
         * @param tokenSupplier nonnull supplier of the current token
         * @return this builder
         */
        public Builder tokenSupplier(Supplier<String> tokenSupplier) {
            this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier must not be null");
            return this;
        }

        /**
         * Sets the parent classloader used when loading algorithm artifacts.
         *
         * @param algorithmParentClassLoader parent loader; defaults to the thread context classloader
         *        captured when the builder is created
         * @return this builder
         */
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

        /**
         * Registers serving-runtime metrics in an application-owned registry.
         *
         * @param meterRegistry registry to use; no registry is configured by default
         * @return this builder
         */
        public Builder meterRegistry(MeterRegistry meterRegistry) {
            this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
            return this;
        }

        /**
         * Registers an application-owned algorithm dependency with its complete generic contract.
         *
         * <p>This supplies an existing algorithm instance, such as an application feature store.
         * It does not register an EMS slot or override variant assignment. The application owns
         * the supplied instance's lifetime.
         *
         * @param <ALGORITHM> dependency algorithm type
         * @param name dependency name used by algorithm definitions
         * @param algorithmType fully resolved type, including generic arguments
         * @param dependency application-owned instance
         * @return this builder
         * @throws IllegalArgumentException if the name is blank or already registered
         */
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

        /**
         * Registers an application-owned dependency using a concrete, non-generic algorithm class.
         * Use {@link #dependency(String, TypeToken, Algorithm)} for a parameterized contract.
         *
         * @param <ALGORITHM> dependency algorithm type
         * @param name dependency name used by algorithm definitions
         * @param algorithmType concrete algorithm class
         * @param dependency application-owned instance
         * @return this builder
         * @throws IllegalArgumentException if the name is blank or already registered
         */
        public <ALGORITHM extends Algorithm> Builder dependency(
                String name,
                Class<ALGORITHM> algorithmType,
                ALGORITHM dependency) {
            return dependency(
                    name,
                    TypeToken.of(Objects.requireNonNull(algorithmType, "algorithmType must not be null")),
                    dependency);
        }

        /**
         * Creates the runtime, loads its initial serving snapshot, and starts periodic refreshes.
         *
         * <p>This call performs EMS and artifact I/O and returns only after the initial graphs have
         * loaded and passed their serving-slot contracts. Initialization failure closes resources
         * acquired by this attempt. The caller must close a successfully built runtime.
         *
         * @return an initialized runtime ready for invocation
         * @throws NullPointerException if the required EMS URI was not supplied
         * @throws IllegalStateException if no root slots are registered, touchpoints overlap,
         *         no parent classloader is available, or initial loading fails in the background service
         * @throws Exception if runtime initialization fails
         */
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
