package com.hotvect.api.data.ranking;

import com.google.common.collect.ImmutableMap;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.common.NamespacedRecord;

import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * A record representing a transformed action with the associated domain model,
 * an ACTION reference, and any additional properties.
 *
 * @param actionId stable action id, propagated from the ranking request
 * @param action the raw action
 * @param transformed transformed features for the action
 * @param additionalProperties additional per-action metadata
 * @param <ACTION> The type parameter for the action (e.g., enumeration or class)
 */
public record TransformedAction<ACTION>(
        String actionId,
        ACTION action,
        NamespacedRecord<Namespace, Object> transformed,
        Map<String, Object> additionalProperties
) {
    public TransformedAction {
        checkArgument(actionId == null || !actionId.isBlank(), "actionId cannot be blank");
    }

    /**
     * Retains the v9 constructor shape used by existing algorithm JARs.
     *
     * TODO: Remove after transformed-action ids are fully migrated.
     *
     * @deprecated Use {@link #TransformedAction(String, Object, NamespacedRecord, Map)} with a stable action id.
     */
    @Deprecated(forRemoval = true)
    public TransformedAction(
            ACTION action,
            NamespacedRecord<Namespace, Object> transformed,
            Map<String, Object> additionalProperties
    ) {
        this(null, action, transformed, additionalProperties);
    }

    /**
     * Retains the v9 constructor shape used by existing algorithm JARs.
     *
     * TODO: Remove after transformed-action ids are fully migrated.
     *
     * @deprecated Use {@link #TransformedAction(String, Object, NamespacedRecord, Map)} with a stable action id.
     */
    @Deprecated(forRemoval = true)
    public TransformedAction(
            ACTION action,
            NamespacedRecord<Namespace, Object> transformed
    ) {
        this(null, action, transformed, ImmutableMap.of());
    }

    /**
     * Retains the v9 factory shape used by existing algorithm source.
     *
     * TODO: Remove after transformed-action ids are fully migrated.
     *
     * @deprecated Use {@link #of(String, Object, NamespacedRecord)} with a stable action id.
     */
    @Deprecated(forRemoval = true)
    public static <ACTION> TransformedAction<ACTION> of(
            ACTION action,
            NamespacedRecord<Namespace, Object> transformed
    ) {
        return new TransformedAction<>(null, action, transformed, ImmutableMap.of());
    }

    /**
     * Retains the v9 factory shape used by existing algorithm source.
     *
     * TODO: Remove after transformed-action ids are fully migrated.
     *
     * @deprecated Use {@link #of(String, Object, NamespacedRecord, Map)} with a stable action id.
     */
    @Deprecated(forRemoval = true)
    public static <ACTION> TransformedAction<ACTION> of(
            ACTION action,
            NamespacedRecord<Namespace, Object> transformed,
            Map<String, Object> additionalProperties
    ) {
        return new TransformedAction<>(null, action, transformed, additionalProperties);
    }

    public static <ACTION> TransformedAction<ACTION> of(
            String actionId,
            ACTION action,
            NamespacedRecord<Namespace, Object> transformed
    ) {
        return new TransformedAction<>(actionId, action, transformed, ImmutableMap.of());
    }

    public static <ACTION> TransformedAction<ACTION> of(
            String actionId,
            ACTION action,
            NamespacedRecord<Namespace, Object> transformed,
            Map<String, Object> additionalProperties
    ) {
        return new TransformedAction<>(actionId, action, transformed, additionalProperties);
    }
}
