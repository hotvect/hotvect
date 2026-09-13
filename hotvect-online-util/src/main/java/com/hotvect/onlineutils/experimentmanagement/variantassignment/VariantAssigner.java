package com.hotvect.onlineutils.experimentmanagement.variantassignment;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.hotvect.onlineutils.experimentmanagement.models.Experiment;
import com.hotvect.onlineutils.experimentmanagement.models.Shard;
import com.hotvect.onlineutils.experimentmanagement.models.Slot;
import com.hotvect.onlineutils.experimentmanagement.models.UserForcedAssignment;
import com.hotvect.onlineutils.experimentmanagement.models.Variant;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable, precomputed request-time assignment state for one validated EMS slot. */
public final class VariantAssigner {
    @SuppressWarnings("deprecation")
    private static final HashFunction MD5 = Hashing.md5();

    private final String slotSalt;
    private final int totalNumberOfShards;
    private final Variant defaultVariant;
    private final Map<Integer, Variant> variants;
    private final Map<Integer, ExperimentAssignment> experimentsByShard;
    private final Map<String, Integer> forcedVariantIds;

    public VariantAssigner(Slot slot) {
        Slot resolvedSlot = Objects.requireNonNull(slot, "slot must not be null");
        if (resolvedSlot.slotSalt() == null || resolvedSlot.slotSalt().isBlank()) {
            throw new IllegalStateException("slotSalt must not be blank");
        }
        if (resolvedSlot.totalNumberOfShards() <= 0) {
            throw new IllegalStateException("totalNumberOfShards must be positive");
        }
        slotSalt = resolvedSlot.slotSalt();
        totalNumberOfShards = resolvedSlot.totalNumberOfShards();
        defaultVariant = resolvedSlot.defaultVariant();

        TreeMap<Integer, Variant> resolvedVariants = new TreeMap<>();
        addVariant(resolvedVariants, defaultVariant);
        TreeMap<Integer, ExperimentAssignment> experimentsById = new TreeMap<>();
        TreeMap<Integer, ExperimentAssignment> resolvedExperimentsByShard = new TreeMap<>();
        for (Experiment experiment : resolvedSlot.experiments()) {
            ExperimentAssignment assignment = new ExperimentAssignment(experiment);
            if (experimentsById.putIfAbsent(experiment.experimentId(), assignment) != null) {
                throw new IllegalStateException("Duplicate experiment id " + experiment.experimentId());
            }
            assignment.variants().forEach(variant -> addVariant(resolvedVariants, variant));
            for (Shard shard : experiment.shards()) {
                if (shard.shardId() < 1 || shard.shardId() > totalNumberOfShards) {
                    throw new IllegalStateException(
                            "Experiment " + experiment.experimentId() + " shard " + shard.shardId()
                                    + " must be within [1, " + totalNumberOfShards + "]");
                }
                ExperimentAssignment previous = resolvedExperimentsByShard.putIfAbsent(shard.shardId(), assignment);
                if (previous != null) {
                    throw new IllegalStateException(
                            "Shard " + shard.shardId() + " is assigned to multiple experiments: "
                                    + previous.experiment().experimentId() + " and " + experiment.experimentId());
                }
            }
        }

        TreeMap<String, Integer> resolvedForcedVariantIds = new TreeMap<>();
        for (UserForcedAssignment forced : resolvedSlot.userForcedAssignments()) {
            String userId = Objects.requireNonNull(forced.userId(), "forced assignment userId must not be null");
            Integer variantId = Objects.requireNonNull(
                    forced.variantId(), "forced assignment variantId must not be null");
            if (resolvedForcedVariantIds.putIfAbsent(userId, variantId) != null) {
                throw new IllegalStateException("Duplicate user forced assignment for user " + userId);
            }
            if (!resolvedVariants.containsKey(variantId)) {
                throw new IllegalStateException(
                        "User forced assignment for " + userId + " references unknown variant " + variantId);
            }
        }
        variants = Collections.unmodifiableMap(resolvedVariants);
        experimentsByShard = Map.copyOf(resolvedExperimentsByShard);
        forcedVariantIds = Map.copyOf(resolvedForcedVariantIds);
    }

    /** Returns every variant addressable by assignment in stable variant-id order. */
    public Map<Integer, Variant> variants() {
        return variants;
    }

    /** Returns the ID of the default variant retained by this assignment snapshot. */
    public int defaultVariantId() {
        return defaultVariant.variantId();
    }

