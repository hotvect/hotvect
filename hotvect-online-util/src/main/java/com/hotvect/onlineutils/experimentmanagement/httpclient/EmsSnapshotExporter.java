package com.hotvect.onlineutils.experimentmanagement.httpclient;

import com.hotvect.onlineutils.experimentmanagement.ExperimentManagementStateSource;
import com.hotvect.onlineutils.experimentmanagement.algodownload.AlgorithmDownloadClient;
import com.hotvect.onlineutils.experimentmanagement.generated.SlotActiveInfo;
import com.hotvect.onlineutils.experimentmanagement.models.Slot;
import com.hotvect.onlineutils.serving.EmsPredictionRuntime;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Captures the complete active EMS state reachable from one offline-prediction root slot. */
public final class EmsSnapshotExporter {
    public static final int PROVENANCE_SCHEMA_VERSION = 1;
    public static final String READ_CONSISTENCY = "independently_fetched_per_slot_non_transactional";

    private final ExperimentManagementServiceClient serviceClient;
    private final AlgorithmDownloadClient downloadClient;
    private final ClassLoader algorithmParentClassLoader;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public EmsSnapshotExporter(
            ExperimentManagementServiceClient serviceClient,
            AlgorithmDownloadClient downloadClient,
            ClassLoader algorithmParentClassLoader,
            MeterRegistry meterRegistry) {
        this(serviceClient, downloadClient, algorithmParentClassLoader, meterRegistry, Clock.systemUTC());
    }

    EmsSnapshotExporter(
            ExperimentManagementServiceClient serviceClient,
            AlgorithmDownloadClient downloadClient,
            ClassLoader algorithmParentClassLoader,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.serviceClient = Objects.requireNonNull(serviceClient, "serviceClient must not be null");
        this.downloadClient = Objects.requireNonNull(downloadClient, "downloadClient must not be null");
        this.algorithmParentClassLoader = Objects.requireNonNull(
                algorithmParentClassLoader,
                "algorithmParentClassLoader must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Validates and prepares every reachable composition before atomically publishing its state document.
     *
     * <p>The runtime owns and closes the EMS client and artifact downloader. A failed fetch, artifact inspection,
     * or graph preparation therefore leaves no completed output document.</p>
     */
    public EmsStateSnapshotDocument export(String rootSlot, Path output) throws Exception {
        Instant captureStartedAt = clock.instant();
        CapturingStateSource stateSource = new CapturingStateSource(serviceClient, clock);
        try (EmsPredictionRuntime ignored = EmsPredictionRuntime.builder()
                .rootSlot(rootSlot)
                .stateSource(stateSource)
                .downloadClient(downloadClient)
                .algorithmParentClassLoader(algorithmParentClassLoader)
                .meterRegistry(meterRegistry)
                .build()) {
            EmsStateSnapshotDocument document = stateSource.document(
                    rootSlot,
                    captureStartedAt,
                    clock.instant());
            EmsStateSnapshotDocument.writeAtomically(output, document);
            return document;
        }
    }

    private static final class CapturingStateSource implements ExperimentManagementStateSource {
        private final ExperimentManagementServiceClient serviceClient;
        private final Clock clock;
        private final Map<String, Slot> mappedSlots = new TreeMap<>();
        private final Map<String, SlotActiveInfo> capturedSlots = new TreeMap<>();
        private final Map<String, Instant> slotCapturedAt = new TreeMap<>();

        private CapturingStateSource(ExperimentManagementServiceClient serviceClient, Clock clock) {
            this.serviceClient = serviceClient;
            this.clock = clock;
        }

        @Override
        public Slot getDefaultVariantAndActiveExperiments(String slotName) throws Exception {
            if (slotName == null || slotName.isBlank()) {
                throw new IllegalArgumentException("EMS slot name must not be blank");
            }
            Slot existing = mappedSlots.get(slotName);
            if (existing != null) {
                return existing;
            }
            SlotActiveInfo document = serviceClient.getDefaultVariantAndActiveExperimentsDocument(slotName);
            Slot mapped = EmsSlotStateMapper.toSlot(document);
            capturedSlots.put(slotName, document);
            slotCapturedAt.put(slotName, clock.instant());
            mappedSlots.put(slotName, mapped);
            return mapped;
        }

        private EmsStateSnapshotDocument document(
                String requestedRootSlot,
                Instant captureStartedAt,
                Instant captureCompletedAt) {
            return new EmsStateSnapshotDocument(
                    capturedSlots,
                    new EmsStateSnapshotDocument.CaptureProvenance(
                            PROVENANCE_SCHEMA_VERSION,
                            serviceClient.baseUri().toString(),
                            requestedRootSlot,
                            captureStartedAt,
                            captureCompletedAt,
                            slotCapturedAt,
                            READ_CONSISTENCY));
        }

        @Override
        public void close() {
            serviceClient.close();
        }
    }
}
