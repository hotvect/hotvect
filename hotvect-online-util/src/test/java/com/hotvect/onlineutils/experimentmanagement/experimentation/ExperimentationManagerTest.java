package com.hotvect.onlineutils.experimentmanagement.experimentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Service;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.AlgorithmParameterMetadata;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.onlineutils.experimentmanagement.algodownload.AlgorithmDownloader;
import com.hotvect.onlineutils.experimentmanagement.algodownload.AlgorithmRepository;
import com.hotvect.onlineutils.experimentmanagement.httpclient.ExperimentManagementServiceClient;
import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import com.hotvect.onlineutils.experimentmanagement.models.Experiment;
import com.hotvect.onlineutils.experimentmanagement.models.Shard;
import com.hotvect.onlineutils.experimentmanagement.models.Slot;
import com.hotvect.onlineutils.experimentmanagement.models.UserForcedAssignment;
import com.hotvect.onlineutils.experimentmanagement.models.Variant;
import com.hotvect.onlineutils.experimentmanagement.models.VariantConfiguration;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExperimentationManagerTest {

    @Test
    void startsManagedUpdatersAndServesAssignments() {
        TestAlgorithmRepository algorithmRepository = new TestAlgorithmRepository();
        ExperimentManagementServiceClient experimentManagementServiceClient =
                new SequencedExperimentManagementServiceClient(Map.of(
                        "catalog", List.of(slotWithDefaultOnly(100, 1)),
                        "deals", List.of(slotWithDefaultOnly(100, 10))));
        ExperimentationManager manager = new DefaultExperimentationManager(
                algorithmRepository,
                Duration.ofDays(1),
                experimentManagementServiceClient,
                Set.of("catalog", "deals"));

        manager.startAsync().awaitRunning();

        VariantConfiguration assigned = manager.assignVariant("catalog", "customer-1");

        assertEquals(Set.of("catalog", "deals"), manager.slotNames());
        assertEquals(1, assigned.variant().variantId());
        assertEquals(100, manager.totalNumberOfShards("catalog"));
        assertEquals(1, manager.currentState("catalog").defaultVariantConfiguration().variant().variantId());
        assertEquals(10, manager.assignVariant("deals", "customer-1").variant().variantId());

        manager.close();
        assertEquals(Service.State.TERMINATED, manager.state());
    }

    @Test
    void refreshNowReplacesServingState() {
        TestAlgorithmRepository algorithmRepository = new TestAlgorithmRepository();
        ExperimentationManager manager =
                new DefaultExperimentationManager(
                        algorithmRepository,
                        Duration.ofDays(1),
                        new SequencedExperimentManagementServiceClient(Map.of(
                                "catalog", List.of(
                                        slotWithDefaultOnly(1, 1),
                                        slotWithFullTrafficExperiment(1, 1, 2)))),
                        Set.of("catalog"));

        manager.startAsync().awaitRunning();
        assertEquals(1, manager.assignVariant("catalog", "customer-1").variant().variantId());

        manager.refreshNow("catalog");

        assertEquals(2, manager.assignVariant("catalog", "customer-1").variant().variantId());
        assertEquals(2, manager.currentState("catalog").maxVariantID());

        manager.close();
    }

    @Test
    void unknownSlotFailsFast() {
        TestAlgorithmRepository algorithmRepository = new TestAlgorithmRepository();
        ExperimentationManager manager = new DefaultExperimentationManager(
                algorithmRepository,
                Duration.ofDays(1),
                new SequencedExperimentManagementServiceClient(Map.of(
                        "catalog", List.of(slotWithDefaultOnly(100, 1)))),
                Set.of("catalog"));

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> manager.totalNumberOfShards("unknown"));

        assertEquals("Unknown slot unknown. Configured slots: [catalog]", exception.getMessage());
    }

    @Test
    void refreshFailsIfShardCountChangesAfterInitialization() {
        TestAlgorithmRepository algorithmRepository = new TestAlgorithmRepository();
        ExperimentationManager manager =
                new DefaultExperimentationManager(
                        algorithmRepository,
                        Duration.ofDays(1),
                        new SequencedExperimentManagementServiceClient(Map.of(
                                "catalog", List.of(
                                        slotWithDefaultOnly(100, 1),
                                        slotWithDefaultOnly(50, 1)))),
                        Set.of("catalog"));

        manager.startAsync().awaitRunning();

        RuntimeException exception =
                assertThrows(RuntimeException.class, () -> manager.refreshNow("catalog"));

        assertEquals(
                "Failed to refresh experimentation state for slot catalog",
                exception.getMessage());
        assertEquals(
                "totalNumberOfShards changed for slot catalog from 100 to 50. "
                        + "This value is treated as immutable slot configuration.",
                exception.getCause().getMessage());

        manager.close();
    }

    @Test
    void updaterDoesNotSwallowErrors() {
        ExperimentStateUpdater updater = new ExperimentStateUpdater(
                "catalog",
                new TestAlgorithmRepository(),
                Duration.ofDays(1),
                new ErrorThrowingExperimentManagementServiceClient());

        AssertionError error = assertThrows(AssertionError.class, updater::updateState);

        assertEquals("fatal test error", error.getMessage());
    }

    private static Slot slotWithDefaultOnly(final int totalNumberOfShards, final int defaultVariantId) {
        return new Slot(
                "slot-salt",
                totalNumberOfShards,
                variant(defaultVariantId, 100, true),
                List.of(),
                List.of());
    }

    private static Slot slotWithFullTrafficExperiment(
            final int totalNumberOfShards,
            final int defaultVariantId,
            final int experimentVariantId) {
        Variant defaultVariant = variant(defaultVariantId, 100, true);
        Variant experimentVariant = variant(experimentVariantId, 100, false);
        Experiment experiment = new Experiment(
                42,
                "test-experiment",
                List.of(experimentVariant),
                100,
                List.of(shard(1)));
        return new Slot(
                "slot-salt",
                totalNumberOfShards,
                defaultVariant,
                List.of(experiment),
                List.<UserForcedAssignment>of());
    }

    private static Variant variant(
            final int variantId,
            final int shardAllocationRatio,
            final boolean isDefault) {
        return new Variant(
                variantId,
                new AlgorithmMetadata(
                        "algorithm-" + variantId,
                        "1.0.0",
                        "parameter-" + variantId,
                        "s3://bucket/algorithm-" + variantId + ".jar",
                        "s3://bucket/parameter-" + variantId + ".zip"),
                Instant.parse("2026-04-12T10:15:30Z"),
                false,
                isDefault,
                shardAllocationRatio);
    }

    private static Shard shard(final int shardId) {
        return new Shard(shardId, Instant.parse("2026-04-12T10:15:30Z"));
    }

    private static final class SequencedExperimentManagementServiceClient extends ExperimentManagementServiceClient {
        private final Map<String, List<Slot>> slotsByName;
        private final Map<String, Integer> nextIndexBySlotName = new java.util.HashMap<>();

        private SequencedExperimentManagementServiceClient(final Map<String, List<Slot>> slotsByName) {
            super(
                    URI.create("https://ems.example"),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    () -> "token");
            this.slotsByName = Map.copyOf(slotsByName);
        }

        @Override
        public synchronized Slot getDefaultVariantAndActiveExperiments(final String slotName) {
            List<Slot> slots = slotsByName.get(slotName);
            if (slots == null) {
                throw new IllegalArgumentException("Unknown test slot " + slotName);
            }
            int nextIndex = nextIndexBySlotName.getOrDefault(slotName, 0);
            int index = Math.min(nextIndex, slots.size() - 1);
            nextIndexBySlotName.put(slotName, nextIndex + 1);
            return slots.get(index);
        }
    }

    private static final class ErrorThrowingExperimentManagementServiceClient extends ExperimentManagementServiceClient {
        private ErrorThrowingExperimentManagementServiceClient() {
            super(
                    URI.create("https://ems.example"),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    () -> "token");
        }

        @Override
        public Slot getDefaultVariantAndActiveExperiments(final String slotName) {
            throw new AssertionError("fatal test error");
        }
    }

    private static final class TestAlgorithmRepository extends AlgorithmRepository {
        private TestAlgorithmRepository() {
            super(new AlgorithmDownloader(
                    null,
                    System.getProperty("java.io.tmpdir"),
                    ExperimentationManagerTest.class.getClassLoader(),
                    false));
        }

        @Override
        public AlgorithmInstance<TestAlgorithm> getAlgorithmInstance(final AlgorithmMetadata algorithmMetadata) {
            return new AlgorithmInstance<>(
                    new AlgorithmDefinition(
                            JsonNodeFactory.instance.objectNode(),
                            algorithmMetadata.algorithmId(),
                            ImmutableMap.of(),
                            ImmutableMap.of(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty()),
                    new AlgorithmParameterMetadata(
                            new AlgorithmId(algorithmMetadata.algorithmName(), algorithmMetadata.algorithmVersion()),
                            algorithmMetadata.latestAlgorithmParameter(),
                            Instant.now(),
                            Optional.empty()),
                    new TestAlgorithm());
        }
    }

    private static final class TestAlgorithm implements Algorithm {
    }
}
