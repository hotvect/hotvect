package com.hotvect.api.data.topk;

import com.hotvect.api.transformation.Computing;

import java.util.Map;
import java.util.Objects;

/**
 * @deprecated Use com.hotvect.core.transform.topk.AvailableAction instead
 */
@Deprecated(forRemoval = true, since = "9.27.0")
public record AvailableAction<ACTION>(
        String actionId,
        Computing<ACTION> action,
        Map<String, Object> additionalProperties
) {
    public AvailableAction {
        Objects.requireNonNull(additionalProperties, "additionalProperties cannot be null");
    }

    public AvailableAction(String actionId, Computing<ACTION> action) {
        this(actionId, action, Map.of());
    }
}
