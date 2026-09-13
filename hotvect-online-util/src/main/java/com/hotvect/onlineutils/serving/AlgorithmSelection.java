package com.hotvect.onlineutils.serving;

import com.hotvect.api.algodefinition.AlgorithmRuntimeId;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** The exact EMS slot and algorithm assignments selected for one invocation. */
public record AlgorithmSelection(
        String rootSlot,
        AlgorithmRuntimeId runtimeId,
        Map<String, SlotAssignment> assignments) {

    public AlgorithmSelection {
        if (rootSlot == null || rootSlot.isBlank()) {
            throw new IllegalArgumentException("rootSlot must not be blank");
        }
        runtimeId = Objects.requireNonNull(runtimeId, "runtimeId must not be null");
        assignments = Collections.unmodifiableMap(
                new TreeMap<>(Objects.requireNonNull(assignments, "assignments must not be null")));
        if (!assignments.containsKey(rootSlot)) {
            throw new IllegalArgumentException("assignments must contain root slot " + rootSlot);
        }
    }

    static AlgorithmSelection from(String rootSlot, PreparedComposition composition) {
        return new AlgorithmSelection(
                rootSlot,
                composition.rootGraph().runtimeId(),
                composition.assignments());
    }
}
