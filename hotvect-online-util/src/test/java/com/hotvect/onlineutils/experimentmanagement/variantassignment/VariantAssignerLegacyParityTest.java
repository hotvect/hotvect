package com.hotvect.onlineutils.experimentmanagement.variantassignment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import com.hotvect.onlineutils.experimentmanagement.models.Experiment;
import com.hotvect.onlineutils.experimentmanagement.models.ExperimentConfiguration;
import com.hotvect.onlineutils.experimentmanagement.models.ExperimentationState;
import com.hotvect.onlineutils.experimentmanagement.models.Shard;
import com.hotvect.onlineutils.experimentmanagement.models.Variant;
import com.hotvect.onlineutils.experimentmanagement.models.VariantConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VariantAssignerLegacyParityTest {
    @SuppressWarnings("deprecation")
    private static final HashFunction MD5 = Hashing.md5();
    private static final int LARGE_SAMPLE_SIZE = 50_000;
    private static final int ORGANIC_TRAFFIC_PARITY_SAMPLES = 1_000;
    private static final int FORCED_ASSIGNMENT_PARITY_SAMPLES = 250;
    private static final String ASSIGNMENT_KEY_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_";
    private static final String SLOT_SALT_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789-_";

    @Test
    void preservesLegacyExperimentHashInputOrder() {
        final int totalNumberOfShards = 11;
        final ExperimentationState state = experimentationState(
                "slot-salt",
                100,
                Set.of(1, 3, 7),
                List.of(variant(2, 1), variant(3, 1)),
                Map.of());
        final String customerNumber =
                findCustomerWithDifferentLegacyAndSwappedKeyOutcomes(state, totalNumberOfShards);

        final int legacyVariantId =
                legacyAssignedVariantConfiguration(customerNumber, state, totalNumberOfShards).variant().variantId();
        final int currentVariantId =
                VariantAssigner.assignVariant(customerNumber, state, totalNumberOfShards).variant().variantId();
        final int swappedKeyVariantId =
                brokenAssignmentWithSwappedExperimentKeyOrder(customerNumber, state, totalNumberOfShards)
                        .variant()
                        .variantId();

        assertEquals(legacyVariantId, currentVariantId);
        assertNotEquals(swappedKeyVariantId, currentVariantId);
    }

    @Test
    void matchesLegacyEmsAssignmentAcrossLargeDeterministicSample() {
        final int totalNumberOfShards = 17;
        final ExperimentationState state = experimentationState(
                "slot-salt-2026",
                37,
                Set.of(1, 2, 5, 8, 9, 11, 13, 17),
                List.of(variant(2, 1), variant(3, 3), variant(4, 2)),
                Map.of());

        for (int i = 0; i < LARGE_SAMPLE_SIZE; i++) {
            final String customerNumber = "customer-" + i;
            assertEquals(
                    legacyAssignedVariantConfiguration(customerNumber, state, totalNumberOfShards).variant().variantId(),
                    VariantAssigner.assignVariant(customerNumber, state, totalNumberOfShards).variant().variantId(),
                    customerNumber);
        }
    }

    @Test
    void matchesLegacyEmsAssignmentForUserForcedAssignments() {
        final ExperimentationState state = experimentationState(
                "slot-salt-2026",
                100,
                Set.of(1),
                List.of(variant(2, 1), variant(3, 1)),
                Map.of("forced-user", 3));

        assertEquals(
                legacyAssignedVariantConfiguration("forced-user", state, 1).variant().variantId(),
                VariantAssigner.assignVariant("forced-user", state, 1).variant().variantId());
    }

    @Test
    void floorModWouldChangeAssignmentsForNegativeHashes() {
        assertEquals(1, VariantAssigner.bucketFromHash(-1, 3));
        assertEquals(2, Math.floorMod(-1, 3));
        assertNotEquals(VariantAssigner.bucketFromHash(-1, 3), Math.floorMod(-1, 3));
    }

    @Test
    void floorModWouldChangeRealAssignmentsForNegativeHashes() {
        final int totalNumberOfShards = 17;
        final ExperimentationState state = experimentationState(
                "slot-salt-2026",
                37,
                Set.of(1, 2, 5, 8, 9, 11, 13, 17),
                List.of(variant(2, 1), variant(3, 3), variant(4, 2)),
                Map.of());
        final String customerNumber = findCustomerWithDifferentLegacyAndFloorModOutcomes(state, totalNumberOfShards);

        final int legacyVariantId =
                legacyAssignedVariantConfiguration(customerNumber, state, totalNumberOfShards).variant().variantId();
        final int currentVariantId =
                VariantAssigner.assignVariant(customerNumber, state, totalNumberOfShards).variant().variantId();
        final int floorModVariantId =
                floorModAssignedVariantConfiguration(customerNumber, state, totalNumberOfShards).variant().variantId();

        assertEquals(legacyVariantId, currentVariantId);
        assertNotEquals(floorModVariantId, currentVariantId);
    }

    @Test
    void preservesLegacyIntegerMinValueOverflow() {
        assertEquals(-48, VariantAssigner.bucketFromHash(Integer.MIN_VALUE, 100));
        assertEquals(-47, VariantAssigner.bucketFromHash(Integer.MIN_VALUE, 100) + 1);
        assertEquals(-48, Math.abs(Integer.MIN_VALUE) % 100);
        assertEquals(52, Math.floorMod(Integer.MIN_VALUE, 100));
        assertNotEquals(
                VariantAssigner.bucketFromHash(Integer.MIN_VALUE, 100),
                Math.floorMod(Integer.MIN_VALUE, 100));
    }

    @Test
    void matchesLegacyEmsAssignmentForOrganicTraffic() {
        final Random random = new Random(0xC0FFEE42L);

        for (int i = 0; i < ORGANIC_TRAFFIC_PARITY_SAMPLES; i++) {
            final int sampleIndex = i;
            final String customerNumber = sampleAssignmentKey(random);
            final String slotSalt = sampleSlotSalt(random);
            final int totalNumberOfShards = random.nextInt(20) + 1;
            final int rampUpPercentage = random.nextInt(101);
            final List<Integer> variantWeights = sampleVariantWeightList(random);
            final List<Integer> shardCandidates = sampleShardCandidates(random);

            final ExperimentationState state = experimentationState(
                    slotSalt,
                    rampUpPercentage,
                    toShardSet(totalNumberOfShards, shardCandidates),
                    variants(variantWeights),
                    Map.of());

            assertEquals(
                    legacyAssignedVariantConfiguration(customerNumber, state, totalNumberOfShards)
                            .variant()
                            .variantId(),
                    VariantAssigner.assignVariant(customerNumber, state, totalNumberOfShards)
                            .variant()
                            .variantId(),
                    () -> "Organic parity mismatch at sample " + sampleIndex);
        }
    }

    @Test
    void matchesLegacyEmsAssignmentForGeneratedUserForcedAssignments() {
        final Random random = new Random(0x5EED1234L);

        for (int i = 0; i < FORCED_ASSIGNMENT_PARITY_SAMPLES; i++) {
            final int sampleIndex = i;
            final String customerNumber = sampleAssignmentKey(random);
            final String slotSalt = sampleSlotSalt(random);
            final List<Variant> experimentVariants = variants(sampleVariantWeightList(random));
            final int forcedVariantId = experimentVariants.get(experimentVariants.size() - 1).variantId();
            final ExperimentationState state = experimentationState(
                    slotSalt,
                    100,
                    Set.of(1),
                    experimentVariants,
                    Map.of(customerNumber, forcedVariantId));

            assertEquals(
                    legacyAssignedVariantConfiguration(customerNumber, state, 1).variant().variantId(),
                    VariantAssigner.assignVariant(customerNumber, state, 1).variant().variantId(),
                    () -> "Forced-assignment parity mismatch at sample " + sampleIndex);
        }
    }

    private static String sampleAssignmentKey(final Random random) {
        return sampleString(random, ASSIGNMENT_KEY_CHARS, 1, 24);
    }

    private static String sampleSlotSalt(final Random random) {
        return sampleString(random, SLOT_SALT_CHARS, 1, 16);
    }

    private static String sampleString(
            final Random random,
            final String alphabet,
            final int minLength,
            final int maxLength) {
        final int length = random.nextInt(maxLength - minLength + 1) + minLength;
        final StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return builder.toString();
    }

    private static List<Integer> sampleVariantWeightList(final Random random) {
        final int size = random.nextInt(3) + 1;
        final List<Integer> weights = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            weights.add(random.nextInt(5) + 1);
        }
        return weights;
    }

    private static List<Integer> sampleShardCandidates(final Random random) {
        final int size = random.nextInt(21);
        final List<Integer> shardCandidates = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            shardCandidates.add(random.nextInt(20) + 1);
        }
        return shardCandidates;
    }

    private static String findCustomerWithDifferentLegacyAndSwappedKeyOutcomes(
            final ExperimentationState state,
            final int totalNumberOfShards) {
        for (int i = 0; i < 100_000; i++) {
            final String candidate = "customer-" + i;
            final int legacyVariantId =
                    legacyAssignedVariantConfiguration(candidate, state, totalNumberOfShards).variant().variantId();
            final int brokenVariantId =
                    brokenAssignmentWithSwappedExperimentKeyOrder(candidate, state, totalNumberOfShards)
                            .variant()
                            .variantId();
            if (legacyVariantId != brokenVariantId) {
                return candidate;
            }
        }
        throw new AssertionError("Failed to find a customer that distinguishes legacy and swapped key order");
    }

    private static String findCustomerWithDifferentLegacyAndFloorModOutcomes(
            final ExperimentationState state,
            final int totalNumberOfShards) {
        for (int i = 0; i < 100_000; i++) {
            final String candidate = "customer-" + i;
            final int legacyVariantId =
                    legacyAssignedVariantConfiguration(candidate, state, totalNumberOfShards).variant().variantId();
            final int floorModVariantId =
                    floorModAssignedVariantConfiguration(candidate, state, totalNumberOfShards).variant().variantId();
            if (legacyVariantId != floorModVariantId) {
                return candidate;
            }
        }
        throw new AssertionError("Failed to find a customer that distinguishes legacy modulo from floorMod");
    }

    private static VariantConfiguration legacyAssignedVariantConfiguration(
            final String customerNumber,
            final ExperimentationState state,
            final int totalNumberOfShards) {
        return assignedVariantConfiguration(
                customerNumber,
                state,
                totalNumberOfShards,
                VariantAssignerLegacyParityTest::legacyBucketFromHash);
    }

    private static VariantConfiguration floorModAssignedVariantConfiguration(
            final String customerNumber,
            final ExperimentationState state,
            final int totalNumberOfShards) {
        return assignedVariantConfiguration(customerNumber, state, totalNumberOfShards, Math::floorMod);
    }

    private static VariantConfiguration brokenAssignmentWithSwappedExperimentKeyOrder(
            final String customerNumber,
            final ExperimentationState state,
            final int totalNumberOfShards) {
        final VariantConfiguration forcedVariantConfiguration = forcedVariantConfiguration(customerNumber, state);
        if (forcedVariantConfiguration != null) {
            return forcedVariantConfiguration;
        }

        final VariantConfiguration defaultVariantConfiguration = state.defaultVariantConfiguration();
        final ExperimentConfiguration experimentConfiguration = state.shardId2ExperimentConfiguration().get(
                experimentShardId(new StringBuilder(customerNumber).append(state.slotSalt()),
                        totalNumberOfShards,
                        VariantAssignerLegacyParityTest::legacyBucketFromHash));
        if (experimentConfiguration == null) {
            return defaultVariantConfiguration;
        }

        return legacyAssignedVariantConfiguration(customerNumber, state, totalNumberOfShards);
    }

    private static VariantConfiguration assignedVariantConfiguration(
            final String customerNumber,
            final ExperimentationState state,
            final int totalNumberOfShards,
            final BucketFunction bucketFunction) {
        final VariantConfiguration forcedVariantConfiguration = forcedVariantConfiguration(customerNumber, state);
        if (forcedVariantConfiguration != null) {
            return forcedVariantConfiguration;
        }

        final VariantConfiguration defaultVariantConfiguration = state.defaultVariantConfiguration();
        final ExperimentConfiguration experimentConfiguration = state.shardId2ExperimentConfiguration().get(
                experimentShardId(randomizationKey(customerNumber, state.slotSalt()), totalNumberOfShards, bucketFunction));
        if (experimentConfiguration == null) {
            return defaultVariantConfiguration;
        }

        final Experiment experiment = experimentConfiguration.experiment();
        final List<VariantConfiguration> expandedVariants = expandVariantConfigurations(experimentConfiguration);
        final VariantConfiguration chosenVariantConfiguration = expandedVariants.get(
                bucketFromRandomizationKey(
                        randomizationKey(customerNumber, state.slotSalt(), String.valueOf(experiment.experimentId())),
                        expandedVariants.size(),
                        bucketFunction));

        if (experiment.rampUpPercentage() == 100) {
            return chosenVariantConfiguration;
        }

        final int rampUpBucket = bucketFromRandomizationKey(
                randomizationKey(customerNumber, state.slotSalt(), String.valueOf(experiment.experimentId()), "ramp-up"),
                100,
                bucketFunction);
        if (rampUpBucket >= experiment.rampUpPercentage()) {
            return defaultVariantConfiguration;
        }
        return chosenVariantConfiguration;
    }

    private static int experimentShardId(
            final StringBuilder randomizationKey,
            final int totalNumberOfShards,
            final BucketFunction bucketFunction) {
        return bucketFromRandomizationKey(randomizationKey, totalNumberOfShards, bucketFunction) + 1;
    }

    private static int bucketFromRandomizationKey(
            final CharSequence randomizationKey,
            final int numberOfBuckets,
            final BucketFunction bucketFunction) {
        final int hashAsInt = MD5.hashString(randomizationKey, StandardCharsets.UTF_8).asInt();
        return bucketFunction.bucketFromHash(hashAsInt, numberOfBuckets);
    }

    private static int legacyBucketFromHash(final int hashAsInt, final int numberOfBuckets) {
        return Math.abs(hashAsInt) % numberOfBuckets;
    }

    private static StringBuilder randomizationKey(
            final String customerNumber,
            final String slotSalt,
            final String... args) {
        final StringBuilder randomizationKey = new StringBuilder(slotSalt);
        for (final String arg : args) {
            randomizationKey.append(arg);
        }
        randomizationKey.append(customerNumber);
        return randomizationKey;
    }

    private static VariantConfiguration forcedVariantConfiguration(
            final String customerNumber,
            final ExperimentationState state) {
        if (!state.userForcedAssignments().containsKey(customerNumber)) {
            return null;
        }
        return state.variantId2VariantConfiguration().get(state.userForcedAssignments().get(customerNumber));
    }

    private static List<VariantConfiguration> expandVariantConfigurations(
            final ExperimentConfiguration experimentConfiguration) {
        final List<VariantConfiguration> expandedVariants = new ArrayList<>();
        for (final VariantConfiguration variantConfiguration : experimentConfiguration.variants()) {
            for (int i = 0; i < variantConfiguration.variant().shardAllocationRatio(); i++) {
                expandedVariants.add(variantConfiguration);
            }
        }
        return expandedVariants;
    }

    private static ExperimentationState experimentationState(
            final String slotSalt,
            final int rampUpPercentage,
            final Set<Integer> shards,
            final List<Variant> experimentVariants,
            final Map<String, Integer> userForcedAssignments) {
        final Variant defaultVariant = variant(1, 100);
        final VariantConfiguration defaultVariantConfiguration =
                new VariantConfiguration(defaultVariant, null);

        final Map<Integer, VariantConfiguration> variantConfigurations = new HashMap<>();
        variantConfigurations.put(defaultVariant.variantId(), defaultVariantConfiguration);

        ImmutableMap<Integer, ExperimentConfiguration> shardIdToExperimentConfiguration = ImmutableMap.of();
        if (!experimentVariants.isEmpty()) {
            final ExperimentConfiguration experimentConfiguration =
                    experimentConfiguration(rampUpPercentage, shards, experimentVariants);
            experimentConfiguration.variants().forEach(variantConfiguration ->
                    variantConfigurations.put(variantConfiguration.variant().variantId(), variantConfiguration));

            final Map<Integer, ExperimentConfiguration> shardMappings = new HashMap<>();
            shards.forEach(shardId -> shardMappings.put(shardId, experimentConfiguration));
            shardIdToExperimentConfiguration = ImmutableMap.copyOf(shardMappings);
        }

        final int maxVariantId = variantConfigurations.keySet().stream().mapToInt(Integer::intValue).max().orElse(1);
        return new ExperimentationState(
                maxVariantId,
                shardIdToExperimentConfiguration,
                ImmutableMap.copyOf(variantConfigurations),
                ImmutableMap.copyOf(userForcedAssignments),
                slotSalt,
                defaultVariantConfiguration);
    }

    private static ExperimentConfiguration experimentConfiguration(
            final int rampUpPercentage,
            final Set<Integer> shards,
            final List<Variant> experimentVariants) {
        final List<VariantConfiguration> experimentVariantConfigurations = experimentVariants.stream()
                .map(variant -> new VariantConfiguration(variant, null))
                .toList();
        final Experiment experiment = new Experiment(
                100,
                "experiment-100",
                experimentVariants,
                rampUpPercentage,
                toShards(shards));
        return new ExperimentConfiguration(experiment, experimentVariantConfigurations);
    }

    private static List<Shard> toShards(final Set<Integer> shards) {
        return shards.stream()
                .map(VariantAssignerLegacyParityTest::shard)
                .toList();
    }

    private static Shard shard(final int shardId) {
        return new Shard(shardId, Instant.parse("2026-04-12T10:15:30Z"));
    }

    private static List<Variant> variants(final List<Integer> variantWeights) {
        final List<Variant> variants = new ArrayList<>();
        int variantId = 2;
        for (final Integer weight : variantWeights) {
            variants.add(variant(variantId++, weight));
        }
        return variants;
    }

    private static Set<Integer> toShardSet(
            final int totalNumberOfShards,
            final List<Integer> shardCandidates) {
        final Set<Integer> shards = new HashSet<>();
        for (final Integer shardCandidate : shardCandidates) {
            if (shardCandidate != null && shardCandidate <= totalNumberOfShards) {
                shards.add(shardCandidate);
            }
        }
        return shards;
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

    private interface BucketFunction {
        int bucketFromHash(int hashAsInt, int numberOfBuckets);
    }

    private static final class TestAlgorithm implements Algorithm {
    }
}
