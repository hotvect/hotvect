package com.hotvect.onlineutils.serving;

import com.hotvect.api.algodefinition.ParameterizedAlgorithmId;

/** The complete EMS variant and algorithm selection for one slot on one request. */
public record SlotAssignment(
        String variantId,
        ParameterizedAlgorithmId algorithm) {

    public SlotAssignment {
        java.util.Objects.requireNonNull(algorithm, "algorithm must not be null");
    }
}
