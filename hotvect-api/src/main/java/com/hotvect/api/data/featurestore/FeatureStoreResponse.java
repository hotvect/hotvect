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
     * If the entire lookup request failed (e.g. connection error), this is present.
     * Otherwise, empty().
     */
    Optional<String> getRequestFailure();

    /**
     * Returns true if—and only if—there was no request‐level failure.
     */
    default boolean isSuccess() {
        return getRequestFailure().isEmpty();
    }

}