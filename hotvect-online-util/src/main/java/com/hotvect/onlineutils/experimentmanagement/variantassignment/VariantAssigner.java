package com.hotvect.onlineutils.experimentmanagement.variantassignment;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.hotvect.onlineutils.experimentmanagement.models.Experiment;
import com.hotvect.onlineutils.experimentmanagement.models.ExperimentConfiguration;
import com.hotvect.onlineutils.experimentmanagement.models.ExperimentationState;
import com.hotvect.onlineutils.experimentmanagement.models.VariantConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VariantAssigner {
    private static final Logger LOG = LoggerFactory.getLogger(VariantAssigner.class);

    @SuppressWarnings("deprecation")
    private static final HashFunction MD5 = Hashing.md5();

    private VariantAssigner() {
    }

    public static VariantConfiguration assignVariant(
            final String customerNumber,
            final ExperimentationState experimentationState,
            final int totalNumberOfShards) {
        final VariantConfiguration forcedVariantConfiguration =
                chooseUserBasedForcedVariantConfiguration(customerNumber, experimentationState);
        if (forcedVariantConfiguration != null) {
            return forcedVariantConfiguration;
        }

        final VariantConfiguration defaultVariantConfiguration =
                experimentationState.defaultVariantConfiguration();
        final ExperimentConfiguration experimentConfiguration =
                chooseExperimentConfiguration(
                        getExperimentShardId(
                                buildRandomizationKey(customerNumber, experimentationState.slotSalt()),
                                totalNumberOfShards),
                        experimentationState);
        if (experimentConfiguration == null) {
            return defaultVariantConfiguration;
        }

        final Experiment experiment = experimentConfiguration.experiment();
        final VariantConfiguration chosenVariantConfiguration =
                chooseVariantConfiguration(
                        buildRandomizationKey(
                                customerNumber,
                                experimentationState.slotSalt(),
                                String.valueOf(experiment.experimentId())),
                        experimentConfiguration);

        final int rampUpPercentage = experiment.rampUpPercentage();
        if (rampUpPercentage == 100) {
            return chosenVariantConfiguration;
        }

        return chooseRampUpVariantConfiguration(
                rampUpPercentage,
                getRampUpBucketOfVariant(buildRandomizationKey(
                        customerNumber,
                        experimentationState.slotSalt(),
                        String.valueOf(experiment.experimentId()),
                        "ramp-up")),
                defaultVariantConfiguration,
                chosenVariantConfiguration);
    }

    private static VariantConfiguration chooseUserBasedForcedVariantConfiguration(
            final String customerNumber,
            final ExperimentationState experimentationState) {
        final Map<String, Integer> forcedUserToVariantIds = experimentationState.userForcedAssignments();
        if (!forcedUserToVariantIds.containsKey(customerNumber)) {
            return null;
        }

        final Integer forcedVariantId = forcedUserToVariantIds.get(customerNumber);
        final VariantConfiguration forcedVariantConfiguration =
                experimentationState.variantId2VariantConfiguration().get(forcedVariantId);
        if (forcedVariantConfiguration == null) {
            LOG.error(
                    "Specified variant {} for user forced assignment does not exist in the given state",
                    forcedVariantId);
        }
        return forcedVariantConfiguration;
    }

    private static int getExperimentShardId(final StringBuilder randomizationKey, final int totalNumberOfShards) {
        return getBucketFromRandomizationKey(randomizationKey, totalNumberOfShards) + 1;
    }

    private static ExperimentConfiguration chooseExperimentConfiguration(
            final int shardId,
            final ExperimentationState experimentationState) {
        return experimentationState.shardId2ExperimentConfiguration().get(shardId);
    }

    private static VariantConfiguration chooseVariantConfiguration(
            final StringBuilder randomizationKey,
            final ExperimentConfiguration experimentConfiguration) {
        final int totalAllocation = totalVariantAllocation(experimentConfiguration);
        final int allocationBucket = getBucketFromRandomizationKey(randomizationKey, totalAllocation);
        return chooseVariantByAllocationBucket(allocationBucket, experimentConfiguration);
    }

    static VariantConfiguration chooseVariantByAllocationBucket(
            final int allocationBucket,
            final ExperimentConfiguration experimentConfiguration) {
        final int totalAllocation = totalVariantAllocation(experimentConfiguration);
        checkState(allocationBucket >= 0 && allocationBucket < totalAllocation,
                "allocationBucket %s must be within [0, %s)",
                allocationBucket,
                totalAllocation);
        int allocationBoundary = 0;
        for (final VariantConfiguration variantConfiguration : experimentConfiguration.variants()) {
            allocationBoundary += shardAllocationRatio(variantConfiguration);
            if (allocationBucket < allocationBoundary) {
                return variantConfiguration;
            }
        }

        throw new IllegalStateException(
                "Failed to choose variant for experiment " + experimentConfiguration.experiment().experimentId());
    }

    private static StringBuilder buildRandomizationKey(
            final String customerNumber,
            final String slotSalt,
            final String... args) {
        final StringBuilder randomizationKey = new StringBuilder(slotSalt);
        for (final String arg : args) {
            randomizationKey.append(arg);
        }
        if (customerNumber != null) {
            randomizationKey.append(customerNumber);
        } else {
            // Null customer ids are treated as test traffic and assigned randomly.
            randomizationKey.append(ThreadLocalRandom.current().nextLong());
        }
        return randomizationKey;
    }

    private static int getBucketFromRandomizationKey(
            final StringBuilder randomizationKey,
            final int numberOfBuckets) {
        final int hashAsInt = MD5.hashString(randomizationKey.toString(), StandardCharsets.UTF_8).asInt();
        return bucketFromHash(hashAsInt, numberOfBuckets);
    }

    static int bucketFromHash(final int hashAsInt, final int numberOfBuckets) {
        checkState(numberOfBuckets > 0, "numberOfBuckets must be positive");
        return Math.abs(hashAsInt) % numberOfBuckets;
    }

    private static int totalVariantAllocation(
            final ExperimentConfiguration experimentConfiguration) {
        int totalAllocation = 0;
        for (final VariantConfiguration variantConfiguration : experimentConfiguration.variants()) {
            totalAllocation += shardAllocationRatio(variantConfiguration);
        }
        checkState(totalAllocation > 0,
                "Experiment %s has no allocated traffic",
                experimentConfiguration.experiment().experimentId());
        return totalAllocation;
    }

    private static int shardAllocationRatio(
            final VariantConfiguration variantConfiguration) {
        final Integer shardRatio = variantConfiguration.variant().shardAllocationRatio();
        checkState(shardRatio != null && shardRatio > 0,
                "Variant %s has invalid shard allocation ratio %s",
                variantConfiguration.variant().variantId(),
                shardRatio);
        return shardRatio;
    }

    private static int getRampUpBucketOfVariant(final StringBuilder randomizationKey) {
        return getBucketFromRandomizationKey(randomizationKey, 100);
    }

    private static VariantConfiguration chooseRampUpVariantConfiguration(
            final int rampUpPercentage,
            final int rampUpBucketOfVariant,
            final VariantConfiguration defaultVariantConfiguration,
            final VariantConfiguration chosenVariantConfiguration) {
        if (rampUpBucketOfVariant >= rampUpPercentage) {
            return defaultVariantConfiguration;
        }
        return chosenVariantConfiguration;
    }
}
