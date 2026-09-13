package com.hotvect.onlineutils.serving;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Operational state of one locally activated serving runtime generation.
 *
 * <p>The runtime activates a complete snapshot locally. EMS reads that produced the snapshot
 * remain independent reads and are not an EMS transaction.</p>
 *
 * @param ready whether a complete generation is currently available for requests
 * @param generation process-local generation of the active snapshot, or zero before activation
 * @param activatedAt time the active snapshot was published, or {@code null} before activation
 * @param lastPreparationDuration preparation time of the active snapshot, or {@code null} before activation
 * @param assignments active slot and variant assignment metadata
 * @param lastFailure latest rejected complete-refresh snapshot, or {@code null} after a successful refresh
 */
public record ServingRuntimeStatus(
        boolean ready,
        long generation,
        Instant activatedAt,
        Duration lastPreparationDuration,
        Map<String, ServingSlotAssignment> assignments,
        ServingRefreshFailure lastFailure) {

    /** Freezes the operational view. */
    public ServingRuntimeStatus {
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
        assignments = Map.copyOf(Objects.requireNonNull(assignments, "assignments must not be null"));
        if (ready) {
            if (generation == 0
                    || activatedAt == null
                    || lastPreparationDuration == null
                    || assignments.values().stream().noneMatch(ServingSlotAssignment::configuredRoot)) {
                throw new IllegalArgumentException("Ready status must describe an active generation");
            }
        } else if (generation != 0
                || activatedAt != null
                || lastPreparationDuration != null
                || !assignments.isEmpty()) {
            throw new IllegalArgumentException("Unready status must not describe an active generation");
        }
    }
}
