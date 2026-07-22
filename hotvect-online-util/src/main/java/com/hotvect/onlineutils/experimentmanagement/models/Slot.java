package com.hotvect.onlineutils.experimentmanagement.models;

import java.util.List;
import java.util.Objects;

public record Slot(
        String slotSalt,
        int totalNumberOfShards,
        Variant defaultVariant,
        List<Experiment> experiments,
        List<UserForcedAssignment> userForcedAssignments) {
    public Slot {
        defaultVariant = Objects.requireNonNull(defaultVariant, "defaultVariant must not be null");
        experiments = List.copyOf(experiments == null ? List.of() : experiments);
        userForcedAssignments = List.copyOf(userForcedAssignments == null ? List.of() : userForcedAssignments);
    }
}
