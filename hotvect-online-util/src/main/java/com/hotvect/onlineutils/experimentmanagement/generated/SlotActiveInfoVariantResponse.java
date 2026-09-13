// Generated EMS client binding. Do not edit manually.


package com.hotvect.onlineutils.experimentmanagement.generated;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Objects;

/** Wire model generated from the pinned direct Stage 2 EMS contract. */
public record SlotActiveInfoVariantResponse(
        @JsonProperty(value = "variant_id", required = true) Integer variantId,
        @JsonProperty(value = "algorithm", required = true) SlotActiveInfoAlgorithmResponse algorithm,
        @JsonProperty(value = "created_at", required = true) Instant createdAt,
        @JsonProperty(value = "is_default") Boolean isDefault,
        @JsonProperty(value = "is_control") Boolean isControl,
        @JsonProperty(value = "shard_allocation_ratio") Integer shardAllocationRatio
) {
    public SlotActiveInfoVariantResponse {
        variantId = Objects.requireNonNull(variantId, "variantId must not be null");
        algorithm = Objects.requireNonNull(algorithm, "algorithm must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
