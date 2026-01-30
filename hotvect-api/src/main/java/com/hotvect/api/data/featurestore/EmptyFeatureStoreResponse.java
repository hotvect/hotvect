package com.hotvect.api.data.featurestore;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * A singleton implementation of {@link FeatureStoreResponse} that represents
 * a successful request that returned no data.
 */
public final class EmptyFeatureStoreResponse implements FeatureStoreResponse {

    /** The singleton instance. */
    public static final EmptyFeatureStoreResponse INSTANCE = new EmptyFeatureStoreResponse();

    // Private constructor to prevent other instantiation.
    private EmptyFeatureStoreResponse() {}

    @Override
    public Map<String, Object> getEntity(Map<String, Object> entityId) {
        return null; // No entities exist
    }

    @Override
    public Map<Map<String, Object>, Map<String, Object>> getAllEntities() {
        return Collections.emptyMap();
    }

    @Override
    public Optional<String> getRequestFailure() {
        return Optional.empty(); // This was a success
    }
}