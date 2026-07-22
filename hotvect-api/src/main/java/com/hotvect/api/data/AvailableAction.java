package com.hotvect.api.data;

import java.util.Map;
import java.util.Objects;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Available action plus the stable action id and per-action metadata used for ranking, scoring,
 * transformation, and tie-breaking.
 *
 * @param actionId stable action id, unique within a request
 * @param action action payload
 * @param additionalProperties per-action metadata to propagate through downstream decisions
 * @param <ACTION> action payload type
 */
public record AvailableAction<ACTION>(
        String actionId,
        ACTION action,
        Map<String, Object> additionalProperties
) {
    public AvailableAction {
        checkArgument(actionId != null && !actionId.isBlank(), "actionId cannot be null or blank");
        Objects.requireNonNull(additionalProperties, "additionalProperties cannot be null");
    }

    public AvailableAction(String actionId, ACTION action) {
        this(actionId, action, Map.of());
    }

    public static <ACTION> AvailableAction<ACTION> of(String actionId, ACTION action) {
        return new AvailableAction<>(actionId, action);
    }

    public static <ACTION> AvailableAction<ACTION> of(
            String actionId,
            ACTION action,
            Map<String, Object> additionalProperties
    ) {
        return new AvailableAction<>(actionId, action, additionalProperties);
    }
}
