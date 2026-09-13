package com.hotvect.onlineutils.serving;

import java.util.Set;

/** Operational variant metadata for one EMS slot in the active generation. */
public record ServingSlotAssignment(
        boolean configuredRoot,
        String defaultVariantId,
        Set<String> activeVariantIds) {

    /** Validates and freezes one slot's assignment metadata. */
    public ServingSlotAssignment {
        activeVariantIds = Set.copyOf(activeVariantIds);
        if (activeVariantIds.isEmpty()) {
            throw new IllegalArgumentException("activeVariantIds must not be empty");
        }
        if (!activeVariantIds.contains(defaultVariantId)) {
            throw new IllegalArgumentException("activeVariantIds must contain defaultVariantId");
        }
    }
}
