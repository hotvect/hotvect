// Generated EMS client binding. Do not edit manually.


package com.hotvect.onlineutils.experimentmanagement.generated;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Wire model generated from the pinned direct Stage 2 EMS contract. */
public record SlotActiveInfoExperimentResponse(
        @JsonProperty(value = "experiment_id", required = true) Integer experimentId,
        @JsonProperty(value = "experiment_name", required = true) String experimentName,
        @JsonProperty(value = "variants", required = true) List<SlotActiveInfoVariantResponse> variants,
        @JsonProperty(value = "shards", required = true) List<SlotActiveInfoShardResponse> shards,
        @JsonProperty(value = "ramp_up_percentage", required = true) Integer rampUpPercentage,
        @JsonProperty(value = "created_at", required = true) Instant createdAt
) {
    public SlotActiveInfoExperimentResponse {
        experimentId = Objects.requireNonNull(experimentId, "experimentId must not be null");
        experimentName = Objects.requireNonNull(experimentName, "experimentName must not be null");
        variants = List.copyOf(Objects.requireNonNull(variants, "variants must not be null"));
        shards = List.copyOf(Objects.requireNonNull(shards, "shards must not be null"));
        rampUpPercentage = Objects.requireNonNull(rampUpPercentage, "rampUpPercentage must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
