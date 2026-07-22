package com.hotvect.onlineutils.experimentmanagement.algodownload;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import com.hotvect.onlineutils.hotdeploy.AlgorithmInstanceFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the repository of algorithm instances and their corresponding holders. This class is responsible
 * for downloading, caching, and providing access to algorithm instances and factories based on algorithm metadata.
 * Algorithm instances are maintained with weak references to allow for garbage collection when no longer in use,
 * while algorithm holders are kept indefinitely due to their small and relatively static number. It is designed
 * to support concurrent access, making it suitable for multithreaded environments.
 * <p>
 * NOTE: You should instantiate only one repository per application and share it across threads
 * to ensure efficient use of resources.
 */
public class AlgorithmRepository {
    private static final Logger LOG = LoggerFactory.getLogger(AlgorithmRepository.class);
    private static final Cleaner CLEANER = Cleaner.create();

    /**
     * Algorithm holders are never removed, as they are required to load the algorithm instance.
     * This should be acceptable, as the number of algorithm holders is expected to be small, and it doesn't change much
     */
    private final ConcurrentHashMap<AlgorithmId, AlgorithmInstanceFactory> algorithmHolders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<AlgorithmInstanceKey, WeakReference<AlgorithmInstance<?>>> algorithmInstances = new ConcurrentHashMap<>();

    private static final String ALGORITHM_NAME_KEY = "AlgorithmName";
    private static final String PARAMETER_ID_KEY = "AlgorithmParameterId";
    private static final String ALGORITHM_METRICS_NAME = "ems.algorithm_parameter.age";

    private final AlgorithmDownloader algorithmDownloader;
    private final Map<String, AlgorithmInstance<?>> globalDependencyOverrides;

    private final MeterRegistry meterRegistry;

    public AlgorithmRepository(final AlgorithmDownloader algorithmDownloader) {
        this(algorithmDownloader, null, Map.of());
    }

    public AlgorithmRepository(final AlgorithmDownloader algorithmDownloader, final MeterRegistry meterRegistry) {
        this(algorithmDownloader, meterRegistry, Map.of());
    }

    public AlgorithmRepository(
            final AlgorithmDownloader algorithmDownloader,
            final Map<String, AlgorithmInstance<?>> dependencyOverrides) {
        this(algorithmDownloader, null, dependencyOverrides);
    }

    public AlgorithmRepository(
            final AlgorithmDownloader algorithmDownloader,
            final MeterRegistry meterRegistry,
            final Map<String, AlgorithmInstance<?>> dependencyOverrides) {
        this.algorithmDownloader = algorithmDownloader;
        this.globalDependencyOverrides = ImmutableMap.copyOf(dependencyOverrides);
        this.meterRegistry = meterRegistry;
    }

    public AlgorithmInstance<?> getAlgorithmInstance(final AlgorithmMetadata algorithmMetadata) {
        final AtomicReference<AlgorithmInstance<?>> resolvedInstance = new AtomicReference<>();
        algorithmInstances.compute(buildAlgorithmInstanceKey(algorithmMetadata), (algorithmInstanceKey, weakRef) -> {
            AlgorithmInstance<?> existingInstance = (weakRef != null) ? weakRef.get() : null;
            if (existingInstance == null) {  // If the reference has been cleared, re-download
                final AlgorithmInstance<?> downloadedInstance = downloadAlgorithmParameter(algorithmMetadata);
                resolvedInstance.set(downloadedInstance);
                return new WeakReference<>(downloadedInstance);
            }

            LOG.debug("Algorithm instance {} for algorithm {} is unchanged, reusing previous instance",
                    algorithmInstanceKey, algorithmMetadata.algorithmId());
            resolvedInstance.set(existingInstance);
            return weakRef;  // Otherwise, return the existing reference
        });
        final AlgorithmInstance<?> algorithmInstance = resolvedInstance.get();
        checkState(algorithmInstance != null,
                "No algorithm instance resolved for " + algorithmMetadata.algorithmId()
                        + " parameter " + algorithmMetadata.latestAlgorithmParameter());
        return algorithmInstance;
    }

    public ImmutableMap<AlgorithmId, AlgorithmInstanceFactory> getAllAlgorithmHolders() {
        return ImmutableMap.copyOf(algorithmHolders);
    }

    private AlgorithmInstanceFactory getAlgorithmHolder(final AlgorithmMetadata algorithmMetadata) {
        return algorithmHolders.computeIfAbsent(
            algorithmMetadata.algorithmId(),
            algorithmId -> this.downloadAlgorithmJar(algorithmMetadata));
    }

