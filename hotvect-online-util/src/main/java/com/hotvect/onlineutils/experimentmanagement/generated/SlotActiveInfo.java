// Generated EMS client binding. Do not edit manually.


package com.hotvect.onlineutils.experimentmanagement.generated;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

/** Wire model generated from the pinned direct Stage 2 EMS contract. */
public record SlotActiveInfo(
        @JsonProperty(value = "slot_salt", required = true) String slotSalt,
        @JsonProperty(value = "total_number_of_shards", required = true) Integer totalNumberOfShards,
        @JsonProperty(value = "default_variant", required = true) SlotActiveInfoVariantResponse defaultVariant,
        @JsonProperty(value = "experiments", required = true) List<SlotActiveInfoExperimentResponse> experiments,
        @JsonProperty(value = "user_forced_assignments", required = true) List<SlotActiveInfoUserForcedAssignmentResponse> userForcedAssignments
) {
    public SlotActiveInfo {
        slotSalt = Objects.requireNonNull(slotSalt, "slotSalt must not be null");
        totalNumberOfShards = Objects.requireNonNull(totalNumberOfShards, "totalNumberOfShards must not be null");
        defaultVariant = Objects.requireNonNull(defaultVariant, "defaultVariant must not be null");
        experiments = List.copyOf(Objects.requireNonNull(experiments, "experiments must not be null"));
        userForcedAssignments = List.copyOf(Objects.requireNonNull(userForcedAssignments, "userForcedAssignments must not be null"));
    }
}
