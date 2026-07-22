package com.hotvect.onlineutils.experimentmanagement.experimentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.collect.ImmutableMap;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.AlgorithmParameterMetadata;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.onlineutils.experimentmanagement.algodownload.AlgorithmDownloader;
import com.hotvect.onlineutils.experimentmanagement.algodownload.AlgorithmRepository;
import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import com.hotvect.onlineutils.experimentmanagement.models.Experiment;
import com.hotvect.onlineutils.experimentmanagement.models.Shard;
import com.hotvect.onlineutils.experimentmanagement.models.Slot;
import com.hotvect.onlineutils.experimentmanagement.models.UserForcedAssignment;
import com.hotvect.onlineutils.experimentmanagement.models.Variant;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OnlineSlotToExperimentationStateConverterTest {

    @Test
    void failsWhenSameAlgorithmVersionUsesDifferentParameters() {
        final OnlineSlotToExperimentationStateConverter converter =
                new OnlineSlotToExperimentationStateConverter(testAlgorithmRepository());
        final Slot slot = new Slot(
                "slot-salt",
                10,
                variant(1, "shared-algorithm", "1.0.0", "parameter-default", null, true),
                List.of(new Experiment(
                        42,
                        "parameter-test",
                        List.of(
                                variant(2, "shared-algorithm", "1.0.0", "parameter-a", 50, false),
                                variant(3, "shared-algorithm", "1.0.0", "parameter-b", 50, false)),
                        100,
                        List.of(shard(1)))),
                List.of());

        final IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> converter.convert(slot));

        assertEquals(
                "Unmatched algorithm metadata found:"
                        + "AlgorithmMetadata[algorithmName=shared-algorithm, algorithmVersion=1.0.0, "
                        + "latestAlgorithmParameter=parameter-a, absoluteS3AlgorithmJarPath=s3://bucket/shared-algorithm-1.0.0.jar, "
                        + "absoluteS3AlgorithmParameterPath=s3://bucket/parameter-a.zip], "
                        + "AlgorithmMetadata[algorithmName=shared-algorithm, algorithmVersion=1.0.0, "
                        + "latestAlgorithmParameter=parameter-b, absoluteS3AlgorithmJarPath=s3://bucket/shared-algorithm-1.0.0.jar, "
                        + "absoluteS3AlgorithmParameterPath=s3://bucket/parameter-b.zip]",
                exception.getMessage());
    }

    @Test
    void failsWhenDefaultVariantUsesDifferentParameterForSameAlgorithmVersion() {
        final OnlineSlotToExperimentationStateConverter converter =
                new OnlineSlotToExperimentationStateConverter(testAlgorithmRepository());
        final Slot slot = new Slot(
                "slot-salt",
                10,
                variant(1, "shared-algorithm", "1.0.0", "parameter-default", null, true),
                List.of(new Experiment(
                        42,
                        "parameter-test",
                        List.of(variant(2, "shared-algorithm", "1.0.0", "parameter-a", 100, false)),
                        100,
                        List.of(shard(1)))),
                List.of());

        final IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> converter.convert(slot));

        assertEquals(
                "Unmatched algorithm metadata found:"
                        + "AlgorithmMetadata[algorithmName=shared-algorithm, algorithmVersion=1.0.0, "
                        + "latestAlgorithmParameter=parameter-a, absoluteS3AlgorithmJarPath=s3://bucket/shared-algorithm-1.0.0.jar, "
                        + "absoluteS3AlgorithmParameterPath=s3://bucket/parameter-a.zip], "
                        + "AlgorithmMetadata[algorithmName=shared-algorithm, algorithmVersion=1.0.0, "
                        + "latestAlgorithmParameter=parameter-default, absoluteS3AlgorithmJarPath=s3://bucket/shared-algorithm-1.0.0.jar, "
                        + "absoluteS3AlgorithmParameterPath=s3://bucket/parameter-default.zip]",
                exception.getMessage());
    }

    @Test
    void failsWhenShardIsOutsideConfiguredSlotRange() {
        final OnlineSlotToExperimentationStateConverter converter =
                new OnlineSlotToExperimentationStateConverter(testAlgorithmRepository());
        final Slot slot = new Slot(
                "slot-salt",
                3,
                defaultVariant(1),
                List.of(new Experiment(
                        42,
                        "out-of-range",
                        List.of(experimentVariant(2, 100, true)),
                        100,
                        List.of(shard(4)))),
                List.of());

        final IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> converter.convert(slot));

        assertEquals("Experiment 42 shard 4 must be within [1, 3]", exception.getMessage());
    }

    @Test
    void failsWhenShardIsAssignedToMultipleExperiments() {
        final OnlineSlotToExperimentationStateConverter converter =
                new OnlineSlotToExperimentationStateConverter(testAlgorithmRepository());
        final Slot slot = new Slot(
                "slot-salt",
                10,
                defaultVariant(1),
                List.of(
                        new Experiment(
                                42,
                                "experiment-a",
                                List.of(experimentVariant(2, 100, true)),
                                100,
                                List.of(shard(2))),
                        new Experiment(
                                43,
                                "experiment-b",
                                List.of(experimentVariant(3, 100, true)),
                                100,
                                List.of(shard(2)))),
                List.of());

        final IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> converter.convert(slot));

        assertEquals("Shard 2 is assigned to multiple experiments: 42 and 43", exception.getMessage());
    }

    @Test
    void failsWhenExperimentVariantsShareVariantId() {
        final OnlineSlotToExperimentationStateConverter converter =
                new OnlineSlotToExperimentationStateConverter(testAlgorithmRepository());
        final Slot slot = new Slot(
                "slot-salt",
                10,
                defaultVariant(1),
                List.of(new Experiment(
                        42,
                        "duplicate-variants",
                        List.of(
                                experimentVariant(2, 50, true),
                                experimentVariant(2, 50, false)),
                        100,
                        List.of(shard(1)))),
                List.of());

        final IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> converter.convert(slot));

        assertEquals("Duplicate variant id 2", exception.getMessage());
    }

    @Test
    void failsWhenDefaultVariantSharesVariantIdWithExperimentVariant() {
        final OnlineSlotToExperimentationStateConverter converter =
                new OnlineSlotToExperimentationStateConverter(testAlgorithmRepository());
        final Slot slot = new Slot(
                "slot-salt",
                10,
                defaultVariant(1),
                List.of(new Experiment(
                        42,
                        "duplicate-default",
                        List.of(experimentVariant(1, 100, true)),
                        100,
                        List.of(shard(1)))),
                List.of());

        final IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> converter.convert(slot));

        assertEquals("Duplicate variant id 1", exception.getMessage());
    }

    @Test
    void preservesUserForcedAssignmentEvenWhenVariantIsUnknownToMatchLegacyEmsFallback() throws Exception {
        final OnlineSlotToExperimentationStateConverter converter =
                new OnlineSlotToExperimentationStateConverter(testAlgorithmRepository());
        final Slot slot = new Slot(
                "slot-salt",
                10,
                defaultVariant(1),
                List.of(),
                List.of(new UserForcedAssignment("user-1", 99)));

        final var state = converter.convert(slot);

        assertEquals(99, state.userForcedAssignments().get("user-1"));
    }

    @Test
    void failsWhenUserForcedAssignmentIsDuplicated() {
        final OnlineSlotToExperimentationStateConverter converter =
                new OnlineSlotToExperimentationStateConverter(testAlgorithmRepository());
        final Slot slot = new Slot(
                "slot-salt",
                10,
                defaultVariant(1),
                List.of(),
                List.of(
                        new UserForcedAssignment("user-1", 1),
                        new UserForcedAssignment("user-1", 1)));

        final IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> converter.convert(slot));

        assertEquals("Duplicate user forced assignment for user user-1", exception.getMessage());
    }

    private static AlgorithmRepository testAlgorithmRepository() {
        return new AlgorithmRepository(new AlgorithmDownloader(
                null,
                System.getProperty("java.io.tmpdir"),
                OnlineSlotToExperimentationStateConverterTest.class.getClassLoader(),
                false)) {
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
                                new AlgorithmId(
                                        algorithmMetadata.algorithmName(),
                                        algorithmMetadata.algorithmVersion()),
                                algorithmMetadata.latestAlgorithmParameter(),
                                Instant.now(),
                                Optional.empty()),
                        new TestAlgorithm());
            }
        };
    }

    private static Variant defaultVariant(final int variantId) {
        return new Variant(
                variantId,
                algorithmMetadata(variantId),
                Instant.parse("2026-04-12T10:15:30Z"),
                true,
                true,
                null);
    }

    private static Variant experimentVariant(
            final int variantId,
            final int shardAllocationRatio,
            final boolean isControl) {
        return new Variant(
                variantId,
                algorithmMetadata(variantId),
                Instant.parse("2026-04-12T10:15:30Z"),
                isControl,
                false,
                shardAllocationRatio);
    }

    private static Variant variant(
            final int variantId,
            final String algorithmName,
            final String algorithmVersion,
            final String parameterId,
            final Integer shardAllocationRatio,
            final Boolean isDefault) {
        return new Variant(
                variantId,
                algorithmMetadata(algorithmName, algorithmVersion, parameterId),
                Instant.parse("2026-04-12T10:15:30Z"),
                false,
                isDefault,
                shardAllocationRatio);
    }

    private static AlgorithmMetadata algorithmMetadata(final int variantId) {
        return new AlgorithmMetadata(
                "algorithm-" + variantId,
                "1.0.0",
                "parameter-" + variantId,
                "s3://bucket/algorithm-" + variantId + ".jar",
                "s3://bucket/parameter-" + variantId + ".zip");
    }

    private static AlgorithmMetadata algorithmMetadata(
            final String algorithmName,
            final String algorithmVersion,
            final String parameterId) {
        return new AlgorithmMetadata(
                algorithmName,
                algorithmVersion,
                parameterId,
                "s3://bucket/" + algorithmName + "-" + algorithmVersion + ".jar",
                "s3://bucket/" + parameterId + ".zip");
    }

    private static Shard shard(final int shardId) {
        return new Shard(shardId, Instant.parse("2026-04-12T10:15:30Z"));
    }

    private static final class TestAlgorithm implements Algorithm {
    }
}
