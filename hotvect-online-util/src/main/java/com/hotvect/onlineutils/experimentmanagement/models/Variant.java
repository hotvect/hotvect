package com.hotvect.onlineutils.experimentmanagement.models;

import java.time.Instant;
import java.util.Objects;

public record Variant(
        int variantId,
        AlgorithmMetadata algorithm,
        Instant createdAt,
        Boolean isControl,
        Boolean isDefault,
        Integer shardAllocationRatio) {

    public Variant {
        Objects.requireNonNull(algorithm, "algorithm must not be null");
    }
}
