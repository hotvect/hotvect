package com.hotvect.api.data.featurestore;

import java.util.Map;
import java.util.Optional;

public interface FeatureStoreResponse {
    /**
     * Return the feature‐set for a given entityId, or null if that ID failed or was not found.
     * Each vector is a {@code Map<String,Object>} (featureName → featureValue).
     */
    Map<String, Object> getEntity(Map<String, Object> entityId);

    /**
     * Return all successfully fetched entities (ID → feature‐vector).
     */
    Map<Map<String, Object>, Map<String, Object>> getAllEntities();

    /**
     * Convenience for callers who “don’t care about failures”:
     *   getEntityOrDefault(id, Collections.emptyMap())
     */
    default Map<String, Object> getEntityOrDefault(Map<String, Object> entityId, Map<String, Object> defaultValue) {
        Map<String, Object> vector = getEntity(entityId);
        return (vector != null) ? vector : defaultValue;
    }

    /**
     * If any lookup request failed (e.g. connection error, partial failure), this is present.
     * Otherwise, empty().
     * <p>
     * Note: The presence of a failure does not imply that the response has no entities.
     * Partial failures are possible where some entities are successfully retrieved while
     * others fail. Use {@link #getAllEntities()} to check for successfully retrieved entities.
     */
    Optional<String> getRequestFailure();

    /**
     * Returns true if—and only if—there was no request‐level failure.
     * <p>
     * <b>Deprecated:</b> This method is misleading as it doesn't account for partial failures.
     * A response can have both successfully retrieved entities and failures. Instead of checking
     * this method, check {@link #getRequestFailure()} for failures and {@link #getAllEntities()}
     * for successfully retrieved data.
     *
     * @deprecated This method will be removed in a future version. Use {@link #getRequestFailure()}
     *             and {@link #getAllEntities()} to properly handle partial failures.
     */
    @Deprecated(forRemoval = true)
    default boolean isSuccess() {
        return getRequestFailure().isEmpty();
    }

}