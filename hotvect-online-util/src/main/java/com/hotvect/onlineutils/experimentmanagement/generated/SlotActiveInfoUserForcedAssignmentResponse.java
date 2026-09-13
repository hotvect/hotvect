// Generated EMS client binding. Do not edit manually.


package com.hotvect.onlineutils.experimentmanagement.generated;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/** Wire model generated from the pinned direct Stage 2 EMS contract. */
public record SlotActiveInfoUserForcedAssignmentResponse(
        @JsonProperty(value = "user_id", required = true) String userId,
        @JsonProperty(value = "variant_id", required = true) Integer variantId
) {
    public SlotActiveInfoUserForcedAssignmentResponse {
        userId = Objects.requireNonNull(userId, "userId must not be null");
        variantId = Objects.requireNonNull(variantId, "variantId must not be null");
    }
}
