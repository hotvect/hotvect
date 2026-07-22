package com.hotvect.onlineutils.experimentmanagement.models;

import com.google.common.collect.ImmutableMap;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable per-slot serving snapshot used for local variant assignment.
 * <p>
 * This contains the refreshable experimentation state loaded from EMS. Static slot
 * configuration such as {@code totalNumberOfShards} is intentionally kept outside this
 * record and treated as immutable slot configuration.
 */
public record ExperimentationState(
        Integer maxVariantID,
        Instant updatedAt,
        ImmutableMap<Integer, ExperimentConfiguration> shardId2ExperimentConfiguration,
        ImmutableMap<Integer, VariantConfiguration> variantId2VariantConfiguration,
        ImmutableMap<String, Integer> userForcedAssignments,
        String slotSalt,
        VariantConfiguration defaultVariantConfiguration) {
    public ExperimentationState {
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        shardId2ExperimentConfiguration = ImmutableMap.copyOf(Objects.requireNonNull(
                shardId2ExperimentConfiguration, "shardId2ExperimentConfiguration must not be null"));
        variantId2VariantConfiguration = ImmutableMap.copyOf(Objects.requireNonNull(
                variantId2VariantConfiguration, "variantId2VariantConfiguration must not be null"));
        userForcedAssignments = ImmutableMap.copyOf(Objects.requireNonNull(
                userForcedAssignments, "userForcedAssignments must not be null"));
        slotSalt = Objects.requireNonNull(slotSalt, "slotSalt must not be null");
        defaultVariantConfiguration = Objects.requireNonNull(
                defaultVariantConfiguration, "defaultVariantConfiguration must not be null");
    }

    public ExperimentationState(
            final Integer maxVariantID,
            final ImmutableMap<Integer, ExperimentConfiguration> shardId2ExperimentConfiguration,
            final ImmutableMap<Integer, VariantConfiguration> variantId2VariantConfiguration,
            final ImmutableMap<String, Integer> userForcedAssignments,
            final String slotSalt,
            final VariantConfiguration defaultVariantConfiguration) {
        this(
                maxVariantID,
                Instant.now(),
                shardId2ExperimentConfiguration,
                variantId2VariantConfiguration,
                userForcedAssignments,
                slotSalt,
                defaultVariantConfiguration
        );
    }
}
