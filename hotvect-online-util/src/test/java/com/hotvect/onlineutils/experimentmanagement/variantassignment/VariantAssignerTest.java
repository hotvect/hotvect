package com.hotvect.onlineutils.experimentmanagement.variantassignment;

import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import com.hotvect.onlineutils.experimentmanagement.models.Experiment;
import com.hotvect.onlineutils.experimentmanagement.models.Shard;
import com.hotvect.onlineutils.experimentmanagement.models.Slot;
import com.hotvect.onlineutils.experimentmanagement.models.UserForcedAssignment;
import com.hotvect.onlineutils.experimentmanagement.models.Variant;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariantAssignerTest {
    @Test
    void returnsDefaultVariantWhenNoExperimentMatches() {
        Slot slot = slot(100, Set.of(), List.of(), Map.of());
        VariantAssigner assigner = new VariantAssigner(slot);

        assertSame(slot.defaultVariant(), assigner.assign("customer-1"));
    }

    @Test
    void forcedUserAssignmentWins() {
        VariantAssigner assigner = new VariantAssigner(
                slot(100, Set.of(1), List.of(variant(2, 100)), Map.of("forced-user", 2)));

        assertEquals(2, assigner.assign("forced-user").variantId());
    }

    @Test
    void assignsSameCustomerDeterministicallyWithinExperiment() {
        VariantAssigner assigner = new VariantAssigner(
                slot(100, Set.of(1), List.of(variant(2, 1), variant(3, 1)), Map.of()));
        String customer = findCustomerAssignedToVariant(assigner, 2);

        assertEquals(2, assigner.assign(customer).variantId());
        assertEquals(2, assigner.assign(customer).variantId());
    }

    @Test
    void cumulativeSelectionMatchesExactAllocationBoundaries() {
        List<Variant> variants = List.of(variant(2, 1), variant(3, 2), variant(4, 3));

        assertEquals(2, VariantAssigner.chooseVariantByAllocationBucket(0, variants).variantId());
        assertEquals(3, VariantAssigner.chooseVariantByAllocationBucket(1, variants).variantId());
        assertEquals(3, VariantAssigner.chooseVariantByAllocationBucket(2, variants).variantId());
        assertEquals(4, VariantAssigner.chooseVariantByAllocationBucket(3, variants).variantId());
        assertEquals(4, VariantAssigner.chooseVariantByAllocationBucket(4, variants).variantId());
        assertEquals(4, VariantAssigner.chooseVariantByAllocationBucket(5, variants).variantId());
    }

    @Test
    void hashedAssignmentsRoughlyMatchWeights() {
        VariantAssigner assigner = new VariantAssigner(
                slot(100, Set.of(1), List.of(variant(2, 1), variant(3, 2), variant(4, 3)), Map.of()));
        Map<Integer, Integer> countsByVariantId = new HashMap<>();
        int sampleSize = 60_000;

        for (int i = 0; i < sampleSize; i++) {
            countsByVariantId.merge(assigner.assign("customer-" + i).variantId(), 1, Integer::sum);
        }

        assertRatioClose(countsByVariantId.getOrDefault(2, 0), sampleSize, 1.0 / 6.0, 0.02);
        assertRatioClose(countsByVariantId.getOrDefault(3, 0), sampleSize, 2.0 / 6.0, 0.02);
        assertRatioClose(countsByVariantId.getOrDefault(4, 0), sampleSize, 3.0 / 6.0, 0.02);
    }

    @Test
    void rampUpCanRouteSomeTrafficBackToDefault() {
        VariantAssigner assigner = new VariantAssigner(
                slot(50, Set.of(1), List.of(variant(2, 100)), Map.of()));

        String treatmentCustomer = findCustomerAssignedToVariant(assigner, 2);
        String defaultCustomer = findCustomerAssignedToVariant(assigner, 1);

        assertNotNull(treatmentCustomer);
        assertNotNull(defaultCustomer);
    }

    @Test
    void validatesTheCompleteAssignmentStateDuringPreparation() {
        Slot invalid = slot(100, Set.of(1), List.of(variant(2, 0)), Map.of());

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new VariantAssigner(invalid));

        assertTrue(error.getMessage().contains("invalid shard allocation ratio"));
    }

    private static String findCustomerAssignedToVariant(VariantAssigner assigner, int expectedVariantId) {
        for (int i = 0; i < 100_000; i++) {
            String candidate = "customer-" + i;
            if (assigner.assign(candidate).variantId() == expectedVariantId) {
                return candidate;
            }
        }
        throw new AssertionError("No customer assigned to variant " + expectedVariantId);
    }

    private static Slot slot(
            int rampUpPercentage,
            Set<Integer> shards,
            List<Variant> experimentVariants,
            Map<String, Integer> forcedAssignments) {
        List<Experiment> experiments = experimentVariants.isEmpty()
                ? List.of()
                : List.of(new Experiment(
                        100,
                        "experiment-100",
                        experimentVariants,
                        rampUpPercentage,
                        shards.stream().map(VariantAssignerTest::shard).toList()));
        return new Slot(
                "slot-salt",
                shards.stream().mapToInt(Integer::intValue).max().orElse(100),
                variant(1, 100),
                experiments,
                forcedAssignments.entrySet().stream()
                        .map(entry -> new UserForcedAssignment(entry.getKey(), entry.getValue()))
                        .toList());
    }

    private static Shard shard(int shardId) {
        return new Shard(shardId, Instant.parse("2026-04-12T10:15:30Z"));
    }

    private static void assertRatioClose(
            int actualCount,
            int sampleSize,
            double expectedRatio,
            double tolerance) {
        double actualRatio = (double) actualCount / sampleSize;
        assertTrue(
                Math.abs(actualRatio - expectedRatio) <= tolerance,
                "Expected ratio around " + expectedRatio + " but was " + actualRatio);
    }

    private static Variant variant(int variantId, int shardAllocationRatio) {
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
}
