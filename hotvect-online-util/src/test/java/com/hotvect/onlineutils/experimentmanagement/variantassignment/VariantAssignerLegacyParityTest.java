package com.hotvect.onlineutils.experimentmanagement.variantassignment;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import com.hotvect.onlineutils.experimentmanagement.models.Experiment;
import com.hotvect.onlineutils.experimentmanagement.models.Shard;
import com.hotvect.onlineutils.experimentmanagement.models.Slot;
import com.hotvect.onlineutils.experimentmanagement.models.UserForcedAssignment;
import com.hotvect.onlineutils.experimentmanagement.models.Variant;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class VariantAssignerLegacyParityTest {
    @SuppressWarnings("deprecation")
    private static final HashFunction MD5 = Hashing.md5();
    private static final String ASSIGNMENT_KEY_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_";
    private static final String SLOT_SALT_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789-_";

    @Test
    void preservesLegacyExperimentHashInputOrder() {
        Slot slot = slot(
                "slot-salt",
                11,
                100,
                Set.of(1, 3, 7),
                List.of(variant(2, 1), variant(3, 1)),
                Map.of());
        VariantAssigner assigner = new VariantAssigner(slot);
        String assignmentKey = findAssignmentWithDifferentSwappedKeyOutcome(slot);

        int legacyVariantId = legacyAssign(assignmentKey, slot, VariantAssignerLegacyParityTest::legacyBucket).variantId();
        int currentVariantId = assigner.assign(assignmentKey).variantId();
        int swappedKeyVariantId = swappedShardKeyAssign(assignmentKey, slot).variantId();

        assertEquals(legacyVariantId, currentVariantId);
        assertNotEquals(swappedKeyVariantId, currentVariantId);
    }

    @Test
    void matchesLegacyAssignmentAcrossLargeDeterministicSample() {
        Slot slot = slot(
                "slot-salt-2026",
                17,
                37,
                Set.of(1, 2, 5, 8, 9, 11, 13, 17),
                List.of(variant(2, 1), variant(3, 3), variant(4, 2)),
                Map.of());
        VariantAssigner assigner = new VariantAssigner(slot);

        for (int i = 0; i < 50_000; i++) {
            String assignmentKey = "customer-" + i;
            assertEquals(
                    legacyAssign(assignmentKey, slot, VariantAssignerLegacyParityTest::legacyBucket).variantId(),
                    assigner.assign(assignmentKey).variantId(),
                    assignmentKey);
        }
    }

    @Test
    void matchesLegacyUserForcedAssignments() {
        Slot slot = slot(
                "slot-salt-2026",
                1,
                100,
                Set.of(1),
                List.of(variant(2, 1), variant(3, 1)),
                Map.of("forced-user", 3));

        assertEquals(
                legacyAssign("forced-user", slot, VariantAssignerLegacyParityTest::legacyBucket).variantId(),
                new VariantAssigner(slot).assign("forced-user").variantId());
    }

    @Test
    void matchesLegacyAssignmentForGeneratedOrganicTraffic() {
        Random random = new Random(0xC0FFEE42L);

        for (int i = 0; i < 1_000; i++) {
            int sampleIndex = i;
            String assignmentKey = sampleString(random, ASSIGNMENT_KEY_CHARS, 1, 24);
            int totalShards = random.nextInt(20) + 1;
            Slot slot = slot(
                    sampleString(random, SLOT_SALT_CHARS, 1, 16),
                    totalShards,
                    random.nextInt(101),
                    sampleShards(random, totalShards),
                    sampleVariants(random),
                    Map.of());

            assertEquals(
                    legacyAssign(assignmentKey, slot, VariantAssignerLegacyParityTest::legacyBucket).variantId(),
                    new VariantAssigner(slot).assign(assignmentKey).variantId(),
                    () -> "Organic parity mismatch at sample " + sampleIndex);
        }
    }

    @Test
    void preservesLegacyModuloForNegativeHashes() {
        assertEquals(1, VariantAssigner.bucketFromHash(-1, 3));
        assertEquals(2, Math.floorMod(-1, 3));
        assertEquals(-48, VariantAssigner.bucketFromHash(Integer.MIN_VALUE, 100));
        assertEquals(52, Math.floorMod(Integer.MIN_VALUE, 100));
    }

    @Test
    void floorModWouldChangeRealAssignments() {
        Slot slot = slot(
                "slot-salt-2026",
                17,
                37,
                Set.of(1, 2, 5, 8, 9, 11, 13, 17),
                List.of(variant(2, 1), variant(3, 3), variant(4, 2)),
                Map.of());
        VariantAssigner assigner = new VariantAssigner(slot);
        String assignmentKey = findAssignmentWithDifferentFloorModOutcome(slot);

        int legacyVariantId = legacyAssign(
                assignmentKey, slot, VariantAssignerLegacyParityTest::legacyBucket).variantId();
        int floorModVariantId = legacyAssign(assignmentKey, slot, Math::floorMod).variantId();

        assertEquals(legacyVariantId, assigner.assign(assignmentKey).variantId());
        assertNotEquals(floorModVariantId, assigner.assign(assignmentKey).variantId());
    }

    private static Variant legacyAssign(String assignmentKey, Slot slot, BucketFunction bucketFunction) {
        for (UserForcedAssignment forced : slot.userForcedAssignments()) {
            if (forced.userId().equals(assignmentKey)) {
                return variant(slot, forced.variantId());
            }
        }
        int shardId = bucket(randomizationKey(slot.slotSalt(), assignmentKey), slot.totalNumberOfShards(), bucketFunction) + 1;
        Experiment experiment = experimentForShard(slot, shardId);
        if (experiment == null) {
            return slot.defaultVariant();
        }
        List<Variant> expanded = expandedVariants(experiment);
        Variant selected = expanded.get(bucket(
                randomizationKey(slot.slotSalt(), assignmentKey, String.valueOf(experiment.experimentId())),
                expanded.size(),
                bucketFunction));
        if (experiment.rampUpPercentage() == 100) {
            return selected;
        }
        int rampUpBucket = bucket(
                randomizationKey(
                        slot.slotSalt(),
                        assignmentKey,
                        String.valueOf(experiment.experimentId()),
                        "ramp-up"),
                100,
                bucketFunction);
        return rampUpBucket < experiment.rampUpPercentage() ? selected : slot.defaultVariant();
    }

    private static Variant swappedShardKeyAssign(String assignmentKey, Slot slot) {
        int shardId = bucket(
                new StringBuilder(assignmentKey).append(slot.slotSalt()),
                slot.totalNumberOfShards(),
                VariantAssignerLegacyParityTest::legacyBucket) + 1;
        return experimentForShard(slot, shardId) == null
                ? slot.defaultVariant()
                : legacyAssign(assignmentKey, slot, VariantAssignerLegacyParityTest::legacyBucket);
    }

    private static String findAssignmentWithDifferentSwappedKeyOutcome(Slot slot) {
        for (int i = 0; i < 100_000; i++) {
            String assignmentKey = "customer-" + i;
            if (legacyAssign(assignmentKey, slot, VariantAssignerLegacyParityTest::legacyBucket).variantId()
                    != swappedShardKeyAssign(assignmentKey, slot).variantId()) {
                return assignmentKey;
            }
        }
        throw new AssertionError("No assignment distinguished the legacy and swapped hash keys");
    }

    private static String findAssignmentWithDifferentFloorModOutcome(Slot slot) {
        for (int i = 0; i < 100_000; i++) {
            String assignmentKey = "customer-" + i;
            if (legacyAssign(assignmentKey, slot, VariantAssignerLegacyParityTest::legacyBucket).variantId()
                    != legacyAssign(assignmentKey, slot, Math::floorMod).variantId()) {
                return assignmentKey;
            }
        }
        throw new AssertionError("No assignment distinguished legacy modulo from floorMod");
    }

    private static Experiment experimentForShard(Slot slot, int shardId) {
        for (Experiment experiment : slot.experiments()) {
            if (experiment.shards().stream().anyMatch(shard -> shard.shardId() == shardId)) {
                return experiment;
            }
        }
        return null;
    }

    private static Variant variant(Slot slot, int variantId) {
        if (slot.defaultVariant().variantId() == variantId) {
            return slot.defaultVariant();
        }
        return slot.experiments().stream()
                .flatMap(experiment -> experiment.variants().stream())
                .filter(variant -> variant.variantId() == variantId)
                .findFirst()
                .orElseThrow();
    }

    private static List<Variant> expandedVariants(Experiment experiment) {
        List<Variant> expanded = new ArrayList<>();
        experiment.variants().stream()
                .sorted(java.util.Comparator.comparingInt(Variant::variantId))
                .forEach(variant -> {
                    for (int i = 0; i < variant.shardAllocationRatio(); i++) {
                        expanded.add(variant);
                    }
                });
        return expanded;
    }

    private static StringBuilder randomizationKey(String slotSalt, String assignmentKey, String... suffixes) {
        StringBuilder key = new StringBuilder(slotSalt);
        for (String suffix : suffixes) {
            key.append(suffix);
        }
        return key.append(assignmentKey);
    }

    private static int bucket(CharSequence key, int bound, BucketFunction bucketFunction) {
        int hash = MD5.hashString(key, StandardCharsets.UTF_8).asInt();
        return bucketFunction.bucket(hash, bound);
    }

    private static int legacyBucket(int hash, int bound) {
        return Math.abs(hash) % bound;
    }

    private static String sampleString(Random random, String alphabet, int minLength, int maxLength) {
        int length = random.nextInt(maxLength - minLength + 1) + minLength;
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return value.toString();
    }

    private static Set<Integer> sampleShards(Random random, int totalShards) {
        Set<Integer> shards = new HashSet<>();
        int count = random.nextInt(21);
        for (int i = 0; i < count; i++) {
            shards.add(random.nextInt(totalShards) + 1);
        }
        return shards;
    }

    private static List<Variant> sampleVariants(Random random) {
        List<Variant> variants = new ArrayList<>();
        int count = random.nextInt(3) + 1;
        for (int i = 0; i < count; i++) {
            variants.add(variant(i + 2, random.nextInt(5) + 1));
        }
        return variants;
    }

    private static Slot slot(
            String slotSalt,
            int totalShards,
            int rampUpPercentage,
            Set<Integer> shards,
            List<Variant> experimentVariants,
            Map<String, Integer> forcedAssignments) {
        Experiment experiment = new Experiment(
                100,
                "experiment-100",
                experimentVariants,
                rampUpPercentage,
                shards.stream()
                        .map(shard -> new Shard(shard, Instant.parse("2026-04-12T10:15:30Z")))
                        .toList());
        return new Slot(
                slotSalt,
                totalShards,
                variant(1, 100),
                List.of(experiment),
                forcedAssignments.entrySet().stream()
                        .map(entry -> new UserForcedAssignment(entry.getKey(), entry.getValue()))
                        .toList());
    }

    private static Variant variant(int variantId, int allocation) {
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
                allocation);
    }

    @FunctionalInterface
    private interface BucketFunction {
        int bucket(int hash, int bound);
    }
}
