package com.hotvect.api.data.featurestore;

import java.util.*;

/**
 * A response representing a failed feature store request, with all maps empty and only requestFailure set.
 */
public final class FailedRequestFeatureStoreResponse implements FeatureStoreResponse {
    private final Map<Map<String, Object>, Map<String, Object>> allEntities = Collections.emptyMap();
    private final String requestFailure;

    public FailedRequestFeatureStoreResponse(String requestFailure) {
        this.requestFailure = requestFailure;
    }

    /**
     * Creates a FailedRequestFeatureStoreResponse with the given failure reason.
     *
     * @param failureReason the reason for the request failure
     * @return a new FailedRequestFeatureStoreResponse
     */
    public static FailedRequestFeatureStoreResponse withFailure(String failureReason) {
        return new FailedRequestFeatureStoreResponse(failureReason);
    }

    @Override
    public Map<Map<String, Object>, Map<String, Object>> getAllEntities() {
        return allEntities;
    }

    @Override
    public Optional<String> getRequestFailure() {
        return Optional.ofNullable(requestFailure);
    }

    @Override
    public Map<String, Object> getEntity(Map<String, Object> entityId) {
        return null;
    }
}
