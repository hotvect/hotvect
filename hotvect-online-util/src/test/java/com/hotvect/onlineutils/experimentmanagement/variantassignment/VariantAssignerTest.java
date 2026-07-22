package com.hotvect.onlineutils.experimentmanagement.variantassignment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableMap;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import com.hotvect.onlineutils.experimentmanagement.models.Experiment;
import com.hotvect.onlineutils.experimentmanagement.models.ExperimentConfiguration;
import com.hotvect.onlineutils.experimentmanagement.models.ExperimentationState;
import com.hotvect.onlineutils.experimentmanagement.models.Shard;
import com.hotvect.onlineutils.experimentmanagement.models.Variant;
import com.hotvect.onlineutils.experimentmanagement.models.VariantConfiguration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VariantAssignerTest {

    @Test
    void returnsDefaultVariantWhenNoExperimentMatches() {
        ExperimentationState state = experimentationState(100, Set.of(), List.of(), Map.of());

        VariantConfiguration assigned =
                VariantAssigner.assignVariant("customer-1", state, 100);

        assertSame(state.defaultVariantConfiguration(), assigned);
    }

    @Test
    void forcedUserAssignmentWins() {
        ExperimentationState state =
                experimentationState(100, Set.of(1), List.of(variant(2, 100)), Map.of("forced-user", 2));

        VariantConfiguration assigned =
                VariantAssigner.assignVariant("forced-user", state, 1);

        assertEquals(2, assigned.variant().variantId());
    }

    @Test
    void assignsSameCustomerDeterministicallyWithinExperiment() {
        ExperimentationState state =
                experimentationState(100, Set.of(1), List.of(variant(2, 1), variant(3, 1)), Map.of());
        String customerNumber = findCustomerAssignedToVariant(state, 1, 2);

        VariantConfiguration firstAssignment =
                VariantAssigner.assignVariant(customerNumber, state, 1);
        VariantConfiguration secondAssignment =
                VariantAssigner.assignVariant(customerNumber, state, 1);

        assertEquals(2, firstAssignment.variant().variantId());
        assertEquals(2, secondAssignment.variant().variantId());
    }

    @Test
    void cumulativeSelectionMatchesExactAllocationBoundaries() {
        ExperimentConfiguration experimentConfiguration = experimentConfiguration(
                100,
                Set.of(1),
                List.of(variant(2, 1), variant(3, 2), variant(4, 3)));

        assertEquals(2, VariantAssigner.chooseVariantByAllocationBucket(0, experimentConfiguration).variant().variantId());
        assertEquals(3, VariantAssigner.chooseVariantByAllocationBucket(1, experimentConfiguration).variant().variantId());
        assertEquals(3, VariantAssigner.chooseVariantByAllocationBucket(2, experimentConfiguration).variant().variantId());
        assertEquals(4, VariantAssigner.chooseVariantByAllocationBucket(3, experimentConfiguration).variant().variantId());
        assertEquals(4, VariantAssigner.chooseVariantByAllocationBucket(4, experimentConfiguration).variant().variantId());
        assertEquals(4, VariantAssigner.chooseVariantByAllocationBucket(5, experimentConfiguration).variant().variantId());
    }

    @Test
    void cumulativeSelectionMatchesExpandedListReferenceImplementation() {
        ExperimentConfiguration experimentConfiguration = experimentConfiguration(
                100,
                Set.of(1),
                List.of(variant(2, 3), variant(3, 5), variant(4, 2)));

        List<VariantConfiguration> expandedReference =
                expandVariantConfigurations(experimentConfiguration);

        for (int bucket = 0; bucket < expandedReference.size(); bucket++) {
            int expectedVariantId = expandedReference.get(bucket).variant().variantId();
            int actualVariantId =
                    VariantAssigner.chooseVariantByAllocationBucket(bucket, experimentConfiguration).variant().variantId();
            assertEquals(expectedVariantId, actualVariantId, "bucket " + bucket);
        }
    }

    @Test
    void allocationBucketCountsMatchWeightsExactly() {
        ExperimentConfiguration experimentConfiguration = experimentConfiguration(
                100,
                Set.of(1),
                List.of(variant(2, 1), variant(3, 2), variant(4, 3)));

        Map<Integer, Integer> countsByVariantId = new HashMap<>();
        for (int bucket = 0; bucket < 6; bucket++) {
            int variantId =
                    VariantAssigner.chooseVariantByAllocationBucket(bucket, experimentConfiguration).variant().variantId();
            countsByVariantId.merge(variantId, 1, Integer::sum);
        }

        assertEquals(1, countsByVariantId.get(2));
        assertEquals(2, countsByVariantId.get(3));
        assertEquals(3, countsByVariantId.get(4));
    }

    @Test
    void hashedAssignmentsRoughlyMatchWeights() {
        ExperimentationState state =
                experimentationState(100, Set.of(1), List.of(variant(2, 1), variant(3, 2), variant(4, 3)), Map.of());

        Map<Integer, Integer> countsByVariantId = new HashMap<>();
        int sampleSize = 60_000;
        for (int i = 0; i < sampleSize; i++) {
            int variantId = VariantAssigner.assignVariant("customer-" + i, state, 1).variant().variantId();
            countsByVariantId.merge(variantId, 1, Integer::sum);
        }

        assertRatioClose(countsByVariantId.getOrDefault(2, 0), sampleSize, 1.0 / 6.0, 0.02);
        assertRatioClose(countsByVariantId.getOrDefault(3, 0), sampleSize, 2.0 / 6.0, 0.02);
        assertRatioClose(countsByVariantId.getOrDefault(4, 0), sampleSize, 3.0 / 6.0, 0.02);
    }

    @Test
    void rampUpCanRouteSomeTrafficBackToDefault() {
        ExperimentationState state =
                experimentationState(50, Set.of(1), List.of(variant(2, 100)), Map.of());

        String treatmentCustomer = findCustomerAssignedToVariant(state, 1, 2);
        String defaultCustomer = findCustomerAssignedToVariant(state, 1, 1);

        assertNotNull(treatmentCustomer);
        assertNotNull(defaultCustomer);
        assertEquals(2, VariantAssigner.assignVariant(treatmentCustomer, state, 1).variant().variantId());
        assertEquals(1, VariantAssigner.assignVariant(defaultCustomer, state, 1).variant().variantId());
    }

    private static String findCustomerAssignedToVariant(
            final ExperimentationState state,
            final int totalNumberOfShards,
            final int expectedVariantId) {
        for (int i = 0; i < 100_000; i++) {
            String candidate = "customer-" + i;
            int assignedVariantId =
                    VariantAssigner.assignVariant(candidate, state, totalNumberOfShards).variant().variantId();
            if (assignedVariantId == expectedVariantId) {
                return candidate;
            }
        }
        throw new AssertionError("No customer assigned to variant " + expectedVariantId);
    }

    private static ExperimentationState experimentationState(
            final int rampUpPercentage,
            final Set<Integer> shards,
            final List<Variant> experimentVariants,
            final Map<String, Integer> userForcedAssignments) {
        Variant defaultVariant = variant(1, 100);
        VariantConfiguration defaultVariantConfiguration =
                new VariantConfiguration(defaultVariant, null);

        Map<Integer, VariantConfiguration> variantConfigurations = new HashMap<>();
        variantConfigurations.put(defaultVariant.variantId(), defaultVariantConfiguration);

        ImmutableMap<Integer, ExperimentConfiguration> shardIdToExperimentConfiguration = ImmutableMap.of();
        if (!experimentVariants.isEmpty()) {
            ExperimentConfiguration experimentConfiguration =
                    experimentConfiguration(rampUpPercentage, shards, experimentVariants);
            experimentConfiguration.variants().forEach(variantConfiguration ->
                    variantConfigurations.put(variantConfiguration.variant().variantId(), variantConfiguration));

            Map<Integer, ExperimentConfiguration> shardMappings = new HashMap<>();
            shards.forEach(shardId -> shardMappings.put(shardId, experimentConfiguration));
            shardIdToExperimentConfiguration = ImmutableMap.copyOf(shardMappings);
        }

        int maxVariantId = variantConfigurations.keySet().stream().mapToInt(Integer::intValue).max().orElse(1);
        return new ExperimentationState(
                maxVariantId,
                shardIdToExperimentConfiguration,
                ImmutableMap.copyOf(variantConfigurations),
                ImmutableMap.copyOf(userForcedAssignments),
                "slot-salt",
                defaultVariantConfiguration);
    }

    private static ExperimentConfiguration experimentConfiguration(
            final int rampUpPercentage,
            final Set<Integer> shards,
            final List<Variant> experimentVariants) {
        List<VariantConfiguration> experimentVariantConfigurations = experimentVariants.stream()
                .map(variant -> new VariantConfiguration(variant, null))
                .toList();
        Experiment experiment = new Experiment(
                100,
                "experiment-100",
                experimentVariants,
                rampUpPercentage,
                toShards(shards));
        return new ExperimentConfiguration(experiment, experimentVariantConfigurations);
    }

    private static List<Shard> toShards(final Set<Integer> shards) {
        return shards.stream()
                .map(VariantAssignerTest::shard)
                .toList();
    }

    private static Shard shard(final int shardId) {
        return new Shard(shardId, Instant.parse("2026-04-12T10:15:30Z"));
    }

    private static List<VariantConfiguration> expandVariantConfigurations(
            final ExperimentConfiguration experimentConfiguration) {
        List<VariantConfiguration> expandedReference = new ArrayList<>();
        for (VariantConfiguration variantConfiguration : experimentConfiguration.variants()) {
            for (int i = 0; i < variantConfiguration.variant().shardAllocationRatio(); i++) {
                expandedReference.add(variantConfiguration);
            }
        }
        return expandedReference;
    }

    private static void assertRatioClose(
            final int actualCount,
            final int sampleSize,
            final double expectedRatio,
            final double tolerance) {
        double actualRatio = (double) actualCount / sampleSize;
        assertTrue(
                Math.abs(actualRatio - expectedRatio) <= tolerance,
                "Expected ratio around " + expectedRatio + " but was " + actualRatio);
    }

    private static Variant variant(final int variantId, final int shardAllocationRatio) {
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
                false,
                shardAllocationRatio);
    }

    private static final class TestAlgorithm implements Algorithm {
    }
}