    private AlgorithmInstanceFactory downloadAlgorithmJar(final AlgorithmMetadata algorithmMetadata) {
        LOG.info("Downloading algorithm jar for {}", algorithmMetadata.algorithmId());
        return algorithmDownloader.downloadAlgorithmJar(algorithmMetadata);
    }

    private AlgorithmInstance<?> downloadAlgorithmParameter(
        final AlgorithmMetadata algorithmMetadata) {

        AlgorithmId algorithmId = algorithmMetadata.algorithmId();
        String algorithmParameterId = algorithmMetadata.latestAlgorithmParameter();

        checkState(!Strings.isNullOrEmpty(algorithmParameterId), "No parameter Id available for " + algorithmId);

        final AlgorithmInstanceFactory algorithmHolder = getAlgorithmHolder(algorithmMetadata);
        checkState(algorithmHolder != null, "Could not find algorithm holder for " + algorithmId + " and parameter " + algorithmParameterId);

        LOG.info("Downloading algorithm parameter for {}", algorithmMetadata.algorithmId());
        final AlgorithmInstance<?> algorithmInstance = algorithmDownloader.loadAlgorithmInstance(algorithmMetadata, algorithmHolder, globalDependencyOverrides);
        checkState(algorithmInstance.algorithmDefinition().algorithmId().equals(algorithmId),
            "Loaded algorithm instance has wrong algorithm ID");

        if (algorithmInstance.algorithmParameterMetadata() != null) {
            checkState(algorithmInstance.algorithmParameterMetadata().parameterId().equals(algorithmParameterId),
                "Downloaded algorithm parameter has wrong version. Expected: " + algorithmParameterId +
                    " but file contained " + algorithmInstance.algorithmParameterMetadata().parameterId());
        }

        if (meterRegistry != null) {
            String algorithmName = algorithmMetadata.algorithmId().algorithmName();
            String algorithmVersion = algorithmMetadata.algorithmId().algorithmVersion();
            String fullAlgorithmName = String.format("%s@%s", algorithmName, algorithmVersion);
            Tags tags = Tags.of(ALGORITHM_NAME_KEY, fullAlgorithmName, PARAMETER_ID_KEY, algorithmParameterId);

            meterRegistry.gauge(ALGORITHM_METRICS_NAME,tags, algorithmInstance, this::emitAlgorithmAgeMetrics);
        }
        registerCleanup(algorithmInstance);

        return algorithmInstance;
    }

    private void registerCleanup(final AlgorithmInstance<?> algorithmInstance) {
        String algorithmId = algorithmInstance.algorithmDefinition().algorithmId().toString();
        String algorithmParameterId = algorithmInstance.algorithmParameterMetadata() == null
                ? "unknown"
                : algorithmInstance.algorithmParameterMetadata().parameterId();
        CLEANER.register(
                algorithmInstance,
                new AlgorithmCleanupAction(algorithmInstance.algorithm(), algorithmId, algorithmParameterId));
    }

    private double emitAlgorithmAgeMetrics(AlgorithmInstance<?> algorithmInstance) {
        if (algorithmInstance.algorithmParameterMetadata() == null || algorithmInstance.algorithmParameterMetadata().ranAt() == null) {
            return -1;
        }
        return Instant.now().getEpochSecond() - algorithmInstance.algorithmParameterMetadata().ranAt().getEpochSecond();
    }

    private AlgorithmInstanceKey buildAlgorithmInstanceKey(final AlgorithmMetadata algorithmMetadata) {
        return new AlgorithmInstanceKey(algorithmMetadata.algorithmId(), algorithmMetadata.latestAlgorithmParameter());
    }

    private record AlgorithmInstanceKey(AlgorithmId algorithmId, String parameterId) {
    }

    private static final class AlgorithmCleanupAction implements Runnable {
        private final Algorithm algorithm;
        private final String algorithmId;
        private final String algorithmParameterId;

        private AlgorithmCleanupAction(
                final Algorithm algorithm,
                final String algorithmId,
                final String algorithmParameterId) {
            this.algorithm = algorithm;
            this.algorithmId = algorithmId;
            this.algorithmParameterId = algorithmParameterId;
        }

        @Override
        public void run() {
            try {
                algorithm.close();
                LOG.info(
                        "Closed algorithm {} parameter {} after its AlgorithmInstance became unreachable",
                        algorithmId,
                        algorithmParameterId);
            } catch (final Exception e) {
                LOG.warn(
                        "Failed to close algorithm {} parameter {} after its AlgorithmInstance became unreachable",
                        algorithmId,
                        algorithmParameterId,
                        e);
            }
        }
    }
}
