package com.hotvect.api.data;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableMap;
import com.hotvect.api.data.featurestore.FeatureStoreResponse;
import java.util.Map;

/**
 * Container for feature store responses per view names.
 */
public record FeatureStoreResponseContainer(Map<String, FeatureStoreResponse> featureStoreResponses) {

    private static final FeatureStoreResponseContainer EMPTY = new FeatureStoreResponseContainer(ImmutableMap.of());

    /**
     * Enforcing non-null map for fail-fast semantics.
     *
     * @param featureStoreResponses the map of view names to feature store responses (must be non-null)
     * @throws IllegalArgumentException if featureStoreResponses is null
     */
    public FeatureStoreResponseContainer {
        checkArgument(featureStoreResponses != null, "featureStoreResponses cannot be null");
    }
    
    public static FeatureStoreResponseContainer empty(){
        return EMPTY;
    }
}