    /** Assigns one non-blank stable key using the established EMS hash contract. */
    public Variant assign(String assignmentKey) {
        if (assignmentKey == null || assignmentKey.isBlank()) {
            throw new IllegalArgumentException("assignmentKey must not be blank");
        }
        Integer forcedVariantId = forcedVariantIds.get(assignmentKey);
        if (forcedVariantId != null) {
            return variants.get(forcedVariantId);
        }
        ExperimentAssignment experiment = experimentsByShard.get(
                bucket(randomizationKey(assignmentKey), totalNumberOfShards) + 1);
        if (experiment == null) {
            return defaultVariant;
        }
        Variant selected = experiment.choose(
                bucket(
                        randomizationKey(assignmentKey, String.valueOf(experiment.experiment().experimentId())),
                        experiment.totalAllocation()));
        if (experiment.experiment().rampUpPercentage() == 100) {
            return selected;
        }
        int rampUpBucket = bucket(
                randomizationKey(
                        assignmentKey,
                        String.valueOf(experiment.experiment().experimentId()),
                        "ramp-up"),
                100);
        return rampUpBucket < experiment.experiment().rampUpPercentage() ? selected : defaultVariant;
    }

    static Variant chooseVariantByAllocationBucket(int bucket, List<Variant> variants) {
        List<Variant> sorted = variants.stream()
                .sorted(Comparator.comparingInt(Variant::variantId))
                .toList();
        return chooseVariantByAllocationBucket(bucket, sorted, totalAllocation(0, sorted), 0);
    }

    static int bucketFromHash(int hashAsInt, int numberOfBuckets) {
        if (numberOfBuckets <= 0) {
            throw new IllegalStateException("numberOfBuckets must be positive");
        }
        return Math.abs(hashAsInt) % numberOfBuckets;
    }

    private String randomizationKey(String assignmentKey, String... suffixes) {
        StringBuilder key = new StringBuilder(slotSalt);
        for (String suffix : suffixes) {
            key.append(suffix);
        }
        return key.append(assignmentKey).toString();
    }

    private static int bucket(String randomizationKey, int numberOfBuckets) {
        return bucketFromHash(
                MD5.hashString(randomizationKey, StandardCharsets.UTF_8).asInt(),
                numberOfBuckets);
    }

    private static void addVariant(Map<Integer, Variant> variants, Variant variant) {
        Variant previous = variants.putIfAbsent(variant.variantId(), variant);
        if (previous != null) {
            throw new IllegalStateException("Duplicate variant id " + variant.variantId());
        }
    }

    private record ExperimentAssignment(
            Experiment experiment,
            List<Variant> variants,
            int totalAllocation) {
        private ExperimentAssignment(Experiment experiment) {
            this(
                    Objects.requireNonNull(experiment, "experiment must not be null"),
                    experiment.variants().stream()
                            .sorted(Comparator.comparingInt(Variant::variantId))
                            .toList(),
                    totalAllocation(experiment));
        }

        private ExperimentAssignment {
            if (experiment.rampUpPercentage() < 0 || experiment.rampUpPercentage() > 100) {
                throw new IllegalStateException(
                        "Experiment " + experiment.experimentId() + " ramp-up percentage must be within [0, 100]");
            }
        }

        private Variant choose(int allocationBucket) {
            return chooseVariantByAllocationBucket(
                    allocationBucket,
                    variants,
                    totalAllocation,
                    experiment.experimentId());
        }

        private static int totalAllocation(Experiment experiment) {
            return VariantAssigner.totalAllocation(experiment.experimentId(), experiment.variants());
        }
    }

    private static Variant chooseVariantByAllocationBucket(
            int allocationBucket,
            List<Variant> variants,
            int totalAllocation,
            int experimentId) {
        if (allocationBucket < 0 || allocationBucket >= totalAllocation) {
            throw new IllegalStateException(
                    "allocationBucket %s must be within [0, %s)"
                            .formatted(allocationBucket, totalAllocation));
        }
        int boundary = 0;
        for (Variant variant : variants) {
            boundary += variant.shardAllocationRatio();
            if (allocationBucket < boundary) {
                return variant;
            }
        }
        throw new IllegalStateException("Could not assign experiment variant " + experimentId);
    }

    private static int totalAllocation(int experimentId, List<Variant> variants) {
        int total = 0;
        for (Variant variant : variants) {
            Integer ratio = variant.shardAllocationRatio();
            if (ratio == null || ratio <= 0) {
                throw new IllegalStateException(
                        "Variant " + variant.variantId() + " has invalid shard allocation ratio " + ratio);
            }
            total += ratio;
        }
        if (total <= 0) {
            throw new IllegalStateException("Experiment " + experimentId + " has no allocated traffic");
        }
        return total;
    }
}
