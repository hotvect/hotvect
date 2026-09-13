// Generated EMS client binding. Do not edit manually.


package com.hotvect.onlineutils.experimentmanagement.generated;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Objects;

/** Wire model generated from the pinned direct Stage 2 EMS contract. */
public record SlotActiveInfoShardResponse(
        @JsonProperty(value = "shard_id", required = true) Integer shardId,
        @JsonProperty(value = "created_at", required = true) Instant createdAt
) {
    public SlotActiveInfoShardResponse {
        shardId = Objects.requireNonNull(shardId, "shardId must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
