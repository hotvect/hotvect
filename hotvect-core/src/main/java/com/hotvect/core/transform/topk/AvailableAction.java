package com.hotvect.core.transform.topk;

import com.hotvect.core.transform.Computable;

import java.util.Map;
import java.util.Objects;

/**
 * @deprecated Legacy TopK compatibility wrapper. Prefer implementing
 * {@link com.hotvect.api.algorithms.TopK} directly in new code.
 */
@Deprecated(forRemoval = true, since = "10.33.0")
public record AvailableAction<ACTION>(
        String actionId,
        Computable<ACTION> action,
        Map<String, Object> additionalProperties
) {
    public AvailableAction {
        Objects.requireNonNull(additionalProperties, "additionalProperties cannot be null");
    }

    public AvailableAction(String actionId, Computable<ACTION> action) {
        this(actionId, action, Map.of());
    }
}
