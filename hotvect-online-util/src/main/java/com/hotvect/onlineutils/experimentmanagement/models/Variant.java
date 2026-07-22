package com.hotvect.onlineutils.experimentmanagement.models;

import java.time.Instant;

public record Variant(
        int variantId,
        AlgorithmMetadata algorithm,
        Instant createdAt,
        Boolean isControl,
        Boolean isDefault,
        Integer shardAllocationRatio) {
}
